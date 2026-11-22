package id.tetuko.core.data.rpc

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

class RpcException(
    message: String,
    val code: Int? = null,
    val huluStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

class RpcClient(
    private val baseUrlProvider: () -> String = { "http://127.0.0.1:14200" },
) {
    /** Resolves the configured RPC gateway for a selected profile. */
    var gatewayUrlProvider: ((String) -> String?)? = null

    /** Invoked when the proxy reports an invalid/expired token (gate -32604). */
    var onInvalidToken: (() -> Unit)? = null
    var onTraffic: ((method: String, path: String, status: Int, request: String, response: String) -> Unit)? = null

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    val http = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(Logging) { level = LogLevel.NONE }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    private fun apiUrl(path: String): String {
        val base = baseUrlProvider().trimEnd('/')
        val p = if (path.startsWith('/')) path else "/$path"
        return "$base$p"
    }

    private fun injectContext(param: JsonObject, ctx: ServerContext): JsonObject = buildJsonObject {
        // keep original params first, then force injected keys
        param.forEach { (k, v) -> if (k !in INJECTED_PARAM_KEYS) put(k, v) }
        put("api_key", ctx.apiKey)
        put("last_latlong", ctx.lastLatLong)
        put("imei", ctx.imei)
    }

    internal fun routeParam(profileId: String?, param: JsonObject): JsonObject {
        val gateway = profileId?.let { id -> runCatching { gatewayUrlProvider?.invoke(id) }.getOrNull() }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return buildJsonObject {
            param.forEach { (key, value) -> if (key != "gateway_url") put(key, value) }
            if (gateway != null) put("gateway_url", gateway)
        }
    }

    suspend fun callRaw(
        `object`: String,
        param: JsonObject = JsonObject(emptyMap()),
        ctx: ServerContext = ServerContext(),
        profileId: String? = null,
    ): RpcRawResponse {
        val body = RpcRequestBody(
            `object` = `object`,
            param = routeParam(profileId, injectContext(param, ctx)),
        )
        val maxAttempts = if (`object` in RETRYABLE_OBJECTS) 3 else 1
        var lastErr: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                val response = http.post(apiUrl("/api/rpc")) {
                    contentType(ContentType.Application.Json)
                    if (!profileId.isNullOrBlank()) header("X-Profile-Id", profileId)
                    setBody(body)
                }
                val rawText = response.bodyAsText()
                onTraffic?.invoke("POST", "/api/rpc", response.status.value, body.toString(), rawText)
                val raw = parseRawResponse(rawText)
                if (!response.status.isSuccess()) {
                    val status = response.status.value
                    val msg = responseErrorMessage(raw, rawText, status)
                    val ex = RpcException(msg, raw.errorCode, raw.huluStatus ?: status)
                    if (isRetryableRpcError(`object`, attempt, maxAttempts, ex)) {
                        lastErr = ex
                        backoff(attempt)
                        return@repeat
                    }
                    throw ex
                }
                if (!raw.error.isNullOrBlank()) {
                    throw RpcException(raw.error ?: "RPC error", raw.errorCode, raw.huluStatus)
                }
                return raw
            } catch (e: RpcException) {
                if (e.code == GateCodes.INVALID_TOKEN) onInvalidToken?.invoke()
                if (isRetryableRpcError(`object`, attempt, maxAttempts, e)) {
                    lastErr = e
                    backoff(attempt)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                if (isRetryableTransportError(`object`, attempt, maxAttempts)) {
                    lastErr = e
                    backoff(attempt)
                } else {
                    throw RpcException(transportErrorMessage(`object`, e), cause = e)
                }
            }
        }
        throw lastErr as? RpcException
            ?: lastErr?.let { RpcException(transportErrorMessage(`object`, it), cause = it) }
            ?: RpcException("Gagal memanggil RPC ${`object`}")
    }

    private fun parseRawResponse(rawText: String): RpcRawResponse {
        if (rawText.isBlank()) return RpcRawResponse()
        return runCatching { json.decodeFromString(RpcRawResponse.serializer(), rawText) }
            .getOrElse {
                // Fall back to extracting common fields from a JSON object if the envelope shape differs.
                val element = runCatching { json.parseToJsonElement(rawText) }.getOrNull()
                val obj = element as? JsonObject
                if (obj != null) {
                    RpcRawResponse(
                        result = obj["result"],
                        total = obj["total"]?.asIntOrNull(),
                        page = obj["page"]?.asIntOrNull(),
                        view = obj["view"]?.asIntOrNull(),
                        error = obj.stringField("error"),
                        errorCode = obj["errorCode"]?.asIntOrNull() ?: obj["code"]?.asIntOrNull(),
                        message = obj.stringField("message"),
                        huluStatus = obj["huluStatus"]?.asIntOrNull() ?: obj["status"]?.asIntOrNull(),
                        idReq = obj.stringField("id_req"),
                        jsonrpc = obj["jsonrpc"]?.asIntOrNull(),
                    )
                } else {
                    RpcRawResponse(message = rawText.take(200))
                }
            }
    }

    private fun responseErrorMessage(raw: RpcRawResponse, rawText: String, status: Int): String {
        return raw.error
            ?: raw.message
            ?: rawText.trim().takeIf { it.isNotBlank() }?.take(240)
            ?: "HTTP $status"
    }

    private fun isRetryableRpcError(
        `object`: String,
        attempt: Int,
        maxAttempts: Int,
        error: RpcException,
    ): Boolean {
        if (`object` !in RETRYABLE_OBJECTS || attempt >= maxAttempts - 1) return false
        if (error.code == GateCodes.INVALID_TOKEN) return false
        val status = error.huluStatus ?: error.code
        return status == 503 || (status != null && status >= 500)
    }

    private fun isRetryableTransportError(
        `object`: String,
        attempt: Int,
        maxAttempts: Int,
    ): Boolean = `object` in RETRYABLE_OBJECTS && attempt < maxAttempts - 1

    private fun transportErrorMessage(`object`: String, e: Exception): String {
        val detail = e.message?.trim().orEmpty()
        return if (detail.isNotBlank()) {
            "Gagal memanggil RPC ${`object`}: $detail"
        } else {
            "Gagal memanggil RPC ${`object`}: ${e::class.simpleName ?: "network error"}"
        }
    }

    private fun JsonElement.asIntOrNull(): Int? = (this as? JsonPrimitive)?.let { p ->
        p.contentOrNull?.toIntOrNull() ?: runCatching { p.content.toInt() }.getOrNull()
    }

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private suspend fun backoff(attempt: Int) {
        // Exponential-ish backoff with jitter (500ms, 1.5s); keeps retries gentle.
        val base = 500L * (attempt + 1)
        delay(base + Random.nextLong(0, 250))
    }

    suspend fun callResultElement(
        `object`: String,
        param: JsonObject = JsonObject(emptyMap()),
        ctx: ServerContext = ServerContext(),
        profileId: String? = null,
    ): JsonElement? = callRaw(`object`, param, ctx, profileId).result

    /** Best-effort extraction of a user-facing message from a raw response/result element. */
    fun resultMessage(element: JsonElement?): String? = when (element) {
        null, JsonNull -> null
        is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonObject -> element.stringField("message")
            ?: element.stringField("error")
            ?: element.stringField("msg")
        else -> null
    }

    /**
     * Uploads an attachment through `POST /api/upload?path=/importfile`
     * (multipart). Field names mirror the v89 Presensi app: `api_key`, `id`,
     * `last_latlong`, `type=ijin`, and the `image` part.
     */
    suspend fun uploadImportFile(
        ctx: ServerContext,
        izinId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        profileId: String? = null,
    ): JsonElement? {
        val text = try {
            val response = http.post(apiUrl("/api/upload?path=%2Fimportfile")) {
                timeout { requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 }
                if (!profileId.isNullOrBlank()) header("X-Profile-Id", profileId)
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("api_key", ctx.apiKey)
                            append("id", izinId)
                            append("last_latlong", "")
                            append("type", "ijin")
                            append(
                                "image",
                                bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, mimeType)
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                },
                            )
                        },
                    ),
                )
            }
            val bodyText = response.bodyAsText()
            if (!response.status.isSuccess()) {
                val parsed = parseJsonElementOrNull(bodyText)
                val message = parsed?.let { resultMessage(it) }
                    ?: bodyText.trim().takeIf { it.isNotBlank() }?.take(240)
                    ?: "Unggah lampiran gagal (HTTP ${response.status.value})."
                throw RpcException(message, null, response.status.value)
            }
            bodyText
        } catch (e: RpcException) {
            throw e
        } catch (e: Exception) {
            throw RpcException(transportErrorMessage("uploadImportFile", e), cause = e)
        }
        return parseJsonElementOrNull(text) ?: text.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) }
    }

    fun parseJsonElementOrNull(text: String): JsonElement? {
        if (text.isBlank()) return null
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    inline fun <reified T> decodeOrNull(element: JsonElement?): T? {
        if (element == null || element is JsonNull) return null
        return runCatching { json.decodeFromJsonElement<T>(element) }.getOrNull()
    }

    fun toRows(element: JsonElement?): JsonArray {
        if (element == null || element is JsonNull) return JsonArray(emptyList())
        if (element is JsonArray) return element
        if (element is JsonObject) {
            val inner = element["result"]
            if (inner is JsonArray) return inner
            val rows = element["rows"] ?: element["data"]
            if (rows is JsonArray) return rows
        }
        return JsonArray(emptyList())
    }

    /** Returns the first array found in a raw result, or an empty array. */
    fun resultArray(element: JsonElement?): JsonArray = toRows(element)
}

fun Map<String, Any?>.toJsonObject(json: Json = Json): JsonObject = buildJsonObject {
    for ((k, v) in this@toJsonObject) {
        when (v) {
            null -> put(k, JsonNull)
            is String -> put(k, v)
            is Number -> put(k, JsonPrimitive(v))
            is Boolean -> put(k, v)
            is JsonElement -> put(k, v)
            else -> put(k, JsonPrimitive(v.toString()))
        }
    }
}
