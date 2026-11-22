package id.tetuko.proxy

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

/**
 * In-process reimplementation of the original TETUKO serverless proxy
 * (`api/rpc.ts`, `api/proxy.ts`, `api/upload.ts`, `api/ep.ts`, ...).
 *
 * The desktop app starts this on 127.0.0.1 so BKD RPC, Ponorogo form/multipart
 * calls, the `/ep` web portal, file uploads and image proxying work without an
 * external Node dev-server or hosted deployment.
 */
fun Application.proxyModule() {
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.Accept)
        allowHeader("X-Requested-With")
        allowHeader("x-presensi-gateway")
        allowHeader("x-presensi-ep-base")
        allowNonSimpleContentTypes = true
    }

    routing {
        get("/health") {
            call.respondText("""{"ok":true,"service":"tetuko-proxy"}""", ContentType.Application.Json)
        }

        post("/api/rpc") {
            val input = runCatching { json.parseToJsonElement(call.receiveTextCompat()).jsonObject }
                .getOrElse { JsonObject(emptyMap()) }
            val paramMap = ((input["param"] as? JsonObject) ?: JsonObject(emptyMap())).toMutableMap()
            val gateway = ProxyConfig.resolveGateway(
                call.request.headers["x-presensi-gateway"],
                paramMap,
            )
            paramMap.remove("gateway_url")
            if (paramMap["api_key"] == null) paramMap["api_key"] = JsonPrimitive("")
            if (paramMap["last_latlong"] == null) paramMap["last_latlong"] = JsonPrimitive("")
            if (paramMap["imei"] == null) paramMap["imei"] = JsonPrimitive("")

            val envelope = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "POST")
                put("version", ProxyConfig.PRESENSI_VERSION)
                put("object", (input["object"] as? JsonPrimitive)?.contentOrNull ?: "")
                put("param", JsonObject(paramMap))
            }
            val upstream = runCatching {
                Upstream.send(
                    "POST",
                    gateway,
                    mapOf(
                        "Content-Type" to "application/json; charset=UTF-8",
                        "Accept" to "application/json",
                        "User-Agent" to "okhttp/4.12.0",
                    ),
                    envelope.toString().toByteArray(),
                )
            }.getOrElse { error ->
                call.respondJsonElement(
                    502,
                    jsonObjectOf(
                        "error" to "Gagal menghubungi gateway: ${error.safeMessage()}",
                        "errorCode" to "502",
                        "huluStatus" to "502",
                    ),
                )
                return@post
            }

            val parsed = parseJsonOrNull(upstream.body)
            val payload = parsed ?: buildJsonObject {
                put("error", "Respons gateway bukan JSON")
                put("raw", upstream.body.decodeToString())
                put("huluStatus", 502)
            }
            if (upstream.status !in 200..299) {
                val enriched = when (payload) {
                    is JsonObject -> JsonObject(
                        payload + if (payload["huluStatus"] == null) mapOf("huluStatus" to JsonPrimitive(upstream.status)) else emptyMap(),
                    )
                    else -> payload
                }
                call.respondJsonElement(upstream.status, enriched)
            } else {
                call.respondJsonElement(200, payload)
            }
        }

        // Ponorogo form-encoded bridge: mirrors `POST /api/proxy`.
        post("/api/proxy") {
            val body = runCatching { json.parseToJsonElement(call.receiveTextCompat()).jsonObject }
                .getOrElse { JsonObject(emptyMap()) }
            val endpoint = (body["endpoint"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val payload = (body["payload"] as? JsonObject) ?: JsonObject(emptyMap())
            val clean = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
            val apiBase = ProxyConfig.resolvePonorogoApiBase((body["api_base"] as? JsonPrimitive)?.contentOrNull)
            val target = apiBase + clean

            val form = payload.entries
                .filter { it.value !is JsonNull }
                .joinToString("&") { (key, value) ->
                    "${encode(key)}=${encode(scalar(value))}"
                }
            val upstream = runCatching {
                Upstream.send(
                    "POST",
                    target,
                    mapOf(
                        "User-Agent" to "Dart/3.0 (dart:io)",
                        "Accept" to "application/json, text/plain, */*",
                        "Authorization" to "Bearer null",
                        "Content-Type" to "application/x-www-form-urlencoded",
                    ),
                    form.toByteArray(),
                )
            }.getOrElse { error ->
                call.respondJsonElement(502, jsonObjectOf("error" to "Gagal menghubungi server Ponorogo: ${error.safeMessage()}"))
                return@post
            }
            call.respondJsonElement(upstream.status, parseJsonOrNull(upstream.body) ?: jsonObjectOf("raw" to upstream.body.decodeToString()))
        }

        // Ponorogo multipart bridge: fields + base64 file.
        post("/api/proxy-multipart") {
            val body = runCatching { json.parseToJsonElement(call.receiveTextCompat()).jsonObject }
                .getOrElse { JsonObject(emptyMap()) }
            val endpoint = (body["endpoint"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val fields = (body["fields"] as? JsonObject) ?: JsonObject(emptyMap())
            val file = body["file"] as? JsonObject
            val clean = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
            val apiBase = ProxyConfig.resolvePonorogoApiBase((body["api_base"] as? JsonPrimitive)?.contentOrNull)
            val target = apiBase + clean

            val boundary = "----TETUKOBoundary${System.nanoTime()}"
            val out = ByteArrayOutputStream()
            fields.entries.filter { it.value !is JsonNull }.forEach { (key, value) ->
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$key\"\r\n\r\n".toByteArray())
                out.write(scalar(value).toByteArray(Charsets.UTF_8))
                out.write("\r\n".toByteArray())
            }
            val base64 = (file?.get("base64") as? JsonPrimitive)?.contentOrNull
            if (!base64.isNullOrBlank()) {
                val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrDefault(ByteArray(0))
                val filename = (file!!["filename"] as? JsonPrimitive)?.contentOrNull ?: "lampiran.jpg"
                val mime = (file["mimeType"] as? JsonPrimitive)?.contentOrNull ?: "image/jpeg"
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"lampiran\"; filename=\"$filename\"\r\n".toByteArray())
                out.write("Content-Type: $mime\r\n\r\n".toByteArray())
                out.write(bytes)
                out.write("\r\n".toByteArray())
            }
            out.write("--$boundary--\r\n".toByteArray())

            val upstream = runCatching {
                Upstream.send(
                    "POST",
                    target,
                    mapOf(
                        "User-Agent" to "Dart/3.0 (dart:io)",
                        "Accept" to "application/json",
                        "Content-Type" to "multipart/form-data; boundary=$boundary",
                    ),
                    out.toByteArray(),
                )
            }.getOrElse { error ->
                call.respondJsonElement(502, jsonObjectOf("error" to "Gagal kirim multipart: ${error.safeMessage()}"))
                return@post
            }
            call.respondJsonElement(upstream.status, parseJsonOrNull(upstream.body) ?: jsonObjectOf("raw" to upstream.body.decodeToString()))
        }

        // Raw upload bridge (e.g. `POST /api/upload?path=/importfile`).
        route("/api/upload") {
            handle {
                val epBase = ProxyConfig.resolveEpBase(
                    call.request.headers["x-presensi-ep-base"],
                    call.request.queryParameters["ep_base"],
                )
                val path = call.request.queryParameters["path"] ?: "/importfile"
                val target = when {
                    path.startsWith("http") -> {
                        if (!ProxyConfig.allowedTarget(path, true)) {
                            call.respondJsonElement(403, jsonObjectOf("error" to "Target upload tidak diizinkan"))
                            return@handle
                        }
                        path
                    }
                    path.startsWith("/") -> epBase + path
                    else -> "$epBase/$path"
                }
                val bytes = runCatching { call.receiveChannel().toByteArray() }.getOrDefault(ByteArray(0))
                val headers = mutableMapOf("Accept" to "application/json")
                call.request.headers[HttpHeaders.ContentType]?.let { headers["Content-Type"] = it }
                val upstream = runCatching {
                    Upstream.send(call.request.httpMethod.value, target, headers, bytes)
                }.getOrElse { error ->
                    call.respondJsonElement(502, jsonObjectOf("error" to error.safeMessage()))
                    return@handle
                }
                call.respondJsonElement(
                    upstream.status,
                    parseJsonOrNull(upstream.body) ?: jsonObjectOf("raw" to upstream.body.decodeToString()),
                )
            }
        }

        get("/api/absensi-log-proxy") {
            val q = call.request.queryParameters
            val unor = q["unor"].orEmpty().trim()
            val start = q["dateStart"] ?: q["start"].orEmpty()
            val end = q["dateEnd"] ?: q["end"].orEmpty()
            val nama = q["nama"].orEmpty().trim()
            val nip = q["nip"].orEmpty().trim()
            val page = (q["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val size = ((q["size"]?.toIntOrNull() ?: 25).coerceIn(1, 100))

            if (unor.isBlank() && (nama.isBlank() || nama == "null") && (nip.isBlank() || nip == "null")) {
                call.respondJsonElement(
                    400,
                    jsonObjectOf("error" to "Pilih instansi atau masukkan nama/NIP untuk melakukan pencarian."),
                )
                return@get
            }
            val unorVal = if (unor.isNotBlank()) stripDanger(unor) else "null"
            val namaVal = if (nama.isNotBlank() && nama != "null") encode(stripDanger(nama)) else "null"
            val nipVal = if (nip.isNotBlank() && nip != "null") encode(stripDanger(nip)) else "null"
            val webBase = ProxyConfig.resolvePonorogoWebBase(q["web_base"])
            val target = "$webBase/api/absensi-log/unor/$unorVal/" +
                "${encode(start)}/${encode(end)}/$namaVal/$nipVal?page.page=$page&page.size=$size&page=$page&size=$size"

            val upstream = runCatching {
                Upstream.sendInsecure(
                    "GET",
                    target,
                    mapOf(
                        "Accept" to "application/json, text/plain, */*",
                        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
                        "User-Agent" to
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Referer" to "${webBase.trimEnd('/')}/",
                        "Origin" to webBase.trimEnd('/'),
                        "Cache-Control" to "no-cache",
                    ),
                )
            }.getOrElse { error ->
                val timeout = error.message?.contains("timed out", ignoreCase = true) == true
                call.respondJsonElement(
                    if (timeout) 504 else 502,
                    jsonObjectOf("error" to if (timeout) "Request ke server presensi timeout. Coba lagi."
                    else "Gagal memuat log presensi instansi: ${error.safeMessage()}"),
                )
                return@get
            }
            val parsed = parseJsonOrNull(upstream.body)
            if (upstream.status != 200) {
                val enriched = when (parsed) {
                    is JsonObject -> JsonObject(parsed + mapOf("status" to JsonPrimitive(upstream.status)))
                    else -> jsonObjectOf(
                        "error" to "Server presensi mengembalikan HTTP ${upstream.status}",
                        "raw" to upstream.body.decodeToString().take(500),
                    )
                }
                call.respondJsonElement(upstream.status, enriched)
            } else if (parsed == null) {
                call.respondJsonElement(
                    502,
                    jsonObjectOf(
                        "error" to "Respons log presensi tidak valid",
                        "raw" to upstream.body.decodeToString().take(500),
                    ),
                )
            } else {
                call.respondJsonElement(200, parsed)
            }
        }

        get("/api/proxy-image") {
            val q = call.request.queryParameters
            var raw = (q["url"] ?: q["path"]).orEmpty().trim()
            val useBaseUrl = (q["use_base_url"] ?: q["useBaseUrl"]) == "true"
            if (raw.isBlank()) {
                call.respondText("path/url diperlukan", status = HttpStatusCode.BadRequest)
                return@get
            }
            if (raw.startsWith("data:")) {
                call.respondText("data URL tidak didukung", status = HttpStatusCode.BadRequest)
                return@get
            }
            if (raw.startsWith("/http://") || raw.startsWith("/https://")) raw = raw.drop(1)

            val epBase = ProxyConfig.resolveEpBase(
                call.request.headers["x-presensi-ep-base"],
                q["ep_base"],
            )
            val webBase = ProxyConfig.resolvePonorogoWebBase(q["web_base"])
            val imageBase = ProxyConfig.resolvePonorogoImageBase(q["image_base"])
            val candidates = imageCandidates(raw, useBaseUrl, epBase, webBase, imageBase)
            var lastError: Throwable? = null
            for (candidate in candidates) {
                try {
                    val upstream = Upstream.send(
                        "GET",
                        candidate,
                        mapOf(
                            "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
                            "User-Agent" to
                                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        ),
                    )
                    val ct = upstream.contentType ?: "application/octet-stream"
                    if (upstream.status !in 200..299 || (ct.contains("text/html") && upstream.body.size < 2048)) {
                        lastError = IllegalStateException("bad response ${upstream.status} $ct")
                        continue
                    }
                    call.response.headers.append("Cache-Control", "public, max-age=3600", safeOnly = false)
                    call.respondBytes(
                        upstream.body,
                        runCatching { ContentType.parse(ct) }.getOrDefault(ContentType.Application.OctetStream),
                        HttpStatusCode.fromValue(upstream.status),
                    )
                    return@get
                } catch (e: Exception) {
                    lastError = e
                }
            }
            call.respondText(
                "Gagal memuat gambar: ${lastError?.message ?: "tidak ada kandidat"}",
                status = HttpStatusCode.BadGateway,
            )
        }

        // Captcha OCR: native in-process ONNX engine (parity with Rust/python)
        // with fallback to an external solver when the model is unavailable.
        post("/api/ocr") {
            val bytes = runCatching { call.receiveChannel().toByteArray() }.getOrDefault(ByteArray(0))
            if (bytes.isEmpty()) {
                call.respondJsonElement(
                    400,
                    buildJsonObject { put("ok", false); put("error", "Body gambar kosong.") },
                )
                return@post
            }

            // 1) Native engine (ocr_model.onnx + ocr_charset.json).
            val native = runCatching { CaptchaOcr.solve(bytes) }
            if (native.isSuccess) {
                val r = native.getOrThrow()
                call.respondJsonElement(
                    200,
                    buildJsonObject {
                        put("ok", r.ok)
                        put("text", r.text)
                        put("kandidat", JsonArray(r.kandidat.map { JsonPrimitive(it) }))
                        put("yakin", r.yakin)
                        put("panjang", r.panjang)
                        put("engine", r.engine)
                        r.error?.let { put("error", it) }
                    },
                )
                return@post
            }
            val nativeErr = native.exceptionOrNull()

            // 2) External bridge fallback (legacy python service / custom URL).
            val targets = listOfNotNull(
                System.getenv("TETUKO_OCR_URL")?.takeIf { it.isNotBlank() },
                System.getenv("PRESENSI_OCR_URL")?.takeIf { it.isNotBlank() },
                "http://127.0.0.1:8791/ocr",
            ).distinct()
            var lastError: String? = nativeErr?.message
            for (target in targets) {
                val upstream = runCatching {
                    Upstream.send(
                        "POST",
                        target,
                        mapOf(
                            "Content-Type" to "application/octet-stream",
                            "Accept" to "application/json",
                        ),
                        bytes,
                    )
                }.getOrElse { e ->
                    lastError = e.safeMessage()
                    null
                } ?: continue
                val parsed = parseJsonOrNull(upstream.body)
                if (parsed != null) {
                    call.respondJsonElement(upstream.status.coerceIn(200, 599), parsed)
                    return@post
                }
            }
            call.respondJsonElement(
                503,
                buildJsonObject {
                    put("ok", false)
                    put("error", "Mesin OCR otomatis tidak tersedia (input manual diperlukan).")
                    put("detail", lastError ?: "no backend")
                },
            )
        }

        // Reverse proxy for the /ep web portal (captcha/OCR flows, login, etc.).
        route("/ep") {
            handle { call.handleEp() }
            route("/{path...}") { handle { call.handleEp() } }
        }
    }
}

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

private val HOP_BY_HOP = setOf(
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length",
    "content-encoding",
)

private val REQUEST_SKIP = HOP_BY_HOP + setOf(
    "x-presensi-ep-base", "x-presensi-gateway", "x-forwarded-host", "x-forwarded-for",
    "accept-encoding", "cookie", "origin", "referer",
)

private val RESPONSE_SKIP = HOP_BY_HOP + setOf("set-cookie", "content-type", "content-length")

private suspend fun ApplicationCall.handleEp() {
    val epBase = ProxyConfig.resolveEpBase(
        request.headers["x-presensi-ep-base"],
        request.queryParameters["ep_base"],
    )
    val targetOrigin = runCatching {
        val u = URI(epBase)
        "${u.scheme}://${u.authority}"
    }.getOrDefault(epBase)

    val uri = request.uri
    val parsed = runCatching { URI(uri) }.getOrNull()
    var upstreamPath = (parsed?.rawPath ?: "/ep").removePrefix("/ep")
    if (upstreamPath.isBlank()) upstreamPath = "/"
    val overridePath = request.queryParameters["__path"]
    if ((upstreamPath == "/" || upstreamPath.isBlank()) && !overridePath.isNullOrBlank()) {
        upstreamPath = if (overridePath.startsWith("/")) overridePath else "/$overridePath"
    }
    val query = (parsed?.rawQuery ?: "")
        .split('&')
        .filter { it.isNotBlank() }
        .filterNot { it.substringBefore('=') == "__path" || it.substringBefore('=') == "ep_base" }
        .joinToString("&")
    if (query.isNotEmpty()) {
        upstreamPath += if (upstreamPath.contains("?")) "&$query" else "?$query"
    }

    val method = request.httpMethod.value
    val body = if (method == "GET" || method == "HEAD") null
    else runCatching { receiveChannel().toByteArray() }.getOrNull()

    val forwarded = LinkedHashMap<String, String>()
    for ((name, values) in request.headers.entries()) {
        val lower = name.lowercase()
        if (lower !in REQUEST_SKIP) forwarded[name] = values.joinToString(", ")
    }
    request.headers[HttpHeaders.Cookie]?.let { forwarded["Cookie"] = it }
    forwarded["Origin"] = targetOrigin
    forwarded["Referer"] = "$targetOrigin/"

    val upstream = runCatching { fetchEpWithFallbacks(method, epBase, upstreamPath, forwarded, body) }
        .getOrElse { error ->
            respondJsonElement(
                502,
                jsonObjectOf(
                    "error" to "Gagal menghubungi server Web Presensi",
                    "detail" to error.safeMessage(),
                ),
            )
            return
        }

    upstream.headers.forEach { (name, values) ->
        val lower = name.lowercase()
        when {
            lower == "location" -> values.firstOrNull()?.let {
                response.headers.append("Location", rewriteLocation(it, epBase), safeOnly = false)
            }
            lower in RESPONSE_SKIP -> Unit
            else -> values.forEach { response.headers.append(name, it, safeOnly = false) }
        }
    }
    upstream.headers["set-cookie"]?.forEach { cookie ->
        response.headers.append("Set-Cookie", rewriteSetCookie(cookie), safeOnly = false)
    }

    if (method == "HEAD") {
        respondBytes(ByteArray(0), status = HttpStatusCode.fromValue(upstream.status))
        return
    }
    respondBytes(
        upstream.body,
        runCatching { ContentType.parse(upstream.contentType ?: "application/octet-stream") }
            .getOrDefault(ContentType.Application.OctetStream),
        HttpStatusCode.fromValue(upstream.status),
    )
}

private suspend fun ApplicationCall.respondJsonElement(status: Int, element: JsonElement) {
    respondBytes(
        element.toString().toByteArray(),
        ContentType.Application.Json,
        HttpStatusCode.fromValue(status.coerceIn(100, 599)),
    )
}

private suspend fun ApplicationCall.receiveTextCompat(): String =
    runCatching { receiveChannel().toByteArray().decodeToString() }.getOrDefault("")

private fun parseJsonOrNull(bytes: ByteArray): JsonElement? =
    runCatching { json.parseToJsonElement(bytes.decodeToString()) }.getOrNull()

private fun jsonObjectOf(vararg pairs: Pair<String, String>): JsonObject =
    JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

private fun scalar(value: JsonElement): String = when (value) {
    is JsonPrimitive -> value.contentOrNull ?: value.toString()
    is JsonArray -> value.toString()
    else -> value.toString()
}

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun stripDanger(value: String): String = value.replace(Regex("[^a-zA-Z0-9 ._\\-]"), "").trim()

private fun Throwable.safeMessage(fallback: String = "upstream request failed"): String {
    val msg = message?.trim()?.takeIf { it.isNotEmpty() }
    val causeMsg = cause?.message?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        msg != null && causeMsg != null && !msg.contains(causeMsg, ignoreCase = true) -> "$msg ($causeMsg)"
        msg != null -> msg
        causeMsg != null -> causeMsg
        else -> fallback
    }
}

private fun epPathCandidates(pathWithQuery: String): List<String> {
    val qIdx = pathWithQuery.indexOf('?')
    val rawPath = if (qIdx >= 0) pathWithQuery.substring(0, qIdx) else pathWithQuery
    val query = if (qIdx >= 0) pathWithQuery.substring(qIdx) else ""
    val normalized = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
    val noIndex = normalized.replace(Regex("^/index\\.php(?=/|$)"), "")
    val basePath = noIndex.ifBlank { "/" }
    val strippedP = basePath.replace(Regex("^/p(?=/|$)"), "")
    val clean = strippedP.ifBlank { "/" }

    val candidates = LinkedHashSet<String>()
    fun add(path: String) {
        val p = if (path.startsWith("/")) path else "/$path"
        candidates.add(p + query)
    }

    // Preserve explicit /index.php/... requests, otherwise try common e-Presensi layouts.
    when {
        normalized.startsWith("/index.php/") || normalized == "/index.php" -> {
            add(normalized)
            add(clean)
            add("/index.php$clean")
        }
        normalized.startsWith("/p/") || normalized == "/p" -> {
            add("/index.php$clean")
            add(clean)
            add(normalized)
        }
        else -> {
            add(normalized)
            add("/index.php$normalized")
        }
    }
    return candidates.toList()
}

private fun isEpRedirectToCandidate(response: UpstreamResponse, epBase: String, candidates: Set<String>): Boolean {
    if (response.status !in 300..399) return false
    val location = response.headers.entries.firstOrNull { it.key.equals("location", ignoreCase = true) }?.value?.firstOrNull() ?: return false
    val resolvedPath = runCatching {
        val uri = URI(epBase).resolve(location)
        buildString {
            append(uri.rawPath.ifBlank { "/" })
            uri.rawQuery?.let { append('?').append(it) }
        }
    }.getOrDefault(location)
    return resolvedPath in candidates
}

private suspend fun fetchEpWithFallbacks(
    method: String,
    epBase: String,
    upstreamPath: String,
    forwarded: Map<String, String>,
    body: ByteArray?,
): UpstreamResponse {
    val candidates = epPathCandidates(upstreamPath)
    val candidateSet = candidates.toSet()
    var last: UpstreamResponse? = null
    var lastError: Exception? = null
    for ((index, candidate) in candidates.withIndex()) {
        val response = try {
            Upstream.send(method, epBase + candidate, forwarded, body)
        } catch (e: Exception) {
            lastError = e
            continue
        }
        last = response
        val hasNext = index < candidates.lastIndex
        if (response.status == 404 && hasNext) continue
        if (hasNext && isEpRedirectToCandidate(response, epBase, candidateSet)) continue
        return response
    }
    lastError?.let { throw it }
    return last ?: throw IllegalStateException("Tidak ada kandidat path /ep yang dapat dicoba.")
}

private fun rewriteSetCookie(cookie: String): String {
    var c = cookie
        .replace(Regex(";\\s*domain=[^;]*", RegexOption.IGNORE_CASE), "")
        .replace(Regex(";\\s*path=[^;]*", RegexOption.IGNORE_CASE), "")
        .replace(Regex(";\\s*secure", RegexOption.IGNORE_CASE), "")
        .trimEnd(';', ' ', '\t')
    c += "; Path=/ep"
    return c
}

private fun rewriteLocation(location: String, epBase: String): String {
    var out = runCatching {
        val resolved = URI(epBase).resolve(location)
        buildString {
            append(resolved.rawPath.ifBlank { "/" })
            resolved.rawQuery?.let { append('?').append(it) }
            resolved.rawFragment?.let { append('#').append(it) }
        }
    }.getOrElse { location }
    if (!out.startsWith("/")) out = "/$out"
    if (!Regex("^/ep(/|$)").containsMatchIn(out)) {
        out = "/ep" + if (out == "/") "/" else out
    }
    return out.replace(Regex("/index\\.php(/|$)"), "/p$1")
}

private fun imageCandidates(raw: String, useBaseUrl: Boolean, epBase: String, webBase: String, imageBase: String): List<String> {
    val candidates = LinkedHashSet<String>()
    if (raw.startsWith("http://") || raw.startsWith("https://")) {
        if (!ProxyConfig.allowedTarget(raw, true)) return emptyList()
        val uri = runCatching { URI(raw) }.getOrNull()
        if (uri == null) {
            candidates.add(raw)
        } else {
            val path = buildString {
                append(uri.rawPath.ifBlank { "/" })
                uri.rawQuery?.let { append('?').append(it) }
            }
            val host = uri.host?.lowercase().orEmpty()
            val isConfiguredPonorogoHost = host == ProxyConfig.hostOf(imageBase) || host == ProxyConfig.hostOf(webBase)
            if (isConfiguredPonorogoHost) {
                if (imageBase.isNotBlank()) candidates.add(imageBase + path)
                if (webBase.isNotBlank()) candidates.add(webBase + path)
                candidates.add(raw)
            } else {
                candidates.add(raw)
            }
        }
    } else {
        val clean = if (raw.startsWith("/")) raw else "/$raw"
        val primary = if (useBaseUrl) webBase + clean else imageBase + clean
        val fallback = if (useBaseUrl) imageBase + clean else webBase + clean
        candidates.add(primary)
        candidates.add(fallback)
        listOf(
            epBase,
            ProxyConfig.jatimEpBase,
            ProxyConfig.madiunEpBase,
            webBase,
        ).filter { it.isNotBlank() }.forEach { candidates.add(it + clean) }
    }
    return candidates.toList()
}

/**
 * Starts the local proxy on [host]:[port]. Bind failures (e.g. an external
 * dev-server already listening on the port) are surfaced to the caller.
 */
fun startProxyServer(port: Int = 14200, host: String = "127.0.0.1"): AutoCloseable {
    val server = embeddedServer(CIO, port = port, host = host) { proxyModule() }
    server.start(wait = false)
    return AutoCloseable { runCatching { server.stop(500, 2000) } }
}
