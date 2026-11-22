package id.tetuko.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.UUID

class AndroidLocalAccountStore(context: Context) : LocalAccountStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("tetuko_app_accounts", Context.MODE_PRIVATE)
    private val lock = Any()
    private val encryptedAccountsKey = "accounts_encrypted_v1"
    private val legacyAccountsKey = "accounts"
    private val keystoreAlias = "tetuko_app_accounts_aes_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun hasAccounts(): Boolean = synchronized(lock) { load().accounts.isNotEmpty() }

    override fun listAccounts(): List<LocalAppAccountSafe> = synchronized(lock) {
        load().accounts.sortedBy { it.username.lowercase() }.map { it.toSafe() }
    }

    override fun getAccount(id: String): LocalAppAccountSafe? = synchronized(lock) {
        load().accounts.firstOrNull { it.id == id }?.toSafe()
    }

    override fun findByUsername(username: String): LocalAppAccountSafe? = synchronized(lock) {
        val normalized = normalizeUsername(username)
        load().accounts.firstOrNull { normalizeUsername(it.username) == normalized }?.toSafe()
    }

    override fun authenticate(username: String, password: String): LocalAppAccountSafe? = synchronized(lock) {
        val normalized = normalizeUsername(username)
        val account = load().accounts.firstOrNull { normalizeUsername(it.username) == normalized } ?: return null
        if (!verifyPassword(password, account.passwordHash)) return null
        account.toSafe()
    }

    override fun ensureBootstrapAdmin(defaultUsername: String, defaultPassword: String): Boolean = synchronized(lock) {
        val data = load()
        if (data.accounts.isNotEmpty()) return false
        val now = System.currentTimeMillis()
        val admin = LocalAppAccount(
            id = UUID.randomUUID().toString(),
            username = normalizeUsername(defaultUsername).ifBlank { "admin" },
            passwordHash = hashPassword(defaultPassword),
            role = AppRole.ADMIN,
            permissions = AppPermissions.AdminFull,
            serverCreds = emptyMap(),
            createdAtMs = now,
            updatedAtMs = now,
        )
        save(data.copy(accounts = listOf(admin)))
        prefs.edit().putBoolean("default_admin_pending", true).commit()
        true
    }

    override fun createAccount(
        username: String,
        password: String,
        role: AppRole,
        permissions: AppPermissions,
        serverCreds: Map<String, LocalServerCredential>,
    ): LocalAppAccountSafe = synchronized(lock) {
        val normalized = normalizeUsername(username)
        require(normalized.isNotBlank()) { "Username wajib diisi." }
        require(password.isNotBlank()) { "Password wajib diisi." }
        val data = load()
        require(data.accounts.none { normalizeUsername(it.username) == normalized }) { "Username sudah digunakan." }
        val now = System.currentTimeMillis()
        val effectivePermissions = if (role == AppRole.ADMIN) {
            AppPermissions.AdminFull.copy(allowEditServerCreds = permissions.allowEditServerCreds)
        } else permissions.copy(role = AppRole.USER)
        val account = LocalAppAccount(
            id = UUID.randomUUID().toString(),
            username = normalized,
            passwordHash = hashPassword(password),
            role = role,
            permissions = effectivePermissions,
            serverCreds = serverCreds,
            createdAtMs = now,
            updatedAtMs = now,
        )
        save(data.copy(accounts = data.accounts + account))
        account.toSafe()
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
        val index = data.accounts.indexOfFirst { it.id == id }
        require(index >= 0) { "Akun tidak ditemukan." }
        val current = data.accounts[index]
        val nextUsername = username?.let(::normalizeUsername) ?: current.username
        require(nextUsername.isNotBlank()) { "Username wajib diisi." }
        require(data.accounts.none { it.id != id && normalizeUsername(it.username) == nextUsername }) { "Username sudah digunakan." }
        val nextRole = role ?: current.role
        val nextPermissions = when {
            permissions != null && nextRole == AppRole.ADMIN -> AppPermissions.AdminFull.copy(allowEditServerCreds = permissions.allowEditServerCreds)
            permissions != null -> permissions.copy(role = AppRole.USER)
            nextRole == AppRole.ADMIN -> AppPermissions.AdminFull
            else -> current.permissions.copy(role = AppRole.USER)
        }
        val updated = current.copy(
            username = nextUsername,
            passwordHash = newPassword?.takeIf { it.isNotBlank() }?.let(::hashPassword) ?: current.passwordHash,
            role = nextRole,
            permissions = nextPermissions,
            serverCreds = serverCreds ?: current.serverCreds,
            updatedAtMs = System.currentTimeMillis(),
        )
        val accounts = data.accounts.toMutableList().apply { set(index, updated) }
        save(data.copy(version = 2, accounts = accounts))
        updated.toSafe()
    }

    override fun deleteAccount(id: String): Boolean = synchronized(lock) {
        val data = load()
        val remaining = data.accounts.filterNot { it.id == id }
        if (remaining.size == data.accounts.size) return false
        save(data.copy(accounts = remaining))
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
        val index = data.accounts.indexOfFirst { it.id == accountId }
        require(index >= 0) { "Akun tidak ditemukan." }
        val current = data.accounts[index]
        val cleanAccounts = accounts.map { LocalServerCredential(it.username.trim(), it.password, it.imei.trim()) }
            .filterNot { it.username.isBlank() && it.password.isBlank() && it.imei.isBlank() }
            .distinctBy { it.username.lowercase() }
        val active = cleanAccounts.firstOrNull { it.username.equals(activeUsername, ignoreCase = true) }
            ?: cleanAccounts.firstOrNull()
        val legacy = current.serverCreds.toMutableMap()
        val all = current.serverCredAccounts.toMutableMap()
        val activeMap = current.activeServerCreds.toMutableMap()
        if (active == null) {
            legacy.remove(profileId)
            all.remove(profileId)
            activeMap.remove(profileId)
        } else {
            legacy[profileId] = active
            all[profileId] = cleanAccounts
            activeMap[profileId] = active.username
        }
        val updated = current.copy(serverCreds = legacy, serverCredAccounts = all, activeServerCreds = activeMap, updatedAtMs = System.currentTimeMillis())
        val accounts = data.accounts.toMutableList().apply { set(index, updated) }
        save(data.copy(version = 2, accounts = accounts))
        updated.toSafe()
    }

    override fun getLockState(): Pair<Int, Long> = synchronized(lock) {
        prefs.getInt("auth_failed_attempts", 0) to prefs.getLong("auth_cooldown_until", 0L)
    }

    override fun setLockState(attempts: Int, cooldownUntilMs: Long) {
        prefs.edit()
            .putInt("auth_failed_attempts", attempts)
            .putLong("auth_cooldown_until", cooldownUntilMs)
            .commit()
    }

    override fun isDefaultAdminPasswordPending(): Boolean = prefs.getBoolean("default_admin_pending", false)

    override fun clearDefaultAdminPasswordPending() {
        prefs.edit().putBoolean("default_admin_pending", false).commit()
    }

    override fun accountSourceLabel(): String = "Akun lokal Android (terproteksi hash)"

    override fun accountSourceError(): String? = null

    override fun isBackendConfigured(): Boolean = true

    private fun load(): LocalAppAccountStoreData {
        val encrypted = prefs.getString(encryptedAccountsKey, null)?.trim().orEmpty()
        if (encrypted.isNotBlank()) {
            val plaintext = decryptAccounts(encrypted)
            return json.decodeFromString<LocalAppAccountStoreData>(plaintext)
        }

        val raw = prefs.getString(legacyAccountsKey, null)?.trim().orEmpty()
        if (raw.isBlank()) return LocalAppAccountStoreData()
        val legacy = json.decodeFromString<LocalAppAccountStoreData>(raw)
        save(legacy.copy(version = 2))
        return legacy.copy(version = 2)
    }

    private fun save(data: LocalAppAccountStoreData) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, accountEncryptionKey())
        val encrypted = cipher.iv + cipher.doFinal(json.encodeToString(data).encodeToByteArray())
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(
            prefs.edit()
                .putString(encryptedAccountsKey, encoded)
                .remove(legacyAccountsKey)
                .commit(),
        ) { "Gagal menyimpan akun terenkripsi ke penyimpanan perangkat." }
    }

    private fun decryptAccounts(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES) { "Data akun terenkripsi tidak valid." }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, accountEncryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    private fun accountEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keystoreAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keystoreAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }

    private fun normalizeUsername(value: String): String = value.trim().lowercase().filter { it.isLetterOrDigit() || it in "_.-" }

    private fun hashPassword(password: String): String {
        val saltBytes = ByteArray(16).also(SecureRandom()::nextBytes)
        val salt = saltBytes.joinToString("") { "%02x".format(it) }
        val digest = sha256("$salt:$password")
        return "sha256v1:$salt:$digest"
    }

    private fun verifyPassword(password: String, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size != 3 || parts[0] != "sha256v1") return false
        val actual = sha256("${parts[1]}:$password")
        return MessageDigest.isEqual(actual.encodeToByteArray(), parts[2].encodeToByteArray())
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
