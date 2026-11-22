@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package id.tetuko.app.ui.screens.pages

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.tetuko.app.service.AppServices
import id.tetuko.app.ui.LocalAppPermissions
import id.tetuko.app.ui.LocalAppState
import id.tetuko.app.ui.components.PageHeaderCard
import id.tetuko.core.data.web.AttendancePage
import id.tetuko.core.data.web.LeavePage
import id.tetuko.core.data.web.PortalAttendance
import id.tetuko.core.data.web.PortalDetails
import id.tetuko.core.data.web.PortalLeave
import id.tetuko.core.data.web.PortalProfile
import id.tetuko.core.data.web.WebPresensiException
import id.tetuko.core.domain.model.AppRole
import id.tetuko.core.domain.model.PageDef
import id.tetuko.core.domain.model.ServerProfile
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap

private enum class WebTab(val label: String) {
    KEHADIRAN("Kehadiran"),
    PERIZINAN("Perizinan"),
    PROFIL("Profil"),
}

private const val MAX_OCR_CAPTCHA_ATTEMPTS = 3

/**
 * Web e-Presensi portal (`/ep`) integration.
 *
 * Captcha is auto-solved when an OCR engine is available via the local proxy
 * (`/api/ocr`); otherwise it falls back to manual entry. Session cookies,
 * login, attendance paging, leave history and profile details follow
 * `src/lib/webPresensi.ts`.
 */
@Composable
fun WebPresensiScreen(profile: ServerProfile, page: PageDef) {
    val permissions = LocalAppPermissions.current
    val canSensitive = permissions.role == AppRole.ADMIN ||
        permissions.allowSensitiveActions ||
        permissions.canUseFeature("sensitive_actions")
    val client = AppServices.webPresensi
    val appState = LocalAppState.current
    val assignedCredential = remember(profile.id) { appState?.serverCredsFor(profile.id) }
    val epBase = profile.epBaseUrl.takeIf { it.isNotBlank() }
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(true) }
    var loggedIn by remember { mutableStateOf(false) }
    var nip by remember(profile.id) { mutableStateOf(assignedCredential?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var captchaText by remember { mutableStateOf("") }
    var captchaImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var captchaBytes by remember { mutableStateOf<ByteArray?>(null) }
    var captchaLoading by remember { mutableStateOf(false) }
    var solvingOcr by remember { mutableStateOf(false) }
    var loggingIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(WebTab.KEHADIRAN) }
    var attendance by remember { mutableStateOf<AttendancePage?>(null) }
    var leaves by remember { mutableStateOf<LeavePage?>(null) }
    var portalProfile by remember { mutableStateOf<PortalProfile?>(null) }
    var portalDetails by remember { mutableStateOf<PortalDetails?>(null) }
    var loadingData by remember { mutableStateOf(false) }
    var preciseCoord by remember { mutableStateOf<Pair<String, String>?>(null) } // (label, "lat, lng")
    var loadingPrecise by remember { mutableStateOf(false) }
    var savingPath by remember { mutableStateOf<String?>(null) }

    suspend fun trySolveCaptcha(auto: Boolean) {
        val bytes = captchaBytes ?: return
        solvingOcr = true
        try {
            val solved = runCatching { client.solveCaptcha(bytes) }.getOrNull()
            if (solved != null) {
                captchaText = solved.text
                info = if (solved.confident) {
                    "Captcha terisi otomatis (OCR). Periksa jika tampak keliru."
                } else {
                    "Captcha terisi dari OCR dengan keyakinan rendah. Periksa dan perbaiki sebelum login."
                }
                error = null
            } else {
                info = "OCR otomatis tidak tersedia. Silakan isi captcha secara manual."
            }
        } catch (_: Exception) {
            if (!auto) info = "Gagal menjalankan OCR. Silakan isi captcha secara manual."
        } finally {
            solvingOcr = false
        }
    }

    suspend fun refreshCaptcha(clearStatus: Boolean = true) {
        captchaLoading = true
        if (clearStatus) {
            error = null
            info = null
        }
        captchaText = ""
        var loadedImage = false
        var lastError: String? = null
        for (attempt in 1..MAX_OCR_CAPTCHA_ATTEMPTS) {
            val image = try {
                client.loadCaptcha(epBase)
            } catch (e: Exception) {
                lastError = e.message ?: "periksa koneksi/proxy"
                continue
            }
            captchaBytes = image.bytes
            captchaImage = runCatching { image.bytes.decodeToImageBitmap() }.getOrNull()
            if (captchaImage == null) {
                lastError = "Gambar captcha tidak dapat dibaca."
                continue
            }
            loadedImage = true
            val solved = runCatching { client.solveCaptcha(image.bytes) }.getOrNull()
            if (!solved?.text.isNullOrBlank()) {
                captchaText = solved?.text.orEmpty()
                info = if (solved?.confident == true) {
                    "OCR membaca captcha pada percobaan $attempt. Periksa jika tampak keliru."
                } else {
                    "OCR mengisi captcha pada percobaan $attempt dengan keyakinan rendah. Periksa sebelum login."
                }
                break
            }
        }
        if (!loadedImage) {
            captchaBytes = null
            captchaImage = null
            error = "Gagal memuat captcha: ${lastError ?: "periksa koneksi/proxy"}."
        } else if (captchaText.isBlank()) {
            info = "OCR tidak berhasil membaca captcha setelah $MAX_OCR_CAPTCHA_ATTEMPTS percobaan. Silakan isi secara manual."
        }
        captchaLoading = false
    }

    suspend fun handleSessionExpired(message: String = "Sesi Web Presensi berakhir. Silakan login kembali (captcha akan dimuat ulang).") {
        client.clearSessionScope()
        loggedIn = false
        attendance = null
        leaves = null
        portalProfile = null
        portalDetails = null
        loadingData = false
        error = null
        info = message
        refreshCaptcha(clearStatus = false)
    }

    suspend fun loadAttendance(pageNo: Int = 1) {
        loadingData = true
        try {
            val result = client.attendance(epBase, pageNo)
            attendance = result
            info = "${result.rows.size} baris • halaman ${result.page}/${result.totalPages}"
            error = null
        } catch (e: WebPresensiException) {
            if (e.sessionExpired) {
                handleSessionExpired()
            } else {
                error = e.message ?: "Gagal memuat data kehadiran."
            }
        } catch (e: Exception) {
            error = "Gagal memuat data kehadiran: ${e.message ?: "periksa koneksi"}."
        } finally {
            loadingData = false
        }
    }

    suspend fun loadLeaves(pageNo: Int = 1) {
        loadingData = true
        try {
            leaves = client.leave(epBase, pageNo)
            info = "${leaves?.rows?.size ?: 0} baris perizinan • halaman ${leaves?.page ?: pageNo}"
            error = null
        } catch (e: WebPresensiException) {
            if (e.sessionExpired) {
                handleSessionExpired()
            } else {
                error = e.message ?: "Gagal memuat data perizinan."
            }
        } catch (e: Exception) {
            error = "Gagal memuat data perizinan: ${e.message ?: "periksa koneksi"}."
        } finally {
            loadingData = false
        }
    }

    suspend fun loadProfile() {
        try {
            portalProfile = client.profile(epBase)
        } catch (e: WebPresensiException) {
            if (e.sessionExpired) { handleSessionExpired(); return }
        } catch (_: Exception) {
            // Best-effort: profil ringkas opsional
        }
        try {
            portalDetails = client.profileDetails(epBase)
        } catch (e: WebPresensiException) {
            if (e.sessionExpired) { handleSessionExpired(); return }
        } catch (_: Exception) {
            // Best-effort: detail pegawai opsional
        }
    }

    fun savePortalFile(path: String, suggested: String) {
        if (!canSensitive) {
            error = "Aksi sensitif (unduh/simpan berkas portal) tidak diizinkan untuk akun ini."
            info = null
            return
        }
        scope.launch {
            savingPath = path
            error = null
            info = null
            try {
                val bytes = client.fetch(path, epBase)
                val saver = AppServices.binaryExporter
                val name = suggested.ifBlank { path.substringAfterLast('/').ifBlank { "lampiran-web" } }
                if (saver == null) {
                    info = "Berkas diunduh ($name, ${bytes.size} byte), tetapi fitur simpan tidak tersedia di platform ini."
                } else {
                    val ok = saver.invoke(name, bytes)
                    if (ok) {
                        info = "Berkas disimpan: $name"
                    } else {
                        info = "Penyimpanan dibatalkan. Berkas tetap berhasil diunduh ($name)."
                    }
                }
            } catch (e: WebPresensiException) {
                if (e.sessionExpired) {
                    handleSessionExpired("Sesi Web Presensi berakhir saat mengunduh berkas. Silakan login kembali.")
                } else {
                    error = "Gagal mengunduh/menyimpan berkas: ${e.message ?: "terjadi kesalahan"}."
                }
            } catch (e: Exception) {
                error = "Gagal mengunduh/menyimpan berkas: ${e.message ?: "periksa koneksi"}."
            } finally {
                savingPath = null
            }
        }
    }

    LaunchedEffect(profile.id, nip) {
        checking = true
        val scopeNip = nip.trim().ifBlank { assignedCredential?.username.orEmpty().trim() }
        client.selectSessionScope(scopeNip.takeIf { it.isNotBlank() }?.let { "${profile.id}:$it" } ?: profile.id)
        info = null
        error = null
        // Probe an authenticated page: if it renders the login form, no session exists.
        try {
            val result = client.attendance(epBase, 1)
            attendance = result
            loggedIn = true
            loadProfile()
        } catch (e: WebPresensiException) {
            if (e.sessionExpired) {
                handleSessionExpired("Sesi tersimpan tidak lagi valid. Silakan login kembali.")
            } else {
                error = e.message ?: "Gagal memeriksa sesi Web Presensi."
            }
        } catch (e: Exception) {
            info = "Tidak dapat memeriksa sesi otomatis (${e.message ?: "gangguan koneksi"}). Silakan login jika diperlukan."
        } finally {
            checking = false
            if (!loggedIn) refreshCaptcha(clearStatus = false)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeaderCard(profile, page)

        if (checking) {
            Card(shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Memeriksa sesi Web Presensi…", fontSize = 13.sp)
                }
            }
            return@Column
        }

        if (!loggedIn) {
            LoginCard(
                profile = profile,
                nip = nip,
                password = password,
                captchaText = captchaText,
                captchaImage = captchaImage,
                captchaLoading = captchaLoading,
                solvingOcr = solvingOcr,
                loggingIn = loggingIn,
                error = error,
                info = info,
                onNip = { nip = it; if (error != null) error = null },
                onPassword = { password = it; if (error != null) error = null },
                onCaptcha = { captchaText = it; if (error != null) error = null },
                onRefresh = { scope.launch { refreshCaptcha() } },
                onSolveOcr = { scope.launch { trySolveCaptcha(auto = false) } },
                onLogin = {
                    if (!canSensitive) {
                        error = "Aksi sensitif (login portal Web Presensi) tidak diizinkan untuk akun ini."
                        return@LoginCard
                    }
                    val cleanNip = nip.trim()
                    when {
                        cleanNip.isBlank() -> error = "NIP / email wajib diisi."
                        password.isBlank() -> error = "Password wajib diisi."
                        captchaText.isBlank() -> error = "Captcha wajib diisi. Jika OCR tidak tersedia, isi manual."
                        else -> scope.launch {
                            loggingIn = true
                            error = null
                            info = null
                            try {
                                client.selectSessionScope("${profile.id}:$cleanNip")
                                client.login(epBase, cleanNip, password, captchaText.trim())
                                password = ""
                                captchaText = ""
                                loggedIn = true
                                info = "Login Web Presensi berhasil."
                                loadAttendance(1)
                                loadProfile()
                            } catch (e: WebPresensiException) {
                                error = e.message ?: "Login gagal. Periksa kredensial dan captcha."
                                if (e.needsNewCaptcha) {
                                    captchaText = ""
                                    info = "Captcha perlu dimuat ulang. Silakan coba lagi dengan captcha baru."
                                    refreshCaptcha(clearStatus = false)
                                }
                            } catch (e: Exception) {
                                error = "Login gagal: ${e.message ?: "periksa koneksi/proxy"}."
                            } finally {
                                loggingIn = false
                            }
                        }
                    }
                },
            )
            return@Column
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(portalProfile?.name?.ifBlank { "Web Presensi" } ?: "Web Presensi", fontWeight = FontWeight.Bold)
                        val sub = listOfNotNull(
                            portalProfile?.nip?.takeIf { it.isNotBlank() }?.let { "NIP $it" },
                            portalProfile?.imei?.let { "IMEI $it" },
                        ).joinToString(" • ")
                        if (sub.isNotBlank()) {
                            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching { client.logout(epBase) }
                                runCatching { client.clearSessionScope() }
                                loggedIn = false
                                attendance = null
                                leaves = null
                                portalProfile = null
                                portalDetails = null
                                error = null
                                info = "Anda telah keluar dari Web Presensi."
                                refreshCaptcha(clearStatus = false)
                            }
                        },
                    ) { Text("Keluar") }
                }
                if (tab == WebTab.KEHADIRAN) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { scope.launch { error = null; info = null; loadAttendance(1) } }, enabled = !loadingData) {
                            Text(if (loadingData) "Memuat…" else "Muat Ulang")
                        }
                        Pager(
                            page = attendance?.page ?: 1,
                            total = attendance?.totalPages ?: 1,
                            enabled = !loadingData,
                            onPrev = { scope.launch { loadAttendance((attendance?.page ?: 1) - 1) } },
                            onNext = { scope.launch { loadAttendance((attendance?.page ?: 1) + 1) } },
                        )
                    }
                } else if (tab == WebTab.PERIZINAN) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { scope.launch { error = null; info = null; loadLeaves(1) } }, enabled = !loadingData) {
                            Text(if (loadingData) "Memuat…" else "Muat Perizinan")
                        }
                        Pager(
                            page = leaves?.page ?: 1,
                            total = leaves?.totalPages ?: 1,
                            enabled = !loadingData,
                            onPrev = { scope.launch { loadLeaves((leaves?.page ?: 1) - 1) } },
                            onNext = { scope.launch { loadLeaves((leaves?.page ?: 1) + 1) } },
                        )
                    }
                }
            }
        }

        TabRow(selectedTabIndex = tab.ordinal) {
            WebTab.entries.forEach { item ->
                Tab(
                    selected = tab == item,
                    onClick = {
                        if (tab != item) {
                            tab = item
                            error = null
                            info = null
                        }
                    },
                    text = { Text(item.label) },
                )
            }
        }

        error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        info?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        when (tab) {
            WebTab.KEHADIRAN -> AttendanceTable(
                rows = attendance?.rows.orEmpty(),
                loading = loadingData,
                loaded = attendance != null,
                onPrecise = { row ->
                    val idMap = row.idMap ?: return@AttendanceTable
                    scope.launch {
                        loadingPrecise = true
                        error = null
                        try {
                            val (la, ln) = client.latLong(idMap, epBase)
                            preciseCoord = if (la != null && ln != null) {
                                "${row.createdAt.ifBlank { "Kehadiran" }} • ${row.checkType}" to "$la, $ln"
                            } else {
                                (row.createdAt.ifBlank { "Kehadiran" }) to "Koordinat presisi tidak tersedia untuk entri ini."
                            }
                        } catch (e: WebPresensiException) {
                            if (e.sessionExpired) {
                                handleSessionExpired()
                            } else {
                                preciseCoord = (row.createdAt.ifBlank { "Kehadiran" }) to (e.message ?: "Gagal memuat koordinat presisi.")
                            }
                        } catch (e: Exception) {
                            preciseCoord = (row.createdAt.ifBlank { "Kehadiran" }) to "Gagal memuat koordinat presisi: ${e.message ?: "periksa koneksi"}."
                        } finally {
                            loadingPrecise = false
                        }
                    }
                },
            )
            WebTab.PERIZINAN -> LeaveTable(
                rows = leaves?.rows.orEmpty(),
                loading = loadingData,
                loaded = leaves != null,
                savingPath = savingPath,
                onSave = { path, name -> savePortalFile(path, name) },
            )
            WebTab.PROFIL -> ProfilePanel(
                profile = portalProfile,
                details = portalDetails,
                savingPath = savingPath,
                onSave = { path, name -> savePortalFile(path, name) },
            )
        }

        preciseCoord?.let { (label, coord) ->
            AlertDialog(
                onDismissRequest = { preciseCoord = null },
                confirmButton = { TextButton(onClick = { preciseCoord = null }) { Text("Tutup") } },
                title = { Text("Koordinat Presisi") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (loadingPrecise) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(coord, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun LoginCard(
    profile: ServerProfile,
    nip: String,
    password: String,
    captchaText: String,
    captchaImage: ImageBitmap?,
    captchaLoading: Boolean,
    solvingOcr: Boolean,
    loggingIn: Boolean,
    error: String?,
    info: String?,
    onNip: (String) -> Unit,
    onPassword: (String) -> Unit,
    onCaptcha: (String) -> Unit,
    onRefresh: () -> Unit,
    onSolveOcr: () -> Unit,
    onLogin: () -> Unit,
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Masuk ke Web e-Presensi", fontWeight = FontWeight.Bold)
            Text(
                "Portal ${profile.epBaseUrl.ifBlank { "e-Presensi" }} diakses lewat proxy lokal /ep. " +
                    "Captcha akan dicoba diisi otomatis jika OCR tersedia; jika tidak, isi manual lalu login.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            info?.takeIf { it.isNotBlank() }?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) {
                    Text(it, Modifier.padding(10.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            OutlinedTextField(
                value = nip,
                onValueChange = onNip,
                label = { Text("NIP / Email") },
                singleLine = true,
                enabled = !loggingIn,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPassword,
                label = { Text("Password") },
                singleLine = true,
                enabled = !loggingIn,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Captcha: isi sesuai gambar. Gunakan tombol OCR (jika tersedia) atau muat ulang bila sulit dibaca.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .width(180.dp)
                        .height(52.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        captchaLoading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        captchaImage != null -> Image(
                            bitmap = captchaImage,
                            contentDescription = "Gambar captcha Web Presensi",
                            modifier = Modifier.fillMaxSize().padding(4.dp),
                        )
                        else -> Text("Captcha belum tersedia", fontSize = 11.sp)
                    }
                }
                IconButton(onClick = onRefresh, enabled = !captchaLoading && !loggingIn) {
                    Icon(Icons.Default.Refresh, contentDescription = "Muat ulang captcha")
                }
                OutlinedButton(
                    onClick = onSolveOcr,
                    enabled = !captchaLoading && !solvingOcr && !loggingIn && captchaImage != null,
                ) {
                    if (solvingOcr) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Text("OCR")
                }
                OutlinedTextField(
                    value = captchaText,
                    onValueChange = onCaptcha,
                    label = { Text(if (solvingOcr) "Membaca OCR…" else "Captcha") },
                    singleLine = true,
                    enabled = !loggingIn && !solvingOcr,
                    modifier = Modifier.width(160.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val canSensitive = LocalAppPermissions.current.let { p ->
                    p.role == AppRole.ADMIN || p.allowSensitiveActions || p.canUseFeature("sensitive_actions")
                }
                Button(onClick = onLogin, enabled = !loggingIn && !captchaLoading && canSensitive) {
                    Text(if (loggingIn) "Memproses login…" else "Login Web Presensi")
                }
                if (!canSensitive) {
                    Text("Aksi login portal dinonaktifkan: izin aksi sensitif tidak diberikan.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun Pager(page: Int, total: Int, enabled: Boolean, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onPrev, enabled = enabled && page > 1) { Text("Sebelumnya") }
        Text("$page / ${total.coerceAtLeast(1)}", fontSize = 12.sp)
        OutlinedButton(onClick = onNext, enabled = enabled && page < total) { Text("Berikutnya") }
    }
}

private val ATTENDANCE_COLUMNS = listOf("Waktu" to 150, "Tipe" to 70, "Jarak" to 70, "Lokasi" to 260, "Approval" to 140)

@Composable
private fun AttendanceTable(
    rows: List<PortalAttendance>,
    loading: Boolean,
    loaded: Boolean,
    onPrecise: (PortalAttendance) -> Unit = {},
) {
    if (loading && rows.isEmpty()) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Memuat data kehadiran…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    if (rows.isEmpty()) {
        EmptyPortalState(
            if (loaded) "Belum ada data kehadiran untuk ditampilkan."
            else "Data kehadiran belum dimuat. Tekan “Muat Ulang” untuk mencoba."
        )
        return
    }
    Column(Modifier.fillMaxWidth()) {
        TableHeader(ATTENDANCE_COLUMNS)
        Text(
            "Ketuk baris yang memiliki lokasi untuk memuat koordinat presisi.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        )
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(rows) { row ->
                val canPrecise = !row.idMap.isNullOrBlank()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .let { m -> if (canPrecise) m.clickable { onPrecise(row) } else m },
                ) {
                    Cell(row.createdAt, 150)
                    Cell(row.checkType, 70)
                    Cell(row.distance, 70)
                    Cell(
                        listOfNotNull(
                            row.attendanceAddress.ifBlank { null },
                            row.latitude?.let { la -> row.longitude?.let { lo -> "$la, $lo" } },
                            if (canPrecise) "ketuk: koordinat presisi" else null,
                        ).joinToString(" • "),
                        260,
                    )
                    Cell(
                        when (row.approved) {
                            true -> "Disetujui${row.approvalTime.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()}"
                            false -> "Ditolak"
                            null -> row.wfh.ifBlank { "—" }
                        },
                        140,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        if (loading) Text("Memuat data…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val LEAVE_COLUMNS = listOf("Tanggal" to 120, "Jenis" to 160, "Alasan" to 260, "Status" to 130)

@Composable
private fun LeaveTable(
    rows: List<PortalLeave>,
    loading: Boolean,
    loaded: Boolean,
    savingPath: String? = null,
    onSave: (path: String, name: String) -> Unit = { _, _ -> },
) {
    if (loading && rows.isEmpty()) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Memuat data perizinan…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    if (rows.isEmpty()) {
        EmptyPortalState(
            if (loaded) "Belum ada data perizinan untuk ditampilkan."
            else "Data perizinan belum dimuat. Tekan “Muat Perizinan” untuk mengambil riwayat."
        )
        return
    }
    Column(Modifier.fillMaxWidth()) {
        TableHeader(LEAVE_COLUMNS + ("Lampiran" to 120))
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(rows) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cell(row.date, 120)
                    Cell(listOf(row.type, row.category).filter { it.isNotBlank() }.joinToString(" • "), 160)
                    Cell(row.reason.ifBlank { row.note.ifBlank { "—" } }, 260)
                    Cell(
                        when (row.approved) {
                            true -> "Disetujui"
                            false -> "Ditolak"
                            null -> "Menunggu"
                        },
                        130,
                    )
                    val fp = row.filePath
                    if (!fp.isNullOrBlank()) {
                        val busy = savingPath == fp
                        OutlinedButton(
                            onClick = {
                                val name = fp.substringAfterLast('/').ifBlank { "lampiran-izin" }
                                onSave(fp, name)
                            },
                            enabled = !busy && savingPath == null,
                            modifier = Modifier.width(120.dp).padding(horizontal = 4.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(if (busy) "Menyimpan…" else "Simpan", fontSize = 11.sp)
                        }
                    } else {
                        Text("—", fontSize = 12.sp, modifier = Modifier.width(120.dp).padding(horizontal = 6.dp))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        if (loading) Text("Memuat data…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TableHeader(columns: List<Pair<String, Int>>) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp),
    ) {
        columns.forEach { (label, width) ->
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(width.dp).padding(horizontal = 6.dp))
        }
    }
}

@Composable
private fun Cell(value: String, width: Int) {
    Text(
        value.ifBlank { "—" },
        fontSize = 12.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width.dp).padding(horizontal = 6.dp),
    )
}

@Composable
private fun ProfilePanel(
    profile: PortalProfile?,
    details: PortalDetails?,
    savingPath: String? = null,
    onSave: (path: String, name: String) -> Unit = { _, _ -> },
) {
    val detailMap = details?.profile.orEmpty()
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Profil Portal", fontWeight = FontWeight.Bold)
            if (profile == null && detailMap.isEmpty() && details?.photoPath.isNullOrBlank()) {
                Text(
                    "Detail pegawai belum tersedia. Coba muat ulang halaman atau periksa sesi/koneksi.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            profile?.name?.takeIf { it.isNotBlank() }?.let { Text(it, fontWeight = FontWeight.SemiBold) }
            profile?.nip?.takeIf { it.isNotBlank() }?.let { Text("NIP $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val imeiText = profile?.imei?.takeIf { it.isNotBlank() } ?: detailMap.entries.firstOrNull { it.key.contains("IMEI", true) }?.value
            imeiText?.takeIf { it.isNotBlank() }?.let { Text("IMEI $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            details?.employeeId?.takeIf { it.isNotBlank() }?.let { Text("ID Pegawai $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            val photoPath = details?.photoPath
            if (!photoPath.isNullOrBlank()) {
                val busy = savingPath == photoPath
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Foto profil tersedia.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        onClick = { onSave(photoPath, photoPath.substringAfterLast('/').ifBlank { "foto-profil.jpg" }) },
                        enabled = !busy && savingPath == null,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text(if (busy) "Menyimpan…" else "Simpan Foto", fontSize = 12.sp) }
                }
            }

            HorizontalDivider()
            if (detailMap.isEmpty()) {
                Text("Biodata lengkap tidak tersedia untuk akun ini.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(detailMap.entries.toList()) { (label, value) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(180.dp))
                            Text(value.ifBlank { "—" }, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPortalState(message: String) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Text(message, Modifier.padding(18.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
