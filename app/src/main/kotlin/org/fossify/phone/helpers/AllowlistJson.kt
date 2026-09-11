package org.fossify.phone.helpers

import org.json.JSONArray
import org.json.JSONObject

data class AllowlistContactEntry(
    val e164: String,
    val label: String,
)

sealed class AllowlistContactParse {
    /** Esper has not delivered the key yet. Safe to retry. Must not wipe. */
    data object Missing : AllowlistContactParse()
    /** Present but unreadable. Must not wipe. Do not keep retrying forever. */
    data object Invalid : AllowlistContactParse()
    data class Apply(val entries: List<AllowlistContactEntry>) : AllowlistContactParse()
}

/**
 * Names for Contacts Provider. Enforcement stays in [CallAllowlist]
 * (`voice` / `allowlist_enabled`). This parser ignores those flags.
 */
object AllowlistJson {
    const val JSON_KEY = CallAllowlist.ALLOWLIST_JSON_KEY

    /**
     * Esper should put the array on [JSON_KEY]. Also accept any other string
     * value that looks like the allowlist blob, in case the DPC remaps keys.
     */
    fun rawFromValues(values: Map<String, String?>): String? {
        values[JSON_KEY]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        for ((_, value) in values) {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                return trimmed
            }
        }
        return null
    }

    fun parse(raw: String?): AllowlistContactParse {
        if (raw == null) return AllowlistContactParse.Missing
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return AllowlistContactParse.Missing
        return try {
            val array = entriesArray(trimmed) ?: return AllowlistContactParse.Invalid
            val seen = LinkedHashSet<String>()
            val entries = buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val e164 = obj.optString("e164").trim()
                    if (e164.isEmpty()) continue
                    val key = matchKey(e164)
                    if (key.isEmpty() || !seen.add(key)) continue
                    val label = obj.optString("label").trim().ifEmpty { e164 }
                    add(AllowlistContactEntry(e164 = e164, label = label))
                }
            }
            AllowlistContactParse.Apply(entries)
        } catch (_: Exception) {
            AllowlistContactParse.Invalid
        }
    }

    /**
     * Esper contract is a JSON array. Also accept the Mongo object
     * `{ enabled, updatedAt, entries }` if that whole blob is the string.
     */
    private fun entriesArray(trimmed: String): JSONArray? {
        if (trimmed.startsWith("[")) {
            return JSONArray(trimmed)
        }
        if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            val entries = obj.optJSONArray("entries") ?: return null
            return entries
        }
        return null
    }

    fun matchKey(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length >= 10) digits.takeLast(10) else digits
    }
}
