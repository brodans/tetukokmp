package id.tetuko.core.domain.model

import kotlinx.serialization.Serializable

/**
 * Local app-account model (feasible desktop-first adapter for Tauri account/permission parity).
 *
 * Accounts are stored locally (encrypted on desktop). Passwords are stored as hashes only.
 * This is NOT full cloud/Firestore multi-user parity, but provides local CRUD/assignment/auth gate.
 */
@Serializable
data class LocalServerCredential(
    val username: String = "",
    val password: String = "",
    val imei: String = "",
)

@Serializable
data class LocalAppAccount(
    val id: String,
    val username: String,
    val passwordHash: String,
    val role: AppRole = AppRole.USER,
    val permissions: AppPermissions = AppPermissions.defaultUser(),
    /** Per-profile server credentials assigned to this app account (profileId -> creds). */
    val serverCreds: Map<String, LocalServerCredential> = emptyMap(),
    /** Multi-account credentials per profile; serverCreds remains the legacy/active mirror. */
    val serverCredAccounts: Map<String, List<LocalServerCredential>> = emptyMap(),
    val activeServerCreds: Map<String, String> = emptyMap(),
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
) {
    fun toSafe(): LocalAppAccountSafe = LocalAppAccountSafe(
        id = id,
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

@Serializable
data class LocalAppAccountSafe(
    val id: String,
    val username: String,
    val role: AppRole = AppRole.USER,
    val permissions: AppPermissions = AppPermissions.defaultUser(),
    val serverCreds: Map<String, LocalServerCredential> = emptyMap(),
    val serverCredAccounts: Map<String, List<LocalServerCredential>> = emptyMap(),
    val activeServerCreds: Map<String, String> = emptyMap(),
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

fun LocalAppAccount.serverCredentials(profileId: String): List<LocalServerCredential> =
    serverCredAccounts[profileId].orEmpty().ifEmpty {
        listOfNotNull(serverCreds[profileId]?.takeUnless {
            it.username.isBlank() && it.password.isBlank() && it.imei.isBlank()
        })
    }

fun LocalAppAccount.activeServerCredential(profileId: String): LocalServerCredential? {
    val credentials = serverCredentials(profileId)
    val activeUsername = activeServerCreds[profileId] ?: serverCreds[profileId]?.username
    return credentials.firstOrNull { it.username.equals(activeUsername, ignoreCase = true) }
        ?: credentials.firstOrNull()
}

fun LocalAppAccountSafe.serverCredentials(profileId: String): List<LocalServerCredential> =
    serverCredAccounts[profileId].orEmpty().ifEmpty {
        listOfNotNull(serverCreds[profileId]?.takeUnless {
            it.username.isBlank() && it.password.isBlank() && it.imei.isBlank()
        })
    }

fun LocalAppAccountSafe.activeServerCredential(profileId: String): LocalServerCredential? {
    val credentials = serverCredentials(profileId)
    val activeUsername = activeServerCreds[profileId] ?: serverCreds[profileId]?.username
    return credentials.firstOrNull { it.username.equals(activeUsername, ignoreCase = true) }
        ?: credentials.firstOrNull()
}

@Serializable
data class LocalAppAccountStoreData(
    val version: Int = 2,
    val accounts: List<LocalAppAccount> = emptyList(),
)
