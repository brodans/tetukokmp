package id.tetuko.proxy

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyConfigTest {
    private val keys = listOf(
        "PRESENSI_DEFAULT_GATEWAY_URL", "JATIM_GATEWAY_URL", "PRESENSI_DEFAULT_EP_BASE_URL", "JATIM_EP_BASE_URL",
        "MADIUN_EP_BASE_URL", "PRESENSI_PONOROGO_API_BASE", "PONOROGO_API_BASE", "PONOROGO_IMAGE_BASE",
        "PONOROGO_EP_BASE_URL", "PRESENSI_ALLOWED_HOSTS",
    )
    private val snapshot = mutableMapOf<String, String?>()

    @BeforeTest
    fun setUp() {
        keys.forEach { k ->
            snapshot[k] = System.getProperty(k)
            System.clearProperty(k)
        }
        System.setProperty("PRESENSI_DEFAULT_GATEWAY_URL", "https://gateway.example.test/service")
        System.setProperty("PRESENSI_DEFAULT_EP_BASE_URL", "https://ep.example.test")
        System.setProperty("PONOROGO_API_BASE", "https://ponorogo-api.example.test/absensApi/index.php")
        System.setProperty("PONOROGO_IMAGE_BASE", "https://ponorogo-img.example.test")
        System.setProperty("PONOROGO_EP_BASE_URL", "https://ponorogo-web.example.test")
        System.setProperty("PRESENSI_ALLOWED_HOSTS", "gateway.example.test,ep.example.test,ponorogo-api.example.test,ponorogo-img.example.test,ponorogo-web.example.test")
        ProxyConfig.reload()
    }

    @AfterTest
    fun tearDown() {
        keys.forEach { k ->
            val v = snapshot[k]
            if (v == null) System.clearProperty(k) else System.setProperty(k, v)
        }
        ProxyConfig.reload()
    }

    @Test
    fun `hostOf normalises case and rejects malformed urls`() {
        assertEquals("gateway.example.test", ProxyConfig.hostOf("https://GATEWAY.Example.Test/service"))
        assertNull(ProxyConfig.hostOf("not a url"))
        assertNull(ProxyConfig.hostOf("file:///etc/passwd"))
    }

    @Test
    fun `untrusted hosts are always rejected`() {
        assertFalse(ProxyConfig.allowedTarget("https://evil.example.com/service"))
        assertFalse(ProxyConfig.allowedTarget("http://10.0.0.1/service"))
        assertFalse(ProxyConfig.allowedTarget("https://evil.example.com/", allowEp = true))
    }

    @Test
    fun `gateway targets must be an allowed host on a service path`() {
        assertTrue(ProxyConfig.allowedTarget("https://gateway.example.test/service"))
        assertFalse(ProxyConfig.allowedTarget("https://gateway.example.test/admin"))
    }

    @Test
    fun `ep targets allow any path on an allowed host`() {
        assertTrue(ProxyConfig.allowedTarget("https://ep.example.test/index.php/login", allowEp = true))
        assertFalse(ProxyConfig.allowedTarget("https://ep.example.test", allowEp = false))
    }

    @Test
    fun `resolveGateway accepts an allow-listed header`() {
        val gateway = "https://gateway.example.test/service"
        assertEquals(gateway, ProxyConfig.resolveGateway(gateway, emptyMap()))
    }

    @Test
    fun `resolveGateway falls back for untrusted header and param`() {
        assertEquals(ProxyConfig.defaultGateway, ProxyConfig.resolveGateway("https://evil.example.com/service", emptyMap()))
        val params = buildJsonObject { put("gateway_url", JsonPrimitive("https://evil.example.com/service")) }
        assertEquals(ProxyConfig.defaultGateway, ProxyConfig.resolveGateway(null, params))
    }

    @Test
    fun `resolveGateway uses an allow-listed param when the header is absent`() {
        val gateway = "https://gateway.example.test/service"
        val params = buildJsonObject { put("gateway_url", JsonPrimitive(gateway)) }
        assertEquals(gateway, ProxyConfig.resolveGateway(null, params))
    }

    @Test
    fun `resolveEpBase trims trailing slash and falls back safely`() {
        val header = "https://ep.example.test/"
        assertEquals("https://ep.example.test", ProxyConfig.resolveEpBase(header, null))
        assertEquals(ProxyConfig.defaultEpBase, ProxyConfig.resolveEpBase("https://evil.example.com", null))
    }

    @Test
    fun `Ponorogo API override is allow-listed`() {
        val allowed = "https://ponorogo-api.example.test/absensApi/index.php"
        assertEquals(allowed, ProxyConfig.resolvePonorogoApiBase(allowed))
        assertEquals(ProxyConfig.ponorogoApiBase.trimEnd('/'), ProxyConfig.resolvePonorogoApiBase("https://evil.example.com/service"))
        assertEquals(ProxyConfig.ponorogoWebBase, ProxyConfig.resolvePonorogoWebBase("https://evil.example.com"))
    }

    @Test
    fun `configured hosts are allow-listed`() {
        assertTrue(ProxyConfig.allowedHosts.contains("gateway.example.test"))
        assertTrue(ProxyConfig.allowedHosts.contains("ponorogo-api.example.test"))
    }
}
