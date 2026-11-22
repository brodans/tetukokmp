package id.tetuko.app.desktop

import id.tetuko.proxy.CaptchaOcr
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertTrue

class CaptchaOcrPackagingTest {
    @Test
    fun `bundled captcha model and charset load without working-directory files`() {
        assertTrue(CaptchaOcr.available(), "Bundled OCR model and charset must load from desktop resources.")
    }

    @Test
    fun `legacy admin layered hash verifies with Tauri format`() {
        val hash = LegacyAdminCredentialCodec.hashPinLayered("Admin!2026")

        assertTrue(hash.startsWith("sha256$"))
        assertTrue(LegacyAdminCredentialCodec.verifyPinLayered("Admin!2026", hash))
        assertFalse(LegacyAdminCredentialCodec.verifyPinLayered("wrong", hash))
    }

    @Test
    fun `legacy admin AES credential uses CryptoJS OpenSSL format`() {
        val encrypted = LegacyAdminCredentialCodec.encrypt("Admin!2026", "test-secret")

        assertTrue(encrypted.startsWith("U2FsdGVkX1"))
        assertFalse(encrypted.contains("Admin!2026"))
        assertEquals("Admin!2026", LegacyAdminCredentialCodec.decrypt(encrypted, "test-secret"))
    }

    @Test
    fun `CryptoJS OpenSSL fixture decrypts with the configured app secret`() {
        val fixture = "U2FsdGVkX18AAQIDBAUGB6FuN5jU8GBM7JNbvrtZ1sk="

        assertEquals("Legacy2026", LegacyAdminCredentialCodec.decrypt(fixture, "TETUKO-compat-secret"))
    }
}