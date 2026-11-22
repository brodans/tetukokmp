package id.tetuko.app.service

import id.tetuko.core.domain.model.AutomationExecutionKey
import id.tetuko.core.domain.model.AutomationExecutionLedger
import id.tetuko.core.domain.model.AutomationLogCodec
import id.tetuko.core.domain.model.AutomationLogEntry

/**
 * File/store-backed execution ledger built on top of existing automation log persistence.
 * This keeps idempotency checks consistent across platforms that provide log readers/writers.
 */
class StoreBackedAutomationLedger(
    private val profileId: String,
    private val readRaw: (String) -> String?,
    private val writeRaw: (String, String) -> Boolean,
) : AutomationExecutionLedger {

    private fun key() = "$profileId:automation_logs"

    private fun readLogs(): MutableList<AutomationLogEntry> =
        AutomationLogCodec.decode(readRaw(key())).toMutableList()

    override fun hasTerminalExecution(key: AutomationExecutionKey): Boolean {
        return readLogs().any { e ->
            e.presetId == key.presetId &&
                (e.dateWib == key.dateWib || e.dateWib.isBlank()) &&
                e.status in TERMINAL_STATUSES
        }
    }

    override fun record(entry: AutomationLogEntry) {
        val logs = readLogs()
        logs.add(0, entry)
        writeRaw(key(), AutomationLogCodec.encode(logs.take(MAX_LOGS)))
    }

    override fun recent(profileId: String, limit: Int): List<AutomationLogEntry> {
        if (profileId != this.profileId) return emptyList()
        return readLogs().take(limit.coerceAtLeast(0))
    }

    companion object {
        private const val MAX_LOGS = 120
        private val TERMINAL_STATUSES = setOf("SUCCESS", "FAILED", "SKIPPED")
    }
}
