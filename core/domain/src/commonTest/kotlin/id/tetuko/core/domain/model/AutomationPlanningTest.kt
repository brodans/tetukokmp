package id.tetuko.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomationPlanningTest {
    @Test
    fun parseMinutes_validAndInvalid() {
        assertEquals(0, AutomationPlanning.parseMinutes("00:00"))
        assertEquals(7 * 60 + 30, AutomationPlanning.parseMinutes("07:30"))
        assertEquals(null, AutomationPlanning.parseMinutes("7:30x"))
        assertEquals(null, AutomationPlanning.parseMinutes("24:00"))
    }

    @Test
    fun inWindow_handlesOvernight() {
        assertTrue(AutomationPlanning.inWindow(23 * 60, 22 * 60, 1 * 60))
        assertTrue(AutomationPlanning.inWindow(0, 22 * 60, 1 * 60))
        assertFalse(AutomationPlanning.inWindow(12 * 60, 22 * 60, 1 * 60))
    }

    @Test
    fun isPastWindow_handlesNormalAndOvernight() {
        assertTrue(AutomationPlanning.isPastWindow(10 * 60, 8 * 60, 9 * 60))
        assertFalse(AutomationPlanning.isPastWindow(8 * 60 + 30, 8 * 60, 9 * 60))
        assertTrue(AutomationPlanning.isPastWindow(2 * 60, 22 * 60, 1 * 60))
        assertFalse(AutomationPlanning.isPastWindow(23 * 60, 22 * 60, 1 * 60))
    }

    @Test
    fun policy_preflight_reportsMissingRequirements() {
        val preset = AutomationPreset(id = "p1", nama = "P", workcodeId = "", stockFotoIds = emptyList())
        val readiness = AutomationExecutionPolicy.preflight(
            preset,
            AutomationExecutionContext(
                profileId = "jatim",
                hasActiveSession = false,
                activeProfileId = null,
                requiresWorkCode = true,
                requiresPhoto = true,
                availableStockPhotoIds = emptySet(),
                nowMinute = 8 * 60,
                dateWib = "2026-11-22",
            ),
        )
        assertFalse(readiness.ok)
        assertTrue(readiness.issues.contains("sesi"))
        assertTrue(readiness.issues.contains("workcode"))
        assertTrue(readiness.issues.contains("stok foto"))
    }

    @Test
    fun coordinator_decide_skipsOutsideWindow() {
        val preset = AutomationPreset(id = "p1", nama = "P", jamMulai = "08:00", jamSelesai = "09:00", aktif = true)
        val plan = AutomationRunCoordinator.plan(
            AutomationRunInput(
                preset = preset,
                profileId = "jatim",
                hasActiveSession = true,
                activeProfileId = "jatim",
                requiresWorkCode = false,
                requiresPhoto = false,
                availableStockPhotoIds = emptySet(),
                nowMinute = 7 * 60,
                dateWib = "2026-11-22",
                nowMs = 1L,
                alreadyTerminalToday = false,
            )
        )
        assertFalse(plan.decision.shouldRun)
        assertEquals("OUTSIDE_WINDOW", plan.decision.reasonCode)
    }
}
