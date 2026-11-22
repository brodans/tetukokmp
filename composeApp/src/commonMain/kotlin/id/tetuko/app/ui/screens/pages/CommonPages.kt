package id.tetuko.app.ui.screens.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import id.tetuko.app.ui.LocalAppPermissions
import id.tetuko.app.ui.LocalAppState
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import okio.ByteString
import id.tetuko.app.service.AppServices
import id.tetuko.app.service.PickedFile
import id.tetuko.app.ui.components.FeatureCard
import id.tetuko.app.ui.components.FullscreenImageViewer
import id.tetuko.app.ui.components.DateField
import id.tetuko.app.ui.components.InfoRow
import id.tetuko.app.ui.components.PageHeaderCard
import id.tetuko.app.ui.components.HorizontalScrollRow
import id.tetuko.app.ui.components.VerticalScrollColumn
import id.tetuko.app.ui.components.TimeField
import id.tetuko.app.ui.components.pageFeatureHint
import id.tetuko.app.util.ExcelSheet
import id.tetuko.app.util.PdfTable
import id.tetuko.app.util.SpreadsheetXml
import id.tetuko.core.data.rpc.RpcObjects
import id.tetuko.core.domain.model.AppRole
import id.tetuko.core.domain.model.AttendanceMode
import id.tetuko.core.domain.model.PageDef
import id.tetuko.core.domain.model.ServerProfile
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*

/** Centralised sensitive-action guard (Tauri parity: admin or explicit flag). */
@Composable
private fun rememberCanSensitiveAction(): Boolean {
    val perms = LocalAppPermissions.current
    return perms.role == AppRole.ADMIN || perms.allowSensitiveActions || perms.canUseFeature("sensitive_actions")
}

private fun todayIso(): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Jakarta"))
    return "%04d-%02d-%02d".format(now.year, now.monthNumber, now.dayOfMonth)
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
private fun daysAgoIso(days: Int): String {
    val d = Clock.System.now().minus(days, DateTimeUnit.DAY, TimeZone.of("Asia/Jakarta"))
        .toLocalDateTime(TimeZone.of("Asia/Jakarta"))
    return "%04d-%02d-%02d".format(d.year, d.monthNumber, d.dayOfMonth)
}
private fun isIsoDate(s: String): Boolean = Regex("""\d{4}-\d{2}-\d{2}""").matches(s.trim())
private fun floorDiv(a: Int, b: Int): Int {
    val q = a / b
    return if (a % b != 0 && (a xor b) < 0) q - 1 else q
}
private fun isoToEpochDay(s: String): Int? = runCatching {
    val p = s.trim().split("-"); if (p.size != 3) return null
    val year = p[0].toInt(); val month = p[1].toInt(); val day = p[2].toInt()
    if (month !in 1..12 || day !in 1..31) return null
    val y = year - if (month <= 2) 1 else 0
    val era = floorDiv(y, 400)
    val yoe = y - era * 400
    val mp = if (month > 2) month - 3 else month + 9
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    era * 146097 + doe - 719468
}.getOrNull()
private fun epochDayToIso(day: Int): String {
    val z = day + 719468
    val era = floorDiv(z, 146097)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    var y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = mp + if (mp < 10) 3 else -9
    y += if (m <= 2) 1 else 0
    return "%04d-%02d-%02d".format(y, m, d)
}
internal fun expandDateRange(from: String, to: String, maxDays: Int = 62): List<String> {
    val a = isoToEpochDay(from)
    val b = isoToEpochDay(to)
    if (a == null || b == null) return listOf(from, to).distinct()
    val end = maxOf(a, b)
    val start = maxOf(minOf(a, b), end - maxDays + 1)
    return (start..end).map { epochDayToIso(it) }.reversed()
}
private fun JsonElement?.asArray(): JsonArray = when (this) {
    is JsonArray -> this
    is JsonObject -> (this["result"] as? JsonArray) ?: (this["data"] as? JsonArray) ?: JsonArray(emptyList())
    else -> JsonArray(emptyList())
}
private fun JsonObject.str(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull
        ?: this[key]?.toString()?.trim('"')?.takeIf { it != "null" } ?: ""

private fun parseLatLongText(raw: String): Pair<Double, Double>? {
    val parts = raw.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size < 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lng = parts[1].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return lat to lng
}
private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371000.0
    fun rad(d: Double) = d * kotlin.math.PI / 180.0
    val dLat = rad(lat2 - lat1)
    val dLng = rad(lng2 - lng1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(rad(lat1)) * kotlin.math.cos(rad(lat2)) *
        kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
    return 2 * r * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}
private fun csvCell(value: String): String {
    // Neutralise spreadsheet formula injection before quoting.
    val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
    return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + safe.replace("\"", "\"\"") + "\""
    } else safe
}

/** Columns shared by report preview, CSV and Excel exports. */
private val REPORT_COLUMNS = listOf(
    "checktype" to "Tipe",
    "waktu" to "Waktu",
    "jarak" to "Jarak",
    "nama" to "Nama",
    "departemen" to "Departemen",
    "approval_text" to "Approval",
)

private fun extractIjinId(result: JsonElement?): String? {
    val obj = result as? JsonObject ?: return null
    fun pick(o: JsonObject): String? =
        (o["id"] ?: o["Id"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    pick(obj)?.let { return it }
    (obj["result"] as? JsonObject)?.let { inner -> pick(inner)?.let { return it } }
    return null
}

@Composable
fun PresensiScreen(profile: ServerProfile, page: PageDef) {
    val ctx by AppServices.serverContext.collectAsState()
    var info by remember { mutableStateOf("Muat data presensi untuk melihat work code dan lokasi.") }
    var loading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf<String?>(null) }
    var workCodes by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var locations by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var todayPunches by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var selectedWorkCode by remember { mutableStateOf<String?>(null) }
    var latText by remember { mutableStateOf("") }
    var lngText by remember { mutableStateOf("") }
    var isWfh by remember { mutableStateOf(false) }
    var kirimIzin by remember { mutableStateOf(false) }
    var typeIjin by remember { mutableStateOf("1") }
    var keteranganIzin by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val canSensitive = rememberCanSensitiveAction()

    val isFridayWib = remember {
        Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Jakarta")).dayOfWeek == DayOfWeek.FRIDAY
    }
    LaunchedEffect(isFridayWib) {
        if (!isFridayWib && isWfh) isWfh = false
    }

    val tipeIjinOptions = remember {
        listOf(
            "1" to "Izin penuh",
            "2" to "Tidak absen masuk",
            "3" to "Tidak absen pulang",
        )
    }

    fun hasSession() = ctx.apiKey.isNotBlank() || AppServices.serverContext.value.apiKey.isNotBlank()

    fun readSavedActivePoint(): id.tetuko.app.service.SavedPoint? {
        val raw = AppServices.pointsReader?.invoke(profile.id) ?: return null
        return runCatching { id.tetuko.app.service.SavedPointsCodec.decode(raw) }
            .getOrDefault(emptyList())
            .firstOrNull { it.active }
    }

    fun applySavedActivePoint(force: Boolean = false): Boolean {
        if (!force && (latText.isNotBlank() || lngText.isNotBlank())) return false
        val active = readSavedActivePoint() ?: return false
        latText = active.latitude.toString()
        lngText = active.longitude.toString()
        AppServices.updateServerContext(lastLatLong = "${active.latitude},${active.longitude}")
        info = "Koordinat aktif diterapkan: ${active.name} (${active.latitude}, ${active.longitude})."
        return true
    }

    fun load() {
        if (!hasSession()) { info = "Sesi server belum aktif. Silakan login server."; return }
        loading = true
        val hadCoords = latText.isNotBlank() && lngText.isNotBlank()
        scope.launch {
            try {
                coroutineScope {
                    val wcDef = async { AppServices.rpcClient.callResultElement(RpcObjects.GET_WORK_CODE, ctx = AppServices.serverContext.value, profileId = profile.id) }
                    val lokDef = async { AppServices.rpcClient.callResultElement(RpcObjects.GET_LOKASI_ABSEN, ctx = AppServices.serverContext.value, profileId = profile.id) }
                    val histDef = async {
                        runCatching {
                            AppServices.rpcClient.callResultElement(
                                RpcObjects.HISTORY_ABSEN,
                                param = buildJsonObject { put("tgl", todayIso()); put("page", 1); put("limit", 20) },
                                ctx = AppServices.serverContext.value,
                                profileId = profile.id,
                            )
                        }.getOrNull()
                    }
                    val wc = wcDef.await()
                    val lok = lokDef.await()
                    val hist = histDef.await()
                    workCodes = wc.asArray().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                    locations = lok.asArray().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                    todayPunches = hist?.asArray()?.mapNotNull { runCatching { it.jsonObject }.getOrNull() } ?: emptyList()
                }
                if (selectedWorkCode == null) {
                    val best = pilihWorkCodeUntukHari(workCodes, todayIso()) ?: workCodes.firstOrNull()
                    selectedWorkCode = best?.strAny("id", "Id")?.ifBlank { null }
                }
                var appliedActive = false
                if (!hadCoords) {
                    appliedActive = applySavedActivePoint(force = false)
                    if (!appliedActive) {
                        val saved = AppServices.serverContext.value.lastLatLong
                        if (latText.isBlank() && lngText.isBlank() && saved.contains(",")) {
                            latText = saved.substringBefore(",").trim(); lngText = saved.substringAfter(",").trim()
                        }
                        if (latText.isBlank() && lngText.isBlank()) appliedActive = applySavedActivePoint(force = false)
                    }
                }
                info = buildString {
                    append("Data siap: ${workCodes.size} work code, ${locations.size} lokasi.")
                    val active = readSavedActivePoint()
                    when {
                        appliedActive && active != null -> append(" Koordinat aktif diterapkan: ${active.name}.")
                        !hadCoords && latText.isNotBlank() && lngText.isNotBlank() -> append(" Koordinat terakhir/sesi digunakan.")
                        active != null -> append(" Titik aktif tersimpan tersedia: ${active.name}.")
                    }
                    if (todayPunches.isNotEmpty()) append(" Status presensi hari ini dimuat (${todayPunches.size} rekaman).")
                }
            } catch (e: Exception) { info = "Gagal memuat: ${e.message}" } finally { loading = false }
        }
    }

    fun parseCoordInputs(): Pair<Double, Double>? {
        val la = latText.trim().replace(',', '.').toDoubleOrNull() ?: return null
        val ln = lngText.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (la !in -90.0..90.0 || ln !in -180.0..180.0) return null
        return la to ln
    }

    fun doAction(checkType: Int, label: String) {
        if (!canSensitive) { info = "Akun ini tidak memiliki izin untuk mengirim presensi (aksi sensitif)."; return }
        if (!hasSession()) { info = "Sesi server belum aktif."; return }
        val wc = selectedWorkCode ?: workCodes.firstOrNull()?.str("id") ?: ""
        if (wc.isBlank()) { info = "[$label] Pilih/muat work code terlebih dahulu."; return }
        val coords = parseCoordInputs()
        if (latText.isBlank() || lngText.isBlank()) {
            info = "[$label] Koordinat wajib diisi. Pilih titik lokasi, gunakan titik aktif tersimpan, atau isi latitude/longitude manual."
            return
        }
        if (coords == null) {
            info = "[$label] Koordinat tidak valid. Latitude harus -90..90 dan longitude -180..180."
            return
        }
        if (kirimIzin && keteranganIzin.trim().isBlank()) {
            info = "[$label] Keterangan izin wajib diisi jika mengirim izin bersama absensi."
            return
        }
        val (la, ln) = coords
        AppServices.updateServerContext(lastLatLong = "$la,$ln")
        submitting = label
        scope.launch {
            try {
                val wcInt = wc.toIntOrNull()
                val todayIsFriday = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Jakarta")).dayOfWeek == DayOfWeek.FRIDAY
                val wfh = if (isWfh && todayIsFriday) 1 else 0
                // cekabsen wajib: checktype + work_code + iswfh.
                val cek = AppServices.rpcClient.callRaw(RpcObjects.CEK_ABSEN, buildJsonObject {
                    put("checktype", checkType)
                    if (wcInt != null) put("work_code", wcInt) else put("work_code", wc)
                    put("iswfh", wfh)
                }, AppServices.serverContext.value, profile.id)
                val cekResult = AppServices.rpcClient.decodeOrNull<id.tetuko.core.data.rpc.CekAbsenResult>(cek.result)
                if (cekResult?.absen == true) {
                    info = "[$label] Tidak dapat absen: ${cekResult.message ?: "sudah absen atau ditolak server."}"
                } else {
                    val ijinFlag = if (kirimIzin) 1 else 0
                    val ijinKeterangan = if (kirimIzin) keteranganIzin.trim() else ""
                    val ijinType = if (kirimIzin) (typeIjin.toIntOrNull() ?: 0) else 0
                    // absen wajib enam key: checktype, work_code, iswfh, ijin, keterangan, type_ijin.
                    val ab = AppServices.rpcClient.callRaw(RpcObjects.ABSEN, buildJsonObject {
                        put("checktype", checkType)
                        if (wcInt != null) put("work_code", wcInt) else put("work_code", wc)
                        put("iswfh", wfh)
                        put("ijin", ijinFlag)
                        put("keterangan", ijinKeterangan)
                        put("type_ijin", ijinType)
                        put("mock", 0)
                    }, AppServices.serverContext.value, profile.id)
                    val warning = cekResult?.message?.takeIf { it.isNotBlank() }
                    val abObj = ab.result as? JsonObject
                    val abFlag = (abObj?.get("absen") as? JsonPrimitive)?.booleanOrNull
                    val abMsg = (abObj?.get("message") as? JsonPrimitive)?.contentOrNull
                    val extra = if (kirimIzin) " (izin disertakan)" else ""
                    val success = ab.error == null && abFlag != false
                    info = when {
                        ab.error != null -> "[$label] Gagal mengirim absensi: ${ab.error}"
                        abFlag == false -> "[$label] Ditolak server: ${abMsg ?: "server menolak absensi."}"
                        else -> "[$label$extra] Berhasil dikirim. ${abMsg ?: ab.message ?: warning ?: "Respons server diterima."}".trim()
                    }
                    if (success) {
                        runCatching {
                            val hist = AppServices.rpcClient.callResultElement(
                                RpcObjects.HISTORY_ABSEN,
                                param = buildJsonObject { put("tgl", todayIso()); put("page", 1); put("limit", 20) },
                                ctx = AppServices.serverContext.value,
                                profileId = profile.id,
                            )
                            todayPunches = hist.asArray().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                        }
                    }
                }
            } catch (e: Exception) {
                info = "[$label] Error: ${e.message}"
            } finally { submitting = null }
        }
    }

    val coordState = remember(latText, lngText) {
        val hasInput = latText.isNotBlank() || lngText.isNotBlank()
        val lat = latText.trim().replace(',', '.').toDoubleOrNull()
        val lng = lngText.trim().replace(',', '.').toDoubleOrNull()
        val valid = lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0
        Triple(hasInput, valid, if (valid) lat!! to lng!! else null)
    }
    val geofenceInfo = remember(coordState, locations) {
        val coords = coordState.third ?: return@remember null
        data class LocInfo(val name: String, val distance: Double, val radius: Double?)
        val candidates = locations.mapNotNull { loc ->
            val nama = loc.str("lokasi").ifBlank { loc.str("data").ifBlank { loc.str("deskripsi").ifBlank { "Lokasi" } } }
            val ll = loc.str("latlong")
            val p = parseLatLongText(ll) ?: return@mapNotNull null
            val radius = loc.str("radius").toDoubleOrNull()?.takeIf { it > 0 }
                ?: loc.str("jarak").toDoubleOrNull()?.takeIf { it > 0 }
            LocInfo(nama, haversineMeters(coords.first, coords.second, p.first, p.second), radius)
        }
        candidates.minByOrNull { it.distance }?.let { nearest ->
            val inside = nearest.radius?.let { nearest.distance <= it }
            Quadruple(nearest.name, nearest.distance, inside, nearest.radius)
        }
    }

    val savedActivePoint = remember(profile.id, ctx.apiKey, latText, lngText, loading, info) { readSavedActivePoint() }

    LaunchedEffect(profile.id) { if (latText.isBlank() && lngText.isBlank()) applySavedActivePoint() }
    LaunchedEffect(profile.id, ctx.apiKey) { if (hasSession()) load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        if (!info.isBlank()) {
            val lower = info.lowercase()
            val isErr = lower.contains("gagal") || lower.contains("ditolak") || lower.contains("error") || lower.contains("wajib") || lower.contains("tidak valid") || lower.contains("tidak dapat")
            val isOk = lower.contains("berhasil") || lower.contains("data siap") || lower.contains("diterapkan") || lower.contains("dipilih")
            val bg = when {
                isErr -> Color(0xFFFEE2E2)
                isOk -> Color(0xFFDCFCE7)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
            val fg = when {
                isErr -> Color(0xFF991B1B)
                isOk -> Color(0xFF166534)
                else -> MaterialTheme.colorScheme.onSurface
            }
            Card(colors = CardDefaults.cardColors(containerColor = bg)) {
                Text(info, modifier = Modifier.padding(12.dp), fontSize = 13.sp, color = fg)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Card(Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Aksi Presensi", fontWeight = FontWeight.Bold)
                Text("Pilih work code lalu kirim presensi (cekabsen → absen) via RPC.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (todayPunches.isNotEmpty()) {
                    val masuk = todayPunches.firstOrNull { isDatangChecktype(it.str("checktype")) }
                    val pulang = todayPunches.firstOrNull { isPulangChecktype(it.str("checktype")) }
                    val siang = todayPunches.firstOrNull { it.str("checktype").trim().let { c -> c == "3" || c.lowercase() == "siang" } }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Status Presensi Hari Ini (${todayIso()})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Datang: ${laporanJamTampil(masuk?.str("waktu")) ?: "Belum tercatat"}", fontSize = 12.sp)
                            if (siang != null) Text("Siang: ${laporanJamTampil(siang.str("waktu")) ?: "Tercatat"}", fontSize = 12.sp)
                            Text("Pulang: ${laporanJamTampil(pulang?.str("waktu")) ?: "Belum tercatat"}", fontSize = 12.sp)
                        }
                    }
                } else if (!loading && hasSession()) {
                    Text("Status presensi hari ini belum dimuat/tercatat. Tekan Muat Data untuk memeriksa.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (workCodes.isNotEmpty()) {
                    Text("Work Code", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    val selectedWc = workCodes.firstOrNull { it.strAny("id", "Id") == selectedWorkCode }
                    selectedWc?.let { wc ->
                        val jamMasukAwal = workCodeJamMasukAwal(wc)
                        val jamObj = (wc["hari"] as? JsonObject)?.get("jam") as? JsonObject
                        val jamKeluar = jamObj?.strAny("jam_keluar", "jam_keluar_akhir")
                        val scheduleHint = listOfNotNull(
                            jamMasukAwal?.let { "Masuk $it" },
                            jamKeluar?.takeIf { it.isNotBlank() }?.let { "Pulang $it" },
                        ).joinToString(" • ")
                        if (scheduleHint.isNotBlank()) {
                            Text("Jadwal terpilih: $scheduleHint (toleransi telat 15 menit)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalScrollRow(modifier = Modifier.fillMaxWidth(), itemSpacing = 8) {
                        workCodes.forEach { wc ->
                            val id = wc.strAny("id", "Id")
                            val nama = wc.strAny("nama", "Nama").ifBlank { id }
                            FilterChip(
                                selected = selectedWorkCode == id,
                                onClick = { selectedWorkCode = id },
                                label = { Text(nama.take(28), maxLines = 1) }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Koordinat Absen (wajib)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Pilih titik dari daftar lokasi, pakai titik aktif tersimpan, atau isi latitude/longitude manual.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (savedActivePoint != null) {
                        OutlinedButton(onClick = { applySavedActivePoint(force = true) }, enabled = submitting == null) {
                            Text("Pakai Titik Aktif", fontSize = 12.sp)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        value = latText, onValueChange = { latText = it },
                        label = { Text("Latitude") }, singleLine = true,
                        placeholder = { Text("-7.123456") },
                        supportingText = { Text(if (latText.isNotBlank()) "Rentang -90..90" else "Wajib diisi") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(170.dp),
                    )
                    OutlinedTextField(
                        value = lngText, onValueChange = { lngText = it },
                        label = { Text("Longitude") }, singleLine = true,
                        placeholder = { Text("111.123456") },
                        supportingText = { Text(if (lngText.isNotBlank()) "Rentang -180..180" else "Wajib diisi") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(190.dp),
                    )
                }
                when {
                    coordState.first && !coordState.second -> {
                        Text("Koordinat tidak valid. Periksa format dan rentang latitude/longitude.", fontSize = 12.sp, color = Color(0xFF991B1B))
                    }
                    coordState.second -> {
                        val coords = coordState.third!!
                        Text(
                            "Koordinat siap: ${coords.first}, ${coords.second}",
                            fontSize = 12.sp,
                            color = Color(0xFF166534),
                        )
                    }
                }
                geofenceInfo?.let { (name, dist, inside, radius) ->
                    val text = buildString {
                        append("Terdekat: $name • ${dist.toInt()} m")
                        if (radius != null) append(" • radius ${radius.toInt()} m")
                        when (inside) {
                            true -> append(" • dalam radius")
                            false -> append(" • di luar radius")
                            null -> append(" • radius lokasi tidak diketahui")
                        }
                    }
                    val color = when (inside) {
                        true -> Color(0xFF166534)
                        false -> Color(0xFF991B1B)
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(text, fontSize = 12.sp, color = color)
                }
                if (geofenceInfo == null && coordState.second && locations.isNotEmpty()) {
                    Text("Tidak ada koordinat lokasi server yang valid untuk dihitung jaraknya.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (locations.isEmpty()) {
                    Text("Muat data untuk melihat informasi jarak/geofence terhadap lokasi terdaftar.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isWfh,
                        onCheckedChange = { if (isFridayWib) isWfh = it },
                        enabled = submitting == null && isFridayWib,
                    )
                    Text(
                        if (isFridayWib) "WFH (khusus Jumat sesuai kebijakan)"
                        else "WFH hanya tersedia pada hari Jumat (WIB)",
                        fontSize = 12.sp,
                        color = if (isFridayWib) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = kirimIzin, onCheckedChange = { kirimIzin = it }, enabled = submitting == null)
                    Text("Kirim izin bersama absensi ini", fontSize = 12.sp)
                }
                if (kirimIzin) {
                    Text("Tipe Izin", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    HorizontalScrollRow(modifier = Modifier.fillMaxWidth(), itemSpacing = 8) {
                        tipeIjinOptions.forEach { (value, labelOpt) ->
                            FilterChip(
                                selected = typeIjin == value,
                                onClick = { typeIjin = value },
                                label = { Text(labelOpt, fontSize = 12.sp) },
                                enabled = submitting == null,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = keteranganIzin,
                        onValueChange = { keteranganIzin = it },
                        label = { Text("Keterangan izin (wajib)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = submitting == null,
                        minLines = 2,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { load() }, enabled = !loading) { Text(if (loading) "Memuat..." else "Muat Data") }
                    Button(onClick = { doAction(1, "Datang") }, enabled = submitting == null && hasSession() && canSensitive) { Text(if (submitting == "Datang") "..." else "Datang") }
                    OutlinedButton(onClick = { doAction(3, "Siang") }, enabled = submitting == null && hasSession() && canSensitive) { Text(if (submitting == "Siang") "..." else "Siang") }
                    OutlinedButton(onClick = { doAction(2, "Pulang") }, enabled = submitting == null && hasSession() && canSensitive) { Text(if (submitting == "Pulang") "..." else "Pulang") }
                }
            } }
            Card(Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lokasi Terdaftar (${locations.size})", fontWeight = FontWeight.Bold)
                if (locations.isEmpty()) Text("Belum ada data lokasi. Muat data untuk menampilkan.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(locations.take(20)) { loc ->
                        val nama = loc.str("lokasi").ifBlank { loc.str("data").ifBlank { loc.str("deskripsi").ifBlank { "Lokasi" } } }
                        val ll = loc.str("latlong")
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (ll.contains(",")) {
                                    val la = ll.substringBefore(",").trim()
                                    val ln = ll.substringAfter(",").trim()
                                    latText = la
                                    lngText = ln
                                    AppServices.updateServerContext(lastLatLong = "$la,$ln")
                                    info = "Koordinat dipilih dari lokasi: $nama"
                                }
                            },
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text("• $nama", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    if (ll.isNotBlank()) ll else "Koordinat tidak tersedia",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } }
        }
    }
}

@Composable
fun PerizinanScreen(profile: ServerProfile, page: PageDef) {
    val ctx by AppServices.serverContext.collectAsState()
    var status by remember { mutableStateOf("Belum dimuat") }
    var loading by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var pageNo by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var filterStatus by remember { mutableStateOf("all") } // all | pending | approved | ditolak
    var filterDateFrom by remember { mutableStateOf(daysAgoIso(30)) }
    var filterDateTo by remember { mutableStateOf(todayIso()) }
    val scope = rememberCoroutineScope()
    val canSensitive = rememberCanSensitiveAction()

    fun load(targetPage: Int = 1, append: Boolean = false) {
        if (ctx.apiKey.isBlank() && AppServices.serverContext.value.apiKey.isBlank()) { status = "Sesi server belum aktif. Login untuk memuat riwayat perizinan."; return }
        loading = true
        if (!append) status = "Memuat riwayat perizinan..."
        scope.launch {
            try {
                val limit = 50
                val el = AppServices.rpcClient.callResultElement(
                    RpcObjects.LIST_IJIN,
                    param = buildJsonObject { put("page", targetPage); put("limit", limit) },
                    ctx = AppServices.serverContext.value,
                    profileId = profile.id,
                )
                val fetched = el.asArray().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                items = if (append) items + fetched else fetched
                pageNo = targetPage
                hasMore = fetched.size >= limit
                status = when {
                    items.isEmpty() -> "Belum ada riwayat perizinan."
                    hasMore -> "Menampilkan ${items.size} data izin. Masih ada halaman berikutnya."
                    else -> "Menampilkan ${items.size} data izin."
                }
            } catch (e: Exception) { status = "Gagal memuat riwayat: ${e.message}" } finally { loading = false }
        }
    }
    fun deleteItem(item: JsonObject) {
        if (!canSensitive) { status = "Akun ini tidak memiliki izin untuk menghapus izin."; return }
        val id = item.str("id").ifBlank { item.str("Id") }
        if (id.isBlank()) { status = "ID izin tidak ditemukan, data ini tidak dapat dihapus."; return }
        loading = true
        scope.launch {
            try {
                val resp = AppServices.rpcClient.callRaw(RpcObjects.DELETE_IJIN, buildJsonObject {
                    id.toIntOrNull()?.let { put("id", it) } ?: put("id", id)
                }, AppServices.serverContext.value, profile.id)
                status = if (resp.error != null) "Gagal menghapus izin: ${resp.error}" else "Izin berhasil dihapus. ${resp.message ?: ""}".trim()
                load(1, false)
            } catch (e: Exception) { status = "Gagal menghapus izin: ${e.message}" } finally { loading = false }
        }
    }

    fun exportIzinCsv(rows: List<JsonObject>) {
        if (rows.isEmpty()) { status = "Tidak ada data untuk diekspor."; return }
        val saved = AppServices.textExporter?.invoke(
            "riwayat-izin-${filterDateFrom}_${filterDateTo}.csv",
            "\uFEFF" + buildIzinCsv(rows),
        ) ?: false
        status = if (saved) "${rows.size} baris riwayat izin diekspor ke CSV." else "Ekspor dibatalkan atau tidak tersedia."
    }

    LaunchedEffect(profile.id, ctx.apiKey) { if (ctx.apiKey.isNotBlank()) load(1, false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        PerizinanAjukanCard(profile)
        Card(shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Riwayat Perizinan", fontWeight = FontWeight.Bold)
                    val statusColor = when {
                        status.startsWith("Gagal") -> Color(0xFF991B1B)
                        loading -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(status, fontSize = 12.sp, color = statusColor)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { load(1, false) }, enabled = !loading) { Text(if (loading) "Memuat..." else "Muat Ulang") }
                    if (hasMore) {
                        OutlinedButton(onClick = { load(pageNo + 1, true) }, enabled = !loading) { Text("Muat Lagi") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Filter status:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(
                    "all" to "Semua",
                    "pending" to "Menunggu",
                    "approved" to "Disetujui",
                    "ditolak" to "Ditolak",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = filterStatus == value,
                        onClick = { filterStatus = value },
                        label = { Text(label, fontSize = 11.sp) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    filterDateFrom,
                    { filterDateFrom = it },
                    label = { Text("Dari", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.width(145.dp),
                )
                OutlinedTextField(
                    filterDateTo,
                    { filterDateTo = it },
                    label = { Text("Sampai", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.width(145.dp),
                )
                Text(
                    "Filter tanggal diterapkan pada data yang sudah dimuat (client-side).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            val filteredItems = remember(items, filterStatus, filterDateFrom, filterDateTo) {
                val from = filterDateFrom.trim().takeIf { isIsoDate(it) }
                val to = filterDateTo.trim().takeIf { isIsoDate(it) }
                items.filter { row ->
                    val statusOk = filterStatus == "all" || izinStatusKey(row) == filterStatus
                    if (!statusOk) return@filter false
                    if (from == null && to == null) return@filter true
                    // Overlap/range check: izin [start,end] intersects filter [from,to]
                    val rowStart = row.str("tgl_ijin").ifBlank { row.str("tgl_ijin_dari").ifBlank { row.str("tanggal") } }.trim()
                    val rowEnd = row.str("tgl_ijin_sampai").trim().ifBlank { rowStart }
                    val rs = if (isIsoDate(rowStart)) rowStart else ""
                    val re = if (isIsoDate(rowEnd)) rowEnd else rs
                    if (rs.isBlank()) return@filter true // unknown date: keep visible
                    val lo = from ?: rs
                    val hi = to ?: re
                    // overlap: rs <= hi && re >= lo
                    rs <= hi && re >= lo
                }
            }
            val hasActiveFilter = filterStatus != "all" || isIsoDate(filterDateFrom.trim()) || isIsoDate(filterDateTo.trim())
            if (filteredItems.isNotEmpty()) {
                OutlinedButton(
                    onClick = { exportIzinCsv(filteredItems) },
                    enabled = !loading && AppServices.textExporter != null,
                ) { Text("Ekspor CSV") }
            }
            if (filteredItems.isEmpty() && !loading) {
                val emptyMsg = if (items.isEmpty()) {
                    "Belum ada data izin ditampilkan. Ajukan perizinan atau muat ulang jika data sudah ada di server."
                } else {
                    "Tidak ada riwayat izin yang cocok dengan filter (status/tanggal) pada data yang dimuat."
                }
                Text(emptyMsg, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (filteredItems.isNotEmpty()) {
                if (hasActiveFilter) {
                    Text("Menampilkan ${filteredItems.size} dari ${items.size} data (filter aktif).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredItems) { it ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val jenis = it.str("jenis_ijin_text").ifBlank { it.str("tipe_ijin_text").ifBlank { "Izin" } }
                                val tgl = it.str("tgl_ijin").ifBlank { it.str("tgl_ijin_dari").ifBlank { it.str("tanggal") } }
                                val sampai = it.str("tgl_ijin_sampai")
                                val alasan = it.str("alasan").ifBlank { it.str("keterangan") }
                                val (statusLabel, statusColor) = izinStatusDisplay(it)
                                val id = it.str("id").ifBlank { it.str("Id") }
                                val tglText = when {
                                    tgl.isBlank() -> "-"
                                    sampai.isNotBlank() && sampai != tgl -> "$tgl s/d $sampai"
                                    else -> tgl
                                }
                                Row(verticalAlignment = Alignment.Top) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(jenis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text("Tanggal: $tglText", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (statusLabel.isNotBlank()) {
                                            Text("Status: $statusLabel", fontSize = 12.sp, color = statusColor, fontWeight = FontWeight.Medium)
                                        }
                                        if (alasan.isNotBlank()) {
                                            Text("Alasan: $alasan", fontSize = 12.sp)
                                        } else {
                                            Text("Alasan: -", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (id.isNotBlank()) {
                                            Text("ID: $id", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (id.isNotBlank()) {
                                        TextButton(onClick = { deleteItem(it) }, enabled = !loading && canSensitive) { Text("Hapus", fontSize = 12.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text("Catatan: pengajuan memakai parameter add_ijin yang diperlukan. Lampiran JPG/PDF (jika dipilih) diunggah terpisah ke /importfile setelah pengajuan tersimpan melalui proxy lokal.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
}

/**
 * Form pengajuan izin — parity dengan alur Tauri:
 * `jenis_ijin` + `getmastertipeijin` + `tipe_ijin` → validasi klien → `add_ijin`.
 * Lampiran sengaja tidak dikirim di payload (server memakai /importfile terpisah).
 */
@Composable
private fun PerizinanAjukanCard(profile: ServerProfile) {
    val ctx by AppServices.serverContext.collectAsState()
    var masterList by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var jenisList by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var tipeMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var jenisId by remember { mutableStateOf("") }
    var tipeId by remember { mutableStateOf("") }
    var dari by remember { mutableStateOf(todayIso()) }
    var sampai by remember { mutableStateOf(todayIso()) }
    var alasan by remember { mutableStateOf("") }
    var attachment by remember { mutableStateOf<PickedFile?>(null) }
    var uploadingAttachment by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var jenisOpen by remember { mutableStateOf(false) }
    var tipeOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canSensitive = rememberCanSensitiveAction()

    fun field(o: JsonObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { k -> o.str(k).takeIf { it.isNotBlank() } } ?: ""
    fun idOf(o: JsonObject) = field(o, "Id", "id")
    fun nameOf(o: JsonObject) = field(o, "Nama", "nama", "NamaIjin", "nama_ijin")
    fun tipeIdOf(o: JsonObject) = field(o, "TipeId", "tipeId", "tipe_id").toIntOrNull()

    val selectedJenis = jenisList.firstOrNull { idOf(it) == jenisId }
    val isSetengahHari = selectedJenis?.let { j ->
        (j["SetengahHari"] as? JsonPrimitive)?.booleanOrNull
            ?: j.str("SetengahHari").lowercase().let { it == "true" || it == "1" }
            || (j["setengahHari"] as? JsonPrimitive)?.booleanOrNull == true
    } ?: false
    val selectedMasterId = selectedJenis?.let { tipeIdOf(it) }
    val selectedMaster = masterList.firstOrNull { idOf(it).toIntOrNull() == selectedMasterId }
    val tipeOptions: List<Int> = (selectedMaster?.get("Tipe") as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { el -> (el as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: runCatching { el.jsonPrimitive.contentOrNull?.toIntOrNull() }.getOrNull() }
        ?.ifEmpty { listOf(1, 2, 3) }
        ?: listOf(1, 2, 3)
    val jenisLabel = selectedJenis?.let { nameOf(it) }?.ifBlank { null } ?: "Pilih jenis izin"
    val tipeLabel = tipeMap[tipeId] ?: if (tipeId.isNotBlank()) "Tipe $tipeId" else "Pilih tipe izin"

    fun loadCatalog() {
        if (ctx.apiKey.isBlank() && AppServices.serverContext.value.apiKey.isBlank()) return
        loading = true
        scope.launch {
            try {
                val masterEl = AppServices.rpcClient.callResultElement(
                    RpcObjects.GET_MASTER_TIPE_IJIN,
                    ctx = AppServices.serverContext.value,
                    profileId = profile.id,
                )
                val jenisEl = AppServices.rpcClient.callResultElement(
                    RpcObjects.JENIS_IJIN,
                    param = buildJsonObject { put("absen", 0); put("master_tipe_ijin", 0) },
                    ctx = AppServices.serverContext.value,
                    profileId = profile.id,
                )
                val tipeEl = AppServices.rpcClient.callResultElement(
                    RpcObjects.TIPE_IJIN,
                    ctx = AppServices.serverContext.value,
                    profileId = profile.id,
                )
                masterList = masterEl.asArray().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                jenisList = jenisEl.asArray().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                val tipeObj = when (tipeEl) {
                    is JsonObject -> (tipeEl["result"] as? JsonObject) ?: tipeEl
                    else -> null
                }
                tipeMap = tipeObj?.entries
                    ?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { label -> k to label } }
                    ?.toMap()
                    ?.filterKeys { it.isNotBlank() }
                    ?: emptyMap()
                message = if (jenisList.isEmpty()) "Katalog jenis izin kosong dari server." else null
                isError = false
            } catch (e: Exception) {
                message = "Gagal memuat katalog izin: ${e.message}"
                isError = true
            } finally { loading = false }
        }
    }

    fun submit() {
        if (!canSensitive) { message = "Akun ini tidak memiliki izin untuk mengajukan izin (aksi sensitif)."; isError = true; return }
        if (AppServices.serverContext.value.apiKey.isBlank()) { message = "Sesi server belum aktif. Login untuk mengajukan izin."; isError = true; return }
        if (jenisId.isBlank()) { message = "Pilih jenis izin terlebih dahulu."; isError = true; return }
        if (tipeId.isBlank()) { message = "Pilih tipe izin terlebih dahulu."; isError = true; return }
        if (alasan.trim().isBlank()) { message = "Alasan wajib diisi."; isError = true; return }
        val sampaiFinal = if (isSetengahHari) dari else sampai
        if (!isIsoDate(dari) || !isIsoDate(sampaiFinal)) { message = "Format tanggal tidak valid. Gunakan YYYY-MM-DD."; isError = true; return }
        if (sampaiFinal < dari) { message = "Tanggal selesai tidak boleh lebih awal dari tanggal mulai."; isError = true; return }
        submitting = true
        message = if (attachment != null) "Mengirim pengajuan izin..." else "Mengirim pengajuan izin..."
        isError = false
        scope.launch {
            try {
                val result = AppServices.rpcClient.callResultElement(
                    RpcObjects.ADD_IJIN,
                    buildJsonObject {
                        put("id", "")
                        put("tgl_ijin", dari)
                        put("tgl_ijin_sampai", sampaiFinal)
                        put("alasan", alasan.trim())
                        put("jenis_ijin", jenisId.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(jenisId))
                        put("tipe_ijin", tipeId.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(tipeId))
                    },
                    AppServices.serverContext.value,
                    profile.id,
                )
                val newId = extractIjinId(result)
                if (attachment != null && !newId.isNullOrBlank()) {
                    uploadingAttachment = true
                    val file = attachment!!
                    try {
                        AppServices.rpcClient.uploadImportFile(
                            ctx = AppServices.serverContext.value,
                            izinId = newId,
                            fileName = file.name,
                            mimeType = file.mimeType,
                            bytes = file.bytes,
                            profileId = profile.id,
                        )
                        message = "Pengajuan izin dan lampiran berhasil dikirim (ID $newId). Muat ulang riwayat untuk melihat data terbaru."
                        isError = false
                        attachment = null
                    } catch (e: Exception) {
                        // The submission itself succeeded; report the upload separately.
                        message = "Pengajuan tersimpan (ID $newId), tetapi lampiran gagal diunggah: ${e.message}. Anda dapat mencoba unggah ulang secara manual jika tersedia."
                        isError = true
                    } finally {
                        uploadingAttachment = false
                    }
                } else if (attachment != null && newId.isNullOrBlank()) {
                    message = "Pengajuan izin terkirim, tetapi ID izin tidak diterima sehingga lampiran belum dapat diunggah. Muat ulang riwayat untuk konfirmasi."
                    isError = true
                } else {
                    message = if (newId.isNullOrBlank()) "Pengajuan izin berhasil dikirim. Muat ulang riwayat untuk melihat data terbaru."
                    else "Pengajuan izin berhasil dikirim (ID $newId). Muat ulang riwayat untuk melihat data terbaru."
                    isError = false
                }
                alasan = ""
            } catch (e: Exception) {
                message = "Gagal mengirim pengajuan: ${e.message}"
                isError = true
            } finally { submitting = false }
        }
    }

    LaunchedEffect(profile.id, ctx.apiKey) { if (ctx.apiKey.isNotBlank()) loadCatalog() }
    LaunchedEffect(isSetengahHari, dari) {
        if (isSetengahHari) sampai = dari
    }

    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ajukan Perizinan", fontWeight = FontWeight.Bold)
                    Text("Lengkapi jenis, tipe, tanggal, dan alasan. Lampiran bersifat opsional.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                TextButton(onClick = { loadCatalog() }, enabled = !loading && !submitting) { Text("Muat Katalog") }
            }

            Text("Jenis & Tipe Izin", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton(onClick = { jenisOpen = true }, enabled = jenisList.isNotEmpty() && !submitting) {
                        Text(jenisLabel.take(32), maxLines = 1)
                    }
                    DropdownMenu(expanded = jenisOpen, onDismissRequest = { jenisOpen = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                        jenisList.take(100).forEach { item ->
                            val id = idOf(item)
                            DropdownMenuItem(
                                text = { Text(nameOf(item).ifBlank { "Jenis $id" }) },
                                onClick = {
                                    jenisId = id
                                    tipeId = ""
                                    jenisOpen = false
                                    val half = (item["SetengahHari"] as? JsonPrimitive)?.booleanOrNull
                                        ?: item.str("SetengahHari").lowercase().let { v -> v == "true" || v == "1" }
                                        || (item["setengahHari"] as? JsonPrimitive)?.booleanOrNull == true
                                    if (half) sampai = dari
                                },
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { tipeOpen = true }, enabled = selectedJenis != null && !submitting) {
                        Text(tipeLabel.take(32), maxLines = 1)
                    }
                    DropdownMenu(expanded = tipeOpen, onDismissRequest = { tipeOpen = false }) {
                        tipeOptions.forEach { id ->
                            DropdownMenuItem(
                                text = { Text(tipeMap[id.toString()] ?: "Tipe $id") },
                                onClick = { tipeId = id.toString(); tipeOpen = false },
                            )
                        }
                    }
                }
            }
            if (jenisList.isEmpty() && !loading) {
                Text("Katalog jenis izin belum dimuat. Tekan \"Muat Katalog\" saat sesi server aktif.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text("Rentang Tanggal", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                DateField(
                    value = dari,
                    onValueChange = { if (isSetengahHari) { dari = it; sampai = it } else dari = it },
                    label = "Mulai",
                    isError = dari.isNotBlank() && !isIsoDate(dari),
                    supportingText = if (dari.isNotBlank() && !isIsoDate(dari)) "Format tanggal tidak valid" else "YYYY-MM-DD · tanggal mulai izin",
                    modifier = Modifier.weight(1f),
                )
                DateField(
                    value = if (isSetengahHari) dari else sampai,
                    onValueChange = { if (!isSetengahHari) sampai = it },
                    label = "Selesai",
                    enabled = !isSetengahHari,
                    readOnly = isSetengahHari,
                    isError = !isSetengahHari && ((sampai.isNotBlank() && !isIsoDate(sampai)) || (isIsoDate(dari) && isIsoDate(sampai) && sampai < dari)),
                    supportingText = when {
                        isSetengahHari -> "Otomatis sama dengan tanggal mulai (setengah hari)"
                        sampai.isNotBlank() && !isIsoDate(sampai) -> "Format tanggal tidak valid"
                        isIsoDate(dari) && isIsoDate(sampai) && sampai < dari -> "Tidak boleh sebelum tanggal mulai"
                        else -> "YYYY-MM-DD · tanggal selesai izin"
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (isSetengahHari) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))) {
                    Text(
                        "Jenis \"${nameOf(selectedJenis ?: return@Card)}\" ditandai setengah hari di server, sehingga tanggal selesai otomatis disamakan dengan tanggal mulai.",
                        modifier = Modifier.padding(10.dp),
                        fontSize = 12.sp,
                    )
                }
            }

            OutlinedTextField(
                value = alasan,
                onValueChange = { alasan = it },
                label = { Text("Alasan (wajib)") },
                placeholder = { Text("Jelaskan alasan pengajuan izin") },
                minLines = 2,
                maxLines = 4,
                isError = alasan.isNotBlank() && alasan.trim().isBlank(),
                supportingText = { Text(if (alasan.trim().isBlank()) "Wajib diisi" else "${alasan.trim().length} karakter") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Lampiran (opsional)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (AppServices.hasFilePicker) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                AppServices.requestFilePicker("Pilih lampiran izin (JPG/PDF)", listOf("jpg", "jpeg", "pdf")) { picked ->
                                    if (picked == null) return@requestFilePicker
                                    when {
                                        picked.bytes.size > 5_000_000 -> {
                                            message = "Lampiran terlalu besar. Maksimal 5 MB."
                                            isError = true
                                        }
                                        picked.mimeType != "image/jpeg" && picked.mimeType != "application/pdf" -> {
                                            message = "Lampiran harus berupa foto JPG atau berkas PDF."
                                            isError = true
                                        }
                                        else -> {
                                            attachment = picked
                                            message = "Lampiran dipilih: ${picked.name}. File akan diunggah setelah pengajuan tersimpan."
                                            isError = false
                                        }
                                    }
                                }
                            },
                            enabled = !submitting && !uploadingAttachment,
                        ) { Text(if (attachment == null) "Pilih Lampiran" else "Ganti Lampiran") }
                        if (attachment != null) {
                            TextButton(onClick = { attachment = null; if (!submitting) message = "Lampiran dihapus."; if (!submitting) isError = false }, enabled = !submitting && !uploadingAttachment) { Text("Hapus Lampiran") }
                        }
                    }
                    attachment?.let { file ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Lampiran siap diunggah", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text("Nama: ${file.name}", fontSize = 12.sp)
                                Text("Tipe: ${file.mimeType} • Ukuran: ${file.bytes.size / 1024} KB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Lampiran dikirim terpisah ke /importfile setelah pengajuan izin berhasil disimpan.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } ?: Text("Belum ada lampiran dipilih. Format didukung: JPG/JPEG atau PDF, maksimal 5 MB.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("Pemilihan lampiran tidak tersedia pada platform ini.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { submit() }, enabled = !submitting && !uploadingAttachment && jenisList.isNotEmpty() && canSensitive) {
                    Text(
                        when {
                            uploadingAttachment -> "Mengunggah lampiran..."
                            submitting -> "Mengirim..."
                            else -> "Kirim Pengajuan"
                        },
                    )
                }
                if (submitting || uploadingAttachment) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            message?.let {
                val color = when {
                    isError -> Color(0xFF991B1B)
                    it.contains("berhasil", ignoreCase = true) -> Color(0xFF166534)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Card(colors = CardDefaults.cardColors(containerColor = if (isError) Color(0xFFFEE2E2) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
                    Text(it, modifier = Modifier.padding(10.dp), fontSize = 12.sp, color = color)
                }
            }
        }
    }
}

private data class LaporanRekapRow(
    val tanggal: String,
    val nama: String,
    val departemen: String,
    val jamMasuk: String?,
    val jamPulang: String?,
    val jarak: String,
    val status: String, // hadir | terlambat
)

private fun laporanTanggalKeIso(value: String?): String? {
    val text = value?.trim().orEmpty()
    if (text.isBlank()) return null
    val iso = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(text)
    if (iso != null) return "${iso.groupValues[1]}-${iso.groupValues[2]}-${iso.groupValues[3]}"
    val indo = Regex("""^(\d{1,2})\s+([A-Za-z]+)\s+(\d{4})""").find(text) ?: return null
    val bulan = listOf(
        "januari", "februari", "maret", "april", "mei", "juni",
        "juli", "agustus", "september", "oktober", "november", "desember",
    ).indexOfFirst { it == indo.groupValues[2].lowercase() }
    if (bulan < 0) return null
    val d = indo.groupValues[1].padStart(2, '0')
    val m = (bulan + 1).toString().padStart(2, '0')
    return "${indo.groupValues[3]}-$m-$d"
}

private fun laporanJamTampil(value: String?): String? {
    val text = value?.trim().orEmpty()
    if (text.isBlank()) return null
    val m = Regex("""(\d{1,2}:\d{2})""").find(text) ?: return null
    val hm = m.groupValues[1].split(":")
    if (hm.size != 2) return null
    return "${hm[0].padStart(2, '0')}:${hm[1]}"
}

private fun laporanTimeToMinutes(value: String?): Int? {
    val t = value?.trim().orEmpty()
    val m = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(t) ?: return null
    val h = m.groupValues[1].toIntOrNull() ?: return null
    val min = m.groupValues[2].toIntOrNull() ?: return null
    if (h !in 0..23 || min !in 0..59) return null
    return h * 60 + min
}

private fun laporanTerlambatCompared(jamMasuk: String?, jamMasukAwal: String?): Boolean {
    val masuk = laporanTimeToMinutes(jamMasuk) ?: return false
    val batas = laporanTimeToMinutes(jamMasukAwal) ?: return false
    return masuk > batas + 15
}

private fun laporanDayNameId(isoDate: String): String {
    val d = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return ""
    return when (d.dayOfWeek) {
        DayOfWeek.MONDAY -> "senin"
        DayOfWeek.TUESDAY -> "selasa"
        DayOfWeek.WEDNESDAY -> "rabu"
        DayOfWeek.THURSDAY -> "kamis"
        DayOfWeek.FRIDAY -> "jumat"
        DayOfWeek.SATURDAY -> "sabtu"
        DayOfWeek.SUNDAY -> "minggu"
        else -> ""
    }
}

private fun JsonObject.strAny(vararg keys: String): String {
    for (k in keys) {
        val v = this[k] ?: continue
        val s = when (v) {
            is JsonPrimitive -> v.contentOrNull ?: v.toString().trim('"')
            else -> v.toString()
        }
        if (s.isNotBlank() && s != "null") return s
    }
    return ""
}

private fun pilihWorkCodeUntukHari(codes: List<JsonObject>, isoDate: String): JsonObject? {
    if (codes.isEmpty()) return null
    val day = laporanDayNameId(isoDate)
    if (day.isBlank()) return codes.firstOrNull { !it.strAny("nama", "Nama").uppercase().startsWith("(EVENT)") } ?: codes.firstOrNull()
    val byName = codes.filter { it.strAny("nama", "Nama").lowercase().startsWith(day) }
    val biasa = byName.filter { !it.strAny("nama", "Nama").uppercase().startsWith("(EVENT)") }
    return biasa.firstOrNull()
        ?: byName.firstOrNull()
        ?: codes.firstOrNull { !it.strAny("nama", "Nama").uppercase().startsWith("(EVENT)") }
        ?: codes.firstOrNull()
}

private fun isDatangChecktype(value: String?): Boolean {
    val t = value?.trim()?.lowercase().orEmpty()
    return t == "datang" || t == "masuk" || t == "1"
}

private fun isPulangChecktype(value: String?): Boolean {
    val t = value?.trim()?.lowercase().orEmpty()
    return t == "pulang" || t == "keluar" || t == "2"
}

private fun workCodeJamMasukAwal(code: JsonObject?): String? {
    if (code == null) return null
    val hari = code["hari"] as? JsonObject ?: return code.strAny("jam_masuk_awal").ifBlank { null }
    val jam = hari["jam"] as? JsonObject ?: return null
    return jam.strAny("jam_masuk_awal", "jam_masuk").ifBlank { null }
}

private fun buildLaporanRekap(rows: List<JsonObject>, workCodes: List<JsonObject>): List<LaporanRekapRow> {
    val groups = linkedMapOf<String, MutableList<JsonObject>>()
    for (row in rows) {
        val iso = laporanTanggalKeIso(row.str("waktu")) ?: continue
        val nama = row.str("nama").ifBlank { "-" }
        val key = "$nama|$iso"
        groups.getOrPut(key) { mutableListOf() }.add(row)
    }
    val out = mutableListOf<LaporanRekapRow>()
    for ((key, bucket) in groups) {
        val iso = key.substringAfterLast('|')
        val schedule = pilihWorkCodeUntukHari(workCodes, iso)
        val punches = bucket.sortedBy { it.str("waktu") }
        val masuk = punches.firstOrNull { isDatangChecktype(it.str("checktype")) }
        val pulang = punches.lastOrNull { isPulangChecktype(it.str("checktype")) }
        val reference = masuk ?: punches.firstOrNull()
        val jamMasuk = laporanJamTampil(masuk?.str("waktu") ?: reference?.str("waktu"))
        val jamPulang = laporanJamTampil(pulang?.str("waktu"))
        val status = if (laporanTerlambatCompared(jamMasuk, workCodeJamMasukAwal(schedule))) "terlambat" else "hadir"
        out += LaporanRekapRow(
            tanggal = iso,
            nama = reference?.str("nama").orEmpty(),
            departemen = reference?.str("departemen").orEmpty(),
            jamMasuk = jamMasuk,
            jamPulang = jamPulang,
            jarak = (masuk ?: reference)?.str("jarak").orEmpty(),
            status = status,
        )
    }
    return out.sortedWith(compareByDescending<LaporanRekapRow> { it.tanggal }.thenBy { it.nama })
}

private fun laporanAverageMinutes(values: List<String?>): Int? {
    var total = 0
    var count = 0
    for (v in values) {
        val m = laporanTimeToMinutes(v) ?: continue
        total += m; count += 1
    }
    return if (count == 0) null else (total / count)
}

private fun formatMinutesLabel(prefix: String, minutes: Int?): String {
    if (minutes == null) return "$prefix -"
    val h = minutes / 60
    val m = minutes % 60
    return "$prefix ${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
}

internal fun izinStatusKey(obj: JsonObject): String {
    val statusRaw = obj.strAny("status", "status_approval")
    val approvalBool = (obj["approval"] as? JsonPrimitive)?.booleanOrNull
        ?: obj.str("approval").lowercase().let { if (it == "true") true else if (it == "false") false else null }
    val code = statusRaw.trim().toIntOrNull()
    val label = izinStatusDisplay(obj).first.lowercase()
    return when {
        code == 1 || approvalBool == true || label.contains("setuju") || label.contains("approve") || label.contains("disetujui") -> "approved"
        code == 3 || label.contains("tolak") || label.contains("reject") || label.contains("ditolak") -> "ditolak"
        code == 2 || approvalBool == false || label.contains("tunggu") || label.contains("pending") || label.contains("menunggu") -> "pending"
        else -> "pending"
    }
}

internal fun izinStatusDisplay(obj: JsonObject): Pair<String, Color> {
    val approvalText = obj.strAny("approval_text", "approvalText").ifBlank { obj.str("approval_text") }
    val statusRaw = obj.strAny("status", "status_approval")
    val approvalBool = (obj["approval"] as? JsonPrimitive)?.booleanOrNull
        ?: obj.str("approval").lowercase().let { if (it == "true") true else if (it == "false") false else null }
    val code = statusRaw.trim().toIntOrNull()
    val label = when {
        code == 1 -> approvalText.ifBlank { "Disetujui" }
        code == 2 -> approvalText.ifBlank { "Menunggu" }
        code == 3 -> approvalText.ifBlank { "Ditolak" }
        approvalBool == true -> approvalText.ifBlank { "Disetujui" }
        approvalBool == false -> approvalText.ifBlank { "Menunggu" }
        approvalText.isNotBlank() -> approvalText
        statusRaw.isNotBlank() -> statusRaw
        else -> ""
    }
    val lower = label.lowercase()
    val color = when {
        code == 1 || approvalBool == true || lower.contains("setuju") || lower.contains("approve") || lower.contains("disetujui") -> Color(0xFF166534)
        code == 3 || lower.contains("tolak") || lower.contains("reject") || lower.contains("ditolak") -> Color(0xFF991B1B)
        code == 2 || lower.contains("tunggu") || lower.contains("pending") || lower.contains("menunggu") -> Color(0xFF92400E)
        else -> Color(0xFF475569)
    }
    return label to color
}

internal fun buildIzinCsv(rows: List<JsonObject>): String = buildString {
    val headers = listOf("ID", "Nama", "NIP", "Jenis", "Tipe", "Mulai", "Selesai", "Alasan", "Berkas", "Diajukan", "Status", "Catatan")
    appendLine(headers.joinToString(",") { csvCell(it) })
    rows.forEach { row ->
        val file = row.str("berkas").ifBlank { row.str("file_lampiran") }
            .substringBefore('?').substringAfterLast('/').substringAfterLast('\\')
        val values = listOf(
            row.str("id").ifBlank { row.str("Id") },
            row.str("nama").ifBlank { row.str("nama_pegawai") },
            row.str("email").ifBlank { row.str("nip") },
            row.str("jenis_ijin_text"),
            row.str("tipe_ijin_text"),
            row.str("tgl_ijin_dari").ifBlank { row.str("tgl_ijin") },
            row.str("tgl_ijin_sampai").ifBlank { row.str("tgl_ijin_dari").ifBlank { row.str("tgl_ijin") } },
            row.str("alasan").ifBlank { row.str("keterangan") }.replace(Regex("[\\r\\n]+"), " "),
            file,
            row.str("created_at"),
            izinStatusDisplay(row).first,
            row.str("catatan").replace(Regex("[\\r\\n]+"), " "),
        )
        appendLine(values.joinToString(",") { csvCell(it) })
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun LaporanScreen(profile: ServerProfile, page: PageDef) {
    val ctx by AppServices.serverContext.collectAsState()
    var from by remember { mutableStateOf(daysAgoIso(7)) }
    var to by remember { mutableStateOf(todayIso()) }
    var loading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var workCodes by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var previewOpen by remember { mutableStateOf(false) }
    var generatedPreview by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var reportType by remember { mutableStateOf("riwayat") } // riwayat | rekap
    val scope = rememberCoroutineScope()
    val canExport = rememberCanSensitiveAction()

    val rekapRows = remember(rows, workCodes) { buildLaporanRekap(rows, workCodes) }
    val stats = remember(rows, rekapRows) {
        val terlambat = rekapRows.count { it.status == "terlambat" }
        val tanpaPulang = rekapRows.count { it.jamPulang.isNullOrBlank() }
        val avgMasuk = laporanAverageMinutes(rekapRows.map { it.jamMasuk })
        val avgPulang = laporanAverageMinutes(rekapRows.map { it.jamPulang })
        mapOf(
            "totalRows" to rows.size,
            "rekapDays" to rekapRows.size,
            "hadir" to (rekapRows.size - terlambat),
            "terlambat" to terlambat,
            "tanpaPulang" to tanpaPulang,
        ) to (avgMasuk to avgPulang)
    }

    fun load() {
        if (ctx.apiKey.isBlank()) { msg = "Sesi belum aktif."; return }
        val dates = expandDateRange(from, to, maxDays = 62)
        if (dates.isEmpty()) { msg = "Rentang tanggal tidak valid."; return }
        scope.launch {
            loading = true; msg = null
            try {
                coroutineScope {
                    val wcDef = async {
                        runCatching {
                            val wcRes = AppServices.rpcClient.callRaw(RpcObjects.GET_WORK_CODE, buildJsonObject {}, ctx, profile.id)
                            val payload = wcRes.result ?: kotlinx.serialization.json.JsonArray(emptyList())
                            val arr = (payload as? JsonArray) ?: (payload as? JsonObject)?.get("result") as? JsonArray
                            arr?.mapNotNull { it as? JsonObject } ?: emptyList()
                        }.getOrElse { emptyList() }
                    }
                    val rowsPerDay = dates.map { d ->
                        async {
                            val res = AppServices.rpcClient.callRaw(
                                RpcObjects.HISTORY_ABSEN,
                                buildJsonObject { put("tgl", d); put("page", 1); put("limit", 200) },
                                ctx, profile.id,
                            )
                            val arr = res.result as? JsonArray ?: JsonArray(emptyList())
                            arr.mapNotNull { it as? JsonObject }
                        }
                    }.awaitAll()
                    workCodes = wcDef.await()
                    val flat = rowsPerDay.flatten()
                    rows = flat
                    val rangeLabel = if (dates.size == 1) dates.first() else "${dates.last()} s/d ${dates.first()}"
                    msg = "Menampilkan ${flat.size} baris riwayat, ${buildLaporanRekap(flat, workCodes).size} rekap harian dari ${dates.size} tanggal ($rangeLabel)."
                }
            } catch (e: Exception) {
                msg = "Gagal memuat laporan: ${e.message}"
                rows = emptyList()
            } finally { loading = false }
        }
    }

    val exporter = AppServices.textExporter
    val binaryExporter = AppServices.binaryExporter
    val binaryPreviewer = AppServices.binaryPreviewer
    val (avgMasuk, avgPulang) = stats.second
    val statMap = stats.first

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Laporan Presensi", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = reportType == "riwayat",
                        onClick = { reportType = "riwayat" },
                        label = { Text("Riwayat Absen") },
                    )
                    FilterChip(
                        selected = reportType == "rekap",
                        onClick = { reportType = "rekap" },
                        label = { Text("Rekap Harian") },
                    )
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 760.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DateField(value = from, onValueChange = { from = it }, label = "Dari", modifier = Modifier.fillMaxWidth())
                            DateField(value = to, onValueChange = { to = it }, label = "Sampai", modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { load() }, enabled = !loading) { Text(if (loading) "Memuat..." else "Tampilkan") }
                                OutlinedButton(onClick = { previewOpen = true }, enabled = (rows.isNotEmpty() || rekapRows.isNotEmpty()) && !loading) {
                                    Text("Pratinjau")
                                }
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            DateField(value = from, onValueChange = { from = it }, label = "Dari", modifier = Modifier.width(180.dp))
                            DateField(value = to, onValueChange = { to = it }, label = "Sampai", modifier = Modifier.width(180.dp))
                            Button(onClick = { load() }, enabled = !loading) { Text(if (loading) "Memuat..." else "Tampilkan") }
                            OutlinedButton(onClick = { previewOpen = true }, enabled = (rows.isNotEmpty() || rekapRows.isNotEmpty()) && !loading) {
                                Text("Pratinjau")
                            }
                        }
                    }
                }
                if (exporter != null) {
                    if (!canExport) {
                        Text(
                            "Ekspor terkunci: akun ini tidak memiliki izin aksi sensitif. Hubungi administrator.",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                        )
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            maxItemsInEachRow = if (maxWidth < 680.dp) 2 else 3,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                        OutlinedButton(
                            onClick = {
                                val csv = if (reportType == "rekap") buildRekapCsv(rekapRows) else buildRiwayatCsv(rows)
                                val saved = exporter("tetuko-laporan-${reportType}-$from-$to.csv", csv)
                                msg = if (saved) "Laporan CSV (${if (reportType == "rekap") "rekap" else "riwayat"}) disimpan." else "Ekspor dibatalkan."
                            },
                            enabled = (if (reportType == "rekap") rekapRows.isNotEmpty() else rows.isNotEmpty()) && !loading && canExport,
                        ) { Text(if (canExport) "Ekspor CSV" else "Ekspor terkunci") }
                        OutlinedButton(
                            onClick = {
                                val xml = SpreadsheetXml.build(
                                    listOf(
                                        ExcelSheet(
                                            name = "Riwayat",
                                            header = REPORT_COLUMNS.map { it.second },
                                            rows = rows.map { r -> REPORT_COLUMNS.map { r.str(it.first) } },
                                            widths = listOf(14, 22, 12, 24, 24, 18),
                                        ),
                                        ExcelSheet(
                                            name = "Rekap Harian",
                                            header = listOf("Tanggal", "Nama", "Departemen", "Jam Masuk", "Jam Pulang", "Jarak", "Status"),
                                            rows = rekapRows.map { r -> listOf(r.tanggal, r.nama, r.departemen, r.jamMasuk.orEmpty(), r.jamPulang.orEmpty(), r.jarak, r.status) },
                                            widths = listOf(14, 24, 24, 12, 12, 12, 12),
                                        ),
                                    ),
                                )
                                val name = "laporan-$reportType-$from-$to.xls"
                                val saved = (binaryExporter?.invoke(name, xml.encodeToByteArray())
                                    ?: exporter?.invoke(name, xml)) == true
                                generatedPreview = name to xml.encodeToByteArray()
                                msg = if (saved) "Laporan Excel (Riwayat + Rekap) disimpan." else "Ekspor dibatalkan."
                            },
                            enabled = (rows.isNotEmpty() || rekapRows.isNotEmpty()) && !loading && canExport,
                        ) { Text(if (canExport) "Ekspor Excel" else "Ekspor terkunci") }
                        OutlinedButton(
                            onClick = {
                                val pdf = if (reportType == "rekap") {
                                    PdfTable.build(
                                        title = "Laporan Presensi TETUKO — Rekap Harian",
                                        subtitle = "$from s/d $to • ${rekapRows.size} baris rekap",
                                        headers = listOf("Tanggal", "Nama", "Departemen", "Masuk", "Pulang", "Jarak", "Status"),
                                        widths = listOf(65, 120, 120, 55, 55, 55, 60),
                                        rows = rekapRows.map { r -> listOf(r.tanggal, r.nama, r.departemen, r.jamMasuk ?: "-", r.jamPulang ?: "-", r.jarak.ifBlank { "-" }, r.status) },
                                    )
                                } else {
                                    PdfTable.build(
                                        title = "Laporan Presensi TETUKO — Riwayat",
                                        subtitle = "$from s/d $to • ${rows.size} baris",
                                        headers = REPORT_COLUMNS.map { it.second },
                                        widths = listOf(70, 110, 55, 110, 120, 95),
                                        rows = rows.map { r -> REPORT_COLUMNS.map { r.str(it.first).ifBlank { "-" } } },
                                    )
                                }
                                val name = "laporan-$reportType-$from-$to.pdf"
                                val bytes = pdf.encodeToByteArray()
                                val saved = (binaryExporter?.invoke(name, bytes)
                                    ?: exporter?.invoke(name, pdf)) == true
                                generatedPreview = name to bytes
                                msg = if (saved) "Laporan PDF (${if (reportType == "rekap") "rekap" else "riwayat"}) disimpan." else "Ekspor dibatalkan."
                            },
                            enabled = (if (reportType == "rekap") rekapRows.isNotEmpty() else rows.isNotEmpty()) && !loading && canExport,
                        ) { Text(if (canExport) "Ekspor PDF" else "Ekspor terkunci") }
                        }
                    }
                }
                generatedPreview?.let { (name, bytes) ->
                    OutlinedButton(
                        onClick = {
                            val opened = binaryPreviewer?.invoke(name, bytes) ?: false
                            msg = if (opened) "Pratinjau native dibuka: $name" else "Pratinjau native tidak tersedia. Gunakan tombol ekspor untuk menyimpan."
                        },
                        enabled = canExport,
                    ) { Text("Pratinjau Berkas Terakhir") }
                }
                if (msg != null) Text(msg!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (rows.isNotEmpty() || rekapRows.isNotEmpty()) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            maxItemsInEachRow = if (maxWidth < 680.dp) 2 else 4,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                        LaporanStatTile("Baris Riwayat", "${statMap["totalRows"] ?: 0}", "Data dimuat", Modifier.weight(1f))
                        LaporanStatTile("Hadir", "${statMap["hadir"] ?: 0}", formatMinutesLabel("rata-rata masuk", avgMasuk), Modifier.weight(1f))
                        LaporanStatTile("Terlambat", "${statMap["terlambat"] ?: 0}", formatMinutesLabel("rata-rata pulang", avgPulang), Modifier.weight(1f))
                        LaporanStatTile("Belum Pulang", "${statMap["tanpaPulang"] ?: 0}", "Rekap harian: ${statMap["rekapDays"] ?: 0}", Modifier.weight(1f))
                        }
                    }
                }

                if (reportType == "rekap") {
                    if (rekapRows.isEmpty() && !loading) {
                        Text("Belum ada data rekap pada rentang tanggal yang dimuat.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (rekapRows.isNotEmpty()) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Rekap Harian (pivot lokal dari history_absen)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(vertical = 6.dp, horizontal = 4.dp)) {
                                    listOf("Tanggal" to 90, "Pegawai" to 170, "Departemen" to 150, "Masuk" to 70, "Pulang" to 70, "Jarak" to 70, "Status" to 90).forEach { (label, w) ->
                                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(w.dp).padding(horizontal = 4.dp))
                                    }
                                }
                                LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(rekapRows) { r ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(r.tanggal, fontSize = 12.sp, modifier = Modifier.width(90.dp).padding(horizontal = 4.dp))
                                            Text(r.nama.ifBlank { "-" }, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.width(170.dp).padding(horizontal = 4.dp))
                                            Text(r.departemen.ifBlank { "-" }, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.width(150.dp).padding(horizontal = 4.dp))
                                            Text(r.jamMasuk ?: "-", fontSize = 12.sp, modifier = Modifier.width(70.dp).padding(horizontal = 4.dp))
                                            Text(r.jamPulang ?: "-", fontSize = 12.sp, modifier = Modifier.width(70.dp).padding(horizontal = 4.dp))
                                            Text(r.jarak.ifBlank { "-" }, fontSize = 12.sp, modifier = Modifier.width(70.dp).padding(horizontal = 4.dp))
                                            val statusColor = if (r.status == "terlambat") Color(0xFFB45309) else Color(0xFF166534)
                                            Text(if (r.status == "terlambat") "Terlambat" else "Hadir", fontSize = 12.sp, color = statusColor, fontWeight = FontWeight.Medium, modifier = Modifier.width(90.dp).padding(horizontal = 4.dp))
                                        }
                                    }
                                }
                                Text("Status dihitung lokal: terlambat jika jam masuk lebih dari 15 menit setelah jam_masuk_awal work code.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else {
                    if (rows.isNotEmpty()) {
                        val byType = remember(rows) {
                            rows.groupingBy { r -> r.str("checktype").ifBlank { "Tidak diketahui" } }.eachCount()
                                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                        }
                        val byDay = remember(rows) {
                            rows.groupingBy { r ->
                                val w = r.str("waktu").trim()
                                val d = w.take(10)
                                if (Regex("""\d{4}-\d{2}-\d{2}""").matches(d)) d else "Tanggal tidak diketahui"
                            }.eachCount().entries.sortedByDescending { it.key }
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Ringkasan Riwayat (data dimuat)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Total baris: ${rows.size}", fontSize = 12.sp)
                                Text(
                                    "Per tipe: " + byType.joinToString(" • ") { "${it.key}: ${it.value}" },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val daySummary = if (byDay.size <= 7) {
                                    byDay.joinToString(" • ") { "${it.key}: ${it.value}" }
                                } else {
                                    byDay.take(7).joinToString(" • ") { "${it.key}: ${it.value}" } + " • +${byDay.size - 7} hari lain"
                                }
                                Text("Per hari: $daySummary", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(rows.take(100)) { r ->
                                val ct = r.str("checktype")
                                val waktu = r.str("waktu")
                                val jarak = r.str("jarak")
                                Text("• ${ct.ifBlank { "-" }}  ${waktu.ifBlank { "-" }}  ${if (jarak.isNotBlank()) "($jarak)" else ""}", fontSize = 12.sp)
                            }
                        }
                        if (rows.size > 100) {
                            Text("Menampilkan 100 baris pertama di daftar. Pratinjau/ekspor memuat seluruh ${rows.size} baris yang dimuat.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (!loading && msg != null && msg!!.startsWith("Menampilkan 0")) {
                        Text("Tidak ada data pada rentang/tanggal yang dipilih.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Sumber data: history_absen (per tanggal, maks. 62 hari per muat) + getworkcode untuk rekap. Rekap/ekspor dihitung dari data yang berhasil dimuat.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (previewOpen) {
            ReportPreviewDialog(
                rows = rows,
                rekapRows = rekapRows,
                reportType = reportType,
                from = from,
                to = to,
                onClose = { previewOpen = false },
            )
        }
    }
}

@Composable
private fun LaporanStatTile(label: String, value: String, hint: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

private fun buildRiwayatCsv(rows: List<JsonObject>): String = buildString {
    appendLine(REPORT_COLUMNS.joinToString(",") { csvCell(it.second) })
    rows.forEach { r -> appendLine(REPORT_COLUMNS.joinToString(",") { csvCell(r.str(it.first)) }) }
}

private fun buildRekapCsv(rows: List<LaporanRekapRow>): String = buildString {
    appendLine(listOf("Tanggal", "Nama", "Departemen", "Jam Masuk", "Jam Pulang", "Jarak", "Status").joinToString(",") { csvCell(it) })
    rows.forEach { r ->
        appendLine(listOf(r.tanggal, r.nama, r.departemen, r.jamMasuk ?: "", r.jamPulang ?: "", r.jarak, r.status).joinToString(",") { csvCell(it) })
    }
}

@Composable
private fun ReportPreviewDialog(
    rows: List<JsonObject>,
    rekapRows: List<LaporanRekapRow>,
    reportType: String,
    from: String,
    to: String,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 980.dp).heightIn(max = 640.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pratinjau Laporan (${if (reportType == "rekap") "Rekap Harian" else "Riwayat"})", fontWeight = FontWeight.Bold)
                        Text(
                            "$from s/d $to • ${if (reportType == "rekap") "${rekapRows.size} baris rekap" else "${rows.size} baris riwayat"}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onClose) { Text("Tutup") }
                }
                if (reportType == "rekap") {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(vertical = 8.dp)) {
                        listOf("Tanggal" to 95, "Nama" to 170, "Departemen" to 170, "Masuk" to 80, "Pulang" to 80, "Jarak" to 80, "Status" to 100).forEach { (label, w) ->
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(w.dp).padding(horizontal = 6.dp))
                        }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                        items(rekapRows) { r ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                listOf(
                                    r.tanggal to 95, r.nama.ifBlank { "—" } to 170, r.departemen.ifBlank { "—" } to 170,
                                    (r.jamMasuk ?: "—") to 80, (r.jamPulang ?: "—") to 80, (r.jarak.ifBlank { "—" }) to 80,
                                    (if (r.status == "terlambat") "Terlambat" else "Hadir") to 100,
                                ).forEach { (v, w) ->
                                    Text(v, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.width(w.dp).padding(horizontal = 6.dp))
                                }
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(vertical = 8.dp)) {
                        REPORT_COLUMNS.forEach { (_, label) ->
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(150.dp).padding(horizontal = 6.dp))
                        }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                        items(rows) { r ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                REPORT_COLUMNS.forEach { (key, _) ->
                                    Text(
                                        r.str(key).ifBlank { "—" },
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.width(150.dp).padding(horizontal = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Text("Tabel dapat digulir. Ekspor Excel memuat Riwayat + Rekap; CSV/PDF mengikuti tampilan yang dipilih.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


@Composable
fun PresetScreen(profile: ServerProfile, page: PageDef) {
    val permissions = LocalAppPermissions.current
    val appState = LocalAppState.current
    val canControlAutomation = permissions.allowAutomationControl || permissions.canUseFeature("automation_control")
    val profileId = profile.id
    var presets by remember(profileId) { mutableStateOf(loadAutomationPresets(profileId)) }
    var logs by remember(profileId) { mutableStateOf(loadAutomationLogs(profileId)) }
    var stockPhotos by remember(profileId) { mutableStateOf(loadAutomationStockPhotos(profileId)) }
    var editing by remember(profileId) { mutableStateOf<id.tetuko.core.domain.model.AutomationPreset?>(null) }
    var creating by remember(profileId) { mutableStateOf(false) }
    var status by remember(profileId) { mutableStateOf<String?>(null) }
    var schedulerEnabled by remember { mutableStateOf(AppServices.isAutomationSchedulerEnabled()) }
    var productivityReminderEnabled by remember { mutableStateOf(AppServices.isProductivityReminderEnabled()) }
    var productivityReminderLimit by remember { mutableStateOf(AppServices.productivityReminderLimit()) }
    var productivityReminderDays by remember { mutableStateOf(AppServices.productivityReminderDays()) }
    var presetQuery by remember(profileId) { mutableStateOf("") }
    var presetFilter by remember(profileId) { mutableStateOf("semua") }
    var presetTombstones by remember(profileId) { mutableStateOf(setOf<String>()) }

    fun persist(next: List<id.tetuko.core.domain.model.AutomationPreset>) {
        val capped = next.take(100)
        presets = capped
        val ok = saveAutomationPresets(profileId, capped)
        if (!ok) status = "Penyimpanan lokal tidak tersedia; perubahan hanya sementara."
    }

    fun persistLogs(next: List<id.tetuko.core.domain.model.AutomationLogEntry>) {
        logs = next
        val ok = saveAutomationLogs(profileId, next)
        if (!ok) status = "Log lokal tidak dapat disimpan di platform ini."
    }

    fun persistStockPhotos(next: List<id.tetuko.core.domain.model.AutomationStockPhoto>) {
        stockPhotos = next
        val ok = saveAutomationStockPhotos(profileId, next)
        if (!ok) status = "Stok foto lokal tidak dapat disimpan di platform ini."
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun addStockPhotoFromPicker() {
        if (!canControlAutomation) { status = "Tidak memiliki izin untuk mengelola stok foto otomasi."; return }
        if (!AppServices.hasFilePicker) {
            status = "Pemilih berkas tidak tersedia di platform ini."
            return
        }
        AppServices.requestFilePicker("Pilih foto stok otomasi", listOf("jpg", "jpeg", "png", "webp")) { picked ->
            if (picked == null) return@requestFilePicker
            if (picked.bytes.isEmpty()) { status = "Berkas foto kosong."; return@requestFilePicker }
            if (picked.bytes.size > 1_500_000) {
                status = "Foto terlalu besar untuk stok lokal (${picked.bytes.size / 1024} KB). Gunakan berkas lebih kecil (<= 1.5 MB)."
                return@requestFilePicker
            }
            val now = Clock.System.now().toEpochMilliseconds()
            val normalized = AppServices.imageProcessor?.invoke(picked, 1280, 72) ?: picked
            val hash = ByteString.of(*normalized.bytes).sha256().hex()
            val duplicate = stockPhotos.firstOrNull { it.sha256 == hash }
            if (duplicate != null) {
                status = "Foto sudah ada sebagai stok: ${duplicate.nama.ifBlank { duplicate.id }}."
                return@requestFilePicker
            }
            val photo = id.tetuko.core.domain.model.AutomationStockPhoto(
                id = "stk_${now}_${kotlin.random.Random.nextInt(1000, 9999)}",
                nama = normalized.name.substringBeforeLast('.').ifBlank { "Foto ${stockPhotos.size + 1}" },
                mimeType = "image/jpeg",
                dataBase64 = kotlin.io.encoding.Base64.encode(normalized.bytes),
                ukuranBytes = normalized.bytes.size.toLong(),
                dibuatPada = now,
                sha256 = hash,
            )
            persistStockPhotos((listOf(photo) + stockPhotos).take(30))
            status = "Stok foto ditambahkan: ${photo.nama}."
        }
    }

    fun removeStockPhoto(photoId: String) {
        if (!canControlAutomation) { status = "Tidak memiliki izin untuk mengelola stok foto otomasi."; return }
        val nextPhotos = stockPhotos.filterNot { it.id == photoId }
        persistStockPhotos(nextPhotos)
        val now = Clock.System.now().toEpochMilliseconds()
        val nextPresets = presets.map { p ->
            if (photoId in p.stockFotoIds) p.copy(stockFotoIds = p.stockFotoIds.filterNot { it == photoId }, diperbarui = now) else p
        }
        if (nextPresets != presets) persist(nextPresets)
        runCatching {
            val rawQ = AppServices.photoQueueReader?.invoke(profileId)
            val q = id.tetuko.core.domain.model.PhotoQueueCodec.decode(rawQ)
            val cleaned = q.queues.mapValues { (_, ids) -> ids.filterNot { it == photoId } }
            AppServices.photoQueueWriter?.invoke(profileId, id.tetuko.core.domain.model.PhotoQueueCodec.encode(id.tetuko.core.domain.model.AutomationPhotoQueueState(cleaned)))
        }
        status = "Stok foto dihapus."
    }

    fun resetPhotoQueues() {
        if (!canControlAutomation) { status = "Tidak memiliki izin untuk mengelola antrian foto."; return }
        val ok = runCatching {
            val encoded = id.tetuko.core.domain.model.PhotoQueueCodec.encode(id.tetuko.core.domain.model.AutomationPhotoQueueState())
            AppServices.photoQueueWriter?.invoke(profileId, encoded) == true
        }.getOrDefault(false)
        status = if (ok) "Antrian foto lokal direset (semua preset)." else "Gagal mereset antrian foto (penyimpanan lokal tidak tersedia)."
    }

    fun resetPresetPhotoQueue(presetId: String, presetName: String) {
        if (!canControlAutomation) { status = "Tidak memiliki izin untuk mengelola antrian foto."; return }
        val ok = runCatching {
            val current = id.tetuko.core.domain.model.PhotoQueueCodec.decode(AppServices.photoQueueReader?.invoke(profileId))
            val newQueues = current.queues.toMutableMap()
            newQueues.remove(presetId)
            AppServices.photoQueueWriter?.invoke(profileId, id.tetuko.core.domain.model.PhotoQueueCodec.encode(id.tetuko.core.domain.model.AutomationPhotoQueueState(newQueues))) == true
        }.getOrDefault(false)
        status = if (ok) "Antrian foto preset '$presetName' direset." else "Gagal mereset antrian foto preset."
    }

    fun appendLog(preset: id.tetuko.core.domain.model.AutomationPreset?, level: String, message: String, source: String = "manual") {
        val now = Clock.System.now().toEpochMilliseconds()
        val entry = id.tetuko.core.domain.model.AutomationLogEntry(
            id = "alog_${now}_${kotlin.random.Random.nextInt(1000, 9999)}",
            presetId = preset?.id.orEmpty(),
            presetNama = preset?.nama.orEmpty(),
            waktu = now,
            status = level,
            pesan = message,
            source = source,
        )
        val next = (listOf(entry) + logs).take(100)
        persistLogs(next)
        AppServices.notifier?.invoke("TETUKO Automasi", "${entry.presetNama.ifBlank { "Preset" }}: $message")
    }

    VerticalScrollColumn(modifier = Modifier.fillMaxSize().padding(20.dp), itemSpacing = 14) {
        PageHeaderCard(profile, page)
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Preset & Otomatisasi", fontWeight = FontWeight.Bold)
                        Text("${presets.size} preset tersimpan • ${stockPhotos.size} stok foto lokal • ${logs.size} log lokal.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Penjadwal", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = schedulerEnabled,
                            onCheckedChange = { enabled ->
                                if (!canControlAutomation) {
                                    status = "Tidak memiliki izin untuk mengubah penjadwal otomasi."
                                } else if (AppServices.setAutomationSchedulerEnabled(enabled)) {
                                    schedulerEnabled = enabled
                                    status = if (enabled) "Penjadwal global diaktifkan." else "Penjadwal global dinonaktifkan."
                                } else {
                                    status = "Pengaturan penjadwal tidak dapat disimpan di platform ini."
                                }
                            },
                            enabled = canControlAutomation && AppServices.canControlAutomationScheduler,
                        )
                    }
                    Button(
                        onClick = {
                            if (!canControlAutomation) {
                                status = "Tidak memiliki izin untuk mengelola preset/otomasi."
                            } else {
                                creating = true; editing = null
                            }
                        },
                        enabled = canControlAutomation,
                    ) { Text("Tambah Preset") }
                }
                if (!canControlAutomation) {
                    Text("Mode lihat saja: izin kontrol otomasi/preset tidak diberikan untuk akun ini.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!AppServices.canControlAutomationScheduler) {
                    Text("Penjadwal latar belum tersedia pada runtime ini.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (!AppServices.isAutomationExactAlarmAvailable()) {
                    Text("Exact alarm tidak tersedia/izin belum diberikan. Automasi tetap berjalan via scheduler periodik, tetapi presisi waktu bisa berkurang.", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
                    val opener = AppServices.exactAlarmSettingsOpener
                    if (opener != null) {
                        OutlinedButton(onClick = {
                            val ok = runCatching { opener() }.getOrDefault(false)
                            status = if (ok) "Buka pengaturan exact alarm." else "Gagal membuka pengaturan exact alarm."
                        }) { Text("Izinkan Exact Alarm") }
                    }
                }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pengingat Produktivitas", fontWeight = FontWeight.SemiBold)
                        Text("Notifikasi jika belum mulai atau aktivitas berjalan lebih dari 2 jam.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = productivityReminderEnabled,
                        onCheckedChange = { enabled ->
                            if (!canControlAutomation) {
                                status = "Tidak memiliki izin mengatur pengingat produktivitas."
                            } else if (AppServices.setProductivityReminderEnabled(enabled)) {
                                productivityReminderEnabled = enabled
                                status = if (enabled) "Pengingat produktivitas diaktifkan." else "Pengingat produktivitas dinonaktifkan."
                            } else {
                                status = "Pengingat tidak dapat disimpan di runtime ini."
                            }
                        },
                        enabled = canControlAutomation,
                    )
                }
                if (productivityReminderEnabled) {
                    TimeField(
                        value = productivityReminderLimit,
                        onValueChange = { productivityReminderLimit = it },
                        label = "Jam batas",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canControlAutomation,
                    )
                    HorizontalScrollRow(modifier = Modifier.fillMaxWidth(), itemSpacing = 6) {
                        listOf(1 to "Sen", 2 to "Sel", 3 to "Rab", 4 to "Kam", 5 to "Jum", 6 to "Sab", 7 to "Min").forEach { (day, label) ->
                            val selected = day in productivityReminderDays
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (!canControlAutomation) return@FilterChip
                                    val next = if (selected) productivityReminderDays.filterNot { it == day } else (productivityReminderDays + day).distinct().sorted()
                                    if (next.isNotEmpty() && AppServices.setProductivityReminderDays(next)) productivityReminderDays = next
                                },
                                label = { Text(label) },
                                enabled = canControlAutomation,
                            )
                        }
                    }
                    Button(onClick = {
                        val limit = productivityReminderLimit.trim()
                        val valid = Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(limit)
                        if (!valid) status = "Format jam batas harus HH:mm, contoh 08:30."
                        else if (AppServices.setProductivityReminderLimit(limit)) status = "Pengingat produktivitas diperbarui: batas $limit WIB."
                        else status = "Pengingat produktivitas tidak dapat disimpan di runtime ini."
                    }, enabled = canControlAutomation) { Text("Simpan Pengingat") }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Status engine otomatis: desktop terjadwal", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(
                            "Scheduler otomatis (desktop/Android) mengevaluasi preset aktif pada zona WIB, memilih waktu eksekusi dalam jendela, menjalankan preflight, lalu mengeksekusi BKD atau Ponorogo. Setiap status dicatat sebagai SUCCESS, FAILED, SKIPPED, RETRYABLE, atau AMBIGUOUS beserta alasan.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        appendLog(null, "INFO", "Log uji manual dicatat. Eksekusi terjadwal hanya dijalankan oleh engine desktop atau preset yang menjalankan tombol Jalankan Sekarang.")
                        status = "Log uji manual ditambahkan."
                    }) { Text("Catat Uji Manual") }
                }
                status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary) }
            }
        }

        if ((creating || editing != null) && canControlAutomation) {
            PresetEditorCard(
                initial = editing,
                availableStockPhotos = stockPhotos,
                onCancel = { creating = false; editing = null },
                onSave = { preset ->
                    if (!canControlAutomation) { status = "Tidak memiliki izin untuk menyimpan preset."; return@PresetEditorCard }
                    val now = Clock.System.now().toEpochMilliseconds()
                    val next = if (editing == null) {
                        presets + preset.copy(
                            id = "pst_${now}_${kotlin.random.Random.nextInt(1000, 9999)}",
                            dibuatPada = now,
                            diperbarui = now,
                        )
                    } else {
                        presets.map { if (it.id == preset.id) preset.copy(diperbarui = now, dibuatPada = it.dibuatPada) else it }
                    }
                    persist(next)
                    creating = false
                    editing = null
                    status = "Preset disimpan."
                },
            )
        } else if (creating || editing != null) {
            status = "Tidak memiliki izin untuk mengelola preset/otomasi."
            creating = false
            editing = null
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Stok Foto Lokal (Otomasi)", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Kelola stok foto lokal untuk otomasi Ponorogo. Foto disimpan per profil dan dipilih melalui antrian preset; preset tidak dijalankan jika prasyarat foto tidak tersedia.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { resetPhotoQueues() }, enabled = canControlAutomation) { Text("Reset Antrian") }
                        FilledTonalButton(onClick = { addStockPhotoFromPicker() }, enabled = canControlAutomation) { Text("Tambah Foto") }
                    }
                }
                if (stockPhotos.isEmpty()) {
                    Text("Belum ada stok foto lokal.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(stockPhotos, key = { it.id }) { photo ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(photo.nama.ifBlank { photo.id }, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    val sizeKb = (photo.ukuranBytes / 1024).coerceAtLeast(0)
                                    Text("${photo.mimeType} • ${sizeKb} KB • ID: ${photo.id.take(18)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (canControlAutomation) {
                                    OutlinedButton(onClick = { removeStockPhoto(photo.id) }) { Text("Hapus") }
                                }
                            }
                        }
                    }
                }
                if (!canControlAutomation) {
                    Text("Izin kontrol otomasi diperlukan untuk menambah/menghapus stok foto.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Daftar Preset", fontWeight = FontWeight.SemiBold)
                        Text("Filter, preview jadwal, health, dan aksi massal.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (canControlAutomation) {
                        TextButton(onClick = {
                            val changed = presets.map { it.copy(aktif = true) }
                            persist(changed)
                            status = "Semua preset diaktifkan."
                        }) { Text("Aktifkan Semua") }
                        TextButton(onClick = {
                            val changed = presets.map { it.copy(aktif = false) }
                            persist(changed)
                            status = "Semua preset dinonaktifkan."
                        }) { Text("Nonaktifkan") }
                    }
                }
                OutlinedTextField(
                    value = presetQuery,
                    onValueChange = { presetQuery = it },
                    label = { Text("Cari nama atau tipe preset") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("semua" to "Semua", "aktif" to "Aktif", "nonaktif" to "Nonaktif").forEach { (value, label) ->
                        FilterChip(selected = presetFilter == value, onClick = { presetFilter = value }, label = { Text(label) })
                    }
                }
                if (canControlAutomation) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val exporter = AppServices.textExporter
                            if (exporter == null) {
                                status = "Ekspor preset tidak tersedia pada runtime ini."
                            } else {
                                val saved = exporter("preset-$profileId-${todayIso()}.json", id.tetuko.core.domain.model.PresetCodec.encode(presets))
                                status = if (saved) "${presets.size} preset diekspor." else "Ekspor preset dibatalkan."
                            }
                        }) { Text("Ekspor Preset") }
                        OutlinedButton(onClick = {
                            val reader = AppServices.automationCloudReader
                            val writer = AppServices.automationCloudWriter
                            if (reader == null || writer == null) {
                                status = "Cloud automation tidak dikonfigurasi; preset tetap lokal perangkat."
                                return@OutlinedButton
                            }
                            val accountId = appState?.currentAccountId?.takeIf { it.isNotBlank() } ?: appState?.appAccountUsername?.takeIf { it.isNotBlank() } ?: "local"
                            val nowMs = Clock.System.now().toEpochMilliseconds()
                            val localUpdated = presets.maxOfOrNull { it.diperbarui } ?: nowMs
                            val remoteRaw = reader(accountId, profileId)
                            val remoteEnvelope = remoteRaw?.let { raw -> runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() }
                            val remoteUpdated = remoteEnvelope?.get("updatedAtMs")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                            val remotePresetJson = remoteEnvelope?.get("presetJson")?.jsonPrimitive?.contentOrNull
                            val remotePresets = remotePresetJson?.let { id.tetuko.core.domain.model.PresetCodec.decode(it) }.orEmpty()
                            val remoteTombstones = remoteEnvelope?.get("tombstones")?.jsonArray
                                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { id -> id.isNotBlank() } }
                                ?.toSet().orEmpty()
                            val tombstones = presetTombstones + remoteTombstones
                            val localById = presets.associateBy { it.id }
                            val remoteById = remotePresets.associateBy { it.id }
                            val mergedPresets = ((localById.keys + remoteById.keys).distinct())
                                .filterNot { it in tombstones }
                                .mapNotNull { id ->
                                    val local = localById[id]
                                    val remote = remoteById[id]
                                    when {
                                        local != null && remote == null -> local
                                        remote != null && local == null -> remote
                                        local != null && remote != null -> if (local.diperbarui >= remote.diperbarui) local else remote
                                        else -> null
                                    }
                                }
                            val envelope = buildJsonObject {
                                put("schemaVersion", 1)
                                put("accountId", accountId)
                                put("profileId", profileId)
                                put("updatedAtMs", maxOf(localUpdated, remoteUpdated, nowMs))
                                put("deviceId", "kmp-desktop")
                                put("presetJson", id.tetuko.core.domain.model.PresetCodec.encode(mergedPresets))
                                put("tombstones", buildJsonArray { tombstones.forEach { add(it) } })
                            }.toString()
                            val saved = writer(accountId, profileId, envelope)
                            if (saved) {
                                persist(mergedPresets)
                                status = if (remoteRaw.isNullOrBlank()) "Preset dipublikasikan ke cloud metadata." else "Preset disinkronkan dengan envelope cloud (waktu terbaru diprioritaskan)."
                            } else {
                                status = "Sinkronisasi cloud gagal; data lokal tetap aman."
                            }
                        }) { Text("Sinkron Cloud") }
                        OutlinedButton(onClick = {
                            if (!AppServices.hasFilePicker) {
                                status = "Pemilih berkas preset tidak tersedia."
                                return@OutlinedButton
                            }
                            AppServices.requestFilePicker("Pilih preset JSON", listOf("json")) { picked ->
                                if (picked == null) return@requestFilePicker
                                if (picked.bytes.size > 2_000_000) { status = "Berkas preset terlalu besar (maks. 2 MB)."; return@requestFilePicker }
                                val raw = runCatching { picked.bytes.decodeToString() }.getOrElse { "" }
                                val imported = id.tetuko.core.domain.model.PresetCodec.decode(raw)
                                if (imported.isEmpty()) { status = "JSON preset kosong atau tidak valid."; return@requestFilePicker }
                                val unique = imported.take(100).distinctBy { it.id.ifBlank { it.nama.lowercase() } }
                                persist(unique)
                                status = "${unique.size} preset diimpor dari ${picked.name}."
                            }
                        }) { Text("Impor Preset") }
                    }
                }
                if (presets.isEmpty()) {
                    Text("Belum ada preset. Tambahkan preset untuk menyiapkan otomasi presensi.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val filteredPresets = presets.filter { preset ->
                        val textMatch = presetQuery.trim().isBlank() || preset.nama.contains(presetQuery.trim(), ignoreCase = true) || preset.tipe.contains(presetQuery.trim(), ignoreCase = true)
                        val filterMatch = when (presetFilter) { "aktif" -> preset.aktif; "nonaktif" -> !preset.aktif; else -> true }
                        textMatch && filterMatch
                    }
                    if (filteredPresets.isEmpty()) {
                        Text("Tidak ada preset cocok dengan filter.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filteredPresets, key = { it.id }) { p ->
                            val readiness = automationPresetReadiness(profileId, profile, p, stockPhotos)
                            PresetRow(
                                preset = p,
                                nextRun = automationNextRunPreview(p),
                                readiness = readiness.first,
                                readinessOk = readiness.second,
                                onToggle = { checked ->
                                    if (!canControlAutomation) { status = "Tidak memiliki izin untuk mengubah status preset."; return@PresetRow }
                                    val now = Clock.System.now().toEpochMilliseconds()
                                    persist(presets.map { if (it.id == p.id) it.copy(aktif = checked, diperbarui = now) else it })
                                    appendLog(
                                        p,
                                        "INFO",
                                        if (checked) {
                                            "Preset diaktifkan. Scheduler otomatis akan mengevaluasi jadwal dan readiness sebelum eksekusi."
                                        } else {
                                            "Preset dinonaktifkan."
                                        },
                                    )
                                },
                                onEdit = {
                                    if (!canControlAutomation) { status = "Tidak memiliki izin untuk mengubah preset."; return@PresetRow }
                                    editing = p; creating = false
                                },
                                onDelete = {
                                    if (!canControlAutomation) { status = "Tidak memiliki izin untuk menghapus preset."; return@PresetRow }
                                    presetTombstones = presetTombstones + p.id
                                    persist(presets.filterNot { it.id == p.id })
                                    appendLog(p, "INFO", "Preset dihapus.")
                                    status = "Preset dihapus."
                                },
                                onManualLog = {
                                    val runner = AppServices.manualAutomationRunner
                                    if (runner == null) {
                                        appendLog(p, "SKIPPED", "Eksekusi manual belum tersedia pada runtime ini.")
                                        status = "Eksekusi manual tidak tersedia pada runtime ini."
                                    } else {
                                        val result = runCatching { runner(profileId, p) }.getOrElse {
                                            id.tetuko.core.domain.model.AutomationManualResult("FAILED", it.message ?: "Eksekusi manual gagal.")
                                        }
                                        appendLog(p, result.status, result.message, "manual")
                                        status = when (result.status) {
                                            "SUCCESS" -> "Eksekusi manual preset '${p.nama}' berhasil."
                                            "SKIPPED" -> "Eksekusi manual dilewati: ${result.message}"
                                            else -> "Eksekusi manual gagal: ${result.message}"
                                        }
                                    }
                                },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    appendLog(p, if (readiness.second) "INFO" else "SKIPPED", "Dry-run: ${readiness.first}. Tidak ada request presensi dikirim.", "dry-run")
                                    status = "Dry-run '${p.nama}': ${readiness.first}."
                                }) { Text("Dry-run") }
                                if (p.stockFotoIds.isNotEmpty()) {
                                    TextButton(onClick = { resetPresetPhotoQueue(p.id, p.nama) }) { Text("Reset Queue") }
                                }
                            }
                            val latestLog = logs.firstOrNull { it.presetId == p.id }
                            latestLog?.let { log ->
                                Text(
                                    "Eksekusi terakhir: ${log.status} • ${log.pesan}",
                                    fontSize = 11.sp,
                                    color = when (log.status) {
                                        "SUCCESS" -> androidx.compose.ui.graphics.Color(0xFF166534)
                                        "FAILED" -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val queue = id.tetuko.core.domain.model.PhotoQueueCodec.decode(AppServices.photoQueueReader?.invoke(profileId)).queues[p.id].orEmpty()
                            if (p.stockFotoIds.isNotEmpty()) {
                                Text("Antrian foto tersisa: ${queue.size}/${p.stockFotoIds.distinct().size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Log Otomasi (Lokal)", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (logs.isNotEmpty()) {
                        TextButton(onClick = {
                            if (!canControlAutomation) { status = "Tidak memiliki izin untuk menghapus log otomasi."; return@TextButton }
                            persistLogs(emptyList()); status = "Log otomasi dibersihkan."
                        }) { Text("Bersihkan") }
                    }
                }
                if (logs.isEmpty()) {
                    Text("Belum ada log. Gunakan \"Catat Uji Manual\" atau \"Jalankan Sekarang\" pada preset untuk mencatat aktivitas.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(logs.take(60)) { log ->
                            val whenText = remember(log.waktu) {
                                val dt = kotlinx.datetime.Instant.fromEpochMilliseconds(log.waktu)
                                    .toLocalDateTime(TimeZone.of("Asia/Jakarta"))
                                "%02d:%02d:%02d %02d-%02d-%04d".format(dt.hour, dt.minute, dt.second, dt.dayOfMonth, dt.monthNumber, dt.year)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "[${log.status}] ${log.presetNama.ifBlank { "Umum" }} • $whenText",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(log.pesan, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetEditorCard(
    initial: id.tetuko.core.domain.model.AutomationPreset?,
    availableStockPhotos: List<id.tetuko.core.domain.model.AutomationStockPhoto> = emptyList(),
    onCancel: () -> Unit,
    onSave: (id.tetuko.core.domain.model.AutomationPreset) -> Unit,
) {
    var nama by remember(initial) { mutableStateOf(initial?.nama ?: "") }
    var tipe by remember(initial) { mutableStateOf(initial?.tipe ?: "datang") }
    var jamMulai by remember(initial) { mutableStateOf(initial?.jamMulai ?: "07:00") }
    var jamSelesai by remember(initial) { mutableStateOf(initial?.jamSelesai ?: "09:00") }
    var workcodeId by remember(initial) { mutableStateOf(initial?.workcodeId ?: "") }
    var lokasiIdsText by remember(initial) { mutableStateOf(initial?.lokasiIds?.joinToString(", ") ?: "") }
    var selectedStockIds by remember(initial) { mutableStateOf(initial?.stockFotoIds?.toSet() ?: emptySet()) }
    var aktif by remember(initial) { mutableStateOf(initial?.aktif ?: false) }
    var hariAktif by remember(initial) { mutableStateOf(initial?.hariAktif?.toSet() ?: setOf(1, 2, 3, 4, 5)) }
    var error by remember(initial) { mutableStateOf<String?>(null) }

    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (initial == null) "Preset Baru" else "Edit Preset", fontWeight = FontWeight.Bold)
            OutlinedTextField(nama, { nama = it }, label = { Text("Nama preset") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Tipe", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("datang" to "Datang", "pulang" to "Pulang", "custom" to "Custom").forEach { (v, label) ->
                    FilterChip(selected = tipe == v, onClick = { tipe = v }, label = { Text(label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField(jamMulai, { jamMulai = it }, label = "Jam mulai", modifier = Modifier.weight(1f))
                TimeField(jamSelesai, { jamSelesai = it }, label = "Jam selesai", modifier = Modifier.weight(1f))
            }
            Text("Hari aktif", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            HorizontalScrollRow(modifier = Modifier.fillMaxWidth(), itemSpacing = 6) {
                val days = listOf(1 to "Sen", 2 to "Sel", 3 to "Rab", 4 to "Kam", 5 to "Jum", 6 to "Sab", 0 to "Min")
                days.forEach { (d, label) ->
                    FilterChip(
                        selected = d in hariAktif,
                        onClick = { hariAktif = if (d in hariAktif) hariAktif - d else hariAktif + d },
                        label = { Text(label) },
                    )
                }
            }
            OutlinedTextField(workcodeId, { workcodeId = it }, label = { Text("Work code ID (opsional, BKD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(lokasiIdsText, { lokasiIdsText = it }, label = { Text("ID lokasi pool (opsional, pisahkan koma)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Stok foto untuk otomasi Ponorogo", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (availableStockPhotos.isEmpty()) {
                Text("Belum ada stok foto lokal. Tambahkan stok foto terlebih dahulu jika preset Ponorogo memerlukan lampiran.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    availableStockPhotos.forEach { photo ->
                        val checked = photo.id in selectedStockIds
                        Row(
                            Modifier.fillMaxWidth().clickable { selectedStockIds = if (checked) selectedStockIds - photo.id else selectedStockIds + photo.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { on -> selectedStockIds = if (on) selectedStockIds + photo.id else selectedStockIds - photo.id })
                            Column(Modifier.weight(1f)) {
                                Text(photo.nama.ifBlank { photo.id }, fontSize = 12.sp)
                                Text("${(photo.ukuranBytes / 1024).coerceAtLeast(0)} KB", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Aktifkan preset")
                    Text("Scheduler otomatis mengevaluasi preset aktif sesuai jadwal dan melaporkan readiness/prasyarat sebelum eksekusi.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = aktif, onCheckedChange = { aktif = it })
            }
            error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val n = nama.trim()
                    if (n.isBlank()) { error = "Nama preset wajib diisi."; return@Button }
                    if (!Regex("^\\d{1,2}:\\d{2}$").matches(jamMulai.trim()) || !Regex("^\\d{1,2}:\\d{2}$").matches(jamSelesai.trim())) {
                        error = "Format jam harus HH:mm."; return@Button
                    }
                    if (hariAktif.isEmpty()) { error = "Pilih minimal satu hari aktif."; return@Button }
                    val lokasiIds = lokasiIdsText.split(',').map { it.trim() }.filter { it.isNotBlank() }
                    onSave(
                        (initial ?: id.tetuko.core.domain.model.AutomationPreset(id = "", nama = n)).copy(
                            nama = n,
                            tipe = tipe,
                            jamMulai = jamMulai.trim(),
                            jamSelesai = jamSelesai.trim(),
                            workcodeId = workcodeId.trim(),
                            lokasiIds = lokasiIds,
                            stockFotoIds = selectedStockIds.toList(),
                            hariAktif = hariAktif.sorted(),
                            aktif = aktif,
                        )
                    )
                }) { Text("Simpan") }
                OutlinedButton(onClick = onCancel) { Text("Batal") }
            }
        }
    }
}

@Composable
private fun PresetRow(
    preset: id.tetuko.core.domain.model.AutomationPreset,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onManualLog: () -> Unit = {},
    nextRun: String? = null,
    readiness: String? = null,
    readinessOk: Boolean = true,
) {
    val dayLabel = listOf(1 to "Sen", 2 to "Sel", 3 to "Rab", 4 to "Kam", 5 to "Jum", 6 to "Sab", 0 to "Min")
        .filter { it.first in preset.hariAktif }.joinToString(",") { it.second }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(preset.nama.ifBlank { "(tanpa nama)" }, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text("${preset.tipe} • ${preset.jamMulai}–${preset.jamSelesai} • ${dayLabel.ifBlank { "—" }}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val meta = listOfNotNull(
                    preset.workcodeId.takeIf { it.isNotBlank() }?.let { "WC: $it" },
                    if (preset.lokasiIds.isNotEmpty()) "Lokasi: ${preset.lokasiIds.size}" else null,
                    if (preset.stockFotoIds.isNotEmpty()) "Stok foto: ${preset.stockFotoIds.size}" else null,
                ).joinToString(" • ")
                if (meta.isNotBlank()) Text(meta, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (preset.aktif) "Status: aktif (scheduler otomatis)" else "Status: nonaktif",
                    fontSize = 11.sp,
                    color = if (preset.aktif) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (preset.aktif && nextRun != null) {
                    Text("Jadwal berikutnya: $nextRun WIB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (readiness != null) {
                    Text(
                        "Readiness: $readiness",
                        fontSize = 11.sp,
                        color = if (readinessOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(checked = preset.aktif, onCheckedChange = onToggle)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onManualLog) { Text(if (AppServices.manualAutomationRunner != null) "Jalankan Sekarang" else "Uji/Log") }
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun automationNextRunPreview(preset: id.tetuko.core.domain.model.AutomationPreset): String? {
    if (!preset.aktif || preset.hariAktif.isEmpty()) return null
    val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
    val zone = kotlinx.datetime.TimeZone.of("Asia/Jakarta")
    var dt = kotlinx.datetime.Instant.fromEpochMilliseconds(now).toLocalDateTime(zone)
    val startMinute = preset.jamMulai.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
    val currentMinute = dt.hour * 60 + dt.minute
    for (offset in 0..8) {
        val candidateDay = dt.date.plus(kotlinx.datetime.DatePeriod(days = offset))
        val presetDay = candidateDay.dayOfWeek.ordinal + 1
        val normalizedDay = if (presetDay == 7) 0 else presetDay
        if (normalizedDay !in preset.hariAktif) continue
        if (offset == 0 && currentMinute >= startMinute) continue
        return "${candidateDay} ${preset.jamMulai}"
    }
    return null
}

private fun automationPresetReadiness(profileId: String, profile: ServerProfile, preset: id.tetuko.core.domain.model.AutomationPreset, stockPhotos: List<id.tetuko.core.domain.model.AutomationStockPhoto>): Pair<String, Boolean> {
    val issues = mutableListOf<String>()
    if (!AppServices.hasActiveSession) issues.add("sesi")
    if (AppServices.activeProfile?.id != profile.id) issues.add("profil aktif")
    if (profile.requiresWorkCode && preset.workcodeId.isBlank()) issues.add("workcode")
    if (profile.requiresPhotoPreset) {
        val candidates = if (preset.stockFotoIds.isNotEmpty()) stockPhotos.filter { it.id in preset.stockFotoIds } else stockPhotos
        if (candidates.isEmpty()) issues.add("stok foto")
    }
    return if (issues.isEmpty()) "Siap" to true else "Butuh ${issues.joinToString(", ")}" to false
}

private fun loadAutomationStockPhotos(profileId: String): List<id.tetuko.core.domain.model.AutomationStockPhoto> =
    id.tetuko.core.domain.model.StockPhotoCodec.decode(AppServices.stockPhotoReader?.invoke(profileId))

private fun saveAutomationStockPhotos(profileId: String, photos: List<id.tetuko.core.domain.model.AutomationStockPhoto>): Boolean {
    val json = runCatching { id.tetuko.core.domain.model.StockPhotoCodec.encode(photos) }.getOrNull() ?: return false
    val writer = AppServices.stockPhotoWriter ?: return false
    return runCatching { writer(profileId, json) }.getOrDefault(false)
}

private fun loadAutomationPresets(profileId: String): List<id.tetuko.core.domain.model.AutomationPreset> =
    id.tetuko.core.domain.model.PresetCodec.decode(AppServices.presetReader?.invoke(profileId))

private fun saveAutomationPresets(profileId: String, presets: List<id.tetuko.core.domain.model.AutomationPreset>): Boolean {
    val json = id.tetuko.core.domain.model.PresetCodec.encode(presets)
    val writer = AppServices.presetWriter
    return if (writer != null) writer(profileId, json) else false
}

private fun loadAutomationLogs(profileId: String): List<id.tetuko.core.domain.model.AutomationLogEntry> =
    id.tetuko.core.domain.model.AutomationLogCodec.decode(AppServices.automationLogReader?.invoke(profileId))

private fun saveAutomationLogs(profileId: String, logs: List<id.tetuko.core.domain.model.AutomationLogEntry>): Boolean {
    val json = id.tetuko.core.domain.model.AutomationLogCodec.encode(logs)
    val writer = AppServices.automationLogWriter
    return if (writer != null) writer(profileId, json) else false
}

private fun todayLocalDateWib(): LocalDate =
    Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Jakarta")).date

private fun urlEncodeComponent(value: String): String {
    val bytes = value.encodeToByteArray()
    val out = StringBuilder(bytes.size)
    for (b in bytes) {
        val i = b.toInt() and 0xFF
        val c = i.toChar()
        val unreserved = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '_' || c == '.' || c == '~'
        if (unreserved) {
            out.append(c)
        } else {
            out.append('%')
            out.append("0123456789ABCDEF"[i ushr 4])
            out.append("0123456789ABCDEF"[i and 0x0F])
        }
    }
    return out.toString()
}

@OptIn(ExperimentalEncodingApi::class)
private fun photoModelFromLampiran(lamp: String): Any? {
    val v = lamp.trim()
    if (v.isBlank() || v == "no_image.png" || v.contains("no_image")) return null
    return when {
        v.startsWith("/9j/") || v.startsWith("iVBOR") -> runCatching { Base64.decode(v) }.getOrNull()
        v.startsWith("http://") || v.startsWith("https://") -> v
        else -> "${AppServices.rpcBaseUrl.trimEnd('/')}/api/proxy-image?path=${urlEncodeComponent(v)}"
    }
}

private fun extractPhotoFromLogDetail(res: JsonElement): Pair<Any, String>? {
    val obj = res as? JsonObject ?: return null
    val data = obj["data"] as? JsonArray ?: return null
    for (i in data.size - 1 downTo 0) {
        val item = data[i] as? JsonObject ?: continue
        val lamp = (item["lampiran"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: (item["foto"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val model = photoModelFromLampiran(lamp) ?: continue
        val jam = (item["jam"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val tanggal = (item["tanggal"] as? JsonPrimitive)?.contentOrNull
            ?: (item["tgl"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val meta = listOfNotNull(tanggal.takeIf { it.isNotBlank() }, jam.takeIf { it.isNotBlank() }).joinToString(" • ")
        return model to meta
    }
    return null
}

private suspend fun fetchLastPresensiPhoto(idPegawai: String): Pair<Any, String>? {
    if (idPegawai.isBlank()) return null
    val today = todayLocalDateWib()
    // Phase 1: last 14 calendar days, weekdays only (Mon-Fri)
    for (offset in 0 until 14) {
        val dt = today.minus(DatePeriod(days = offset))
        val dow = dt.dayOfWeek
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) continue
        val res = runCatching {
            AppServices.ponorogoClient.send("/logActivity/log_detail", buildJsonObject {
                put("id_pegawai", idPegawai)
                put("tanggal", dt.toString())
            })
        }.getOrNull() ?: continue
        val found = extractPhotoFromLogDetail(res)
        if (found != null) return found
    }
    // Phase 2: sample around Wednesday of each month, 1-6 months back
    for (mo in 1..6) {
        var base = LocalDate(today.year, today.monthNumber, 15).minus(DatePeriod(months = mo))
        // adjust toward Wednesday (value 3 in java.time; kotlinx DayOfWeek: MONDAY=1..SUNDAY=7)
        val target = DayOfWeek.WEDNESDAY
        // move base to nearest Wednesday (simple search in small window)
        var best = base
        var bestDiff = 8
        for (delta in -3..3) {
            val cand = base.plus(DatePeriod(days = delta))
            if (cand.dayOfWeek == target) { best = cand; bestDiff = 0; break }
            val diff = kotlin.math.abs(cand.dayOfWeek.ordinal - target.ordinal)
            if (diff < bestDiff) { bestDiff = diff; best = cand }
        }
        base = best
        val res = runCatching {
            AppServices.ponorogoClient.send("/logActivity/log_detail", buildJsonObject {
                put("id_pegawai", idPegawai)
                put("tanggal", base.toString())
            })
        }.getOrNull() ?: continue
        val found = extractPhotoFromLogDetail(res)
        if (found != null) return found
    }
    return null
}

@Composable
fun DatabaseScreen(profile: ServerProfile, page: PageDef) {
    val permissions = LocalAppPermissions.current
    val canSearchDatabase = permissions.role == id.tetuko.core.domain.model.AppRole.ADMIN ||
        permissions.allowSearchDatabase ||
        permissions.allowSearchPegawai ||
        permissions.canUseFeature("search_database")
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pegawai by remember { mutableStateOf<id.tetuko.app.data.RefTable?>(null) }
    var instansi by remember { mutableStateOf<id.tetuko.app.data.RefTable?>(null) }
    var opd by remember { mutableStateOf<id.tetuko.app.data.RefTable?>(null) }
    var query by remember { mutableStateOf("") }
    var instansiFilter by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) } // 0 pegawai, 1 instansi, 2 opd
    var selectedIdx by remember { mutableStateOf<Int?>(null) }
    var selectedRow by remember { mutableStateOf<Map<String, String>?>(null) }
    var biodataLoading by remember { mutableStateOf(false) }
    var biodataError by remember { mutableStateOf<String?>(null) }
    var biodata by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    var biodataStoreReady by remember { mutableStateOf(id.tetuko.app.data.BiodataStore.getCached() != null) }
    var photoLoading by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf<String?>(null) }
    var photoModel by remember { mutableStateOf<Any?>(null) }
    var photoMeta by remember { mutableStateOf<String?>(null) } // "jam • tanggal"
    var photoViewerModel by remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val p = runCatching { id.tetuko.app.data.RefDataLoader.loadTable("files/pegawai.json") }.getOrNull()
                val i = runCatching { id.tetuko.app.data.RefDataLoader.loadTable("files/instansi.json") }.getOrNull()
                val o = runCatching { id.tetuko.app.data.RefDataLoader.loadTable("files/opd.json") }.getOrNull()
                pegawai = p; instansi = i; opd = o
            }
            if (pegawai == null && instansi == null && opd == null) {
                error = "Data referensi tidak dapat dimuat."
            }
        } catch (e: Exception) {
            error = "Gagal memuat database: ${e.message}"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(canSearchDatabase) {
        if (!canSearchDatabase) {
            if (query.isNotBlank()) query = ""
            if (instansiFilter.isNotBlank()) instansiFilter = ""
        }
    }

    val pegawaiFiltered by remember(pegawai, query, instansiFilter, canSearchDatabase) {
        derivedStateOf {
            val t = pegawai ?: return@derivedStateOf emptyList<Int>()
            val q = if (canSearchDatabase) query.trim().lowercase() else ""
            val f = if (canSearchDatabase) instansiFilter.trim().lowercase() else ""
            if (q.isEmpty() && f.isEmpty()) return@derivedStateOf (0 until minOf(t.size, 200)).toList()
            val out = ArrayList<Int>(200)
            val nipI = t.keys.indexOf("nip")
            val namaI = t.keys.indexOf("nama")
            val idI = t.keys.indexOf("id")
            val searchIdxs = listOf(nipI, namaI, idI).filter { it >= 0 }
            for (i in 0 until t.size) {
                val instVal = t.cell(i, "instansi").lowercase()
                if (f.isNotEmpty() && !instVal.contains(f)) continue
                if (q.isNotEmpty()) {
                    var hit = false
                    for (ki in searchIdxs) {
                        if (t.cell(i, t.keys[ki]).lowercase().contains(q)) { hit = true; break }
                    }
                    if (!hit) continue
                }
                out.add(i)
                if (out.size >= 300) break
            }
            out
        }
    }

    LaunchedEffect(selectedRow?.get("nip")) {
        val nip = selectedRow?.get("nip").orEmpty().trim()
        biodata = null
        biodataError = null
        if (nip.isBlank()) { biodataLoading = false; return@LaunchedEffect }
        biodataLoading = true
        try {
            val store = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                id.tetuko.app.data.BiodataStore.load()
            }
            biodataStoreReady = store != null
            biodata = store?.getByNip(nip)
            if (store == null) biodataError = "Data biodata tidak dapat dimuat."
        } catch (e: Exception) {
            biodataError = "Gagal memuat biodata: ${e.message}"
        } finally {
            biodataLoading = false
        }
    }

    LaunchedEffect(selectedRow?.get("id"), profile.id, profile.mode) {
        val idPegawai = selectedRow?.get("id").orEmpty().trim()
        photoLoading = false
        photoError = null
        photoModel = null
        photoMeta = null
        if (selectedRow == null) return@LaunchedEffect
        if (idPegawai.isBlank()) {
            photoError = "ID pegawai tidak tersedia, foto presensi tidak dapat dicari."
            return@LaunchedEffect
        }
        val canTryPhoto = profile.mode == AttendanceMode.PONOROGO_ABSENSI
        if (!canTryPhoto) {
            photoError = "Foto presensi terakhir hanya tersedia pada konteks profil Ponorogo."
            return@LaunchedEffect
        }
        photoLoading = true
        try {
            val found = fetchLastPresensiPhoto(idPegawai)
            if (found != null) {
                photoModel = found.first
                photoMeta = found.second.ifBlank { null }
            } else {
                photoError = "Foto presensi terakhir tidak ditemukan (atau tidak tersedia pada periode pencarian)."
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            photoError = "Gagal memuat foto presensi: endpoint atau lampiran tidak valid."
        } finally {
            photoLoading = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Database Pegawai", fontWeight = FontWeight.Bold)
                Text(
                    "Pencarian data pegawai, instansi, dan OPD dari database referensi perangkat. Detail biodata ditampilkan bila tersedia; foto presensi terakhir dimuat saat endpoint profil mengembalikan lampiran valid.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0; selectedIdx = null; selectedRow = null }, text = { Text("Pegawai (${pegawai?.size ?: 0})") })
                    Tab(selected = tab == 1, onClick = { tab = 1; selectedIdx = null; selectedRow = null }, text = { Text("Instansi (${instansi?.size ?: 0})") })
                    Tab(selected = tab == 2, onClick = { tab = 2; selectedIdx = null; selectedRow = null }, text = { Text("OPD (${opd?.size ?: 0})") })
                }
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Memuat database…", fontSize = 12.sp) }
                    error != null -> Text(error!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    else -> {
                        when (tab) {
                            0 -> {
                                if (!canSearchDatabase) {
                                    Text(
                                        "Pencarian database dibatasi oleh izin akun. Daftar ditampilkan dalam mode terbatas.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedTextField(
                                    query,
                                    { if (canSearchDatabase) { query = it; selectedIdx = null; selectedRow = null } },
                                    label = { Text("Cari NIP, Nama, atau ID Pegawai") },
                                    singleLine = true,
                                    enabled = canSearchDatabase,
                                    readOnly = !canSearchDatabase,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    instansiFilter,
                                    { if (canSearchDatabase) { instansiFilter = it; selectedIdx = null; selectedRow = null } },
                                    label = { Text("Filter OPD/Instansi (opsional)") },
                                    singleLine = true,
                                    enabled = canSearchDatabase,
                                    readOnly = !canSearchDatabase,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("Menampilkan ${pegawaiFiltered.size} hasil", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    LazyColumn(Modifier.weight(1f).heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        items(pegawaiFiltered.size) { i ->
                                            val idx = pegawaiFiltered[i]
                                            val t = pegawai!!
                                            val nip = t.cell(idx, "nip")
                                            val nama = t.cell(idx, "nama")
                                            val jab = t.cell(idx, "nama_jabatan").ifBlank { t.cell(idx, "jabatan") }
                                            val inst = t.cell(idx, "instansi")
                                            val sel = selectedIdx == idx
                                            Column(
                                                Modifier.fillMaxWidth()
                                                    .clickable {
                                                        selectedIdx = idx
                                                        selectedRow = t.rowObject(idx)
                                                        biodata = null
                                                        biodataError = null
                                                        photoLoading = false
                                                        photoError = null
                                                        photoModel = null
                                                        photoMeta = null
                                                    }
                                                    .background(
                                                        if (sel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else androidx.compose.ui.graphics.Color.Transparent,
                                                        RoundedCornerShape(8.dp)
                                                    )
                                                    .padding(8.dp)
                                            ) {
                                                Text(nama.ifBlank { "(tanpa nama)" }, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium)
                                                val sub = listOfNotNull(
                                                    nip.takeIf { it.isNotBlank() }?.let { "NIP: $it" },
                                                    jab.takeIf { it.isNotBlank() },
                                                ).joinToString(" • ")
                                                if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                if (inst.isNotBlank()) Text(inst, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                        }
                                    }
                                    selectedRow?.let { row ->
                                        Card(Modifier.width(360.dp).heightIn(max = 480.dp), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
                                            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(verticalAlignment = Alignment.Top) {
                                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                        Text(row["nama"].orEmpty().ifBlank { "Detail Pegawai" }, fontWeight = FontWeight.Bold)
                                                        val idLine = listOfNotNull(
                                                            row["nip"]?.takeIf { it.isNotBlank() }?.let { "NIP: $it" },
                                                            row["id"]?.takeIf { it.isNotBlank() }?.let { "ID: $it" },
                                                        ).joinToString(" • ")
                                                        if (idLine.isNotBlank()) Text(idLine, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    TextButton(onClick = {
                                                        selectedIdx = null
                                                        selectedRow = null
                                                        biodata = null
                                                        biodataError = null
                                                        photoLoading = false
                                                        photoError = null
                                                        photoModel = null
                                                        photoMeta = null
                                                    }) { Text("Tutup") }
                                                }

                                                Text("Foto Presensi Terakhir", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                                when {
                                                    photoLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                                        Spacer(Modifier.width(8.dp))
                                                        Text("Mencari foto presensi terakhir…", fontSize = 12.sp)
                                                    }
                                                    photoModel != null -> {
                                                        AsyncImage(
                                                            model = photoModel,
                                                            contentDescription = "Foto presensi terakhir pegawai",
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(180.dp)
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                                                .clickable { photoViewerModel = photoModel },
                                                            contentScale = ContentScale.Crop,
                                                        )
                                                        photoMeta?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                    }
                                                    photoError != null -> Text(photoError!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    else -> Text("Pilih pegawai untuk menampilkan detail dan mencoba memuat foto presensi.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }

                                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                                Text("Data Kepegawaian", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                                PegawaiDetailRow("Nama", row["nama"])
                                                PegawaiDetailRow("NIP", row["nip"])
                                                PegawaiDetailRow("ID", row["id"])
                                                PegawaiDetailRow("Instansi", row["instansi"])
                                                PegawaiDetailRow("Unit/UNOR", row["unor"])
                                                PegawaiDetailRow("Jabatan", row["nama_jabatan"].orEmpty().ifBlank { row["jabatan"].orEmpty() })
                                                PegawaiDetailRow("Tipe/Kelas", listOfNotNull(row["tipe_jabatan"]?.takeIf { it.isNotBlank() }, row["kelas_jabatan"]?.takeIf { it.isNotBlank() }).joinToString(" / ").ifBlank { null })
                                                PegawaiDetailRow("Status", row["status_pegawai"])
                                                PegawaiDetailRow("Lokasi/Jam Kerja", listOfNotNull(row["nama_lokasi"]?.takeIf { it.isNotBlank() }, row["jam_kerja"]?.takeIf { it.isNotBlank() }).joinToString(" • ").ifBlank { null })

                                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                                Text("Biodata", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                                when {
                                                    biodataLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(if (!biodataStoreReady) "Memuat data biodata (pertama kali)…" else "Memuat biodata…", fontSize = 12.sp)
                                                    }
                                                    biodataError != null -> Text(biodataError!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                                    biodata == null -> Text("Biodata tidak tersedia untuk NIP ini.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    else -> {
                                                        BiodataDetailContent(biodata!!)
                                                    }
                                                }
                                                Text("Foto presensi terakhir ditampilkan bila endpoint dan konteks profil mengembalikan lampiran yang valid.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                            1, 2 -> {
                                val t = if (tab == 1) instansi else opd
                                val keys = if (tab == 1) listOf("nama", "kode", "tipeSkpd") else listOf("nama", "kode")
                                OutlinedTextField(query, { query = it }, label = { Text("Cari nama/kode") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                val results = t?.search(query, keys, limit = 250).orEmpty()
                                Text("Menampilkan ${results.size} hasil", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(results.size) { i ->
                                        val row = results[i]
                                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                            Text(row["nama"].orEmpty().ifBlank { "(tanpa nama)" }, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            val sub = listOfNotNull(
                                                row["kode"]?.takeIf { it.isNotBlank() }?.let { "Kode: $it" },
                                                row["tipeSkpd"]?.takeIf { it.isNotBlank() }?.let { "Tipe: $it" },
                                            ).joinToString(" • ")
                                            if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    photoViewerModel?.let { model ->
        FullscreenImageViewer(
            model = model,
            title = "Foto presensi ${photoMeta.orEmpty()}".trim(),
            onDismiss = { photoViewerModel = null },
            onDownload = {
                when (model) {
                    is ByteArray -> model
                    is String -> AppServices.ponorogoClient.fetchImage(model)
                    else -> error("Sumber foto tidak didukung untuk diunduh.")
                }
            },
            downloadFileName = "foto-presensi.jpg",
        )
    }
}

@Composable
private fun PegawaiDetailRow(label: String, value: String?) {
    val v = value?.trim().orEmpty()
    if (v.isBlank() || v == "null") return
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, fontSize = 13.sp)
    }
}

@Composable
private fun BiodataDetailContent(bio: JsonObject) {
    fun s(key: String): String {
        val el = bio[key] ?: return ""
        return when (el) {
            is JsonPrimitive -> el.contentOrNull?.trim().orEmpty()
            is kotlinx.serialization.json.JsonNull -> ""
            else -> ""
        }
    }

    val fields = listOf(
        "Nama" to s("nama"),
        "NIK" to s("nik"),
        "NIP" to s("nip_baru").ifBlank { s("nip") },
        "TTL" to s("ttl"),
        "Jenis Kelamin" to s("jk"),
        "Agama" to s("agama"),
        "Status Perkawinan" to s("perkawinan"),
        "Alamat" to s("alamat"),
        "Email" to s("email"),
        "HP" to s("hp"),
        "SKPD" to s("skpd"),
        "Golongan" to s("gol"),
        "TMT Pangkat" to s("tmt_pangkat"),
        "Jabatan" to s("nama_jabatan"),
        "Jenis Jabatan" to s("jenis_jabatan"),
        "Status Jabatan" to s("status_jabatan"),
        "TMT Jabatan" to s("tmt_jabatan"),
        "Masa Kerja" to s("masa_kerja_keseluruhan"),
        "Pendidikan" to listOf(s("pdd_jenjang"), s("pdd_jurusan"), s("pdd_sekolah")).filter { it.isNotBlank() }.joinToString(" • "),
        "TMT Pensiun" to s("tmt_pensiun"),
    )
    fields.forEach { (k, v) -> PegawaiDetailRow(k, v) }

    val histories = listOf(
        "Riwayat Pangkat" to "riwayat_pangkat",
        "Riwayat Gaji Berkala" to "riwayat_gaji_berkala",
        "Riwayat Pendidikan" to "riwayat_pendidikan",
        "Riwayat Jabatan Struktural" to "riwayat_jabatan_struktural",
        "Riwayat Jabatan Fungsional" to "riwayat_jabatan_fungsional",
        "Riwayat Jabatan Pelaksana" to "riwayat_jabatan_pelaksana",
        "Riwayat Diklat" to "riwayat_diklat",
        "Riwayat SKP" to "riwayat_skp",
        "Data Pasangan" to "pasangan",
        "Data Anak" to "anak",
    )
    histories.forEach { (label, key) ->
        val arr = bio[key] as? JsonArray ?: return@forEach
        if (arr.isEmpty()) return@forEach
        Spacer(Modifier.height(4.dp))
        Text("$label (${arr.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        arr.forEachIndexed { idx, el ->
            val obj = el as? JsonObject ?: return@forEachIndexed
            val summary = biodataEntrySummary(obj)
            if (summary.isNotBlank()) {
                Text("${idx + 1}. $summary", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun biodataEntrySummary(obj: JsonObject): String {
    fun v(k: String): String = (obj[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    // Prefer common meaningful keys; fall back to joining a few string values.
    val preferred = listOf(
        "nama", "pangkat", "golongan", "gol", "jabatan", "nama_jabatan", "unit", "skpd",
        "jenjang", "jurusan", "sekolah", "tahun_lulus", "lulus",
        "tmt", "tmt_pangkat", "tmt_jabatan", "tanggal", "tgl", "no_sk", "nomor", "nilai",
        "status", "keterangan", "ttl", "pekerjaan", "tunjangan",
    )
    val parts = ArrayList<String>(4)
    for (k in preferred) {
        val value = v(k)
        if (value.isNotBlank() && value != "null" && !parts.contains(value)) {
            parts.add(value)
            if (parts.size >= 4) break
        }
    }
    if (parts.isEmpty()) {
        for ((_, valueEl) in obj) {
            val value = (valueEl as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (value.isNotBlank() && value != "null") {
                parts.add(value)
                if (parts.size >= 3) break
            }
        }
    }
    return parts.joinToString(" • ")
}

@Composable
fun UnorScreen(profile: ServerProfile, page: PageDef) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var unorTable by remember { mutableStateOf<id.tetuko.app.data.RefTable?>(null) }
    var lokasiTable by remember { mutableStateOf<id.tetuko.app.data.RefTable?>(null) }
    var tab by remember { mutableStateOf(0) } // 0 UNOR/hierarki, 1 Lokasi
    var query by remember { mutableStateOf("") }
    var lokasiQuery by remember { mutableStateOf("") }
    var expandedInstansi by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                unorTable = runCatching { id.tetuko.app.data.RefDataLoader.loadTable("files/unor.json") }.getOrNull()
                lokasiTable = runCatching { id.tetuko.app.data.RefDataLoader.loadTable("files/lokasi.json") }.getOrNull()
            }
            if (unorTable == null && lokasiTable == null) error = "Gagal memuat data UNOR/lokasi."
        } catch (e: Exception) {
            error = "Gagal memuat data: ${e.message}"
        } finally {
            loading = false
        }
    }

    val grouped by remember(unorTable, query) {
        derivedStateOf {
            val t = unorTable ?: return@derivedStateOf emptyList<Pair<String, List<Map<String, String>>>>()
            val q = query.trim().lowercase()
            val matchedIdx = ArrayList<Int>()
            val namaI = t.keys.indexOf("nama")
            val instNamaI = t.keys.indexOf("instansiNama")
            val kodeI = t.keys.indexOf("kode")
            val instKodeI = t.keys.indexOf("instansiKode")
            val searchIdxs = listOf(namaI, instNamaI, kodeI, instKodeI).filter { it >= 0 }
            for (i in 0 until t.size) {
                if (q.isNotEmpty()) {
                    var hit = false
                    for (ki in searchIdxs) {
                        if (t.cell(i, t.keys[ki]).lowercase().contains(q)) { hit = true; break }
                    }
                    if (!hit) continue
                }
                matchedIdx.add(i)
                if (q.isEmpty() && matchedIdx.size >= 1200) break
                if (q.isNotEmpty() && matchedIdx.size >= 500) break
            }
            val map = linkedMapOf<String, MutableList<Map<String, String>>>()
            for (i in matchedIdx) {
                val instNama = t.cell(i, "instansiNama").ifBlank { "(Tanpa Instansi)" }
                val instKode = t.cell(i, "instansiKode")
                val key = if (instKode.isNotBlank()) "$instNama||$instKode" else "$instNama||"
                map.getOrPut(key) { mutableListOf() }.add(t.rowObject(i))
            }
            map.entries.map { (key, rows) ->
                val instNama = key.substringBefore("||")
                val instKode = key.substringAfter("||")
                val label = if (instKode.isNotBlank()) "$instNama ($instKode)" else instNama
                label to rows
            }.sortedBy { it.first.lowercase() }
        }
    }

    val lokasiResults by remember(lokasiTable, lokasiQuery) {
        derivedStateOf {
            val t = lokasiTable ?: return@derivedStateOf emptyList<Map<String, String>>()
            val q = lokasiQuery.trim().lowercase()
            if (q.isEmpty()) {
                val take = minOf(t.size, 150)
                (0 until take).map { t.rowObject(it) }
            } else {
                val out = ArrayList<Map<String, String>>(150)
                val searchKeys = listOf("nama", "kode", "alamat", "kota")
                val idxs = searchKeys.mapNotNull { k -> t.keys.indexOf(k).takeIf { it >= 0 } }
                for (i in 0 until t.size) {
                    val row = t.rows[i].jsonArray
                    var hit = false
                    for (ki in idxs) {
                        if (t.cell(i, t.keys[ki]).lowercase().contains(q)) { hit = true; break }
                    }
                    if (hit) {
                        out.add(t.rowObject(i))
                        if (out.size >= 200) break
                    }
                }
                out
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Struktur Organisasi / UNOR & Lokasi", fontWeight = FontWeight.Bold)
                Text(
                    "Jelajahi hierarki UNOR per instansi dan referensi lokasi dari data terbundle. Gunakan pencarian untuk memfilter; saat pencarian aktif, kelompok yang cocok akan diperluas otomatis.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("UNOR (${unorTable?.size ?: 0})") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Lokasi (${lokasiTable?.size ?: 0})") })
                }
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Memuat data…", fontSize = 12.sp) }
                    error != null -> Text(error!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    tab == 0 -> {
                        OutlinedTextField(
                            query,
                            { query = it },
                            label = { Text("Cari unit/instansi/kode UNOR") },
                            placeholder = { Text("Contoh: nama instansi, nama unit, atau kode") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val totalUnits = grouped.sumOf { it.second.size }
                        val info = buildString {
                            append("$totalUnits unit dalam ${grouped.size} kelompok instansi")
                            if (query.isBlank() && (unorTable?.size ?: 0) > totalUnits) append(" (ditampilkan terbatas untuk performa)")
                            if (query.isNotBlank()) append(" • filter aktif")
                        }
                        Text(info, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (grouped.isEmpty()) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
                                Text(
                                    if (query.isBlank()) "Data UNOR tidak tersedia." else "Tidak ada UNOR/instansi yang cocok dengan pencarian.",
                                    modifier = Modifier.padding(12.dp),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(grouped.size) { gi ->
                                    val (instLabel, rows) = grouped[gi]
                                    val expanded = query.isNotBlank() || expandedInstansi.contains(instLabel)
                                    Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
                                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(
                                                Modifier.fillMaxWidth().clickable {
                                                    expandedInstansi = if (expandedInstansi.contains(instLabel)) expandedInstansi - instLabel else expandedInstansi + instLabel
                                                },
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(if (expanded) "▾" else "▸", fontSize = 12.sp, modifier = Modifier.width(18.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(instLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                                    Text("${rows.size} unit organisasi", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                if (query.isBlank()) {
                                                    Text(if (expanded) "Tutup" else "Buka", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                            if (expanded) {
                                                rows.forEachIndexed { idx, row ->
                                                    val nama = row["nama"].orEmpty().ifBlank { "(tanpa nama)" }
                                                    val kode = row["kode"].orEmpty()
                                                    val isLast = idx == rows.lastIndex
                                                    Row(
                                                        Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                                                        verticalAlignment = Alignment.Top,
                                                    ) {
                                                        Text(if (isLast) "└" else "├", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(14.dp))
                                                        Column(Modifier.weight(1f)) {
                                                            Text(nama, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                                            val sub = listOfNotNull(
                                                                kode.takeIf { it.isNotBlank() }?.let { "Kode: $it" },
                                                                row["instansiTipeSkpd"]?.takeIf { it.isNotBlank() }?.let { "Tipe: $it" },
                                                            ).joinToString(" • ")
                                                            if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        OutlinedTextField(
                            lokasiQuery,
                            { lokasiQuery = it },
                            label = { Text("Cari lokasi/kode/alamat/kota") },
                            placeholder = { Text("Contoh: nama lokasi, kode, alamat, atau kota") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            if (lokasiQuery.isBlank()) "Menampilkan ${lokasiResults.size} dari ${lokasiTable?.size ?: 0} lokasi"
                            else "Menampilkan ${lokasiResults.size} lokasi hasil pencarian",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (lokasiResults.isEmpty()) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
                                Text(
                                    if (lokasiQuery.isBlank()) "Data lokasi tidak tersedia." else "Tidak ada lokasi yang cocok dengan pencarian.",
                                    modifier = Modifier.padding(12.dp),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(lokasiResults.size) { i ->
                                    val row = lokasiResults[i]
                                    val nama = row["nama"].orEmpty().ifBlank { "(tanpa nama)" }
                                    val kode = row["kode"].orEmpty()
                                    val alamat = row["alamat"].orEmpty()
                                    val lat = row["latitude"].orEmpty()
                                    val lng = row["longitude"].orEmpty()
                                    val radius = row["radius"].orEmpty()
                                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Text(nama, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        val sub = listOfNotNull(
                                            kode.takeIf { it.isNotBlank() }?.let { "Kode: $it" },
                                            if (lat.isNotBlank() && lng.isNotBlank()) "Koordinat: $lat, $lng" else null,
                                            radius.takeIf { it.isNotBlank() }?.let { "Radius: ${it}m" },
                                        ).joinToString(" • ")
                                        if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (alamat.isNotBlank()) Text(alamat, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RiwayatScreen(profile: ServerProfile, page: PageDef) {
    val ctx by AppServices.serverContext.collectAsState()
    var date by remember { mutableStateOf(todayIso()) }
    var loading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun load() {
        if (ctx.apiKey.isBlank() && AppServices.serverContext.value.apiKey.isBlank()) { msg = "Sesi server belum aktif."; return }
        loading = true; msg = null
        scope.launch {
            try {
                val el = AppServices.rpcClient.callResultElement(RpcObjects.HISTORY_ABSEN, buildJsonObject { put("tgl", date); put("page",1); put("limit",100) }, AppServices.serverContext.value, profile.id)
                rows = el.asArray().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                msg = if (rows.isEmpty()) "Tidak ada data pada $date." else "Ditemukan ${rows.size} baris pada $date."
            } catch (e: Exception) { msg = "Gagal memuat: ${e.message}" } finally { loading = false }
        }
    }
    LaunchedEffect(profile.id, page.id, ctx.apiKey) { if (ctx.apiKey.isNotBlank()) load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        Card(shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Riwayat / ${page.label}", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DateField(value = date, onValueChange = { date = it }, label = "Tanggal", modifier = Modifier.width(220.dp))
                Button(onClick = { load() }, enabled = !loading) { Text(if (loading) "Memuat..." else "Muat Data") }
            }
            if (msg != null) Text(msg!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (rows.isNotEmpty()) {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(rows.take(100)) { r ->
                        val ct = r.str("checktype")
                        val waktu = r.str("waktu")
                        val nama = r.str("nama")
                        val jarak = r.str("jarak")
                        val approval = r.str("approval_text").ifBlank { r.str("approval") }
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(listOfNotNull(ct.ifBlank { null }, waktu.ifBlank { null }).joinToString(" • ").ifBlank { "(data)" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                if (nama.isNotBlank() || jarak.isNotBlank() || approval.isNotBlank()) {
                                    Text(listOfNotNull(nama.ifBlank { null }, if (jarak.isNotBlank()) "jarak $jarak" else null, approval.ifBlank { null }).joinToString(" • "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            Text("Catatan: tampilan ini memuat riwayat presensi (history_absen) sebagai data dasar. Modul ${page.label} mode/fitur khusus (produktifitas, instansi, review, dll.) akan diperdalam sesuai API terkait.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
}

@Composable
fun GenericPageScreen(profile: ServerProfile, page: PageDef) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Modul ${page.label}", fontWeight = FontWeight.SemiBold)
                Text("Struktur menu, route (${profile.id}/${page.slug}), dan kerangka UI native Compose tersedia. Integrasi fungsional penuh mengikuti implementasi Tauri.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                InfoRow("Page ID", page.id); InfoRow("Slug", page.slug); InfoRow("Profil", profile.id); InfoRow("Mode", profile.mode.name)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard("Fitur target", pageFeatureHint(page.id), Modifier.weight(1f))
            FeatureCard("Status", "Menu & navigasi: aktif\nUI shell: aktif\nIntegrasi data: bertahap per modul", Modifier.weight(1f))
        }
    }
}
