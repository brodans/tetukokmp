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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import id.tetuko.app.data.PegawaiSearchResult
import id.tetuko.app.data.PegawaiSearchRepository
import id.tetuko.app.data.RefDataLoader
import id.tetuko.app.data.RefTable
import id.tetuko.app.data.findImmediateSupervisor
import id.tetuko.app.data.PonorogoLocation
import id.tetuko.app.data.PonorogoLocationRepository
import id.tetuko.app.data.findLocationFor
import id.tetuko.app.ui.LocalAppPermissions
import id.tetuko.app.service.AppServices
import id.tetuko.app.ui.components.PageHeaderCard
import id.tetuko.app.ui.components.PegawaiSearchField
import id.tetuko.app.ui.components.PonorogoAttachmentPreview
import id.tetuko.app.ui.components.HorizontalScrollRow
import id.tetuko.app.ui.components.VerticalScrollColumn
import id.tetuko.app.ui.components.FullscreenImageViewer
import id.tetuko.app.ui.components.DropdownField
import id.tetuko.app.ui.components.DateField
import id.tetuko.core.domain.model.AppRole
import id.tetuko.core.domain.model.PageDef
import id.tetuko.core.domain.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*

@Composable
private fun rememberCanSensitiveAction(): Boolean {
    val perms = LocalAppPermissions.current
    return perms.role == AppRole.ADMIN || perms.allowSensitiveActions || perms.canUseFeature("sensitive_actions")
}

private fun todayIso(): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Jakarta"))
    return "%04d-%02d-%02d".format(now.year, now.monthNumber, now.dayOfMonth)
}

private fun daysAgoIso(days: Int): String {
    val tz = TimeZone.of("Asia/Jakarta")
    val date = Clock.System.now().toLocalDateTime(tz).date
    val d = date.minus(DatePeriod(days = days))
    return "%04d-%02d-%02d".format(d.year, d.monthNumber, d.dayOfMonth)
}

private fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
private fun JsonElement?.asArr(): JsonArray = when (this) {
    is JsonArray -> this
    is JsonObject -> (this["content"] as? JsonArray)
        ?: (this["data"] as? JsonArray)
        ?: (this["result"] as? JsonArray)
        ?: JsonArray(emptyList())
    else -> JsonArray(emptyList())
}
private fun JsonObject.str(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull
        ?: this[key]?.toString()?.trim('"')?.takeIf { it != "null" } ?: ""
private fun JsonElement?.str(key: String): String = this.asObj()?.str(key).orEmpty()
private fun JsonObject.intOf(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull ?: this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

// ── Submit Presensi (Ponorogo) ──────────────────────────────────────────────

@Composable
fun PonorogoPresensiScreen(profile: ServerProfile, page: PageDef) {
    val ctx by AppServices.serverContext.collectAsState()
    val scope = rememberCoroutineScope()
    val pon = AppServices.ponorogoProfile
    val pegawaiId = pon?.str("id_pegawai")?.ifBlank { pon?.str("id") }.orEmpty()
    val nip = pon?.str("nip").orEmpty()
    val ownPegawai = remember(pegawaiId, nip, pon?.str("nama"), pon?.str("kode_unor")) {
        PegawaiSearchResult(
            id = pegawaiId,
            nip = nip,
            nama = pon?.str("nama").orEmpty(),
            instansi = pon?.str("instansi").orEmpty(),
            kodeUnor = pon?.str("kode_unor").orEmpty(),
            kodeInstansi = pon?.str("kode_instansi").orEmpty(),
            unor = pon?.str("unor").orEmpty(),
            namaLokasi = pon?.str("nama_lokasi").orEmpty(),
            idLokasi = pon?.str("id_lokasi").orEmpty(),
            deviceId = pon?.str("emei").orEmpty(),
        )
    }
    val canSearchPegawai = LocalAppPermissions.current.canUseFeature("search_pegawai")
    var targetPegawai by remember(ownPegawai) { mutableStateOf(ownPegawai) }
    var targetLocation by remember { mutableStateOf<PonorogoLocation?>(null) }
    var targetLocationLoading by remember { mutableStateOf(false) }
    var targetLocationRequestId by remember { mutableIntStateOf(0) }
    var refreshRequestId by remember { mutableIntStateOf(0) }

    var loading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var jamKerja by remember { mutableStateOf<JsonObject?>(null) }
    var cekAbsen by remember { mutableStateOf<JsonObject?>(null) }
    var lat by remember { mutableStateOf("") }
    var lng by remember { mutableStateOf("") }
    var keterangan by remember { mutableStateOf("Presensi Reguler") }
    var photo by remember { mutableStateOf<id.tetuko.app.service.PickedFile?>(null) }
    var photoPreviewOpen by remember { mutableStateOf(false) }
    val canSensitive = rememberCanSensitiveAction()

    fun refresh() {
        if (ctx.apiKey.isBlank()) { info = "Sesi belum aktif. Silakan login server terlebih dahulu."; return }
        val targetId = if (canSearchPegawai) targetPegawai.id else ownPegawai.id
        if (targetId.isBlank()) { info = "ID pegawai tidak tersedia. Login ulang diperlukan."; return }
        val currentRequest = ++refreshRequestId
        loading = true
        info = null
        scope.launch {
            try {
                val jam = AppServices.ponorogoClient.send("/pegawai/getJamKerjaPegawai", buildJsonObject { put("id_pegawai", targetId) })
                val parsedJam = jam.asObj() ?: (jam as? JsonArray)?.firstOrNull().asObj() ?: (jam.asObj()?.get("data") as? JsonObject)
                val cek = AppServices.ponorogoClient.send("/Tupoksi/cek_absen", buildJsonObject { put("id_pegawai", targetId) })
                val parsedCek = cek.asObj() ?: (cek as? JsonArray)?.firstOrNull().asObj() ?: (cek.asObj()?.get("data") as? JsonObject)
                if (currentRequest != refreshRequestId) return@launch
                jamKerja = parsedJam
                cekAbsen = parsedCek
                if (lat.isBlank() || lng.isBlank()) {
                    val saved = AppServices.serverContext.value.lastLatLong
                    if (saved.contains(",")) {
                        lat = saved.substringBefore(",").trim()
                        lng = saved.substringAfter(",").trim()
                    }
                }
                info = "Jam kerja dan status absen berhasil dimuat untuk ${targetPegawai.nama.ifBlank { targetId }}."
            } catch (e: Exception) {
                if (currentRequest == refreshRequestId) info = "Gagal memuat data presensi: ${e.message}"
            } finally {
                if (currentRequest == refreshRequestId) loading = false
            }
        }
    }

    fun applyActivePoint(showMessage: Boolean = false) {
        val raw = AppServices.pointsReader?.invoke(profile.id)
        val active = runCatching { id.tetuko.app.service.SavedPointsCodec.decode(raw) }.getOrDefault(emptyList()).firstOrNull { it.active }
        if (active != null) {
            lat = active.latitude.toString()
            lng = active.longitude.toString()
            AppServices.updateServerContext(lastLatLong = "${active.latitude},${active.longitude}")
            if (showMessage) info = "Koordinat aktif diterapkan: ${active.name} (${active.latitude}, ${active.longitude})."
        } else if (showMessage) {
            info = "Belum ada titik absen aktif tersimpan untuk profil ini."
        }
    }

    fun selectPegawai(target: PegawaiSearchResult) {
        val currentRequest = ++targetLocationRequestId
        targetPegawai = target
        targetLocation = null
        if (target.id == ownPegawai.id) {
            targetLocationLoading = false
            applyActivePoint()
            return
        }
        lat = ""
        lng = ""
        targetLocationLoading = true
        info = null
        scope.launch {
            try {
                val locations = withContext(Dispatchers.Default) { PonorogoLocationRepository.loadTable() }
                if (currentRequest != targetLocationRequestId) return@launch
                val location = locations.findLocationFor(target)
                targetLocation = location
                location?.let {
                    lat = it.latitude.toString()
                    lng = it.longitude.toString()
                    info = "Lokasi pegawai diterapkan: ${it.nama}."
                } ?: run { info = "Lokasi target tidak ditemukan. Isi koordinat secara manual sebelum mengirim." }
            } catch (e: Exception) {
                if (currentRequest == targetLocationRequestId) info = "Gagal memuat lokasi target: ${e.message}"
            } finally {
                if (currentRequest == targetLocationRequestId) targetLocationLoading = false
            }
        }
    }

    LaunchedEffect(canSearchPegawai, ownPegawai) {
        if (!canSearchPegawai) {
            targetLocationRequestId++
            targetPegawai = ownPegawai
            targetLocation = null
            targetLocationLoading = false
        }
    }

    LaunchedEffect(profile.id, ctx.apiKey, targetPegawai.id) {
        if (targetPegawai.id == ownPegawai.id && lat.isBlank() && lng.isBlank()) applyActivePoint()
        if (ctx.apiKey.isNotBlank()) refresh()
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun submit() {
        if (!canSensitive) { info = "Akun ini tidak memiliki izin untuk mengirim presensi (aksi sensitif)."; return }
        if (ctx.apiKey.isBlank()) { info = "Sesi belum aktif. Silakan login server terlebih dahulu."; return }
        if (targetLocationLoading) { info = "Menunggu lokasi pegawai target selesai dimuat."; return }
        val target = if (canSearchPegawai) targetPegawai else ownPegawai
        val latText = lat.trim().replace(',', '.')
        val lngText = lng.trim().replace(',', '.')
        val la = latText.toDoubleOrNull()
        val lo = lngText.toDoubleOrNull()
        if (la == null || lo == null) {
            info = "Koordinat tidak valid. Isi latitude dan longitude dengan angka desimal (contoh: -7.8685, 111.4620)."
            return
        }
        if (la !in -90.0..90.0 || lo !in -180.0..180.0) {
            info = "Koordinat di luar jangkauan. Latitude harus -90..90 dan longitude harus -180..180."
            return
        }
        if (target.id.isBlank()) { info = "ID pegawai tidak tersedia. Login ulang diperlukan."; return }
        submitting = true
        info = null
        scope.launch {
            try {
                val deviceId = target.deviceId.ifBlank { ctx.imei.ifBlank { pon?.str("emai").orEmpty() } }
                val lampiran = photo?.let { Base64.encode(it.bytes) }.orEmpty()
                val payload = buildJsonObject {
                    put("id_pegawai", target.id)
                    put("nip", target.nip.ifBlank { nip })
                    put("lattitude", la.toString())
                    put("longitude", lo.toString())
                    put("latitude", la.toString())
                    put("imei", deviceId)
                    put("sim_serial", deviceId)
                    put("bedgenumber", deviceId)
                    put("keterangan", keterangan.ifBlank { "Presensi Reguler" })
                    put("work_mode", "WFO")
                    put("kode_instansi", target.kodeInstansi.ifBlank { pon?.str("kode_instansi").orEmpty() })
                    put("kode_unor", target.kodeUnor.ifBlank { target.unor }.ifBlank { pon?.str("kode_unor").orEmpty() })
                    put("id_lokasi", targetLocation?.id ?: target.idLokasi.ifBlank { pon?.str("id_lokasi").orEmpty() })
                    put("versi", "2.0.0")
                    put("lampiran", lampiran)
                }
                val res = AppServices.ponorogoClient.send("/login/absen_mobile", payload).asObj()
                val ok = (res?.get("success") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
                val msg = res?.str("message")?.ifBlank { res.str("keterangan") }.orEmpty()
                info = if (ok) "Presensi berhasil dikirim. $msg".trim() else "Presensi gagal: ${msg.ifBlank { res?.str("error") ?: "respons tidak dikenal" }}"
                if (ok) refresh()
            } catch (e: Exception) {
                info = "Gagal submit presensi: ${e.message}"
            } finally {
                submitting = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        PegawaiSearchField(
            enabled = canSearchPegawai,
            selected = if (canSearchPegawai) targetPegawai else ownPegawai,
            own = ownPegawai,
            onSelect = { selectPegawai(it) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (targetLocation != null && targetPegawai.id != ownPegawai.id) {
            InfoLine("Lokasi target", "${targetLocation?.nama} (${targetLocation?.latitude}, ${targetLocation?.longitude})")
        }
        info?.let {
            val err = it.startsWith("Gagal") || it.contains("tidak valid", true) || it.contains("di luar jangkauan", true) || it.contains("gagal", true) || it.contains("belum aktif", true) || it.contains("tidak tersedia", true)
            Card(colors = CardDefaults.cardColors(containerColor = if (err) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Text(it, Modifier.padding(12.dp), fontSize = 13.sp)
            }
        }
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Status & Jam Kerja", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { refresh() }, enabled = !loading) { Text(if (loading) "Memuat…" else "Muat Ulang") }
                }
                jamKerja?.let { j ->
                    val jamKerjaNama = j.str("jam_kerja").ifBlank { j.str("nama_shift") }
                    val hari = j.str("nama_hari").ifBlank { j.str("hari") }
                    InfoLine("Jam kerja", listOf(jamKerjaNama, hari).filter { it.isNotBlank() && it != "null" }.joinToString(" • "))
                    InfoLine("Rentang masuk", "${j.str("jam_mulai_scan_masuk").ifBlank { "—" }} - ${j.str("jam_akhir_scan_masuk").ifBlank { "—" }}")
                    InfoLine("Rentang pulang", "${j.str("jam_mulai_scan_pulang").ifBlank { "—" }} - ${j.str("jam_akhir_scan_pulang").ifBlank { "—" }}")
                    val jamMasuk = j.str("jam_masuk")
                    val jamPulang = j.str("jam_pulang")
                    if (jamMasuk.isNotBlank() || jamPulang.isNotBlank()) {
                        InfoLine("Jadwal", "${jamMasuk.ifBlank { "—" }} - ${jamPulang.ifBlank { "—" }}")
                    }
                } ?: Text("Jam kerja belum dimuat. Tekan Muat Ulang untuk mengambil data.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                cekAbsen?.let { c ->
                    val statusHari = c.str("status").ifBlank { c.str("keterangan_status") }
                    if (statusHari.isNotBlank()) InfoLine("Status", statusHari)
                    InfoLine("Absen masuk", c.str("absen_masuk").ifBlank { c.str("jam_masuk").ifBlank { "—" } })
                    InfoLine("Absen pulang", c.str("absen_pulang").ifBlank { c.str("jam_pulang").ifBlank { "—" } })
                } ?: Text("Status absen hari ini belum dimuat.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Submit Presensi", fontWeight = FontWeight.Bold)
                Text("Kirim presensi ke /login/absen_mobile. Koordinat wajib; lampiran foto dari berkas bersifat opsional (format umum: JPG, PNG, WEBP, HEIC).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, placeholder = { Text("-7.8685") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(lng, { lng = it }, label = { Text("Longitude") }, placeholder = { Text("111.4620") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
                FilledTonalButton(onClick = { applyActivePoint(showMessage = true) }, enabled = !submitting) {
                    Text("Gunakan Titik Aktif")
                }
                OutlinedTextField(keterangan, { keterangan = it }, label = { Text("Keterangan") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                val available = AppServices.requestFilePicker("Pilih foto presensi", listOf("jpg", "jpeg", "png", "webp", "heic", "heif", "tif", "tiff", "bmp", "ico", "avif", "jxl")) { picked ->
                                    if (picked != null) photo = AppServices.imageProcessor?.invoke(picked, 320, 95) ?: picked
                                }
                                if (!available) info = "Pemilih berkas tidak tersedia di platform ini."
                            },
                            enabled = !submitting && AppServices.hasFilePicker,
                        ) { Text(if (photo == null) "Pilih Foto" else "Ganti Foto") }
                        if (AppServices.hasCameraCapture) {
                            OutlinedButton(
                                onClick = {
                                    val available = AppServices.requestCameraCapture { captured ->
                                        if (captured != null) {
                                            photo = AppServices.imageProcessor?.invoke(captured, 320, 95) ?: captured
                                            info = "Foto kamera siap: ${captured.name} (${captured.bytes.size / 1024} KB)."
                                        }
                                    }
                                    if (!available) info = "Kamera tidak tersedia pada perangkat ini."
                                },
                                enabled = !submitting,
                            ) { Text("Ambil Foto") }
                        }
                    }
                    photo?.let { selected ->
                        Text(selected.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { photoPreviewOpen = true }, enabled = !submitting) { Text("Pratinjau") }
                            TextButton(onClick = { photo = null }, enabled = !submitting) { Text("Hapus") }
                        }
                    } ?: Text("Tanpa lampiran", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { submit() }, enabled = !submitting && ctx.apiKey.isNotBlank() && canSensitive) {
                    Text(if (submitting) "Mengirim…" else "Kirim Presensi")
                }
            }
        }
    }
    photo?.takeIf { photoPreviewOpen }?.let { selected ->
        FullscreenImageViewer(
            model = selected.bytes,
            title = selected.name,
            onDismiss = { photoPreviewOpen = false },
            onDownload = { selected.bytes },
            downloadFileName = selected.name,
        )
    }
}

// ── History Presensi Instansi (Ponorogo) ────────────────────────────────────

@Composable
fun PonorogoInstansiLogScreen(profile: ServerProfile, page: PageDef) {
    val permissions = LocalAppPermissions.current
    val canSearchIdentity = permissions.role == id.tetuko.core.domain.model.AppRole.ADMIN ||
        permissions.allowSearchPegawai ||
        permissions.allowSearchInstansi ||
        permissions.canUseFeature("search_pegawai") ||
        permissions.canUseFeature("search_instansi")
    val ctx by AppServices.serverContext.collectAsState()
    val scope = rememberCoroutineScope()
    val pon = AppServices.ponorogoProfile
    val ownUnor = pon?.str("kode_unor")?.ifBlank { pon?.str("kode_instansi") }.orEmpty()

    var dateStart by remember { mutableStateOf(daysAgoIso(7)) }
    var dateEnd by remember { mutableStateOf(todayIso()) }
    var unor by remember { mutableStateOf(ownUnor) }
    var nama by remember { mutableStateOf("") }
    var nip by remember { mutableStateOf("") }
    var pageNo by remember { mutableStateOf(1) }
    var pageSize by remember { mutableStateOf(25) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var totalPages by remember { mutableStateOf(1) }
    var totalElements by remember { mutableStateOf(0) }

    fun load(targetPage: Int = pageNo, size: Int = pageSize) {
        if (ctx.apiKey.isBlank()) { message = "Sesi belum aktif. Silakan login server terlebih dahulu."; return }
        val namaFilter = if (canSearchIdentity) nama else ""
        val nipFilter = if (canSearchIdentity) nip else ""
        if (!canSearchIdentity && (nama.isNotBlank() || nip.isNotBlank())) {
            nama = ""
            nip = ""
        }
        loading = true
        pageNo = targetPage
        pageSize = size
        scope.launch {
            try {
                val result = AppServices.ponorogoClient.attendanceLog(unor, dateStart, dateEnd, namaFilter, nipFilter, pageNo, pageSize)
                val obj = result.asObj()
                rows = result.asArr().mapNotNull { it as? JsonObject }
                totalPages = (obj?.intOf("totalPages") ?: obj?.intOf("total_pages") ?: 1).coerceAtLeast(1)
                totalElements = obj?.intOf("totalElements") ?: obj?.intOf("total_elements") ?: rows.size
                message = "Data dimuat."
            } catch (e: Exception) {
                message = "Gagal memuat log instansi: ${e.message}"
                rows = emptyList()
                totalElements = 0
                totalPages = 1
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(profile.id) { if (ctx.apiKey.isNotBlank()) load(1) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("History Presensi Instansi", fontWeight = FontWeight.Bold)
                Text("Filter log presensi instansi/UNOR berdasarkan rentang tanggal dan identitas pegawai.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Text("Rentang & Unit", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                HorizontalScrollRow(modifier = Modifier.fillMaxWidth(), itemSpacing = 8) {
                    DateField(dateStart, { dateStart = it }, label = "Dari", modifier = Modifier.width(170.dp))
                    DateField(dateEnd, { dateEnd = it }, label = "Sampai", modifier = Modifier.width(170.dp))
                    OutlinedTextField(unor, { unor = it }, label = { Text("Kode UNOR/Instansi") }, singleLine = true, modifier = Modifier.width(180.dp))
                }

                Text("Filter Pegawai (opsional)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (!canSearchIdentity) {
                    Text(
                        "Pencarian nama/NIP pegawai lain dibatasi oleh izin akun. Filter tanggal/UNOR tetap dapat digunakan.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalScrollRow(modifier = Modifier.fillMaxWidth(), itemSpacing = 8) {
                    OutlinedTextField(
                        nama,
                        { if (canSearchIdentity) nama = it },
                        label = { Text("Nama") },
                        singleLine = true,
                        enabled = canSearchIdentity,
                        readOnly = !canSearchIdentity,
                        modifier = Modifier.width(220.dp),
                    )
                    OutlinedTextField(
                        nip,
                        { if (canSearchIdentity) nip = it },
                        label = { Text("NIP") },
                        singleLine = true,
                        enabled = canSearchIdentity,
                        readOnly = !canSearchIdentity,
                        modifier = Modifier.width(180.dp),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Baris/halaman", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(10, 25, 50, 100).forEach { size ->
                        FilterChip(
                            selected = pageSize == size,
                            onClick = { if (!loading) load(1, size) },
                            label = { Text(size.toString(), fontSize = 12.sp) },
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { load(1, pageSize) }, enabled = !loading) { Text(if (loading) "Memuat…" else "Muat Data") }
                    OutlinedButton(onClick = { if (pageNo > 1) load(pageNo - 1) }, enabled = !loading && pageNo > 1) { Text("Sebelumnya") }
                    Text("Halaman $pageNo / ${totalPages.coerceAtLeast(1)}", fontSize = 12.sp)
                    OutlinedButton(onClick = { if (pageNo < totalPages) load(pageNo + 1) }, enabled = !loading && pageNo < totalPages) { Text("Berikutnya") }
                }

                Text(
                    "Total: $totalElements baris • Ditampilkan: ${rows.size} baris pada halaman ini",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                message?.let {
                    val isError = it.startsWith("Gagal") || it.contains("belum aktif", true)
                    Text(it, fontSize = 12.sp, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (rows.isNotEmpty()) {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.padding(vertical = 6.dp)) {
                        listOf("Nama" to 160, "NIP" to 130, "Tanggal" to 120, "Masuk" to 80, "Pulang" to 80, "Keterangan" to 220).forEach { (h, w) ->
                            Text(h, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.width(w.dp).padding(horizontal = 4.dp))
                        }
                    }
                    HorizontalDivider()
                    LazyColumn(Modifier.heightIn(max = 420.dp).width(830.dp)) {
                        items(rows) { row ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(row.str("nama").ifBlank { row.str("nama_pegawai") }, fontSize = 12.sp, modifier = Modifier.width(160.dp).padding(horizontal = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(row.str("nip"), fontSize = 12.sp, modifier = Modifier.width(130.dp).padding(horizontal = 4.dp), maxLines = 1)
                                Text(row.str("tanggal").ifBlank { row.str("tgl") }, fontSize = 12.sp, modifier = Modifier.width(120.dp).padding(horizontal = 4.dp), maxLines = 1)
                                Text(row.str("jam_masuk").ifBlank { row.str("absen_masuk") }, fontSize = 12.sp, modifier = Modifier.width(80.dp).padding(horizontal = 4.dp), maxLines = 1)
                                Text(row.str("jam_pulang").ifBlank { row.str("absen_pulang") }, fontSize = 12.sp, modifier = Modifier.width(80.dp).padding(horizontal = 4.dp), maxLines = 1)
                                Text(row.str("keterangan").ifBlank { row.str("status") }, fontSize = 12.sp, modifier = Modifier.width(220.dp).padding(horizontal = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        } else if (!loading && message != null) {
            Card(shape = RoundedCornerShape(16.dp)) {
                Text("Tidak ada data untuk filter yang dipilih.", Modifier.padding(16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Laporan (Ponorogo) ──────────────────────────────────────────────────────

@Composable
fun PonorogoLaporanScreen(profile: ServerProfile, page: PageDef) {
    val canSensitive = rememberCanSensitiveAction()
    val ctx by AppServices.serverContext.collectAsState()
    val scope = rememberCoroutineScope()
    val pon = AppServices.ponorogoProfile
    var reportType by remember { mutableStateOf("per-pegawai") }
    var format by remember { mutableStateOf("pdf") }
    var dateStart by remember { mutableStateOf(daysAgoIso(30)) }
    var dateEnd by remember { mutableStateOf(todayIso()) }
    var idu by remember { mutableStateOf(pon?.str("kode_instansi").orEmpty()) }
    var idp by remember { mutableStateOf(pon?.str("id_pegawai").orEmpty().ifBlank { pon?.str("id").orEmpty() }) }
    val ownEmployee = remember(pon) {
        PegawaiSearchResult(
            id = pon?.str("id_pegawai").orEmpty().ifBlank { pon?.str("id").orEmpty() },
            nip = pon?.str("nip").orEmpty(),
            nama = pon?.str("nama").orEmpty().ifBlank { pon?.str("nama_pegawai").orEmpty() },
            instansi = pon?.str("nama_instansi").orEmpty().ifBlank { pon?.str("instansi").orEmpty() },
            kodeInstansi = pon?.str("kode_instansi").orEmpty(),
        )
    }
    var selectedEmployee by remember(profile.id) { mutableStateOf(ownEmployee) }
    var unitTable by remember { mutableStateOf<RefTable?>(null) }
    var unitQuery by remember { mutableStateOf("") }
    var showUnitResults by remember { mutableStateOf(false) }
    var selectedUnitName by remember { mutableStateOf(pon?.str("nama_instansi").orEmpty().ifBlank { pon?.str("instansi").orEmpty() }) }
    var statusFilter by remember { mutableStateOf("pns") }
    var status by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var previewReport by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var reportPreviewOpen by remember { mutableStateOf(false) }

    val permissions = LocalAppPermissions.current
    val canSearchLaporan = permissions.role == AppRole.ADMIN || permissions.allowSearchLaporan
    val usesEmployee = reportType == "per-pegawai" || reportType == "per-pegawai-aktivitas"
    val reportOptions = buildList {
        if (permissions.role == AppRole.ADMIN || permissions.reportPerPegawai) add("per-pegawai" to "Per Pegawai")
        if (permissions.role == AppRole.ADMIN || permissions.reportPerPegawaiAktivitas) add("per-pegawai-aktivitas" to "Per Pegawai (Aktivitas)")
        if (permissions.role == AppRole.ADMIN || permissions.reportSkorPerInstansi) add("rekap-instansi" to "Rekap Instansi")
        if (permissions.role == AppRole.ADMIN || permissions.reportSkorPerInstansi) add("skor-per-instansi" to "Skor Per Instansi")
        if (permissions.role == AppRole.ADMIN || permissions.reportAktivitasPerInstansi) add("aktivitas-per-instansi" to "Aktivitas Per Instansi")
        if (permissions.role == AppRole.ADMIN || permissions.reportRekapTppAktivitas) add("rekap-tpp-aktivitas" to "Rekap TPP Aktivitas")
    }.ifEmpty { listOf("per-pegawai" to "Per Pegawai") }
    LaunchedEffect(reportOptions) {
        if (reportOptions.none { it.first == reportType }) reportType = reportOptions.first().first
    }
    LaunchedEffect(reportType) {
        statusFilter = if (reportType in setOf("per-pegawai", "per-pegawai-aktivitas")) "all" else "pns"
    }
    LaunchedEffect(Unit) {
        unitTable = runCatching { RefDataLoader.loadTable("files/opd.json") }.getOrNull()
        if (unitTable == null) unitTable = runCatching { RefDataLoader.loadTable("files/instansi.json") }.getOrNull()
    }
    val unitResults = remember(unitTable, unitQuery) {
        val source = unitTable ?: return@remember emptyList()
        if (unitQuery.trim().length < 2) emptyList()
        else source.search(unitQuery, listOf("nama", "kode", "id"), 12)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Laporan Ponorogo", fontWeight = FontWeight.Bold)
                Text(
                    "Unduh laporan melalui endpoint /ep menggunakan parameter reportType, rentang tanggal, ID pegawai/unit, dan format.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Jenis Laporan", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                HorizontalScrollRow(modifier = Modifier.fillMaxWidth(), itemSpacing = 8) {
                    reportOptions.forEach { (value, label) ->
                        FilterChip(
                            selected = reportType == value,
                            onClick = { reportType = value },
                            label = { Text(label, fontSize = 11.sp) },
                        )
                    }
                }
                Text(
                    "Catatan: ketersediaan jenis laporan bergantung pada dukungan server. Jika tidak tersedia, server dapat mengembalikan galat.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Parameter", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                HorizontalScrollRow(modifier = Modifier.fillMaxWidth(), itemSpacing = 8) {
                    DateField(dateStart, { dateStart = it }, label = "Dari (t1)", supportingText = "Tanggal laporan", modifier = Modifier.width(160.dp))
                    DateField(dateEnd, { dateEnd = it }, label = "Sampai (t2)", supportingText = "Tanggal laporan", modifier = Modifier.width(160.dp))
                }
                if (usesEmployee) {
                    PegawaiSearchField(
                        enabled = canSearchLaporan,
                        selected = selectedEmployee,
                        own = ownEmployee,
                        onSelect = { employee ->
                            selectedEmployee = employee
                            idp = employee.id
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = unitQuery,
                                onValueChange = { unitQuery = it; showUnitResults = true },
                                label = { Text("Cari instansi / OPD") },
                                supportingText = { Text(if (unitTable == null) "Data referensi unit belum tersedia." else "Pilih unit dari daftar atau isi IDU di bawah.", fontSize = 10.sp) },
                                singleLine = true,
                                enabled = canSearchLaporan,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DropdownMenu(
                                expanded = showUnitResults && unitResults.isNotEmpty(),
                                onDismissRequest = { showUnitResults = false },
                                modifier = Modifier.heightIn(max = 320.dp),
                            ) {
                                unitResults.forEach { unit ->
                                    val code = unit["kode"].orEmpty().ifBlank { unit["id"].orEmpty() }
                                    val name = unit["nama"].orEmpty().ifBlank { code }
                                    DropdownMenuItem(
                                        text = { Text("$name${if (code.isBlank()) "" else " • $code"}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                        onClick = {
                                            idu = code
                                            selectedUnitName = name
                                            unitQuery = name
                                            showUnitResults = false
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = idu,
                            onValueChange = { idu = it; selectedUnitName = "" },
                            label = { Text("ID/Kode Unit (idu)") },
                            singleLine = true,
                            enabled = canSearchLaporan,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Unit terpilih: ${selectedUnitName.ifBlank { idu.ifBlank { "belum dipilih" } }}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Filter status", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("pns" to "PNS", "non_pns" to "Non-PNS").forEach { (value, label) ->
                                FilterChip(
                                    selected = statusFilter == value,
                                    onClick = { statusFilter = value },
                                    enabled = canSearchLaporan,
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Format", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    FilterChip(selected = format == "pdf", onClick = { format = "pdf" }, label = { Text("PDF") })
                    FilterChip(selected = format == "xls", onClick = { format = "xls" }, label = { Text("XLS") })
                    Button(
                        onClick = {
                            if (!canSensitive) {
                                status = "Aksi sensitif (unduh/ekspor laporan) tidak diizinkan untuk akun ini."
                                return@Button
                            }
                            if (usesEmployee && (selectedEmployee.id.isBlank() || idp.isBlank())) {
                                status = "Pilih pegawai terlebih dahulu."
                                return@Button
                            }
                            if (!usesEmployee && idu.isBlank()) {
                                status = "Pilih atau masukkan ID/Kode Unit terlebih dahulu."
                                return@Button
                            }
                            val binarySaver = AppServices.binaryExporter
                            if (binarySaver == null) {
                                status = "Fitur simpan berkas biner tidak tersedia di platform ini."
                                return@Button
                            }
                            loading = true
                            status = null
                            scope.launch {
                                try {
                                    val q = buildString {
                                        append("reportType="); append(reportType)
                                        append("&idp="); append(idp.trim())
                                        append("&idu="); append(idu.trim())
                                        append("&t1="); append(dateStart.trim())
                                        append("&t2="); append(dateEnd.trim())
                                        append("&status="); append(statusFilter)
                                        append("&format="); append(format)
                                    }
                                    val bytes = AppServices.ponorogoClient.report(q, profile.epBaseUrl)
                                    val name = "tetuko-laporan-$reportType-${dateStart.trim()}-${dateEnd.trim()}.$format"
                                    previewReport = name to bytes
                                    reportPreviewOpen = true
                                    val saved = binarySaver(name, bytes)
                                    status = if (saved) {
                                        "Laporan disimpan: $name (${bytes.size} byte)."
                                    } else {
                                        "Penyimpanan dibatalkan."
                                    }
                                } catch (e: Exception) {
                                    status = "Gagal mengunduh laporan: ${e.message}"
                                } finally {
                                    loading = false
                                }
                            }
                        },
                        enabled = !loading && ctx.apiKey.isNotBlank() && canSensitive,
                    ) { Text(if (loading) "Mengunduh…" else "Unduh Laporan") }
                    previewReport?.let { (name, _) ->
                        OutlinedButton(onClick = { reportPreviewOpen = true }) { Text("Pratinjau") }
                    }
                    if (!canSensitive) {
                        Text("Aksi unduh/ekspor laporan dinonaktifkan: izin aksi sensitif tidak diberikan.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                status?.let {
                    val isError = it.startsWith("Gagal") || it.contains("tidak tersedia", true)
                    Text(it, fontSize = 12.sp, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    previewReport?.takeIf { reportPreviewOpen }?.let { (name, bytes) ->
        ReportPreviewDialog(
            name = name,
            bytes = bytes,
            format = format,
            onClose = { reportPreviewOpen = false },
        )
    }
}

@Composable
private fun ReportPreviewDialog(
    name: String,
    bytes: ByteArray,
    format: String,
    onClose: () -> Unit,
) {
    var fullscreen by remember(name) { mutableStateOf(false) }
    var message by remember(name) { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().heightIn(min = 420.dp, max = 720.dp).padding(12.dp),
            shape = if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pratinjau Laporan", fontWeight = FontWeight.Bold)
                        Text(name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { fullscreen = !fullscreen }) {
                        Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Ubah layar penuh")
                    }
                    IconButton(onClick = {
                        val opened = AppServices.binaryPreviewer?.invoke(name, bytes) ?: false
                        message = if (opened) "Dibuka di aplikasi bawaan." else "Viewer bawaan tidak tersedia. Gunakan Unduh."
                    }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Buka dengan aplikasi bawaan") }
                    IconButton(onClick = {
                        val saved = AppServices.binaryExporter?.invoke(name, bytes) ?: false
                        message = if (saved) "Laporan berhasil disimpan." else "Penyimpanan dibatalkan atau tidak tersedia."
                    }) { Icon(Icons.Filled.Download, "Unduh laporan") }
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Tutup") }
                }

                Box(
                    Modifier.weight(1f).fillMaxWidth().background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (format == "pdf") {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(24.dp)) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Color(0xFFDC2626), modifier = Modifier.size(48.dp))
                            Text("Dokumen PDF siap", fontWeight = FontWeight.Bold)
                            Text("Gunakan tombol Buka untuk tampilan native fullscreen.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { AppServices.binaryPreviewer?.invoke(name, bytes) }) {
                                Icon(Icons.Filled.Fullscreen, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Buka Pratinjau Native")
                            }
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(24.dp)) {
                            Icon(Icons.Filled.Download, null, tint = Color(0xFF059669), modifier = Modifier.size(48.dp))
                            Text("Pratinjau XLS buka melalui aplikasi spreadsheet", fontWeight = FontWeight.Bold)
                            Text("Format spreadsheet tidak ditampilkan inline oleh semua platform.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { AppServices.binaryPreviewer?.invoke(name, bytes) }) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Buka / Unduh XLS")
                            }
                        }
                    }
                }

                message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

// ── Izin/Cuti (Ponorogo) ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PonorogoIzinScreen(profile: ServerProfile, page: PageDef) {
    val ctx by AppServices.serverContext.collectAsState()
    val scope = rememberCoroutineScope()
    val pon = AppServices.ponorogoProfile
    val pegawaiId = pon?.str("id_pegawai").orEmpty().ifBlank { pon?.str("id").orEmpty() }
    val namaPegawai = pon?.str("nama").orEmpty().ifBlank { pon?.str("nama_pegawai").orEmpty() }
    val nipPegawai = pon?.str("nip").orEmpty()
    val ownPegawai = remember(pegawaiId, namaPegawai, nipPegawai) {
        PegawaiSearchResult(pegawaiId, nipPegawai, namaPegawai, pon?.str("nama_instansi").orEmpty())
    }
    val canSearchIzin = LocalAppPermissions.current.canUseFeature("search_izin")
    var targetPegawai by remember(ownPegawai) { mutableStateOf(ownPegawai) }
    val activePegawai = if (canSearchIzin) targetPegawai else ownPegawai

    val permissions = LocalAppPermissions.current
    val canPengajuan = permissions.role == AppRole.ADMIN || permissions.tabPengajuanIzin
    val canHistory = permissions.role == AppRole.ADMIN || permissions.canAccessMenu(profile.id, "tabIzin")
    var selectedTab by remember { mutableStateOf(if (canPengajuan) 0 else 1) }
    var loading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var jenisList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) } // id to nama
    var jenisExpanded by remember { mutableStateOf(false) }
    var selectedJenis by remember { mutableStateOf<Pair<String, String>?>(null) }
    var attachment by remember { mutableStateOf<id.tetuko.app.service.PickedFile?>(null) }
    var attachmentPreview by remember { mutableStateOf<id.tetuko.app.service.PickedFile?>(null) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var keterangan by remember { mutableStateOf("") }
    var tglMulai by remember { mutableStateOf(todayIso()) }
    var tglSelesai by remember { mutableStateOf(todayIso()) }
    var confirmSubmit by remember { mutableStateOf(false) }
    var historyStart by remember { mutableStateOf(daysAgoIso(3)) }
    var historyEnd by remember { mutableStateOf(todayIso()) }
    var historyRequestId by remember { mutableIntStateOf(0) }
    var jenisRequestId by remember { mutableIntStateOf(0) }
    val canSensitive = rememberCanSensitiveAction()

    fun pickAttachment() {
        val available = AppServices.requestFilePicker(
            "Pilih lampiran izin",
            listOf("jpg", "jpeg", "png", "webp", "pdf", "doc", "docx"),
        ) { picked ->
            if (picked == null) return@requestFilePicker
            val extension = picked.name.substringAfterLast('.', "").lowercase()
            val allowedExtensions = setOf("jpg", "jpeg", "png", "webp", "pdf", "doc", "docx")
            val allowedMimeTypes = setOf(
                "image/jpeg", "image/jpg", "image/png", "image/webp", "application/pdf",
                "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            )
            attachmentError = when {
                extension !in allowedExtensions && picked.mimeType.lowercase() !in allowedMimeTypes ->
                    "Format tidak didukung. Gunakan JPG, JPEG, PNG, WEBP, PDF, DOC, atau DOCX."
                picked.bytes.size > 5 * 1024 * 1024 -> "Ukuran file maksimum 5 MB."
                else -> null
            }
            if (attachmentError == null) attachment = picked
        }
        if (!available) attachmentError = "Pemilih berkas tidak tersedia di platform ini."
    }

    fun loadHistory(showStatus: Boolean = true) {
        val targetId = if (canSearchIzin) targetPegawai.id else ownPegawai.id
        if (ctx.apiKey.isBlank() || targetId.isBlank()) {
            if (showStatus) status = "Sesi/ID pegawai tidak tersedia. Silakan login ulang."
            return
        }
        val currentRequest = ++historyRequestId
        loading = true
        scope.launch {
            try {
                val res = AppServices.ponorogoClient.send("/izin/history_Izin", buildJsonObject {
                    put("id_pegawai", targetId)
                    put("tgl_awal", historyStart)
                    put("tgl_akhir", historyEnd)
                })
                if (currentRequest != historyRequestId) return@launch
                history = res.asArr().mapNotNull { it as? JsonObject }
                if (showStatus) status = "Riwayat izin dimuat: ${history.size} item."
            } catch (e: Exception) {
                if (currentRequest == historyRequestId && showStatus) status = "Gagal memuat riwayat izin: ${e.message}"
            } finally {
                if (currentRequest == historyRequestId) loading = false
            }
        }
    }

    fun loadJenis() {
        val targetId = if (canSearchIzin) targetPegawai.id else ownPegawai.id
        if (ctx.apiKey.isBlank() || targetId.isBlank()) return
        val currentRequest = ++jenisRequestId
        scope.launch {
            try {
                val res = AppServices.ponorogoClient.send("/izin/jenis_izin", buildJsonObject { put("id_pegawai", targetId) })
                if (currentRequest != jenisRequestId) return@launch
                val arr = res.asArr()
                jenisList = arr.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val id = o.str("id").ifBlank { o.str("id_jenis_ijin_cuti").ifBlank { o.str("id_jenis_izin") } }
                    val nama = o.str("nama").ifBlank { o.str("nama_jenis_ijin").ifBlank { o.str("jenis_izin") } }
                    if (id.isBlank() || nama.isBlank()) null else id to nama
                }
                if (selectedJenis == null && jenisList.isNotEmpty()) selectedJenis = jenisList.first()
            } catch (_: Exception) {
                if (currentRequest == jenisRequestId) jenisList = emptyList()
            }
        }
    }

    fun validateSubmission(): Boolean {
        if (!canSensitive) { status = "Akun ini tidak memiliki izin untuk mengajukan izin/cuti (aksi sensitif)."; return false }
        if (ctx.apiKey.isBlank()) { status = "Sesi belum aktif. Silakan login server terlebih dahulu."; return false }
        val target = if (canSearchIzin) targetPegawai else ownPegawai
        if (target.id.isBlank()) { status = "ID pegawai tidak tersedia. Login ulang diperlukan."; return false }
        if (keterangan.isBlank()) { status = "Keterangan/alasan wajib diisi."; return false }
        val jenis = selectedJenis
        if (jenis == null || jenis.first.isBlank()) { status = "Pilih jenis izin/cuti terlebih dahulu."; return false }
        val mulai = runCatching { LocalDate.parse(tglMulai) }.getOrNull()
        val selesai = runCatching { LocalDate.parse(tglSelesai) }.getOrNull()
        if (mulai == null || selesai == null) { status = "Tanggal harus valid dengan format YYYY-MM-DD."; return false }
        if (selesai < mulai) { status = "Tanggal selesai tidak boleh sebelum tanggal mulai."; return false }
        return true
    }

    fun submit() {
        if (!validateSubmission()) return
        confirmSubmit = false
        val target = if (canSearchIzin) targetPegawai else ownPegawai
        val jenis = selectedJenis ?: return
        submitting = true
        status = null
        scope.launch {
            try {
                val fields = mutableMapOf(
                    "id_pegawai" to target.id,
                    "id_jenis_ijin_cuti" to jenis.first,
                    "id_jenis_izin" to jenis.first,
                    "keterangan" to keterangan,
                    "tgl_mulai" to tglMulai,
                    "tgl_selesai" to tglSelesai,
                    "tanggal_mulai" to tglMulai,
                    "tanggal_selesai" to tglSelesai,
                    "nip" to target.nip,
                )
                val file = attachment
                val res = if (file != null) {
                    @OptIn(ExperimentalEncodingApi::class)
                    val b64 = Base64.encode(file.bytes)
                    AppServices.ponorogoClient.sendMultipart(
                        "/Izin/insert_ijin",
                        fields,
                        fileName = file.name,
                        base64 = b64,
                        mimeType = file.mimeType,
                    )
                } else {
                    AppServices.ponorogoClient.sendMultipart("/Izin/insert_ijin", fields)
                }.asObj()
                val ok = (res?.get("success") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
                val codeOk = res?.str("code") == "200" || res?.str("status") == "success"
                if (ok || codeOk) {
                    status = "Pengajuan izin berhasil dikirim."
                    keterangan = ""
                    attachment = null
                    selectedTab = 1
                    loadHistory(showStatus = false)
                } else {
                    status = "Gagal mengirim pengajuan: ${res?.str("message") ?: res?.str("error") ?: "respons tidak dikenal"}"
                }
            } catch (e: Exception) {
                status = "Gagal mengirim pengajuan: ${e.message}"
            } finally {
                submitting = false
            }
        }
    }

    LaunchedEffect(canSearchIzin, ownPegawai) {
        if (!canSearchIzin) {
            targetPegawai = ownPegawai
            selectedJenis = null
        }
    }
    LaunchedEffect(profile.id, ctx.apiKey, activePegawai.id) {
        if (ctx.apiKey.isNotBlank()) loadJenis()
    }
    LaunchedEffect(profile.id, ctx.apiKey, activePegawai.id, historyStart, historyEnd) {
        if (ctx.apiKey.isNotBlank()) loadHistory(showStatus = false)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)

        Card(shape = RoundedCornerShape(16.dp)) {
            Column {
                if (canSearchIzin) {
                    PegawaiSearchField(
                        enabled = true,
                        selected = targetPegawai,
                        own = ownPegawai,
                        onSelect = {
                            if (targetPegawai.id != it.id) {
                                targetPegawai = it
                                selectedJenis = null
                                jenisList = emptyList()
                                status = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
                TabRow(selectedTabIndex = selectedTab) {
                    if (canPengajuan) Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Pengajuan") })
                    if (canHistory) Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Riwayat") })
                }

                status?.let {
                    val isError = it.startsWith("Gagal") || it.contains("wajib", true) || it.contains("tidak boleh", true) || it.contains("tidak tersedia", true) || it.contains("belum aktif", true)
                    Surface(color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
                        Text(it, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 12.sp)
                    }
                }

                when {
                    selectedTab == 0 && canPengajuan -> {
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Ajukan Izin/Cuti", fontWeight = FontWeight.Bold)
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(if (canSearchIzin) "Pengajuan untuk" else "Pegawai (diri sendiri)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        InfoLine("Nama", activePegawai.nama.ifBlank { "—" })
                                        InfoLine("NIP", activePegawai.nip.ifBlank { "—" })
                                        InfoLine("ID Pegawai", activePegawai.id.ifBlank { "—" })
                                    }
                                }

                            DropdownField(
                                value = selectedJenis?.first.orEmpty(),
                                options = jenisList,
                                onValueChange = { id -> selectedJenis = jenisList.firstOrNull { it.first == id } },
                                label = "Jenis Izin/Cuti",
                                placeholder = if (jenisList.isEmpty()) "Memuat jenis izin…" else "Pilih jenis izin",
                                modifier = Modifier.fillMaxWidth(),
                                enabled = jenisList.isNotEmpty() && !submitting,
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DateField(tglMulai, { tglMulai = it }, label = "Mulai", modifier = Modifier.weight(1f))
                                DateField(tglSelesai, { tglSelesai = it }, label = "Selesai", modifier = Modifier.weight(1f))
                            }
                            OutlinedTextField(
                                keterangan,
                                { keterangan = it },
                                label = { Text("Keterangan/Alasan") },
                                supportingText = { Text("Wajib diisi", fontSize = 11.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Lampiran (opsional)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { pickAttachment() },
                                        enabled = AppServices.hasFilePicker && !submitting,
                                    ) { Text(if (attachment == null) "Pilih Lampiran" else "Ganti Lampiran") }
                                    attachment?.let {
                                        Text(it.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        if (it.mimeType.startsWith("image/")) {
                                            TextButton(onClick = { attachmentPreview = it }, enabled = !submitting) { Text("Lihat") }
                                        }
                                        TextButton(onClick = { attachment = null; attachmentError = null }, enabled = !submitting) { Text("Hapus") }
                                    } ?: Text("JPG/PNG/WEBP/PDF/DOC/DOCX · Maks. 5 MB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                attachmentError?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { if (validateSubmission()) confirmSubmit = true }, enabled = !submitting && ctx.apiKey.isNotBlank() && canSensitive) {
                                    Text(if (submitting) "Memproses…" else "Kirim Pengajuan")
                                }
                                if (submitting) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                            }
                            Text("Pengajuan dikirim ke /Izin/insert_ijin.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    canHistory -> {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Riwayat Izin/Cuti", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                FilledTonalButton(onClick = { loadHistory() }, enabled = !loading) {
                                    Text(if (loading) "Memuat…" else "Muat Ulang")
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                DateField(historyStart, { historyStart = it }, label = "Dari", modifier = Modifier.width(170.dp))
                                DateField(historyEnd, { historyEnd = it }, label = "Sampai", modifier = Modifier.width(170.dp))
                            }
                            if (loading && history.isEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text("Memuat riwayat…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (history.isEmpty() && !loading) {
                                Text("Belum ada riwayat izin.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (history.isNotEmpty()) {
                                LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                    items(history) { row ->
                                        val fp = row.str("file_path").ifBlank { row.str("lampiran").ifBlank { row.str("attachment") } }
                                        val judul = row.str("keterangan").ifBlank { row.str("alasan").ifBlank { "Pengajuan izin" } }
                                        val mulai = row.str("tgl_mulai").ifBlank { row.str("tanggal_mulai") }
                                        val selesai = row.str("tgl_selesai").ifBlank { row.str("tanggal_selesai") }
                                        val (statusLabel, statusColor) = izinStatusDisplay(row)
                                        val jenisNama = row.str("nama_jenis_ijin").ifBlank { row.str("jenis_izin") }
                                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(judul, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            Text(
                                                listOfNotNull(
                                                    if (mulai.isNotBlank() || selesai.isNotBlank()) "$mulai s/d $selesai" else null,
                                                    jenisNama.takeIf { it.isNotBlank() },
                                                ).joinToString(" • "),
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            if (statusLabel.isNotBlank()) {
                                                Text("Status: $statusLabel", fontSize = 12.sp, color = statusColor, fontWeight = FontWeight.Medium)
                                            }
                                            if (fp.isNotBlank()) {
                                                Text("Lampiran: ${fp.substringAfterLast('/')}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (confirmSubmit) {
            AlertDialog(
                onDismissRequest = { confirmSubmit = false },
                title = { Text("Konfirmasi Pengajuan Izin") },
                text = {
                    Text("${activePegawai.nama} - ${selectedJenis?.second}\n$tglMulai s/d $tglSelesai\n$keterangan")
                },
                confirmButton = {
                    Button(onClick = { submit() }, enabled = !submitting) { Text("Ya, Kirim") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { confirmSubmit = false }) { Text("Batal") }
                },
            )
        }
        attachmentPreview?.let { file ->
            FullscreenImageViewer(
                model = file.bytes,
                title = file.name,
                onDismiss = { attachmentPreview = null },
                onDownload = { file.bytes },
                downloadFileName = file.name,
            )
        }
    }
}

// ── History/Log (Ponorogo) ─────────────────────────────────────────────────

@Composable
fun PonorogoHistoryScreen(profile: ServerProfile, page: PageDef) {
    val ctx by AppServices.serverContext.collectAsState()
    val scope = rememberCoroutineScope()
    val pon = AppServices.ponorogoProfile
    val pegawaiId = pon?.str("id_pegawai").orEmpty().ifBlank { pon?.str("id").orEmpty() }
    val ownPegawai = remember(pegawaiId, pon?.str("nip"), pon?.str("nama")) {
        PegawaiSearchResult(pegawaiId, pon?.str("nip").orEmpty(), pon?.str("nama").orEmpty(), "")
    }
    val permissions = LocalAppPermissions.current
    val canSearchOthers = permissions.role == AppRole.ADMIN || permissions.allowSearchPresensi || permissions.canUseFeature("search_presensi")
    var targetPegawai by remember(ownPegawai) { mutableStateOf(ownPegawai) }
    var status by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var tglAwal by remember { mutableStateOf(daysAgoIso(7)) }
    var tglAkhir by remember { mutableStateOf(todayIso()) }
    var search by remember { mutableStateOf("") }
    var visibleCount by remember { mutableIntStateOf(50) }
    val canTerkini = permissions.role == AppRole.ADMIN || permissions.tabLogPresensiTerkini
    val canLengkap = permissions.role == AppRole.ADMIN || permissions.tabLogLengkap
    var historyMode by remember(canTerkini, canLengkap) { mutableIntStateOf(if (canTerkini) 0 else 1) }
    var requestId by remember { mutableIntStateOf(0) }

    fun load() {
        val targetId = if (canSearchOthers) targetPegawai.id else ownPegawai.id
        if (ctx.apiKey.isBlank() || targetId.isBlank()) { status = "Sesi/ID pegawai tidak tersedia."; return }
        val currentRequest = ++requestId
        loading = true
        status = null
        scope.launch {
            try {
                val res = AppServices.ponorogoClient.send("/logActivity/log", buildJsonObject {
                    put("id_pegawai", targetId)
                    put("tgl_awal", tglAwal)
                    put("tgl_akhir", tglAkhir)
                })
                var data = res.asArr().mapNotNull { it as? JsonObject }
                if (data.isEmpty()) data = res.asObj()?.get("data")?.asArr()?.mapNotNull { it as? JsonObject } ?: emptyList()
                if (currentRequest != requestId) return@launch
                rows = data
                status = "Riwayat presensi dimuat: ${rows.size} baris untuk ${targetPegawai.nama.ifBlank { targetId }} ($tglAwal s/d $tglAkhir)."
            } catch (e: Exception) {
                if (currentRequest == requestId) {
                    status = "Gagal memuat riwayat: ${e.message}"
                    rows = emptyList()
                }
            } finally {
                if (currentRequest == requestId) loading = false
            }
        }
    }
    LaunchedEffect(profile.id, ctx.apiKey, targetPegawai.id) { if (ctx.apiKey.isNotBlank()) load() }

    fun rowTanggal(row: JsonObject): String = row.str("tanggal").ifBlank { row.str("tgl").ifBlank { row.str("created_at") } }
    fun rowMasuk(row: JsonObject): String = row.str("absen_masuk").ifBlank { row.str("jam_masuk").ifBlank { row.str("waktu_masuk") } }
    fun rowPulang(row: JsonObject): String = row.str("absen_pulang").ifBlank { row.str("jam_pulang").ifBlank { row.str("waktu_pulang") } }
    fun rowKet(row: JsonObject): String = row.str("keterangan").ifBlank { row.str("status").ifBlank { row.str("aktivitas") } }
    fun hasTime(v: String): Boolean = v.isNotBlank() && v != "—" && v != "null" && v != "-"

    val filteredRows = remember(rows, search) {
        val q = search.trim().lowercase()
        if (q.isBlank()) rows else rows.filter { row ->
            listOf(rowTanggal(row), rowMasuk(row), rowPulang(row), rowKet(row)).any { it.lowercase().contains(q) }
        }
    }
    val daysWithMasuk = remember(rows) { rows.count { hasTime(rowMasuk(it)) } }
    val daysWithPulang = remember(rows) { rows.count { hasTime(rowPulang(it)) } }
    val uniqueDates = remember(rows) { rows.mapNotNull { rowTanggal(it).takeIf { t -> t.isNotBlank() && t != "null" } }.toSet().size }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("History / Log Presensi", fontWeight = FontWeight.Bold)
                if (canTerkini || canLengkap) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canTerkini) FilterChip(selected = historyMode == 0, onClick = {
                            historyMode = 0
                            visibleCount = 50
                            tglAwal = daysAgoIso(7)
                            tglAkhir = todayIso()
                        }, label = { Text("Terkini") })
                        if (canLengkap) FilterChip(selected = historyMode == 1, onClick = {
                            historyMode = 1
                            visibleCount = 50
                            tglAwal = daysAgoIso(30)
                            tglAkhir = todayIso()
                        }, label = { Text("Lengkap") })
                    }
                }
                PegawaiSearchField(
                    enabled = canSearchOthers,
                    selected = if (canSearchOthers) targetPegawai else ownPegawai,
                    own = ownPegawai,
                    onSelect = { targetPegawai = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DateField(tglAwal, { tglAwal = it }, label = "Dari", modifier = Modifier.width(170.dp))
                    DateField(tglAkhir, { tglAkhir = it }, label = "Sampai", modifier = Modifier.width(170.dp))
                    FilledTonalButton(onClick = { load() }, enabled = !loading) { Text(if (loading) "Memuat…" else "Muat") }
                }
                OutlinedTextField(
                    search,
                    { search = it },
                    label = { Text("Cari dalam data (tanggal/keterangan)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (rows.isNotEmpty()) {
                    Text(
                        "Ringkasan: ${rows.size} baris • $uniqueDates tanggal unik • $daysWithMasuk dengan jam masuk • $daysWithPulang dengan jam pulang" +
                            if (search.isNotBlank()) " • Ditampilkan: ${filteredRows.size}" else "",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                status?.let {
                    val isError = it.startsWith("Gagal") || it.contains("tidak tersedia", true)
                    Text(it, fontSize = 12.sp, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (filteredRows.isNotEmpty()) {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Tanggal", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.width(110.dp))
                        Text("Masuk", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.width(90.dp))
                        Text("Pulang", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.width(90.dp))
                        Text("Keterangan", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider()
                    LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        items(filteredRows.take(visibleCount)) { row ->
                            val tanggal = rowTanggal(row)
                            val masuk = rowMasuk(row)
                            val pulang = rowPulang(row)
                            val ket = rowKet(row)
                            val lamp = row.str("lampiran").ifBlank { row.str("foto").ifBlank { row.str("file_lampiran") } }
                            val hasLamp = lamp.isNotBlank() && lamp != "null" && !lamp.contains("no_image", true)
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                    Text(tanggal.ifBlank { "—" }, fontSize = 12.sp, modifier = Modifier.width(110.dp))
                                    Text(masuk.ifBlank { "—" }, fontSize = 12.sp, modifier = Modifier.width(90.dp))
                                    Text(pulang.ifBlank { "—" }, fontSize = 12.sp, modifier = Modifier.width(90.dp))
                                    Text(ket.ifBlank { "—" }, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                if (hasLamp) PonorogoAttachmentPreview(lamp, "Foto presensi $tanggal")
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            }
                        }
                    }
                    if (filteredRows.size > visibleCount) {
                        OutlinedButton(onClick = { visibleCount += 50 }) { Text("Tampilkan 50 baris lagi") }
                    }
                }
            }
        } else if (!loading) {
            Card(shape = RoundedCornerShape(16.dp)) {
                val msg = if (rows.isEmpty()) {
                    "Belum ada data riwayat presensi pada rentang tanggal ini."
                } else {
                    "Tidak ada baris yang cocok dengan pencarian."
                }
                Text(msg, Modifier.padding(16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


private fun parseDateTimeMillis(raw: String): Long? {
    val s = raw.trim()
    if (s.isBlank() || s == "null") return null
    // Try kotlinx-datetime Instant / LocalDateTime patterns first
    runCatching { return kotlinx.datetime.Instant.parse(s).toEpochMilliseconds() }
    val norm = if (s.contains('T')) s else s.replace(' ', 'T')
    runCatching { return kotlinx.datetime.Instant.parse(norm + if (norm.endsWith('Z') || norm.contains('+') || Regex(".*T.*-\\d{2}:?\\d{2}$").matches(norm)) "" else "Z").toEpochMilliseconds() }
    // Manual parse: YYYY-MM-DD[ T]HH:mm(:ss)?
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?""").find(norm) ?: return null
    val (y, mo, d, h, mi, sec) = m.destructured
    return runCatching {
        val ldt = kotlinx.datetime.LocalDateTime(
            y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), sec.ifBlank { "0" }.toInt()
        )
        ldt.toInstant(TimeZone.of("Asia/Jakarta")).toEpochMilliseconds()
    }.getOrNull()
}

private fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun JsonObject.taskId(): String =
    str("id").ifBlank { str("id_log").ifBlank { str("id_aktifitas").ifBlank { str("id_aktivitas") } } }

private fun JsonObject.isOngoingTask(): Boolean {
    val selesai = str("tgl_selesai").ifBlank { str("jam_selesai") }
    if (selesai.isBlank() || selesai == "null" || selesai == "0000-00-00 00:00:00") {
        val st = str("status").lowercase()
        if (st.contains("selesai") || st.contains("diakhiri") || st.contains("done") || st.contains("finish")) return false
        return true
    }
    return false
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalEncodingApi::class)
@Composable
fun PonorogoProduktivitasScreen(profile: ServerProfile, page: PageDef) {
    val canSensitive = rememberCanSensitiveAction()
    val ctx by AppServices.serverContext.collectAsState()
    val scope = rememberCoroutineScope()
    val pon = AppServices.ponorogoProfile
    val pegawaiId = pon?.str("id_pegawai").orEmpty().ifBlank { pon?.str("id").orEmpty() }

    var loading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var activities by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var tupoksi by remember { mutableStateOf<List<JsonObject>>(emptyList()) }

    // start form
    var jenis by remember { mutableStateOf("TUPOKSI") } // TUPOKSI | NON_TUPOKSI
    var selectedTupoksiId by remember { mutableStateOf("") }
    var selectedTupoksiNama by remember { mutableStateOf("") }
    var tupoksiExpanded by remember { mutableStateOf(false) }
    var keterangan by remember { mutableStateOf("") }
    var tugasNonTupoksi by remember { mutableStateOf("") }
    var lokasi by remember { mutableStateOf("") }

    // per-task UI: expanded, photo, loading flags
    data class TaskUi(var expanded: Boolean = false, var photo: id.tetuko.app.service.PickedFile? = null, var ending: Boolean = false, var continuing: Boolean = false)
    var taskUi by remember { mutableStateOf<Map<String, TaskUi>>(emptyMap()) }
    var activityPhotoPreview by remember { mutableStateOf<id.tetuko.app.service.PickedFile?>(null) }

    var nowMs by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            nowMs = Clock.System.now().toEpochMilliseconds()
        }
    }

    fun load() {
        if (ctx.apiKey.isBlank() || pegawaiId.isBlank()) {
            status = "Sesi/ID pegawai tidak tersedia."; statusIsError = true; return
        }
        loading = true
        status = null
        val today = todayIso()
        scope.launch {
            try {
                val aktif = AppServices.ponorogoClient.send(
                    "/Tupoksi/ambilDataAktivitas",
                    buildJsonObject {
                        put("id_pegawai", pegawaiId)
                        put("tgl_mulai", today)
                        put("tgl_akhir", today)
                    },
                )
                activities = aktif.asArr().mapNotNull { it as? JsonObject }
            } catch (e: Exception) {
                activities = emptyList()
                status = "Gagal memuat aktivitas: ${e.message}"; statusIsError = true
            }
            try {
                val t = AppServices.ponorogoClient.send(
                    "/Tupoksi/get_data_tupoksi",
                    buildJsonObject { put("id_pegawai", pegawaiId) },
                )
                tupoksi = t.asArr().mapNotNull { it as? JsonObject }
            } catch (_: Exception) { }
            if (status == null) {
                val ongoing = activities.count { it.isOngoingTask() }
                status = "Hari ini: ${activities.size} aktivitas ($ongoing berjalan) • Tupoksi: ${tupoksi.size}."
                statusIsError = false
            }
            loading = false
        }
    }

    LaunchedEffect(profile.id, ctx.apiKey) { if (ctx.apiKey.isNotBlank()) load() }

    fun startActivity() {
        if (!canSensitive) { status = "Aksi sensitif (mulai aktivitas) tidak diizinkan untuk akun ini."; statusIsError = true; return }
        if (ctx.apiKey.isBlank() || pegawaiId.isBlank()) { status = "Sesi/ID pegawai tidak tersedia."; statusIsError = true; return }
        scope.launch {
            submitting = true
            status = null
            try {
                val endpoint = if (jenis == "TUPOKSI") "/Tupoksi/simpanTupoksi" else "/Tupoksi/simpanNonTupoksi"
                val payload = buildJsonObject {
                    put("id_pegawai", pegawaiId)
                    put("lokasi", lokasi.trim())
                    if (jenis == "TUPOKSI") {
                        put("id_tupoksi", selectedTupoksiId)
                        put("deskr_tupoksi", keterangan)
                        put("keterangan", keterangan)
                    } else {
                        put("tugas", tugasNonTupoksi)
                        put("keterangan", keterangan)
                        put("deskr_tupoksi", keterangan)
                    }
                }
                if (jenis == "TUPOKSI" && selectedTupoksiId.isBlank()) { status = "Pilih tupoksi terlebih dahulu."; statusIsError = true; return@launch }
                if (jenis == "TUPOKSI" && keterangan.isBlank()) { status = "Keterangan tidak boleh kosong."; statusIsError = true; return@launch }
                if (jenis == "NON_TUPOKSI" && tugasNonTupoksi.isBlank()) { status = "Nama tugas tidak boleh kosong."; statusIsError = true; return@launch }
                val res = AppServices.ponorogoClient.send(endpoint, payload).asObj()
                val ok = (res?.get("success") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
                if (ok) {
                    status = "Aktivitas berhasil dimulai."
                    statusIsError = false
                    keterangan = ""; tugasNonTupoksi = ""; selectedTupoksiId = ""; selectedTupoksiNama = ""
                    load()
                } else {
                    status = "Gagal memulai aktivitas: ${res?.str("message") ?: res?.str("error") ?: "respons tidak dikenal"}"
                    statusIsError = true
                }
            } catch (e: Exception) {
                status = "Gagal memulai aktivitas: ${e.message}"; statusIsError = true
            } finally { submitting = false }
        }
    }

    fun endActivity(task: JsonObject, continueAfter: Boolean) {
        if (!canSensitive) { status = "Aksi sensitif (akhiri/lanjutkan aktivitas) tidak diizinkan untuk akun ini."; statusIsError = true; return }
        val taskId = task.taskId()
        if (taskId.isBlank()) { status = "ID aktivitas tidak ditemukan."; statusIsError = true; return }
        val ui = taskUi[taskId] ?: TaskUi()
        val lampiran = ui.photo?.let { Base64.encode(it.bytes) }.orEmpty()
        scope.launch {
            taskUi = taskUi + (taskId to ui.copy(ending = !continueAfter, continuing = continueAfter))
            try {
                val payload = buildJsonObject {
                    put("id_pegawai", pegawaiId)
                    put("id_aktifitas", taskId)
                    put("id_log", taskId)
                    put("id", taskId)
                    put("lampiran", lampiran)
                    put("lokasi", lokasi.trim())
                }
                val res = AppServices.ponorogoClient.send("/Tupoksi/akhiriAktivitas", payload).asObj()
                val ok = (res?.get("success") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
                if (!ok) {
                    status = "Gagal mengakhiri aktivitas: ${res?.str("message") ?: "respons tidak dikenal"}"
                    statusIsError = true
                    return@launch
                }
                if (continueAfter) {
                    val isNon = task.str("jenis").equals("NON_TUPOKSI", true) || task.str("id_tupoksi").isBlank()
                    val endpoint = if (isNon) "/Tupoksi/simpanNonTupoksi" else "/Tupoksi/simpanTupoksi"
                    val lanjutPayload = buildJsonObject {
                        put("id_pegawai", pegawaiId)
                        put("lokasi", lokasi.trim())
                        if (isNon) {
                            put("tugas", task.str("tugas"))
                            put("keterangan", task.str("keterangan"))
                            put("deskr_tupoksi", task.str("keterangan"))
                        } else {
                            put("id_tupoksi", task.str("id_tupoksi"))
                            put("deskr_tupoksi", task.str("keterangan").ifBlank { task.str("deskr_tupoksi") })
                            put("keterangan", task.str("keterangan"))
                        }
                    }
                    val lanj = AppServices.ponorogoClient.send(endpoint, lanjutPayload).asObj()
                    val lok = (lanj?.get("success") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
                    status = if (lok) "Aktivitas diakhiri dan dilanjutkan." else "Aktivitas diakhiri, namun gagal melanjutkan: ${lanj?.str("message") ?: ""}"
                    statusIsError = !lok
                } else {
                    status = "Aktivitas berhasil diakhiri."
                    statusIsError = false
                }
                taskUi = taskUi - taskId
                load()
            } catch (e: Exception) {
                status = "Gagal: ${e.message}"; statusIsError = true
            } finally {
                val cur = taskUi[taskId] ?: TaskUi()
                taskUi = taskUi + (taskId to cur.copy(ending = false, continuing = false))
            }
        }
    }

    val ongoing = activities.filter { it.isOngoingTask() }
    val finished = activities.filterNot { it.isOngoingTask() }

    VerticalScrollColumn(modifier = Modifier.fillMaxSize().padding(20.dp), itemSpacing = 14) {
        PageHeaderCard(profile, page)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { load() }, enabled = !loading) { Text(if (loading) "Memuat…" else "Muat Ulang") }
            status?.let {
                Text(it, fontSize = 12.sp, color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }

        // Start activity form
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Mulai Aktivitas", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = jenis == "TUPOKSI", onClick = { jenis = "TUPOKSI" }, label = { Text("Tupoksi") })
                    FilterChip(selected = jenis == "NON_TUPOKSI", onClick = { jenis = "NON_TUPOKSI" }, label = { Text("Non Tupoksi") })
                }
                if (jenis == "TUPOKSI") {
                    val tupoksiOptions = tupoksi.take(200).mapNotNull { t ->
                        val id = t.str("id").ifBlank { t.str("id_tupoksi") }
                        val nama = t.str("nama_tupoksi").ifBlank { t.str("tupoksi").ifBlank { t.str("nama").ifBlank { t.str("tugas") } } }
                        if (id.isBlank() && nama.isBlank()) null else id to nama.ifBlank { id }
                    }
                    DropdownField(
                        value = selectedTupoksiId,
                        options = tupoksiOptions,
                        onValueChange = { id ->
                            selectedTupoksiId = id
                            selectedTupoksiNama = tupoksiOptions.firstOrNull { it.first == id }?.second.orEmpty()
                        },
                        label = "Tupoksi",
                        placeholder = if (tupoksiOptions.isEmpty()) "Memuat tupoksi…" else "Pilih tupoksi",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = tupoksiOptions.isNotEmpty(),
                    )
                } else {
                    OutlinedTextField(tugasNonTupoksi, { tugasNonTupoksi = it }, label = { Text("Nama tugas") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                OutlinedTextField(keterangan, { keterangan = it }, label = { Text("Keterangan / deskripsi") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(
                    lokasi,
                    { lokasi = it },
                    label = { Text("Lokasi (opsional)") },
                    placeholder = { Text("Contoh: Kantor, lapangan, rumah dinas") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(onClick = { startActivity() }, enabled = !submitting && ctx.apiKey.isNotBlank() && canSensitive) {
                    Text(if (submitting) "Memulai…" else "Mulai Aktivitas")
                }
                if (!canSensitive) {
                    Text("Izin aksi sensitif diperlukan untuk mulai/mengakhiri aktivitas.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Lampiran foto (opsional) dapat dipilih dari berkas saat mengakhiri aktivitas. Nilai lokasi akan ikut dikirim saat mulai/mengakhiri aktivitas jika diisi.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Ongoing activities
        Text("Aktivitas Berjalan (${ongoing.size})", fontWeight = FontWeight.Bold)
        if (ongoing.isEmpty()) {
            Card(shape = RoundedCornerShape(16.dp)) {
                Text("Tidak ada aktivitas yang sedang berjalan.", Modifier.padding(16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            ongoing.forEach { task ->
                val taskId = task.taskId().ifBlank { task.hashCode().toString() }
                val ui = taskUi[taskId] ?: TaskUi()
                val judul = task.str("tugas").ifBlank { task.str("nama_tugas").ifBlank { task.str("keterangan").ifBlank { task.str("aktivitas") } } }
                val mulai = task.str("tgl_mulai").ifBlank { task.str("jam_mulai") }
                val startMs = parseDateTimeMillis(mulai)
                val elapsed = startMs?.let { formatElapsed(nowMs - it) } ?: "—"
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { taskUi = taskUi + (taskId to ui.copy(expanded = !ui.expanded)) }) {
                                Text(judul.ifBlank { "Aktivitas" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Mulai: ${mulai.ifBlank { "—" }} • Durasi: $elapsed", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { taskUi = taskUi + (taskId to ui.copy(expanded = !ui.expanded)) }) { Text(if (ui.expanded) "Tutup" else "Detail") }
                        }
                        if (ui.expanded) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = {
                                    AppServices.requestFilePicker("Pilih foto aktivitas", listOf("jpg", "jpeg", "png", "webp", "heic", "heif", "tif", "tiff", "bmp", "ico", "avif", "jxl")) { picked ->
                                        if (picked != null) {
                                            val normalized = AppServices.imageProcessor?.invoke(picked, 1280, 72) ?: picked
                                            taskUi = taskUi + (taskId to ui.copy(photo = normalized))
                                        }
                                    }
                                }, enabled = !ui.ending && !ui.continuing && AppServices.hasFilePicker) { Text(if (ui.photo == null) "Lampirkan Foto" else "Ganti Foto") }
                                if (AppServices.hasCameraCapture) {
                                    OutlinedButton(onClick = {
                                        AppServices.requestCameraCapture { captured ->
                                            if (captured != null) {
                                                val normalized = AppServices.imageProcessor?.invoke(captured, 1280, 72) ?: captured
                                                taskUi = taskUi + (taskId to ui.copy(photo = normalized))
                                            }
                                        }
                                    }, enabled = !ui.ending && !ui.continuing) { Text("Kamera") }
                                }
                                ui.photo?.let {
                                    Text(it.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { activityPhotoPreview = it }) { Text("Lihat") }
                                    TextButton(onClick = { taskUi = taskUi + (taskId to ui.copy(photo = null)) }) { Text("Hapus") }
                                } ?: Text("Tanpa lampiran foto", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { endActivity(task, false) }, enabled = !ui.ending && !ui.continuing && canSensitive) {
                                    Text(if (ui.ending) "Mengakhiri…" else "Akhiri Aktivitas")
                                }
                                OutlinedButton(onClick = { endActivity(task, true) }, enabled = !ui.ending && !ui.continuing && canSensitive) {
                                    Text(if (ui.continuing) "Memproses…" else "Akhiri & Lanjutkan")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Finished today
        Text("Aktivitas Selesai Hari Ini (${finished.size})", fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (finished.isEmpty() && ongoing.isEmpty()) {
                    Text("Belum ada aktivitas hari ini.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (finished.isEmpty()) {
                    Text("Belum ada aktivitas yang diakhiri hari ini.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    finished.take(50).forEach { row ->
                        val judul = row.str("tugas").ifBlank { row.str("nama_tugas").ifBlank { row.str("keterangan").ifBlank { row.str("aktivitas") } } }
                        val waktu = listOf(row.str("tgl_mulai"), row.str("tgl_selesai")).filter { it.isNotBlank() && it != "null" }.joinToString(" → ")
                        val st = row.str("status")
                        Column(Modifier.padding(vertical = 2.dp)) {
                            Text(judul.ifBlank { "Aktivitas" }, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            if (waktu.isNotBlank() || st.isNotBlank()) {
                                Text(listOf(waktu, st).filter { it.isNotBlank() }.joinToString(" • "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
    activityPhotoPreview?.let { file ->
        FullscreenImageViewer(
            model = file.bytes,
            title = file.name,
            onDismiss = { activityPhotoPreview = null },
            onDownload = { file.bytes },
            downloadFileName = file.name,
        )
    }
}

@Composable
fun PonorogoHistoryProduktivitasScreen(profile: ServerProfile, page: PageDef) {
    val ctx by AppServices.serverContext.collectAsState()
    val scope = rememberCoroutineScope()
    val pon = AppServices.ponorogoProfile
    val pegawaiId = pon?.str("id_pegawai").orEmpty().ifBlank { pon?.str("id").orEmpty() }
    val ownPegawai = remember(pegawaiId, pon?.str("nip"), pon?.str("nama")) {
        PegawaiSearchResult(pegawaiId, pon?.str("nip").orEmpty(), pon?.str("nama").orEmpty(), "")
    }
    val permissions = LocalAppPermissions.current
    val canSearchOthers = permissions.role == AppRole.ADMIN || permissions.allowSearchProduktivitas || permissions.canUseFeature("search_produktivitas")
    var targetPegawai by remember(ownPegawai) { mutableStateOf(ownPegawai) }

    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var summary by remember { mutableStateOf<JsonObject?>(null) }
    var tglAwal by remember { mutableStateOf(daysAgoIso(7)) }
    var tglAkhir by remember { mutableStateOf(todayIso()) }
    var requestId by remember { mutableIntStateOf(0) }

    fun load() {
        val targetId = if (canSearchOthers) targetPegawai.id else ownPegawai.id
        if (ctx.apiKey.isBlank() || targetId.isBlank()) { status = "Sesi/ID pegawai tidak tersedia."; return }
        val currentRequest = ++requestId
        loading = true
        status = null
        summary = null
        scope.launch {
            try {
                val jamRes = runCatching {
                    AppServices.ponorogoClient.send(
                        "/Tupoksi/ambil_jam_aktifitas",
                        buildJsonObject {
                            put("id_pegawai", targetId)
                            put("tgl_awal", tglAwal)
                            put("tgl_akhir", tglAkhir)
                        },
                    )
                }.getOrNull()
                summary = jamRes?.asObj()?.get("jumlah")?.asArr()?.firstOrNull()?.asObj()
                    ?: jamRes?.asArr()?.firstOrNull()?.asObj()
                    ?: jamRes?.asObj()

                val res = AppServices.ponorogoClient.send(
                    "/Tupoksi/ambilDataAktivitas",
                    buildJsonObject {
                        put("id_pegawai", targetId)
                        put("tgl_mulai", tglAwal)
                        put("tgl_akhir", tglAkhir)
                    },
                )
                var data = res.asArr().mapNotNull { it as? JsonObject }
                if (data.isEmpty()) {
                    val obj = res.asObj()
                    data = obj?.get("data")?.asArr()?.mapNotNull { it as? JsonObject } ?: emptyList()
                }
                if (data.isEmpty()) {
                    val log = AppServices.ponorogoClient.send(
                        "/logActivity/log",
                        buildJsonObject {
                            put("id_pegawai", targetId)
                            put("tgl_awal", tglAwal)
                            put("tgl_akhir", tglAkhir)
                        },
                    )
                    data = log.asArr().mapNotNull { it as? JsonObject }
                    if (data.isEmpty()) data = log.asObj()?.get("data")?.asArr()?.mapNotNull { it as? JsonObject } ?: emptyList()
                }
                if (currentRequest != requestId) return@launch
                rows = data
                status = "Memuat ${rows.size} aktivitas untuk ${targetPegawai.nama.ifBlank { targetId }} ($tglAwal s/d $tglAkhir)."
            } catch (e: Exception) {
                if (currentRequest == requestId) {
                    status = "Gagal: ${e.message}"
                    rows = emptyList()
                }
            } finally {
                if (currentRequest == requestId) loading = false
            }
        }
    }

    LaunchedEffect(profile.id, ctx.apiKey, targetPegawai.id) { if (ctx.apiKey.isNotBlank()) load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("History Produktivitas", fontWeight = FontWeight.Bold)
                PegawaiSearchField(
                    enabled = canSearchOthers,
                    selected = if (canSearchOthers) targetPegawai else ownPegawai,
                    own = ownPegawai,
                    onSelect = { targetPegawai = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DateField(tglAwal, { tglAwal = it }, label = "Dari", modifier = Modifier.width(150.dp))
                    DateField(tglAkhir, { tglAkhir = it }, label = "Sampai", modifier = Modifier.width(150.dp))
                    FilledTonalButton(onClick = { load() }, enabled = !loading) { Text(if (loading) "Memuat…" else "Muat") }
                }
                summary?.let { s ->
                    val total = s.str("total_aktivitas").ifBlank { s.str("total_jam") }
                    val disetujui = s.str("total_disetujui").ifBlank { s.str("disetujui") }
                    val batal = s.str("total_batal").ifBlank { s.str("ditolak").ifBlank { s.str("batal") } }
                    if (total.isNotBlank() || disetujui.isNotBlank() || batal.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            listOfNotNull(
                                total.takeIf { it.isNotBlank() }?.let { "Total jam: $it" },
                                disetujui.takeIf { it.isNotBlank() }?.let { "Disetujui: $it" },
                                batal.takeIf { it.isNotBlank() }?.let { "Batal/Ditolak: $it" },
                            ).forEach { item ->
                                AssistChip(onClick = {}, label = { Text(item, fontSize = 12.sp) })
                            }
                        }
                    }
                }
                status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (rows.isNotEmpty()) {
            Card(shape = RoundedCornerShape(16.dp)) {
                LazyColumn(Modifier.heightIn(max = 520.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows) { row ->
                        val jenis = row.str("jenis")
                        val tugas = row.str("tugas").ifBlank { row.str("nama_tugas").ifBlank { row.str("aktivitas") } }
                        val ket = row.str("keterangan").ifBlank { row.str("deskr_tupoksi") }
                        val judul = tugas.ifBlank { ket }
                        val mulai = row.str("tgl_mulai").ifBlank { row.str("jam_mulai").ifBlank { row.str("tanggal") } }
                        val selesai = row.str("tgl_selesai").ifBlank { row.str("jam_selesai") }
                        val waktu = listOf(mulai, selesai).filter { it.isNotBlank() && it != "null" }.joinToString(" → ")
                        val st = row.str("status")
                        val lamp = row.str("lampiran").ifBlank { row.str("foto").ifBlank { row.str("file_lampiran") } }
                        val hasLamp = lamp.isNotBlank() && lamp != "null" && !lamp.contains("no_image", true)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(judul.ifBlank { "Aktivitas" }, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                if (jenis.isNotBlank()) {
                                    Text(jenis, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (waktu.isNotBlank() || st.isNotBlank() || hasLamp) {
                                Text(
                                    listOfNotNull(
                                        waktu.takeIf { it.isNotBlank() },
                                        st.takeIf { it.isNotBlank() },
                                        if (hasLamp) "Lampiran foto" else null,
                                    ).joinToString(" • "),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (ket.isNotBlank() && ket != judul) {
                                Text(ket, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            if (hasLamp) PonorogoAttachmentPreview(lamp, "Lampiran aktivitas $judul")
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        } else if (!loading) {
            Card(shape = RoundedCornerShape(16.dp)) {
                Text("Belum ada data aktivitas pada rentang tanggal ini.", Modifier.padding(16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun PonorogoReviewScreen(profile: ServerProfile, page: PageDef) {
    val permissions = LocalAppPermissions.current
    val canSensitive = rememberCanSensitiveAction()
    val canReviewOthers = permissions.canUseFeature("search_review")
    val ctx by AppServices.serverContext.collectAsState()
    val scope = rememberCoroutineScope()
    val pon = AppServices.ponorogoProfile
    val ownPegawaiId = pon?.str("id_pegawai").orEmpty().ifBlank { pon?.str("id").orEmpty() }
    val ownNama = pon?.str("nama").orEmpty()
    val ownPegawai = remember(ownPegawaiId, pon?.str("nip"), ownNama, pon?.str("kode_unor")) {
        PegawaiSearchResult(
            id = ownPegawaiId,
            nip = pon?.str("nip").orEmpty(),
            nama = ownNama,
            instansi = pon?.str("nama_instansi").orEmpty(),
            kodeUnor = pon?.str("kode_unor").orEmpty().ifBlank { pon?.str("kode_instansi").orEmpty() },
        )
    }

    var targetPegawai by remember(ownPegawai) { mutableStateOf(PegawaiSearchResult("9d196ee2-e34c-11e8-bad3-28924a31c25f", "", "", "")) }
    var targetLoading by remember { mutableStateOf(true) }
    var requestId by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var activities by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var dashboard by remember { mutableStateOf<JsonObject?>(null) }
    var tglAwal by remember { mutableStateOf(daysAgoIso(7)) }
    var tglAkhir by remember { mutableStateOf(todayIso()) }
    var updatingId by remember { mutableStateOf<String?>(null) }
    var filterStatus by remember { mutableStateOf("ALL") } // ALL | PENDING | DISETUJUI | DITOLAK

    LaunchedEffect(ownPegawai.id, ownPegawai.nip) {
        targetLoading = true
        val supervisor = runCatching {
            withContext(Dispatchers.Default) {
                PegawaiSearchRepository.loadTable().findImmediateSupervisor(ownPegawai.id, ownPegawai.nip)
            }
        }.getOrNull()
        targetPegawai = supervisor ?: PegawaiSearchResult("9d196ee2-e34c-11e8-bad3-28924a31c25f", "", "", "")
        targetLoading = false
    }

    fun load() {
        val pid = targetPegawai.id.ifBlank { "9d196ee2-e34c-11e8-bad3-28924a31c25f" }
        if (ctx.apiKey.isBlank() || pid.isBlank()) { status = "Sesi/ID pegawai tidak tersedia."; return }
        val currentRequest = ++requestId
        loading = true
        status = null
        scope.launch {
            try {
                val payload = buildJsonObject {
                    put("id_pegawai", pid)
                    put("tgl_mulai", tglAwal)
                    put("tgl_akhir", tglAkhir)
                    targetPegawai.kodeUnor.ifBlank { pon?.str("kode_instansi").orEmpty() }
                        .takeIf { it.isNotBlank() }?.let { put("kode_instansi", it) }
                    pon?.str("message")?.takeIf { it.isNotBlank() }?.let { put("nomor", it) }
                }
                val actRes = runCatching { AppServices.ponorogoClient.send("/Tupoksi/get_aktifitas", payload) }.getOrNull()
                var list = actRes.asArr().mapNotNull { it as? JsonObject }
                if (list.isEmpty()) list = actRes.asObj()?.get("data")?.asArr()?.mapNotNull { it as? JsonObject } ?: emptyList()
                if (currentRequest != requestId) return@launch
                activities = list
                val dashRes = runCatching { AppServices.ponorogoClient.send("/Tupoksi/ambil_data_dashboard", payload) }.getOrNull()
                if (currentRequest != requestId) return@launch
                dashboard = dashRes.asObj()?.get("dashboard")?.asArr()?.firstOrNull()?.asObj() ?: dashRes.asObj()
                status = "Memuat ${activities.size} aktivitas untuk ${targetPegawai.nama.ifBlank { pid }} ($tglAwal s/d $tglAkhir)."
            } catch (e: Exception) {
                if (currentRequest == requestId) {
                    status = "Gagal: ${e.message}"
                    activities = emptyList()
                }
            } finally {
                if (currentRequest == requestId) loading = false
            }
        }
    }

    fun updateStatus(act: JsonObject, approve: Boolean) {
        if (!canSensitive) { status = "Aksi sensitif (setujui/tolak aktivitas) tidak diizinkan untuk akun ini."; return }
        val actId = act.str("id").ifBlank { act.str("id_aktifitas").ifBlank { act.str("id_log") } }
        if (actId.isBlank()) { status = "ID aktivitas tidak ditemukan."; return }
        updatingId = actId
        scope.launch {
            try {
                val payload = buildJsonObject {
                    put("id", actId)
                    put("id_aktifitas", actId)
                    put("status", if (approve) 1 else 2)
                    put("nama", act.str("nama").ifBlank { act.str("nama_pegawai").ifBlank { ownNama } })
                }
                val res = AppServices.ponorogoClient.send("/Tupoksi/updateStatus", payload).asObj()
                val ok = (res?.get("success") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
                if (ok) {
                    status = if (approve) "Aktivitas disetujui." else "Aktivitas ditolak."
                    activities = activities.map { row ->
                        val rid = row.str("id").ifBlank { row.str("id_aktifitas").ifBlank { row.str("id_log") } }
                        if (rid == actId) {
                            JsonObject(row.toMutableMap().apply { put("status", JsonPrimitive(if (approve) "DISETUJUI" else "DITOLAK")) })
                        } else row
                    }
                } else {
                    status = "Gagal update: ${res?.str("message") ?: res?.str("error") ?: "respons tidak dikenal"}"
                }
            } catch (e: Exception) {
                status = "Gagal update status: ${e.message}"
            } finally {
                updatingId = null
            }
        }
    }

    LaunchedEffect(profile.id, ctx.apiKey, targetPegawai.id, targetLoading) {
        if (!targetLoading && ctx.apiKey.isNotBlank()) load()
    }

    val filtered = remember(activities, filterStatus) {
        activities.filter { act ->
            val st = act.str("status").uppercase()
            when (filterStatus) {
                "PENDING" -> st.isBlank() || st.contains("BELUM") || st.contains("PENDING") || st.contains("PROSES")
                "DISETUJUI" -> st.contains("DISETUJUI") || st == "1" || st == "APPROVED"
                "DITOLAK" -> st.contains("DITOLAK") || st == "2" || st == "REJECTED"
                else -> true
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Review Produktivitas", fontWeight = FontWeight.Bold)
                Text(
                    "Tinjau dan perbarui status aktivitas pegawai yang ditugaskan kepada Anda.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PegawaiSearchField(
                    enabled = canReviewOthers,
                    selected = targetPegawai,
                    own = ownPegawai,
                    onSelect = { targetPegawai = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (targetLoading) Text("Memuat data hierarki pegawai...", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DateField(tglAwal, { tglAwal = it }, label = "Dari", modifier = Modifier.width(150.dp))
                    DateField(tglAkhir, { tglAkhir = it }, label = "Sampai", modifier = Modifier.width(150.dp))
                    FilledTonalButton(onClick = { load() }, enabled = !loading) { Text(if (loading) "Memuat…" else "Muat") }
                }
                HorizontalScrollRow(modifier = Modifier.fillMaxWidth(), itemSpacing = 8) {
                    listOf("ALL" to "Semua", "PENDING" to "Belum verifikasi", "DISETUJUI" to "Disetujui", "DITOLAK" to "Ditolak").forEach { (key, label) ->
                        FilterChip(selected = filterStatus == key, onClick = { filterStatus = key }, label = { Text(label, fontSize = 12.sp) })
                    }
                }
                dashboard?.let { d ->
                    val info = listOfNotNull(
                        d.str("disetujui").takeIf { it.isNotBlank() }?.let { "Disetujui: $it" },
                        d.str("ditolak").takeIf { it.isNotBlank() }?.let { "Ditolak: $it" },
                        d.str("belum_verifikasi").takeIf { it.isNotBlank() }?.let { "Belum verifikasi: $it" },
                        d.str("total").takeIf { it.isNotBlank() }?.let { "Total: $it" },
                    ).joinToString(" • ")
                    if (info.isNotBlank()) Text(info, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (filtered.isNotEmpty()) {
            Card(shape = RoundedCornerShape(16.dp)) {
                LazyColumn(Modifier.heightIn(max = 520.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(filtered.take(120)) { act ->
                        val actId = act.str("id").ifBlank { act.str("id_aktifitas").ifBlank { act.str("id_log") } }
                        val judul = act.str("tugas").ifBlank { act.str("nama_tugas").ifBlank { act.str("aktivitas").ifBlank { act.str("keterangan") } } }
                        val ket = act.str("keterangan").ifBlank { act.str("deskr_tupoksi") }
                        val st = act.str("status")
                        val jenis = act.str("jenis")
                        val mulai = act.str("tgl_mulai").ifBlank { act.str("tanggal") }
                        val selesai = act.str("tgl_selesai")
                        val waktu = listOf(mulai, selesai).filter { it.isNotBlank() && it != "null" }.joinToString(" → ")
                        val peg = act.str("nama").ifBlank { act.str("nama_pegawai") }
                        val lamp = act.str("lampiran").ifBlank { act.str("foto").ifBlank { act.str("file_lampiran") } }
                        val hasLamp = lamp.isNotBlank() && lamp != "null" && !lamp.contains("no_image", true)
                        val stUpper = st.uppercase()
                        val pending = st.isBlank() || stUpper.contains("BELUM") || stUpper.contains("PENDING") || stUpper.contains("PROSES")
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(judul.ifBlank { "Aktivitas" }, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                if (jenis.isNotBlank()) Text(jenis, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                listOfNotNull(
                                    peg.takeIf { it.isNotBlank() },
                                    waktu.takeIf { it.isNotBlank() },
                                    st.takeIf { it.isNotBlank() },
                                    if (hasLamp) "Lampiran foto" else null,
                                ).joinToString(" • "),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (ket.isNotBlank() && ket != judul) {
                                Text(ket, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                            if (hasLamp) PonorogoAttachmentPreview(lamp, "Lampiran aktivitas $judul")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                val busy = updatingId == actId
                                Button(onClick = { updateStatus(act, true) }, enabled = !busy && actId.isNotBlank() && canSensitive) { Text(if (busy) "…" else "Setujui", fontSize = 12.sp) }
                                OutlinedButton(onClick = { updateStatus(act, false) }, enabled = !busy && actId.isNotBlank() && canSensitive) { Text("Tolak", fontSize = 12.sp) }
                                if (!canSensitive) {
                                    Text("Izin aksi sensitif diperlukan untuk setujui/tolak.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!pending && st.isNotBlank()) {
                                    Text(st, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        } else if (!loading) {
            Card(shape = RoundedCornerShape(16.dp)) {
                Text("Belum ada aktivitas untuk ditinjau pada rentang/filter ini.", Modifier.padding(16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Shared helpers ──────────────────────────────────────────────────────────

@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isBlank() || value == "null") return
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(160.dp))
        Text(value, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SimpleListPage(
    profile: ServerProfile,
    page: PageDef,
    status: String?,
    loading: Boolean,
    rows: List<JsonObject>,
    onReload: () -> Unit,
    columns: (JsonObject) -> List<String>,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onReload, enabled = !loading) { Text(if (loading) "Memuat…" else "Muat Ulang") }
            status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (rows.isNotEmpty()) {
            Card(shape = RoundedCornerShape(16.dp)) {
                LazyColumn(Modifier.heightIn(max = 460.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(rows.take(100)) { row ->
                        val cols = columns(row)
                        Text(cols.filter { it.isNotBlank() }.joinToString("  •  "), fontSize = 12.sp)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointInfoPage(
    profile: ServerProfile,
    page: PageDef,
    title: String,
    description: String,
    endpoints: String,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Endpoint: $endpoints", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Halaman ini terhubung ke infrastruktur klien Ponorogo dan akan diperdalam ke paritas fungsional penuh.", fontSize = 12.sp)
            }
        }
    }
}
