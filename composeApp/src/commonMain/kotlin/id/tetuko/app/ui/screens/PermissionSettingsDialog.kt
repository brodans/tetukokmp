package id.tetuko.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import id.tetuko.app.ui.components.ModalScrollColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.tetuko.core.domain.model.AppNavigation
import id.tetuko.core.domain.model.AppPermissions
import id.tetuko.core.domain.model.AppRole
import id.tetuko.core.domain.model.DefaultProfiles
import id.tetuko.core.domain.model.ProfileAccess
import id.tetuko.core.domain.model.ServerProfile

/**
 * Local permission/access assignment dialog (feasible desktop adapter).
 *
 * Allows configuring role/profile access, per-profile menu allow-lists, and
 * granular feature flags for the current local session/config. Changes are
 * applied to [current] via [onApply] and persisted by the desktop session store
 * through normal state snapshots.
 *
 * This is not full Tauri parity (no Firestore multi-user CRUD/sync), but it
 * provides local permission management/assignment and enforcement inputs.
 */
@Composable
fun PermissionSettingsDialog(
    current: AppPermissions,
    onApply: (AppPermissions) -> Unit,
    onDismiss: () -> Unit,
) {
    var role by remember(current) { mutableStateOf(current.role) }
    var allowEditServerCreds by remember(current) { mutableStateOf(current.allowEditServerCreds) }
    var allowSearchPegawai by remember(current) { mutableStateOf(current.allowSearchPegawai) }
    var allowSearchIzin by remember(current) { mutableStateOf(current.allowSearchIzin) }
    var allowSearchInstansi by remember(current) { mutableStateOf(current.allowSearchInstansi) }
    var allowSearchDatabase by remember(current) { mutableStateOf(current.allowSearchDatabase) }
    var allowSearchProduktivitas by remember(current) { mutableStateOf(current.allowSearchProduktivitas) }
    var allowSearchPresensi by remember(current) { mutableStateOf(current.allowSearchPresensi) }
    var allowSearchLaporan by remember(current) { mutableStateOf(current.allowSearchLaporan) }
    var allowSearchUnor by remember(current) { mutableStateOf(current.allowSearchUnor) }
    var allowSearchReview by remember(current) { mutableStateOf(current.allowSearchReview) }
    var allowAutomationControl by remember(current) { mutableStateOf(current.allowAutomationControl) }
    var allowSensitiveActions by remember(current) { mutableStateOf(current.allowSensitiveActions) }
    var tabPengajuanIzin by remember(current) { mutableStateOf(current.tabPengajuanIzin) }
    var tabLogPresensiTerkini by remember(current) { mutableStateOf(current.tabLogPresensiTerkini) }
    var tabLogLengkap by remember(current) { mutableStateOf(current.tabLogLengkap) }
    var reportPerPegawai by remember(current) { mutableStateOf(current.reportPerPegawai) }
    var reportPerPegawaiAktivitas by remember(current) { mutableStateOf(current.reportPerPegawaiAktivitas) }
    var reportSkorPerInstansi by remember(current) { mutableStateOf(current.reportSkorPerInstansi) }
    var reportAktivitasPerInstansi by remember(current) { mutableStateOf(current.reportAktivitasPerInstansi) }
    var reportRekapTppAktivitas by remember(current) { mutableStateOf(current.reportRekapTppAktivitas) }

    var profileAccess by remember(current) { mutableStateOf(current.profiles) }

    // Per-profile menu allow-lists: null in map value means "all menus" for that profile.
    // We represent UI as: all=true => no restriction stored for profile (fallback/global/null).
    data class MenuConfig(val allAllowed: Boolean, val allowed: Set<String>)
    fun initialMenuConfig(profile: ServerProfile): MenuConfig {
        val pages = AppNavigation.pagesFor(profile)
        val effective = current.effectiveMenuAllowList(profile.id)
        return if (effective == null) {
            MenuConfig(true, pages.map { it.id }.toSet())
        } else {
            val set = effective.toMutableSet().apply { add("tabBeranda") }
            MenuConfig(false, set)
        }
    }
    var menuConfigs by remember(current) {
        mutableStateOf(DefaultProfiles.all.associate { it.id to initialMenuConfig(it) })
    }

    fun buildPermissions(): AppPermissions {
        if (role == AppRole.ADMIN) {
            return AppPermissions.AdminFull.copy(
                // Keep admin full by default; allowEditServerCreds toggle still meaningful
                allowEditServerCreds = allowEditServerCreds,
            )
        }
        val perProfile = menuConfigs.mapValues { (_, cfg) ->
            if (cfg.allAllowed) emptySet<String>() // marker not used; we store absence via null handling below
            else cfg.allowed.ifEmpty { setOf("tabBeranda") }
        }
        // If all profiles are "all allowed", we can keep null; else store explicit sets for restricted ones
        val anyRestricted = menuConfigs.any { (_, cfg) -> !cfg.allAllowed }
        val allowedByProfile: Map<String, Set<String>>? = if (!anyRestricted) null else {
            perProfile.mapValues { (pid, set) ->
                val cfg = menuConfigs[pid]!!
                if (cfg.allAllowed) {
                    // "all allowed" for this profile -> store all page IDs to be explicit and robust
                    AppNavigation.pagesFor(DefaultProfiles.all.first { it.id == pid }).map { it.id }.toSet()
                } else set
            }
        }
        return AppPermissions(
            role = AppRole.USER,
            profiles = profileAccess,
            allowedMenuIds = null,
            allowedMenuIdsByProfile = allowedByProfile,
            allowEditServerCreds = allowEditServerCreds,
            allowSearchPegawai = allowSearchPegawai,
            allowSearchIzin = allowSearchIzin,
            allowSearchInstansi = allowSearchInstansi,
            allowSearchDatabase = allowSearchDatabase,
            allowSearchProduktivitas = allowSearchProduktivitas,
            allowSearchPresensi = allowSearchPresensi,
            allowSearchLaporan = allowSearchLaporan,
            allowSearchUnor = allowSearchUnor,
            allowSearchReview = allowSearchReview,
            allowAutomationControl = allowAutomationControl,
            allowSensitiveActions = allowSensitiveActions,
            tabPengajuanIzin = tabPengajuanIzin,
            tabLogPresensiTerkini = tabLogPresensiTerkini,
            tabLogLengkap = tabLogLengkap,
            reportPerPegawai = reportPerPegawai,
            reportPerPegawaiAktivitas = reportPerPegawaiAktivitas,
            reportSkorPerInstansi = reportSkorPerInstansi,
            reportAktivitasPerInstansi = reportAktivitasPerInstansi,
            reportRekapTppAktivitas = reportRekapTppAktivitas,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Pengaturan izin") },
        title = { Text("Pengaturan Akses & Izin (Lokal)") },
        text = {
            ModalScrollColumn(maxHeightDp = 560, itemSpacing = 12) {
                Text(
                    "Konfigurasi lokal untuk pembatasan dan penugasan izin sesi ini. Sinkronisasi cloud akun/permission memerlukan Firestore terkonfigurasi.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Mode / Peran", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = role == AppRole.ADMIN,
                                onClick = { role = AppRole.ADMIN },
                                label = { Text("Admin (penuh)") },
                            )
                            FilterChip(
                                selected = role == AppRole.USER,
                                onClick = { role = AppRole.USER },
                                label = { Text("User (terbatas)") },
                            )
                        }
                        if (role == AppRole.ADMIN) {
                            Text("Mode Admin memberikan akses penuh. Beberapa opsi detail di bawah berlaku untuk konfigurasi mode User.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (role == AppRole.USER) {
                    Card(shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Akses Profil Server", fontWeight = FontWeight.SemiBold)
                            ProfileToggleRow("Jatim (BKD)", profileAccess.jatim) { profileAccess = profileAccess.copy(jatim = it) }
                            ProfileToggleRow("Madiun (BKD)", profileAccess.madiun) { profileAccess = profileAccess.copy(madiun = it) }
                            ProfileToggleRow("Ponorogo", profileAccess.ponorogo) { profileAccess = profileAccess.copy(ponorogo = it) }
                        }
                    }

                    Card(shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Akses Menu per Profil", fontWeight = FontWeight.SemiBold)
                            DefaultProfiles.all.forEach { profile ->
                                val enabledProfile = profileAccess.allowed(profile.id)
                                val cfg = menuConfigs[profile.id] ?: return@forEach
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(profile.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                        if (!enabledProfile) {
                                            Text("Profil dinonaktifkan", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (enabledProfile) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            FilterChip(
                                                selected = cfg.allAllowed,
                                                onClick = {
                                                    menuConfigs = menuConfigs + (profile.id to cfg.copy(allAllowed = true, allowed = AppNavigation.pagesFor(profile).map { it.id }.toSet()))
                                                },
                                                label = { Text("Semua menu") },
                                            )
                                            FilterChip(
                                                selected = !cfg.allAllowed,
                                                onClick = {
                                                    menuConfigs = menuConfigs + (profile.id to cfg.copy(allAllowed = false))
                                                },
                                                label = { Text("Pilih menu") },
                                            )
                                        }
                                        if (!cfg.allAllowed) {
                                            val pages = AppNavigation.pagesFor(profile)
                                            pages.forEach { page ->
                                                val checked = page.id == "tabBeranda" || page.id in cfg.allowed
                                                val mandatory = page.id == "tabBeranda"
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Checkbox(
                                                        checked = checked,
                                                        onCheckedChange = { on ->
                                                            if (mandatory) return@Checkbox
                                                            val next = cfg.allowed.toMutableSet().apply {
                                                                if (on) add(page.id) else remove(page.id)
                                                                add("tabBeranda")
                                                            }
                                                            menuConfigs = menuConfigs + (profile.id to cfg.copy(allowed = next))
                                                        },
                                                        enabled = !mandatory,
                                                    )
                                                    Column {
                                                        Text(page.label, fontSize = 13.sp)
                                                        if (mandatory) Text("Wajib (Beranda)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }

                Card(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Izin Fitur / Aksi (Granular)", fontWeight = FontWeight.SemiBold)
                        val enabled = role == AppRole.USER
                        FeatureToggle("Izinkan ubah kredensial server (edit creds/IMEI/foto)", allowEditServerCreds, enabled) { allowEditServerCreds = it }
                        FeatureToggle("Pencarian pegawai", allowSearchPegawai, enabled) { allowSearchPegawai = it }
                        FeatureToggle("Pencarian/target izin", allowSearchIzin, enabled) { allowSearchIzin = it }
                        FeatureToggle("Pencarian identitas instansi/log", allowSearchInstansi, enabled) { allowSearchInstansi = it }
                        FeatureToggle("Pencarian database pegawai", allowSearchDatabase, enabled) { allowSearchDatabase = it }
                        FeatureToggle("Pencarian produktivitas", allowSearchProduktivitas, enabled) { allowSearchProduktivitas = it }
                        FeatureToggle("Pencarian presensi", allowSearchPresensi, enabled) { allowSearchPresensi = it }
                        FeatureToggle("Pencarian laporan", allowSearchLaporan, enabled) { allowSearchLaporan = it }
                        FeatureToggle("Pencarian UNOR/struktur", allowSearchUnor, enabled) { allowSearchUnor = it }
                        FeatureToggle("Pencarian/review produktivitas", allowSearchReview, enabled) { allowSearchReview = it }
                        FeatureToggle("Kontrol automasi/preset", allowAutomationControl, enabled) { allowAutomationControl = it }
                        FeatureToggle("Aksi sensitif", allowSensitiveActions, enabled) { allowSensitiveActions = it }
                        Text("Sub-fitur Tauri", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        FeatureToggle("Pengajuan izin/cuti", tabPengajuanIzin, enabled) { tabPengajuanIzin = it }
                        FeatureToggle("Presensi terkini", tabLogPresensiTerkini, enabled) { tabLogPresensiTerkini = it }
                        FeatureToggle("Log presensi lengkap", tabLogLengkap, enabled) { tabLogLengkap = it }
                        FeatureToggle("Laporan per pegawai", reportPerPegawai, enabled) { reportPerPegawai = it }
                        FeatureToggle("Laporan aktivitas per pegawai", reportPerPegawaiAktivitas, enabled) { reportPerPegawaiAktivitas = it }
                        FeatureToggle("Skor produktivitas per instansi", reportSkorPerInstansi, enabled) { reportSkorPerInstansi = it }
                        FeatureToggle("Aktivitas per instansi", reportAktivitasPerInstansi, enabled) { reportAktivitasPerInstansi = it }
                        FeatureToggle("Rekap TPP aktivitas", reportRekapTppAktivitas, enabled) { reportRekapTppAktivitas = it }
                        if (!enabled) {
                            Text("Mode Admin mengaktifkan semua izin fitur.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(buildPermissions()) }) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Simpan & Terapkan")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Batal")
            }
        },
    )
}

@Composable
private fun ProfileToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun FeatureToggle(label: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp, color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = if (enabled) checked else true, onCheckedChange = { if (enabled) onChecked(it) }, enabled = enabled)
    }
}
