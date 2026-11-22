@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package id.tetuko.app.ui.screens.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import id.tetuko.app.ui.components.VerticalScrollColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalDensity
import id.tetuko.app.service.AppServices
import id.tetuko.app.service.SavedPoint
import id.tetuko.app.service.SavedPointsCodec
import id.tetuko.app.ui.LocalAppPermissions
import id.tetuko.app.ui.components.PageHeaderCard
import id.tetuko.core.domain.model.AppRole
import id.tetuko.core.domain.model.PageDef
import id.tetuko.core.domain.model.ServerProfile
import id.tetuko.core.data.rpc.RpcObjects
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.pow

private const val MAX_SAVED_POINTS = 40

private data class MapPoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val saved: Boolean,
    val active: Boolean,
)

/**
 * Lokasi Absen: server locations plus locally saved points.
 *
 * Renders OpenStreetMap tiles with pan, pinch/gesture zoom, markers, geofence
 * circles, point selection, and saved-point activation. No paid map API key is
 * required; displayed tiles retain the OpenStreetMap attribution.
 */
@Composable
fun LokasiAbsenScreen(profile: ServerProfile, page: PageDef) {
    val permissions = LocalAppPermissions.current
    val canSensitive = permissions.role == AppRole.ADMIN ||
        permissions.allowSensitiveActions ||
        permissions.canUseFeature("sensitive_actions")
    val scope = rememberCoroutineScope()
    var serverPoints by remember { mutableStateOf<List<MapPoint>>(emptyList()) }
    var savedPoints by remember { mutableStateOf<List<SavedPoint>>(emptyList()) }
    var loadedStore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var draftName by remember { mutableStateOf("") }
    var draftLat by remember { mutableStateOf("") }
    var draftLng by remember { mutableStateOf("") }
    var draftRadius by remember { mutableStateOf("100") }
    var draftError by remember { mutableStateOf<String?>(null) }

    fun loadServer() {
        loading = true
        scope.launch {
            try {
                val el = AppServices.rpcClient.callResultElement(
                    RpcObjects.GET_LOKASI_ABSEN,
                    ctx = AppServices.serverContext.value,
                    profileId = profile.id,
                )
                serverPoints = el?.jsonArrayObjects().orEmpty().mapNotNull { obj ->
                    val ll = obj.str("latlong")
                    val coords = parseLatLong(ll) ?: return@mapNotNull null
                    MapPoint(
                        name = obj.str("lokasi").ifBlank { obj.str("data").ifBlank { "Lokasi server" } },
                        latitude = coords.first,
                        longitude = coords.second,
                        radiusMeters = obj.str("radius").toDoubleOrNull() ?: 0.0,
                        saved = false,
                        active = false,
                    )
                }
                message = "Ditemukan ${serverPoints.size} lokasi server."
            } catch (e: Exception) {
                message = "Gagal memuat lokasi: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(profile.id) {
        val raw = AppServices.pointsReader?.invoke(profile.id)
        savedPoints = runCatching { SavedPointsCodec.decode(raw) }.getOrDefault(emptyList())
        loadedStore = true
        if (AppServices.serverContext.value.apiKey.isNotBlank()) loadServer()
    }

    LaunchedEffect(savedPoints, loadedStore) {
        if (loadedStore) {
            runCatching { AppServices.pointsWriter?.invoke(profile.id, SavedPointsCodec.encode(savedPoints)) }
        }
    }

    val allPoints = buildList {
        addAll(serverPoints)
        savedPoints.forEach {
            add(
                MapPoint(
                    name = it.name,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    radiusMeters = it.radiusMeters,
                    saved = true,
                    active = it.active,
                ),
            )
        }
    }

    fun openEditor(prefill: Pair<Double, Double>? = null, existing: SavedPoint? = null) {
        if (!canSensitive) { message = "Aksi sensitif (kelola titik lokasi) tidak diizinkan untuk akun ini."; return }
        editingId = existing?.id
        draftName = existing?.name ?: "Titik ${savedPoints.size + 1}"
        draftLat = (existing?.latitude ?: prefill?.first ?: serverPoints.firstOrNull()?.latitude ?: -7.8667).toString()
        draftLng = (existing?.longitude ?: prefill?.second ?: serverPoints.firstOrNull()?.longitude ?: 111.4667).toString()
        draftRadius = (existing?.radiusMeters ?: 100.0).toInt().toString()
        draftError = null
        editorOpen = true
    }

    val activePoint = savedPoints.firstOrNull { it.active }

    fun saveDraft() {
        if (!canSensitive) { draftError = "Aksi sensitif (simpan titik lokasi) tidak diizinkan untuk akun ini."; return }
        val lat = draftLat.toDoubleOrNull()
        val lng = draftLng.toDoubleOrNull()
        val radius = draftRadius.toDoubleOrNull()
        when {
            draftName.isBlank() -> draftError = "Nama titik wajib diisi."
            lat == null || lat !in -90.0..90.0 -> draftError = "Latitude harus antara -90 dan 90."
            lng == null || lng !in -180.0..180.0 -> draftError = "Longitude harus antara -180 dan 180."
            radius == null || radius !in 10.0..5000.0 -> draftError = "Radius harus 10–5000 meter."
            editingId == null && savedPoints.size >= MAX_SAVED_POINTS ->
                draftError = "Maksimal $MAX_SAVED_POINTS titik tersimpan."
            else -> {
                val id = editingId
                savedPoints = if (id == null) {
                    savedPoints + SavedPoint(
                        id = "p${Clock.System.now().toEpochMilliseconds()}",
                        name = draftName.trim(),
                        latitude = lat!!,
                        longitude = lng!!,
                        radiusMeters = radius!!,
                        active = savedPoints.isEmpty(),
                    )
                } else {
                    savedPoints.map {
                        if (it.id == id) it.copy(name = draftName.trim(), latitude = lat!!, longitude = lng!!, radiusMeters = radius!!)
                        else it
                    }
                }
                message = if (id == null) "Titik “${draftName.trim()}” ditambahkan." else "Titik diperbarui."
                editorOpen = false
            }
        }
    }

    fun activate(point: SavedPoint) {
        if (!canSensitive) { message = "Aksi sensitif (aktifkan titik lokasi) tidak diizinkan untuk akun ini."; return }
        savedPoints = savedPoints.map { it.copy(active = it.id == point.id) }
        AppServices.updateServerContext(lastLatLong = "${point.latitude},${point.longitude}")
        message = "Titik aktif: ${point.name} (${point.latitude}, ${point.longitude})."
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Peta & Titik Lokasi Absen", fontWeight = FontWeight.Bold)
                        val summary = when {
                            loading -> "Memuat lokasi server…"
                            allPoints.isEmpty() -> "Belum ada titik. Muat lokasi server atau tambahkan titik lokal."
                            else -> "${serverPoints.size} titik server • ${savedPoints.size} titik tersimpan"
                        }
                        Text(
                            summary,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (activePoint != null) {
                            Text(
                                "Titik aktif presensi: ${activePoint.name} (${activePoint.latitude}, ${activePoint.longitude})",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        message?.let {
                            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedButton(onClick = { loadServer() }, enabled = !loading) {
                        Text(if (loading) "Memuat…" else "Muat Lokasi Server")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { openEditor() },
                        enabled = savedPoints.size < MAX_SAVED_POINTS && canSensitive,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Tambah titik absen lokal", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tambah Titik")
                    }
                }
                if (savedPoints.size >= MAX_SAVED_POINTS) {
                    Text(
                        "Batas $MAX_SAVED_POINTS titik tersimpan tercapai. Hapus titik lama untuk menambah baru.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (allPoints.isEmpty() && !loading) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Belum ada titik lokasi ditampilkan", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(
                                "Gunakan \"Muat Lokasi Server\" untuk mengambil lokasi terdaftar, atau klik peta / tombol \"Tambah Titik\" untuk menyimpan titik lokal.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                SketchMap(
                    points = allPoints,
                    onPick = { lat, lng -> openEditor(lat to lng) },
                )
                Text(
                    "Geser atau pinch untuk bergerak dan memperbesar peta. Ketuk peta untuk menambah titik, lalu gunakan \"Aktifkan\" pada titik tersimpan sebagai referensi presensi.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (savedPoints.isEmpty()) {
                    Text(
                        "Belum ada titik tersimpan lokal. Titik lokal dapat diaktifkan, diedit, dan dipakai untuk geofence/presensi.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Titik Tersimpan Lokal (${savedPoints.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(savedPoints, key = { it.id }) { point ->
                            val nearest = nearestServer(serverPoints, point)
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Place,
                                        contentDescription = if (point.active) "Titik aktif" else "Titik tersimpan",
                                        tint = if (point.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            point.name + if (point.active) "  •  AKTIF" else "",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            color = if (point.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            "Koordinat: ${point.latitude}, ${point.longitude} • Radius geofence: ${point.radiusMeters.toInt()} m",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        nearest?.let { (server, distance) ->
                                            val inside = distance <= point.radiusMeters
                                            Text(
                                                "Jarak ke lokasi server terdekat (${server.name}): ${distance.toInt()} m • ${if (inside) "dalam radius titik" else "di luar radius titik"}",
                                                fontSize = 11.sp,
                                                color = if (inside) Color(0xFF166534) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        } ?: if (serverPoints.isEmpty()) {
                                            Text(
                                                "Muat lokasi server untuk melihat perbandingan jarak/geofence.",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        } else {
                                            null
                                        }
                                    }
                                    TextButton(onClick = { activate(point) }, enabled = !point.active && canSensitive) { Text(if (point.active) "Aktif" else "Aktifkan") }
                                    TextButton(onClick = { openEditor(existing = point) }, enabled = canSensitive) { Text("Edit") }
                                    IconButton(onClick = {
                                        if (!canSensitive) { message = "Aksi sensitif (hapus titik lokasi) tidak diizinkan untuk akun ini."; return@IconButton }
                                        val wasActive = point.active
                                        savedPoints = savedPoints.filterNot { it.id == point.id }
                                        message = if (wasActive) "Titik aktif dihapus. Pilih/aktifkan titik lain bila perlu." else "Titik dihapus."
                                    }, enabled = canSensitive) { Icon(Icons.Default.Delete, contentDescription = "Hapus titik ${point.name}") }
                                }
                            }
                        }
                    }
                }

                if (serverPoints.isNotEmpty()) {
                    Text("Lokasi Server (${serverPoints.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(serverPoints) { loc ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(loc.name, fontSize = 13.sp)
                                    Text(
                                        "${loc.latitude}, ${loc.longitude}" +
                                            if (loc.radiusMeters > 0) " • radius server ${loc.radiusMeters.toInt()} m" else " • radius server tidak tersedia",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = {
                                    if (!canSensitive) { message = "Aksi sensitif (pakai koordinat lokasi) tidak diizinkan untuk akun ini."; return@TextButton }
                                    AppServices.updateServerContext(lastLatLong = "${loc.latitude},${loc.longitude}")
                                    message = "Koordinat ${loc.name} dipakai untuk presensi (last_latlong diperbarui)."
                                }) { Text("Pakai Koordinat") }
                            }
                        }
                    }
                } else if (!loading) {
                    Text("Belum ada lokasi server dimuat. Tekan \"Muat Lokasi Server\" jika sesi server aktif.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (editorOpen) {
        Dialog(onDismissRequest = { editorOpen = false }) {
            Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 460.dp)) {
                VerticalScrollColumn(
                    modifier = Modifier.padding(18.dp),
                    itemSpacing = 10,
                ) {
                    Text(if (editingId == null) "Tambah Titik Absen" else "Edit Titik Absen", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        label = { Text("Nama titik") },
                        placeholder = { Text("Contoh: Kantor BKD / Rumah") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = draftLat,
                            onValueChange = { draftLat = it },
                            label = { Text("Latitude") },
                            placeholder = { Text("-7.8667") },
                            supportingText = { Text("Rentang -90 sampai 90") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = draftLng,
                            onValueChange = { draftLng = it },
                            label = { Text("Longitude") },
                            placeholder = { Text("111.4667") },
                            supportingText = { Text("Rentang -180 sampai 180") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = draftRadius,
                        onValueChange = { draftRadius = it },
                        label = { Text("Radius geofence (meter)") },
                        supportingText = { Text("Nilai valid 10–5000 meter") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    draftError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { saveDraft() }, enabled = canSensitive) { Text("Simpan") }
                        TextButton(onClick = { editorOpen = false }) { Text("Batal") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SketchMap(points: List<MapPoint>, onPick: (Double, Double) -> Unit) {
    val initialCenter = remember(points) {
        if (points.isEmpty()) -7.87 to 111.466 else
            points.map { it.latitude }.average() to points.map { it.longitude }.average()
    }
    var center by remember(points) { mutableStateOf(initialCenter) }
    var zoom by remember(points) { mutableIntStateOf(if (points.size > 1) 15 else 16) }
    var viewport by remember { mutableStateOf(640f to 520f) }
    val loadedTiles = remember { mutableStateMapOf<String, ImageBitmap>() }
    val tileErrors = remember { mutableStateMapOf<String, Boolean>() }
    val marker = MaterialTheme.colorScheme.primary
    val savedMarker = MaterialTheme.colorScheme.tertiary
    val tileCoordinates = remember(center, zoom, viewport) {
        visibleOsmTiles(center.first, center.second, zoom, viewport.first, viewport.second)
    }

    LaunchedEffect(tileCoordinates) {
        coroutineScope {
            tileCoordinates.filter { tile ->
                val key = tile.key
                key !in loadedTiles && tileErrors[key] != true
            }.map { tile ->
                async(Dispatchers.IO) {
                    runCatching { OSM_TILE_CACHE.load(tile) }
                        .onSuccess { bitmap -> loadedTiles[tile.key] = bitmap }
                        .onFailure { tileErrors[tile.key] = true }
                }
            }.awaitAll()
        }
        if (loadedTiles.size > 180) {
            val visible = tileCoordinates.mapTo(mutableSetOf()) { it.key }
            loadedTiles.keys.filterNot { it in visible }.take(loadedTiles.size - 120).forEach(loadedTiles::remove)
            tileErrors.keys.filterNot { it in visible }.toList().forEach(tileErrors::remove)
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("OpenStreetMap", Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { zoom = (zoom - 1).coerceIn(11, 19) }, enabled = zoom > 11) {
                Icon(Icons.Filled.ZoomOut, "Perkecil peta")
            }
            Text("$zoom", fontSize = 11.sp)
            IconButton(onClick = { zoom = (zoom + 1).coerceIn(11, 19) }, enabled = zoom < 19) {
                Icon(Icons.Filled.ZoomIn, "Perbesar peta")
            }
            IconButton(onClick = {
                center = initialCenter
                zoom = if (points.size > 1) 15 else 16
            }) { Icon(Icons.Filled.RestartAlt, "Fit semua lokasi") }
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(Color(0xFFE7E3DC), RoundedCornerShape(8.dp))
                .pointerInput(center, zoom) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        val nextZoom = (zoom + kotlin.math.ln(gestureZoom.toDouble()).div(kotlin.math.ln(2.0)).toInt()).coerceIn(11, 19)
                        val world = OSM_TILE_SIZE * 2.0.pow(nextZoom.toDouble())
                        val centerPixel = geoToWorldPixel(center.first, center.second, nextZoom)
                        center = worldPixelToGeo(
                            centerPixel.first - pan.x,
                            centerPixel.second - pan.y,
                            nextZoom,
                        )
                        zoom = nextZoom
                    }
                }
                .pointerInput(center, zoom) {
                    detectTapGestures { offset ->
                        val pixel = geoToWorldPixel(center.first, center.second, zoom)
                        val picked = worldPixelToGeo(
                            pixel.first + offset.x - size.width / 2.0,
                            pixel.second + offset.y - size.height / 2.0,
                            zoom,
                        )
                        onPick(picked.first, picked.second)
                    }
                },
        ) {
            viewport = size.width to size.height
            val centerPixel = geoToWorldPixel(center.first, center.second, zoom)
            tileCoordinates.forEach { tile ->
                val bitmap = loadedTiles[tile.key] ?: return@forEach
                val left = (tile.x * OSM_TILE_SIZE - centerPixel.first + size.width / 2.0).toInt()
                val top = (tile.y * OSM_TILE_SIZE - centerPixel.second + size.height / 2.0).toInt()
                drawImage(bitmap, dstOffset = IntOffset(left, top), dstSize = IntSize(OSM_TILE_SIZE.toInt(), OSM_TILE_SIZE.toInt()))
            }

            val metersPerPixel = kotlin.math.cos(center.first * PI / 180.0) * 2 * PI * 6_378_137.0 / (OSM_TILE_SIZE * 2.0.pow(zoom.toDouble()))
            points.forEach { point ->
                val pixel = geoToWorldPixel(point.latitude, point.longitude, zoom)
                val x = (pixel.first - centerPixel.first + size.width / 2.0).toFloat()
                val y = (pixel.second - centerPixel.second + size.height / 2.0).toFloat()
                if (point.radiusMeters > 0) {
                    val radius = (point.radiusMeters / metersPerPixel).toFloat().coerceIn(4f, size.minDimension)
                    drawCircle((if (point.saved) savedMarker else marker).copy(alpha = 0.18f), radius, Offset(x, y))
                    drawCircle((if (point.saved) savedMarker else marker).copy(alpha = 0.75f), radius, Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                }
                drawCircle(Color.White, 9f, Offset(x, y))
                drawCircle(if (point.saved) savedMarker else marker, 6f, Offset(x, y))
                if (point.active) drawCircle(marker, 11f, Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            }
        }
        Text("© OpenStreetMap contributors · Ketuk peta untuk memilih titik", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val OSM_TILE_SIZE = 256

private data class OsmTile(val zoom: Int, val x: Int, val y: Int) {
    val key: String get() = "$zoom/$x/$y"
    val url: String get() = "https://tile.openstreetmap.org/$zoom/$x/$y.png"
}

private class OsmTileCache {
    private val client = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 12_000
            connectTimeoutMillis = 6_000
            socketTimeoutMillis = 12_000
        }
    }
    private val cache = mutableMapOf<String, Pair<ImageBitmap, Long>>()

    @org.jetbrains.compose.resources.ExperimentalResourceApi
    suspend fun load(tile: OsmTile): ImageBitmap {
        cache[tile.key]?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        val response = client.get(tile.url) {
            header(HttpHeaders.UserAgent, "TETUKO/1.0 (location map; contact: support@tetuko.app)")
            header(HttpHeaders.Accept, "image/png,image/*;q=0.9")
        }
        check(response.status.value == 200) { "Tile HTTP ${response.status.value}" }
        val bytes = response.body<ByteArray>()
        check(bytes.isNotEmpty()) { "Tile kosong" }
        val bitmap = bytes.decodeToImageBitmap()
        val maxAgeSeconds = response.headers[HttpHeaders.CacheControl]
            ?.let { Regex("max-age=(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }
            ?: 3600L
        cache[tile.key] = bitmap to (System.currentTimeMillis() + maxAgeSeconds.coerceAtLeast(60L) * 1000L)
        return bitmap
    }
}

private val OSM_TILE_CACHE = OsmTileCache()

private fun visibleOsmTiles(latitude: Double, longitude: Double, zoom: Int, width: Float, height: Float): List<OsmTile> {
    val center = geoToWorldPixel(latitude, longitude, zoom)
    val minX = floor((center.first - width / 2.0) / OSM_TILE_SIZE).toInt()
    val maxX = floor((center.first + width / 2.0) / OSM_TILE_SIZE).toInt()
    val minY = floor((center.second - height / 2.0) / OSM_TILE_SIZE).toInt()
    val maxY = floor((center.second + height / 2.0) / OSM_TILE_SIZE).toInt()
    val count = 2.0.pow(zoom.toDouble()).toInt()
    return buildList {
        for (x in minX..maxX) for (y in minY..maxY) {
            if (y !in 0 until count) continue
            add(OsmTile(zoom, ((x % count) + count) % count, y))
        }
    }.distinctBy { it.key }
}

private fun geoToWorldPixel(latitude: Double, longitude: Double, zoom: Int): Pair<Double, Double> {
    val world = OSM_TILE_SIZE * 2.0.pow(zoom.toDouble())
    val lat = latitude.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
    val x = (longitude + 180.0) / 360.0 * world
    val y = (1.0 - ln(tan(lat) + 1.0 / cos(lat)) / PI) / 2.0 * world
    return x to y
}

private fun worldPixelToGeo(x: Double, y: Double, zoom: Int): Pair<Double, Double> {
    val world = OSM_TILE_SIZE * 2.0.pow(zoom.toDouble())
    val longitude = x / world * 360.0 - 180.0
    val n = PI - 2.0 * PI * y / world
    val latitude = 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
    return latitude.coerceIn(-85.05112878, 85.05112878) to longitude
}

private fun parseLatLong(raw: String): Pair<Double, Double>? {
    if (raw.isBlank()) return null
    val parts = raw.replace(';', ',').split(',').map { it.trim() }
    if (parts.size < 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lng = parts[1].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return lat to lng
}

private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLng = (lng2 - lng1) * PI / 180.0
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

private fun nearestServer(server: List<MapPoint>, point: SavedPoint): Pair<MapPoint, Double>? =
    server.minByOrNull { haversineMeters(it.latitude, it.longitude, point.latitude, point.longitude) }
        ?.let { it to haversineMeters(it.latitude, it.longitude, point.latitude, point.longitude) }

private fun JsonElement?.jsonArrayObjects(): List<JsonObject> {
    val array: JsonArray = when (this) {
        is JsonArray -> this
        is JsonObject -> (this["result"] as? JsonArray) ?: (this["data"] as? JsonArray) ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return array.mapNotNull { it as? JsonObject }
}

private fun JsonObject.str(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull
        ?: this[key]?.toString()?.trim('"')?.takeIf { it != "null" } ?: ""
