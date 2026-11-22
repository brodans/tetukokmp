package id.tetuko.core.data.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class RpcClientRoutingTest {
    @Test
    fun `selected profile gateway overrides caller supplied gateway`() {
        val client = RpcClient().apply {
            gatewayUrlProvider = { profileId ->
                if (profileId == "madiun") "https://gateway.example.test/service" else null
            }
        }

        val routed = client.routeParam(
            "madiun",
            JsonObject(
                mapOf(
                    "gateway_url" to JsonPrimitive("https://untrusted.example.invalid/service"),
                    "page" to JsonPrimitive(1),
                ),
            ),
        )

        assertEquals("https://gateway.example.test/service", routed["gateway_url"]?.jsonPrimitive?.contentOrNull)
        assertEquals(JsonPrimitive(1), routed["page"])
    }

    @Test
    fun `unknown profile does not forward a caller supplied gateway`() {
        val client = RpcClient()
        val routed = client.routeParam(
            "unknown",
            JsonObject(mapOf("gateway_url" to JsonPrimitive("https://untrusted.example.invalid/service"))),
        )

        assertNull(routed["gateway_url"])
    }
}