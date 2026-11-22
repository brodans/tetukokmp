package id.tetuko.proxy

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Runtime configuration and host allow-listing for the local proxy.
 *
 * The proxy mirrors the security model of the original Tauri/Vercel helper:
 * upstream targets are restricted to a fixed allow-list so the locally bound
 * server can never be abused as an open SSRF relay.
 */
object ProxyConfig {
    const val PRESENSI_VERSION = 89

    var defaultGateway: String = ""; private set
    var defaultEpBase: String = ""; private set
    var jatimEpBase: String = ""; private set
    var madiunEpBase: String = ""; private set
    var ponorogoApiBase: String = ""; private set
    var ponorogoImgBase: String = ""; private set
    var ponorogoWebBase: String = ""; private set
    var allowedHosts: Set<String> = emptySet(); private set

    init { reload() }

    fun reload() {
        defaultGateway = env("PRESENSI_DEFAULT_GATEWAY_URL", "JATIM_GATEWAY_URL", fallback = "")
        defaultEpBase = env("PRESENSI_DEFAULT_EP_BASE_URL", "JATIM_EP_BASE_URL", fallback = "").trimEnd('/')
        jatimEpBase = env("JATIM_EP_BASE_URL", "PRESENSI_DEFAULT_EP_BASE_URL", fallback = defaultEpBase).trimEnd('/')
        madiunEpBase = env("MADIUN_EP_BASE_URL", fallback = "").trimEnd('/')
        ponorogoApiBase = env("PRESENSI_PONOROGO_API_BASE", "PONOROGO_API_BASE", fallback = "")
        ponorogoImgBase = env("PONOROGO_IMAGE_BASE", "PRESENSI_PONOROGO_IMAGE_BASE", fallback = "").trimEnd('/')
        ponorogoWebBase = env("PONOROGO_EP_BASE_URL", "PONOROGO_WEB_BASE", fallback = "").trimEnd('/')
        val explicit = env("PRESENSI_ALLOWED_HOSTS", fallback = "")
            .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val derived = listOf(defaultGateway, defaultEpBase, jatimEpBase, madiunEpBase, ponorogoApiBase, ponorogoImgBase, ponorogoWebBase)
            .mapNotNull { hostOf(it) }
        allowedHosts = (explicit + derived).toSet()
    }

    fun hostOf(url: String): String? = runCatching {
        java.net.URI(url).host?.lowercase()
    }.getOrNull()

    fun allowedTarget(url: String, allowEp: Boolean = false): Boolean {
        val host = hostOf(url) ?: return false
        if (host !in allowedHosts) return false
        if (allowEp) return true
        return url.contains("/service") || url.contains("absensApi")
    }

    fun resolveGateway(header: String?, param: Map<String, JsonElement>): String {
        val headerValue = header?.trim()
        if (!headerValue.isNullOrBlank() && allowedTarget(headerValue, false)) return headerValue
        val fromParam = (param["gateway_url"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (!fromParam.isNullOrBlank() && allowedTarget(fromParam, false)) return fromParam
        return defaultGateway
    }

    fun resolveEpBase(header: String?, query: String?): String {
        val headerValue = header?.trim()
        if (!headerValue.isNullOrBlank() && allowedTarget(headerValue, true)) return headerValue.trimEnd('/')
        val queryValue = query?.trim()
        if (!queryValue.isNullOrBlank() && allowedTarget(queryValue, true)) return queryValue.trimEnd('/')
        return defaultEpBase
    }

    fun resolvePonorogoApiBase(candidate: String?): String =
        candidate?.trim()?.takeIf { allowedTarget(it, false) }?.trimEnd('/') ?: ponorogoApiBase.trimEnd('/')

    fun resolvePonorogoWebBase(candidate: String?): String =
        candidate?.trim()?.takeIf { allowedTarget(it, true) }?.trimEnd('/') ?: ponorogoWebBase

    fun resolvePonorogoImageBase(candidate: String?): String =
        candidate?.trim()?.takeIf { allowedTarget(it, true) }?.trimEnd('/') ?: ponorogoImgBase

    private fun env(vararg names: String, fallback: String): String =
        names.firstNotNullOfOrNull { name ->
            System.getenv(name)?.trim()?.takeIf { it.isNotBlank() }
                ?: System.getProperty(name)?.trim()?.takeIf { it.isNotBlank() }
                ?: System.getProperty("tetuko.env.$name")?.trim()?.takeIf { it.isNotBlank() }
        } ?: fallback
}
