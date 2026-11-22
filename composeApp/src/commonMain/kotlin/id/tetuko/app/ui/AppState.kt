package id.tetuko.app.ui

import androidx.compose.runtime.*
import id.tetuko.app.service.AppServices
import id.tetuko.app.service.AuthService
import id.tetuko.core.domain.model.*

val LocalAppPermissions = compositionLocalOf { AppPermissions.AdminFull }
val LocalAppState = staticCompositionLocalOf<AppState?> { null }

/** Result of the application-gate authentication. */
data class AppLoginResult(
    val ok: Boolean,
    val message: String? = null,
    /** True only for invalid credentials (used for lockout counting). */
    val credentialFailure: Boolean = false,
)

expect fun isSystemDarkModeDetected(): Boolean

class AppState {
    var isDarkMode by mutableStateOf(isSystemDarkModeDetected())
    var appAuthed by mutableStateOf(false)
    var permissions by mutableStateOf(AppPermissions.AdminFull)
    /** Id of the authenticated local app account (null on legacy/fallback auth). */
    var currentAccountId by mutableStateOf<String?>(null)
    /** Identity username of the authenticated app account (separate from server display name). */
    var appAccountUsername by mutableStateOf("")
    /** Bootstrap admin still uses the default password: force a change after login. */
    var mustChangeDefaultPassword by mutableStateOf(false)
    var currentProfile by mutableStateOf<ServerProfile?>(null)
    var pendingProfile by mutableStateOf<ServerProfile?>(null) // selected in hub, waiting server login
    var pendingPageId by mutableStateOf<String?>(null) // optional deep-link target after server login
    /** Deep-link consumed after the application gate is passed (startup hardening). */
    var deferredDeepLink by mutableStateOf<Pair<String, String>?>(null)
    var activePageId by mutableStateOf("tabBeranda")
    var showLogoutConfirm by mutableStateOf(false)

    // simple in-memory session placeholders
    var appUsername by mutableStateOf("")
    var serverDisplayName by mutableStateOf("Pengguna")
    var serverNip by mutableStateOf("")

    val route: AppRoute
        get() = when {
            !appAuthed -> AppRoute.Masuk
            currentProfile == null -> AppRoute.Login
            else -> AppRoute.App(currentProfile!!.id, sanitizeActivePageFor(currentProfile!!))
        }

    /** Profile IDs this user may access; null means all profiles (admin/full access). */
    fun allowedProfileIds(): Set<String>? =
        if (permissions.role == AppRole.ADMIN) null
        else DefaultProfiles.all.map { it.id }.filter { permissions.canAccessProfile(it) }.toSet()

    val hasAllowedProfiles: Boolean
        get() = DefaultProfiles.all.any { permissions.canAccessProfile(it.id) }

    /** Feature-level permission helper aligned with granular [AppPermissions] flags. */
    fun canUseFeature(feature: String): Boolean = permissions.canUseFeature(feature)

    fun canSearchPegawai(): Boolean = permissions.allowSearchPegawai || permissions.canUseFeature("search_pegawai")
    fun canSearchIzin(): Boolean = permissions.allowSearchIzin || permissions.canUseFeature("search_izin")
    fun canSearchInstansi(): Boolean = permissions.allowSearchInstansi || permissions.canUseFeature("search_instansi")
    fun canSearchDatabase(): Boolean = permissions.allowSearchDatabase || permissions.canUseFeature("search_database")
    fun canSearchProduktivitas(): Boolean = permissions.allowSearchProduktivitas || permissions.canUseFeature("search_produktivitas")
    fun canSearchPresensi(): Boolean = permissions.allowSearchPresensi || permissions.canUseFeature("search_presensi")
    fun canControlAutomation(): Boolean = permissions.allowAutomationControl || permissions.canUseFeature("automation_control")
    fun canPerformSensitiveActions(): Boolean = permissions.allowSensitiveActions || permissions.canUseFeature("sensitive_actions")

    /** Account management is admin-only; password self-change is available to all accounts. */
    fun canManageAccounts(): Boolean = appAuthed && permissions.role == AppRole.ADMIN

    fun currentAccount(): LocalAppAccountSafe? {
        val id = currentAccountId ?: return null
        return AppServices.localAccountStore?.getAccount(id)
    }

    /** Server credentials assigned to the current app account for [profileId]. */
    fun serverCredsFor(profileId: String): LocalServerCredential? {
        val id = currentAccountId ?: return null
        return AppServices.localAccountStore?.getServerCreds(id, profileId)
    }

    fun serverCredAccountsFor(profileId: String): List<LocalServerCredential> {
        val id = currentAccountId ?: return emptyList()
        return AppServices.localAccountStore?.getServerCredAccounts(id, profileId).orEmpty()
    }

    fun selectServerCredential(profileId: String, credentials: List<LocalServerCredential>, username: String) {
        val id = currentAccountId ?: return
        val store = AppServices.localAccountStore ?: return
        if (credentials.none { it.username.equals(username, ignoreCase = true) }) return
        runCatching { store.setServerCredAccounts(id, profileId, credentials, username) }
    }

    /**
     * Authenticates the application gate against the configured account store.
     * Falls back to the permissive legacy gate when no store is available on the platform.
     */
    fun loginApp(username: String, password: String): AppLoginResult {
        val store = AppServices.localAccountStore
        if (store == null) {
            // Legacy fallback (platform without account adapter).
            loginAppLegacy(username)
            return AppLoginResult(true)
        }
        val cfgError = runCatching { store.accountSourceError() }.getOrNull()
        if (!cfgError.isNullOrBlank()) {
            return AppLoginResult(false, cfgError)
        }
        val account = try {
            store.authenticate(username, password)
        } catch (e: Exception) {
            val msg = e.message?.takeIf { it.isNotBlank() }
                ?: "Gagal mengautentikasi akun. Periksa konfigurasi/koneksi."
            return AppLoginResult(false, msg)
        }
        if (account == null) {
            return AppLoginResult(false, "ID pengguna atau kata sandi salah.", credentialFailure = true)
        }
        runCatching { store.setLockState(0, 0L) }
        applyAuthenticatedAccount(username.trim(), account.id, account.permissions)
        mustChangeDefaultPassword = account.role == AppRole.ADMIN &&
            runCatching { store.isDefaultAdminPasswordPending() }.getOrDefault(false)
        return AppLoginResult(true)
    }

    /** Applies an already-authenticated account (used by restore and legacy flows). */
    fun applyAuthenticatedAccount(username: String, accountId: String?, perms: AppPermissions? = null) {
        mustChangeDefaultPassword = false
        runCatching { AuthService.logoutLocal() }
        AppServices.clearServerSession()
        appUsername = username
        appAccountUsername = username
        currentAccountId = accountId
        appAuthed = true
        permissions = perms ?: AppPermissions.AdminFull
        currentProfile = null
        pendingProfile = null
        pendingPageId = null
        serverDisplayName = "Pengguna"
        serverNip = ""
        activePageId = "tabBeranda"
        showLogoutConfirm = false
    }

    private fun loginAppLegacy(username: String) {
        applyAuthenticatedAccount(username, null, AppPermissions.AdminFull)
    }

    /** Returns the first menu/page id allowed for [profile], or Beranda as a safe fallback. */
    fun firstAllowedPageId(profile: ServerProfile): String {
        val pages = AppNavigation.pagesFor(profile)
        val firstAllowed = pages.firstOrNull { permissions.canAccessMenu(profile.id, it.id) }?.id
        if (firstAllowed != null) return firstAllowed
        return if (permissions.canAccessMenu(profile.id, "tabBeranda")) "tabBeranda"
        else pages.firstOrNull()?.id ?: "tabBeranda"
    }

    fun sanitizeActivePageFor(profile: ServerProfile): String {
        if (permissions.canAccessMenu(profile.id, activePageId)) return activePageId
        val safe = firstAllowedPageId(profile)
        if (safe != activePageId) activePageId = safe
        return activePageId
    }

    fun completeDefaultPasswordChange(newPassword: String): AppLoginResult {
        val id = currentAccountId ?: return AppLoginResult(false, "Akun tidak dikenali.")
        val store = AppServices.localAccountStore ?: return AppLoginResult(false, "Adapter akun tidak tersedia.")
        val result = runCatching { store.updateAccount(id, newPassword = newPassword) }
        if (result.isFailure) return AppLoginResult(false, result.exceptionOrNull()?.message ?: "Gagal mengubah password.")
        runCatching { store.clearDefaultAdminPasswordPending() }
        mustChangeDefaultPassword = false
        return AppLoginResult(true, "Password admin berhasil diubah.")
    }

    fun logoutApp() {
        mustChangeDefaultPassword = false
        runCatching { AuthService.logoutLocal() }
        AppServices.clearServerSession()
        appAuthed = false
        permissions = AppPermissions.AdminFull
        currentAccountId = null
        appAccountUsername = ""
        currentProfile = null
        pendingProfile = null
        pendingPageId = null
        deferredDeepLink = null
        appUsername = ""
        serverDisplayName = "Pengguna"
        serverNip = ""
        activePageId = "tabBeranda"
        showLogoutConfirm = false
    }

    /** Applies a startup deep link once the app-level auth gate has been passed. */
    fun consumeDeferredDeepLink() {
        val target = deferredDeepLink ?: return
        deferredDeepLink = null
        val (profileId, pageId) = target
        val profile = DefaultProfiles.all.firstOrNull { it.id == profileId } ?: return
        if (!permissions.canAccessProfile(profile.id)) return
        val resolved = AppNavigation.resolveAccessiblePageId(profile, pageId, permissions)
        if (currentProfile?.id == profile.id && AppServices.hasActiveSession) {
            navigateTo(resolved)
        } else {
            pendingProfile = profile
            pendingPageId = resolved
        }
    }

    fun chooseProfile(profile: ServerProfile) {
        if (!permissions.canAccessProfile(profile.id)) return
        pendingProfile = profile
        pendingPageId = null
    }

    fun cancelPendingProfile() {
        pendingProfile = null
        pendingPageId = null
    }

    fun completeServerLogin(
        displayName: String = "Pengguna",
        nip: String = "",
        imei: String = "",
        serverUsername: String = "",
        serverPassword: String = "",
    ) {
        val p = pendingProfile ?: return
        if (!permissions.canAccessProfile(p.id)) {
            pendingProfile = null
            pendingPageId = null
            return
        }
        saveServerCredentials(p, serverUsername, serverPassword, imei)
        val requestedPage = pendingPageId
        currentProfile = p
        pendingProfile = null
        pendingPageId = null
        serverDisplayName = displayName.ifBlank { "Pengguna" }
        serverNip = nip
        activePageId = when {
            requestedPage != null && permissions.canAccessMenu(p.id, requestedPage) -> requestedPage
            else -> {
                val defaultId = AppNavigation.defaultPageId(p)
                if (permissions.canAccessMenu(p.id, defaultId)) defaultId else firstAllowedPageId(p)
            }
        }
    }

    fun saveServerCredentials(profile: ServerProfile, username: String, password: String, imei: String) {
        if (profile.mode != AttendanceMode.BKD_RPC || !permissions.allowEditServerCreds || username.isBlank()) return
        val accountId = currentAccountId ?: return
        val store = AppServices.localAccountStore ?: return
        val current = store.getServerCreds(accountId, profile.id)
        val nextCreds = LocalServerCredential(
            username = username.trim(),
            password = password,
            imei = imei.trim().ifBlank { current?.imei.orEmpty() },
        )
        runCatching { store.setServerCreds(accountId, profile.id, nextCreds) }
    }

    fun switchServer() {
        runCatching { AuthService.logoutLocal() }
        AppServices.clearServerSession()
        currentProfile = null
        pendingProfile = null
        pendingPageId = null
        serverDisplayName = "Pengguna"
        serverNip = ""
        activePageId = "tabBeranda"
        showLogoutConfirm = false
    }

    /** Global token-expiry handler: keeps the app authenticated but returns to server selection. */
    fun forceServerLogout() {
        if (!appAuthed && currentProfile == null && pendingProfile == null) return
        runCatching { AuthService.logoutLocal() }
        AppServices.clearServerSession()
        currentProfile = null
        pendingProfile = null
        pendingPageId = null
        serverDisplayName = "Pengguna"
        serverNip = ""
        activePageId = "tabBeranda"
        showLogoutConfirm = false
    }

    fun navigateTo(pageId: String) {
        val p = currentProfile ?: return
        if (!permissions.canAccessMenu(p.id, pageId)) return
        activePageId = pageId
    }

    fun toggleDarkMode() { isDarkMode = !isDarkMode }
}

@Composable
fun rememberAppState(): AppState {
    val state = remember { AppState() }
    DisposableEffect(state) {
        val client = AppServices.rpcClient
        val previous = client.onInvalidToken
        client.onInvalidToken = { state.forceServerLogout() }
        onDispose { client.onInvalidToken = previous }
    }
    return state
}
