package id.tetuko.app.service

import id.tetuko.core.domain.model.AppPermissions
import id.tetuko.core.domain.model.LocalAppAccountSafe
import id.tetuko.core.domain.model.LocalServerCredential

/**
 * Abstraction for app-account management/auth (desktop runtime adapter wired at startup).
 * Methods are synchronous from caller perspective but may do IO internally in implementations;
 * callers should invoke off the UI thread when used from desktop UI event handlers if needed.
 *
 * Implementations may be local-only (encrypted device store) or remote-backed
 * (e.g. Firestore) depending on runtime configuration.
 */
interface LocalAccountStore {
    fun isAvailable(): Boolean = true
    fun hasAccounts(): Boolean
    fun listAccounts(): List<LocalAppAccountSafe>
    fun getAccount(id: String): LocalAppAccountSafe?
    fun findByUsername(username: String): LocalAppAccountSafe?

    /**
     * Verifies credentials and returns safe account on success.
     * Returns null for invalid credentials. Implementations should throw on
     * configuration/unavailable/network/system errors so callers can distinguish
     * "wrong password" from "service unavailable".
     */
    fun authenticate(username: String, password: String): LocalAppAccountSafe?

    /** Ensures a bootstrap admin exists when store is empty; returns true if created. */
    fun ensureBootstrapAdmin(defaultUsername: String = "admin", defaultPassword: String = "admin"): Boolean

    fun createAccount(
        username: String,
        password: String,
        role: id.tetuko.core.domain.model.AppRole,
        permissions: AppPermissions,
        serverCreds: Map<String, LocalServerCredential> = emptyMap(),
    ): LocalAppAccountSafe

    fun updateAccount(
        id: String,
        username: String? = null,
        newPassword: String? = null,
        role: id.tetuko.core.domain.model.AppRole? = null,
        permissions: AppPermissions? = null,
        serverCreds: Map<String, LocalServerCredential>? = null,
    ): LocalAppAccountSafe

    fun deleteAccount(id: String): Boolean

    fun getServerCreds(accountId: String, profileId: String): LocalServerCredential?
    fun setServerCreds(accountId: String, profileId: String, creds: LocalServerCredential?): LocalAppAccountSafe

    fun getServerCredAccounts(accountId: String, profileId: String): List<LocalServerCredential> =
        listOfNotNull(getServerCreds(accountId, profileId))

    fun setServerCredAccounts(
        accountId: String,
        profileId: String,
        accounts: List<LocalServerCredential>,
        activeUsername: String?,
    ): LocalAppAccountSafe {
        val selected = accounts.firstOrNull { it.username.equals(activeUsername, ignoreCase = true) }
            ?: accounts.firstOrNull()
        return setServerCreds(accountId, profileId, selected)
    }

    /** Failed-attempt counter and lock cooldown (epoch ms), persisted by the platform adapter. */
    fun getLockState(): Pair<Int, Long> = 0 to 0L
    fun setLockState(attempts: Int, cooldownUntilMs: Long)

    /** True while the bootstrap admin still uses the well-known default password. */
    fun isDefaultAdminPasswordPending(): Boolean = false
    fun clearDefaultAdminPasswordPending()

    /** Human-readable account/auth backend label for UI status (optional). */
    fun accountSourceLabel(): String? = null

    /** Human-readable configuration/backend error, if the store is not usable (optional). */
    fun accountSourceError(): String? = null

    /** True when the backend is properly configured for primary use (optional). */
    fun isBackendConfigured(): Boolean = true
}
