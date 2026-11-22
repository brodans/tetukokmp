@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package id.tetuko.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.tetuko.app.service.AppServices
import id.tetuko.app.service.AuthService
import id.tetuko.app.ui.AppLoginResult
import id.tetuko.core.common.AppIdentity
import id.tetuko.core.domain.model.AttendanceMode
import id.tetuko.core.domain.model.DefaultProfiles
import id.tetuko.core.domain.model.LocalServerCredential
import id.tetuko.core.domain.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.decodeToImageBitmap
import tetuko.composeapp.generated.resources.Res
import tetuko.composeapp.generated.resources.tetuko_icon

@Composable
fun MasukScreen(onLogin: (String, String) -> AppLoginResult) {
    val store = AppServices.localAccountStore
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempts by remember { mutableStateOf(0) }
    var cooldownUntil by remember { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val sourceLabel = remember(store) { store?.accountSourceLabel() }
    val sourceError = remember(store) { store?.accountSourceError() }

    LaunchedEffect(Unit) {
        val (a, c) = store?.getLockState() ?: (0 to 0L)
        attempts = a
        cooldownUntil = c
    }
    LaunchedEffect(cooldownUntil) {
        if (cooldownUntil <= 0L) return@LaunchedEffect
        while (Clock.System.now().toEpochMilliseconds() < cooldownUntil) {
            kotlinx.coroutines.delay(1000)
            nowMs = Clock.System.now().toEpochMilliseconds()
        }
        attempts = 0
        cooldownUntil = 0L
        runCatching { store?.setLockState(0, 0L) }
    }

    val locked = cooldownUntil > nowMs
    val remainingSec = if (locked) ((cooldownUntil - nowMs) / 1000).coerceAtLeast(0) else 0L
    val fmtTime = { s: Long -> "${(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}" }
    val maxAttempts = 5
    val cooldownMs = 5 * 60 * 1000L

    fun submit() {
        if (busy || locked) return
        val u = username.trim().lowercase().replace(Regex("\\s+"), "")
        if (u.isBlank()) { error = "ID pengguna wajib diisi."; return }
        if (password.isBlank()) { error = "Kata sandi wajib diisi."; return }
        busy = true
        error = null
        scope.launch {
            val result = runCatching { onLogin(u, password) }.getOrElse {
                AppLoginResult(false, it.message ?: "Gagal memproses login.")
            }
            if (result.ok) {
                runCatching { store?.setLockState(0, 0L) }
                attempts = 0
                cooldownUntil = 0L
            } else if (result.credentialFailure) {
                val next = attempts + 1
                attempts = next
                if (next >= maxAttempts) {
                    cooldownUntil = Clock.System.now().toEpochMilliseconds() + cooldownMs
                    runCatching { store?.setLockState(next, cooldownUntil) }
                    error = "Terlalu banyak percobaan gagal. Sistem dikunci 5 menit."
                } else {
                    runCatching { store?.setLockState(next, 0L) }
                    error = "${result.message ?: "Kredensial salah."} ${maxAttempts - next} percobaan tersisa."
                }
            } else {
                error = result.message ?: "Gagal masuk. Periksa konfigurasi atau koneksi."
            }
            busy = false
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF0B1120)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.92f)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    modifier = Modifier.size(80.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1E293B)).border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp)).padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(Res.drawable.tetuko_icon),
                        contentDescription = "Logo TETUKO",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(AppIdentity.APP_NAME, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Text("MASUK KE APLIKASI", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Gunakan akun aplikasi (admin/user) yang dikonfigurasi untuk TETUKO.",
                    color = Color(0xFF94A3B8), fontSize = 11.sp,
                )
                if (store == null) {
                    Text(
                        "Adapter akun tidak tersedia di platform ini; gerbang masuk menggunakan mode kompatibilitas.",
                        color = Color(0xFFFDE68A), fontSize = 11.sp,
                    )
                } else {
                    if (!sourceError.isNullOrBlank()) {
                        Surface(color = Color(0xFF7F1D1D).copy(alpha = 0.35f), shape = RoundedCornerShape(10.dp)) {
                            Text(
                                sourceError,
                                modifier = Modifier.padding(10.dp),
                                color = Color(0xFFFECACA),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                if (locked) {
                    Surface(color = Color(0xFF7F1D1D).copy(alpha = 0.4f), shape = RoundedCornerShape(10.dp)) {
                        Text(
                            "Akses terkunci sementara. Coba lagi dalam ${fmtTime(remainingSec)}.",
                            modifier = Modifier.padding(10.dp), color = Color(0xFFFECACA), fontSize = 12.sp,
                        )
                    }
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; if (error != null) error = null },
                    label = { Text("ID Pengguna") },
                    singleLine = true,
                    enabled = !locked && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF3B82F6), unfocusedBorderColor = Color(0xFF334155),
                        focusedLabelColor = Color(0xFF93C5FD), unfocusedLabelColor = Color(0xFF94A3B8), cursorColor = Color.White
                    )
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; if (error != null) error = null },
                    label = { Text("Kata Sandi") },
                    singleLine = true,
                    enabled = !locked && !busy,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }, enabled = !locked) {
                            Text(if (showPassword) "Sembunyikan" else "Lihat", color = Color(0xFF93C5FD), fontSize = 10.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF3B82F6), unfocusedBorderColor = Color(0xFF334155),
                        focusedLabelColor = Color(0xFF93C5FD), unfocusedLabelColor = Color(0xFF94A3B8), cursorColor = Color.White
                    )
                )
                error?.let { Text(it, color = Color(0xFFFCA5A5), fontSize = 12.sp) }
                Button(
                    onClick = { submit() },
                    enabled = !locked && !busy && username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text(if (locked) "Akses Tertutup" else "Masuk ke Sistem", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ServerHubScreen(onSelectProfile: (ServerProfile) -> Unit, onLogoutApp: () -> Unit, isDark: Boolean, allowedProfileIds: Set<String>? = null, canManageAccounts: Boolean = false, currentAccountId: String? = null) {
    val profiles = DefaultProfiles.all.filter { allowedProfileIds == null || it.id in allowedProfileIds }
    var hubTab by remember { mutableIntStateOf(0) }
    var showAccountManagement by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 840.dp
            val actionColumns = if (maxWidth < 480.dp) 1 else 2
            val heading: @Composable ColumnScope.() -> Unit = {
                Text(if (hubTab == 0) "Pilih Server Presensi" else "Manajemen Preset", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("TETUKO KMP • ${if (hubTab == 0) "Pilih profil server untuk melanjutkan" else "Kelola penjadwal lintas profil"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            val actions: @Composable FlowRowScope.() -> Unit = {
                if (canManageAccounts) {
                    OutlinedButton(onClick = { showAccountManagement = true }) {
                        Icon(Icons.Filled.Groups, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Manajemen Akun")
                    }
                }
                if (AppServices.canControlAutostart) {
                    OutlinedButton(onClick = { AppServices.setAutostartEnabled(!AppServices.isAutostartEnabled()) }) {
                        Icon(if (AppServices.isAutostartEnabled()) Icons.Filled.CheckCircle else Icons.Filled.PowerSettingsNew, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(if (AppServices.isAutostartEnabled()) "Autostart ON" else "Autostart OFF")
                    }
                }
                OutlinedButton(onClick = onLogoutApp) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("Keluar Aplikasi")
                }
            }
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(content = heading)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        maxItemsInEachRow = actionColumns,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = actions,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f), content = heading)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TabRow(selectedTabIndex = hubTab) {
            Tab(selected = hubTab == 0, onClick = { hubTab = 0 }, text = { Text("Server") })
            Tab(selected = hubTab == 1, onClick = { hubTab = 1 }, text = { Text("Preset Otomasi") })
        }
        Spacer(Modifier.height(12.dp))
        if (hubTab == 1) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { ServerHubAutomationPanel(profiles) }
            }
            return@Column
        }
        if (profiles.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tidak ada server yang diizinkan", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Akses ke profil server dibatasi oleh izin saat ini. Hubungi administrator jika ini tidak sesuai, atau keluar aplikasi untuk mencoba akun lain.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(profiles) { profile ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onSelectProfile(profile) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2563EB).copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.Dns, contentDescription = null, tint = Color(0xFF2563EB)) }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(profile.nama, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            val modeLabel = if (profile.mode == AttendanceMode.PONOROGO_ABSENSI) "Mode: Ponorogo Absensi" else "Mode: BKD RPC"
                            Text(modeLabel, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                        Icon(Icons.Filled.ChevronRight, contentDescription = "Buka ${profile.label}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (showAccountManagement) {
        AccountManagementDialog(
            currentAccountId = currentAccountId,
            isAdmin = canManageAccounts,
            onDismiss = { showAccountManagement = false },
        )
    }
}

@Composable
private fun ServerHubAutomationPanel(profiles: List<ServerProfile>) {
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(kotlinx.datetime.Clock.System.now().toEpochMilliseconds()) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Manajemen Preset Global", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Switch(
                        checked = AppServices.isAutomationSchedulerEnabled(),
                        onCheckedChange = { enabled ->
                            val saved = AppServices.setAutomationSchedulerEnabled(enabled)
                            status = if (saved) "Penjadwal global ${if (enabled) "diaktifkan" else "dinonaktifkan"}."
                            else "Pengaturan penjadwal tidak dapat disimpan."
                        },
                        enabled = AppServices.canControlAutomationScheduler,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        AppServices.notifier?.invoke("TETUKO - Tes Notifikasi", "Notifikasi preset/automasi berfungsi.")
                        status = if (AppServices.notifier != null) "Permintaan notifikasi terkirim." else "Runtime ini belum menyediakan notifikasi native."
                    }) { Icon(Icons.Filled.Notifications, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Tes Notifikasi") }
                    OutlinedButton(onClick = { now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(); status = "Daftar preset dimuat ulang." }) {
                        Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Muat Ulang")
                    }
                }
                status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        profiles.forEach { profile ->
            val presets = remember(profile.id, now) {
                id.tetuko.core.domain.model.PresetCodec.decode(AppServices.presetReader?.invoke(profile.id))
            }
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(profile.label, fontWeight = FontWeight.SemiBold)
                    Text(profile.nama, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (presets.isEmpty()) {
                        Text("Belum ada preset untuk profil ini.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        presets.forEach { preset ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(preset.nama, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                    Text("${preset.jamMulai}-${preset.jamSelesai} • ${preset.tipe}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = preset.aktif,
                                    onCheckedChange = { enabled ->
                                        val next = presets.map { if (it.id == preset.id) it.copy(aktif = enabled, diperbarui = now) else it }
                                        val json = id.tetuko.core.domain.model.PresetCodec.encode(next)
                                        val saved = AppServices.presetWriter?.invoke(profile.id, json) == true
                                        status = if (saved) "Preset ${preset.nama} ${if (enabled) "diaktifkan" else "dinonaktifkan"}." else "Preset gagal disimpan."
                                        now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                                    },
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
fun ServerLoginScreen(
    profile: ServerProfile,
    onBack: () -> Unit,
    onLogin: (String, String, String, String, String) -> Unit,
    onImeiRecovered: (String, String, String) -> Unit,
    savedServerAccounts: List<LocalServerCredential> = emptyList(),
    onSelectServerCredential: (LocalServerCredential) -> Unit = {},
    allowEditCreds: Boolean = true,
    initialUsername: String = "",
    initialPassword: String = "",
    initialImei: String = "",
) {
    var username by remember(profile.id) { mutableStateOf(initialUsername) }
    var password by remember(profile.id) { mutableStateOf(initialPassword) }
    var imei by remember(profile.id) { mutableStateOf(initialImei) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deviceBoundHint by remember { mutableStateOf(false) }
    var recoveryOpen by remember(profile.id) { mutableStateOf(false) }
    var recoveryBusy by remember { mutableStateOf(false) }
    var captchaLoading by remember { mutableStateOf(false) }
    var captchaText by remember { mutableStateOf("") }
    var captchaImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var recoveryMessage by remember { mutableStateOf<String?>(null) }
    var recoveryError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val recoverySupported = AuthService.supportsImeiRecovery(profile)
    val webPresensi = AppServices.webPresensi
    val epBase = profile.epBaseUrl.takeIf { it.isNotBlank() }
    var savedAccountsExpanded by remember(profile.id) { mutableStateOf(false) }

    suspend fun refreshRecoveryCaptcha() {
        captchaLoading = true
        captchaText = ""
        captchaImage = null
        recoveryMessage = null
        recoveryError = null
        webPresensi.resetSession()
        try {
            repeat(3) {
                val image = webPresensi.loadCaptcha(epBase)
                captchaImage = runCatching { image.bytes.decodeToImageBitmap() }.getOrNull()
                if (captchaImage == null) throw IllegalStateException("Gambar captcha tidak dapat dibaca.")
                val solved = webPresensi.solveCaptcha(image.bytes)?.text?.trim().orEmpty()
                if (solved.isNotBlank()) {
                    captchaText = solved
                    recoveryMessage = "OCR mengisi captcha. Periksa dan koreksi bila perlu."
                    return
                }
            }
            recoveryMessage = "OCR tidak berhasil membaca captcha. Silakan isi secara manual."
        } catch (e: Exception) {
            recoveryError = e.message ?: "Gagal memuat captcha."
        } finally {
            captchaLoading = false
        }
    }

    suspend fun submitImeiRecovery() {
        if (username.isBlank() || password.isBlank()) {
            recoveryError = "Username/NIP dan password wajib diisi terlebih dahulu."
            return
        }
        if (captchaText.isBlank()) {
            recoveryError = "Kode captcha wajib diisi."
            return
        }
        recoveryBusy = true
        recoveryError = null
        try {
            webPresensi.login(epBase, username.trim(), password, captchaText.trim())
            val recoveredImei = webPresensi.profile(epBase).imei?.trim().orEmpty()
            if (recoveredImei.isBlank()) {
                throw IllegalStateException("Login web berhasil, tetapi IMEI tidak ditemukan. Muat captcha ulang dan verifikasi kembali.")
            }
            imei = recoveredImei
            onImeiRecovered(username.trim(), password, recoveredImei)
            webPresensi.logout(epBase)
            val result = AuthService.loginServer(profile, username.trim(), password, recoveredImei)
            if (!result.ok) throw IllegalStateException(result.message ?: "Login server gagal menggunakan IMEI hasil pemulihan.")
            recoveryOpen = false
            onLogin(result.profileName, result.nip.ifBlank { username.trim() }, recoveredImei, username.trim(), password)
        } catch (e: Exception) {
            recoveryError = e.message ?: "Pemulihan IMEI gagal."
            runCatching { refreshRecoveryCaptcha() }
        } finally {
            recoveryBusy = false
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF111827), Color(0xFF0B1120)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.95f)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = Color(0xFF94A3B8)) }
                    Column {
                        Text("Login ${profile.label}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(profile.nama, color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                }
                val modeText = if (profile.mode == AttendanceMode.PONOROGO_ABSENSI) "Mode Ponorogo Absensi" else "Mode: BKD RPC"
                AssistChip(onClick = {}, label = { Text(modeText) }, enabled = false)

                if (!error.isNullOrBlank()) {
                    Surface(color = Color(0xFF7F1D1D).copy(alpha = 0.35f), shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(error!!, color = Color(0xFFFECACA), fontSize = 12.sp)
                            if (deviceBoundHint) {
                                Text(
                                    "Akun ini mungkin terikat ke perangkat/IMEI tertentu. Pastikan IMEI/Device ID yang dimasukkan sama dengan perangkat terdaftar. Jika perangkat berubah, hubungi administrator server untuk reset/bind ulang perangkat.",
                                    color = Color(0xFFFDE68A),
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }

                if (savedServerAccounts.isNotEmpty()) {
                    val selectedSaved = savedServerAccounts.firstOrNull { it.username.equals(username, ignoreCase = true) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { savedAccountsExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    selectedSaved?.username?.let { "Akun tersimpan: $it" } ?: "Pilih akun tersimpan",
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = savedAccountsExpanded,
                                onDismissRequest = { savedAccountsExpanded = false },
                                modifier = Modifier.heightIn(max = 320.dp),
                            ) {
                                savedServerAccounts.forEach { saved ->
                                    val isActive = saved.username.equals(username, ignoreCase = true)
                                    DropdownMenuItem(
                                        text = { Text(saved.username, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                                        trailingIcon = if (isActive) ({ Icon(Icons.Filled.Check, contentDescription = "Dipilih") }) else null,
                                        onClick = {
                                            username = saved.username
                                            password = saved.password
                                            imei = saved.imei
                                            error = null
                                            deviceBoundHint = false
                                            savedAccountsExpanded = false
                                            scope.launch(Dispatchers.IO) { onSelectServerCredential(saved) }
                                        },
                                    )
                                }
                            }
                        }
                        if (allowEditCreds) {
                            OutlinedButton(onClick = {
                                username = ""
                                password = ""
                                imei = ""
                                error = null
                                deviceBoundHint = false
                            }) { Text("Akun baru") }
                        }
                    }
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; if (error != null) { error = null; deviceBoundHint = false } },
                    label = { Text("Username / NIP") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    enabled = allowEditCreds,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF3B82F6), unfocusedBorderColor = Color(0xFF334155),
                        focusedLabelColor = Color(0xFF93C5FD), unfocusedLabelColor = Color(0xFF94A3B8), cursorColor = Color.White
                    )
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; if (error != null) { error = null; deviceBoundHint = false } },
                    label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    enabled = allowEditCreds,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF3B82F6), unfocusedBorderColor = Color(0xFF334155),
                        focusedLabelColor = Color(0xFF93C5FD), unfocusedLabelColor = Color(0xFF94A3B8), cursorColor = Color.White
                    )
                )
                OutlinedTextField(
                    value = imei,
                    onValueChange = { imei = it; if (error != null) { error = null; deviceBoundHint = false } },
                    label = { Text("IMEI / Device ID (opsional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    enabled = allowEditCreds,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF3B82F6), unfocusedBorderColor = Color(0xFF334155),
                        focusedLabelColor = Color(0xFF93C5FD), unfocusedLabelColor = Color(0xFF94A3B8), cursorColor = Color.White
                    )
                )
                if (!allowEditCreds) {
                    Text(
                        "Kredensial server dikunci oleh administrator (allowEditServerCreds=false). Isi sesuai kredensial yang ditetapkan atau minta bantuan admin jika perlu perubahan.",
                        color = Color(0xFFFCA5A5),
                        fontSize = 11.sp,
                    )
                } else if (initialUsername.isNotBlank() || initialImei.isNotBlank()) {
                    Text(
                        "Kredensial server diisi otomatis dari assignment akun aplikasi Anda. Anda dapat mengubahnya jika diizinkan.",
                        color = Color(0xFF93C5FD),
                        fontSize = 11.sp,
                    )
                }
                val hint = if (profile.mode == AttendanceMode.PONOROGO_ABSENSI)
                    "Login server Ponorogo melalui proxy lokal. Gunakan kredensial server yang valid; isi IMEI/Device ID jika akun terikat perangkat."
                else
                    "Login BKD RPC melalui proxy /api/rpc. Gunakan kredensial server yang valid; isi IMEI/Device ID jika akun terikat perangkat."
                Text(hint, color = Color(0xFF64748B), fontSize = 11.sp)

                Button(
                    onClick = {
                        if (loading) return@Button
                        val cleanUser = username.trim()
                        if (cleanUser.isBlank()) { error = "Username/NIP wajib diisi."; deviceBoundHint = false; return@Button }
                        if (password.isBlank()) { error = "Password wajib diisi."; deviceBoundHint = false; return@Button }
                        loading = true
                        error = null
                        deviceBoundHint = false
                        scope.launch {
                            val res = AuthService.loginServer(profile, cleanUser, password, imei.trim())
                            loading = false
                            if (res.ok) {
                                onLogin(res.profileName.ifBlank { cleanUser }, res.nip.ifBlank { cleanUser }, imei.trim(), cleanUser, password)
                            } else {
                                val msg = res.message?.takeIf { it.isNotBlank() } ?: "Login gagal. Periksa username/password, IMEI/Device ID, dan koneksi server."
                                error = msg
                                val deviceBound = res.deviceBound ||
                                    msg.contains("IMEI", ignoreCase = true) ||
                                    msg.contains("perangkat", ignoreCase = true) ||
                                    msg.contains("device", ignoreCase = true)
                                deviceBoundHint = deviceBound
                                if (deviceBound && recoverySupported) {
                                    recoveryOpen = true
                                    refreshRecoveryCaptcha()
                                }
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text("Masuk ke ${profile.label}", fontWeight = FontWeight.SemiBold)
                }
                if (recoverySupported && deviceBoundHint && recoveryOpen) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("Pemulihan IMEI", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Login ke portal web ${profile.label} dengan captcha untuk membaca IMEI akun.", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                            Box(
                                Modifier.fillMaxWidth().height(64.dp).background(Color(0xFF0F172A), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    captchaLoading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    captchaImage != null -> Image(
                                        bitmap = captchaImage!!,
                                        contentDescription = "Captcha ${profile.label}",
                                        modifier = Modifier.fillMaxSize().padding(4.dp),
                                        contentScale = ContentScale.Fit,
                                    )
                                    else -> Text("Captcha belum tersedia", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                            }
                            OutlinedTextField(
                                value = captchaText,
                                onValueChange = { captchaText = it; recoveryError = null },
                                label = { Text("Kode captcha") },
                                singleLine = true,
                                enabled = !recoveryBusy && !captchaLoading,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            recoveryMessage?.let { Text(it, color = Color(0xFF93C5FD), fontSize = 11.sp) }
                            recoveryError?.let { Text(it, color = Color(0xFFFCA5A5), fontSize = 11.sp) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { scope.launch { refreshRecoveryCaptcha() } }, enabled = !recoveryBusy && !captchaLoading) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp)); Text("Captcha baru")
                                }
                                Button(onClick = { scope.launch { submitImeiRecovery() } }, enabled = !recoveryBusy && !captchaLoading) {
                                    if (recoveryBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    else Text("Ambil IMEI dan login")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
