package id.tetuko.app.service

import id.tetuko.core.data.ponorogo.PonorogoClient
import id.tetuko.core.data.rpc.RpcClient
import id.tetuko.core.data.rpc.ServerContext
import id.tetuko.core.data.web.ScopedPersistentCookiesStorage
import id.tetuko.core.data.web.WebPresensiClient
import id.tetuko.core.domain.model.DefaultProfiles
import id.tetuko.core.domain.model.ServerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Simple service holder for RPC client and runtime session context.
 * This is a pragmatic singleton for the desktop/mobile app shell;
 * can be migrated to Koin DI later.
 */
object AppServices {
    val rpcClient: RpcClient = RpcClient(baseUrlProvider = { rpcBaseUrl }).apply {
        gatewayUrlProvider = { profileId -> DefaultProfiles.byId(profileId).gatewayUrl }
    }

    /** Platform hooks for the encrypted, per-profile/NIP Web Presensi cookie jar. */
    var webCookieReader: ((String) -> String?)? = null
    var webCookieWriter: ((String, String) -> Boolean)? = null
    var webCookieDeleter: ((String) -> Boolean)? = null

    private val webCookieStorage = ScopedPersistentCookiesStorage(
        reader = { scope -> webCookieReader?.invoke(scope) },
        writer = { scope, value -> webCookieWriter?.invoke(scope, value) ?: false },
        deleter = { scope -> webCookieDeleter?.invoke(scope) ?: true },
    )

    /** Portal `/ep` client; owns a scoped persistent cookie jar (see [WebPresensiClient]). */
    val webPresensi: WebPresensiClient = WebPresensiClient(
        baseUrlProvider = { rpcBaseUrl },
        persistentStorage = webCookieStorage,
    )

    /** Ponorogo endpoint bridge (generic `/api/proxy` contract). */
    val ponorogoClient: PonorogoClient = PonorogoClient(baseUrlProvider = { rpcBaseUrl }).apply {
        apiBaseUrlProvider = { activeProfile?.ponorogoApiBase }
        imageBaseUrlProvider = { activeProfile?.ponorogoImageBase }
        webBaseUrlProvider = { activeProfile?.epBaseUrl }
    }

    /** Raw profile returned by `/login/do_LoginMobile`, kept for Ponorogo pages. */
    var ponorogoProfile: JsonObject? = null

    // The desktop entry point starts an embedded proxy on this address.
    var rpcBaseUrl: String = "http://127.0.0.1:14200"

    private val _serverContext = MutableStateFlow(ServerContext())
    val serverContext: StateFlow<ServerContext> = _serverContext.asStateFlow()

    /**
     * Platform-provided native save dialog hook (desktopApp wires this to AWT FileDialog).
     * Common UI calls this only when non-null, so other targets degrade gracefully.
     * Returns true when the file was written, false when the user cancelled.
     */
    var textExporter: ((defaultName: String, content: String) -> Boolean)? = null

    /**
     * Platform-provided native open dialog hook for attachments (desktopApp uses
     * AWT FileDialog). `null` on targets without a picker, so the UI hides upload.
     */
    var filePicker: ((title: String, extensions: List<String>) -> PickedFile?)? = null

    /** Async platform file picker for Android SAF; desktop continues using [filePicker]. */
    var filePickerAsync: ((title: String, extensions: List<String>, onPicked: (PickedFile?) -> Unit) -> Unit)? = null

    /** Async native camera capture hook; null on platforms without camera support. */
    var cameraCaptureAsync: (((PickedFile?) -> Unit) -> Unit)? = null

    /** Platform image crop/encode hook; crop bounds are normalized to the source image. */
    var imageCropper: ((PickedFile, ImageCropRect) -> PickedFile?)? = null

    /** Normalize an image by correcting orientation, scaling, and JPEG encoding. */
    var imageProcessor: ((PickedFile, Int, Int) -> PickedFile?)? = null

    val hasFilePicker: Boolean
        get() = filePicker != null || filePickerAsync != null

    val hasCameraCapture: Boolean
        get() = cameraCaptureAsync != null

    fun requestFilePicker(
        title: String,
        extensions: List<String>,
        onPicked: (PickedFile?) -> Unit,
    ): Boolean {
        val picker = filePicker
        if (picker != null) {
            onPicked(picker(title, extensions))
            return true
        }
        val asyncPicker = filePickerAsync ?: return false
        asyncPicker(title, extensions, onPicked)
        return true
    }

    fun requestCameraCapture(onCaptured: (PickedFile?) -> Unit): Boolean {
        val capture = cameraCaptureAsync ?: return false
        capture(onCaptured)
        return true
    }

    /** Native save dialog for binary content (PDF/XLS reports, attachments). */
    var binaryExporter: ((defaultName: String, bytes: ByteArray) -> Boolean)? = null

    /** Platform-provided viewer hook for downloaded PDF/XLS reports. */
    var binaryPreviewer: ((defaultName: String, bytes: ByteArray) -> Boolean)? = null

    data class ApiTrafficEntry(
        val id: Long,
        val method: String,
        val path: String,
        val status: Int,
        val request: String,
        val response: String,
        val waktu: Long,
    )

    private val apiTraffic = mutableListOf<ApiTrafficEntry>()
    private val apiTrafficListeners = mutableListOf<(List<ApiTrafficEntry>) -> Unit>()
    private val apiTrafficLock = Any()
    private val secretFieldNames = setOf(
        "api_key", "apikey", "token", "authorization", "password", "password_lama",
        "password_sekarang", "password_baru", "passwordhash", "passwordencrypted", "captcha",
        "m_user[password]", "m_user[captcha]",
    )

    fun apiTrafficSnapshot(): List<ApiTrafficEntry> = synchronized(apiTrafficLock) { apiTraffic.toList() }

    fun subscribeApiTraffic(listener: (List<ApiTrafficEntry>) -> Unit): () -> Unit {
        val snapshot = synchronized(apiTrafficLock) {
            apiTrafficListeners.add(listener)
            apiTraffic.toList()
        }
        listener(snapshot)
        return { synchronized(apiTrafficLock) { apiTrafficListeners.remove(listener) } }
    }

    fun clearApiTraffic() {
        val (snapshot, listeners) = synchronized(apiTrafficLock) {
            apiTraffic.clear()
            apiTraffic.toList() to apiTrafficListeners.toList()
        }
        listeners.forEach { it(snapshot) }
    }

    internal fun recordApiTraffic(method: String, path: String, status: Int, request: String, response: String) {
        val entry = ApiTrafficEntry(
            id = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
            method = method,
            path = path,
            status = status,
            request = redactTraffic(request).take(20_000),
            response = redactTraffic(response).take(20_000),
            waktu = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
        )
        val (snapshot, listeners) = synchronized(apiTrafficLock) {
            apiTraffic.add(0, entry)
            if (apiTraffic.size > 100) apiTraffic.removeAt(apiTraffic.lastIndex)
            apiTraffic.toList() to apiTrafficListeners.toList()
        }
        listeners.forEach { it(snapshot) }
    }

    private fun redactTraffic(raw: String): String {
        val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        if (parsed is JsonObject || parsed is JsonArray) return redactTrafficElement(parsed).toString()
        return Regex("(?i)(api[_-]?key|token|authorization|password(?:_[a-z]+)?|captcha)(\\s*[=:]\\s*)([^&\\s,;]+)")
            .replace(raw) { match -> "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]" }
    }

    private fun redactTrafficElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            if (key.lowercase() in secretFieldNames) JsonPrimitive("[REDACTED]") else redactTrafficElement(value)
        })
        is JsonArray -> JsonArray(element.map(::redactTrafficElement))
        else -> element
    }

    init {
        rpcClient.onTraffic = { method, path, status, request, response ->
            recordApiTraffic(method, path, status, request, response)
        }
        ponorogoClient.onTraffic = { method, path, status, request, response ->
            recordApiTraffic(method, path, status, request, response)
        }
        webPresensi.onTraffic = { method, path, status, request, response ->
            recordApiTraffic(method, path, status, request, response)
        }
    }

    /** Platform persistence for saved attendance points (desktop: JSON file). */
    var pointsReader: ((profileId: String) -> String?)? = null
    var pointsWriter: ((profileId: String, json: String) -> Boolean)? = null

    /** Platform persistence for automation presets per profile (desktop: JSON file). */
    var presetReader: ((profileId: String) -> String?)? = null
    var presetWriter: ((profileId: String, json: String) -> Boolean)? = null

    /** Platform persistence for automation execution logs per profile (desktop: JSON file). */
    var automationLogReader: ((profileId: String) -> String?)? = null
    var automationLogWriter: ((profileId: String, json: String) -> Boolean)? = null

    /** Platform persistence for automation stock photos per profile (desktop: JSON file, best-effort). */
    var stockPhotoReader: ((profileId: String) -> String?)? = null
    var stockPhotoWriter: ((profileId: String, json: String) -> Boolean)? = null

    /** Platform persistence for per-preset photo queues (desktop: JSON file, best-effort). */
    var photoQueueReader: ((profileId: String) -> String?)? = null
    var photoQueueWriter: ((profileId: String, json: String) -> Boolean)? = null

    /** Live Pegawai/UNOR providers; null means live API is unavailable for the current runtime. */
    var livePegawaiProvider: (suspend (profileId: String, query: String, page: Int, size: Int) -> String?)? = null
    var liveUnorProvider: (suspend (profileId: String, query: String) -> String?)? = null

    /** Cloud automation metadata hooks (metadata only; never credentials/photos). */
    var automationCloudReader: ((accountId: String, profileId: String) -> String?)? = null
    var automationCloudWriter: ((accountId: String, profileId: String, json: String) -> Boolean)? = null

    /** Global scheduler setting; only runtimes with a background scheduler provide these hooks. */
    var automationSchedulerEnabledReader: (() -> Boolean)? = null
    var automationSchedulerEnabledWriter: ((Boolean) -> Boolean)? = null
    var automationExactAlarmAvailableReader: (() -> Boolean)? = null
    var exactAlarmSettingsOpener: (() -> Boolean)? = null

    val canControlAutomationScheduler: Boolean
        get() = automationSchedulerEnabledReader != null && automationSchedulerEnabledWriter != null

    fun isAutomationSchedulerEnabled(): Boolean = automationSchedulerEnabledReader?.invoke() ?: false

    fun setAutomationSchedulerEnabled(enabled: Boolean): Boolean =
        automationSchedulerEnabledWriter?.invoke(enabled) ?: false

    fun isAutomationExactAlarmAvailable(): Boolean = automationExactAlarmAvailableReader?.invoke() ?: true

    /** Persisted native automation session snapshot (Android/desktop adapters). */
    var autostartEnabledReader: (() -> Boolean)? = null
    var autostartEnabledWriter: ((Boolean) -> Boolean)? = null
    val canControlAutostart: Boolean get() = autostartEnabledReader != null && autostartEnabledWriter != null
    fun isAutostartEnabled(): Boolean = autostartEnabledReader?.invoke() ?: false
    fun setAutostartEnabled(enabled: Boolean): Boolean = autostartEnabledWriter?.invoke(enabled) ?: false

    var automationSessionWriter: ((ServerProfile, ServerContext, JsonObject?) -> Boolean)? = null
    var automationSessionClearer: (() -> Boolean)? = null

    /** Optional native/manual preset runner. Desktop implements it; Android reports unavailable until a background executor exists. */
    var manualAutomationRunner: ((String, AutomationPreset) -> AutomationManualResult)? = null

    /** Productivity reminder settings and persisted send state. */
    var productivityReminderEnabledReader: (() -> Boolean)? = null
    var productivityReminderEnabledWriter: ((Boolean) -> Boolean)? = null
    var productivityReminderLimitReader: (() -> String)? = null
    var productivityReminderLimitWriter: ((String) -> Boolean)? = null
    var productivityReminderDaysReader: (() -> List<Int>)? = null
    var productivityReminderDaysWriter: ((List<Int>) -> Boolean)? = null
    var productivityReminderStateReader: ((String) -> String?)? = null
    var productivityReminderStateWriter: ((String, String) -> Boolean)? = null

    fun isProductivityReminderEnabled(): Boolean = productivityReminderEnabledReader?.invoke() ?: true
    fun setProductivityReminderEnabled(enabled: Boolean): Boolean = productivityReminderEnabledWriter?.invoke(enabled) ?: false
    fun productivityReminderLimit(): String = productivityReminderLimitReader?.invoke()?.takeIf { it.isNotBlank() } ?: "08:30"
    fun setProductivityReminderLimit(limit: String): Boolean = productivityReminderLimitWriter?.invoke(limit) ?: false
    fun productivityReminderDays(): List<Int> = productivityReminderDaysReader?.invoke()?.takeIf { it.isNotEmpty() } ?: listOf(1, 2, 3, 4, 5)
    fun setProductivityReminderDays(days: List<Int>): Boolean = productivityReminderDaysWriter?.invoke(days) ?: false

    /** Optional platform notification hook (desktop tray balloon / Android notification). */
    var notifier: ((title: String, message: String) -> Unit)? = null

    /** Local app-account store/auth (desktop-first). Null when unavailable on a platform. */
    var localAccountStore: LocalAccountStore? = null

    var activeProfile: ServerProfile? = null
        private set

    fun setActiveProfile(profile: ServerProfile?) {
        if (profile?.id != activeProfile?.id) webPresensi.resetSession()
        activeProfile = profile
    }

    fun updateServerContext(apiKey: String? = null, lastLatLong: String? = null, imei: String? = null) {
        val cur = _serverContext.value
        val next = cur.copy(
            apiKey = apiKey ?: cur.apiKey,
            lastLatLong = lastLatLong ?: cur.lastLatLong,
            imei = imei ?: cur.imei,
        )
        _serverContext.value = next
        val profile = activeProfile
        if (profile != null) automationSessionWriter?.invoke(profile, next, ponorogoProfile)
    }

    fun clearServerSession() {
        _serverContext.value = ServerContext()
        automationSessionClearer?.invoke()
        activeProfile = null
        ponorogoProfile = null
        webPresensi.resetSession()
    }

    /** True when a server profile is selected and an authenticated API key is present. */
    val hasActiveSession: Boolean
        get() = activeProfile != null && _serverContext.value.apiKey.isNotBlank()

    fun currentProfileId(): String? = activeProfile?.id

    fun isProfileActive(profileId: String): Boolean = activeProfile?.id == profileId && hasActiveSession
}

/** A file chosen through a platform picker, kept in memory until uploaded. */
class PickedFile(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
)

data class ImageCropRect(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
)

/** Locally saved attendance point (mirrors `TitikAbsen` in the Tauri app). */
@Serializable
data class SavedPoint(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double = 100.0,
    val active: Boolean = false,
    val createdAt: Long = 0L,
)

object SavedPointsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(points: List<SavedPoint>): String = json.encodeToString(points)

    fun decode(raw: String?): List<SavedPoint> =
        if (raw.isNullOrBlank()) emptyList() else json.decodeFromString(raw)
}

// Canonical automation models live in core.domain.model to keep platform executors/schedulers consistent.
typealias AutomationPreset = id.tetuko.core.domain.model.AutomationPreset
typealias AutomationExecutionState = id.tetuko.core.domain.model.AutomationExecutionState
typealias AutomationManualResult = id.tetuko.core.domain.model.AutomationManualResult
typealias AutomationLogEntry = id.tetuko.core.domain.model.AutomationLogEntry
typealias AutomationStockPhoto = id.tetuko.core.domain.model.AutomationStockPhoto
typealias AutomationPhotoQueueState = id.tetuko.core.domain.model.AutomationPhotoQueueState

object PresetCodec {
    fun encode(presets: List<AutomationPreset>): String = id.tetuko.core.domain.model.PresetCodec.encode(presets)
    fun decode(raw: String?): List<AutomationPreset> = id.tetuko.core.domain.model.PresetCodec.decode(raw)
}

object AutomationLogCodec {
    fun encode(logs: List<AutomationLogEntry>): String = id.tetuko.core.domain.model.AutomationLogCodec.encode(logs)
    fun decode(raw: String?): List<AutomationLogEntry> = id.tetuko.core.domain.model.AutomationLogCodec.decode(raw)
}

object StockPhotoCodec {
    fun encode(photos: List<AutomationStockPhoto>): String = id.tetuko.core.domain.model.StockPhotoCodec.encode(photos)
    fun decode(raw: String?): List<AutomationStockPhoto> = id.tetuko.core.domain.model.StockPhotoCodec.decode(raw)
}

object PhotoQueueCodec {
    fun encode(state: AutomationPhotoQueueState): String = id.tetuko.core.domain.model.PhotoQueueCodec.encode(state)
    fun decode(raw: String?): AutomationPhotoQueueState = id.tetuko.core.domain.model.PhotoQueueCodec.decode(raw)
}
