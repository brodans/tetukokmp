package id.tetuko.core.domain.model

data class AutomationRunInput(
    val preset: AutomationPreset,
    val profileId: String,
    val hasActiveSession: Boolean,
    val activeProfileId: String?,
    val requiresWorkCode: Boolean,
    val requiresPhoto: Boolean,
    val availableStockPhotoIds: Set<String>,
    val nowMinute: Int,
    val dateWib: String,
    val nowMs: Long,
    val alreadyTerminalToday: Boolean,
    val nextRetryAtMs: Long = 0L,
)

data class AutomationRunPlan(
    val decision: AutomationExecutionDecision,
    val readiness: AutomationReadiness,
)

object AutomationRunCoordinator {
    fun plan(input: AutomationRunInput): AutomationRunPlan {
        val ctx = AutomationExecutionContext(
            profileId = input.profileId,
            hasActiveSession = input.hasActiveSession,
            activeProfileId = input.activeProfileId,
            requiresWorkCode = input.requiresWorkCode,
            requiresPhoto = input.requiresPhoto,
            availableStockPhotoIds = input.availableStockPhotoIds,
            nowMinute = input.nowMinute,
            dateWib = input.dateWib,
        )
        val readiness = AutomationExecutionPolicy.preflight(input.preset, ctx)
        val decision = AutomationExecutionPolicy.decide(
            preset = input.preset,
            ctx = ctx,
            alreadyTerminalToday = input.alreadyTerminalToday,
            nextRetryAtMs = input.nextRetryAtMs,
            nowMs = input.nowMs,
        )
        return AutomationRunPlan(decision = decision, readiness = readiness)
    }

    fun logEntry(
        preset: AutomationPreset,
        status: String,
        message: String,
        source: String,
        dateWib: String,
        nowMs: Long,
        reasonCode: String = "",
        errorClass: String = "",
        httpStatus: Int = 0,
        latLong: String = "",
        photoId: String = "",
        attempt: Int = 1,
        correlationId: String = "",
    ): AutomationLogEntry = AutomationLogEntry(
        id = "alog_${nowMs}_${preset.id.take(8)}",
        presetId = preset.id,
        presetNama = preset.nama,
        waktu = nowMs,
        status = status,
        pesan = message,
        source = source,
        dateWib = dateWib,
        plannedAt = nowMs,
        firedAt = nowMs,
        finishedAt = nowMs,
        latLong = latLong,
        workcodeId = preset.workcodeId,
        photoId = photoId,
        attempt = attempt,
        reasonCode = reasonCode,
        errorClass = errorClass,
        httpStatus = httpStatus,
        correlationId = correlationId,
    )
}
