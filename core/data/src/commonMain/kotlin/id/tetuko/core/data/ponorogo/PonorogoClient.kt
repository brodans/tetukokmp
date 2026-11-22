package id.tetuko.core.data.ponorogo

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Ponorogo absensi client.
 *
 * The portal exposes endpoint/payload pairs through the local proxy
 * (`POST /api/proxy`), multipart uploads through `/api/proxy-multipart`, the
 * institutional attendance log through `/api/absensi-log-proxy`, and reports
 * through the `/ep` reverse proxy. This class mirrors `src/api/index.ts`.
 */
class PonorogoClient(private val baseUrlProvider: () -> String) {
    var onTraffic: ((method: String, path: String, status: Int, request: String, response: String) -> Unit)? = null
    var apiBaseUrlProvider: (() -> String?)? = null
    var imageBaseUrlProvider: (() -> String?)? = null
    var webBaseUrlProvider: (() -> String?)? = null
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val http = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 45_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 45_000
        }
    }

    private fun apiUrl(path: String): String {
        val base = baseUrlProvider().trimEnd('/')
        val suffix = if (path.startsWith('/')) path else "/$path"
        return "$base$suffix"
    }

    fun imageProxyUrl(path: String): String {
        val rawPath = path.trim()
        val query = buildList {
            add("path=${rawPath.encodeURLParameter()}")
            imageBaseUrlProvider?.invoke()?.takeIf { it.isNotBlank() }?.let { add("image_base=${it.encodeURLParameter()}") }
            webBaseUrlProvider?.invoke()?.takeIf { it.isNotBlank() }?.let { add("web_base=${it.encodeURLParameter()}") }
        }.joinToString("&")
        return "${apiUrl("/api/proxy-image")}?$query"
    }

    fun imageDownloadUrl(source: String): String {
        val raw = source.trim()
        return if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            raw
        } else {
            imageProxyUrl(raw)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun fetchImage(source: String): ByteArray {
        val raw = source.trim()
        if (raw.startsWith("data:image/", ignoreCase = true)) {
            val payload = raw.substringAfter("base64,", "")
            if (payload.isBlank()) throw PonorogoException("Data gambar tidak berisi payload base64.")
            return runCatching { Base64.decode(payload) }
                .getOrElse { throw PonorogoException("Data gambar base64 tidak valid.", it) }
        }
        if (raw.startsWith("/9j/") || raw.startsWith("iVBOR")) {
            return runCatching { Base64.decode(raw) }
                .getOrElse { throw PonorogoException("Data gambar base64 tidak valid.", it) }
        }
        val response = http.get(imageDownloadUrl(raw))
        if (!response.status.isSuccess()) {
            val text = runCatching { response.bodyAsText() }.getOrNull()
            throw PonorogoException(text?.takeIf { it.isNotBlank() } ?: "Gagal mengunduh lampiran (HTTP ${response.status.value}).")
        }
        val bytes = response.body<ByteArray>()
        if (bytes.isEmpty()) throw PonorogoException("Lampiran kosong.")
        onTraffic?.invoke("GET", "/api/proxy-image", response.status.value, raw, "${bytes.size} bytes")
        return bytes
    }

    suspend fun send(
        endpoint: String,
        payload: JsonObject = JsonObject(emptyMap()),
        apiBase: String? = null,
    ): JsonElement? {
        val body = buildJsonObject {
            put("endpoint", endpoint)
            put("payload", payload)
            (apiBase?.takeIf { it.isNotBlank() } ?: apiBaseUrlProvider?.invoke())
                ?.takeIf { it.isNotBlank() }?.let { put("api_base", it) }
        }
        return postJson(
            path = "/api/proxy",
            body = body.toString(),
            defaultError = "Permintaan Ponorogo gagal",
            endpointLabel = endpoint,
        )
    }

    suspend fun sendMultipart(
        endpoint: String,
        fields: Map<String, String>,
        fileName: String? = null,
        base64: String? = null,
        mimeType: String = "image/jpeg",
        apiBase: String? = null,
    ): JsonElement? {
        val body = buildJsonObject {
            put("endpoint", endpoint)
            put("fields", JsonObject(fields.mapValues { JsonPrimitive(it.value) }))
            (apiBase?.takeIf { it.isNotBlank() } ?: apiBaseUrlProvider?.invoke())
                ?.takeIf { it.isNotBlank() }?.let { put("api_base", it) }
            if (fileName != null && base64 != null) {
                put(
                    "file",
                    buildJsonObject {
                        put("filename", fileName)
                        put("base64", base64)
                        put("mimeType", mimeType)
                    },
                )
            }
        }
        return postJson(
            path = "/api/proxy-multipart",
            body = body.toString(),
            defaultError = "Unggah Ponorogo gagal",
            endpointLabel = endpoint,
        )
    }

    /** Institutional log search; blank filters are sent as `null` like the original. */
    suspend fun attendanceLog(
        unor: String,
        dateStart: String,
        dateEnd: String,
        nama: String,
        nip: String,
        page: Int = 1,
        size: Int = 25,
    ): JsonElement? = try {
        val response = http.get(apiUrl("/api/absensi-log-proxy")) {
            webBaseUrlProvider?.invoke()?.takeIf { it.isNotBlank() }?.let { parameter("web_base", it) }
            parameter("unor", unor.ifBlank { "null" })
            parameter("dateStart", dateStart)
            parameter("dateEnd", dateEnd)
            parameter("nama", nama.ifBlank { "null" })
            parameter("nip", nip.ifBlank { "null" })
            parameter("page", page)
            parameter("size", size)
        }
        val text = response.bodyAsText()
        val parsed = parseJsonOrNull(text)
        if (!response.status.isSuccess()) {
            throw PonorogoException(
                extractErrorMessage(parsed, text)
                    ?: "Gagal memuat log presensi instansi (HTTP ${response.status.value}).",
            )
        }
        parsed
    } catch (e: PonorogoException) {
        throw e
    } catch (e: Exception) {
        throw PonorogoException(transportMessage("memuat log presensi instansi", e), e)
    }

    /** Downloads a Ponorogo report (PDF/XLS) through `/ep`. */
    suspend fun report(pathQuery: String, epBase: String? = null): ByteArray = try {
        val params = parseQueryParams(pathQuery)
        val target = buildReportTarget(params)
        val response = http.get(apiUrl("/ep$target")) {
            val selectedEpBase = epBase?.takeIf { it.isNotBlank() } ?: webBaseUrlProvider?.invoke()
            if (!selectedEpBase.isNullOrBlank()) header("x-presensi-ep-base", selectedEpBase.trimEnd('/'))
        }
        if (!response.status.isSuccess()) {
            val message = runCatching { extractErrorMessage(parseJsonOrNull(response.bodyAsText()), null) }.getOrNull()
            throw PonorogoException(message ?: "Gagal memuat laporan (HTTP ${response.status.value}).")
        }
        val bytes = response.body<ByteArray>()
        if (params["format"] != "xls") {
            val signature = bytes.take(5).toByteArray().decodeToString()
            if (signature != "%PDF-") throw PonorogoException("Server tidak mengembalikan berkas PDF yang valid.")
        } else if (bytes.isEmpty()) {
            throw PonorogoException("Server mengembalikan berkas XLS kosong.")
        }
        bytes
    } catch (e: PonorogoException) {
        throw e
    } catch (e: Exception) {
        throw PonorogoException(transportMessage("memuat laporan Ponorogo", e), e)
    }

    private suspend fun postJson(
        path: String,
        body: String,
        defaultError: String,
        endpointLabel: String? = null,
    ): JsonElement? = try {
        val response = http.post(apiUrl(path)) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val text = response.bodyAsText()
        val parsed = parseJsonOrNull(text)
        if (!response.status.isSuccess()) {
            val suffix = endpointLabel?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
            throw PonorogoException(
                extractErrorMessage(parsed, text)
                    ?: "$defaultError$suffix (HTTP ${response.status.value}).",
            )
        }
        parsed
    } catch (e: PonorogoException) {
        throw e
    } catch (e: Exception) {
        val suffix = endpointLabel?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
        throw PonorogoException(transportMessage("$defaultError$suffix", e), e)
    }

    private fun parseJsonOrNull(text: String): JsonElement? {
        if (text.isBlank()) return null
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    private fun extractErrorMessage(parsed: JsonElement?, rawText: String?): String? {
        val obj = parsed as? JsonObject
        val fromObj = obj?.let { (it["error"] ?: it["message"] ?: it["msg"]) }
            ?.let { element ->
                (element as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() }
                    ?: element.toString().takeIf { s -> s.isNotBlank() && s != "null" }
            }
        if (!fromObj.isNullOrBlank()) return fromObj
        val trimmed = rawText?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        // Avoid surfacing very large/non-text payloads as messages.
        return if (trimmed.length <= 300) trimmed else trimmed.take(300) + "…"
    }

    private fun transportMessage(action: String, e: Exception): String {
        val detail = e.message?.trim().orEmpty()
        return if (detail.isNotBlank()) "Gagal $action: $detail" else "Gagal $action."
    }

    private fun parseQueryParams(pathQuery: String): Map<String, String> =
        pathQuery.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
        }.toMap()

    private fun encodeReportParam(value: String): String = buildString {
        val safe = "-._~"
        value.trim().forEach { char ->
            when {
                char.isLetterOrDigit() || char in safe -> append(char)
                char.code <= 0x7f -> append('%').append(char.code.toString(16).uppercase().padStart(2, '0'))
                else -> char.toString().encodeToByteArray().forEach { append('%').append(it.toInt().and(0xff).toString(16).uppercase().padStart(2, '0')) }
            }
        }
    }

    internal fun buildReportTarget(params: Map<String, String>): String {
        val reportType = params["reportType"].orEmpty()
        val idp = params["idp"].orEmpty()
        val idu = params["idu"].orEmpty()
        val t1 = params["t1"].orEmpty()
        val t2 = params["t2"].orEmpty()
        val status = params["status"].orEmpty().ifBlank { "all" }
        val format = if (params["format"] == "xls") "xls" else "pdf"
        return when (reportType) {
            "per-pegawai" ->
                "/api/report/absensi/sby/per-pegawai.$format?idp=${encodeReportParam(idp)}&t1=${encodeReportParam(t1)}&t2=${encodeReportParam(t2)}"
            "per-pegawai-aktivitas" ->
                "/api/report/absensi/prg/per-pegawai-aktivitas.$format?idp=${encodeReportParam(idp)}&t1=${encodeReportParam(t1)}&t2=${encodeReportParam(t2)}"
            "rekap-instansi" ->
                "/api/report/absensi/sby/rekap-instansi.$format?idu=${encodeReportParam(idu)}&t1=${encodeReportParam(t1)}&t2=${encodeReportParam(t2)}&status=${encodeReportParam(status)}"
            "skor-per-instansi" ->
                "/api/report/absensi/sby/skor-per-instansi2026-new.$format?idu=${encodeReportParam(idu)}&t1=${encodeReportParam(t1)}&t2=${encodeReportParam(t2)}&status=${encodeReportParam(params["status"].orEmpty().ifBlank { "pns" })}"
            "aktivitas-per-instansi" ->
                "/api/report/absensi/prg/aktivitas-per-instansi2026-new.$format?idu=${encodeReportParam(idu)}&t1=${encodeReportParam(t1)}&t2=${encodeReportParam(t2)}&status=${encodeReportParam(params["status"].orEmpty().ifBlank { "pns" })}"
            "rekap-tpp-aktivitas" ->
                "/api/report/absensi/prg/rekap-tpp-aktivitas2026-new.$format?idu=${encodeReportParam(idu)}&t1=${encodeReportParam(t1)}&t2=${encodeReportParam(t2)}&status=${encodeReportParam(params["status"].orEmpty().ifBlank { "pns" })}"
            else -> throw PonorogoException("reportType tidak valid atau tidak didukung.")
        }
    }
}

class PonorogoException(message: String, cause: Throwable? = null) : Exception(message, cause)
