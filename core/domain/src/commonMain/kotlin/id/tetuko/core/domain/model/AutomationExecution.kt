package id.tetuko.core.domain.model

data class AutomationReadiness(
    val ok: Boolean,
    val issues: List<String>,
) {
    val label: String get() = if (ok) "Siap" else "Butuh ${issues.joinToString(", ")}"
}

data class AutomationExecutionContext(
    val profileId: String,
    val hasActiveSession: Boolean,
    val activeProfileId: String?,
    val requiresWorkCode: Boolean,
    val requiresPhoto: Boolean,
    val availableStockPhotoIds: Set<String>,
    val nowMinute: Int,
    val dateWib: String,
)

data class AutomationExecutionDecision(
    val shouldRun: Boolean,
    val status: String,
    val reasonCode: String,
    val message: String,
)

object AutomationExecutionPolicy {
    fun preflight(
        preset: AutomationPreset,
        ctx: AutomationExecutionContext,
    ): AutomationReadiness {
        val issues = AutomationPlanning.readinessIssues(
            hasActiveSession = ctx.hasActiveSession,
            activeProfileId = ctx.activeProfileId,
            profileId = ctx.profileId,
            requiresWorkCode = ctx.requiresWorkCode,
            requiresPhoto = ctx.requiresPhoto,
            preset = preset,
            stockPhotoIds = ctx.availableStockPhotoIds,
        )
        return AutomationReadiness(issues.isEmpty(), issues)
    }

    fun decide(
        preset: AutomationPreset,
        ctx: AutomationExecutionContext,
        alreadyTerminalToday: Boolean,
        nextRetryAtMs: Long,
        nowMs: Long,
    ): AutomationExecutionDecision {
        val start = AutomationPlanning.parseMinutes(preset.jamMulai)
            ?: return AutomationExecutionDecision(false, "SKIPPED", "INVALID_TIME", "Jam mulai preset tidak valid.")
        val end = AutomationPlanning.parseMinutes(preset.jamSelesai)
            ?: return AutomationExecutionDecision(false, "SKIPPED", "INVALID_TIME", "Jam selesai preset tidak valid.")
        if (!AutomationPlanning.inWindow(ctx.nowMinute, start, end)) {
            val past = AutomationPlanning.isPastWindow(ctx.nowMinute, start, end)
            return if (past) {
                AutomationExecutionDecision(false, "SKIPPED", "WINDOW_MISSED", "Jendela preset terlewati: ${preset.jamMulai}-${preset.jamSelesai} WIB.")
            } else {
                AutomationExecutionDecision(false, "SKIPPED", "OUTSIDE_WINDOW", "Belum masuk jendela preset.")
            }
        }
        if (alreadyTerminalToday) {
            return AutomationExecutionDecision(false, "SKIPPED", "ALREADY_TERMINAL", "Preset sudah memiliki status terminal hari ini.")
        }
        if (nextRetryAtMs > nowMs) {
            return AutomationExecutionDecision(false, "RETRYABLE", "BACKOFF", "Menunggu backoff sebelum mencoba lagi.")
        }
        val readiness = preflight(preset, ctx)
        if (!readiness.ok) {
            return AutomationExecutionDecision(false, "SKIPPED", "PREFLIGHT_FAILED", "Readiness: ${readiness.label}.")
        }
        return AutomationExecutionDecision(true, "PENDING", "READY", "Preset siap dieksekusi.")
    }
}
