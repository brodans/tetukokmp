package id.tetuko.core.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual

@Serializable
data class AutomationSyncEnvelope<T>(
    val schemaVersion: Int = 1,
    val deviceId: String,
    val accountId: String,
    val profileId: String,
    val updatedAtMs: Long,
    val items: List<@Contextual T>,
    val tombstones: List<String> = emptyList(),
)

object AutomationSyncMerge {
    fun <T> merge(
        localUpdatedAtMs: Long,
        remoteUpdatedAtMs: Long,
        localOrigin: String,
        remoteOrigin: String,
        localItems: List<T>,
        remoteItems: List<T>,
        localTombstones: Set<String>,
        remoteTombstones: Set<String>,
        idOf: (T) -> String,
    ): AutomationSyncResult<T> {
        val tombstones = localTombstones + remoteTombstones
        val localById = localItems.associateBy(idOf)
        val remoteById = remoteItems.associateBy(idOf)
        val merged = linkedMapOf<String, T>()

        val allIds = (localById.keys + remoteById.keys).distinct()
        for (id in allIds) {
            if (id in tombstones) continue
            val local = localById[id]
            val remote = remoteById[id]
            val winner = when {
                local != null && remote == null -> local
                remote != null && local == null -> remote
                local != null && remote != null -> {
                    val winnerOrigin = if (localUpdatedAtMs != remoteUpdatedAtMs) {
                        if (localUpdatedAtMs > remoteUpdatedAtMs) localOrigin else remoteOrigin
                    } else {
                        if (localOrigin >= remoteOrigin) localOrigin else remoteOrigin
                    }
                    if (winnerOrigin == localOrigin) local else remote
                }
                else -> continue
            }
            merged[id] = winner
        }

        val newTombstones = tombstones.toSet()
        val conflict = allIds.count { id ->
            id !in tombstones && localById[id] != null && remoteById[id] != null
        }
        return AutomationSyncResult(merged.values.toList(), newTombstones, conflict)
    }
}

data class AutomationSyncResult<T>(
    val items: List<T>,
    val tombstones: Set<String>,
    val conflictCount: Int,
)
