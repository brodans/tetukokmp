package id.tetuko.core.domain.model

object AutomationPlanning {
    fun parseMinutes(hhmm: String): Int? {
        val p = hhmm.trim().split(":")
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun inWindow(nowMinute: Int, start: Int, end: Int): Boolean =
        if (start <= end) nowMinute in start..end else (nowMinute >= start || nowMinute <= end)

    fun isPastWindow(nowMinute: Int, start: Int, end: Int): Boolean =
        if (start <= end) nowMinute > end else nowMinute in (end + 1) until start

    fun executionKey(profileId: String, preset: AutomationPreset, dateWib: String): AutomationExecutionKey =
        AutomationExecutionKey(profileId = profileId, presetId = preset.id, dateWib = dateWib, tipe = preset.tipe)

    fun readinessIssues(
        hasActiveSession: Boolean,
        activeProfileId: String?,
        profileId: String,
        requiresWorkCode: Boolean,
        requiresPhoto: Boolean,
        preset: AutomationPreset,
        stockPhotoIds: Set<String>,
    ): List<String> = buildList {
        if (!hasActiveSession) add("sesi")
        if (activeProfileId != profileId) add("profil aktif")
        if (requiresWorkCode && preset.workcodeId.isBlank()) add("workcode")
        if (requiresPhoto) {
            val candidates = if (preset.stockFotoIds.isNotEmpty()) preset.stockFotoIds.filter { it in stockPhotoIds } else stockPhotoIds.toList()
            if (candidates.isEmpty()) add("stok foto")
        }
    }
}
