package id.tetuko.core.data.web

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

class WebPresensiClient(
    private val baseUrlProvider: () -> String,
    private val persistentStorage: ScopedPersistentCookiesStorage? = null,
) {
    var onTraffic: ((method: String, path: String, status: Int, request: String, response: String) -> Unit)? = null
    private val jsonHeaders = mapOf("Accept" to "text/html,application/xhtml+xml")

    @Volatile
    private var http: HttpClient = newClient()

    private fun newClient(): HttpClient = HttpClient {
        install(HttpCookies) { storage = persistentStorage ?: AcceptAllCookiesStorage() }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    suspend fun selectSessionScope(scope: String?) {
        persistentStorage?.selectScope(scope)
    }

    suspend fun clearSessionScope() {
        persistentStorage?.clearScope()
    }

    fun resetSession() {
        runCatching { http.close() }
        http = newClient()
    }

    private fun epUrl(path: String): String {
        val base = baseUrlProvider().trimEnd('/')
        val suffix = if (path.startsWith('/')) path else "/$path"
        return when {
            suffix.startsWith("/api/") -> "$base$suffix"
            suffix.startsWith("/ep/") || suffix == "/ep" -> "$base$suffix"
            else -> "$base/ep$suffix"
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.withEpBase(epBase: String?) {
        if (!epBase.isNullOrBlank()) header("x-presensi-ep-base", epBase.trimEnd('/'))
    }

    suspend fun loadCaptcha(epBase: String?): CaptchaImage {
        val r = Random.nextLong(1, Long.MAX_VALUE)
        val candidates = listOf(
            "/p/" to "/p/captcha?r=$r&reload=1",
            "/" to "/captcha?r=$r&reload=1",
            "/index.php/p/" to "/index.php/p/captcha?r=$r&reload=1",
            "/index.php/" to "/index.php/captcha?r=$r&reload=1",
            "/p/" to "/captcha?r=$r&reload=1",
        )
        var lastStatus = 0
        var lastError: String? = null
        var sawLoginPage = false
        for ((init, captcha) in candidates) {
            try {
                runCatching { http.get(epUrl(init)) { withEpBase(epBase) } }
                val response = http.get(epUrl(captcha)) { withEpBase(epBase) }
                lastStatus = response.status.value
                onTraffic?.invoke("GET", epUrl(captcha), response.status.value, "captcha refresh", "")
                if (!response.status.isSuccess()) continue
                val bytes = response.body<ByteArray>()
                val type = response.headers["Content-Type"]?.lowercase().orEmpty()
                if (bytes.size < 32) continue
                if (looksLikeLoginHtml(bytes, type)) {
                    sawLoginPage = true
                    continue
                }
                if (type.contains("text/html")) continue
                if (type.isNotBlank() && "image" !in type && "octet-stream" !in type) continue
                return CaptchaImage(bytes, type.ifBlank { "image/png" })
            } catch (e: Exception) {
                lastError = e.message?.trim()?.takeIf { it.isNotBlank() }
            }
        }
        if (sawLoginPage) {
            throw WebPresensiException(
                "Gagal memuat captcha: portal mengembalikan halaman login. Coba muat ulang captcha.",
                needsNewCaptcha = true,
            )
        }
        if (lastStatus != 0) throw WebPresensiException("Gagal memuat captcha (HTTP $lastStatus).")
        throw WebPresensiException(
            "Gagal memuat captcha${lastError?.let { " — $it" } ?: " (tidak ada endpoint yang cocok)."}",
        )
    }

    suspend fun login(epBase: String?, nip: String, password: String, captcha: String) {
        val response = http.post(epUrl("/p/login")) {
            withEpBase(epBase)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("m_user[email]", nip.filterNot { it.isWhitespace() })
                        append("m_user[password]", password.filterNot { it.isWhitespace() })
                        append("m_user[CAPTCHA]", captcha.filterNot { it.isWhitespace() })
                    },
                ),
            )
        }
        val html = response.bodyAsText()
        onTraffic?.invoke("POST", epUrl("/p/login"), response.status.value, "nip/password/captcha", html.take(20_000))
        if (isLoginPage(html)) {
            val message = loginErrorText(html).ifBlank { "Login gagal (periksa NIP, password, dan captcha)." }
            throw WebPresensiException(clarifyLoginMessage(message), needsNewCaptcha = true)
        }
        if (!response.status.isSuccess()) {
            throw WebPresensiException("Login gagal menghubungi server e-Presensi (HTTP ${response.status.value}).")
        }
    }

    suspend fun logout(epBase: String?) {
        runCatching { http.get(epUrl("/p/default/logout")) { withEpBase(epBase) } }
        resetSession()
    }

    suspend fun attendance(epBase: String?, page: Int = 1): AttendancePage {
        val suffix = if (page > 1) "?page=$page" else ""
        val html = getHtml("/p/checkinout$suffix", epBase)
        return AttendancePage(parseAttendance(html), page, countPages(html))
    }

    suspend fun leave(epBase: String?, page: Int = 1): LeavePage {
        val suffix = if (page > 1) "?page=$page" else ""
        val html = getHtml("/p/perizinan$suffix", epBase)
        return LeavePage(parseLeave(html), page, countPages(html))
    }

    suspend fun profile(epBase: String?): PortalProfile {
        val html = getHtml("/p/checkinout", epBase)
        val p = extractProfile(html)
        return PortalProfile(name = p?.first.orEmpty(), nip = p?.second.orEmpty(), imei = extractImeis(html).firstOrNull())
    }

    suspend fun profileDetails(epBase: String?): PortalDetails = parseDetails(getHtml("/p/pegawai/details/action", epBase))

    suspend fun solveCaptcha(image: ByteArray): CaptchaSolve? = runCatching {
        val response = http.post(epUrl("/api/ocr")) {
            contentType(ContentType.Application.OctetStream)
            setBody(image)
        }
        val text = response.bodyAsText()
        onTraffic?.invoke("POST", epUrl("/api/ocr"), response.status.value, "captcha image (${image.size} bytes)", text.take(20_000))
        if (!response.status.isSuccess()) return@runCatching null
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return@runCatching null
        val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull
            ?: (root["ok"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            ?: false
        if (!ok) return@runCatching null
        val solved = (root["text"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val yakin = (root["yakin"] as? JsonPrimitive)?.booleanOrNull
            ?: (root["yakin"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            ?: false
        val kandidat = (root["kandidat"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotBlank() } }
            ?.distinct()
            ?: emptyList()
        if (solved.isBlank()) null else CaptchaSolve(solved, kandidat, yakin)
    }.getOrNull()

    suspend fun latLong(idMap: String, epBase: String?): Pair<Double?, Double?> {
        val html = getHtml("/p/checkinout/load/action?latlong=${idMap.trim()}", epBase)
        val lat = Regex("Latitude\\s*:\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val lng = Regex("Longitude\\s*:\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        return lat to lng
    }

    suspend fun fetch(path: String, epBase: String?): ByteArray {
        val response = http.get(epUrl(path)) { withEpBase(epBase) }
        if (!response.status.isSuccess()) {
            throw WebPresensiException("Gagal memuat berkas (HTTP ${response.status.value}).")
        }
        val bytes = response.body<ByteArray>()
        val type = response.headers["Content-Type"].orEmpty()
        if (looksLikeLoginHtml(bytes, type)) {
            throw WebPresensiException("Sesi Web Presensi berakhir. Silakan login ulang.", sessionExpired = true)
        }
        return bytes
    }

    private suspend fun getHtml(path: String, epBase: String?): String {
        val response = try {
            http.get(epUrl(path)) {
                withEpBase(epBase)
                jsonHeaders.forEach { (k, v) -> header(k, v) }
            }
        } catch (e: Exception) {
            val detail = e.message?.trim().orEmpty()
            throw WebPresensiException(
                if (detail.isNotBlank()) "Gagal memuat halaman portal: $detail" else "Gagal memuat halaman portal.",
                cause = e,
            )
        }
        val html = response.bodyAsText()
        onTraffic?.invoke("GET", epUrl(path), response.status.value, "", html.take(20_000))
        if (!response.status.isSuccess()) {
            if (isLoginPage(html)) {
                throw WebPresensiException("Sesi Web Presensi berakhir. Silakan login ulang.", sessionExpired = true)
            }
            throw WebPresensiException("Gagal memuat halaman portal (HTTP ${response.status.value}).")
        }
        if (isLoginPage(html)) {
            throw WebPresensiException("Sesi Web Presensi berakhir. Silakan login ulang.", sessionExpired = true)
        }
        return html
    }

    private fun looksLikeLoginHtml(bytes: ByteArray, contentType: String): Boolean {
        val lowerType = contentType.lowercase()
        if (lowerType.isNotBlank() && "text/html" !in lowerType && "text/plain" !in lowerType) return false
        if (bytes.isEmpty()) return false
        val prefix = bytes.copyOfRange(0, minOf(bytes.size, 4096)).decodeToString()
        return isLoginPage(prefix)
    }

    companion object {
        fun isLoginPage(html: String): Boolean = html.contains("m_user[email]") && html.contains("m_user[CAPTCHA]")

        fun countPages(html: String): Int {
            var max = 1
            Regex("[?&]page=(\\d+)").findAll(html).forEach { m ->
                m.groupValues[1].toIntOrNull()?.let { if (it > max) max = it }
            }
            return max
        }

        fun decodeEntities(input: String): String = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(input) { m ->
            val token = m.groupValues[1]
            when {
                token.startsWith("#x", true) -> token.drop(2).toIntOrNull(16)?.toChar()?.toString() ?: m.value
                token.startsWith("#") -> token.drop(1).toIntOrNull()?.toChar()?.toString() ?: m.value
                token.equals("nbsp", true) -> " "
                token.equals("amp", true) -> "&"
                token.equals("lt", true) -> "<"
                token.equals("gt", true) -> ">"
                token.equals("quot", true) -> "\""
                token.equals("apos", true) -> "'"
                else -> m.value
            }
        }

        fun stripTags(html: String): String {
            val withBreaks = html
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<hr\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), " ")
            return decodeEntities(withBreaks)
                .replace('\u00A0', ' ')
                .lines()
                .map { line -> line.replace(Regex("[ \\t]+"), " ").trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
                .trim()
        }

        fun singleLine(html: String): String = stripTags(html).replace(Regex("\\s+"), " ").trim()

        private fun parts(html: String): List<String> = html.split(Regex("<br\\s*/?>|<hr\\s*/?>", RegexOption.IGNORE_CASE)).map { singleLine(it) }.filter { it.isNotEmpty() }

        private fun cell(row: String, classe: String): String {
            val m = Regex("<td[^>]*sf_admin_list_td_$classe\\b[^>]*>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE).find(row)
            return m?.groupValues?.get(1).orEmpty()
        }

        private fun rows(html: String): List<String> = Regex("<tr[\\s\\S]*?</tr>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()

        fun parseAttendance(html: String): List<PortalAttendance> {
            return rows(html).filter { it.contains("sf_admin_list_td_imei", ignoreCase = true) }.map { row ->
                val employee = parts(cell(row, "pegawai_id"))
                val latlong = cell(row, "latlong")
                val coord = Regex("(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)").find(latlong)
                val idMap = Regex("latlong=(\\d+)", RegexOption.IGNORE_CASE).find(latlong)?.groupValues?.get(1)
                val address = parts(latlong).drop(1).joinToString(" ").replace(Regex("^[-\\d.,\\s]+"), "").trim()
                val department = parts(cell(row, "departemen"))
                val approval = cell(row, "approval")
                val approvalParts = parts(approval)
                PortalAttendance(
                    id = Regex("name=\"ids\\[\\]\"\\s+value=\"(\\d+)\"", RegexOption.IGNORE_CASE).find(row)?.groupValues?.get(1) ?: idMap,
                    idMap = idMap,
                    name = employee.getOrNull(0).orEmpty(),
                    nip = employee.getOrNull(1).orEmpty(),
                    employeeStatus = employee.getOrNull(2).orEmpty(),
                    latitude = coord?.groupValues?.get(1)?.toDoubleOrNull(),
                    longitude = coord?.groupValues?.get(2)?.toDoubleOrNull(),
                    attendanceAddress = address,
                    unit = department.getOrNull(0).orEmpty(),
                    homeAddress = department.getOrNull(1).orEmpty(),
                    distance = singleLine(cell(row, "jarak")),
                    checkType = singleLine(cell(row, "checktype")),
                    imei = singleLine(cell(row, "imei")),
                    approved = when {
                        approval.contains("fa-check", ignoreCase = true) -> true
                        approval.contains("fa-times", ignoreCase = true) -> false
                        else -> null
                    },
                    approvalTime = approvalParts.firstOrNull { it.contains(Regex("\\d{4}")) }.orEmpty(),
                    createdAt = singleLine(cell(row, "created_at")),
                    wfh = singleLine(cell(row, "iswfh")),
                    workCode = singleLine(cell(row, "work_code")),
                )
            }
        }

        fun parseLeave(html: String): List<PortalLeave> {
            return rows(html).filter { it.contains("sf_admin_list_td_", ignoreCase = true) }.map { row ->
                val employee = parts(cell(row, "pegawai_id"))
                val unit = parts(cell(row, "departemen"))
                val date = parts(cell(row, "tgl_ijin"))
                val approval = cell(row, "approval")
                val attachment = cell(row, "attachment").ifBlank { row }
                PortalLeave(
                    id = Regex("name=\"ids\\[\\]\"\\s+value=\"(\\d+)\"", RegexOption.IGNORE_CASE).find(row)?.groupValues?.get(1),
                    name = employee.getOrNull(0).orEmpty(),
                    nip = employee.getOrNull(1).orEmpty(),
                    unit = unit.firstOrNull().orEmpty(),
                    type = singleLine(cell(row, "jenis_ijin_id")),
                    category = singleLine(cell(row, "pegawai_id_master_tipe_ijin")),
                    date = date.firstOrNull() ?: singleLine(cell(row, "tgl_ijin")),
                    reason = singleLine(cell(row, "keterangan")),
                    note = singleLine(cell(row, "approval_note")),
                    filePath = Regex("(?:href|src)=\"([^\"]*uploads/[^\"]+)\"", RegexOption.IGNORE_CASE).find(attachment)?.groupValues?.get(1),
                    approved = when {
                        approval.contains("fa-check", ignoreCase = true) -> true
                        approval.contains("fa-times", ignoreCase = true) -> false
                        else -> null
                    },
                    approvalTime = parts(approval).firstOrNull { it.contains(Regex("\\d{4}")) }.orEmpty(),
                    createdAt = singleLine(cell(row, "created_at")),
                )
            }
        }

        fun extractImeis(html: String): List<String> = Regex("sf_admin_list_td_imei\">\\s*([^<]+?)\\s*</").findAll(html.replace(Regex("\\s+"), " ")).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() && it != "-" }.distinct().toList()

        fun extractProfile(html: String): Pair<String, String>? {
            val m = Regex(">\\s*([^<>]{3,90}?)\\s*<br>\\s*(\\d{8,20})\\s*<br>").find(html.replace(Regex("\\s+"), " "))
            return m?.let { it.groupValues[1].trim() to it.groupValues[2] }
        }

        fun parseDetails(html: String): PortalDetails {
            val profile = linkedMapOf<String, String>()
            Regex("<td[^>]*>\\s*([^<>]{1,40}?)\\s*</td>\\s*<td[^>]*>\\s*:\\s*</td>\\s*<td[^>]*>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                val label = singleLine(m.groupValues[1])
                if (label.isNotEmpty()) profile[label] = singleLine(m.groupValues[2])
            }
            val photo = Regex("<img[^>]*width=\"150px\"[^>]*src=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            val employeeId = Regex("/(?:index\\.php/)?pegawai/(\\d+)/ijins", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            return PortalDetails(profile, photo, employeeId)
        }

        fun clarifyLoginMessage(message: String): String {
            val t = message.lowercase().replace(Regex("\\s+"), " ")
            fun matches(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(t)
            val nip = matches("(?:email|e-?mail|nip|username|user|akun).{0,60}(?:salah|keliru|tidak valid|tidak cocok|tidak ditemukan|tidak terdaftar|invalid|incorrect|wrong|not found)|(?:salah|keliru|tidak valid|tidak cocok|tidak ditemukan|tidak terdaftar|invalid|incorrect|wrong).{0,60}(?:email|e-?mail|nip|username|user|akun)")
            val password = matches("(?:password|kata sandi|sandi).{0,60}(?:salah|keliru|tidak valid|tidak cocok|tidak sesuai|invalid|incorrect|wrong)|(?:salah|keliru|tidak valid|tidak cocok|tidak sesuai|invalid|incorrect|wrong).{0,60}(?:password|kata sandi|sandi)")
            val captcha = matches("(?:captcha|capcha|kode verifikasi).{0,60}(?:salah|keliru|tidak valid|tidak cocok|invalid|incorrect|wrong)|(?:salah|keliru|tidak valid|tidak cocok|invalid|incorrect|wrong).{0,60}(?:captcha|capcha|kode verifikasi)")
            val hits = listOf(nip, password, captcha).count { it }
            if (hits != 1) return message
            return when {
                nip -> "Email / NIP tidak terdaftar."
                password -> "Password salah."
                else -> "Captcha salah."
            }
        }

        fun loginErrorText(html: String): String {
            val out = mutableListOf<String>()
            val re = Regex("<(?:div|span|p)\\b(?=[^>]*\\bclass\\s*=\\s*([\"'])[^\"']*(?:alert-danger|help-block|invalid-feedback|text-danger|error)[^\"']*\\1)[^>]*>([\\s\\S]*?)</(?:div|span|p)>", RegexOption.IGNORE_CASE)
            re.findAll(html).forEach { m ->
                val text = singleLine(m.groupValues[2])
                if (text.isNotEmpty()) out += text
            }
            return out.joinToString(" ")
        }
    }
}

class WebPresensiException(
    message: String,
    val needsNewCaptcha: Boolean = false,
    val sessionExpired: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

data class CaptchaImage(val bytes: ByteArray, val contentType: String)
data class CaptchaSolve(val text: String, val candidates: List<String>, val confident: Boolean)
data class PortalProfile(val name: String, val nip: String, val imei: String?)
data class PortalAttendance(
    val id: String?,
    val idMap: String?,
    val name: String,
    val nip: String,
    val employeeStatus: String,
    val latitude: Double?,
    val longitude: Double?,
    val attendanceAddress: String,
    val unit: String,
    val homeAddress: String,
    val distance: String,
    val checkType: String,
    val imei: String,
    val approved: Boolean?,
    val approvalTime: String,
    val createdAt: String,
    val wfh: String,
    val workCode: String,
)
data class AttendancePage(val rows: List<PortalAttendance>, val page: Int, val totalPages: Int)
data class PortalLeave(
    val id: String?,
    val name: String,
    val nip: String,
    val unit: String,
    val type: String,
    val category: String,
    val date: String,
    val reason: String,
    val note: String,
    val filePath: String?,
    val approved: Boolean?,
    val approvalTime: String,
    val createdAt: String,
)
data class LeavePage(val rows: List<PortalLeave>, val page: Int, val totalPages: Int)
data class PortalDetails(val profile: Map<String, String>, val photoPath: String?, val employeeId: String?)
