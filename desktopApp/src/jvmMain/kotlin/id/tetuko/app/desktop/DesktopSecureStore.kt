package id.tetuko.app.desktop

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted-at-rest storage for desktop session data.
 *
 * Values are serialised to a properties blob and encrypted with AES-256/GCM. The
 * per-user random key lives in a separate file restricted to the owner (0600) and
 * never leaves `~/.tetuko/data`. This is not a hardware-backed keychain, but it
 * avoids storing API keys/session tokens as readable plaintext in java.util.prefs.
 *
 * All operations are best-effort: a missing/corrupt store simply behaves as empty
 * so the app can fall back to a normal login (recovery-friendly, never crashes).
 */
object DesktopSecureStore {
    private const val FILE = "session.bin"
    private const val KEY_FILE = "session.key"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val dir: Path = Path.of(System.getProperty("user.home"), ".tetuko", "data")

    fun load(): Map<String, String> = runCatching {
        val file = dir.resolve(FILE)
        if (!Files.exists(file)) return emptyMap()
        val raw = Base64.getDecoder().decode(Files.readAllBytes(file).decodeToString())
        if (raw.size <= IV_BYTES) return emptyMap()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, IV_BYTES)))
        val plain = cipher.doFinal(raw.copyOfRange(IV_BYTES, raw.size))
        val props = Properties().apply { load(plain.inputStream()) }
        props.stringPropertyNames().associateWith { props.getProperty(it) ?: "" }
    }.getOrElse { emptyMap() }

    fun save(values: Map<String, String>) {
        runCatching {
            Files.createDirectories(dir)
            val props = Properties().apply { values.forEach { (k, v) -> setProperty(k, v) } }
            val plain = ByteArrayOutputStream().use { out ->
                props.store(out, "TETUKO session")
                out.toByteArray()
            }
            val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            val encrypted = cipher.doFinal(plain)
            val file = dir.resolve(FILE)
            Files.write(file, Base64.getEncoder().encode(iv + encrypted))
            restrictToOwner(file)
        }
    }

    fun clear() {
        runCatching { Files.deleteIfExists(dir.resolve(FILE)) }
    }

    private fun secretKey(): SecretKeySpec {
        val file = dir.resolve(KEY_FILE)
        val bytes = if (Files.exists(file)) {
            Files.readAllBytes(file)
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }.also {
                Files.createDirectories(dir)
                Files.write(file, it)
                restrictToOwner(file)
            }
        }
        return SecretKeySpec(bytes, "AES")
    }

    private fun restrictToOwner(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}
