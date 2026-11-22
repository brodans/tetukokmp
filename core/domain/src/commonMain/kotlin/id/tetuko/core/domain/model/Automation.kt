package id.tetuko.core.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Automation preset (parity target: presetOtomatisasi / presetAutomasi). */
@Serializable
data class AutomationPreset(
    val id: String,
    val nama: String,
    val tipe: String = "datang", // datang | pulang | custom
    val lokasiIds: List<String> = emptyList(),
    val workcodeId: String = "",
    val stockFotoIds: List<String> = emptyList(),
    val jamMulai: String = "07:00",
    val jamSelesai: String = "09:00",
    val hariAktif: List<Int> = listOf(1, 2, 3, 4, 5),
    val aktif: Boolean = false,
    val dibuatPada: Long = 0L,
    val diperbarui: Long = 0L,
    val schemaVersion: Int = 1,
)

object PresetCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(presets: List<AutomationPreset>): String = json.encodeToString(presets)

    fun decode(raw: String?): List<AutomationPreset> =
        if (raw.isNullOrBlank()) emptyList() else runCatching {
            json.decodeFromString<List<AutomationPreset>>(raw)
        }.getOrDefault(emptyList())
}

enum class AutomationExecutionState { PENDING, SUCCESS, FAILED, SKIPPED, RETRYABLE, AMBIGUOUS }

@Serializable
data class AutomationManualResult(
    val status: String,
    val message: String,
    val reasonCode: String = "",
    val errorClass: String = "",
    val httpStatus: Int = 0,
)

@Serializable
data class AutomationLogEntry(
    val id: String,
    val presetId: String = "",
    val presetNama: String = "",
    val waktu: Long = 0L,
    val status: String = "INFO", // INFO | SUCCESS | FAILED | SKIPPED | QUEUED
    val pesan: String = "",
    val source: String = "manual", // manual | scheduler
    val dateWib: String = "",
    val plannedAt: Long = 0L,
    val firedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val latLong: String = "",
    val locationId: String = "",
    val workcodeId: String = "",
    val photoId: String = "",
    val attempt: Int = 1,
    val reasonCode: String = "",
    val errorClass: String = "",
    val httpStatus: Int = 0,
    val correlationId: String = "",
    val schemaVersion: Int = 1,
)

object AutomationLogCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(logs: List<AutomationLogEntry>): String = json.encodeToString(logs)

    fun decode(raw: String?): List<AutomationLogEntry> =
        if (raw.isNullOrBlank()) emptyList() else runCatching {
            json.decodeFromString<List<AutomationLogEntry>>(raw)
        }.getOrDefault(emptyList())
}

/** Locally stored automation stock photo (base64 payload, best-effort/local-only). */
@Serializable
data class AutomationStockPhoto(
    val id: String,
    val nama: String = "",
    val mimeType: String = "image/jpeg",
    /** Raw base64 data (without data: prefix) or data URL; consumers should normalize as needed. */
    val dataBase64: String = "",
    val ukuranBytes: Long = 0L,
    val dibuatPada: Long = 0L,
    val sha256: String = "",
    val schemaVersion: Int = 1,
)

object StockPhotoCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    fun encode(photos: List<AutomationStockPhoto>): String = json.encodeToString(photos)

    fun decode(raw: String?): List<AutomationStockPhoto> =
        if (raw.isNullOrBlank()) emptyList() else runCatching {
            json.decodeFromString<List<AutomationStockPhoto>>(raw)
        }.getOrDefault(emptyList())
}

/** Shuffled queue of stock photo IDs for a preset (local automation helper). */
@Serializable
data class AutomationPhotoQueueState(
    val queues: Map<String, List<String>> = emptyMap(),
    val schemaVersion: Int = 1,
)

object PhotoQueueCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    fun encode(state: AutomationPhotoQueueState): String = json.encodeToString(state)

    fun decode(raw: String?): AutomationPhotoQueueState =
        if (raw.isNullOrBlank()) AutomationPhotoQueueState() else runCatching {
            json.decodeFromString<AutomationPhotoQueueState>(raw)
        }.getOrDefault(AutomationPhotoQueueState())
}

/** Idempotency key for automation execution dedup across schedulers/restarts. */
data class AutomationExecutionKey(
    val profileId: String,
    val presetId: String,
    val dateWib: String,
    val tipe: String,
)

/** Minimal ledger contract for reliable automation execution. */
interface AutomationExecutionLedger {
    fun hasTerminalExecution(key: AutomationExecutionKey): Boolean
    fun record(entry: AutomationLogEntry)
    fun recent(profileId: String, limit: Int = 120): List<AutomationLogEntry>
}
