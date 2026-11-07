package app.encrogram.core.internal

import org.json.JSONArray
import org.json.JSONObject

/**
 * The string at [key], or null when absent or JSON null. Android's org.json returns the text "null" from
 * optString for a JSON null (the JVM library returns the fallback), so never use optString on nullable fields.
 */
internal fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)

/** Iterates a JSONArray of objects, skipping non-objects. */
internal inline fun JSONArray?.forEachObject(block: (JSONObject) -> Unit) {
    if (this == null) return
    for (i in 0 until length()) optJSONObject(i)?.let(block)
}

/** A deep copy through text (org.json has no clone). */
internal fun JSONObject.deepCopy(): JSONObject = JSONObject(toString())
