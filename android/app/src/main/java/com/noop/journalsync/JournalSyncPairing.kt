package com.noop.journalsync

/**
 * Pure parser for the clipboard "paste configuration" payload (spec zhoop-journal-sync-SPEC.md §10).
 *
 * Accepts exactly one flat JSON object of the shape
 * `{"url": "...", "token": "...", "cf_id": "...", "cf_secret": "..."}` (extra keys ignored,
 * missing token/url is an error, malformed input is an error — never a crash, never logged).
 * Hand-rolled scanner on purpose: the JVM unit tests must not depend on org.json, and the
 * parser must be trivially auditable for the no-secrets-in-logs guarantee.
 */
object JournalSyncPairing {
    data class Config(val url: String, val token: String, val cfId: String?, val cfSecret: String?)

    /**
     * Accepts only flat JSON objects whose string values are simple (no escapes required by the
     * pairing format: URLs, tokens and ids contain no quotes/backslashes/control chars). Anything
     * else — arrays, nesting, escapes, trailing junk — returns null.
     */
    fun parse(raw: String): Config? {
        var i = skipWs(raw, 0)
        if (i >= raw.length || raw[i] != '{') return null
        i = skipWs(raw, i + 1)
        var url: String? = null
        var token: String? = null
        var cfId: String? = null
        var cfSecret: String? = null
        var sawAny = false
        if (i < raw.length && raw[i] == '}') {
            i++
        } else {
            while (true) {
                val key = readString(raw, i) ?: return null
                i = skipWs(raw, key.second)
                if (i >= raw.length || raw[i] != ':') return null
                i = skipWs(raw, i + 1)
                val value = readString(raw, i) ?: return null
                i = skipWs(raw, value.second)
                sawAny = true
                when (key.first) {
                    "url" -> url = value.first
                    "token" -> token = value.first
                    "cf_id" -> cfId = value.first
                    "cf_secret" -> cfSecret = value.first
                }
                if (i < raw.length && raw[i] == ',') {
                    i = skipWs(raw, i + 1)
                } else break
            }
            if (i >= raw.length || raw[i] != '}') return null
            i++
        }
        if (skipWs(raw, i) != raw.length || !sawAny) return null
        val u = url?.trim().orEmpty()
        val t = token?.trim().orEmpty()
        if (u.isEmpty() || t.isEmpty()) return null
        return Config(url = u, token = t, cfId = cfId?.trim()?.takeIf { it.isNotEmpty() }, cfSecret = cfSecret?.trim()?.takeIf { it.isNotEmpty() })
    }

    private fun skipWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    private fun readString(s: String, from: Int): Pair<String, Int>? {
        if (from >= s.length || s[from] != '"') return null
        val sb = StringBuilder()
        var i = from + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> return sb.toString() to (i + 1)
                c == '\\' || c < ' ' -> return null // no escapes/control chars in this format
                else -> sb.append(c)
            }
            i++
        }
        return null
    }
}
