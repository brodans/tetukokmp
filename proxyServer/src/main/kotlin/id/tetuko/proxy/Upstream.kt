package id.tetuko.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class UpstreamResponse(
    val status: Int,
    val contentType: String?,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)

/**
 * Minimal upstream HTTP client (JDK HttpClient) shared by all proxy routes.
 * Redirects are never followed so callers can rewrite `Location` themselves.
 */
object Upstream {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    /**
     * Lenient client used only for the legacy Ponorogo log host, which serves a
     * certificate that does not validate on desktop JVMs (same bypass as the
     * original Node helper).
     */
    private val insecureClient: HttpClient by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val context = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .sslContext(context)
            .build()
    }

    suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
    ): UpstreamResponse = execute(client, method, url, headers, body)

    suspend fun sendInsecure(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
    ): UpstreamResponse = execute(insecureClient, method, url, headers, body)

    private suspend fun execute(
        http: HttpClient,
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): UpstreamResponse = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .method(
                method.uppercase(),
                if (body == null || body.isEmpty()) HttpRequest.BodyPublishers.noBody()
                else HttpRequest.BodyPublishers.ofByteArray(body),
            )
        headers.forEach { (name, value) ->
            runCatching { builder.header(name, value) }
        }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        UpstreamResponse(
            status = response.statusCode(),
            contentType = response.headers().firstValue("content-type").orElse(null),
            headers = response.headers().map(),
            body = response.body() ?: ByteArray(0),
        )
    }
}
