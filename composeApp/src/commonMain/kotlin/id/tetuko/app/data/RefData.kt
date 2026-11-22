@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package id.tetuko.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.ExperimentalResourceApi
import tetuko.composeapp.generated.resources.Res

object RefDataLoader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun loadTable(path: String): RefTable {
        val bytes = Res.readBytes(path)
        val text = bytes.decodeToString()
        val root = json.parseToJsonElement(text).jsonObject
        val keys = root["k"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val rows = root["d"]?.jsonArray ?: JsonArray(emptyList())
        val strings = root["s"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        return RefTable(keys, rows, strings)
    }
}

class RefTable(
    val keys: List<String>,
    val rows: JsonArray,
    private val strings: List<String> = emptyList(),
) {
    val size: Int get() = rows.size

    fun rowObject(index: Int): Map<String, String> {
        val row = rows[index].jsonArray
        val map = LinkedHashMap<String, String>(keys.size)
        for (i in keys.indices) {
            map[keys[i]] = cellToString(row.getOrNull(i))
        }
        return map
    }

    fun cell(index: Int, key: String): String {
        val i = keys.indexOf(key)
        if (i < 0) return ""
        return cellToString(rows[index].jsonArray.getOrNull(i))
    }

    fun search(query: String, searchKeys: List<String>, limit: Int = 200): List<Map<String, String>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            val take = minOf(limit, rows.size)
            return (0 until take).map { rowObject(it) }
        }
        val out = ArrayList<Map<String, String>>(minOf(limit, 64))
        val idxs = searchKeys.mapNotNull { k -> keys.indexOf(k).takeIf { it >= 0 } }
        for (i in 0 until rows.size) {
            val row = rows[i].jsonArray
            var hit = false
            for (ki in idxs) {
                val v = cellToString(row.getOrNull(ki)).lowercase()
                if (v.contains(q)) { hit = true; break }
            }
            if (hit) {
                out.add(rowObject(i))
                if (out.size >= limit) break
            }
        }
        return out
    }

    /**
     * Search and return matching row indices (useful for large tables where
     * callers want to render lazily without building all maps up front).
     */
    fun searchIndices(query: String, searchKeys: List<String>, limit: Int = 200): List<Int> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            val take = minOf(limit, rows.size)
            return (0 until take).toList()
        }
        val out = ArrayList<Int>(minOf(limit, 64))
        val idxs = searchKeys.mapNotNull { k -> keys.indexOf(k).takeIf { it >= 0 } }
        for (i in 0 until rows.size) {
            val row = rows[i].jsonArray
            var hit = false
            for (ki in idxs) {
                val v = cellToString(row.getOrNull(ki)).lowercase()
                if (v.contains(q)) { hit = true; break }
            }
            if (hit) {
                out.add(i)
                if (out.size >= limit) break
            }
        }
        return out
    }

    private fun resolve(el: JsonElement?): JsonElement? {
        if (el == null || el is JsonNull) return null
        if (el is JsonObject) {
            val ref = (el["$"] as? JsonPrimitive)?.intOrNull
            if (ref != null && strings.isNotEmpty()) {
                val s = strings.getOrNull(ref) ?: return null
                return JsonPrimitive(s)
            }
        }
        return el
    }

    private fun cellToString(el: JsonElement?): String {
        val r = resolve(el) ?: return ""
        return when (r) {
            is JsonNull -> ""
            is JsonPrimitive -> r.contentOrNull ?: r.toString().trim('"')
            is JsonObject, is JsonArray -> r.toString()
        }
    }
}
