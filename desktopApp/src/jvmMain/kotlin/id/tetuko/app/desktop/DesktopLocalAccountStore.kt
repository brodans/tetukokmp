package id.tetuko.app.desktop

import at.favre.lib.crypto.bcrypt.BCrypt
import id.tetuko.app.service.LocalAccountStore
import id.tetuko.core.domain.model.AppPermissions
import id.tetuko.core.domain.model.AppRole
import id.tetuko.core.domain.model.LocalAppAccount
import id.tetuko.core.domain.model.LocalAppAccountSafe
import id.tetuko.core.domain.model.LocalAppAccountStoreData
import id.tetuko.core.domain.model.LocalServerCredential
import id.tetuko.core.domain.model.activeServerCredential
import id.tetuko.core.domain.model.serverCredentials
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Desktop-local account store (encrypted-at-rest via [DesktopSecureStore]).
 * Feasible adapter for Tauri account/permission management: local CRUD/assignment/auth
 * with bcrypt password hashes. Not cloud-synced/multi-device.
 */
class DesktopLocalAccountStore(
    private val sourceLabel: String? = "Akun lokal perangkat (terenkripsi)",
    private val sourceError: String? = null,
) : LocalAccountStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val lock = Any()
    private val key = "local_accounts_v1"
    private val keyAttempts = "auth_failed_attempts"
    private val keyCooldown = "auth_cooldown_until"
    private val keyDefaultAdminPending = "auth_default_admin_pending"

    override fun hasAccounts(): Boolean = synchronized(lock) { load().accounts.isNotEmpty() }

    override fun listAccounts(): List<LocalAppAccountSafe> = synchronized(lock) {
        load().accounts.sortedBy { it.username.lowercase() }.map { it.toSafe() }
    }

    override fun getAccount(id: String): LocalAppAccountSafe? = synchronized(lock) {
        load().accounts.firstOrNull { it.id == id }?.toSafe()
    }

    override fun findByUsername(username: String): LocalAppAccountSafe? = synchronized(lock) {
        val u = norm(username)
        load().accounts.firstOrNull { norm(it.username) == u }?.toSafe()
    }

    override fun authenticate(username: String, password: String): LocalAppAccountSafe? = synchronized(lock) {
        val u = norm(username)
        val account = load().accounts.firstOrNull { norm(it.username) == u } ?: return null
        if (!verifyPassword(password, account.passwordHash)) return null
        account.toSafe()
    }

    override fun ensureBootstrapAdmin(defaultUsername: String, defaultPassword: String): Boolean = synchronized(lock) {
        val data = load()
        if (data.accounts.isNotEmpty()) return false
        val now = System.currentTimeMillis()
        val admin = LocalAppAccount(
            id = UUID.randomUUID().toString(),
            username = norm(defaultUsername).ifBlank { "admin" },
            passwordHash = hashPassword(defaultPassword),
            role = AppRole.ADMIN,
            permissions = AppPermissions.AdminFull,
            serverCreds = emptyMap(),
            createdAtMs = now,
            updatedAtMs = now,
        )
        save(LocalAppAccountStoreData(version = 1, accounts = listOf(admin)))
        setDefaultAdminPending(true)
        true
    }

    override fun createAccount(
        username: String,
        password: String,
        role: AppRole,
        permissions: AppPermissions,
        serverCreds: Map<String, LocalServerCredential>,
    ): LocalAppAccountSafe = synchronized(lock) {
        val u = norm(username)
        require(u.isNotBlank()) { "Username wajib diisi." }
        require(password.isNotBlank()) { "Password wajib diisi." }
        val data = load()
        require(data.accounts.none { norm(it.username) == u }) { "Username sudah digunakan." }
        val now = System.currentTimeMillis()
        val perms = if (role == AppRole.ADMIN) AppPermissions.AdminFull.copy(allowEditServerCreds = permissions.allowEditServerCreds) else permissions.copy(role = AppRole.USER)
        val acc = LocalAppAccount(
            id = UUID.randomUUID().toString(),
            username = u,
            passwordHash = hashPassword(password),
            role = role,
            permissions = perms,
            serverCreds = serverCreds,
            createdAtMs = now,
            updatedAtMs = now,
        )
        save(data.copy(accounts = data.accounts + acc))
        acc.toSafe()
    }

    override fun updateAccount(
        id: String,
        username: String?,
        newPassword: String?,
        role: AppRole?,
        permissions: AppPermissions?,
        serverCreds: Map<String, LocalServerCredential>?,
    ): LocalAppAccountSafe = synchronized(lock) {
        val data = load()
        val idx = data.accounts.indexOfFirst { it.id == id }
        require(idx >= 0) { "Akun tidak ditemukan." }
        val cur = data.accounts[idx]
        val newUsername = username?.let { norm(it) } ?: cur.username
        require(newUsername.isNotBlank()) { "Username wajib diisi." }
        require(data.accounts.none { it.id != id && norm(it.username) == newUsername }) { "Username sudah digunakan." }
        val nextRole = role ?: cur.role
        val nextPerms = when {
            permissions != null && nextRole == AppRole.ADMIN -> AppPermissions.AdminFull.copy(allowEditServerCreds = permissions.allowEditServerCreds)
            permissions != null -> permissions.copy(role = AppRole.USER)
            nextRole == AppRole.ADMIN -> AppPermissions.AdminFull
            else -> cur.permissions.copy(role = AppRole.USER)
        }
        val updated = cur.copy(
            username = newUsername,
            passwordHash = if (!newPassword.isNullOrBlank()) hashPassword(newPassword) else cur.passwordHash,
            role = nextRole,
            permissions = nextPerms,
            serverCreds = serverCreds ?: cur.serverCreds,
            updatedAtMs = System.currentTimeMillis(),
        )
        val next = data.accounts.toMutableList().also { it[idx] = updated }
        require(next.any { it.role == AppRole.ADMIN }) { "Minimal harus ada satu akun admin." }
        save(data.copy(accounts = next))
        if (updated.role == AppRole.ADMIN && !newPassword.isNullOrBlank()) clearDefaultAdminPasswordPending()
        updated.toSafe()
    }

    override fun deleteAccount(id: String): Boolean = synchronized(lock) {
        val data = load()
        val next = data.accounts.filterNot { it.id == id }
        if (next.size == data.accounts.size) return false
        require(next.any { it.role == AppRole.ADMIN }) { "Tidak dapat menghapus: minimal harus ada satu akun admin." }
        save(data.copy(accounts = next))
        true
    }

    override fun getServerCreds(accountId: String, profileId: String): LocalServerCredential? = synchronized(lock) {
        load().accounts.firstOrNull { it.id == accountId }?.activeServerCredential(profileId)
    }

    override fun getServerCredAccounts(accountId: String, profileId: String): List<LocalServerCredential> = synchronized(lock) {
        load().accounts.firstOrNull { it.id == accountId }?.serverCredentials(profileId).orEmpty()
    }

    override fun setServerCreds(accountId: String, profileId: String, creds: LocalServerCredential?): LocalAppAccountSafe {
        val existing = getServerCredAccounts(accountId, profileId)
        val next = if (creds == null) emptyList() else existing
            .filterNot { it.username.equals(creds.username, ignoreCase = true) } + creds
        return setServerCredAccounts(accountId, profileId, next, creds?.username)
    }

    override fun setServerCredAccounts(
        accountId: String,
        profileId: String,
        accounts: List<LocalServerCredential>,
        activeUsername: String?,
    ): LocalAppAccountSafe = synchronized(lock) {
        val data = load()
        val idx = data.accounts.indexOfFirst { it.id == accountId }
        require(idx >= 0) { "Akun tidak ditemukan." }
        val cur = data.accounts[idx]
        val cleanAccounts = accounts.map { LocalServerCredential(it.username.trim(), it.password, it.imei.trim()) }
            .filterNot { it.username.isBlank() && it.password.isBlank() && it.imei.isBlank() }
            .distinctBy { it.username.lowercase() }
        val active = cleanAccounts.firstOrNull { it.username.equals(activeUsername, ignoreCase = true) }
            ?: cleanAccounts.firstOrNull()
        val legacy = cur.serverCreds.toMutableMap()
        val all = cur.serverCredAccounts.toMutableMap()
        val activeMap = cur.activeServerCreds.toMutableMap()
        if (active == null) {
            legacy.remove(profileId)
            all.remove(profileId)
            activeMap.remove(profileId)
        } else {
            legacy[profileId] = active
            all[profileId] = cleanAccounts
            activeMap[profileId] = active.username
        }
        val updated = cur.copy(serverCreds = legacy, serverCredAccounts = all, activeServerCreds = activeMap, updatedAtMs = System.currentTimeMillis())
        val next = data.accounts.toMutableList().also { it[idx] = updated }
        save(data.copy(version = 2, accounts = next))
        updated.toSafe()
    }

    override fun isDefaultAdminPasswordPending(): Boolean =
        DesktopSecureStore.load()[keyDefaultAdminPending]?.toBooleanStrictOrNull() == true

    override fun clearDefaultAdminPasswordPending() = setDefaultAdminPending(false)

    override fun accountSourceLabel(): String? = sourceLabel

    override fun accountSourceError(): String? = sourceError

    override fun isBackendConfigured(): Boolean = sourceError == null

    private fun setDefaultAdminPending(pending: Boolean) {
        val cur = DesktopSecureStore.load().toMutableMap()
        cur[keyDefaultAdminPending] = pending.toString()
        DesktopSecureStore.save(cur)
    }

    override fun getLockState(): Pair<Int, Long> {
        val data = DesktopSecureStore.load()
        val attempts = data[keyAttempts]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val cooldown = data[keyCooldown]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        return attempts to cooldown
    }

    override fun setLockState(attempts: Int, cooldownUntilMs: Long) {
        val cur = DesktopSecureStore.load().toMutableMap()
        cur[keyAttempts] = attempts.coerceAtLeast(0).toString()
        cur[keyCooldown] = cooldownUntilMs.coerceAtLeast(0L).toString()
        DesktopSecureStore.save(cur)
    }

    private var recovering = false

    private fun load(): LocalAppAccountStoreData {
        val raw = DesktopSecureStore.load()[key] ?: return LocalAppAccountStoreData()
        val decoded = runCatching { json.decodeFromString(LocalAppAccountStoreData.serializer(), raw) }
        if (decoded.isSuccess) return decoded.getOrThrow()
        // Corrupt payload: bootstrap a fresh local admin once so the gate remains usable.
        // Best-effort and guarded against recursion (save() writes through DesktopSecureStore).
        if (!recovering) {
            recovering = true
            runCatching { ensureBootstrapAdmin() }
            recovering = false
        }
        return LocalAppAccountStoreData()
    }

    private fun save(data: LocalAppAccountStoreData) {
        val cur = DesktopSecureStore.load().toMutableMap()
        cur[key] = json.encodeToString(LocalAppAccountStoreData.serializer(), data)
        DesktopSecureStore.save(cur)
    }

    private fun norm(s: String): String = s.trim().lowercase().replace("\\s+".toRegex(), "")

    private fun hashPassword(password: String): String =
        BCrypt.withDefaults().hashToString(12, password.toCharArray())

    private fun verifyPassword(password: String, hash: String): Boolean = runCatching {
        if (hash.isBlank()) return false
        BCrypt.verifyer().verify(password.toCharArray(), hash).verified
    }.getOrDefault(false)
}
