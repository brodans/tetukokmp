package id.tetuko.app.desktop

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import id.tetuko.app.desktop.mini.MiniWindow
import id.tetuko.app.desktop.mini.MiniWindowManager
import id.tetuko.app.service.AppServices
import id.tetuko.app.service.ImageCropRect
import id.tetuko.app.service.PickedFile
import id.tetuko.app.service.AuthService
import id.tetuko.app.ui.AppState
import id.tetuko.app.ui.TetukoApp
import id.tetuko.app.ui.rememberAppState
import id.tetuko.core.common.AppIdentity
import id.tetuko.core.data.rpc.GateCodes
import id.tetuko.core.data.rpc.RpcException
import id.tetuko.core.data.rpc.RpcObjects
import id.tetuko.core.domain.model.AppNavigation
import id.tetuko.core.domain.model.AppRoute
import id.tetuko.core.domain.model.DefaultProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.awt.*
import java.awt.image.BufferedImage
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * Desktop runtime for TETUKO (SimpMusic-like UX): system tray, close-to-tray,
 * restore signal, desktop notifications, and mini window.
 */
fun runDesktopApp(args: Array<String> = emptyArray()) {
    System.setProperty("compose.swing.render.on.graphics", "true")
    runCatching { System.setProperty("awt.useSystemAAFontSettings", "on") }
    AppServices.imageCropper = ::cropPickedImage
    AppServices.imageProcessor = ::processPickedImage

    application {
        val appState = rememberAppState()
        val startMinimized = args.any { it == "--minimized" || it == "--hidden" }
        var isVisible by remember { mutableStateOf(!startMinimized) }
        val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))

        // Restore persisted session/UI state on startup (persistence & recovery)
        LaunchedEffect(Unit) {
            DesktopSessionStore.restore(appState)
            validateRestoredSession(appState)
            applyDeepLink(appState, args)
        }

        // Restore requests (tray actions, mini window button)
        LaunchedEffect(Unit) {
            DesktopRestoreSignal.requests.collect {
                isVisible = true
                windowState.isMinimized = false
            }
        }

        val trayController = remember { AwtTrayController() }

        // Install tray once
        DisposableEffect(Unit) {
            trayController.install(
                onShow = { DesktopRestoreSignal.request() },
                onToggleMini = { MiniWindowManager.toggle() },
                onQuickNav = { pageId -> DesktopNavigation.navigateTo(pageId) },
                onQuit = {
                    DesktopSessionStore.save(appState)
                    runCatching { AuthService.logoutLocal() }
                    exitApplication()
                },
            )
            onDispose { trayController.dispose() }
        }

        // Handle navigation intents from tray/mini quick actions
        LaunchedEffect(appState) {
            DesktopNavigation.pageIntents.collect { requested ->
                val inApp = appState.route is AppRoute.App
                if (inApp) {
                    val profile = appState.currentProfile
                    val resolvedPageId = if (profile != null) {
                        resolveDesktopQuickNavPageId(profile, requested)
                    } else null
                    if (profile == null || resolvedPageId == null) {
                        DesktopRestoreSignal.request()
                        DesktopNotifications.info("Menu tersebut tidak tersedia untuk profil/sesi ini.")
                        return@collect
                    }
                    val before = appState.activePageId
                    appState.navigateTo(resolvedPageId)
                    val moved = appState.activePageId == resolvedPageId
                    DesktopRestoreSignal.request()
                    if (!moved && before != resolvedPageId) {
                        DesktopNotifications.info("Menu tersebut tidak tersedia atau tidak diizinkan untuk profil/sesi ini.")
                    }
                } else {
                    DesktopRestoreSignal.request()
                    val msg = if (appState.appAuthed) {
                        "Masuk ke server terlebih dahulu untuk membuka menu."
                    } else {
                        "Masuk ke aplikasi terlebih dahulu."
                    }
                    DesktopNotifications.info(msg)
                }
            }
        }

        // Persist session/UI state on relevant changes (recovery)
        LaunchedEffect(appState) {
            snapshotFlow {
                PersistSnapshot(
                    dark = appState.isDarkMode,
                    authed = appState.appAuthed,
                    appUser = appState.appUsername,
                    profileId = appState.currentProfile?.id ?: "",
                    pageId = appState.activePageId,
                    name = appState.serverDisplayName,
                    nip = appState.serverNip,
                    apiKey = id.tetuko.app.service.AppServices.serverContext.value.apiKey,
                )
            }.distinctUntilChanged().collect {
                withContext(Dispatchers.IO) { DesktopSessionStore.save(appState) }
            }
        }

        // Periodic session expiry-touch (P0 hardening): refresh savedAt while an
        // authenticated server session is active so normal use keeps recovery valid.
        LaunchedEffect(appState) {
            while (true) {
                kotlinx.coroutines.delay(6L * 60L * 60L * 1000L) // 6 hours
                val hasActiveSession = appState.appAuthed &&
                    appState.currentProfile != null &&
                    id.tetuko.app.service.AppServices.serverContext.value.apiKey.isNotBlank()
                if (hasActiveSession) {
                    withContext(Dispatchers.IO) { DesktopSessionStore.save(appState) }
                }
            }
        }

        // Keep tray labels/tooltips in sync with app state and visibility
        LaunchedEffect(appState, trayController) {
            while (true) {
                val snap = TraySnapshot(
                    route = routeKey(appState),
                    profileLabel = appState.currentProfile?.label ?: "",
                    user = appState.serverDisplayName,
                    pageId = appState.activePageId,
                    visible = isVisible,
                    miniOpen = MiniWindowManager.isOpen,
                    dark = appState.isDarkMode,
                    schedulerEnabled = id.tetuko.app.service.AppServices.isAutomationSchedulerEnabled(),
                    schedulerPaused = id.tetuko.app.desktop.DesktopAutomationScheduler.isPaused(),
                )
                trayController.update(
                    status = statusLabel(snap.route, snap.profileLabel, snap.user, snap.schedulerEnabled, snap.schedulerPaused),
                    tooltip = tooltipText(snap.route, snap.profileLabel, snap.schedulerEnabled, snap.schedulerPaused),
                    openLabel = if (snap.visible) "Tampilkan TETUKO" else "Buka TETUKO",
                    miniLabel = if (snap.miniOpen) "Tutup Mini Window" else "Buka Mini Window",
                )
                kotlinx.coroutines.delay(30_000)
            }
        }

        // Desktop notifications on important state transitions (SimpMusic-like feedback)
        LaunchedEffect(appState) {
            var lastRoute = routeKey(appState)
            var lastUser = appState.serverDisplayName
            var lastProfile = appState.currentProfile?.label ?: ""
            snapshotFlow {
                Triple(routeKey(appState), appState.currentProfile?.label ?: "", appState.serverDisplayName)
            }.distinctUntilChanged().collect { (route, profile, user) ->
                if (lastRoute != "app" && route == "app") {
                    DesktopNotifications.success("Terhubung ke ${if (profile.isBlank()) "server" else profile}${if (user.isNotBlank() && user != "Pengguna") " sebagai $user" else ""}")
                } else if (lastRoute == "app" && route == "login") {
                    DesktopNotifications.info("Sesi server ditutup. Pilih server untuk melanjutkan.")
                } else if (lastRoute != "masuk" && route == "masuk") {
                    DesktopNotifications.info("Anda telah keluar dari aplikasi.")
                }
                lastRoute = route; lastProfile = profile; lastUser = user
            }
        }

        Window(
            onCloseRequest = {
                // Close-to-tray behavior like SimpMusic
                isVisible = false
                DesktopNotifications.info("TETUKO masih berjalan di system tray.")
            },
            title = "${AppIdentity.APP_NAME} - ${AppIdentity.APP_FULL_NAME}",
            state = windowState,
            visible = isVisible,
        ) {
            window.minimumSize = Dimension(900, 600)

            // Bring to front on restore
            LaunchedEffect(Unit) {
                DesktopRestoreSignal.requests.collect {
                    runCatching {
                        window.isAlwaysOnTop = true
                        window.toFront()
                        window.requestFocus()
                        window.isAlwaysOnTop = false
                    }
                }
            }

            TetukoApp(state = appState)
        }

        if (MiniWindowManager.isOpen) {
            MiniWindow(
                appState = appState,
                onCloseRequest = { MiniWindowManager.isOpen = false },
            )
        }
    }
}

private fun processPickedImage(file: PickedFile, maxSide: Int, qualityPercent: Int): PickedFile? = runCatching {
    val source = ImageIO.read(ByteArrayInputStream(file.bytes)) ?: return null
    val scale = minOf(1.0, maxSide.toDouble() / maxOf(source.width, source.height))
    val width = (source.width * scale).toInt().coerceAtLeast(1)
    val height = (source.height * scale).toInt().coerceAtLeast(1)
    val output = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = output.createGraphics()
    try {
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.drawImage(source, 0, 0, width, height, null)
    } finally { graphics.dispose() }
    val bytes = ByteArrayOutputStream().use { stream ->
        val writer = ImageIO.getImageWritersByFormatName("jpg").asSequence().firstOrNull()
            ?: error("Encoder JPEG tidak tersedia.")
        val param = writer.defaultWriteParam
        param.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = qualityPercent.coerceIn(10, 100) / 100f
        writer.output = javax.imageio.ImageIO.createImageOutputStream(stream)
        writer.write(null, javax.imageio.IIOImage(output, null, null), param)
        writer.dispose()
        stream.toByteArray()
    }
    PickedFile("photo-$maxSide.jpg", "image/jpeg", bytes)
}.getOrNull()

private fun cropPickedImage(file: PickedFile, rect: ImageCropRect): PickedFile? = runCatching {
    val source = ImageIO.read(ByteArrayInputStream(file.bytes)) ?: return null
    val left = (rect.left * source.width).toInt().coerceIn(0, source.width - 1)
    val top = (rect.top * source.height).toInt().coerceIn(0, source.height - 1)
    val width = (rect.width * source.width).toInt().coerceIn(1, source.width - left)
    val height = (rect.height * source.height).toInt().coerceIn(1, source.height - top)
    val cropped = source.getSubimage(left, top, width, height)
    val output = java.awt.image.BufferedImage(400, 400, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val graphics = output.createGraphics()
    try {
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.drawImage(cropped, 0, 0, 400, 400, null)
    } finally {
        graphics.dispose()
    }
    val bytes = ByteArrayOutputStream().use { stream -> ImageIO.write(output, "jpg", stream); stream.toByteArray() }
    PickedFile("foto-profil-crop.jpg", "image/jpeg", bytes)
}.getOrNull()

private data class TraySnapshot(
    val route: String,
    val profileLabel: String,
    val user: String,
    val pageId: String,
    val visible: Boolean,
    val miniOpen: Boolean,
    val dark: Boolean,
    val schedulerEnabled: Boolean,
    val schedulerPaused: Boolean,
)

private data class PersistSnapshot(
    val dark: Boolean,
    val authed: Boolean,
    val appUser: String,
    val profileId: String,
    val pageId: String,
    val name: String,
    val nip: String,
    val apiKey: String,
)

private fun resolveDesktopQuickNavPageId(
    profile: id.tetuko.core.domain.model.ServerProfile,
    requested: String,
): String? {
    val key = requested.trim()
    if (key.isBlank()) return null

    // Mode-aware action aliases (beranda/presensi/izin/laporan/lokasi/...)
    AppNavigation.quickActionPageId(profile, key)?.let { return it }

    // Direct page ID for the active profile (already mode-correct)
    AppNavigation.pageById(profile, key)?.let { return it.id }

    // Compatibility: map known BKD hardcoded IDs to action keys and resolve per mode
    val compatAction = when (key) {
        "tabBeranda" -> "beranda"
        "tabPresensi" -> "presensi"
        "tabPerizinan" -> "izin"
        "tabLaporan" -> "laporan"
        "tabLokasiAbsen" -> "lokasi"
        "tabWeb" -> "web"
        "tabPresetOtomatisasi" -> "preset"
        "tabAbsen" -> "presensi"
        "tabIzin" -> "izin"
        "tabReport" -> "laporan"
        "tabLog" -> "log"
        "tabAutomasi" -> "otomasi"
        else -> null
    }
    return compatAction?.let { AppNavigation.quickActionPageId(profile, it) }
}

/**
 * Applies a startup deep link (`tetuko://<profile>/<slug>` or `--route=/<profile>/<slug>`).
 * Permission checks mirror in-app navigation; invalid targets fall back safely
 * (with clear feedback) instead of forcing an unauthorized route.
 */
private fun applyDeepLink(state: AppState, args: Array<String>) {
    val path = args.asSequence().mapNotNull { arg ->
        when {
            arg.startsWith("tetuko://") -> arg.removePrefix("tetuko://")
            arg.startsWith("--route=") -> arg.removePrefix("--route=")
            else -> null
        }
    }.firstOrNull()?.trim()?.trim('/') ?: return

    val parts = path.split('/').filter { it.isNotBlank() }
    if (parts.size < 2) {
        DesktopNotifications.info("Tautan deep link tidak valid.")
        return
    }
    val profile = DefaultProfiles.all.firstOrNull { it.id == parts[0] } ?: run {
        DesktopNotifications.info("Profil pada tautan tidak dikenali.")
        return
    }

    if (!state.appAuthed) {
        // Preserve the intended target and apply it after the app gate is passed.
        state.deferredDeepLink = profile.id to AppNavigation.slugToPageId(profile, parts[1])
        DesktopNotifications.info("Masuk ke aplikasi terlebih dahulu; tautan akan dibuka setelahnya.")
        return
    }
    if (!state.permissions.canAccessProfile(profile.id)) {
        DesktopNotifications.warn("Profil ${profile.label} tidak diizinkan untuk akun ini.")
        return
    }
    if (allowedMenuCountSafe(state, profile) == 0) {
        DesktopNotifications.warn("Tidak ada menu yang diizinkan pada profil ${profile.label}.")
        return
    }

    val requestedSlug = parts[1]
    val pageId = AppNavigation.slugToPageId(profile, requestedSlug)
    if (!state.permissions.canAccessMenu(profile.id, pageId)) {
        DesktopNotifications.info("Halaman '$requestedSlug' tidak diizinkan untuk akun ini.")
        // Fall back to a safe default page for the profile if possible.
        val fallback = AppNavigation.defaultPageId(profile)
        if (!state.permissions.canAccessMenu(profile.id, fallback)) return
        if (state.currentProfile?.id == profile.id) {
            state.navigateTo(fallback)
        } else {
            state.pendingProfile = profile
            state.pendingPageId = fallback
        }
        return
    }

    val sameActiveProfile = state.currentProfile?.id == profile.id && AppServices.serverContext.value.apiKey.isNotBlank()
    if (sameActiveProfile) {
        state.navigateTo(pageId)
        return
    }

    // Route to server login for the target profile and preserve intended page after login.
    // Clear any active server session to avoid stale context while switching targets.
    if (state.currentProfile != null || AppServices.serverContext.value.apiKey.isNotBlank()) {
        runCatching { AuthService.logoutLocal() }
        AppServices.clearServerSession()
        state.currentProfile = null
        state.serverDisplayName = "Pengguna"
        state.serverNip = ""
    }
    state.pendingProfile = profile
    state.pendingPageId = pageId
    state.activePageId = "tabBeranda"
}

private fun allowedMenuCountSafe(state: AppState, profile: id.tetuko.core.domain.model.ServerProfile): Int =
    runCatching {
        AppNavigation.pagesFor(profile).count { state.permissions.canAccessMenu(profile.id, it.id) }
    }.getOrDefault(0)

/**
 * Validates a restored server session without breaking offline recovery:
 * - invalid/expired token (gate -32604) → clear session, return to login;
 * - network/proxy unavailable → keep the cached session (offline-friendly).
 */
private suspend fun validateRestoredSession(state: AppState) {
    val profile = state.currentProfile ?: return
    if (AppServices.serverContext.value.apiKey.isBlank()) {
        // Inconsistent restored state: keep app auth but force clean server-login flow.
        state.forceServerLogout()
        runCatching { DesktopSessionStore.clearServerSessionOnly() }
        return
    }
    if (!state.permissions.canAccessProfile(profile.id)) {
        state.forceServerLogout()
        runCatching { DesktopSessionStore.clearServerSessionOnly() }
        DesktopNotifications.warn("Profil tersimpan tidak lagi diizinkan. Silakan login ulang.")
        return
    }
    try {
        AppServices.rpcClient.callResultElement(
            RpcObjects.SYNC_DATA,
            ctx = AppServices.serverContext.value,
            profileId = profile.id,
        )
    } catch (e: RpcException) {
        if (e.code == GateCodes.INVALID_TOKEN) {
            runCatching { AuthService.logoutLocal() }
            state.forceServerLogout()
            runCatching { DesktopSessionStore.clearServerSessionOnly() }
            DesktopNotifications.warn("Sesi server kedaluwarsa. Silakan login ulang.")
        }
    } catch (_: Exception) {
        // Offline/proxy down: keep the session so the user can recover connectivity.
    }
}

private fun routeKey(state: AppState): String = when (state.route) {
    is AppRoute.Masuk -> "masuk"
    is AppRoute.Login -> "login"
    is AppRoute.App -> "app"
}

private fun statusLabel(route: String, profileLabel: String, user: String, schedulerEnabled: Boolean, schedulerPaused: Boolean): String = when (route) {
    "masuk" -> "Status: Belum masuk aplikasi"
    "login" -> "Status: Pilih / login server"
    else -> {
        val sched = when {
            !schedulerEnabled -> "Scheduler mati"
            schedulerPaused -> "Scheduler dijeda"
            else -> "Scheduler aktif"
        }
        "Status: ${if (profileLabel.isBlank()) "Aktif" else profileLabel} • ${if (user.isBlank()) "Pengguna" else user} • $sched"
    }
}

private fun tooltipText(route: String, profileLabel: String, schedulerEnabled: Boolean, schedulerPaused: Boolean): String = buildString {
    append(AppIdentity.APP_NAME)
    append(" - ")
    append(
        when (route) {
            "masuk" -> "Belum masuk"
            "login" -> "Pilih server"
            else -> if (profileLabel.isBlank()) "Aktif" else profileLabel
        }
    )
    append(" | ")
    append(if (!schedulerEnabled) "Scheduler mati" else if (schedulerPaused) "Scheduler dijeda" else "Scheduler aktif")
}

private class AwtTrayController {
    private var trayIcon: TrayIcon? = null
    private var statusItem: MenuItem? = null
    private var openItem: MenuItem? = null
    private var miniItem: MenuItem? = null
    private var autostartItem: CheckboxMenuItem? = null

    fun install(
        onShow: () -> Unit,
        onToggleMini: () -> Unit,
        onQuickNav: (String) -> Unit,
        onQuit: () -> Unit,
    ) {
        if (!SystemTray.isSupported()) return
        val image = loadTrayAwtImage()
        val popup = PopupMenu()
        val status = MenuItem("Status: Memulai...").apply { isEnabled = false }
        val open = MenuItem("Buka TETUKO")
        val mini = MenuItem("Buka Mini Window")
        val autostart = CheckboxMenuItem("Mulai otomatis saat login", DesktopAutostart.isEnabled())
        val pauseAutomation = MenuItem("Tunda Eksekusi Otomatis 1 Jam")
        val resumeAutomation = MenuItem("Aktifkan Kembali Eksekusi Otomatis")
        val quit = MenuItem("Keluar")

        // Quick actions (SimpMusic-like tray usefulness / desktop-native convenience)
        val quickMenu = Menu("Aksi Cepat")
        val qaBeranda = MenuItem("Buka Beranda")
        val qaPresensi = MenuItem("Buka Presensi")
        val qaIzin = MenuItem("Buka Perizinan/Izin")
        val qaLaporan = MenuItem("Buka Laporan")
        val qaLokasi = MenuItem("Buka Lokasi Absen")
        val qaPreset = MenuItem("Buka Preset Automasi")
        val qaLog = MenuItem("Buka Log Aktivitas")
        quickMenu.add(qaBeranda)
        quickMenu.add(qaPresensi)
        quickMenu.add(qaIzin)
        quickMenu.add(qaLaporan)
        quickMenu.add(qaLokasi)
        quickMenu.addSeparator()
        quickMenu.add(qaPreset)
        quickMenu.add(qaLog)

        popup.add(status)
        popup.addSeparator()
        popup.add(open)
        popup.add(mini)
        popup.add(autostart)
        popup.add(quickMenu)
        popup.addSeparator()
        popup.add(pauseAutomation)
        popup.add(resumeAutomation)
        popup.add(quit)

        val icon = TrayIcon(image, AppIdentity.APP_NAME).apply {
            isImageAutoSize = true
            this.popupMenu = popup
        }

        open.addActionListener { onShow() }
        mini.addActionListener { onToggleMini() }
        autostart.addItemListener {
            val want = autostart.state
            val ok = DesktopAutostart.setEnabled(want)
            if (ok) {
                DesktopNotifications.info(if (want) "Autostart diaktifkan." else "Autostart dinonaktifkan.")
            } else {
                DesktopNotifications.error("Gagal mengubah pengaturan autostart.")
                autostart.state = DesktopAutostart.isEnabled()
            }
        }
        qaBeranda.addActionListener { onQuickNav("beranda") }
        qaPresensi.addActionListener { onQuickNav("presensi") }
        qaIzin.addActionListener { onQuickNav("izin") }
        qaLaporan.addActionListener { onQuickNav("laporan") }
        qaLokasi.addActionListener { onQuickNav("lokasi") }
        qaPreset.addActionListener { onQuickNav("preset") }
        qaLog.addActionListener { onQuickNav("log") }
        pauseAutomation.addActionListener {
            id.tetuko.app.desktop.DesktopAutomationScheduler.pauseFor(java.time.Duration.ofHours(1))
            DesktopNotifications.info("Eksekusi otomatis ditunda selama 1 jam.")
        }
        resumeAutomation.addActionListener {
            id.tetuko.app.desktop.DesktopAutomationScheduler.resume()
            DesktopNotifications.info("Eksekusi otomatis diaktifkan kembali.")
        }
        quit.addActionListener { onQuit() }
        icon.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) onShow()
            }
        })
        icon.addActionListener { onShow() }

        runCatching {
            SystemTray.getSystemTray().add(icon)
            DesktopNotifications.attachTrayIcon(icon)
            trayIcon = icon
            statusItem = status
            openItem = open
            miniItem = mini
            autostartItem = autostart
        }
    }

    fun update(status: String, tooltip: String, openLabel: String, miniLabel: String) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { update(status, tooltip, openLabel, miniLabel) }
            return
        }
        trayIcon?.toolTip = tooltip
        statusItem?.label = status
        openItem?.label = openLabel
        miniItem?.label = miniLabel
        autostartItem?.state = DesktopAutostart.isEnabled()
    }

    fun dispose() {
        DesktopNotifications.attachTrayIcon(null)
        val icon = trayIcon ?: return
        runCatching { if (SystemTray.isSupported()) SystemTray.getSystemTray().remove(icon) }
        trayIcon = null
    }
}

private fun loadTrayAwtImage(): Image {
    ensureTrayIconCache()
    val codeSourceDir = runCatching {
        File(DesktopEnv::class.java.protectionDomain.codeSource.location.toURI()).parentFile
    }.getOrNull()
    val candidates = listOf(
        File(System.getProperty("tetuko.resource.dir", "."), "icons/icon.png"),
        File(System.getProperty("tetuko.resource.dir", "."), "app-resources/icons/icon.png"),
        File(System.getProperty("user.home"), ".tetuko/icon.png"),
        codeSourceDir?.resolve("icons/icon.png"),
        codeSourceDir?.resolve("../icons/icon.png"),
        codeSourceDir?.resolve("app-resources/icons/icon.png"),
        File("app-resources/icons/icon.png"),
        File("app-resources/icons/32x32.png"),
        File("app-resources/icons/128x128.png"),
    )
    val img = candidates.filterNotNull().firstNotNullOfOrNull { f -> runCatching { if (f.exists()) ImageIO.read(f) else null }.getOrNull() }
        ?: runCatching {
            val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("icons/icon.png")
                ?: Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")
                ?: Thread.currentThread().contextClassLoader.getResourceAsStream("app-resources/icons/icon.png")
                ?: Thread.currentThread().contextClassLoader.getResourceAsStream("icons/128x128.png")
            stream?.use { ImageIO.read(it) }
        }.getOrNull()

    return img ?: createFallbackTrayImage()
}

private fun ensureTrayIconCache() {
    runCatching {
        val cache = File(System.getProperty("user.home"), ".tetuko/icon.png")
        if (cache.exists() && cache.length() > 100) return@runCatching
        val candidates = listOf(
            File("app-resources/icons/128x128@2x.png"),
            File("app-resources/icons/128x128.png"),
            File("app-resources/icons/icon.png"),
        )
        var bytes = candidates.firstOrNull { it.exists() }?.readBytes()
        if (bytes == null) {
            val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("icons/128x128.png")
                ?: Thread.currentThread().contextClassLoader.getResourceAsStream("icons/icon.png")
                ?: Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")
            bytes = stream?.use { it.readBytes() }
        }
        if (bytes != null) {
            cache.parentFile?.mkdirs()
            cache.writeBytes(bytes)
        }
    }
}

private fun createFallbackTrayImage(): Image {
    val size = 64
    val buffered = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = buffered.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(37, 99, 235)
    g.fillRoundRect(4, 4, size - 8, size - 8, 16, 16)
    g.color = Color.WHITE
    g.font = Font("SansSerif", Font.BOLD, 34)
    g.drawString("T", 20, 44)
    g.dispose()
    return buffered
}
