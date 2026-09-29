package com.devreply.sdk

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The chat's language, the same way on every platform (sdk/conformance/strings/locale-vectors.json). */
class LocalizationTest {
    @After fun reset() {
        L10n.override = null
    }

    private fun conformance(name: String): File {
        // Gradle runs unit tests from the module directory (sdk/android/devreply).
        val candidates = listOf("../../conformance/strings/$name", "../conformance/strings/$name", "sdk/conformance/strings/$name")
        return candidates.map(::File).first { it.exists() }
    }

    @Test fun everyConformanceCasePicksTheSameLanguage() {
        val cases = JSONObject(conformance("locale-vectors.json").readText()).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val preferred = c.getJSONArray("preferred").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals("case $preferred", c.getString("expect"), L10n.resolve(preferred))
        }
        assertTrue("at least a dozen cases", cases.length() >= 12)
    }

    @Test fun theAppsChoiceWinsAndFillsPlaceholders() {
        L10n.override = "es-MX"
        assertEquals("es", L10n.language)
        assertEquals("Inicia una conversación", t("start_title"))
        assertEquals("También te escribiremos a ana@example.com.", t("notice.email", "email" to "ana@example.com"))
        L10n.override = "de"
        assertEquals("Neue Antworten von Fox: 3", t("launcher.many", "team" to "Fox", "count" to 3))
        L10n.override = "xx"
        assertEquals("Start a conversation", t("start_title"))
    }

    @Test fun everyLanguageHasEveryText() {
        val keys = DevReplyStrings.table("en").keys
        assertEquals(15, DevReplyStrings.languages.size)
        for (lang in DevReplyStrings.languages) {
            val table = DevReplyStrings.table(lang)
            assertEquals("$lang keys", keys, table.keys)
            for (k in keys) assertTrue("$lang $k is empty", table.getValue(k).isNotBlank())
        }
    }

    @Test fun serverDefaultsAreTranslatedButTheTeamsOwnWordsAreNot() {
        L10n.override = "fr"
        val json = JSONObject(
            """{"app_name":"Fox","greeting":"Yo, founders here","intro":"Ask us anything, or tell us what's broken.",
               "reply_time":"Usually replies within an hour","reply_within":"an hour","reply_within_key":"hour",
               "localize":["intro","start_buttons","reply_time","reply_within"],
               "start_buttons":[{"category":"bug","emoji":"","title":"Something's broken"}]}""",
        )
        val c = MessengerConfig.parse(json, "Fox")
        assertEquals("Yo, founders here", c.greetingText)
        assertEquals("Posez-nous vos questions ou dites-nous ce qui ne va pas.", c.introText)
        assertEquals("Quelque chose ne marche pas", c.title(c.startButtons[0]))
        assertEquals("Répond généralement en moins d'une heure", c.replyTimeText)
        assertEquals("Comptez jusqu'à une heure pour une réponse.", c.replyAllowText)
        // A server before 0.4: no keys, no localize list: its English as it is.
        val old = MessengerConfig.parse(JSONObject("""{"greeting":"Hi there 👋","reply_time":"Usually replies within a day","reply_within":"a day"}"""), "Fox")
        assertEquals("Hi there 👋", old.greetingText)
        assertEquals("Usually replies within a day", old.replyTimeText)
        assertEquals("Please allow up to a day for a reply.", old.replyAllowText)
    }

    @Test fun theResolvedLineCarriesItsKey() {
        val b = Block.parse(JSONObject("""{"type":"text","text":"✓ Marked as resolved. Reply here any time to open it again.","key":"resolved"}"""))
        assertEquals("resolved", (b as Block.Text).key)
    }
}
