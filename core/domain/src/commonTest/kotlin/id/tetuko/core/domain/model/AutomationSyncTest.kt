package id.tetuko.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AutomationSyncTest {
    @Test
    fun `newer local item wins and remote tombstone removes local item`() {
        val result = AutomationSyncMerge.merge(
            localUpdatedAtMs = 200,
            remoteUpdatedAtMs = 100,
            localOrigin = "device-b",
            remoteOrigin = "device-a",
            localItems = listOf("local-preset"),
            remoteItems = listOf("remote-preset"),
            localTombstones = setOf("deleted-preset"),
            remoteTombstones = setOf("deleted-preset"),
            idOf = { it },
        )

        assertEquals(listOf("local-preset", "remote-preset"), result.items)
        assertEquals(setOf("deleted-preset"), result.tombstones)
    }

    @Test
    fun `local tombstone wins regardless of remote item presence`() {
        val result = AutomationSyncMerge.merge(
            localUpdatedAtMs = 400,
            remoteUpdatedAtMs = 500,
            localOrigin = "device-a",
            remoteOrigin = "device-b",
            localItems = listOf("preset-a", "preset-b"),
            remoteItems = listOf("preset-a", "preset-b", "preset-c"),
            localTombstones = setOf("preset-b"),
            remoteTombstones = emptySet(),
            idOf = { it },
        )

        assertEquals(listOf("preset-a", "preset-c"), result.items)
        assertEquals(setOf("preset-b"), result.tombstones)
    }

    @Test
    fun `tombstone suppresses an item present on one side`() {
        val result = AutomationSyncMerge.merge(
            localUpdatedAtMs = 300,
            remoteUpdatedAtMs = 200,
            localOrigin = "device-a",
            remoteOrigin = "device-b",
            localItems = listOf("deleted-preset", "kept-preset"),
            remoteItems = emptyList<String>(),
            localTombstones = emptySet(),
            remoteTombstones = setOf("deleted-preset"),
            idOf = { it },
        )

        assertEquals(listOf("kept-preset"), result.items)
        assertEquals(setOf("deleted-preset"), result.tombstones)
    }
}
