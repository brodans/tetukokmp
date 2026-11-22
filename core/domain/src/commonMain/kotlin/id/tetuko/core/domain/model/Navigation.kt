package id.tetuko.core.domain.model

enum class MenuIcon {
    HOME, CAMERA, FILE_CHECK, FILE_TEXT, MAP_PIN, ZAP, GLOBE,
    EDIT, ACTIVITY, BAR_CHART, BUILDING, NETWORK, BOT, DATABASE, LOG_OUT, KEY, CODE
}

data class PageDef(
    val id: String,
    val label: String,
    val slug: String,
    val icon: MenuIcon,
    val group: String? = null,
)

object AppNavigation {
    const val AUTOMATION_AVAILABLE = true

    val bkdPages: List<PageDef> = buildList {
        add(PageDef("tabBeranda", "Beranda", "beranda", MenuIcon.HOME))
        add(PageDef("tabPresensi", "Presensi", "presensi", MenuIcon.CAMERA))
        add(PageDef("tabPerizinan", "Perizinan", "izin", MenuIcon.FILE_CHECK))
        add(PageDef("tabLaporan", "Laporan", "laporan", MenuIcon.FILE_TEXT))
        add(PageDef("tabLokasiAbsen", "Lokasi Absen", "lokasi", MenuIcon.MAP_PIN))
        if (AUTOMATION_AVAILABLE) add(PageDef("tabPresetOtomatisasi", "Preset Auto", "preset", MenuIcon.ZAP))
        add(PageDef("tabWeb", "WEB", "web", MenuIcon.GLOBE))
    }

    val ponorogoPages: List<PageDef> = buildList {
        add(PageDef("tabBeranda", "Beranda", "beranda", MenuIcon.HOME, "utama"))
        add(PageDef("tabAbsen", "Submit Presensi", "presensi", MenuIcon.CAMERA, "utama"))
        add(PageDef("tabLog", "History Presensi", "log", MenuIcon.FILE_TEXT, "utama"))
        add(PageDef("tabInputAktivitas", "Produktivitas Harian", "produktivitas", MenuIcon.EDIT, "utama"))
        add(PageDef("tabAktivitas", "History Produktivitas", "log-produktivitas", MenuIcon.ACTIVITY, "utama"))
        add(PageDef("tabReview", "Review Produktivitas", "review", MenuIcon.BAR_CHART, "utama"))
        add(PageDef("tabIzin", "Izin/Cuti", "izin", MenuIcon.FILE_CHECK, "utama"))
        add(PageDef("tabLogPresensiInstansi", "History Presensi Instansi", "instansi", MenuIcon.BUILDING, "utama"))
        add(PageDef("tabReport", "Laporan", "laporan", MenuIcon.FILE_TEXT, "utama"))
        if (AUTOMATION_AVAILABLE) add(PageDef("tabAutomasi", "Preset Automasi", "otomasi", MenuIcon.BOT, "referensi"))
        add(PageDef("tabDatabase", "Database Pegawai", "database", MenuIcon.DATABASE, "referensi"))
        add(PageDef("tabUnor", "Struktur Organisasi", "unor", MenuIcon.NETWORK, "referensi"))
    }

    val ponorogoGroupOrder = listOf("utama" to "Menu Utama", "referensi" to "Referensi")

    fun pagesFor(profile: ServerProfile): List<PageDef> =
        if (profile.mode == AttendanceMode.PONOROGO_ABSENSI) ponorogoPages else bkdPages

    fun pageById(profile: ServerProfile, pageId: String): PageDef? =
        pagesFor(profile).firstOrNull { it.id == pageId }

    fun defaultPageId(profile: ServerProfile): String = "tabBeranda"

    /** First page accessible under [permissions], falling back to Beranda/default if none match. */
    fun firstAccessiblePageId(profile: ServerProfile, permissions: AppPermissions): String {
        val pages = pagesFor(profile)
        if (pages.isEmpty()) return defaultPageId(profile)
        val allowed = pages.firstOrNull { permissions.canAccessMenu(profile.id, it.id) }
        return allowed?.id ?: pages.firstOrNull { it.id == "tabBeranda" }?.id ?: pages.first().id
    }

    /**
     * Resolve a requested page to an accessible page for the given profile/permissions.
     * Useful for deep-link restore and post-login navigation with safe fallback.
     */
    fun resolveAccessiblePageId(profile: ServerProfile, requestedPageId: String?, permissions: AppPermissions): String {
        val pages = pagesFor(profile)
        if (pages.isEmpty()) return defaultPageId(profile)
        val requested = requestedPageId?.takeIf { id -> pages.any { it.id == id } }
        if (requested != null && permissions.canAccessMenu(profile.id, requested)) return requested
        return firstAccessiblePageId(profile, permissions)
    }

    fun slugToPageId(profile: ServerProfile, slug: String): String {
        val pages = pagesFor(profile)
        if (profile.mode == AttendanceMode.PONOROGO_ABSENSI && slug == "login-info") return "tabBeranda"
        return pages.firstOrNull { it.slug == slug }?.id ?: defaultPageId(profile)
    }

    fun pageToPath(profile: ServerProfile, pageId: String): String {
        val pages = pagesFor(profile)
        val slug = pages.firstOrNull { it.id == pageId }?.slug ?: pages.firstOrNull()?.slug ?: "beranda"
        return "/${profile.id}/$slug"
    }

    /**
     * Mode-aware quick-action mapping for tray/mini-window shortcuts.
     * Returns null when the action has no sensible target for the profile.
     */
    fun quickActionPageId(profile: ServerProfile, action: String): String? {
        val isPonorogo = profile.mode == AttendanceMode.PONOROGO_ABSENSI
        return when (action.lowercase()) {
            "beranda", "home" -> "tabBeranda"
            "presensi", "absen", "submit_presensi" -> if (isPonorogo) "tabAbsen" else "tabPresensi"
            "izin", "perizinan", "cuti" -> if (isPonorogo) "tabIzin" else "tabPerizinan"
            "laporan", "report" -> if (isPonorogo) "tabReport" else "tabLaporan"
            "lokasi", "lokasi_absen" -> if (isPonorogo) null else "tabLokasiAbsen"
            "log", "history", "riwayat" -> if (isPonorogo) "tabLog" else null
            "produktivitas", "aktivitas_input" -> if (isPonorogo) "tabInputAktivitas" else null
            "log_produktivitas", "history_produktivitas" -> if (isPonorogo) "tabAktivitas" else null
            "instansi", "log_instansi" -> if (isPonorogo) "tabLogPresensiInstansi" else null
            "review" -> if (isPonorogo) "tabReview" else null
            "database" -> if (isPonorogo) "tabDatabase" else null
            "unor" -> if (isPonorogo) "tabUnor" else null
            "preset", "automasi", "otomasi" -> if (isPonorogo) "tabAutomasi" else "tabPresetOtomatisasi"
            "web" -> if (isPonorogo) null else "tabWeb"
            else -> null
        }?.takeIf { pageById(profile, it) != null }
    }
}

sealed interface AppRoute {
    data object Masuk : AppRoute
    data object Login : AppRoute
    data class App(val profileId: String, val pageId: String) : AppRoute
}
