package id.tetuko.proxy

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Native JVM OCR for BKD e-Presensi captchas, ported from the Rust engine in
 * `src-tauri/src/captcha_ocr.rs` (same ONNX model + charset).
 *
 * Preprocess: resize to height 64 keeping aspect ratio (bilinear), grayscale,
 * normalize to [0, 1] (`pixel / 255`), tensor shape (1, 1, H, W).
 * Decode: CTC greedy — skip blank (index 0) and consecutive duplicates; prefer
 * a 4-digit result, with a digit-restricted argmax candidate.
 */
object CaptchaOcr {
    private const val CAPTCHA_LEN = 4
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var engine: Engine? = null
    private val lock = Any()

    fun available(): Boolean {
        if (System.getenv("TETUKO_OCR_DISABLE_NATIVE") == "1" || System.getProperty("tetuko.ocr.native.disabled") == "1") return false
        return runCatching { ensureEngine() }.isSuccess
    }

    fun solve(imageBytes: ByteArray): OcrResult {
        val eng = ensureEngine()
        val (h, w, data) = preprocess(imageBytes)
        val env = OrtEnvironment.getEnvironment()
        val inputName = eng.session.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 1, h.toLong(), w.toLong())).use { tensor ->
            eng.session.run(mapOf(inputName to tensor)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val raw = results[0].value
                val (seq, classes, flat) = flattenOutput(raw)
                val digitIdx = argmaxPerTimestep(flat, seq, classes, eng.digitAllowed)
                val freeIdx = argmaxPerTimestep(flat, seq, classes, null)
                val textDigit = ctcDecode(digitIdx, eng.charset)
                val textFree = ctcDecode(freeIdx, eng.charset)
                val candidates = linkedSetOf<String>().apply {
                    if (textDigit.isNotEmpty()) add(textDigit)
                    if (textFree.isNotEmpty()) add(textFree)
                }.toList()
                val main = candidates.firstOrNull { it.length == CAPTCHA_LEN && it.all(Char::isDigit) }
                    ?: candidates.firstOrNull { it.length == CAPTCHA_LEN }
                    ?: candidates.firstOrNull().orEmpty()
                val yakin = main.length == CAPTCHA_LEN && main.all(Char::isDigit)
                return OcrResult(ok = true, text = main, kandidat = candidates, yakin = yakin, panjang = CAPTCHA_LEN)
            }
        }
    }

    private fun ensureEngine(): Engine {
        engine?.let { return it }
        synchronized(lock) {
            engine?.let { return it }
            val charsetBytes = loadAsset("TETUKO_OCR_CHARSET", "app-resources/ocr/ocr_charset.json")
                ?: error("Charset OCR tidak ditemukan (ocr_charset.json).")
            val modelBytes = loadAsset("TETUKO_OCR_MODEL", "app-resources/ocr/ocr_model.onnx")
                ?: error("Model OCR tidak ditemukan (ocr_model.onnx).")
            val charset = loadCharset(charsetBytes.decodeToString())
            val digitAllowed = buildList {
                charset.forEachIndexed { i, c ->
                    if (i != 0 && c.length == 1 && c[0].isDigit()) add(i)
                }
                if (0 !in this) add(0)
            }.toIntArray()
            val env = runCatching { OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR) }
                .getOrElse { OrtEnvironment.getEnvironment() }
            val opts = OrtSession.SessionOptions().apply {
                runCatching { setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR) }
                runCatching { setSessionLogVerbosityLevel(0) }
            }
            val session = env.createSession(modelBytes, opts)
            return Engine(session, charset, digitAllowed).also { engine = it }
        }
    }

    private fun loadAsset(envKey: String, relative: String): ByteArray? {
        val fromEnv = System.getenv(envKey)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        if (fromEnv != null && Files.isRegularFile(fromEnv)) return Files.readAllBytes(fromEnv)
        val cwd = Path.of("").toAbsolutePath()
        val candidates = listOf(
            cwd.resolve(relative),
            cwd.resolve("../$relative").normalize(),
            Path.of(System.getProperty("user.home"), ".tetuko", relative.substringAfterLast('/')),
        )
        candidates.firstOrNull { Files.isRegularFile(it) }?.let { return Files.readAllBytes(it) }

        val classLoader = CaptchaOcr::class.java.classLoader
        val resourcePaths = listOf(relative.removePrefix("app-resources/"), relative)
        for (resourcePath in resourcePaths) {
            classLoader.getResourceAsStream(resourcePath)?.use { return it.readBytes() }
        }
        return null
    }

    private fun loadCharset(text: String): List<String> {
        val arr = json.parseToJsonElement(text) as JsonArray
        return arr.map { it.jsonPrimitive.content }
    }

    /** Returns (height, width, data) for a (1,1,H,W) float tensor, normalized to [0,1]. */
    internal fun preprocess(imageBytes: ByteArray): Triple<Int, Int, FloatArray> {
        val src = ImageIO.read(ByteArrayInputStream(imageBytes))
            ?: error("Gambar captcha tidak dapat dibaca.")
        val targetH = 64
        val targetW = max(1, (src.width.toFloat() * (targetH.toFloat() / max(1, src.height).toFloat())).roundToInt())
        val scaled = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, targetW, targetH, null)
        g.dispose()
        val data = FloatArray(targetH * targetW)
        var idx = 0
        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                val rgb = scaled.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val gr = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                // Luma (Rec. 601) — close to Rust `to_luma8()` for this captcha style.
                val luma = (0.299f * r + 0.587f * gr + 0.114f * b)
                data[idx++] = luma / 255.0f
            }
        }
        return Triple(targetH, targetW, data)
    }

    private fun flattenOutput(raw: Any): Triple<Int, Int, FloatArray> = when (raw) {
        is Array<*> -> {
            // Common shapes: [1, seq, classes] or [seq, 1, classes]
            val a = raw as Array<*>
            when (val first = a.firstOrNull()) {
                is Array<*> -> when (val second = first.firstOrNull()) {
                    is FloatArray -> { // [1][seq][classes]
                        val seq = first.size
                        val classes = second.size
                        Triple(seq, classes, flatten2D(first))
                    }
                    is Array<*> -> { // [seq][1][classes]
                        val seq = a.size
                        val classes = (second.firstOrNull() as? FloatArray)?.size ?: 0
                        val flat = FloatArray(seq * classes)
                        var p = 0
                        for (s in 0 until seq) {
                            val inner = (a[s] as Array<*>)[0] as FloatArray
                            for (v in inner) flat[p++] = v
                        }
                        Triple(seq, classes, flat)
                    }
                    else -> error("Bentuk output OCR tidak didukung.")
                }
                is FloatArray -> { // [seq][classes]
                    val seq = a.size
                    val classes = first.size
                    Triple(seq, classes, flatten2D(a))
                }
                else -> error("Bentuk output OCR tidak didukung.")
            }
        }
        is FloatArray -> error("Bentuk output OCR tidak didukung (1D).")
        else -> error("Tipe output OCR tidak didukung: ${raw::class}")
    }

    private fun flatten2D(rows: Array<*>): FloatArray {
        val seq = rows.size
        val classes = (rows[0] as FloatArray).size
        val flat = FloatArray(seq * classes)
        var p = 0
        for (i in 0 until seq) {
            val row = rows[i] as FloatArray
            for (v in row) flat[p++] = v
        }
        return flat
    }

    internal fun argmaxPerTimestep(
        logits: FloatArray,
        seq: Int,
        classes: Int,
        allowed: IntArray?,
    ): IntArray {
        val out = IntArray(seq)
        for (t in 0 until seq) {
            val base = t * classes
            var bestI = 0
            var bestV = Float.NEGATIVE_INFINITY
            if (allowed != null) {
                for (i in allowed) {
                    if (i in 0 until classes) {
                        val v = logits[base + i]
                        if (v > bestV) { bestV = v; bestI = i }
                    }
                }
            } else {
                for (i in 0 until classes) {
                    val v = logits[base + i]
                    if (v > bestV) { bestV = v; bestI = i }
                }
            }
            out[t] = bestI
        }
        return out
    }

    internal fun ctcDecode(indices: IntArray, charset: List<String>): String = buildString {
        var prev = -1
        for (i in indices) {
            if (i != prev && i != 0) {
                charset.getOrNull(i)?.let { append(it) }
            }
            prev = i
        }
    }

    private data class Engine(
        val session: OrtSession,
        val charset: List<String>,
        val digitAllowed: IntArray,
    )
}

data class OcrResult(
    val ok: Boolean,
    val text: String = "",
    val kandidat: List<String> = emptyList(),
    val yakin: Boolean = false,
    val panjang: Int = 4,
    val engine: String = "onnx-jvm",
    val error: String? = null,
)
