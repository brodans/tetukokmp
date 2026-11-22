package id.tetuko.app.ui.screens.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import id.tetuko.app.ui.components.HorizontalScrollRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.tetuko.app.service.AppServices
import id.tetuko.app.ui.components.InfoRow
import id.tetuko.app.ui.components.StatCard
import id.tetuko.core.data.rpc.RpcObjects
import id.tetuko.core.domain.model.PageDef
import id.tetuko.core.domain.model.ServerProfile
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private fun todayIso(): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Jakarta"))
    return "%04d-%02d-%02d".format(now.year, now.monthNumber, now.dayOfMonth)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun BerandaBkdScreen(profile: ServerProfile, page: PageDef, onNavigate: (String) -> Unit = {}) {
    val ctx by AppServices.serverContext.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var loadedOnce by remember(profile.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lokasiCount by remember { mutableStateOf<Int?>(null) }
    var punchesToday by remember { mutableStateOf<List<String>>(emptyList()) }
    var syncing by remember { mutableStateOf(false) }
    var syncInfo by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val hasSession = ctx.apiKey.isNotBlank() || AppServices.serverContext.value.apiKey.isNotBlank()

    fun refresh() {
        if (!hasSession) return
        loading = true; error = null
        scope.launch {
            try {
                val today = todayIso()
                val lokasiEl = AppServices.rpcClient.callResultElement(RpcObjects.GET_LOKASI_ABSEN, ctx = AppServices.serverContext.value, profileId = profile.id)
                val lokasiArr = if (lokasiEl is JsonArray) lokasiEl else if (lokasiEl?.jsonObject?.get("result") is JsonArray) lokasiEl.jsonObject["result"]!!.jsonArray else JsonArray(emptyList())
                lokasiCount = lokasiArr.size

                val historyEl = AppServices.rpcClient.callResultElement(
                    RpcObjects.HISTORY_ABSEN,
                    param = buildJsonObject { put("tgl", today); put("page", 1); put("limit", 20) },
                    ctx = AppServices.serverContext.value,
                    profileId = profile.id,
                )
                val rows = if (historyEl is JsonArray) historyEl else if (historyEl?.jsonObject?.get("result") is JsonArray) historyEl.jsonObject["result"]!!.jsonArray else JsonArray(emptyList())
                punchesToday = rows.mapNotNull { r ->
                    val o = runCatching { r.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val ct = o["checktype"]?.jsonPrimitive?.contentOrNull ?: ""
                    val wt = o["waktu"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (ct.isBlank() && wt.isBlank()) null else "$ct ${wt.takeLast(8)}".trim()
                }
                loadedOnce = true
            } catch (e: Exception) {
                error = e.message ?: "Gagal memuat data beranda."
            } finally { loading = false }
        }
    }

    LaunchedEffect(profile.id, ctx.apiKey) { if (hasSession) refresh() }

    fun syncData() {
        if (AppServices.serverContext.value.apiKey.isBlank()) { syncInfo = "Sesi server belum aktif. Login server terlebih dahulu."; return }
        syncing = true; syncInfo = "Memulai sinkronisasi data referensi..."
        scope.launch {
            try {
                val resp = AppServices.rpcClient.callRaw(
                    RpcObjects.SYNC_DATA,
                    ctx = AppServices.serverContext.value,
                    profileId = profile.id,
                )
                syncInfo = resp.message?.takeIf { it.isNotBlank() } ?: "Sinkronisasi data referensi selesai."
            } catch (e: Exception) {
                syncInfo = "Gagal sinkronisasi: ${e.message}"
            } finally { syncing = false }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Halo, Pengguna", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${profile.nama} • ${profile.label}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val statusText = when {
                    !hasSession -> "Sesi server belum aktif. Login untuk memuat data."
                    loading -> "Memuat data beranda..."
                    loadedOnce -> "Data beranda dimuat."
                    else -> "Menyiapkan data..."
                }
                Text(statusText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = { refresh() }, enabled = !loading && hasSession) {
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Refresh, contentDescription = "Muat ulang data beranda", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp)); Text(if (loading) "Memuat..." else "Muat Ulang")
            }
        }
        if (!hasSession) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Text("Sesi belum terhubung. Masuk ke server untuk melihat status presensi hari ini, lokasi, dan sinkronisasi data.", modifier = Modifier.padding(12.dp), fontSize = 13.sp)
            }
        }
        if (!error.isNullOrBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2))) {
                Text(error!!, color = Color(0xFF991B1B), modifier = Modifier.padding(12.dp), fontSize = 13.sp)
            }
        }
        val jamMasuk = punchesToday.firstOrNull { it.contains("Datang", true) || it.startsWith("1") }?.substringAfterLast(' ')?.take(5)
        val jamPulang = punchesToday.firstOrNull { it.contains("Pulang", true) || it.startsWith("2") }?.substringAfterLast(' ')?.take(5)
        val statusHari = when {
            !hasSession -> "Belum Login"
            loading && !loadedOnce -> "Memuat..."
            jamMasuk != null -> "Hadir"
            else -> "Belum Absen"
        }
        val statusSub = when {
            !hasSession -> "Sesi belum aktif"
            loading && !loadedOnce -> "Mengambil data hari ini"
            jamMasuk != null -> "Masuk $jamMasuk"
            else -> "Belum ada catatan hari ini"
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = if (maxWidth < 1024.dp) 2 else 4,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard("Status Hari Ini", statusHari, statusSub, Modifier.weight(1f))
                StatCard("Jam Masuk", jamMasuk ?: if (loading && !loadedOnce && hasSession) "..." else "--:--", "Absen Datang hari ini", Modifier.weight(1f))
                StatCard("Jam Pulang", jamPulang ?: if (loading && !loadedOnce && hasSession) "..." else "--:--", "Absen Pulang hari ini", Modifier.weight(1f))
                StatCard(
                    "Lokasi Absen",
                    when {
                        !hasSession -> "-"
                        lokasiCount == null && loading -> "..."
                        (lokasiCount ?: 0) > 0 -> "Terdaftar"
                        loadedOnce -> "Kosong"
                        else -> "-"
                    },
                    "${lokasiCount ?: 0} titik terdaftar",
                    Modifier.weight(1f),
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = if (maxWidth < 1024.dp) 1 else 2,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Identitas & Sesi", fontWeight = FontWeight.Bold)
                    InfoRow("Server", profile.label); InfoRow("Profil", profile.nama); InfoRow("Mode", profile.mode.name)
                    InfoRow("API Key", if (AppServices.serverContext.value.apiKey.isBlank()) "Belum terhubung" else "Terhubung")
                    InfoRow("Rekaman hari ini", "${punchesToday.size} baris")
                }
            }
            Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aksi Cepat", fontWeight = FontWeight.Bold)
                    HorizontalScrollRow(modifier = Modifier.fillMaxWidth(), itemSpacing = 8) {
                        listOf(
                            "tabPresensi" to "Presensi",
                            "tabPerizinan" to "Perizinan",
                            "tabLaporan" to "Laporan",
                            "tabLokasiAbsen" to "Lokasi",
                            "tabWeb" to "WEB",
                            "tabPresetOtomatisasi" to "Preset",
                        ).forEach { (id, label) ->
                            FilledTonalButton(onClick = { onNavigate(id) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(label, fontSize = 12.sp)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { syncData() }, enabled = !syncing && hasSession) {
                            if (syncing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Filled.Refresh, contentDescription = "Sinkronisasi data referensi", modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (syncing) "Sinkron..." else "Sync Data", fontSize = 12.sp)
                        }
                    }
                    if (syncInfo != null) {
                        val syncErr = syncInfo!!.startsWith("Gagal") || syncInfo!!.contains("belum aktif", ignoreCase = true)
                        Text(syncInfo!!, fontSize = 11.sp, color = if (syncErr) Color(0xFF991B1B) else MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (!hasSession) {
                        Text("Sync data tersedia setelah login server.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Data dimuat via RPC saat sesi aktif.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            }
        }
    }
}
