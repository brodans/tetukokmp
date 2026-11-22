package id.tetuko.core.data.web

import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Cookie jar scoped to one server profile/account, persisted through encrypted platform hooks. */
class ScopedPersistentCookiesStorage(
    private val reader: (String) -> String?,
    private val writer: (String, String) -> Boolean,
    private val deleter: (String) -> Boolean,
) : CookiesStorage {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var scope: String? = null
    private var delegate = AcceptAllCookiesStorage()
    private var cookies = mutableListOf<StoredCookie>()

    suspend fun selectScope(nextScope: String?) = lock.withLock {
        if (scope == nextScope) return@withLock
        delegate.close()
        delegate = AcceptAllCookiesStorage()
        scope = nextScope?.takeIf { it.isNotBlank() }
        cookies = mutableListOf()
        val selected = scope ?: return@withLock
        val raw = reader(selected)?.takeIf { it.isNotBlank() } ?: return@withLock
        val restored = runCatching { json.decodeFromString<List<StoredCookie>>(raw) }.getOrNull() ?: return@withLock
        cookies.addAll(restored.takeLast(MAX_COOKIES))
        cookies.forEach { stored ->
            runCatching { delegate.addCookie(Url(stored.originUrl), stored.cookie) }
        }
    }

    suspend fun clearScope() = lock.withLock {
        val selected = scope
        delegate.close()
        delegate = AcceptAllCookiesStorage()
        cookies.clear()
        if (!selected.isNullOrBlank()) deleter(selected)
    }

    override suspend fun get(requestUrl: Url): List<Cookie> = lock.withLock {
        delegate.get(requestUrl)
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) = lock.withLock {
        delegate.addCookie(requestUrl, cookie)
        val selected = scope ?: return@withLock
        val origin = requestUrl.toString()
        cookies.removeAll { existing ->
            existing.cookie.name == cookie.name &&
                existing.cookie.domain == cookie.domain &&
                existing.cookie.path == cookie.path &&
                existing.originUrl.substringBefore('/', missingDelimiterValue = existing.originUrl) == origin.substringBefore('/', missingDelimiterValue = origin)
        }
        cookies.add(StoredCookie(origin, cookie))
        val encoded = json.encodeToString(cookies.takeLast(MAX_COOKIES))
        check(writer(selected, encoded)) { "Gagal menyimpan sesi Web Presensi terenkripsi." }
    }

    override fun close() = Unit

    @Serializable
    private data class StoredCookie(val originUrl: String, val cookie: Cookie)

    private companion object {
        const val MAX_COOKIES = 100
    }
}