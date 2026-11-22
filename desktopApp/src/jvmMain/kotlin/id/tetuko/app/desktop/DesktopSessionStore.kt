package id.tetuko.app.desktop

import id.tetuko.app.service.AppServices
import id.tetuko.app.ui.AppState
import id.tetuko.core.domain.model.AppNavigation
import id.tetuko.core.domain.model.AppPermissions
import id.tetuko.core.domain.model.AppRole
import id.tetuko.core.domain.model.DefaultProfiles
import id.tetuko.core.domain.model.ProfileAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * Desktop session/UI persistence and recovery (SimpMusic-like convenience).
 *
 * Session tokens (api_key) and server context are stored in [DesktopSecureStore]
 * (AES-GCM, owner-only key file) instead of readable Preferences. Sessions expire
 * after [SESSION_TTL_MS] so a stale token cannot silently restore forever; expired
 * sessions fall back to the normal server-login flow.
 */
object DesktopSessionStore {
    private val SESSION_TTL_MS: Long = TimeUnit.DAYS.toMillis(30)

    fun restore(appState: AppState) {
        val data = DesktopSecureStore.load()
        if (data.isEmpty()) return

        runCatching {
            appState.isDarkMode = data["isDarkMode"]?.toBooleanStrictOrNull() ?: id.tetuko.app.ui.isSystemDarkModeDetected()

            val appAuthed = data["appAuthed"]?.toBooleanStrictOrNull() ?: false
            if (!appAuthed) {
                // Defensive reset for corrupt/partial state.
                appState.appAuthed = false
                appState.currentProfile = null
                appState.pendingProfile = null
                appState.pendingPageId = null
                return
            }

            appState.appAuthed = true
            appState.appUsername = data["appUsername"].orEmpty().trim()
            appState.appAccountUsername = data["appAccountUsername"].orEmpty().trim()
            appState.currentAccountId = data["currentAccountId"]?.trim()?.takeIf { it.isNotBlank() }

            // Account store is the source of truth for role/permissions when available.
            val accId = appState.currentAccountId
            val store = AppServices.localAccountStore
            val hasAccounts = if (accId == null) {
                runCatching { store?.hasAccounts() == true }.getOrDefault(false)
            } else false
            if (accId == null && hasAccounts) {
                // Legacy session without account binding must pass the app gate again.
                appState.appAuthed = false
                appState.currentProfile = null
                appState.pendingProfile = null
                appState.pendingPageId = null
                appState.currentAccountId = null
                appState.appAccountUsername = ""
                appState.activePageId = "tabBeranda"
                AppServices.clearServerSession()
                clearServerSessionOnly()
                return
            }
            if (accId == null) {
                // Legacy session on a platform without accounts: restore the
                // permission snapshot as-is (no account source of truth available).
                restorePermissions(appState, data)
            }
            if (accId != null) {
                val account = runCatching { store?.getAccount(accId) }.getOrNull()
                // If account can't be validated (missing or backend unreachable), require re-login.
                // This avoids keeping a stale privileged session when the source of truth is unavailable.
                if (account == null || store == null) {
                    appState.appAuthed = false
                    appState.currentProfile = null
                    appState.pendingProfile = null
                    appState.pendingPageId = null
                    appState.currentAccountId = null
                    appState.appAccountUsername = ""
                    appState.activePageId = "tabBeranda"
                    AppServices.clearServerSession()
                    clearServerSessionOnly()
                    return
                }
                appState.permissions = account.permissions
                if (appState.appAccountUsername.isBlank()) appState.appAccountUsername = account.username
                appState.mustChangeDefaultPassword = account.role == AppRole.ADMIN &&
                    runCatching { store.isDefaultAdminPasswordPending() }.getOrDefault(false)
            }

            val profileId = data["currentProfileId"].orEmpty().trim()
            val apiKey = data["apiKey"].orEmpty()
            val savedAt = data["savedAt"]?.toLongOrNull() ?: 0L
            val expired = savedAt <= 0L || System.currentTimeMillis() - savedAt > SESSION_TTL_MS

            val profile = DefaultProfiles.all.firstOrNull { it.id == profileId }
            val profileAllowed = profile != null && appState.permissions.canAccessProfile(profile.id)

            if (profile == null || !profileAllowed || apiKey.isBlank() || expired) {
                // Keep app-level auth when possible but clear server session and fall back to hub/login.
                appState.currentProfile = null
                appState.pendingProfile = null
                appState.pendingPageId = null
                appState.serverDisplayName = "Pengguna"
                appState.serverNip = ""
                appState.activePageId = "tabBeranda"
                AppServices.clearServerSession()
                if (expired || apiKey.isBlank() || profile == null || !profileAllowed) {
                    clearServerSessionOnly()
                }
                return
            }

            val restoredPageId = data["activePageId"].orEmpty().ifBlank { "tabBeranda" }
            val safePageId = when {
                appState.permissions.canAccessMenu(profile.id, restoredPageId) -> restoredPageId
                else -> {
                    val defaultId = AppNavigation.defaultPageId(profile)
                    if (appState.permissions.canAccessMenu(profile.id, defaultId)) defaultId
                    else AppNavigation.pagesFor(profile)
                        .firstOrNull { appState.permissions.canAccessMenu(profile.id, it.id) }
                        ?.id ?: "tabBeranda"
                }
            }

            appState.currentProfile = profile
            appState.pendingProfile = null
            appState.pendingPageId = null
            appState.activePageId = safePageId
            appState.serverDisplayName = data["serverDisplayName"].orEmpty().ifBlank { "Pengguna" }
            appState.serverNip = data["serverNip"].orEmpty().trim()

            AppServices.clearServerSession()
            AppServices.setActiveProfile(profile)
            AppServices.updateServerContext(
                apiKey = apiKey,
                imei = data["imei"].orEmpty(),
                lastLatLong = data["lastLatLong"].orEmpty(),
            )
            AppServices.ponorogoProfile = data["ponorogoProfile"]
                ?.takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() }
        }.onFailure {
            // Corrupt/invalid store must never crash restore; keep app usable.
            runCatching { AppServices.clearServerSession() }
        }
    }

    fun save(appState: AppState) {
        runCatching {
            val ctx = AppServices.serverContext.value
            val active = appState.appAuthed &&
                appState.currentProfile != null &&
                ctx.apiKey.isNotBlank()
            val values = buildMap {
                put("savedAt", System.currentTimeMillis().toString())
                put("isDarkMode", appState.isDarkMode.toString())
                put("appAuthed", appState.appAuthed.toString())
                put("appUsername", appState.appUsername)
                put("appAccountUsername", appState.appAccountUsername)
                put("currentAccountId", appState.currentAccountId ?: "")
                put("role", appState.permissions.role.name)
                put("allowEditServerCreds", appState.permissions.allowEditServerCreds.toString())
                // Granular feature flags (best-effort parity persistence)
                put("allowSearchPegawai", appState.permissions.allowSearchPegawai.toString())
                put("allowSearchIzin", appState.permissions.allowSearchIzin.toString())
                put("allowSearchInstansi", appState.permissions.allowSearchInstansi.toString())
                put("allowSearchDatabase", appState.permissions.allowSearchDatabase.toString())
                put("allowSearchProduktivitas", appState.permissions.allowSearchProduktivitas.toString())
                put("allowSearchPresensi", appState.permissions.allowSearchPresensi.toString())
                put("allowSearchLaporan", appState.permissions.allowSearchLaporan.toString())
                put("allowSearchUnor", appState.permissions.allowSearchUnor.toString())
                put("allowSearchReview", appState.permissions.allowSearchReview.toString())
                put("allowAutomationControl", appState.permissions.allowAutomationControl.toString())
                put("allowSensitiveActions", appState.permissions.allowSensitiveActions.toString())
                // Profile access & menu allow-list (P0 parity persistence)
                put("profileAccess.jatim", appState.permissions.profiles.jatim.toString())
                put("profileAccess.madiun", appState.permissions.profiles.madiun.toString())
                put("profileAccess.ponorogo", appState.permissions.profiles.ponorogo.toString())
                put(
                    "allowedMenuIds",
                    appState.permissions.allowedMenuIds?.joinToString(",") ?: "__ALL__",
                )
                put("allowedMenuIdsByProfile", encodeMenuAllowByProfile(appState.permissions.allowedMenuIdsByProfile))

                put("currentProfileId", appState.currentProfile?.id ?: "")
                put("activePageId", appState.activePageId)
                put("serverDisplayName", appState.serverDisplayName)
                put("serverNip", appState.serverNip)
                // Persist session context only while a server session is active.
                put("apiKey", if (active) ctx.apiKey else "")
                put("imei", if (active) ctx.imei else "")
                put("lastLatLong", if (active) ctx.lastLatLong else "")
                put(
                    "ponorogoProfile",
                    if (active) runCatching { AppServices.ponorogoProfile?.toString().orEmpty() }.getOrDefault("") else "",
                )
            }
            DesktopSecureStore.save(values)
        }
    }

    fun clearServerSessionOnly() {
        runCatching {
            val data = DesktopSecureStore.load().toMutableMap()
            data["apiKey"] = ""
            data["imei"] = ""
            data["lastLatLong"] = ""
            data["ponorogoProfile"] = ""
            data["currentProfileId"] = ""
            data["activePageId"] = "tabBeranda"
            data["serverDisplayName"] = "Pengguna"
            data["serverNip"] = ""
            DesktopSecureStore.save(data)
        }
    }

    fun clearAll() {
        DesktopSecureStore.clear()
    }


    private fun encodeMenuAllowByProfile(map: Map<String, Set<String>>?): String {
        if (map == null) return ""
        return buildJsonObject {
            map.forEach { (profileId, ids) ->
                put(profileId, buildJsonArray { ids.sorted().forEach { add(JsonPrimitive(it)) } })
            }
        }.toString()
    }

    private fun decodeMenuAllowByProfile(raw: String?): Map<String, Set<String>>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching null
            val result = linkedMapOf<String, Set<String>>()
            obj.entries.forEach { (k, v) ->
                when (v) {
                    is JsonNull -> {
                        // null/absent semantics treated as fallback to global/legacy (do not override)
                    }
                    is JsonArray -> {
                        val set = v.mapNotNull { el ->
                            runCatching { (el as? JsonPrimitive)?.content }.getOrNull()
                                ?: runCatching { el.jsonPrimitive.content }.getOrNull()
                        }.filter { it.isNotBlank() }.toSet()
                        result[k] = set
                    }
                    else -> Unit
                }
            }
            result.ifEmpty { null }
        }.getOrNull()
    }

    private fun restorePermissions(appState: AppState, data: Map<String, String>) {
        val role = data["role"]?.let { runCatching { AppRole.valueOf(it) }.getOrNull() }
        if (role == null) {
            // No persisted role info: keep existing safe default behavior.
            // If app-level auth was persisted, avoid silently escalating to admin on corrupt data.
            val appAuthed = data["appAuthed"]?.toBooleanStrictOrNull() ?: false
            if (appAuthed) appState.permissions = AppPermissions.defaultUser()
            return
        }

        val allowEdit = data["allowEditServerCreds"]?.toBooleanStrictOrNull()
        val base = if (role == AppRole.ADMIN) AppPermissions.AdminFull else AppPermissions.defaultUser()
        val restoredProfiles = if (role == AppRole.ADMIN) {
            base.profiles
        } else {
            val hasAny = listOf("profileAccess.jatim", "profileAccess.madiun", "profileAccess.ponorogo")
                .any { data.containsKey(it) }
            if (hasAny) {
                ProfileAccess(
                    jatim = data["profileAccess.jatim"]?.toBooleanStrictOrNull() ?: base.profiles.jatim,
                    madiun = data["profileAccess.madiun"]?.toBooleanStrictOrNull() ?: base.profiles.madiun,
                    ponorogo = data["profileAccess.ponorogo"]?.toBooleanStrictOrNull() ?: base.profiles.ponorogo,
                )
            } else base.profiles
        }
        val restoredMenuIds = if (role == AppRole.ADMIN) {
            null
        } else {
            when (val raw = data["allowedMenuIds"]) {
                null, "__ALL__" -> base.allowedMenuIds
                "" -> emptySet()
                else -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    .ifEmpty { base.allowedMenuIds }
            }
        }
        val restoredMenuIdsByProfile = if (role == AppRole.ADMIN) {
            null
        } else {
            decodeMenuAllowByProfile(data["allowedMenuIdsByProfile"]) ?: base.allowedMenuIdsByProfile
        }
        appState.permissions = base.copy(
            profiles = restoredProfiles,
            allowedMenuIds = restoredMenuIds,
            allowedMenuIdsByProfile = restoredMenuIdsByProfile,
            allowEditServerCreds = allowEdit ?: base.allowEditServerCreds,
            allowSearchPegawai = data["allowSearchPegawai"]?.toBooleanStrictOrNull() ?: base.allowSearchPegawai,
            allowSearchIzin = data["allowSearchIzin"]?.toBooleanStrictOrNull() ?: base.allowSearchIzin,
            allowSearchInstansi = data["allowSearchInstansi"]?.toBooleanStrictOrNull() ?: base.allowSearchInstansi,
            allowSearchDatabase = data["allowSearchDatabase"]?.toBooleanStrictOrNull() ?: base.allowSearchDatabase,
            allowSearchProduktivitas = data["allowSearchProduktivitas"]?.toBooleanStrictOrNull() ?: base.allowSearchProduktivitas,
            allowSearchPresensi = data["allowSearchPresensi"]?.toBooleanStrictOrNull() ?: base.allowSearchPresensi,
            allowSearchLaporan = data["allowSearchLaporan"]?.toBooleanStrictOrNull() ?: base.allowSearchLaporan,
            allowSearchUnor = data["allowSearchUnor"]?.toBooleanStrictOrNull() ?: base.allowSearchUnor,
            allowSearchReview = data["allowSearchReview"]?.toBooleanStrictOrNull() ?: base.allowSearchReview,
            allowAutomationControl = data["allowAutomationControl"]?.toBooleanStrictOrNull() ?: base.allowAutomationControl,
            allowSensitiveActions = data["allowSensitiveActions"]?.toBooleanStrictOrNull() ?: base.allowSensitiveActions,
        )
    }
}
