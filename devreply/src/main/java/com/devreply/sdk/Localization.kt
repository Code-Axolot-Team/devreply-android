package com.devreply.sdk

import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import android.os.LocaleList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.util.Locale

/**
 * The chat's language (spec 05, "Languages"): the app's choice ([DevReply.setLocale]) or the
 * device's languages in order, matched against the languages in [DevReplyStrings] the same way on
 * every platform (sdk/conformance/strings/locale-vectors.json). Nothing matches: English.
 */
internal object L10n {
    /** The app's choice, e.g. `es-MX`; null = the device's languages. Compose state: screens re-render. */
    var override by mutableStateOf<String?>(null)

    /** The table in use, e.g. `es`. */
    val language: String get() = resolve(override?.let { listOf(it) } ?: deviceTags())

    /** The tag the server gets: the app's choice, or the device's first language. */
    val tag: String get() = override ?: Locale.getDefault().toLanguageTag()

    /** For dates and times: the app's choice, or the device's. */
    val javaLocale: Locale get() = override?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()

    fun deviceTags(): List<String> {
        val tags = runCatching {
            val list: LocaleList? = LocaleList.getDefault()
            if (list == null) emptyList() else (0 until list.size()).mapNotNull { list[it]?.toLanguageTag() }
        }.getOrDefault(emptyList())
        return tags.ifEmpty { listOf(Locale.getDefault().toLanguageTag()) }
    }

    fun resolve(preferred: List<String>): String {
        for (raw in preferred) {
            val tag = raw.trim().replace('_', '-').lowercase(Locale.ROOT)
            if (tag.isEmpty()) continue
            DevReplyStrings.languages.firstOrNull { it.lowercase(Locale.ROOT) == tag }?.let { return it }
            val special = specialCase(tag)
            DevReplyStrings.languages.firstOrNull { it.lowercase(Locale.ROOT) == special }?.let { return it }
            val base = tag.substringBefore('-')
            DevReplyStrings.languages.firstOrNull { it.lowercase(Locale.ROOT) == base }?.let { return it }
        }
        return "en"
    }

    /**
     * The cases a plain language match gets wrong: Chinese by script, Portuguese by country, and the old
     * codes Java and Android still send (iw, in, no). Lowercase in and out; "" when none applies.
     */
    fun specialCase(tag: String): String {
        val parts = tag.split('-')
        val base = parts.first()
        val rest = parts.drop(1)
        val region = rest.firstOrNull { it.length == 2 || (it.length == 3 && it.all(Char::isDigit)) }
        return when (base) {
            "zh" -> when {
                "hant" in rest -> "zh-hant"
                "hans" in rest -> "zh-hans"
                region in setOf("tw", "hk", "mo") -> "zh-hant"
                else -> "zh-hans"
            }
            "pt" -> if (region == null || region == "br") "pt-br" else "pt-pt"
            "iw" -> "he"
            "in" -> "id"
            "no", "nn" -> "nb"
            else -> ""
        }
    }

    /** The chat's language is written right to left (Hebrew, Arabic): the chat lays out from the right. */
    val isRtl: Boolean get() = language in DevReplyStrings.rtl

    /** The text for [key] in the chat's language, with `{name}` placeholders filled in. */
    fun t(key: String, vararg args: Pair<String, Any?>): String {
        var s = DevReplyStrings.table(language)[key] ?: DevReplyStrings.table("en")[key] ?: key
        for ((name, value) in args) s = s.replace("{$name}", value?.toString() ?: "")
        return s
    }

    fun has(key: String): Boolean = DevReplyStrings.table("en").containsKey(key)

    /** "5 minutes ago", "yesterday"… in the chat's language. */
    fun relative(at: Instant, now: Instant = Instant.now()): String {
        val f = RelativeDateTimeFormatter.getInstance(ULocale.forLocale(javaLocale))
        val s = (now.epochSecond - at.epochSecond).coerceAtLeast(0)
        val last = RelativeDateTimeFormatter.Direction.LAST
        return when {
            s < 60 -> f.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
            s < 3600 -> f.format((s / 60).toDouble(), last, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
            s < 86_400 -> f.format((s / 3600).toDouble(), last, RelativeDateTimeFormatter.RelativeUnit.HOURS)
            else -> f.format((s / 86_400).toDouble(), last, RelativeDateTimeFormatter.RelativeUnit.DAYS)
        }
    }

    /** The server's "resolved" line, before 0.4 servers sent a key for it. */
    const val LEGACY_RESOLVED = "✓ Marked as resolved. Reply here any time to open it again."
}

internal fun t(key: String, vararg args: Pair<String, Any?>): String = L10n.t(key, *args)

// Texts the server sends: its defaults are shown in the user's language (config `localize`), texts
// the team wrote themselves as written.

internal val MessengerConfig.greetingText: String get() = if ("greeting" in localize) t("greeting") else greeting
internal val MessengerConfig.introText: String get() = if ("intro" in localize) t("intro") else intro

internal fun MessengerConfig.title(button: MessengerConfig.StartButton): String =
    if ("start_buttons" in localize) t("category.${button.category.wire}") else button.title

/** "Usually replies within 3 working days". */
internal val MessengerConfig.replyTimeText: String
    get() = replyWithinKey?.takeIf { L10n.has("reply_time.$it") }?.let { t("reply_time.$it") } ?: replyTime

/** "Please allow up to 3 working days for a reply." */
internal val MessengerConfig.replyAllowText: String
    get() = replyWithinKey?.takeIf { L10n.has("reply_allow.$it") }?.let { t("reply_allow.$it") }
        ?: "Please allow up to $replyWithin for a reply."
