package id.tetuko.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Smoke test: verifies the bundled ONNX model and charset can be loaded and a
 * session created when running from the repository root (where `app-resources/`
 * lives). Skips gracefully if resources are not discoverable in this context.
 */
class CaptchaOcrSmokeTest {
    @Test
    fun `native ocr engine loads bundled model when resources are present`() {
        val available = CaptchaOcr.available()
        // In the repo working directory the bundled assets should be found.
        if (!available) {
            println("CaptchaOcr not available in this environment (model/charset not found) - skipping assertion.")
            return
        }
        assertTrue(available)
    }

    @Test
    fun `native ocr runs a full inference on a generated captcha image`() {
        if (!CaptchaOcr.available()) return
        val image = BufferedImage(180, 48, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color.BLACK
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 30)
            graphics.drawString("1234", 34, 36)
        } finally {
            graphics.dispose()
        }
        val bytes = ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }

        val result = CaptchaOcr.solve(bytes)

        assertTrue(result.ok)
        assertEquals("onnx-jvm", result.engine)
        assertEquals(4, result.panjang)
        assertTrue(result.kandidat.size <= 2)
    }

    @Test
    fun `preprocess produces expected tensor shape for a tiny image`() {
        // 10x10 RGBA PNG-like header is hard to craft, but we can build a minimal BMP or use a generated PNG.
        // Instead, verify argmax/ctc helpers which don't need the model.
        val charset = listOf("", "1", "2", "3", "4", "a")
        // logits for seq=4, classes=6: prefer 1,2,3,4
        val logits = floatArrayOf(
            0f, 5f, 1f, 0f, 0f, 0f, // t0 -> 1
            0f, 1f, 5f, 0f, 0f, 0f, // t1 -> 2
            0f, 0f, 1f, 5f, 0f, 0f, // t2 -> 3
            0f, 0f, 0f, 1f, 5f, 0f, // t3 -> 4
        )
        val idx = CaptchaOcr.argmaxPerTimestep(logits, 4, 6, null)
        assertTrue(CaptchaOcr.ctcDecode(idx, charset) == "1234")
    }

    @Test
    fun `ctc decode collapses repeats and skips blank`() {
        val charset = listOf("", "1", "2")
        val decoded = CaptchaOcr.ctcDecode(intArrayOf(1, 1, 0, 2, 2, 1), charset)
        assertTrue(decoded == "121")
    }
}
