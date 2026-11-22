@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package id.tetuko.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.ExperimentalResourceApi
import tetuko.composeapp.generated.resources.Res

/**
 * Lazy/on-demand decoder for bundled biodata.json (columnar: {k, n, s, d}).
 * Parses once and resolves only the requested NIP's row to limit overhead.
 */
class BiodataStore private constructor(
    private val keys: List<String>,
    private val nips: List<String>,
    private val strings: List<String>,
    private val rows: JsonArray,
    private val nipIndex: Map<String, Int>,
) {
    fun getByNip(nip: String): JsonObject? {
        if (nip.isBlank()) return null
        val idx = nipIndex[nip] ?: return null
        if (idx !in 0 until rows.size) return null
        val row = rows[idx] as? JsonArray ?: return null
        return decodeRow(row)
    }

    fun hasNip(nip: String): Boolean = nip.isNotBlank() && nipIndex.containsKey(nip)

    private fun decodeRow(row: JsonArray): JsonObject = buildJsonObject {
        for (j in keys.indices) {
            val raw = row.getOrNull(j) ?: continue
            if (raw is JsonNull) continue
            val resolved = resolveRefs(raw) ?: continue
            if (resolved is JsonNull) continue
            put(keys[j], resolved)
        }
    }

    private fun resolveRefs(v: JsonElement): JsonElement? {
        if (v is JsonNull) return null
        if (v is JsonArray) {
            val out = ArrayList<JsonElement>(v.size)
            for (item in v) {
                val r = resolveRefs(item)
                if (r != null) out.add(r) else out.add(JsonNull)
            }
            return JsonArray(out)
        }
        if (v is JsonObject) {
            // String table reference: {"$": number}
            val ref = (v["$"] as? JsonPrimitive)?.intOrNull
            if (ref != null && v.size == 1 && strings.isNotEmpty()) {
                val s = strings.getOrNull(ref) ?: return null
                return JsonPrimitive(s)
            }
            val out = LinkedHashMap<String, JsonElement>(v.size)
            for ((k, child) in v) {
                val r = resolveRefs(child)
                if (r != null) out.put(k, r)
            }
            return JsonObject(out)
        }
        // Primitive
        return v
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        @Volatile
        private var cached: BiodataStore? = null
        @Volatile
        private var loadFailed: Boolean = false

        suspend fun load(): BiodataStore? {
            cached?.let { return it }
            if (loadFailed) return null
            return try {
                val bytes = Res.readBytes("files/biodata.json")
                val text = bytes.decodeToString()
                val root = json.parseToJsonElement(text).jsonObject
                val keys = root["k"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val nips = root["n"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val strings = root["s"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val rows = root["d"]?.jsonArray ?: JsonArray(emptyList())
                val index = HashMap<String, Int>(nips.size * 2)
                for (i in nips.indices) index[nips[i]] = i
                val store = BiodataStore(keys, nips, strings, rows, index)
                cached = store
                store
            } catch (_: Throwable) {
                loadFailed = true
                null
            }
        }

        fun getCached(): BiodataStore? = cached
    }
}

fun JsonElement?.biodataText(): String {
    if (this == null || this is JsonNull) return ""
    return when (this) {
        is JsonPrimitive -> this.contentOrNull ?: this.toString().trim('"')
        is JsonObject, is JsonArray -> "" // complex fields rendered separately
    }
}

fun JsonElement?.biodataPrimitiveText(): String {
    if (this == null || this is JsonNull) return ""
    return when (this) {
        is JsonPrimitive -> this.contentOrNull ?: this.toString().trim('"')
        else -> this.toString()
    }
}
