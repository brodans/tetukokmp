package id.tetuko.app.desktop

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Lightweight env loader for JVM desktop runtime.
 * Priority: process environment variables, then values parsed from .env files.
 *
 * Environment names use the native desktop `.env` contract without frontend-tool prefixes.
 */
object DesktopEnv {
    @Volatile
    private var loaded = false
    private val dotenv: MutableMap<String, String> = linkedMapOf()

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        dotenv.clear()
        candidateEnvFiles().forEach { path ->
            runCatching { parseDotEnv(path) }.getOrNull()?.forEach { (k, v) ->
                if (k.isNotBlank() && !dotenv.containsKey(k)) dotenv[k] = v
            }
        }
        loaded = true
    }

    fun reload() {
        loaded = false
        ensureLoaded()
    }

    /** Returns process env first, then .env fallback. Blank values are treated as absent. */
    fun get(name: String): String? {
        ensureLoaded()
        val fromProcess = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
        if (fromProcess != null) return fromProcess
        return dotenv[name]?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun getAny(vararg names: String): String? = names.firstNotNullOfOrNull { get(it) }

    fun snapshot(): Map<String, String> {
        ensureLoaded()
        val merged = LinkedHashMap(dotenv)
        System.getenv().forEach { (k, v) ->
            if (!v.isNullOrBlank()) merged[k] = v
        }
        return merged
    }

    private fun candidateEnvFiles(): List<Path> {
        val out = linkedSetOf<Path>()
        fun addIfFile(p: Path?) {
            if (p == null) return
            val n = p.normalize().toAbsolutePath()
            if (Files.isRegularFile(n)) out.add(n)
        }
        // 1) Current working directory (common when launched from project/app dir)
        runCatching { addIfFile(Paths.get("").toAbsolutePath().resolve(".env")) }
        runCatching { addIfFile(Paths.get(System.getProperty("user.dir", "")).resolve(".env")) }
        // 2) User home (fallback for packaged app launches)
        runCatching { addIfFile(Paths.get(System.getProperty("user.home", "")).resolve(".env")) }
        // 3) Directory containing the running jar/app classes when resolvable
        runCatching {
            val codeSource = DesktopEnv::class.java.protectionDomain?.codeSource?.location?.toURI()
            if (codeSource != null) {
                var p = Paths.get(codeSource)
                if (Files.isRegularFile(p)) p = p.parent
                // Walk up a few levels to find a nearby .env (e.g. app bundle root)
                var cur: Path? = p
                repeat(5) {
                    if (cur != null) addIfFile(cur!!.resolve(".env"))
                    cur = cur?.parent
                }
            }
        }
        return out.toList()
    }

    private fun parseDotEnv(path: Path): Map<String, String> {
        val text = runCatching { Files.readString(path, StandardCharsets.UTF_8) }
            .getOrElse { Files.readString(path) }
        val result = linkedMapOf<String, String>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            var line = lines[i]
            i++
            val trimmedStart = line.trimStart()
            if (trimmedStart.isEmpty() || trimmedStart.startsWith('#')) continue
            if (trimmedStart.startsWith("export ")) line = trimmedStart.removePrefix("export ").trimStart()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (key.isEmpty()) continue
            var value = line.substring(eq + 1).trimStart()
            value = parseValue(value, lines, iHolder = intArrayOf(i)).also { i = it.second }
                .first
            result[key] = value
        }
        return result
    }

    /**
     * Parses a dotenv value supporting quotes and simple multiline quoted values.
     * Returns (value, nextLineIndex).
     */
    private fun parseValue(raw: String, lines: List<String>, iHolder: IntArray): Pair<String, Int> {
        var i = iHolder[0]
        if (raw.isEmpty()) return "" to i
        val first = raw[0]
        if (first == '\'' || first == '"') {
            val quote = first
            val sb = StringBuilder()
            var idx = 1
            var closed = false
            var line = raw
            while (true) {
                while (idx < line.length) {
                    val c = line[idx++]
                    if (c == quote) { closed = true; break }
                    if (quote == '"' && c == '\\' && idx < line.length) {
                        val n = line[idx++]
                        when (n) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            else -> { sb.append('\\'); sb.append(n) }
                        }
                    } else {
                        sb.append(c)
                    }
                }
                if (closed) break
                if (i >= lines.size) break
                sb.append('\n')
                line = lines[i++]
                idx = 0
            }
            return sb.toString() to i
        }
        // Unquoted: strip inline comment if preceded by whitespace
        var v = raw
        val hash = run {
            var inSpaceBefore = false
            for (idx in v.indices) {
                val c = v[idx]
                if (c == '#') {
                    if (idx == 0 || v[idx - 1].isWhitespace()) return@run idx
                }
            }
            -1
        }
        if (hash >= 0) v = v.substring(0, hash)
        v = v.trimEnd()
        // Remove surrounding quotes if any odd remnants
        if (v.length >= 2 && ((v.first() == '"' && v.last() == '"') || (v.first() == '\'' && v.last() == '\''))) {
            v = v.substring(1, v.length - 1)
        }
        return v to i
    }
}

data class FirebaseAppConfig(
    val apiKey: String,
    val authDomain: String,
    val projectId: String,
    val storageBucket: String,
    val messagingSenderId: String,
    val appId: String,
    val measurementId: String,
    val appSecret: String,
    val payloadKey: String,
) {
    val isConfigured: Boolean get() = projectId.isNotBlank() && apiKey.isNotBlank()

    companion object {
        fun load(): FirebaseAppConfig {
            fun g(name: String) = DesktopEnv.get(name).orEmpty()
            return FirebaseAppConfig(
                apiKey = g("FIREBASE_API_KEY"),
                authDomain = g("FIREBASE_AUTH_DOMAIN"),
                projectId = g("FIREBASE_PROJECT_ID"),
                storageBucket = g("FIREBASE_STORAGE_BUCKET"),
                messagingSenderId = g("FIREBASE_MESSAGING_SENDER_ID"),
                appId = g("FIREBASE_APP_ID"),
                measurementId = g("FIREBASE_MEASUREMENT_ID"),
                appSecret = g("APP_SECRET"),
                payloadKey = g("PAYLOAD_KEY"),
            )
        }

        fun missingRequired(config: FirebaseAppConfig): List<String> {
            val missing = mutableListOf<String>()
            if (config.projectId.isBlank()) missing += "FIREBASE_PROJECT_ID"
            if (config.apiKey.isBlank()) missing += "FIREBASE_API_KEY"
            return missing
        }

        fun statusMessage(config: FirebaseAppConfig): String? {
            val missing = missingRequired(config)
            return if (missing.isEmpty()) null
            else "Konfigurasi Firebase belum lengkap/terbaca: ${missing.joinToString(", ")}. Set environment variable atau file .env."
        }
    }
}
