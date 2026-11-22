package id.tetuko.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

class ServerProfileTest {
    @Test
    fun `profile overrides use KMP environment names and preserve stable IDs`() {
        val profiles = DefaultProfiles.profilesWithOverrides(
            mapOf(
                "JATIM_GATEWAY_URL" to "https://jatim.example/service",
                "MADIUN_GATEWAY_URL" to "https://madiun.example/service",
                "MADIUN_LABEL" to "Madiun Uji",
                "MADIUN_REQUIRES_WORKCODE" to "false",
                "PONOROGO_API_BASE" to "https://api.ponorogo.example/Ponorogo-absensApi/index.php",
            ),
        )
        val jatim = profiles.first { it.id == "jatim" }
        val madiun = profiles.first { it.id == "madiun" }
        val ponorogo = profiles.first { it.id == "ponorogo" }

        assertEquals("https://jatim.example/service", jatim.gatewayUrl)
        assertEquals("https://madiun.example/service", madiun.gatewayUrl)
        assertEquals("Madiun Uji", madiun.label)
        assertFalse(madiun.requiresWorkCode)
        assertEquals("https://api.ponorogo.example/Ponorogo-absensApi/index.php", ponorogo.ponorogoApiBase)
        assertTrue(profiles.any { it.id == "jatim" })
        assertTrue(profiles.any { it.id == "ponorogo" })
    }

    @Test
    fun `invalid profile mode falls back to the built in mode`() {
        val profile = DefaultProfiles.profilesWithOverrides(
            mapOf("JATIM_MODE" to "unknown"),
        ).first { it.id == "jatim" }

        assertEquals(AttendanceMode.BKD_RPC, profile.mode)
    }

    @Test
    fun `legacy single server credential remains readable`() {
        val account = Json.decodeFromString<LocalAppAccount>(
            """{"id":"local","username":"admin","passwordHash":"hash","serverCreds":{"jatim":{"username":"old-user","password":"secret","imei":"device"}}}""",
        )

        assertEquals("old-user", account.activeServerCredential("jatim")?.username)
        assertEquals("old-user", account.serverCredentials("jatim").single().username)
        assertEquals("old-user", account.toSafe().activeServerCredential("jatim")?.username)
    }

    @Test
    fun `multi-account profile resolves configured active username`() {
        val account = LocalAppAccountSafe(
            id = "local",
            username = "admin",
            serverCredAccounts = mapOf(
                "madiun" to listOf(
                    LocalServerCredential("user-a", "pass-a"),
                    LocalServerCredential("user-b", "pass-b"),
                ),
            ),
            activeServerCreds = mapOf("madiun" to "USER-B"),
        )

        assertEquals("user-b", account.activeServerCredential("madiun")?.username)
        assertEquals(2, account.serverCredentials("madiun").size)
    }
}