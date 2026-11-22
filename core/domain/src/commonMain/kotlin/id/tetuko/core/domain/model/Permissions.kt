package id.tetuko.core.domain.model

import kotlinx.serialization.Serializable

/**
 * Permission/role model (admin/user) with profile/menu access and granular feature flags.
 * Enforcement is applied in navigation/UI; backend/user-management integration can be extended later.
 */
@Serializable
enum class AppRole { ADMIN, USER }

@Serializable
data class ProfileAccess(
    val jatim: Boolean = true,
    val madiun: Boolean = true,
    val ponorogo: Boolean = true,
) {
    fun allowed(profileId: String): Boolean = when (profileId) {
        "jatim" -> jatim
        "madiun" -> madiun
        "ponorogo" -> ponorogo
        else -> false
    }
}

/**
 * Granular permissions (parity-oriented). Defaults are permissive for ADMIN and
 * conservative for restricted USER profiles unless explicitly configured.
 *
 * Menu access parity notes:
 * - [allowedMenuIdsByProfile]: per-profile allow-list (profileId -> allowed page IDs), preferred.
 *   Key present with null/empty handling: missing key falls back to [allowedMenuIds]; explicit set restricts menus.
 * - [allowedMenuIds]: legacy/global allow-list (backward compatibility).
 * - null/absent allow-list means "all menus allowed for that (allowed) profile".
 */
@Serializable
data class AppPermissions(
    val role: AppRole = AppRole.ADMIN,
    val profiles: ProfileAccess = ProfileAccess(),
    val allowedMenuIds: Set<String>? = null, // legacy/global; null = all menus for allowed profiles (admin/default)
    val allowedMenuIdsByProfile: Map<String, Set<String>>? = null, // per-profile menu allow-list (parity)
    val allowEditServerCreds: Boolean = true,
    // Granular feature flags (P0 parity support)
    val allowSearchPegawai: Boolean = (role == AppRole.ADMIN),
    val allowSearchIzin: Boolean = (role == AppRole.ADMIN),
    val allowSearchInstansi: Boolean = (role == AppRole.ADMIN),
    val allowSearchDatabase: Boolean = (role == AppRole.ADMIN),
    val allowSearchProduktivitas: Boolean = (role == AppRole.ADMIN),
    val allowSearchPresensi: Boolean = (role == AppRole.ADMIN),
    val allowSearchLaporan: Boolean = (role == AppRole.ADMIN),
    val allowSearchUnor: Boolean = (role == AppRole.ADMIN),
    val allowSearchReview: Boolean = (role == AppRole.ADMIN),
    val allowAutomationControl: Boolean = (role == AppRole.ADMIN),
    val allowSensitiveActions: Boolean = (role == AppRole.ADMIN),
    val tabPengajuanIzin: Boolean = (role == AppRole.ADMIN),
    val tabLogPresensiTerkini: Boolean = (role == AppRole.ADMIN),
    val tabLogLengkap: Boolean = (role == AppRole.ADMIN),
    val reportPerPegawai: Boolean = (role == AppRole.ADMIN),
    val reportPerPegawaiAktivitas: Boolean = (role == AppRole.ADMIN),
    val reportSkorPerInstansi: Boolean = (role == AppRole.ADMIN),
    val reportAktivitasPerInstansi: Boolean = (role == AppRole.ADMIN),
    val reportRekapTppAktivitas: Boolean = (role == AppRole.ADMIN),
) {
    fun canAccessProfile(profileId: String): Boolean =
        role == AppRole.ADMIN || profiles.allowed(profileId)

    fun canAccessMenu(profileId: String, pageId: String): Boolean {
        if (!canAccessProfile(profileId)) return false
        if (role == AppRole.ADMIN) return true
        // always allow Beranda as safe default
        if (pageId == "tabBeranda") return true
        val perProfile = allowedMenuIdsByProfile
        val allowed = if (perProfile != null && perProfile.containsKey(profileId)) {
            perProfile[profileId] // null entry => allow all for this profile
        } else {
            allowedMenuIds // legacy/global fallback
        }
        return allowed?.contains(pageId) ?: true
    }

    /** Effective menu allow-list for [profileId] (null = all menus allowed). */
    fun effectiveMenuAllowList(profileId: String): Set<String>? {
        val perProfile = allowedMenuIdsByProfile
        return if (perProfile != null && perProfile.containsKey(profileId)) perProfile[profileId]
        else allowedMenuIds
    }

    /** Generic helper for feature-level checks; extend as parity requires. */
    fun canUseFeature(feature: String): Boolean = when (feature) {
        "edit_creds", "edit_server_creds", "edit_login_credentials" -> allowEditServerCreds
        "search_pegawai" -> allowSearchPegawai
        "search_izin" -> allowSearchIzin
        "search_instansi" -> allowSearchInstansi
        "search_database" -> allowSearchDatabase
        "search_produktivitas" -> allowSearchProduktivitas
        "search_presensi" -> allowSearchPresensi
        "search_laporan" -> allowSearchLaporan
        "search_unor" -> allowSearchUnor
        "search_review" -> allowSearchReview
        "automation_control" -> allowAutomationControl
        "sensitive_actions" -> allowSensitiveActions
        "tab_pengajuan_izin", "izin_pengajuan" -> tabPengajuanIzin
        "tab_log_presensi_terkini", "log_presensi_terkini" -> tabLogPresensiTerkini
        "tab_log_lengkap", "log_presensi_lengkap" -> tabLogLengkap
        "report_per_pegawai" -> reportPerPegawai
        "report_per_pegawai_aktivitas" -> reportPerPegawaiAktivitas
        "report_skor_per_instansi" -> reportSkorPerInstansi
        "report_aktivitas_per_instansi" -> reportAktivitasPerInstansi
        "report_rekap_tpp_aktivitas" -> reportRekapTppAktivitas
        else -> role == AppRole.ADMIN
    }

    companion object {
        val AdminFull = AppPermissions(
            role = AppRole.ADMIN,
            allowSearchPegawai = true,
            allowSearchIzin = true,
            allowSearchInstansi = true,
            allowSearchDatabase = true,
            allowSearchProduktivitas = true,
            allowSearchPresensi = true,
            allowSearchLaporan = true,
            allowSearchUnor = true,
            allowSearchReview = true,
            allowAutomationControl = true,
            allowSensitiveActions = true,
            tabPengajuanIzin = true,
            tabLogPresensiTerkini = true,
            tabLogLengkap = true,
            reportPerPegawai = true,
            reportPerPegawaiAktivitas = true,
            reportSkorPerInstansi = true,
            reportAktivitasPerInstansi = true,
            reportRekapTppAktivitas = true,
        )

        fun defaultUser(): AppPermissions = AppPermissions(
            role = AppRole.USER,
            profiles = ProfileAccess(jatim = false, madiun = false, ponorogo = true),
            allowedMenuIds = null,
            allowedMenuIdsByProfile = mapOf(
                "jatim" to setOf("tabBeranda", "tabPresensi", "tabPerizinan", "tabLaporan", "tabLokasiAbsen"),
                "madiun" to setOf("tabBeranda", "tabPresensi", "tabPerizinan", "tabLaporan", "tabLokasiAbsen"),
                "ponorogo" to setOf(
                    "tabBeranda",
                    "tabAbsen",
                    "tabLog",
                    "tabInputAktivitas",
                    "tabAktivitas",
                    "tabReview",
                    "tabIzin",
                ),
            ),
            allowEditServerCreds = false,
            allowSearchPegawai = false,
            allowSearchIzin = false,
            allowSearchInstansi = false,
            allowSearchDatabase = false,
            allowSearchProduktivitas = false,
            allowSearchPresensi = false,
            allowSearchLaporan = false,
            allowSearchUnor = false,
            allowSearchReview = false,
            allowAutomationControl = false,
            allowSensitiveActions = false,
            tabPengajuanIzin = true,
            tabLogPresensiTerkini = true,
            tabLogLengkap = true,
            reportPerPegawai = false,
            reportPerPegawaiAktivitas = false,
            reportSkorPerInstansi = false,
            reportAktivitasPerInstansi = false,
            reportRekapTppAktivitas = false,
        )
    }
}
