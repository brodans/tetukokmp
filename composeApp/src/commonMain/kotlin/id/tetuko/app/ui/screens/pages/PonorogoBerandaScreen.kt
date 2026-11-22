package id.tetuko.app.ui.screens.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import id.tetuko.app.ui.components.VerticalScrollColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.tetuko.app.service.AppServices
import id.tetuko.app.ui.components.InfoRow
import id.tetuko.app.ui.components.PageHeaderCard
import id.tetuko.app.ui.components.StatCard
import id.tetuko.core.domain.model.PageDef
import id.tetuko.core.domain.model.ServerProfile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Beranda mode Ponorogo.
 *
 * Uses the real portal bridge (`/pegawai/getJamKerjaPegawai`,
 * `/Tupoksi/cek_absen`) instead of the BKD RPC objects, matching
 * `LoginServer.tsx` and `Presensi.tsx`.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun PonorogoBerandaScreen(profile: ServerProfile, page: PageDef, onNavigate: (String) -> Unit = {}) {
    val ctx by AppServices.serverContext.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var jamKerja by remember { mutableStateOf<JsonObject?>(null) }
    var statusAbsen by remember { mutableStateOf<JsonObject?>(null) }
    val scope = rememberCoroutineScope()

    val ponorogo = AppServices.ponorogoProfile
    val pegawaiId = ponorogo?.stringOf("id", "id_pegawai", "pegawai_id").orEmpty()
    val nama = ponorogo?.stringOf("nama", "nama_pegawai").orEmpty()
    val nip = ponorogo?.stringOf("nip").orEmpty()
    val jabatan = ponorogo?.stringOf("nama_jabatan", "jabatan").orEmpty()
    val instansi = ponorogo?.stringOf("nama_instansi", "instansi").orEmpty()

    fun refresh() {
        if (AppServices.serverContext.value.apiKey.isBlank()) {
            info = "Sesi server belum aktif. Silakan login server."
            return
        }
        if (pegawaiId.isBlank()) {
            info = "ID pegawai Ponorogo tidak tersedia. Keluar lalu login ulang agar profil dimuat."
            return
        }
        loading = true
        info = null
        scope.launch {
            try {
                val jam = AppServices.ponorogoClient.send(
                    "/pegawai/getJamKerjaPegawai",
                    buildJsonObject { put("id_pegawai", pegawaiId) },
                )
                jamKerja = jam.unwrapObject()
                val absen = AppServices.ponorogoClient.send(
                    "/Tupoksi/cek_absen",
                    buildJsonObject { put("id_pegawai", pegawaiId) },
                )
                statusAbsen = absen.unwrapObject()
                info = "Jam kerja dan status absen berhasil dimuat dari server Ponorogo."
            } catch (e: Exception) {
                info = "Gagal memuat data Ponorogo: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(profile.id, ctx.apiKey) { if (ctx.apiKey.isNotBlank()) refresh() }

    VerticalScrollColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        itemSpacing = 14,
    ) {
        PageHeaderCard(profile, page)

        Card(shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Beranda ${profile.label}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(nama.takeIf { it.isNotBlank() }, profile.nama.takeIf { it.isNotBlank() }).distinct().joinToString(" • ").ifBlank { "Profil Ponorogo" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
                FilledTonalButton(onClick = { refresh() }, enabled = !loading) {
                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Refresh, contentDescription = "Muat ulang data Ponorogo", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (loading) "Memuat…" else "Muat Ulang")
                }
            }
        }

        info?.let {
            val isError = it.startsWith("Gagal") || it.contains("belum aktif", true) || it.contains("tidak tersedia", true)
            Card(colors = CardDefaults.cardColors(containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Text(it, Modifier.padding(12.dp), fontSize = 12.sp)
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = if (maxWidth < 1024.dp) 2 else 3,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard("Mode", "Ponorogo", "Absensi & produktivitas", Modifier.weight(1f))
                StatCard(
                    "Jam Kerja",
                    jamKerja?.let { j ->
                        val masuk = j.stringOf("jam_masuk", "jamMulai", "jam_mulai")
                        val pulang = j.stringOf("jam_pulang", "jamSelesai", "jam_selesai")
                        when {
                            masuk.isNotBlank() || pulang.isNotBlank() -> "${masuk.ifBlank { "—" }}–${pulang.ifBlank { "—" }}"
                            else -> j.stringOf("jam_kerja", "nama_shift").ifBlank { "Ada" }
                        }
                    } ?: "—",
                    jamKerja?.stringOf("nama_hari", "hari", "tanggal").orEmpty().ifBlank { "Belum dimuat" },
                    Modifier.weight(1f),
                )
                StatCard(
                    "Status Absen",
                    statusAbsen?.let { s ->
                        s.stringOf("status", "keterangan", "status_absen").ifBlank {
                            val masuk = s.stringOf("absen_masuk", "jam_masuk")
                            val pulang = s.stringOf("absen_pulang", "jam_pulang")
                            when {
                                masuk.isNotBlank() && pulang.isNotBlank() -> "Masuk & Pulang"
                                masuk.isNotBlank() -> "Sudah Masuk"
                                pulang.isNotBlank() -> "Sudah Pulang"
                                else -> "Tercatat"
                            }
                        }
                    } ?: "—",
                    statusAbsen?.let { s ->
                        val masuk = s.stringOf("absen_masuk", "jam_masuk", "waktu_masuk")
                        val pulang = s.stringOf("absen_pulang", "jam_pulang", "waktu_pulang")
                        listOfNotNull(
                            masuk.takeIf { it.isNotBlank() }?.let { "Masuk $it" },
                            pulang.takeIf { it.isNotBlank() }?.let { "Pulang $it" },
                        ).joinToString(" • ").ifBlank { s.stringOf("jam", "waktu", "tanggal").ifBlank { "Belum dimuat" } }
                    } ?: "Belum dimuat",
                    Modifier.weight(1f),
                )
            }
        }

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Informasi Akun & Sesi", fontWeight = FontWeight.Bold)
                InfoRow("Profil", profile.label)
                InfoRow("Nama", nama.ifBlank { "—" })
                InfoRow("NIP", nip.ifBlank { "—" })
                InfoRow("Jabatan", jabatan.ifBlank { "—" })
                InfoRow("Instansi", instansi.ifBlank { "—" })
                InfoRow("ID Pegawai", pegawaiId.ifBlank { "—" })
                InfoRow("IMEI/Perangkat", ctx.imei.ifBlank { "—" })
                InfoRow("Sesi", if (ctx.apiKey.isBlank()) "Belum terhubung" else "Terhubung")
            }
        }

        if (jamKerja != null || statusAbsen != null) {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Detail dari Server", fontWeight = FontWeight.Bold)
                    jamKerja?.let { DetailList("Jam Kerja", it) }
                    statusAbsen?.let { DetailList("Status Absen Hari Ini", it) }
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Aksi Cepat", fontWeight = FontWeight.Bold)
                Text("Navigasi ke modul utama Ponorogo.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val actions = listOf(
                    "tabAbsen" to "Submit Presensi",
                    "tabLog" to "History Presensi",
                    "tabInputAktivitas" to "Produktivitas",
                    "tabAktivitas" to "History Produktivitas",
                    "tabReview" to "Review Produktivitas",
                    "tabIzin" to "Izin/Cuti",
                    "tabLogPresensiInstansi" to "Log Instansi",
                    "tabReport" to "Laporan",
                    "tabAutomasi" to "Preset Automasi",
                    "tabDatabase" to "Database Pegawai",
                    "tabUnor" to "UNOR",
                )
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        maxItemsInEachRow = if (maxWidth < 1024.dp) 2 else 3,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        actions.forEach { (id, label) ->
                            FilledTonalButton(
                                onClick = { onNavigate(id) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    label,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailList(title: String, obj: JsonObject) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    obj.entries.take(10).forEach { (key, value) ->
        val text = when (value) {
            is JsonPrimitive -> value.contentOrNull.orEmpty()
            else -> value.toString()
        }
        if (text.isNotBlank() && text != "null") {
            Row(Modifier.fillMaxWidth()) {
                Text(humanize(key), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(200.dp))
                Text(text, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun humanize(key: String): String =
    key.replace('_', ' ').replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replaceFirstChar { it.uppercase() }

private fun JsonElement?.unwrapObject(): JsonObject? = when (this) {
    is JsonObject -> (this["data"] as? JsonObject) ?: this
    else -> null
}

private fun JsonObject.stringOf(vararg keys: String): String =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    }.orEmpty()
