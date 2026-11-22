package id.tetuko.app.ui.screens

import androidx.compose.runtime.Composable
import id.tetuko.app.ui.screens.pages.*
import id.tetuko.core.domain.model.AttendanceMode
import id.tetuko.core.domain.model.PageDef
import id.tetuko.core.domain.model.ServerProfile

/**
 * Central router for in-app pages. Routes to modular page implementations
 * under ui.screens.pages.* to keep feature screens isolated and maintainable.
 */
@Composable
fun PageScreen(profile: ServerProfile, page: PageDef, onNavigate: (String) -> Unit = {}) {
    when {
        profile.mode == AttendanceMode.BKD_RPC && page.id == "tabBeranda" -> BerandaBkdScreen(profile, page, onNavigate)
        profile.mode == AttendanceMode.PONOROGO_ABSENSI && page.id == "tabBeranda" -> PonorogoBerandaScreen(profile, page, onNavigate)

        // BKD
        page.id == "tabPresensi" -> PresensiScreen(profile, page)
        page.id == "tabPerizinan" -> PerizinanScreen(profile, page)
        page.id == "tabLaporan" -> LaporanScreen(profile, page)
        page.id == "tabLokasiAbsen" -> LokasiAbsenScreen(profile, page)
        page.id == "tabWeb" -> WebPresensiScreen(profile, page)
        page.id == "tabPresetOtomatisasi" -> PresetScreen(profile, page)

        // Ponorogo (mode-aware)
        profile.mode == AttendanceMode.PONOROGO_ABSENSI && page.id == "tabAbsen" -> PonorogoPresensiScreen(profile, page)
        profile.mode == AttendanceMode.PONOROGO_ABSENSI && page.id == "tabIzin" -> PonorogoIzinScreen(profile, page)
        profile.mode == AttendanceMode.PONOROGO_ABSENSI && page.id == "tabLogPresensiInstansi" -> PonorogoInstansiLogScreen(profile, page)
        profile.mode == AttendanceMode.PONOROGO_ABSENSI && page.id == "tabReport" -> PonorogoLaporanScreen(profile, page)
        profile.mode == AttendanceMode.PONOROGO_ABSENSI && page.id == "tabLog" -> PonorogoHistoryScreen(profile, page)
        profile.mode == AttendanceMode.PONOROGO_ABSENSI && page.id == "tabInputAktivitas" -> PonorogoProduktivitasScreen(profile, page)
        profile.mode == AttendanceMode.PONOROGO_ABSENSI && page.id == "tabAktivitas" -> PonorogoHistoryProduktivitasScreen(profile, page)
        profile.mode == AttendanceMode.PONOROGO_ABSENSI && page.id == "tabReview" -> PonorogoReviewScreen(profile, page)

        page.id == "tabAutomasi" -> PresetScreen(profile, page)
        page.id == "tabDatabase" -> DatabaseScreen(profile, page)
        page.id == "tabUnor" -> UnorScreen(profile, page)
        else -> GenericPageScreen(profile, page)
    }
}
