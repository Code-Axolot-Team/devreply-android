package com.devreply.sdk

import org.json.JSONArray
import java.util.Locale

/**
 * Which links the chat opens (spec 14, 0.6 wire formats): https, or one of the app's own deep-link schemes
 * (`link_schemes` in the config, e.g. `kneefit`). Anything else (other apps' schemes, `intent:`, `file:`,
 * `javascript:`, plain http) isn't opened by link buttons and gifts; Markdown links keep http and mailto as before.
 */
internal object LinkPolicy {
    /** Never the app's own scheme, whatever the config says: these reach other apps or the system. */
    private val NEVER = setOf(
        "http", "https", "mailto", "javascript", "vbscript", "data", "file", "content", "intent", "android-app",
        "about", "blob", "tel", "sms", "market",
    )

    /** The app's schemes from the cached or fetched config. */
    val appSchemes: List<String> get() = Messenger.config.linkSchemes

    /** `link_schemes` from the config: usable scheme names (RFC 3986), lowercase, without `:` or `://`. */
    fun schemes(json: JSONArray?): List<String> {
        if (json == null) return emptyList()
        return (0 until json.length()).mapNotNull { i ->
            (json.opt(i) as? String)?.trim()?.removeSuffix("://")?.removeSuffix(":")?.lowercase(Locale.ROOT)
                ?.takeIf { isSchemeName(it) && it !in NEVER }
        }.distinct()
    }

    private fun isSchemeName(s: String): Boolean =
        s.isNotEmpty() && s.length <= 40 && s[0] in 'a'..'z' && s.all { it in 'a'..'z' || it in '0'..'9' || it in "+-." }

    /** The scheme of [url], lowercase, or null. */
    fun scheme(url: String): String? {
        val colon = url.indexOf(':')
        if (colon <= 0) return null
        return url.substring(0, colon).lowercase(Locale.ROOT).takeIf(::isSchemeName)
    }

    /** A link button or a gift may open [url]: `https://<host>…`, or one of [schemes]. No spaces or control characters. */
    fun isAllowed(url: String, schemes: List<String> = appSchemes): Boolean {
        if (url.isEmpty() || url.any { it.isWhitespace() || it.isISOControl() }) return false
        val scheme = scheme(url) ?: return false
        if (scheme == "https") {
            val rest = url.substring("https:".length)
            return rest.startsWith("//") && rest.length > 2 && rest[2] != '/'
        }
        return scheme in schemes && scheme !in NEVER
    }

    /** What Markdown links may use: the usual http, https and mailto, and the app's schemes. */
    fun markdownPrefixes(schemes: List<String>): List<String> =
        listOf("http://", "https://", "mailto:") + schemes.filter { isSchemeName(it) && it !in NEVER }.map { "$it:" }
}
