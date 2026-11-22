package id.tetuko.app

import id.tetuko.app.desktop.DesktopNotifications
import id.tetuko.app.desktop.DesktopLocalAccountStore
import id.tetuko.app.desktop.DesktopEnv
import id.tetuko.app.desktop.DesktopAutostart
import id.tetuko.app.desktop.DesktopSingleInstance
import id.tetuko.app.desktop.DesktopSecureStore
import id.tetuko.app.desktop.FirebaseAppConfig
import id.tetuko.app.desktop.FirestoreAccountStore
import id.tetuko.app.desktop.runDesktopApp
import id.tetuko.app.service.AppServices
import id.tetuko.app.service.PickedFile
import id.tetuko.core.domain.model.DefaultProfiles
import id.tetuko.proxy.startProxyServer
import java.awt.FileDialog
import java.awt.Frame
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import com.github.sarxos.webcam.Webcam
import com.github.sarxos.webcam.WebcamPanel
import com.github.sarxos.webcam.WebcamResolution

fun main(args: Array<String>) {
    val instance = DesktopSingleInstance.acquire(args)
    if (!instance.acquired) {
        instance.forward(args)
        return
    }
    DesktopEnv.ensureLoaded()
    DefaultProfiles.applyEnvironmentOverrides(DesktopEnv.snapshot())
    propagateDotEnvToSystemPropertiesForProxy()
    installNativeTextExporter()
    installNativeBinaryExporter()
    installNativeBinaryPreviewer()
    installNativeFilePicker()
    installNativeCameraCapture()
    installSavedPointsStore()
    installPresetStore()
    installAutomationLogStore()
    installStockPhotoStore()
    installPhotoQueueStore()
    installWebCookieStore()
    installAutomationSchedulerSettings()
    installProductivityReminderSettings()
    installLocalAccountStore()
    AppServices.autostartEnabledReader = { DesktopAutostart.isEnabled() }
    AppServices.autostartEnabledWriter = { enabled -> DesktopAutostart.setEnabled(enabled) }
    installNotifier()
    id.tetuko.app.desktop.DesktopAutomationScheduler.start()
    AppServices.manualAutomationRunner = { profileId, preset ->
        id.tetuko.app.desktop.DesktopAutomationScheduler.executeNow(profileId, preset)
    }
    // Embedded proxy replaces the old Node dev-server: BKD RPC, uploads,
    // Ponorogo bridges and the /ep portal all target 127.0.0.1:14200.
    val proxy = runCatching { startProxyServer(14200) }.getOrNull()
    try {
        runDesktopApp(args)
    } finally {
        instance.close()
        id.tetuko.app.desktop.DesktopAutomationScheduler.stop()
        proxy?.close()
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
        val writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpg").asSequence().firstOrNull()
            ?: error("Encoder JPEG tidak tersedia.")
        val param = writer.defaultWriteParam
        param.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = qualityPercent.coerceIn(10, 100) / 100f
        stream.use { target ->
            writer.output = javax.imageio.ImageIO.createImageOutputStream(target)
            writer.write(null, javax.imageio.IIOImage(output, null, null), param)
            writer.dispose()
        }
        stream.toByteArray()
    }
    output.flush()
    PickedFile("photo-$maxSide.jpg", "image/jpeg", bytes)
}.getOrNull()

private fun installNativeCameraCapture() {
    AppServices.cameraCaptureAsync = { callback ->
        SwingUtilities.invokeLater {
            val webcam = Webcam.getDefault()
            if (webcam == null) {
                DesktopNotifications.warn("Kamera tidak ditemukan atau tidak dapat diakses.")
                callback(null)
                return@invokeLater
            }
            val completed = AtomicBoolean(false)
            val frame = JFrame("Ambil Foto Presensi")
            fun finish(result: PickedFile?) {
                if (!completed.compareAndSet(false, true)) return
                runCatching { if (webcam.isOpen) webcam.close() }
                frame.dispose()
                callback(result)
            }
            try {
                webcam.viewSize = WebcamResolution.VGA.size
                webcam.open()
                frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
                frame.layout = BorderLayout()
                frame.add(WebcamPanel(webcam), BorderLayout.CENTER)
                val actions = JPanel()
                actions.add(JButton("Ambil Foto").apply {
                    addActionListener {
                        val image = webcam.image
                        if (image == null) DesktopNotifications.warn("Frame kamera belum tersedia.")
                        else finish(encodeCameraImage(image))
                    }
                })
                actions.add(JButton("Batal").apply { addActionListener { finish(null) } })
                frame.add(actions, BorderLayout.SOUTH)
                frame.addWindowListener(object : java.awt.event.WindowAdapter() {
                    override fun windowClosing(event: java.awt.event.WindowEvent?) = finish(null)
                })
                frame.pack()
                frame.setLocationRelativeTo(null)
                frame.isVisible = true
            } catch (error: Exception) {
                runCatching { if (webcam.isOpen) webcam.close() }
                DesktopNotifications.error("Gagal membuka kamera: ${error.message ?: "periksa izin/perangkat"}")
                callback(null)
            }
        }
    }
}

private fun encodeCameraImage(source: BufferedImage): PickedFile? = runCatching {
    val scale = minOf(1.0, 320.0 / maxOf(source.width, source.height))
    val width = (source.width * scale).toInt().coerceAtLeast(1)
    val height = (source.height * scale).toInt().coerceAtLeast(1)
    val output = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = output.createGraphics()
    try {
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.drawImage(source, 0, 0, width, height, null)
    } finally {
        graphics.dispose()
    }
    val bytes = ByteArrayOutputStream().use { stream ->
        check(ImageIO.write(output, "jpg", stream)) { "Encoder JPEG tidak tersedia." }
        stream.toByteArray()
    }
    PickedFile("camera-capture.jpg", "image/jpeg", bytes)
}.getOrNull()

private fun installWebCookieStore() {
    AppServices.webCookieReader = { scope ->
        DesktopSecureStore.load()["web_cookie:${scope}"]
    }
    AppServices.webCookieWriter = { scope, value ->
        runCatching {
            val data = DesktopSecureStore.load().toMutableMap()
            data["web_cookie:${scope}"] = value
            DesktopSecureStore.save(data)
            true
        }.getOrDefault(false)
    }
    AppServices.webCookieDeleter = { scope ->
        runCatching {
            val data = DesktopSecureStore.load().toMutableMap()
            data.remove("web_cookie:${scope}")
            DesktopSecureStore.save(data)
            true
        }.getOrDefault(false)
    }
}

private fun installAutomationSchedulerSettings() {
    val directory = Path.of(System.getProperty("user.home"), ".tetuko", "data")
    val file = directory.resolve("automation-scheduler-enabled.txt")
    AppServices.automationSchedulerEnabledReader = {
        runCatching {
            if (Files.exists(file)) Files.readString(file).trim().toBooleanStrictOrNull() ?: true else true
        }.getOrDefault(true)
    }
    AppServices.automationSchedulerEnabledWriter = { enabled ->
        runCatching {
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, "automation-scheduler-", ".tmp")
            Files.writeString(temporary, enabled.toString())
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
    }
}

private fun installProductivityReminderSettings() {
    val directory = Path.of(System.getProperty("user.home"), ".tetuko", "data")
    val enabledFile = directory.resolve("productivity-reminder-enabled.txt")
    val limitFile = directory.resolve("productivity-reminder-limit.txt")
    val daysFile = directory.resolve("productivity-reminder-days.txt")
    val stateFile = directory.resolve("productivity-reminder-state.txt")

    AppServices.productivityReminderEnabledReader = {
        runCatching {
            if (Files.exists(enabledFile)) Files.readString(enabledFile).trim().toBooleanStrictOrNull() ?: true else true
        }.getOrDefault(true)
    }
    AppServices.productivityReminderEnabledWriter = { enabled ->
        runCatching {
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, "reminder-enabled-", ".tmp")
            Files.writeString(temporary, enabled.toString())
            Files.move(temporary, enabledFile, StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
    }
    AppServices.productivityReminderLimitReader = {
        runCatching { if (Files.exists(limitFile)) Files.readString(limitFile).trim().ifBlank { "08:30" } else "08:30" }.getOrDefault("08:30")
    }
    AppServices.productivityReminderLimitWriter = { limit ->
        runCatching {
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, "reminder-limit-", ".tmp")
            Files.writeString(temporary, limit)
            Files.move(temporary, limitFile, StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
    }
    AppServices.productivityReminderDaysReader = {
        runCatching {
            if (Files.exists(daysFile)) Files.readString(daysFile).split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.isNotEmpty() } ?: listOf(1, 2, 3, 4, 5)
            else listOf(1, 2, 3, 4, 5)
        }.getOrDefault(listOf(1, 2, 3, 4, 5))
    }
    AppServices.productivityReminderDaysWriter = { days ->
        runCatching {
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, "reminder-days-", ".tmp")
            Files.writeString(temporary, days.joinToString(","))
            Files.move(temporary, daysFile, StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
    }
    AppServices.productivityReminderStateReader = { key ->
        runCatching {
            val raw = if (Files.exists(stateFile)) Files.readString(stateFile) else ""
            raw.lines().firstOrNull { it.substringBefore('=', "") == key }?.substringAfter('=')?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
    AppServices.productivityReminderStateWriter = { key, value ->
        runCatching {
            Files.createDirectories(directory)
            val entries = if (Files.exists(stateFile)) Files.readString(stateFile).lines().filter { it.isNotBlank() && it.substringBefore('=', "") != key }.toMutableList() else mutableListOf()
            entries.add("$key=$value")
            val temporary = Files.createTempFile(directory, "reminder-state-", ".tmp")
            Files.writeString(temporary, entries.joinToString("\n"))
            Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
    }
}

private fun propagateDotEnvToSystemPropertiesForProxy() {
    // Expose native `.env` values as system properties for embedded proxy config.
    val keys = listOf(
        "PRESENSI_ALLOWED_HOSTS",
        "PRESENSI_DEFAULT_GATEWAY_URL",
        "PRESENSI_DEFAULT_EP_BASE_URL",
        "PRESENSI_PONOROGO_API_BASE",
        "JATIM_GATEWAY_URL",
        "JATIM_EP_BASE_URL",
        "MADIUN_GATEWAY_URL",
        "MADIUN_EP_BASE_URL",
        "PONOROGO_GATEWAY_URL",
        "PONOROGO_EP_BASE_URL",
        "PONOROGO_API_BASE",
        "PONOROGO_IMAGE_BASE",
        "TETUKO_OCR_URL",
        "TETUKO_OCR_MODEL",
        "TETUKO_OCR_CHARSET",
    )
    keys.forEach { key ->
        if (System.getenv(key).isNullOrBlank() && System.getProperty(key).isNullOrBlank()) {
            DesktopEnv.get(key)?.let { value ->
                runCatching { System.setProperty(key, value) }
            }
        }
    }
}

/**
 * Wires the common UI's export hook to a native AWT save dialog (desktop-only).
 * Keeping this in `desktopApp` avoids AWT dependencies in common/Android code.
 */
private fun installNativeTextExporter() {
    AppServices.textExporter = { defaultName, content ->
        runCatching {
            val dialog = FileDialog(null as Frame?, "Simpan berkas", FileDialog.SAVE).apply {
                file = defaultName
                isVisible = true
            }
            val dir = dialog.directory
            val name = dialog.file
            if (dir != null && name != null) {
                File(dir, name).writeText(content, Charsets.UTF_8)
                true
            } else false
        }.getOrElse { e ->
            DesktopNotifications.error("Gagal menyimpan berkas: ${e.message}")
            false
        }
    }
}

/**
 * Native binary save dialog (e.g. Ponorogo PDF/XLS reports).
 */
private fun installNativeBinaryPreviewer() {
    AppServices.binaryPreviewer = { defaultName, bytes ->
        runCatching {
            val directory = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "tetuko-preview"))
            val file = directory.resolve(defaultName)
            Files.write(file, bytes)
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(file.toFile())
                true
            } else false
        }.getOrDefault(false)
    }
}

private fun installNativeBinaryExporter() {
    AppServices.binaryExporter = { defaultName, bytes ->
        runCatching {
            val dialog = FileDialog(null as Frame?, "Simpan berkas", FileDialog.SAVE).apply {
                file = defaultName
                isVisible = true
            }
            val dir = dialog.directory
            val name = dialog.file
            if (dir != null && name != null) {
                File(dir, name).writeBytes(bytes)
                true
            } else false
        }.getOrElse { e ->
            DesktopNotifications.error("Gagal menyimpan berkas: ${e.message}")
            false
        }
    }
}

/**
 * Persists attendance points per profile under `~/.tetuko/data`. The file is
 * not secret (coordinates only), so it avoids the encrypted session store.
 */
private fun installSavedPointsStore() {
    val dir = Path.of(System.getProperty("user.home"), ".tetuko", "data")
    fun fileFor(profileId: String): Path {
        val safe = profileId.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(40).ifBlank { "profile" }
        return dir.resolve("saved-points-$safe.json")
    }
    AppServices.pointsReader = { profileId ->
        runCatching {
            val file = fileFor(profileId)
            if (Files.exists(file) && Files.size(file) < 1_000_000) Files.readString(file) else null
        }.getOrNull()
    }
    AppServices.pointsWriter = { profileId, json ->
        runCatching {
            Files.createDirectories(dir)
            val target = fileFor(profileId)
            val tmp = dir.resolve(target.fileName.toString() + ".tmp")
            Files.writeString(tmp, json)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
    }
}

private fun writeAutomationAtomic(dir: Path, target: Path, json: String): Boolean = runCatching {
    Files.createDirectories(dir)
    if (Files.exists(target)) {
        val backup = Path.of(target.toString() + ".bak")
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING)
    }
    val tmp = dir.resolve(target.fileName.toString() + ".tmp")
    Files.writeString(tmp, json)
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    true
}.getOrDefault(false)

/** Persists automation presets per profile under `~/.tetuko/data`. */
private fun installPresetStore() {
    val dir = Path.of(System.getProperty("user.home"), ".tetuko", "data")
    fun fileFor(profileId: String): Path {
        val safe = profileId.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(40).ifBlank { "profile" }
        return dir.resolve("presets-$safe.json")
    }
    AppServices.presetReader = { profileId ->
        runCatching {
            val file = fileFor(profileId)
            if (Files.exists(file) && Files.size(file) < 2_000_000) Files.readString(file) else null
        }.getOrNull()
    }
    AppServices.presetWriter = { profileId, json -> writeAutomationAtomic(dir, fileFor(profileId), json) }
}

/** Persists automation execution logs per profile under `~/.tetuko/data`. */
private fun installAutomationLogStore() {
    val dir = Path.of(System.getProperty("user.home"), ".tetuko", "data")
    fun fileFor(profileId: String): Path {
        val safe = profileId.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(40).ifBlank { "profile" }
        return dir.resolve("automation-log-$safe.json")
    }
    AppServices.automationLogReader = { profileId ->
        runCatching {
            val file = fileFor(profileId)
            if (Files.exists(file) && Files.size(file) < 2_000_000) Files.readString(file) else null
        }.getOrNull()
    }
    AppServices.automationLogWriter = { profileId, json -> writeAutomationAtomic(dir, fileFor(profileId), json) }
}



/** Persists automation stock photos per profile under `~/.tetuko/data` (best-effort). */
private fun installStockPhotoStore() {
    val dir = Path.of(System.getProperty("user.home"), ".tetuko", "data")
    fun fileFor(profileId: String): Path {
        val safe = profileId.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(40).ifBlank { "profile" }
        return dir.resolve("automation-stock-$safe.json")
    }
    AppServices.stockPhotoReader = { profileId ->
        runCatching {
            val file = fileFor(profileId)
            if (Files.exists(file) && Files.size(file) < 12_000_000) Files.readString(file) else null
        }.getOrNull()
    }
    AppServices.stockPhotoWriter = { profileId, json -> writeAutomationAtomic(dir, fileFor(profileId), json) }
}

/** Persists per-preset stock photo queues per profile under `~/.tetuko/data` (best-effort). */
private fun installPhotoQueueStore() {
    val dir = Path.of(System.getProperty("user.home"), ".tetuko", "data")
    fun fileFor(profileId: String): Path {
        val safe = profileId.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(40).ifBlank { "profile" }
        return dir.resolve("automation-photo-queue-$safe.json")
    }
    AppServices.photoQueueReader = { profileId ->
        runCatching {
            val file = fileFor(profileId)
            if (Files.exists(file) && Files.size(file) < 2_000_000) Files.readString(file) else null
        }.getOrNull()
    }
    AppServices.photoQueueWriter = { profileId, json -> writeAutomationAtomic(dir, fileFor(profileId), json) }
}

private fun installLocalAccountStore() {
    val firebase = FirebaseAppConfig.load()
    val store = if (firebase.isConfigured) {
        val remote = FirestoreAccountStore(firebase)
        runCatching { remote.ensureBootstrapAdmin(defaultUsername = "admin", defaultPassword = "admin") }
            .onFailure { e ->
                DesktopNotifications.error(
                    "Gagal menyiapkan akun awal di Firestore: ${e.message ?: "periksa koneksi/konfigurasi"}"
                )
            }
        AppServices.automationCloudReader = { accountId, profileId -> remote.readAutomationSync(accountId, profileId) }
        AppServices.automationCloudWriter = { accountId, profileId, json -> remote.writeAutomationSync(accountId, profileId, json) }
        remote
    } else {
        val status = FirebaseAppConfig.statusMessage(firebase)
            ?: "Konfigurasi Firebase tidak terbaca. Gunakan FIREBASE_PROJECT_ID dan FIREBASE_API_KEY di environment variable atau file .env."
        val local = DesktopLocalAccountStore(
            sourceLabel = "Akun lokal perangkat (terenkripsi) — fallback (Firebase tidak aktif)",
            sourceError = null,
        )
        // Bootstrap a default local admin only for local fallback mode.
        runCatching { local.ensureBootstrapAdmin(defaultUsername = "admin", defaultPassword = "admin") }
        // Expose explicit status through notifications/log context is limited; UI shows label.
        // We attach status in label by using a dedicated wrapper would be nicer, but keep simple:
        // store label is fixed; MasukScreen shows label. We'll also set a one-time hint via notifier? no UI hook.
        // Instead, use a local store variant that returns informational status as label detail.
        object : id.tetuko.app.service.LocalAccountStore by local {
            override fun accountSourceLabel(): String? =
                "Akun lokal perangkat (terenkripsi). $status"
        }
    }
    AppServices.localAccountStore = store
}

/**
 * Wires the common notification hook to desktop tray balloons.
 */
private fun installNotifier() {
    AppServices.notifier = { title, message ->
        runCatching { DesktopNotifications.notify(title = title, message = message) }
    }
}

/**
 * Native open dialog for attachments. Kept in `desktopApp` so common code has
 * no AWT dependency; the returned bytes are uploaded via the embedded proxy.
 */
private fun installNativeFilePicker() {
    AppServices.filePicker = { title, extensions ->
        runCatching {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
                if (extensions.isNotEmpty()) {
                    setFilenameFilter { _, name ->
                        val lower = name.lowercase()
                        extensions.any { lower.endsWith(".${it.lowercase()}") }
                    }
                }
                isVisible = true
            }
            val directory = dialog.directory
            val name = dialog.file
            if (directory != null && name != null) {
                val file = File(directory, name)
                val mime = when (file.extension.lowercase()) {
                    "pdf" -> "application/pdf"
                    "png" -> "image/png"
                    else -> "image/jpeg"
                }
                PickedFile(file.name, mime, file.readBytes())
            } else {
                null
            }
        }.getOrElse { e ->
            DesktopNotifications.error("Gagal membuka berkas: ${e.message}")
            null
        }
    }
}

