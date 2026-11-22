package id.tetuko.app.desktop

import at.favre.lib.crypto.bcrypt.BCrypt
import id.tetuko.app.service.LocalAccountStore
import id.tetuko.core.domain.model.AppPermissions
import id.tetuko.core.domain.model.AppRole
import id.tetuko.core.domain.model.LocalAppAccountSafe
import id.tetuko.core.domain.model.LocalServerCredential
import id.tetuko.core.domain.model.activeServerCredential
import id.tetuko.core.domain.model.serverCredentials
import id.tetuko.core.domain.model.ProfileAccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Firestore-backed app account store (desktop/JVM) using Firestore REST API.
 *
 * - No Firebase Auth dependency (matches open rules/use-case).
 * - Reads/writes user/account data from Firestore (primary: tetuko_users).
 * - Passwords verified/stored as bcrypt hashes.
 * - Lockout is persisted locally per-device (best-effort), while accounts/permissions
 *   remain sourced from Firestore.
 *
 * Schema mapping is intentionally tolerant to support existing documents.
 */
class FirestoreAccountStore(
    private val config: FirebaseAppConfig,
) : LocalAccountStore {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
    private val legacyServerCredentialFields = listOf(
        "serverCreds", "server_creds", "serverCredentials", "server_credentials",
        "serverCredAccounts", "server_cred_accounts", "activeServerCreds", "active_server_creds",
    )

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 20_000
        }
        engine {
            config {
                connectTimeout(10, TimeUnit.SECONDS)
                readTimeout(20, TimeUnit.SECONDS)
                writeTimeout(20, TimeUnit.SECONDS)
            }
        }
    }

    private val lock = Any()
    private val projectId = config.projectId.trim()
    private val apiKey = config.apiKey.trim()
    private val primaryCollection = "tetuko_users"
    private val fallbackCollections = listOf("users", "settings")

    // Local per-device lockout/default-admin-pending flags (not account source of truth)
    private val localKeyAttempts = "auth_failed_attempts"
    private val localKeyCooldown = "auth_cooldown_until"
    private val localKeyDefaultAdminPending = "auth_default_admin_pending"

    override fun isAvailable(): Boolean = config.isConfigured

    override fun isBackendConfigured(): Boolean = config.isConfigured

    override fun accountSourceLabel(): String? = "Firebase/Firestore (akun & izin tersinkron)"

    override fun accountSourceError(): String? = FirebaseAppConfig.statusMessage(config)

    override fun hasAccounts(): Boolean = synchronized(lock) {
        ensureConfigured()
        listAllDocs().isNotEmpty()
    }

    override fun listAccounts(): List<LocalAppAccountSafe> = synchronized(lock) {
        ensureConfigured()
        listAllDocs().mapNotNull { it.toSafeAccountOrNull() }
            .distinctBy { it.id }
            .sortedBy { it.username.lowercase() }
    }

    override fun getAccount(id: String): LocalAppAccountSafe? = synchronized(lock) {
        ensureConfigured()
        if (id.isBlank()) return null
        // Fast path: try primary collection by doc id
        fetchDoc(primaryCollection, id)?.toSafeAccountOrNull()?.let { return it }
        // Search by mapped id/field across collections
        listAllDocs().firstOrNull { it.matchesId(id) }?.toSafeAccountOrNull()
    }

    override fun findByUsername(username: String): LocalAppAccountSafe? = synchronized(lock) {
        ensureConfigured()
        val u = norm(username)
        if (u.isBlank()) return null
        // Try structured query on primary first
        queryByUsername(primaryCollection, u)?.firstOrNull()?.toSafeAccountOrNull()?.let { return it }
        // Fallback: scan candidate collections
        listAllDocs().firstOrNull { norm(it.username) == u }?.toSafeAccountOrNull()
    }

    override fun authenticate(username: String, password: String): LocalAppAccountSafe? = synchronized(lock) {
        ensureConfigured()
        val u = norm(username)
        if (u.isBlank() || password.isBlank()) return null

        val adminAuth = fetchDoc("settings", "app_auth")
        val admin = parseAdminAuthDocument(adminAuth)
        if (admin != null && u == admin.username && verifyAdminPassword(password, admin)) {
            return LocalAppAccountSafe(
                id = "admin",
                username = admin.username,
                role = AppRole.ADMIN,
                permissions = AppPermissions.AdminFull,
                serverCreds = emptyMap(),
                createdAtMs = admin.updatedAt,
                updatedAtMs = admin.updatedAt,
            )
        }
        val doc = queryByUsername(primaryCollection, u)?.firstOrNull()
            ?: listAllDocs().firstOrNull { norm(it.username) == u }
            ?: return null
        val hash = doc.passwordHash
        if (hash.isBlank() || !verifyPassword(password, hash)) return null
        doc.toSafeAccountOrNull()
    }

    override fun ensureBootstrapAdmin(defaultUsername: String, defaultPassword: String): Boolean = synchronized(lock) {
        ensureConfigured()
        val existing = listAllDocs()
        if (existing.isNotEmpty()) return false
        val now = System.currentTimeMillis()
        val uname = norm(defaultUsername).ifBlank { "admin" }
        val fields = buildAccountFields(
            username = uname,
            passwordHash = hashPassword(defaultPassword),
            role = AppRole.ADMIN,
            permissions = AppPermissions.AdminFull,
            serverCreds = emptyMap(),
            createdAtMs = now,
            updatedAtMs = now,
            existing = null,
        )
        val docId = uname.trim()
        val ok = upsertDocument(primaryCollection, docId, fields, merge = false)
        if (ok) {
            val adminFields = encodeFieldsMap(mapOf(
                "adminUsername" to uname,
                "passwordHash" to hashPassword(defaultPassword),
                "pinHash" to LegacyAdminCredentialCodec.hashPinLayered(defaultPassword),
                "pinEncrypted" to LegacyAdminCredentialCodec.encrypt(defaultPassword, config.appSecret),
                "updatedAt" to now,
            ))
            upsertDocument("settings", "app_auth", adminFields, merge = true)
            setDefaultAdminPending(true)
        }
        ok
    }

    override fun createAccount(
        username: String,
        password: String,
        role: AppRole,
        permissions: AppPermissions,
        serverCreds: Map<String, LocalServerCredential>,
    ): LocalAppAccountSafe = synchronized(lock) {
        ensureConfigured()
        val u = norm(username)
        require(u.isNotBlank()) { "Username wajib diisi." }
        require(password.isNotBlank()) { "Password wajib diisi." }
        val all = listAllDocs()
        require(all.none { norm(it.username) == u }) { "Username sudah digunakan." }
        val now = System.currentTimeMillis()
        val perms = normalizePerms(role, permissions)
        val docId = preferredDocId(null, u)
        val fields = buildAccountFields(
            username = u,
            passwordHash = hashPassword(password),
            role = role,
            permissions = perms,
            serverCreds = sanitizeServerCredsMap(serverCreds),
            createdAtMs = now,
            updatedAtMs = now,
            existing = null,
        )
        val ok = upsertDocument(primaryCollection, docId, fields, merge = false)
        if (!ok) throw IllegalStateException("Gagal membuat akun di Firestore.")
        sanitizeServerCredsMap(serverCreds).forEach { (profileId, credential) ->
            check(saveTauriServerCredPayload(profileId, u, listOf(credential), credential.username)) {
                "Gagal menyimpan kredensial server terenkripsi."
            }
        }
        fetchDoc(primaryCollection, docId)?.toSafeAccountOrNull()
            ?: LocalAppAccountSafe(
                id = docId,
                username = u,
                role = role,
                permissions = perms,
                serverCreds = sanitizeServerCredsMap(serverCreds),
                createdAtMs = now,
                updatedAtMs = now,
            )
    }

    override fun updateAccount(
        id: String,
        username: String?,
        newPassword: String?,
        role: AppRole?,
        permissions: AppPermissions?,
        serverCreds: Map<String, LocalServerCredential>?,
    ): LocalAppAccountSafe = synchronized(lock) {
        ensureConfigured()
        val all = listAllDocs()
        val cur = all.firstOrNull { it.matchesId(id) }
            ?: throw IllegalArgumentException("Akun tidak ditemukan.")
        val newUsername = username?.let { norm(it) } ?: cur.username
        require(newUsername.isNotBlank()) { "Username wajib diisi." }
        require(all.none { it.docId != cur.docId && norm(it.username) == newUsername }) {
            "Username sudah digunakan."
        }
        val nextRole = role ?: cur.role
        val nextPerms = normalizePerms(nextRole, permissions ?: if (role != null && role == AppRole.ADMIN) AppPermissions.AdminFull else cur.permissions)
        val nextCreds = serverCreds?.let { sanitizeServerCredsMap(it) } ?: cur.serverCreds
        val now = System.currentTimeMillis()
        val nextHash = if (!newPassword.isNullOrBlank()) hashPassword(newPassword) else cur.passwordHash

        // Validate "at least one admin remains" against effective state
        val effective = all.map {
            if (it.docId == cur.docId) it.copy(role = nextRole, username = newUsername) else it
        }
        require(effective.any { it.role == AppRole.ADMIN }) { "Minimal harus ada satu akun admin." }

        migrateLegacyServerCreds(cur, newUsername)

        val fields = buildAccountFields(
            username = newUsername,
            passwordHash = nextHash,
            role = nextRole,
            permissions = nextPerms,
            serverCreds = nextCreds,
            createdAtMs = if (cur.createdAtMs > 0) cur.createdAtMs else now,
            updatedAtMs = now,
            existing = cur.rawFields,
        )
        val ok = upsertDocument(cur.collection, cur.docId, fields, merge = true, deleteFieldPaths = legacyServerCredentialFields)
        if (!ok) throw IllegalStateException("Gagal memperbarui akun di Firestore.")
        if (newUsername != cur.username) {
            profileIdsFor(cur).forEach { profileId ->
                deleteDocument("settings", credentialDocumentId(profileId, cur.username))
            }
        }
        if (serverCreds != null) {
            (profileIdsFor(cur) + nextCreds.keys).distinct().forEach { profileId ->
                val credential = nextCreds[profileId]
                if (credential == null || isBlankCred(credential)) {
                    deleteDocument("settings", credentialDocumentId(profileId, newUsername))
                } else {
                    check(saveTauriServerCredPayload(profileId, newUsername, listOf(credential), credential.username)) {
                        "Gagal menyimpan kredensial server terenkripsi."
                    }
                }
            }
        }

        if (nextRole == AppRole.ADMIN) {
            val adminUpdate = linkedMapOf<String, Any?>(
                "adminUsername" to newUsername,
                "updatedAt" to now,
            )
            if (!newPassword.isNullOrBlank()) {
                adminUpdate["passwordHash"] = nextHash
                adminUpdate["pinHash"] = LegacyAdminCredentialCodec.hashPinLayered(newPassword)
                adminUpdate["pinEncrypted"] = LegacyAdminCredentialCodec.encrypt(newPassword, config.appSecret)
            }
            val adminFields = encodeFieldsMap(adminUpdate)
            upsertDocument("settings", "app_auth", adminFields, merge = true)
            if (!newPassword.isNullOrBlank()) clearDefaultAdminPasswordPending()
        }

        fetchDoc(cur.collection, cur.docId)?.toSafeAccountOrNull()
            ?: LocalAppAccountSafe(
                id = cur.id,
                username = newUsername,
                role = nextRole,
                permissions = nextPerms,
                serverCreds = nextCreds,
                createdAtMs = if (cur.createdAtMs > 0) cur.createdAtMs else now,
                updatedAtMs = now,
            )
    }

    override fun deleteAccount(id: String): Boolean = synchronized(lock) {
        ensureConfigured()
        val all = listAllDocs()
        val cur = all.firstOrNull { it.matchesId(id) } ?: return false
        val remaining = all.filterNot { it.docId == cur.docId }
        require(remaining.any { it.role == AppRole.ADMIN }) {
            "Tidak dapat menghapus: minimal harus ada satu akun admin."
        }
        deleteDocument(cur.collection, cur.docId)
    }

    override fun getServerCreds(accountId: String, profileId: String): LocalServerCredential? = synchronized(lock) {
        ensureConfigured()
        val acc = listAllDocs().firstOrNull { it.matchesId(accountId) }
        val accountKey = acc?.username?.trim()?.lowercase().orEmpty()
        val payload = loadTauriServerCredPayload(profileId, accountKey)
            ?: loadTauriServerCredPayload(profileId, null)
        if (payload != null) {
            val active = parseMapString(payload, "activeNip", "active_nip", "nip", "username")
            val accounts = parseTauriCredAccounts(payload)
            val selected = accounts.firstOrNull { norm(it.username) == norm(active) } ?: accounts.firstOrNull()
            if (selected != null) return selected
        }
        acc?.activeServerCredential(profileId)
    }

    override fun getServerCredAccounts(accountId: String, profileId: String): List<LocalServerCredential> = synchronized(lock) {
        ensureConfigured()
        val acc = listAllDocs().firstOrNull { it.matchesId(accountId) }
        val accountKey = acc?.username?.trim()?.lowercase().orEmpty()
        val payload = loadTauriServerCredPayload(profileId, accountKey)
            ?: loadTauriServerCredPayload(profileId, null)
        val fromSettings = payload?.let(::parseTauriCredAccounts).orEmpty()
        if (fromSettings.isNotEmpty()) fromSettings else acc?.serverCredentials(profileId).orEmpty()
    }

    override fun setServerCreds(
        accountId: String,
        profileId: String,
        creds: LocalServerCredential?,
    ): LocalAppAccountSafe {
        val existing = getServerCredAccounts(accountId, profileId)
        val next = if (creds == null) emptyList() else existing
            .filterNot { norm(it.username) == norm(creds.username) } + creds
        return setServerCredAccounts(accountId, profileId, next, creds?.username)
    }

    override fun setServerCredAccounts(
        accountId: String,
        profileId: String,
        accounts: List<LocalServerCredential>,
        activeUsername: String?,
    ): LocalAppAccountSafe = synchronized(lock) {
        ensureConfigured()
        val all = listAllDocs()
        val cur = all.firstOrNull { it.matchesId(accountId) }
            ?: throw IllegalArgumentException("Akun tidak ditemukan.")
        migrateLegacyServerCreds(cur)
        val cleanAccounts = accounts.map(::sanitizeCred).filterNot(::isBlankCred).distinctBy { norm(it.username) }
        val active = cleanAccounts.firstOrNull { norm(it.username) == norm(activeUsername.orEmpty()) }
            ?: cleanAccounts.firstOrNull()
        val credsMap = cur.serverCreds.toMutableMap()
        val accountsMap = cur.serverCredAccounts.toMutableMap()
        val activeMap = cur.activeServerCreds.toMutableMap()
        if (active == null) {
            credsMap.remove(profileId)
            accountsMap.remove(profileId)
            activeMap.remove(profileId)
        } else {
            credsMap[profileId] = active
            accountsMap[profileId] = cleanAccounts
            activeMap[profileId] = active.username
        }
        val now = System.currentTimeMillis()
        val fields = buildAccountFields(
            username = cur.username,
            passwordHash = cur.passwordHash,
            role = cur.role,
            permissions = cur.permissions,
            serverCreds = credsMap,
            createdAtMs = if (cur.createdAtMs > 0) cur.createdAtMs else now,
            updatedAtMs = now,
            existing = cur.rawFields,
            serverCredAccounts = accountsMap,
            activeServerCreds = activeMap,
        )
        val ok = upsertDocument(cur.collection, cur.docId, fields, merge = true, deleteFieldPaths = legacyServerCredentialFields)
        if (!ok) throw IllegalStateException("Gagal menyimpan kredensial server ke Firestore.")
        check(saveTauriServerCredPayload(profileId, cur.username, cleanAccounts, active?.username.orEmpty())) {
            "Gagal menyimpan kredensial server terenkripsi ke Firestore."
        }
        fetchDoc(cur.collection, cur.docId)?.toSafeAccountOrNull()
            ?: cur.toSafeCopy().copy(
                serverCreds = credsMap,
                serverCredAccounts = accountsMap,
                activeServerCreds = activeMap,
                updatedAtMs = now,
            )
    }

    fun readAutomationSync(accountId: String, profileId: String): String? {
        val docId = automationSyncDocumentId(accountId, profileId)
        val doc = fetchDoc("settings", docId) ?: return null
        val flat = decodeFieldsMap(doc.rawFields)
        return firstString(flat, "payload", "automationSync", "json")
    }

    fun writeAutomationSync(accountId: String, profileId: String, payloadJson: String): Boolean {
        val docId = automationSyncDocumentId(accountId, profileId)
        val fields = encodeFieldsMap(mapOf(
            "schemaVersion" to 1,
            "accountId" to safeCredentialKey(accountId),
            "profileId" to safeCredentialKey(profileId),
            "payload" to payloadJson,
            "updatedAt" to java.time.Instant.now().toString(),
        ))
        return upsertDocument("settings", docId, fields, merge = true)
    }

    private fun automationSyncDocumentId(accountId: String, profileId: String): String {
        val account = safeCredentialKey(accountId)
        val profile = safeCredentialKey(profileId)
        return "automation_${account}_${profile}"
    }

    private fun safeCredentialKey(value: String): String {
        val cleaned = value.trim().lowercase().replace(Regex("[^a-zA-Z0-9_]"), "_")
        return cleaned.ifBlank { "default" }
    }

    private fun profileIdsFor(account: FsAccountDoc): Set<String> = buildSet {
        addAll(listOf("jatim", "madiun", "ponorogo"))
        addAll(account.serverCreds.keys)
        addAll(account.serverCredAccounts.keys)
        addAll(account.activeServerCreds.keys)
    }

    private fun scrubLegacyCredentialFields(account: FsAccountDoc): FsAccountDoc {
        val flat = decodeFieldsMap(account.rawFields)
        val nestedCreds = (flat["permissions"] as? Map<*, *>)?.keys
            ?.any { it?.toString() in setOf("serverCreds", "server_creds") } == true
        if (legacyServerCredentialFields.none { it in flat } && !nestedCreds) return account

        migrateLegacyServerCreds(account)
        val now = System.currentTimeMillis()
        val fields = buildAccountFields(
            username = account.username,
            passwordHash = account.passwordHash,
            role = account.role,
            permissions = account.permissions,
            serverCreds = emptyMap(),
            createdAtMs = if (account.createdAtMs > 0) account.createdAtMs else now,
            updatedAtMs = now,
            existing = account.rawFields,
        )
        check(upsertDocument(
            account.collection,
            account.docId,
            fields,
            merge = true,
            deleteFieldPaths = legacyServerCredentialFields,
        )) { "Gagal menghapus kredensial plaintext legacy dari dokumen akun Firestore." }
        return account.copy(
            serverCreds = emptyMap(),
            serverCredAccounts = emptyMap(),
            activeServerCreds = emptyMap(),
            updatedAtMs = now,
            rawFields = fields,
        )
    }

    private fun migrateLegacyServerCreds(account: FsAccountDoc, targetAccountKey: String = account.username) {
        for (profileId in profileIdsFor(account)) {
            val oldPayload = loadTauriServerCredPayload(profileId, account.username)
            val targetPayload = if (targetAccountKey == account.username) oldPayload
                else loadTauriServerCredPayload(profileId, targetAccountKey)
            val oldAccounts = oldPayload?.let(::parseTauriCredAccounts).orEmpty()
            val legacyAccounts = account.serverCredentials(profileId)
            val targetAccounts = if (targetAccountKey == account.username) emptyList()
                else targetPayload?.let(::parseTauriCredAccounts).orEmpty()
            val accounts = (oldAccounts + legacyAccounts + targetAccounts).distinctBy { norm(it.username) }
            if (accounts.isEmpty()) continue

            val requestedActive = oldPayload?.let { parseMapString(it, "activeNip", "active_nip", "nip", "username") }
                ?: account.activeServerCredential(profileId)?.username
            val active = accounts.firstOrNull { norm(it.username) == norm(requestedActive.orEmpty()) }
                ?: accounts.first()
            check(saveTauriServerCredPayload(profileId, account.username, accounts, active.username)) {
                "Gagal memigrasikan kredensial lama profil $profileId ke payload terenkripsi."
            }
            if (targetAccountKey != account.username) {
                val targetActive = targetPayload?.let { parseMapString(it, "activeNip", "active_nip") }
                    ?.takeIf { target -> accounts.any { norm(it.username) == norm(target) } }
                    ?: active.username
                check(saveTauriServerCredPayload(profileId, targetAccountKey, accounts, targetActive)) {
                    "Gagal memindahkan kredensial terenkripsi profil $profileId ke akun baru."
                }
            }
        }
    }

    private fun credentialDocumentId(profileId: String, accountKey: String?): String {
        val profile = safeCredentialKey(profileId)
        val account = accountKey?.trim()?.takeIf { it.isNotBlank() }?.let(::safeCredentialKey)
        return if (account != null) "login_${account}_${profile}" else "login_${profile}"
    }

    private fun loadTauriServerCredPayload(profileId: String, accountKey: String?): Map<String, Any?>? {
        val docId = credentialDocumentId(profileId, accountKey)
        val doc = fetchDoc("settings", docId) ?: return null
        val flat = decodeFieldsMap(doc.rawFields)
        val encrypted = firstString(flat, "encrypted")
        val payload = if (!encrypted.isNullOrBlank()) decryptTauriPayload(encrypted) else flat
        if (payload.isEmpty()) return null
        return payload
    }

    private fun saveTauriServerCredPayload(
        profileId: String,
        accountKey: String,
        accounts: List<LocalServerCredential>,
        activeUsername: String,
    ): Boolean {
        val cleanAccount = accountKey.trim().lowercase()
        val primary = accounts.firstOrNull { norm(it.username) == norm(activeUsername) } ?: accounts.firstOrNull()
        val payload = linkedMapOf<String, Any?>(
            "profileId" to profileId,
            "account" to safeCredentialKey(cleanAccount),
            "activeNip" to primary?.username.orEmpty(),
            "accounts" to accounts.map { mapOf("nip" to it.username, "password" to it.password, "imei" to it.imei) },
            "nip" to primary?.username.orEmpty(),
            "username" to primary?.username.orEmpty(),
            "password" to primary?.password.orEmpty(),
            "imei" to primary?.imei.orEmpty(),
            "timestamp" to java.time.Instant.now().toString(),
        )
        val encrypted = encryptTauriPayload(payload)
        val fields = encodeFieldsMap(mapOf("encrypted" to encrypted, "updatedAt" to java.time.Instant.now().toString()))
        return upsertDocument("settings", credentialDocumentId(profileId, cleanAccount), fields, merge = true)
    }

    private fun parseTauriCredAccounts(payload: Map<String, Any?>): List<LocalServerCredential> {
        val values = payload["accounts"] as? List<*> ?: return listOfNotNull(parseSingleCred(payload))
        return values.mapNotNull { value ->
            val map = value as? Map<*, *> ?: return@mapNotNull null
            val username = (map["nip"] ?: map["username"] ?: "").toStringValue()
            val password = (map["password"] ?: "").toStringValue()
            val imei = (map["imei"] ?: "").toStringValue()
            sanitizeCred(LocalServerCredential(username, password, imei)).takeUnless(::isBlankCred)
        }
    }

    private fun parseMapString(map: Map<String, Any?>, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> (map[key] as? String)?.takeIf { it.isNotBlank() } }.orEmpty()

    private fun encryptTauriPayload(payload: Map<String, Any?>): String {
        val json = Json { explicitNulls = false }.encodeToString(JsonObject.serializer(), buildJsonObjectFromMap(payload))
        val key = config.payloadKey
        var shifted = ""
        for (i in json.indices) {
            val shift = if (key.isEmpty()) 0 else key[i % key.length].code
            shifted += ((json[i].code + shift) % 65536).toChar()
        }
        val base64 = java.util.Base64.getEncoder().encodeToString(shifted.toByteArray(Charsets.UTF_8))
        return "ENC$" + base64.reversed() + "\$SEC"
    }

    private fun decryptTauriPayload(encrypted: String): Map<String, Any?> {
        if (!encrypted.startsWith("ENC$") || !encrypted.endsWith("\$SEC")) {
            return runCatching { json.parseToJsonElement(encrypted).jsonObject.mapValues { decodePlainValue(it.value) } }.getOrDefault(emptyMap())
        }
        return runCatching {
            val cleaned = encrypted.substring(4, encrypted.length - 4).reversed()
            val shifted = String(java.util.Base64.getDecoder().decode(cleaned), Charsets.UTF_8)
            val key = config.payloadKey
            val jsonText = buildString {
                for (i in shifted.indices) {
                    val shift = if (key.isEmpty()) 0 else key[i % key.length].code
                    append(((shifted[i].code - shift + 65536) % 65536).toChar())
                }
            }
            json.parseToJsonElement(jsonText).jsonObject.mapValues { decodePlainValue(it.value) }
        }.getOrDefault(emptyMap())
    }

    private fun buildJsonObjectFromMap(map: Map<String, Any?>): JsonObject = buildJsonObject {
        map.forEach { (key, value) -> put(key, encodePlainJsonValue(value)) }
    }

    private fun encodePlainJsonValue(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject { value.forEach { (k, v) -> if (k != null) put(k.toString(), encodePlainJsonValue(v)) } }
        is Iterable<*> -> buildJsonArray { value.forEach { add(encodePlainJsonValue(it)) } }
        else -> JsonPrimitive(value.toString())
    }

    private fun decodePlainValue(value: JsonElement?): Any? = decodeValue(value)

    override fun getLockState(): Pair<Int, Long> {
        val data = DesktopSecureStore.load()
        val attempts = data[localKeyAttempts]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val cooldown = data[localKeyCooldown]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        return attempts to cooldown
    }

    override fun setLockState(attempts: Int, cooldownUntilMs: Long) {
        val cur = DesktopSecureStore.load().toMutableMap()
        cur[localKeyAttempts] = attempts.coerceAtLeast(0).toString()
        cur[localKeyCooldown] = cooldownUntilMs.coerceAtLeast(0L).toString()
        DesktopSecureStore.save(cur)
    }

    override fun isDefaultAdminPasswordPending(): Boolean =
        DesktopSecureStore.load()[localKeyDefaultAdminPending]?.toBooleanStrictOrNull() == true

    override fun clearDefaultAdminPasswordPending() {
        val cur = DesktopSecureStore.load().toMutableMap()
        cur[localKeyDefaultAdminPending] = "false"
        DesktopSecureStore.save(cur)
    }

    private fun setDefaultAdminPending(pending: Boolean) {
        val cur = DesktopSecureStore.load().toMutableMap()
        cur[localKeyDefaultAdminPending] = pending.toString()
        DesktopSecureStore.save(cur)
    }

    // -------------------- Internal model & mapping --------------------

    private data class FsAccountDoc(
        val collection: String,
        val docId: String,
        val id: String,
        val username: String,
        val passwordHash: String,
        val role: AppRole,
        val permissions: AppPermissions,
        val serverCreds: Map<String, LocalServerCredential>,
        val serverCredAccounts: Map<String, List<LocalServerCredential>>,
        val activeServerCreds: Map<String, String>,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val rawFields: JsonObject,
    ) {
        fun serverCredentials(profileId: String): List<LocalServerCredential> =
            serverCredAccounts[profileId].orEmpty().ifEmpty {
                listOfNotNull(serverCreds[profileId]?.takeUnless {
                    it.username.isBlank() && it.password.isBlank() && it.imei.isBlank()
                })
            }

        fun activeServerCredential(profileId: String): LocalServerCredential? {
            val credentials = serverCredentials(profileId)
            val active = activeServerCreds[profileId] ?: serverCreds[profileId]?.username
            return credentials.firstOrNull { it.username.equals(active, ignoreCase = true) }
                ?: credentials.firstOrNull()
        }

        fun matchesId(q: String): Boolean {
            if (q.isBlank()) return false
            return id == q || docId == q
        }
        fun toSafeAccountOrNull(): LocalAppAccountSafe? {
            val u = username.trim()
            if (u.isBlank()) return null
            return LocalAppAccountSafe(
                id = id.ifBlank { docId },
                username = u,
                role = role,
                permissions = permissions,
                serverCreds = serverCreds,
                serverCredAccounts = serverCredAccounts,
                activeServerCreds = activeServerCreds,
                createdAtMs = createdAtMs,
                updatedAtMs = updatedAtMs,
            )
        }
        fun toSafeCopy(): LocalAppAccountSafe = LocalAppAccountSafe(
            id = id.ifBlank { docId },
            username = username,
            role = role,
            permissions = permissions,
            serverCreds = serverCreds,
            serverCredAccounts = serverCredAccounts,
            activeServerCreds = activeServerCreds,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs,
        )
    }

    private fun ensureConfigured() {
        val missing = FirebaseAppConfig.missingRequired(config)
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Firebase belum terkonfigurasi/terbaca: ${missing.joinToString(", ")}. " +
                    "Gunakan environment variable atau file .env dengan nama konfigurasi desktop."
            )
        }
    }

    private fun listAllDocs(): List<FsAccountDoc> {
        val out = linkedMapOf<String, FsAccountDoc>()
        val collections = buildList {
            add(primaryCollection)
            fallbackCollections.forEach { if (it !in this) add(it) }
        }
        collections.forEach { col ->
            runCatching { listCollection(col) }.getOrNull().orEmpty().forEach { doc ->
                // Dedupe by (collection,docId) and by username+id preference
                val key = "${doc.collection}/${doc.docId}"
                val existing = out[key]
                if (existing == null) out[key] = doc
            }
        }
        // Deduplicate logical same account appearing in multiple collections by username (prefer primary)
        val byUser = linkedMapOf<String, FsAccountDoc>()
        out.values.sortedWith(compareBy<FsAccountDoc> { if (it.collection == primaryCollection) 0 else 1 }
            .thenByDescending { it.updatedAtMs }).forEach { doc ->
            val u = norm(doc.username)
            if (u.isBlank()) return@forEach
            val prev = byUser[u]
            if (prev == null || (prev.collection != primaryCollection && doc.collection == primaryCollection)) {
                byUser[u] = doc
            }
        }
        val result = if (byUser.isNotEmpty()) byUser.values.toList() else out.values.toList()
        return result.map(::scrubLegacyCredentialFields)
    }

    private fun listCollection(collection: String): List<FsAccountDoc> {
        val docs = mutableListOf<FsAccountDoc>()
        var pageToken: String? = null
        var guard = 0
        do {
            guard++
            val url = firestoreDocumentsUrl(collection)
            val body = httpGet(url) {
                parameter("key", apiKey)
                parameter("pageSize", "200")
                if (!pageToken.isNullOrBlank()) parameter("pageToken", pageToken)
            }
            val root = parseJsonObject(body) ?: break
            val arr = root["documents"] as? JsonArray ?: JsonArray(emptyList())
            arr.forEach { el ->
                val obj = el as? JsonObject ?: return@forEach
                parseDoc(collection, obj)?.let { docs += it }
            }
            pageToken = root.string("nextPageToken")?.takeIf { it.isNotBlank() }
        } while (!pageToken.isNullOrBlank() && guard < 20)
        return docs
    }

    private fun queryByUsername(collection: String, normalizedUsername: String): List<FsAccountDoc>? {
        val url = "https://firestore.googleapis.com/v1/projects/${enc(projectId)}/databases/(default)/documents:runQuery"
        // Try common field names
        val fieldNames = listOf("username", "userName", "name")
        for (field in fieldNames) {
            val bodyObj = buildJsonObject {
                put("structuredQuery", buildJsonObject {
                    put("from", buildJsonArray { add(buildJsonObject { put("collectionId", collection) }) })
                    put("where", buildJsonObject {
                        put("fieldFilter", buildJsonObject {
                            put("field", buildJsonObject { put("fieldPath", field) })
                            put("op", "EQUAL")
                            put("value", buildJsonObject { put("stringValue", normalizedUsername) })
                        })
                    })
                    put("limit", 5)
                })
            }
            val resp = runCatching {
                httpPostJson(url, bodyObj.toString()) { parameter("key", apiKey) }
            }.getOrNull() ?: continue
            val arr = runCatching { json.parseToJsonElement(resp) as? JsonArray }.getOrNull() ?: continue
            val docs = arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val docObj = obj["document"] as? JsonObject ?: return@mapNotNull null
                parseDoc(collection, docObj)
            }
            if (docs.isNotEmpty()) return docs.map(::scrubLegacyCredentialFields)
        }
        // Also try original-cased username not possible here (we normalized), fall through
        return null
    }

    private fun fetchDoc(collection: String, docId: String): FsAccountDoc? {
        val url = firestoreDocumentUrl(collection, docId)
        val body = runCatching { httpGet(url) { parameter("key", apiKey) } }.getOrNull() ?: return null
        val obj = parseJsonObject(body) ?: return null
        if (obj.containsKey("error")) return null
        return parseDoc(collection, obj)
    }

    private fun upsertDocument(
        collection: String,
        docId: String,
        fields: JsonObject,
        merge: Boolean,
        deleteFieldPaths: List<String> = emptyList(),
    ): Boolean {
        val url = firestoreDocumentUrl(collection, docId)
        val payload = buildJsonObject { put("fields", fields) }.toString()
        val resp = runCatching {
            httpPatchJson(url, payload) {
                parameter("key", apiKey)
                if (merge) {
                    // update only provided field paths; preserve unknown existing fields
                    (collectFieldPaths(fields) + deleteFieldPaths).distinct().forEach { parameter("updateMask.fieldPaths", it) }
                }
            }
        }.getOrNull() ?: return false
        val obj = parseJsonObject(resp)
        return obj != null && !obj.containsKey("error")
    }

    private fun deleteDocument(collection: String, docId: String): Boolean {
        val url = firestoreDocumentUrl(collection, docId)
        return runCatching { httpDelete(url) { parameter("key", apiKey) } }.getOrDefault(false)
    }

    private fun collectFieldPaths(obj: JsonObject, prefix: String = ""): List<String> {
        // Firestore updateMask uses top-level field names for replacing whole maps/values.
        // For our canonical writes we update top-level keys to preserve other unknown top-level data.
        return if (prefix.isEmpty()) obj.keys.toList() else listOf(prefix)
    }

    private fun firestoreDocumentsUrl(collection: String): String =
        "https://firestore.googleapis.com/v1/projects/${enc(projectId)}/databases/(default)/documents/${enc(collection)}"

    private fun firestoreDocumentUrl(collection: String, docId: String): String =
        "https://firestore.googleapis.com/v1/projects/${enc(projectId)}/databases/(default)/documents/${enc(collection)}/${enc(docId)}"

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

    // -------------------- HTTP helpers (blocking, caller already off-UI) --------------------

    private fun httpGet(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): String {
        val resp = kotlinx.coroutines.runBlocking {
            client.get(url) { block() }
        }
        return handleResponse(url, resp)
    }

    private fun httpPostJson(url: String, body: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): String {
        val resp = kotlinx.coroutines.runBlocking {
            client.post(url) {
                block()
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
        return handleResponse(url, resp)
    }

    private fun httpPatchJson(url: String, body: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): String {
        val resp = kotlinx.coroutines.runBlocking {
            client.patch(url) {
                block()
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
        return handleResponse(url, resp)
    }

    private fun httpDelete(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): Boolean {
        val resp = kotlinx.coroutines.runBlocking { client.delete(url) { block() } }
        val text = kotlinx.coroutines.runBlocking { runCatching { resp.bodyAsText() }.getOrDefault("") }
        if (resp.status.isSuccess()) return true
        throw mapHttpError(resp.status.value, text)
    }

    private fun handleResponse(url: String, resp: HttpResponse): String {
        val text = kotlinx.coroutines.runBlocking { runCatching { resp.bodyAsText() }.getOrDefault("") }
        if (resp.status.isSuccess()) return text
        // 404 on document get is "not found" (caller may handle)
        if (resp.status.value == 404) return text
        throw mapHttpError(resp.status.value, text)
    }

    private fun mapHttpError(code: Int, body: String): IllegalStateException {
        val parsed = parseJsonObject(body)
        val serverMsg = parsed?.obj("error")?.string("message")
            ?: parsed?.string("error")
            ?: parsed?.string("message")
        val hint = when (code) {
            401, 403 -> "Akses Firestore ditolak (periksa FIREBASE_API_KEY/rules/proyek)."
            404 -> "Data Firestore tidak ditemukan."
            429 -> "Terlalu banyak permintaan ke Firestore. Coba lagi sesaat lagi."
            in 500..599 -> "Layanan Firestore sedang bermasalah (HTTP $code)."
            else -> "Gagal mengakses Firestore (HTTP $code)."
        }
        val msg = if (!serverMsg.isNullOrBlank()) "$hint Detail: ${serverMsg.take(180)}" else hint
        return IllegalStateException(msg)
    }

    private fun parseJsonObject(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }

    // -------------------- Firestore parsing/mapping --------------------

    private fun parseDoc(collection: String, doc: JsonObject): FsAccountDoc? {
        val name = doc.string("name").orEmpty() // projects/.../documents/col/id
        val docId = name.substringAfterLast('/').ifBlank { doc.string("id").orEmpty() }
        val fields = doc.obj("fields") ?: JsonObject(emptyMap())
        if (fields.isEmpty() && docId.isBlank()) return null

        val flat = decodeFieldsMap(fields)

        val username = firstString(flat, "username", "userName", "user_name", "nama", "name", "email", "nip")
            ?.trim().orEmpty().ifBlank { docId }
        val passHash = firstString(flat, "passwordHash", "password_hash", "hash", "bcrypt", "passwordBcrypt", "password_bcrypt")
            ?: firstString(flat, "password").takeIfLooksLikeHash()
            ?: ""
        // role
        val role = parseRole(flat, firstString(flat, "role", "userRole", "type", "level"))

        val perms = parsePermissions(flat, role)
        val serverCreds = parseServerCreds(flat)
        val serverCredAccounts = parseServerCredAccounts(flat)
        val activeServerCreds = parseActiveServerCreds(flat, serverCreds)
        val createdAt = firstLong(flat, "createdAtMs", "created_at_ms", "createdAt", "created_at", "created")
            ?: parseFirestoreTimestampMs(flat["createdAt"]) ?: 0L
        val updatedAt = firstLong(flat, "updatedAtMs", "updated_at_ms", "updatedAt", "updated_at", "updated")
            ?: parseFirestoreTimestampMs(flat["updatedAt"]) ?: createdAt
        val id = firstString(flat, "id", "uid", "userId", "user_id").orEmpty().ifBlank { docId }

        return FsAccountDoc(
            collection = collection,
            docId = docId,
            id = id,
            username = norm(username).ifBlank { username.trim() },
            passwordHash = passHash,
            role = role,
            permissions = perms,
            serverCreds = serverCreds,
            serverCredAccounts = serverCredAccounts,
            activeServerCreds = activeServerCreds,
            createdAtMs = createdAt,
            updatedAtMs = updatedAt,
            rawFields = fields,
        )
    }

    private fun decodeFieldsMap(fields: JsonObject): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        fields.forEach { (k, v) -> out[k] = decodeValue(v) }
        return out
    }

    private fun decodeValue(v: JsonElement?): Any? {
        val obj = v as? JsonObject ?: return null
        return when {
            obj.containsKey("stringValue") -> obj.string("stringValue")
            obj.containsKey("integerValue") -> obj.string("integerValue")?.toLongOrNull()
                ?: obj.string("integerValue")
            obj.containsKey("doubleValue") -> (obj["doubleValue"] as? JsonPrimitive)?.doubleOrNull
                ?: obj.string("doubleValue")?.toDoubleOrNull()
            obj.containsKey("booleanValue") -> (obj["booleanValue"] as? JsonPrimitive)?.booleanOrNull
                ?: obj.string("booleanValue")?.toBooleanStrictOrNull()
            obj.containsKey("nullValue") -> null
            obj.containsKey("timestampValue") -> obj.string("timestampValue")
            obj.containsKey("mapValue") -> {
                val inner = (obj["mapValue"] as? JsonObject)?.obj("fields") ?: JsonObject(emptyMap())
                decodeFieldsMap(inner)
            }
            obj.containsKey("arrayValue") -> {
                val values = (obj["arrayValue"] as? JsonObject)?.get("values") as? JsonArray
                    ?: JsonArray(emptyList())
                (values as List<JsonElement>).map { el -> decodeValue(el) }
            }
            obj.containsKey("referenceValue") -> obj.string("referenceValue")
            else -> {
                // Fallback: try to treat as plain json
                when (v) {
                    is JsonPrimitive -> v.contentOrNull ?: v.toString()
                    is JsonArray -> (v as List<JsonElement>).map { el -> decodeValue(el) }
                    is JsonObject -> decodeFieldsMap(v)
                    else -> null
                }
            }
        }
    }

    private fun encodeValue(value: Any?): JsonObject = when (value) {
        null -> buildJsonObject { put("nullValue", JsonNull) }
        is String -> buildJsonObject { put("stringValue", value) }
        is Boolean -> buildJsonObject { put("booleanValue", JsonPrimitive(value)) }
        is Int -> buildJsonObject { put("integerValue", value.toString()) }
        is Long -> buildJsonObject { put("integerValue", value.toString()) }
        is Float -> buildJsonObject { put("doubleValue", JsonPrimitive(value.toDouble())) }
        is Double -> buildJsonObject { put("doubleValue", JsonPrimitive(value)) }
        is Map<*, *> -> buildJsonObject {
            put("mapValue", buildJsonObject {
                put("fields", buildJsonObject {
                    value.forEach { (k, v) ->
                        if (k != null) put(k.toString(), encodeValue(v))
                    }
                })
            })
        }
        is Iterable<*> -> buildJsonObject {
            put("arrayValue", buildJsonObject {
                put("values", buildJsonArray { value.forEach { add(encodeValue(it)) } })
            })
        }
        is JsonElement -> buildJsonObject { put("stringValue", value.toString()) }
        else -> buildJsonObject { put("stringValue", value.toString()) }
    }

    private fun encodeFieldsMap(map: Map<String, Any?>): JsonObject = buildJsonObject {
        map.forEach { (k, v) -> put(k, encodeValue(v)) }
    }

    private fun parseRole(flat: Map<String, Any?>, roleStr: String?): AppRole {
        val s = roleStr?.trim()?.lowercase()
        if (s == "admin" || s == "administrator") return AppRole.ADMIN
        if (s == "user" || s == "pegawai" || s == "member") return AppRole.USER
        val isAdmin = firstBool(flat, "isAdmin", "is_admin", "admin")
        if (isAdmin == true) return AppRole.ADMIN
        val perms = flat["permissions"] as? Map<*, *>
        val pr = (perms?.get("role") as? String)?.lowercase()
        if (pr == "admin") return AppRole.ADMIN
        return AppRole.USER
    }

    private fun parsePermissions(flat: Map<String, Any?>, role: AppRole): AppPermissions {
        val base = if (role == AppRole.ADMIN) AppPermissions.AdminFull else AppPermissions.defaultUser()
        val p = (flat["permissions"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?: (flat["permission"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?: emptyMap<String, Any?>()

        fun boolAny(vararg keys: String): Boolean? {
            for (k in keys) {
                val v = p[k] ?: flat[k]
                toBool(v)?.let { return it }
            }
            return null
        }
        fun setAny(vararg keys: String): Set<String>? {
            for (k in keys) {
                val v = p[k] ?: flat[k]
                toStringSet(v)?.let { return it }
            }
            return null
        }
        fun mapSetAny(vararg keys: String): Map<String, Set<String>>? {
            for (k in keys) {
                val v = p[k] ?: flat[k]
                toProfileMenuMap(v)?.let { return it }
            }
            return null
        }

        val profiles = parseProfileAccess(p, flat, base.profiles)
        val allowedMenuIds = setAny("allowedMenuIds", "allowed_menu_ids", "menuIds")
        val nestedMenus = p["menus"] as? Map<*, *>
        val allowedByProfile = nestedMenus?.let { parseTauriMenuMap(it) }
            ?: mapSetAny("allowedMenuIdsByProfile", "allowed_menu_ids_by_profile", "menuByProfile", "menusByProfile")

        return base.copy(
            role = role,
            profiles = if (role == AppRole.ADMIN) ProfileAccess() else profiles,
            allowedMenuIds = if (role == AppRole.ADMIN) null else allowedMenuIds,
            allowedMenuIdsByProfile = if (role == AppRole.ADMIN) null else allowedByProfile,
            allowEditServerCreds = boolAny("allowEditServerCreds", "allow_edit_server_creds", "canEditServerCreds", "allowEditLoginCredentials") ?: base.allowEditServerCreds,
            allowSearchPegawai = boolAny("allowSearchPegawai", "allow_search_pegawai") ?: base.allowSearchPegawai,
            allowSearchIzin = boolAny("allowSearchIzin", "allow_search_izin") ?: base.allowSearchIzin,
            allowSearchInstansi = boolAny("allowSearchInstansi", "allow_search_instansi") ?: base.allowSearchInstansi,
            allowSearchDatabase = boolAny("allowSearchDatabase", "allow_search_database") ?: base.allowSearchDatabase,
            allowSearchProduktivitas = boolAny("allowSearchProduktivitas", "allow_search_produktivitas") ?: base.allowSearchProduktivitas,
            allowSearchPresensi = boolAny("allowSearchPresensi", "allow_search_presensi") ?: base.allowSearchPresensi,
            allowSearchLaporan = boolAny("allowSearchLaporan", "allow_search_laporan") ?: base.allowSearchLaporan,
            allowSearchUnor = boolAny("allowSearchUnor", "allow_search_unor") ?: base.allowSearchUnor,
            allowSearchReview = boolAny("allowSearchReview", "allow_search_review") ?: base.allowSearchReview,
            allowAutomationControl = boolAny("allowAutomationControl", "allow_automation_control") ?: base.allowAutomationControl,
            allowSensitiveActions = boolAny("allowSensitiveActions", "allow_sensitive_actions") ?: base.allowSensitiveActions,
            tabPengajuanIzin = boolAny("tabPengajuanIzin", "tab_pengajuan_izin") ?: base.tabPengajuanIzin,
            tabLogPresensiTerkini = boolAny("tabLogPresensiTerkini", "tab_log_presensi_terkini") ?: base.tabLogPresensiTerkini,
            tabLogLengkap = boolAny("tabLogLengkap", "tab_log_lengkap") ?: base.tabLogLengkap,
            reportPerPegawai = boolAny("reportPerPegawai", "report_per_pegawai") ?: base.reportPerPegawai,
            reportPerPegawaiAktivitas = boolAny("reportPerPegawaiAktivitas", "report_per_pegawai_aktivitas") ?: base.reportPerPegawaiAktivitas,
            reportSkorPerInstansi = boolAny("reportSkorPerInstansi", "report_skor_per_instansi") ?: base.reportSkorPerInstansi,
            reportAktivitasPerInstansi = boolAny("reportAktivitasPerInstansi", "report_aktivitas_per_instansi") ?: base.reportAktivitasPerInstansi,
            reportRekapTppAktivitas = boolAny("reportRekapTppAktivitas", "report_rekap_tpp_aktivitas") ?: base.reportRekapTppAktivitas,
        )
    }

    private fun parseProfileAccess(p: Map<String, Any?>, flat: Map<String, Any?>, fallback: ProfileAccess): ProfileAccess {
        val profObj = (p["profiles"] as? Map<*, *>) ?: (p["profileAccess"] as? Map<*, *>)
            ?: (flat["profiles"] as? Map<*, *>) ?: (flat["profileAccess"] as? Map<*, *>)
        if (profObj != null) {
            val m = profObj.mapKeys { it.key.toString().lowercase() }
            val jatim = toBool(m["jatim"]) ?: fallback.jatim
            val madiun = toBool(m["madiun"]) ?: fallback.madiun
            val ponorogo = toBool(m["ponorogo"]) ?: fallback.ponorogo
            return ProfileAccess(jatim, madiun, ponorogo)
        }
        // Alternative: allowedProfiles list
        val allowed = toStringSet(p["allowedProfiles"] ?: flat["allowedProfiles"] ?: flat["allowed_profiles"])
        if (allowed != null) {
            val set = allowed.map { it.lowercase() }.toSet()
            return ProfileAccess(
                jatim = "jatim" in set,
                madiun = "madiun" in set,
                ponorogo = "ponorogo" in set,
            )
        }
        return fallback
    }

    private fun parseServerCreds(flat: Map<String, Any?>): Map<String, LocalServerCredential> {
        val candidates = listOf(
            flat["serverCreds"], flat["server_creds"], flat["serverCredentials"], flat["server_credentials"],
            (flat["permissions"] as? Map<*, *>)?.get("serverCreds"),
        )
        val obj = candidates.firstNotNullOfOrNull { it as? Map<*, *> } ?: return emptyMap()
        val out = linkedMapOf<String, LocalServerCredential>()
        obj.forEach { (k, v) ->
            val profileId = k?.toString()?.trim().orEmpty()
            if (profileId.isBlank()) return@forEach
            val cred = parseSingleCred(v) ?: return@forEach
            if (!isBlankCred(cred)) out[profileId] = cred
        }
        return out
    }

    private fun parseServerCredAccounts(flat: Map<String, Any?>): Map<String, List<LocalServerCredential>> {
        val raw = flat["serverCredAccounts"] ?: flat["server_cred_accounts"]
        val profiles = raw as? Map<*, *> ?: return emptyMap()
        return profiles.mapNotNull { (key, value) ->
            val profileId = key?.toString()?.trim().orEmpty()
            val map = value as? Map<*, *> ?: return@mapNotNull null
            val entries = map["accounts"] as? List<*> ?: return@mapNotNull null
            val credentials = entries.mapNotNull(::parseSingleCred).distinctBy { norm(it.username) }
            if (profileId.isBlank() || credentials.isEmpty()) null else profileId to credentials
        }.toMap()
    }

    private fun parseActiveServerCreds(
        flat: Map<String, Any?>,
        legacy: Map<String, LocalServerCredential>,
    ): Map<String, String> {
        val raw = flat["activeServerCreds"] ?: flat["active_server_creds"]
        val active = (raw as? Map<*, *>)?.mapNotNull { (key, value) ->
            val profileId = key?.toString()?.trim().orEmpty()
            val username = value?.toString()?.trim().orEmpty()
            if (profileId.isBlank() || username.isBlank()) null else profileId to username
        }?.toMap().orEmpty()
        return legacy.mapValues { (profileId, credential) -> active[profileId] ?: credential.username } + active
    }

    private fun parseSingleCred(v: Any?): LocalServerCredential? {
        val m = when (v) {
            is Map<*, *> -> v.mapKeys { it.key.toString() }
            else -> return null
        }
        val username = (m["username"] ?: m["user"] ?: m["nip"] ?: m["email"] ?: "").toStringValue()
        val password = (m["password"] ?: m["pass"] ?: m["pwd"] ?: "").toStringValue()
        val imei = (m["imei"] ?: m["deviceId"] ?: m["device_id"] ?: m["device"] ?: "").toStringValue()
        val cred = sanitizeCred(LocalServerCredential(username, password, imei))
        return if (isBlankCred(cred)) null else cred
    }

    private fun buildAccountFields(
        username: String,
        passwordHash: String,
        role: AppRole,
        permissions: AppPermissions,
        serverCreds: Map<String, LocalServerCredential>,
        createdAtMs: Long,
        updatedAtMs: Long,
        existing: JsonObject?,
        serverCredAccounts: Map<String, List<LocalServerCredential>> = parseServerCredAccounts(
            decodeFieldsMap(existing ?: JsonObject(emptyMap())),
        ),
        activeServerCreds: Map<String, String> = parseActiveServerCreds(
            decodeFieldsMap(existing ?: JsonObject(emptyMap())), serverCreds,
        ),
    ): JsonObject {
        // Canonical schema + compatibility aliases commonly used by web/Tauri apps.
        val permsMap = linkedMapOf<String, Any?>(
            "role" to role.name,
            "profiles" to mapOf(
                "jatim" to permissions.profiles.jatim,
                "madiun" to permissions.profiles.madiun,
                "ponorogo" to permissions.profiles.ponorogo,
            ),
            "allowedMenuIdsByProfile" to permissions.allowedMenuIdsByProfile?.mapValues { it.value.toList() },
            "allowEditServerCreds" to permissions.allowEditServerCreds,
            "allowSearchPegawai" to permissions.allowSearchPegawai,
            "allowSearchIzin" to permissions.allowSearchIzin,
            "allowSearchInstansi" to permissions.allowSearchInstansi,
            "allowSearchDatabase" to permissions.allowSearchDatabase,
            "allowSearchProduktivitas" to permissions.allowSearchProduktivitas,
            "allowSearchPresensi" to permissions.allowSearchPresensi,
            "allowSearchLaporan" to permissions.allowSearchLaporan,
            "allowSearchUnor" to permissions.allowSearchUnor,
            "allowSearchReview" to permissions.allowSearchReview,
            "allowAutomationControl" to permissions.allowAutomationControl,
            "allowSensitiveActions" to permissions.allowSensitiveActions,
            "tabPengajuanIzin" to permissions.tabPengajuanIzin,
            "tabLogPresensiTerkini" to permissions.tabLogPresensiTerkini,
            "tabLogLengkap" to permissions.tabLogLengkap,
            "reportPerPegawai" to permissions.reportPerPegawai,
            "reportPerPegawaiAktivitas" to permissions.reportPerPegawaiAktivitas,
            "reportSkorPerInstansi" to permissions.reportSkorPerInstansi,
            "reportAktivitasPerInstansi" to permissions.reportAktivitasPerInstansi,
            "reportRekapTppAktivitas" to permissions.reportRekapTppAktivitas,
        )
        val allowedMenuIds = permissions.allowedMenuIds
        if (allowedMenuIds != null) {
            permsMap["allowedMenuIds"] = allowedMenuIds.toList()
        }
        val bkdMenuIds = setOf("tabBeranda", "tabPresensi", "tabPerizinan", "tabLaporan", "tabLokasiAbsen", "tabPresetOtomatisasi", "tabWeb")
        val ponorogoMenuIds = setOf("tabBeranda", "tabAbsen", "tabLog", "tabInputAktivitas", "tabAktivitas", "tabReview", "tabIzin", "tabLogPresensiInstansi", "tabReport", "tabAutomasi", "tabDatabase", "tabUnor")
        fun menuMap(profileId: String): Map<String, Boolean> {
            val allowed = permissions.allowedMenuIdsByProfile?.get(profileId)
                ?: permissions.allowedMenuIds
                ?: if (profileId == "ponorogo") ponorogoMenuIds else bkdMenuIds
            val ids = if (profileId == "ponorogo") ponorogoMenuIds else bkdMenuIds
            return ids.associateWith { id -> id == "tabBeranda" || id in allowed }
        }
        val tauriMenus = mapOf("jatim" to menuMap("jatim"), "madiun" to menuMap("madiun"), "ponorogo" to menuMap("ponorogo"))
        permsMap["menus"] = tauriMenus

        val canonical = linkedMapOf<String, Any?>(
            "username" to username,
            "displayName" to username,
            "passwordHash" to passwordHash,
            "role" to role.name.lowercase(),
            "isAdmin" to (role == AppRole.ADMIN),
            "permissions" to permsMap,
            "createdAtMs" to createdAtMs,
            "updatedAtMs" to updatedAtMs,
            "updatedAt" to updatedAtMs,
            "createdAt" to createdAtMs,
        )
        // Preserve unknown existing top-level fields by merging (canonical keys overwrite)
        val merged = linkedMapOf<String, Any?>()
        if (existing != null) {
            decodeFieldsMap(existing).forEach { (k, v) -> merged[k] = v }
        }
        legacyServerCredentialFields.forEach(merged::remove)
        canonical.forEach { (k, v) -> merged[k] = v }
        // Keep a couple of common aliases in sync
        merged["password_hash"] = passwordHash
        merged["userName"] = username
        merged["displayName"] = username
        return encodeFieldsMap(merged)
    }

    // -------------------- Utilities --------------------

    private fun norm(s: String): String = s.trim().lowercase().replace("\\s+".toRegex(), "")

    private fun hashPassword(password: String): String =
        BCrypt.withDefaults().hashToString(12, password.toCharArray())

    private fun verifyPassword(password: String, hash: String): Boolean = runCatching {
        if (hash.isBlank()) return false
        BCrypt.verifyer().verify(password.toCharArray(), hash).verified
    }.getOrDefault(false)

    private fun normalizePerms(role: AppRole, permissions: AppPermissions): AppPermissions {
        return if (role == AppRole.ADMIN) {
            AppPermissions.AdminFull.copy(allowEditServerCreds = permissions.allowEditServerCreds)
        } else {
            permissions.copy(role = AppRole.USER)
        }
    }

    private fun preferredDocId(existingId: String?, username: String): String {
        val base = existingId?.trim().takeUnless { it.isNullOrBlank() } ?: norm(username)
        val clean = base.replace(Regex("[^a-zA-Z0-9_.-]"), "_").trim('_')
        return clean.ifBlank { UUID.randomUUID().toString() }
    }

    private fun sanitizeCred(c: LocalServerCredential): LocalServerCredential =
        LocalServerCredential(c.username.trim(), c.password, c.imei.trim())

    private fun sanitizeServerCredsMap(map: Map<String, LocalServerCredential>): Map<String, LocalServerCredential> =
        map.mapValues { (_, v) -> sanitizeCred(v) }.filterValues { !isBlankCred(it) }

    private fun isBlankCred(c: LocalServerCredential): Boolean =
        c.username.isBlank() && c.password.isBlank() && c.imei.isBlank()

    private fun firstString(map: Map<String, Any?>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> map[k]?.toStringValue()?.takeIf { it.isNotBlank() } }

    private fun firstLong(map: Map<String, Any?>, vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { k -> toLong(map[k]) }

    private fun firstBool(map: Map<String, Any?>, vararg keys: String): Boolean? =
        keys.firstNotNullOfOrNull { k -> toBool(map[k]) }

    private fun toStringSet(v: Any?): Set<String>? = when (v) {
        null -> null
        is Set<*> -> v.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }.toSet()
        is List<*> -> v.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }.toSet()
        is Array<*> -> v.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }.toSet()
        is String -> v.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        else -> null
    }

    private fun parseTauriMenuMap(v: Map<*, *>): Map<String, Set<String>>? {
        val out = linkedMapOf<String, Set<String>>()
        val bkdIds = setOf("tabBeranda", "tabPresensi", "tabPerizinan", "tabLaporan", "tabLokasiAbsen", "tabPresetOtomatisasi", "tabWeb")
        val ponorogoIds = setOf("tabBeranda", "tabAbsen", "tabLog", "tabInputAktivitas", "tabAktivitas", "tabReview", "tabIzin", "tabLogPresensiInstansi", "tabReport", "tabAutomasi", "tabDatabase", "tabUnor")
        v.forEach { (key, value) ->
            val profileId = key?.toString()?.trim()?.lowercase().orEmpty()
            val menuMap = value as? Map<*, *> ?: return@forEach
            val allowedIds = (if (profileId == "ponorogo") ponorogoIds else bkdIds).filter { id ->
                val enabled = menuMap[id]
                id == "tabBeranda" || enabled is Boolean && enabled
            }.toSet()
            out[profileId] = allowedIds
        }
        return out.ifEmpty { null }
    }

    private data class AdminAuthRecord(
        val username: String,
        val passwordHash: String,
        val pinHash: String,
        val pinEncrypted: String,
        val updatedAt: Long,
    )

    private fun parseAdminAuthDocument(doc: FsAccountDoc?): AdminAuthRecord? {
        val d = doc ?: return null
        val flat = decodeFieldsMap(d.rawFields)
        val username = firstString(flat, "adminUsername", "username", "admin_username")?.trim()?.lowercase().orEmpty().ifBlank { "admin" }
        val passwordHash = firstString(flat, "passwordHash", "password_hash") ?: ""
        val pinHash = firstString(flat, "pinHash", "pin_hash") ?: ""
        val pinEncrypted = firstString(flat, "pinEncrypted", "pin_encrypted") ?: ""
        val updatedAt = firstLong(flat, "updatedAt", "updated_at") ?: parseFirestoreTimestampMs(flat["updatedAt"])
            ?: d.updatedAtMs
        return if (passwordHash.isBlank() && pinHash.isBlank() && pinEncrypted.isBlank()) null
        else AdminAuthRecord(username, passwordHash, pinHash, pinEncrypted, updatedAt)
    }

    private fun verifyAdminPassword(password: String, admin: AdminAuthRecord): Boolean =
        verifyPassword(password, admin.passwordHash) ||
            LegacyAdminCredentialCodec.verifyPinLayered(password, admin.pinHash) ||
            LegacyAdminCredentialCodec.decrypt(admin.pinEncrypted, config.appSecret) == password

    private fun toProfileMenuMap(v: Any?): Map<String, Set<String>>? {
        val m = v as? Map<*, *> ?: return null
        val out = linkedMapOf<String, Set<String>>()
        m.forEach { (k, value) ->
            val pid = k?.toString()?.trim().orEmpty()
            if (pid.isBlank()) return@forEach
            val set = toStringSet(value) ?: emptySet()
            out[pid] = set
        }
        return out.ifEmpty { null }
    }

    private fun toBool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull() ?: when (v.trim().lowercase()) {
            "1", "yes", "y", "true", "admin" -> true
            "0", "no", "n", "false", "user" -> false
            else -> null
        }
        is Number -> when (v.toInt()) { 0 -> false; 1 -> true; else -> null }
        else -> null
    }

    private fun toLong(v: Any?): Long? = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is Float -> v.toLong()
        is String -> v.toLongOrNull() ?: parseIsoToEpochMs(v)
        else -> null
    }

    private fun parseIsoToEpochMs(s: String): Long? = runCatching {
        java.time.Instant.parse(s.trim()).toEpochMilli()
    }.getOrNull()

    private fun parseFirestoreTimestampMs(v: Any?): Long? = when (v) {
        is String -> parseIsoToEpochMs(v)
        is Map<*, *> -> (v["seconds"] ?: v["_seconds"])?.let { toLong(it)?.times(1000) }
        else -> null
    }

    private fun Any?.toStringValue(): String = when (this) {
        null -> ""
        is String -> this
        is Number, is Boolean -> this.toString()
        else -> this.toString()
    }

    private fun String?.takeIfLooksLikeHash(): String? {
        val s = this?.trim().orEmpty()
        if (s.length < 20) return null
        return if (s.startsWith("$2a$") || s.startsWith("$2b$") || s.startsWith("$2y$")) s else null
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        ?: (this[key] as? JsonPrimitive)?.toString()?.trim('"')
}

    internal object LegacyAdminCredentialCodec {
        private const val PIN_SALT = "presensi.pin.salt.v1"
        private const val OPENSSL_HEADER = "Salted__"
        private const val AES_KEY_BYTES = 32
        private const val AES_IV_BYTES = 16

        fun hashPinLayered(pin: String): String {
            val first = sha256("$PIN_SALT:$pin")
            val second = sha256(first)
            return "sha256$" + sha256("$second:$PIN_SALT")
        }

        fun verifyPinLayered(pin: String, hash: String): Boolean {
            if (hash.isBlank()) return false
            return MessageDigest.isEqual(hashPinLayered(pin).toByteArray(Charsets.UTF_8), hash.toByteArray(Charsets.UTF_8)) || hash == pin
        }

        fun encrypt(password: String, appSecret: String): String {
            val salt = ByteArray(8).also(SecureRandom()::nextBytes)
            val (key, iv) = deriveKeyAndIv(appSecret, salt)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            return java.util.Base64.getEncoder().encodeToString(OPENSSL_HEADER.toByteArray(Charsets.US_ASCII) + salt + encrypted)
        }

        fun decrypt(encrypted: String, appSecret: String): String? = runCatching {
            if (encrypted.isBlank()) return null
            val bytes = java.util.Base64.getDecoder().decode(encrypted.trim())
            val header = OPENSSL_HEADER.toByteArray(Charsets.US_ASCII)
            require(bytes.size > header.size + 8 && bytes.copyOfRange(0, header.size).contentEquals(header))
            val salt = bytes.copyOfRange(header.size, header.size + 8)
            val (key, iv) = deriveKeyAndIv(appSecret, salt)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(bytes.copyOfRange(header.size + 8, bytes.size)), Charsets.UTF_8)
        }.getOrNull()

        private fun deriveKeyAndIv(passphrase: String, salt: ByteArray): Pair<ByteArray, ByteArray> {
            val password = passphrase.toByteArray(Charsets.UTF_8)
            val requiredBytes = AES_KEY_BYTES + AES_IV_BYTES
            val derived = ByteArray(requiredBytes)
            var written = 0
            var previous = ByteArray(0)
            while (written < requiredBytes) {
                val digest = MessageDigest.getInstance("MD5")
                digest.update(previous)
                digest.update(password)
                digest.update(salt)
                previous = digest.digest()
                val count = minOf(previous.size, requiredBytes - written)
                previous.copyInto(derived, written, 0, count)
                written += count
            }
            return derived.copyOfRange(0, AES_KEY_BYTES) to derived.copyOfRange(AES_KEY_BYTES, requiredBytes)
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

