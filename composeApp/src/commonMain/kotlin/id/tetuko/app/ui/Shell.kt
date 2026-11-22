package id.tetuko.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import id.tetuko.app.ui.components.VerticalScrollColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.tetuko.app.service.AppServices
import id.tetuko.app.ui.screens.AccountManagementDialog
import id.tetuko.app.ui.screens.ChangePasswordDialog
import id.tetuko.app.ui.screens.PermissionSettingsDialog
import id.tetuko.app.ui.screens.PageScreen
import id.tetuko.core.common.AppIdentity
import id.tetuko.core.domain.model.*
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.compose.resources.painterResource
import tetuko.composeapp.generated.resources.Res
import tetuko.composeapp.generated.resources.tetuko_icon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    state: AppState,
    profile: ServerProfile,
    onLogoutRequest: () -> Unit,
) {
    val allPages = AppNavigation.pagesFor(profile)
    val pages = allPages.filter { state.permissions.canAccessMenu(profile.id, it.id) }.ifEmpty { listOf(allPages.first()) }
    val activePage = pages.firstOrNull { it.id == state.activePageId } ?: pages.first().also { state.activePageId = it.id }
    val isPonorogo = profile.mode == AttendanceMode.PONOROGO_ABSENSI
    var sidebarExpanded by remember { mutableStateOf(true) }
    var mobileMenuOpen by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showPermissionSettings by remember { mutableStateOf(false) }
    var showAccountManagement by remember { mutableStateOf(false) }
    var showDeveloperInspector by remember { mutableStateOf(false) }
    var isOnline by remember { mutableStateOf<Boolean?>(null) }

    // Lightweight connectivity/status probe (recovery-friendly indicator)
    LaunchedEffect(profile.id, AppServices.serverContext.value.apiKey) {
        while (true) {
            val ctx = AppServices.serverContext.value
            isOnline = if (ctx.apiKey.isBlank()) null else try {
                AppServices.rpcClient.callResultElement(
                    id.tetuko.core.data.rpc.RpcObjects.SYNC_DATA,
                    ctx = ctx,
                    profileId = profile.id,
                )
                true
            } catch (_: Exception) {
                false
            }
            delay(45_000)
        }
    }

    LaunchedEffect(profile.id, isPonorogo) {
        if (!isPonorogo) return@LaunchedEffect
        while (true) {
            try {
                if (AppServices.isProductivityReminderEnabled()) {
                    val pon = AppServices.ponorogoProfile
                    val idPegawai = (pon?.get("id_pegawai") as? JsonPrimitive)?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?: (pon?.get("id") as? JsonPrimitive)?.contentOrNull
                        ?: ""
                    if (idPegawai.isNotBlank()) {
                        val now = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Jakarta"))
                        val day = if (now.dayOfWeek.ordinal == 6) 7 else now.dayOfWeek.ordinal + 1
                        if (day in AppServices.productivityReminderDays()) {
                            val date = "%04d-%02d-%02d".format(now.year, now.monthNumber, now.dayOfMonth)
                            val hhmm = "%02d:%02d".format(now.hour, now.minute)
                            val limit = AppServices.productivityReminderLimit()
                            fun toMinutes(value: String): Int? {
                                val parts = value.split(':')
                                if (parts.size != 2) return null
                                val h = parts[0].toIntOrNull() ?: return null
                                val m = parts[1].toIntOrNull() ?: return null
                                return h * 60 + m
                            }

                            val activityResponse = runCatching {
                                AppServices.ponorogoClient.send("/Tupoksi/ambilDataAktivitas", kotlinx.serialization.json.buildJsonObject {
                                    put("id_pegawai", idPegawai)
                                    put("tgl_mulai", date)
                                    put("tgl_akhir", date)
                                })
                            }.getOrNull()
                            val activeActivities = when (val root = activityResponse) {
                                is JsonObject -> (root["data"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                                is JsonArray -> root.mapNotNull { it as? JsonObject }
                                else -> emptyList()
                            }.filter { row ->
                                val status = (row["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                                val finished = (row["tgl_selesai"] as? JsonPrimitive)?.contentOrNull
                                status == "BELUM DIAKHIRI" || status == "Proses" || finished.isNullOrBlank()
                            }

                            val limitReached = (toMinutes(hhmm) ?: 0) >= (toMinutes(limit) ?: 510)
                            val missingKey = "missing:$idPegawai:$date"
                            if (limitReached && activeActivities.isEmpty() && AppServices.productivityReminderStateReader?.invoke(missingKey) != "ya") {
                                val jam = runCatching { AppServices.ponorogoClient.send("/pegawai/getJamKerjaPegawai", kotlinx.serialization.json.buildJsonObject { put("id_pegawai", idPegawai) }) }.getOrNull()
                                val jamRoot = when (jam) {
                                    is JsonArray -> jam.firstOrNull()?.jsonObject
                                    is JsonObject -> jam
                                    else -> null
                                }
                                val jamMasuk = jamRoot?.get("jam_masuk")?.jsonPrimitive?.contentOrNull
                                    ?: jamRoot?.get("absen_masuk")?.jsonPrimitive?.contentOrNull
                                    ?: jamRoot?.get("jam_absen")?.jsonPrimitive?.contentOrNull
                                if (!jamMasuk.isNullOrBlank()) {
                                    AppServices.notifier?.invoke("Pengingat Produktivitas", "Sudah melewati $limit WIB dan belum ada aktivitas produktivitas yang berjalan.")
                                    AppServices.productivityReminderStateWriter?.invoke(missingKey, "ya")
                                }
                            }

                            activeActivities.forEachIndexed { index, row ->
                                val start = (row["tgl_mulai"] as? JsonPrimitive)?.contentOrNull
                                    ?: (row["mulai"] as? JsonPrimitive)?.contentOrNull
                                    ?: ""
                                val startTime = Regex("(\\d{2}):(\\d{2})").find(start)?.value
                                val startMinutes = startTime?.let { toMinutes(it) }
                                val elapsed = if (startMinutes != null) ((now.hour * 60 + now.minute) - startMinutes) / 60 else 0
                                val hourBucket = elapsed.coerceAtLeast(2)
                                val activityId = (row["id"] as? JsonPrimitive)?.contentOrNull ?: "${index}-${start.hashCode()}"
                                val longKey = "long:$idPegawai:$activityId:${hourBucket}"
                                if (elapsed >= 2 && AppServices.productivityReminderStateReader?.invoke(longKey) != "ya") {
                                    AppServices.notifier?.invoke("Aktivitas Berjalan $hourBucket Jam", "Aktivitas produktivitas sudah berjalan sejak $startTime. Lanjutkan atau akhiri aktivitas.")
                                    AppServices.productivityReminderStateWriter?.invoke(longKey, "ya")
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Reminder stays best-effort and does not interrupt the shell.
            }
            delay(60_000)
        }
    }

    val navWidth = if (sidebarExpanded) 256.dp else 84.dp

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val compactLayout = maxWidth < 1024.dp
        Row(Modifier.fillMaxSize()) {
        if (!compactLayout) {
        Surface(
            modifier = Modifier.width(navWidth).fillMaxHeight(),
            color = Color(0xFF0F172A),
            tonalElevation = 0.dp
        ) {
            Column(Modifier.fillMaxHeight()) {
                // Brand header
                Row(
                    Modifier.height(56.dp).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E293B))
                            .semantics { contentDescription = "Logo ${AppIdentity.APP_NAME}" },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(Res.drawable.tetuko_icon),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    AnimatedVisibility(sidebarExpanded) {
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(profile.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(profile.nama, color = Color(0xFF94A3B8), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { sidebarExpanded = !sidebarExpanded }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (sidebarExpanded) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight,
                            contentDescription = if (sidebarExpanded) "Sembunyikan menu" else "Tampilkan menu",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                HorizontalDivider(color = Color(0xFF1E293B))

                // Nav
                VerticalScrollColumn(
                    modifier = Modifier.weight(1f).padding(10.dp),
                    itemSpacing = 4,
                ) {
                    if (isPonorogo) {
                        AppNavigation.ponorogoGroupOrder.forEach { (gid, glabel) ->
                            val groupPages = pages.filter { it.group == gid }
                            if (groupPages.isNotEmpty()) {
                                if (sidebarExpanded) {
                                    Text(glabel.uppercase(), color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 2.dp))
                                }
                                groupPages.forEach { p -> NavItem(p, p.id == state.activePageId, sidebarExpanded) { state.navigateTo(p.id) } }
                            }
                        }
                    } else {
                        pages.forEach { p -> NavItem(p, p.id == state.activePageId, sidebarExpanded) { state.navigateTo(p.id) } }
                    }
                }

                HorizontalDivider(color = Color(0xFF1E293B))
                // Footer / account & logout
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (sidebarExpanded) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .semantics { contentDescription = "Akun aktif: ${state.serverDisplayName}${if (state.serverNip.isNotBlank()) ", ${state.serverNip}" else ""}" },
                        ) {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF2563EB).copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                                Text((state.serverDisplayName.firstOrNull() ?: 'P').uppercase(), color = Color(0xFF93C5FD), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(state.serverDisplayName, color = Color(0xFFE2E8F0), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (state.serverNip.isNotBlank()) Text(state.serverNip, color = Color(0xFF94A3B8), fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                    NavActionItem(Icons.Filled.AccountCircle, "Akun & Keamanan", sidebarExpanded) { showAccount = true }
                    if (state.canManageAccounts()) {
                        NavActionItem(Icons.Filled.AdminPanelSettings, "Pengaturan Izin", sidebarExpanded) { showPermissionSettings = true }
                    }
                    if (state.canManageAccounts()) {
                        NavActionItem(Icons.Filled.Groups, "Manajemen Akun", sidebarExpanded) { showAccountManagement = true }
                    }
                    NavActionItem(Icons.Filled.Code, "Developer Inspector", sidebarExpanded) { showDeveloperInspector = true }
                    NavActionItem(Icons.AutoMirrored.Filled.Logout, "Keluar / Ganti Server", sidebarExpanded, danger = true, onClick = onLogoutRequest)
                }
            }
        }

        }

        // Main area
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // Header
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 2.dp) {
                Row(
                    Modifier.height(56.dp).fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (compactLayout) {
                        IconButton(onClick = { mobileMenuOpen = true }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Buka menu")
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(activePage.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val sub = if (state.serverNip.isNotBlank()) "${state.serverDisplayName} · ${state.serverNip}" else state.serverDisplayName
                        Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    val statusText = when (isOnline) {
                        true -> "Online"
                        false -> "Offline / gangguan"
                        null -> "Status tidak diketahui"
                    }
                    StatusPill(statusText, online = isOnline)
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { state.toggleDarkMode() }) {
                        Icon(
                            if (state.isDarkMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = if (state.isDarkMode) "Aktifkan mode terang" else "Aktifkan mode gelap",
                        )
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                CompositionLocalProvider(
                    LocalAppPermissions provides state.permissions,
                    LocalAppState provides state,
                ) {
                    PageScreen(profile = profile, page = activePage, onNavigate = { state.navigateTo(it) })
                }
            }
        }
    }

        if (compactLayout && mobileMenuOpen) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable { mobileMenuOpen = false },
                )
                Surface(
                    modifier = Modifier.width(256.dp).fillMaxHeight(),
                    color = Color(0xFF0F172A),
                    tonalElevation = 0.dp,
                    shadowElevation = 12.dp,
                ) {
                    Column(Modifier.fillMaxHeight()) {
                        Row(
                            Modifier.height(56.dp).fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(profile.label, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { mobileMenuOpen = false }) {
                                Icon(Icons.Filled.Close, contentDescription = "Tutup menu", tint = Color.White)
                            }
                        }
                        HorizontalDivider(color = Color(0xFF1E293B))
                        VerticalScrollColumn(
                            modifier = Modifier.weight(1f).padding(10.dp),
                            itemSpacing = 4,
                        ) {
                            if (isPonorogo) {
                                AppNavigation.ponorogoGroupOrder.forEach { (gid, glabel) ->
                                    val groupPages = pages.filter { it.group == gid }
                                    if (groupPages.isNotEmpty()) {
                                        Text(glabel.uppercase(), color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 2.dp))
                                        groupPages.forEach { p -> NavItem(p, p.id == state.activePageId, true) { state.navigateTo(p.id); mobileMenuOpen = false } }
                                    }
                                }
                            } else {
                                pages.forEach { p -> NavItem(p, p.id == state.activePageId, true) { state.navigateTo(p.id); mobileMenuOpen = false } }
                            }
                        }
                        HorizontalDivider(color = Color(0xFF1E293B))
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            NavActionItem(Icons.Filled.AccountCircle, "Akun & Keamanan", true) { mobileMenuOpen = false; showAccount = true }
                            if (state.canManageAccounts()) {
                                NavActionItem(Icons.Filled.AdminPanelSettings, "Pengaturan Izin", true) { mobileMenuOpen = false; showPermissionSettings = true }
                                NavActionItem(Icons.Filled.Groups, "Manajemen Akun", true) { mobileMenuOpen = false; showAccountManagement = true }
                            }
                            NavActionItem(Icons.Filled.Code, "Developer Inspector", true) { mobileMenuOpen = false; showDeveloperInspector = true }
                            NavActionItem(Icons.AutoMirrored.Filled.Logout, "Keluar / Ganti Server", true, danger = true) { mobileMenuOpen = false; onLogoutRequest() }
                        }
                    }
                }
            }
        }
    }

    if (state.showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { state.showLogoutConfirm = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = "Peringatan", tint = Color(0xFFF59E0B)) },
            title = { Text("Keluar / Ganti Server?") },
            text = { Text("Sesi server saat ini akan diakhiri dan Anda akan kembali ke pilihan server.") },
            confirmButton = {
                Button(onClick = { state.showLogoutConfirm = false; state.switchServer() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48))) { Text("Ya, Keluar") }
            },
            dismissButton = { OutlinedButton(onClick = { state.showLogoutConfirm = false }) { Text("Batal") } }
        )
    }

    if (showAccount) {
        ChangePasswordDialog(
            profile = profile,
            allowEditCreds = state.permissions.allowEditServerCreds,
            onDismiss = { showAccount = false },
        )
    }

    if (showPermissionSettings) {
        PermissionSettingsDialog(
            current = state.permissions,
            onApply = { updated ->
                // Capture admin status before overwriting permissions.
                val wasAdmin = state.canManageAccounts()
                state.permissions = updated
                // Persist the assignment to the authenticated account when allowed.
                val accId = state.currentAccountId
                if (accId != null && wasAdmin) {
                    runCatching { AppServices.localAccountStore?.updateAccount(accId, permissions = updated) }
                }
                // Re-validate profile/page access after permission changes
                val p = state.currentProfile
                if (p == null || !state.permissions.canAccessProfile(p.id)) {
                    // Profile access revoked: fall back to server selection safely
                    state.forceServerLogout()
                    showPermissionSettings = false
                    return@PermissionSettingsDialog
                }
                state.activePageId = state.sanitizeActivePageFor(p)
                showPermissionSettings = false
            },
            onDismiss = { showPermissionSettings = false },
        )
    }

    if (showDeveloperInspector) {
        DeveloperInspectorDialog(onDismiss = { showDeveloperInspector = false })
    }

    if (showAccountManagement) {
        AccountManagementDialog(
            currentAccountId = state.currentAccountId,
            isAdmin = state.canManageAccounts(),
            onDismiss = { showAccountManagement = false },
        )
    }
}

@Composable
private fun DeveloperInspectorDialog(onDismiss: () -> Unit) {
    var entries by remember { mutableStateOf(AppServices.apiTrafficSnapshot()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(Unit) {
        val unsubscribe = AppServices.subscribeApiTraffic { entries = it }
        onDispose { unsubscribe() }
    }

    val selected = entries.firstOrNull { it.id == selectedId }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Code, contentDescription = "Developer Inspector") },
        title = { Text("Developer Inspector") },
        text = {
            Column(Modifier.widthIn(max = 680.dp).heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { AppServices.clearApiTraffic(); selectedId = null }) { Text("Bersihkan") }
                    OutlinedButton(onClick = {
                        val activeProfile = AppServices.activeProfile
                        val header = buildString {
                            appendLine("TETUKO Diagnostic Bundle")
                            appendLine("Generated: ${kotlinx.datetime.Clock.System.now()}")
                            appendLine("Platform: common")
                            appendLine("Active profile: ${activeProfile?.id.orEmpty().ifBlank { "none" }}")
                            appendLine("Session active: ${AppServices.hasActiveSession}")
                            appendLine("Automation scheduler enabled: ${AppServices.isAutomationSchedulerEnabled()}")
                            appendLine("Traffic entries: ${entries.size}")
                            appendLine("Secret values/API keys/tokens: redacted")
                        }
                        val text = header + "\n" + entries.joinToString("\n\n") { entry ->
                            "${entry.method} ${entry.path} • HTTP ${entry.status}\nREQUEST:\n${entry.request}\nRESPONSE:\n${entry.response}"
                        }
                        AppServices.textExporter?.invoke("tetuko-api-traffic.txt", text)
                    }, enabled = entries.isNotEmpty()) { Text("Ekspor") }
                }
                Text("${entries.size}/100 request terakhir", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        val success = entry.status in 200..299
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { selectedId = if (selectedId == entry.id) null else entry.id },
                            shape = RoundedCornerShape(10.dp),
                            color = if (success) Color(0xFFDCFCE7) else Color(0xFFFEE2E2),
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${entry.method} ${entry.path}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text("HTTP ${entry.status}", fontSize = 11.sp, color = if (success) Color(0xFF166534) else Color(0xFF991B1B))
                            }
                        }
                    }
                }
                selected?.let { entry ->
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { Text("Request", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                            item { Text(entry.request, fontSize = 11.sp) }
                            item { Text("Response", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                            item { Text(entry.response, fontSize = 11.sp) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

@Composable
private fun NavItem(page: PageDef, active: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val bg = if (active) Color(0xFF2563EB).copy(alpha = 0.15f) else Color.Transparent
    val fg = if (active) Color(0xFF93C5FD) else Color(0xFF94A3B8)
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(bg)
            .clickable(role = Role.Tab) { onClick() }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(page.icon.toImageVector(), contentDescription = if (expanded) null else page.label, tint = fg, modifier = Modifier.size(20.dp))
        if (expanded) {
            Spacer(Modifier.width(10.dp))
            Text(page.label, color = if (active) Color(0xFFBFDBFE) else Color(0xFFCBD5E1), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NavActionItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, expanded: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val fg = if (danger) Color(0xFFFDA4AF) else Color(0xFF94A3B8)
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = if (expanded) null else label, tint = fg, modifier = Modifier.size(20.dp))
        if (expanded) { Spacer(Modifier.width(10.dp)); Text(label, color = fg, fontSize = 13.sp) }
    }
}

@Composable
private fun StatusPill(text: String, online: Boolean?) {
    val (dot, fg) = when (online) {
        true -> Color(0xFF10B981) to Color(0xFF059669)
        false -> Color(0xFFF59E0B) to Color(0xFFB45309)
        null -> Color(0xFF94A3B8) to Color(0xFF475569)
    }
    val bg = dot.copy(alpha = 0.12f)
    Surface(
        color = bg,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.semantics { contentDescription = "Status koneksi: $text" },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
