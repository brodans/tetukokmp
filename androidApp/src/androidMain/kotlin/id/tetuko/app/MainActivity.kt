package id.tetuko.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import id.tetuko.app.service.AppServices
import id.tetuko.app.service.ImageCropRect
import id.tetuko.app.service.PickedFile
import id.tetuko.app.ui.TetukoApp
import id.tetuko.app.ui.isSystemDarkModeDetected
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val requested = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
            val missing = requested.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        }
        setContent {
            val context = LocalContext.current
            var fileResult by remember { mutableStateOf<((PickedFile?) -> Unit)?>(null) }
            var cameraResult by remember { mutableStateOf<((PickedFile?) -> Unit)?>(null) }
            var cameraFile by remember { mutableStateOf<File?>(null) }
            var pendingTextExport by remember { mutableStateOf<Pair<String, String>?>(null) }
            var pendingBinaryExport by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }

            val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val callback = fileResult
                fileResult = null
                callback?.invoke(uri?.let { readPickedFile(context, it) })
            }
            val textExporterLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                val pending = pendingTextExport
                pendingTextExport = null
                if (uri != null && pending != null) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(pending.second.toByteArray(Charsets.UTF_8)) }
                    }
                }
            }
            val binaryExporterLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
                val pending = pendingBinaryExport
                pendingBinaryExport = null
                if (uri != null && pending != null) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(pending.second) }
                    }
                }
            }
            val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                val callback = cameraResult
                val file = cameraFile
                cameraResult = null
                cameraFile = null
                val captured = if (success && file != null) normalizeCameraImage(file) else null
                file?.delete()
                callback?.invoke(captured)
            }

            SideEffect {
                installAndroidNativeStores(context)
                val accountStore = AppServices.localAccountStore as? AndroidLocalAccountStore
                    ?: AndroidLocalAccountStore(context).also { AppServices.localAccountStore = it }
                runCatching { accountStore.ensureBootstrapAdmin() }
                installAndroidNotifications(context)
                TetukoAndroidAutomation.install(context)
                AppServices.manualAutomationRunner = { profileId, preset ->
                    TetukoAndroidAutomation.runManual(context, profileId, preset)
                }
                AppServices.automationExactAlarmAvailableReader = {
                    context.getSharedPreferences("tetuko_native_stores", Context.MODE_PRIVATE)
                        .getBoolean("automation_exact_alarm_available", true)
                }
                AppServices.exactAlarmSettingsOpener = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching {
                            val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            true
                        }.getOrDefault(false)
                    } else true
                }
                AppServices.textExporter = { defaultName, content ->
                    pendingTextExport = defaultName to content
                    textExporterLauncher.launch(defaultName)
                    true
                }
                AppServices.binaryExporter = { defaultName, bytes ->
                    pendingBinaryExport = defaultName to bytes
                    binaryExporterLauncher.launch(defaultName)
                    true
                }
                AppServices.binaryPreviewer = { defaultName, bytes ->
                    runCatching {
                        val previewDir = File(context.cacheDir, "report-preview").apply { mkdirs() }
                        val previewFile = File(previewDir, defaultName)
                        previewFile.writeBytes(bytes)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", previewFile)
                        val mime = if (defaultName.lowercase().endsWith(".pdf")) "application/pdf" else "application/vnd.ms-excel"
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(Intent.createChooser(intent, "Pratinjau laporan"))
                        true
                    }.getOrDefault(false)
                }
                AppServices.filePickerAsync = { _, extensions, callback ->
                    fileResult = callback
                    documentPicker.launch(mimeTypesFor(extensions))
                }
                AppServices.cameraCaptureAsync = { callback ->
                    cameraResult = callback
                    val directory = File(context.cacheDir, "camera").apply { mkdirs() }
                    val output = File(directory, "capture-${System.currentTimeMillis()}.jpg")
                    cameraFile = output
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
                    cameraLauncher.launch(uri)
                }
                AppServices.imageCropper = ::cropPickedImage
                AppServices.imageProcessor = ::processPickedImage
            }
            TetukoApp()
        }
    }
}

private fun installAndroidNativeStores(context: Context) {
    val prefs = context.getSharedPreferences("tetuko_native_stores", Context.MODE_PRIVATE)

    fun profileKey(profileId: String, suffix: String) = "${profileId.trim()}:$suffix"

    val cookieAlias = "tetuko_web_presensi_cookie_aes_v1"
    fun cookieKey(scope: String): String = "web_cookie:" + MessageDigest.getInstance("SHA-256")
        .digest(scope.trim().encodeToByteArray()).joinToString("") { "%02x".format(it) }
    fun cookieSecret(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(cookieAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(cookieAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
    fun encryptCookie(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, cookieSecret())
        val payload = cipher.iv + cipher.doFinal(value.encodeToByteArray())
        return Base64.getEncoder().encodeToString(payload)
    }
    fun decryptCookie(value: String): String? = runCatching {
        val payload = Base64.getDecoder().decode(value)
        val iv = payload.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, cookieSecret(), GCMParameterSpec(128, iv))
        cipher.doFinal(payload.copyOfRange(12, payload.size)).decodeToString()
    }.getOrNull()

    AppServices.webCookieReader = { scope -> prefs.getString(cookieKey(scope), null)?.let(::decryptCookie) }
    AppServices.webCookieWriter = { scope, value -> prefs.edit().putString(cookieKey(scope), encryptCookie(value)).commit() }
    AppServices.webCookieDeleter = { scope -> prefs.edit().remove(cookieKey(scope)).commit() }

    AppServices.pointsReader = { profileId -> prefs.getString(profileKey(profileId, "points"), null) }
    AppServices.pointsWriter = { profileId, json -> prefs.edit().putString(profileKey(profileId, "points"), json).commit() }
    AppServices.presetReader = { profileId -> prefs.getString(profileKey(profileId, "presets"), null) }
    AppServices.presetWriter = { profileId, json -> prefs.edit().putString(profileKey(profileId, "presets"), json).commit() }
    AppServices.automationLogReader = { profileId -> prefs.getString(profileKey(profileId, "automation_logs"), null) }
    AppServices.automationLogWriter = { profileId, json -> prefs.edit().putString(profileKey(profileId, "automation_logs"), json).commit() }
    AppServices.stockPhotoReader = { profileId -> prefs.getString(profileKey(profileId, "stock_photos"), null) }
    AppServices.stockPhotoWriter = { profileId, json -> prefs.edit().putString(profileKey(profileId, "stock_photos"), json).commit() }
    AppServices.photoQueueReader = { profileId -> prefs.getString(profileKey(profileId, "photo_queues"), null) }
    AppServices.photoQueueWriter = { profileId, json -> prefs.edit().putString(profileKey(profileId, "photo_queues"), json).commit() }
    if (AndroidAutomationCloudStore.isConfigured()) {
        AppServices.automationCloudReader = { accountId, profileId -> AndroidAutomationCloudStore.read(accountId, profileId) }
        AppServices.automationCloudWriter = { accountId, profileId, json -> AndroidAutomationCloudStore.write(accountId, profileId, json) }
    }
    AppServices.automationSessionWriter = { profile, ctx, ponorogoProfile ->
        prefs.edit()
            .putString("active_profile_id", profile.id)
            .putString("encrypted_active_api_key", AndroidAutomationSecureStore.encrypt(context, ctx.apiKey))
            .remove("active_api_key")
            .putString("active_imei", ctx.imei)
            .putString("active_last_latlong", ctx.lastLatLong)
            .putString("active_gateway_url", profile.gatewayUrl)
            .putString("active_ponorogo_api_base", profile.ponorogoApiBase.orEmpty())
            .putString("active_ponorogo_profile", ponorogoProfile?.toString())
            .commit()
    }
    AppServices.productivityReminderEnabledReader = { prefs.getBoolean("productivity_reminder_enabled", true) }
    AppServices.productivityReminderEnabledWriter = { enabled -> prefs.edit().putBoolean("productivity_reminder_enabled", enabled).commit() }
    AppServices.productivityReminderLimitReader = { prefs.getString("productivity_reminder_limit", "08:30") ?: "08:30" }
    AppServices.productivityReminderLimitWriter = { limit -> prefs.edit().putString("productivity_reminder_limit", limit).commit() }
    AppServices.productivityReminderDaysReader = {
        prefs.getString("productivity_reminder_days", "1,2,3,4,5")
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() } ?: listOf(1, 2, 3, 4, 5)
    }
    AppServices.productivityReminderDaysWriter = { days -> prefs.edit().putString("productivity_reminder_days", days.joinToString(",")).commit() }
    AppServices.productivityReminderStateReader = { key -> prefs.getString("reminder:$key", null) }
    AppServices.productivityReminderStateWriter = { key, value -> prefs.edit().putString("reminder:$key", value).commit() }
    AppServices.automationSchedulerEnabledReader = {
        prefs.getBoolean("automation_scheduler_enabled", true)
    }
    AppServices.automationSchedulerEnabledWriter = { enabled ->
        val saved = prefs.edit().putBoolean("automation_scheduler_enabled", enabled).commit()
        if (saved) TetukoAndroidAutomation.install(context)
        saved
    }
    AppServices.automationSessionClearer = {
        prefs.edit()
            .remove("active_profile_id")
            .remove("active_api_key")
            .remove("encrypted_active_api_key")
            .remove("active_imei")
            .remove("active_last_latlong")
            .remove("active_gateway_url")
            .remove("active_ponorogo_api_base")
            .remove("active_ponorogo_profile")
            .commit()
    }
}

private fun installAndroidNotifications(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel("tetuko_automation", "TETUKO Otomasi", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "Pengingat dan hasil automasi TETUKO"
    }
    manager.createNotificationChannel(channel)
    AppServices.notifier = { title, message ->
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val notification = NotificationCompat.Builder(context, "tetuko_automation")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        }
    }
}

private fun mimeTypesFor(extensions: List<String>): Array<String> {
    val mimeByExtension = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
        "heic" to "image/heic", "heif" to "image/heif", "pdf" to "application/pdf", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )
    return extensions.mapNotNull { mimeByExtension[it.lowercase()] }.distinct().ifEmpty { listOf("*/*") }.toTypedArray()
}

private fun readPickedFile(context: Context, uri: Uri): PickedFile? = runCatching {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "lampiran"
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    PickedFile(name, context.contentResolver.getType(uri) ?: mimeTypeFor(name), bytes)
}.getOrNull()

private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "heic", "heif" -> "image/heic"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    else -> "image/jpeg"
}

private fun normalizeCameraImage(file: File): PickedFile? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sampleSize = 1
    while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > 640) sampleSize *= 2
    val source = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    ) ?: return null
    val matrix = Matrix()
    when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
    }
    val oriented = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (oriented !== source) source.recycle()
    val scale = minOf(1f, 320f / max(oriented.width, oriented.height))
    val width = (oriented.width * scale).roundToInt().coerceAtLeast(1)
    val height = (oriented.height * scale).roundToInt().coerceAtLeast(1)
    val resized = if (width == oriented.width && height == oriented.height) oriented else Bitmap.createScaledBitmap(oriented, width, height, true)
    val bytes = ByteArrayOutputStream().use { output ->
        resized.compress(Bitmap.CompressFormat.JPEG, 95, output)
        output.toByteArray()
    }
    if (resized !== oriented) oriented.recycle()
    resized.recycle()
    PickedFile("camera-capture.jpg", "image/jpeg", bytes)
}.getOrNull()

private fun processPickedImage(file: PickedFile, maxSide: Int, qualityPercent: Int): PickedFile? = runCatching {
    val decoded = BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size) ?: return null
    val exif = ExifInterface(ByteArrayInputStream(file.bytes))
    val matrix = Matrix()
    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
    }
    val source = if (!matrix.isIdentity) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { decoded.recycle() } else decoded
    val scale = minOf(1f, maxSide.toFloat() / max(source.width, source.height))
    val width = (source.width * scale).roundToInt().coerceAtLeast(1)
    val height = (source.height * scale).roundToInt().coerceAtLeast(1)
    val resized = if (width == source.width && height == source.height) source else Bitmap.createScaledBitmap(source, width, height, true)
    val bytes = ByteArrayOutputStream().use { output ->
        resized.compress(Bitmap.CompressFormat.JPEG, qualityPercent.coerceIn(10, 100), output)
        output.toByteArray()
    }
    if (resized !== source) source.recycle()
    resized.recycle()
    PickedFile("photo-${maxSide}.jpg", "image/jpeg", bytes)
}.getOrNull()

private fun cropPickedImage(file: PickedFile, rect: ImageCropRect): PickedFile? = runCatching {
    val source = BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size) ?: return null
    val left = (rect.left * source.width).roundToInt().coerceIn(0, source.width - 1)
    val top = (rect.top * source.height).roundToInt().coerceIn(0, source.height - 1)
    val width = (rect.width * source.width).roundToInt().coerceIn(1, source.width - left)
    val height = (rect.height * source.height).roundToInt().coerceIn(1, source.height - top)
    val crop = Bitmap.createBitmap(source, left, top, width, height)
    if (crop !== source) source.recycle()
    val square = Bitmap.createScaledBitmap(crop, 400, 400, true)
    if (square !== crop) crop.recycle()
    val bytes = ByteArrayOutputStream().use { output ->
        square.compress(Bitmap.CompressFormat.JPEG, 85, output)
        output.toByteArray()
    }
    square.recycle()
    PickedFile("foto-profil-crop.jpg", "image/jpeg", bytes)
}.getOrNull()
