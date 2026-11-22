package id.tetuko.app

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object AndroidAutomationCloudStore {
    fun isConfigured(): Boolean =
        BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

    fun read(accountId: String, profileId: String): String? {
        if (!isConfigured()) return null
        val url = documentUrl(accountId, profileId)
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.requestMethod = "GET"
            connection!!.connectTimeout = 20_000
            connection!!.readTimeout = 20_000
            val code = connection!!.responseCode
            if (code == 404) return@runCatching null
            val stream = if (code in 200..299) connection!!.inputStream else connection!!.errorStream
            val text = stream?.let { BufferedReader(it.reader()).use(BufferedReader::readText) }.orEmpty()
            if (code !in 200..299) return@runCatching null
            val fields = JSONObject(text).optJSONObject("fields") ?: return@runCatching null
            fields.optJSONObject("payload")?.optString("stringValue")
        }.getOrNull().also { connection?.disconnect() }
    }

    fun write(accountId: String, profileId: String, payloadJson: String): Boolean {
        if (!isConfigured()) return false
        val url = documentUrl(accountId, profileId)
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.requestMethod = "PATCH"
            connection!!.connectTimeout = 20_000
            connection!!.readTimeout = 20_000
            connection!!.doOutput = true
            connection!!.setRequestProperty("Content-Type", "application/json")
            val now = java.time.Instant.now().toString()
            val payload = JSONObject()
                .put("fields", JSONObject()
                    .put("schemaVersion", JSONObject().put("integerValue", "1"))
                    .put("accountId", JSONObject().put("stringValue", safeKey(accountId)))
                    .put("profileId", JSONObject().put("stringValue", safeKey(profileId)))
                    .put("payload", JSONObject().put("stringValue", payloadJson))
                    .put("updatedAt", JSONObject().put("stringValue", now)))
            connection!!.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            connection!!.responseCode in 200..299
        }.getOrDefault(false).also { connection?.disconnect() }
    }

    private fun documentUrl(accountId: String, profileId: String): String {
        val project = BuildConfig.FIREBASE_PROJECT_ID
        val key = BuildConfig.FIREBASE_API_KEY
        val doc = "automation_${safeKey(accountId)}_${safeKey(profileId)}"
        val collection = URLEncoder.encode("settings", StandardCharsets.UTF_8)
        val document = URLEncoder.encode(doc, StandardCharsets.UTF_8)
        return "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/$collection/$document?key=${URLEncoder.encode(key, StandardCharsets.UTF_8)}"
    }

    private fun safeKey(value: String): String = value.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9_-]"), "_")
        .take(80)
        .ifBlank { "local" }
}
