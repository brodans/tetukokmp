package id.tetuko.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.tetuko.app.ui.toImageVector
import id.tetuko.core.domain.model.PageDef
import id.tetuko.core.domain.model.ServerProfile

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(value, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    }
}

@Composable
fun FeatureCard(title: String, body: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatCard(label: String, value: String, hint: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PageHeaderCard(profile: ServerProfile, page: PageDef) {
    val icon = page.icon.toImageVector()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(page.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${profile.label} • ${profile.nama}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
}

fun pageFeatureHint(pageId: String): String = when (pageId) {
    "tabBeranda" -> "Ringkasan, info akun/server, akses cepat"
    "tabPresensi", "tabAbsen" -> "Submit presensi, foto/lokasi/captcha, status"
    "tabPerizinan", "tabIzin" -> "Pengajuan & riwayat izin/cuti/perizinan"
    "tabLaporan", "tabReport" -> "Filter, preview, ekspor PDF/Excel"
    "tabLokasiAbsen" -> "Peta & manajemen lokasi absen"
    "tabWeb" -> "Akses web presensi terintegrasi"
    "tabPresetOtomatisasi", "tabAutomasi" -> "Preset, scheduler, eksekusi otomatis"
    "tabLog", "tabLogPresensiInstansi" -> "Riwayat/history presensi"
    "tabInputAktivitas" -> "Input produktivitas harian"
    "tabAktivitas" -> "History produktivitas"
    "tabReview" -> "Review produktivitas"
    "tabDatabase" -> "Database pegawai & pencarian"
    "tabUnor" -> "Struktur organisasi / UNOR"
    else -> "Fitur sesuai modul halaman"
}
