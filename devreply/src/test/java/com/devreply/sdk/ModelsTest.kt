package com.devreply.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Same cases as sdk/swift/Tests (ModelsTests, ForwardCompatTests) until the shared conformance suite exists.

class BlockTest {
    private fun message(blocks: String) = Message.parse(
        JSONObject(
            """{"id":"01a0e892-b653-7191-9df9-c7e19fc68be7","author":"admin","is_internal_note":false,
               "created_at":"2026-09-28T15:12:04.177473Z","blocks":$blocks}""",
        ),
    )

    @Test fun textBlock() {
        val m = message("""[{"type":"text","text":"On it!"}]""")
        assertEquals(listOf(Block.Text("On it!")), m.blocks)
        assertEquals(Message.Author.Admin, m.author)
        assertFalse(m.isFromUser)
    }

    @Test fun unknownTypeFallsBackToText() {
        val m = message("""[{"type":"carousel","items":[1,2],"fallback":"Open the app to see this."}]""")
        assertEquals("Open the app to see this.", m.plainText)
    }

    @Test fun newerMinSdkFallsBack() {
        val m = message("""[{"type":"text","text":"new","min_sdk":"9.0.0","fallback":"Update the app"}]""")
        assertEquals(listOf(Block.Unsupported("Update the app")), m.blocks)
        val ok = message("""[{"type":"text","text":"old enough","min_sdk":"0.1.0"}]""")
        assertEquals("old enough", ok.plainText)
    }

    @Test fun versionCompare() {
        assertTrue(Block.isVersion("0.1.0", olderThan = "0.2"))
        assertTrue(Block.isVersion("0.9.9", olderThan = "1.0.0"))
        assertFalse(Block.isVersion("1.0.0", olderThan = "1.0"))
        assertFalse(Block.isVersion("1.2.0", olderThan = "1.1.9"))
    }

    @Test fun fileBlock() {
        val m = message(
            """[{"type":"file","attachment_id":"x","name":"log.txt","mime":"text/plain","size":120,
                "min_sdk":"0.3.0","fallback":"File: log.txt","url":"https://example.com/f"}]""",
        )
        assertEquals(listOf(Block.File("https://example.com/f", "log.txt", 120, "text/plain")), m.blocks)
    }

    @Test fun fileWithoutUrlShowsFallback() {
        val m = message("""[{"type":"file","name":"log.txt","fallback":"File: log.txt"}]""")
        assertEquals("File: log.txt", m.plainText)
    }

    @Test fun imageBlock() {
        val m = message("""[{"type":"image","url":"https://example.com/i.jpg","width":800,"height":600,"min_sdk":"0.2.0"}]""")
        assertEquals(listOf(Block.Image("https://example.com/i.jpg", 800, 600)), m.blocks)
    }
}

class DateTest {
    @Test fun microsecondsFromTheServer() {
        val d = parseRfc3339("2026-09-28T15:12:04.177473Z")!!
        assertEquals(1_790_608_324_177L, d.toEpochMilli())
    }

    @Test fun withoutFractionAndWithOffset() {
        assertNotNull(parseRfc3339("2026-09-28T15:12:04Z"))
        assertEquals(parseRfc3339("2026-09-28T15:12:04.5Z"), parseRfc3339("2026-09-28T17:12:04.5+02:00"))
        assertNull(parseRfc3339("nope"))
    }
}

class ConfigTest {
    @Test fun decodesServerConfig() {
        val c = MessengerConfig.parse(
            JSONObject(
                """{"app_name":"Fox Notes","team_name":"Fox Notes","greeting":"Hi there 👋","intro":"Ask us anything",
                   "reply_time":"Usually replies within a few hours",
                   "start_buttons":[{"category":"bug","emoji":"🐞","title":"Something's broken"}]}""",
            ),
            appName = "Host",
        )
        assertEquals("Fox Notes", c.teamName)
        assertEquals(DevReplyCategory.Bug, c.startButtons.first().category)
        assertEquals("older servers don't send it: the default", "3 working days", c.replyWithin)
    }

    @Test fun decodesReplyWithin() {
        val c = MessengerConfig.parse(
            JSONObject("""{"app_name":"Fox Notes","reply_within":"an hour","reply_time":"Usually replies within an hour"}"""),
            appName = "Host",
        )
        assertEquals("an hour", c.replyWithin)
        assertEquals("Usually replies within an hour", c.replyTime)
    }
}

class ForwardCompatTest {
    @Test fun conversationWithUnknownCategoryAndStatus() {
        val list = JSONArray(
            """[{"id":"01a0e892-b653-7191-9df9-c7e19fc68be7","status":"waiting_on_user","category":"feedback",
                "started_by":"agent","last_text":"Hi","last_author":"bot","unread":1,
                "created_at":"2026-09-28T15:12:04Z","last_message_at":"2026-09-28T15:12:04Z","brand_new_field":{"x":1}}]""",
        ).lossy(Conversation::parse)
        assertEquals(1, list.size)
        assertEquals(DevReplyCategory.Other, list[0].category)
    }

    @Test fun messageWithUnknownAuthor() {
        val m = Message.parse(
            JSONObject(
                """{"id":"01a0e892-b653-7191-9df9-c7e19fc68be7","author":"bot","is_internal_note":false,
                   "created_at":"2026-09-28T15:12:04Z","blocks":[{"type":"text","text":"hello"}]}""",
            ),
        )
        assertFalse(m.isFromUser)
        assertEquals("hello", m.plainText)
    }

    @Test fun configWithMissingFieldsAndUnknownButtons() {
        val c = MessengerConfig.parse(
            JSONObject(
                """{"app_name":"Fox Notes","start_buttons":[{"category":"feedback","emoji":"📣","title":"Feedback"},
                                                           {"category":"bug","emoji":"🐞","title":"Broken"}]}""",
            ),
            appName = "Host",
        )
        assertEquals("Fox Notes", c.teamName)
        assertTrue(c.greeting.isNotEmpty())
        assertEquals(2, c.startButtons.size)
    }

    @Test fun oneBrokenConversationDoesNotHideTheOthers() {
        val list = JSONArray(
            """[{"id":"not-a-uuid"},
                {"id":"01a0e892-b653-7191-9df9-c7e19fc68be7","status":"open","unread":0,"last_message_at":"2026-09-28T15:12:04Z"}]""",
        ).lossy(Conversation::parse)
        assertEquals(1, list.size)
    }
}

class AttributeTest {
    @Test fun valuesEncodeAsJsonValues() {
        val json = attributesJson(mapOf("plan" to "pro", "decks" to 12, "ratio" to 0.5, "trial" to false, "gone" to null, "whole" to 3.0))
        assertEquals("pro", json.get("plan"))
        assertEquals(12L, json.get("decks"))
        assertEquals(0.5, json.get("ratio"))
        assertEquals(false, json.get("trial"))
        assertTrue(json.isNull("gone") && json.has("gone"))
        assertEquals(3L, json.get("whole"))
    }
}

// SDK 0.4.0 (spec 05): personas, the app icon, team faces, and registration back-off.
class PersonaTest {
    private fun message(extra: String, author: String = "admin") = Message.parse(
        JSONObject(
            """{"id":"01a0e892-b653-7191-9df9-c7e19fc68be7","author":"$author","is_internal_note":false,
               "created_at":"2026-09-28T15:12:04.177473Z","blocks":[{"type":"text","text":"Hi"}]$extra}""",
        ),
    )

    @Test fun personaOnTeamMessages() {
        val m = message(""","persona":{"name":"Anna","title":"Support","avatar_url":"https://api.devreply.com/i/1"}""")
        assertEquals(Persona("Anna", "Support", "https://api.devreply.com/i/1"), m.persona)
    }

    @Test fun personaIsOptionalAndForgiving() {
        assertNull(message("").persona)
        assertNull(message(""","persona":null""").persona)
        assertNull("not an object", message(""","persona":"Anna"""").persona)
        assertNull("no name, no label", message(""","persona":{"title":"Support"}""").persona)
        assertEquals(Persona("Anna", "", null), message(""","persona":{"name":" Anna ","avatar_url":""}""").persona)
        assertNull("the user's own messages never get one", message(""","persona":{"name":"Anna"}""", author = "user").persona)
        // Newer fields inside persona don't matter.
        assertEquals("Anna", message(""","persona":{"name":"Anna","kind":"agent","x":[1]}""").persona?.name)
    }

    @Test fun configIconAndTeam() {
        val c = MessengerConfig.parse(
            JSONObject(
                """{"app_name":"Fox Notes","app_icon_url":"https://api.devreply.com/i/9",
                   "team":[{"name":"Anna","title":"Support","avatar_url":"https://x/a"},{"title":"no name"},
                           {"name":"Sergei","title":"","avatar_url":null},{"name":"C"},{"name":"D"}]}""",
            ),
            "Fallback",
        )
        assertEquals("https://api.devreply.com/i/9", c.appIconUrl)
        assertEquals("bad items skipped, 3 at most", listOf("Anna", "Sergei", "C"), c.team.map { it.name })
        val old = MessengerConfig.parse(JSONObject("""{"app_name":"Fox Notes"}"""), "Fallback")
        assertNull(old.appIconUrl)
        assertTrue(old.team.isEmpty())
        val odd = MessengerConfig.parse(JSONObject("""{"app_icon_url":5,"team":"nope"}"""), "Fallback")
        assertNull(odd.appIconUrl)
        assertTrue(odd.team.isEmpty())
    }

    @Test fun sdkVersionIs040() {
        assertEquals("0.4.3", DEVREPLY_SDK_VERSION)
    }
}

class RegistrationBackoffTest {
    @Test fun waitsLongerAfterEachRefusal() {
        var now = 0L
        val b = RegistrationBackoff { now }
        assertTrue(b.allowed())
        assertTrue("the first refusal is the one that logs", b.refused())
        assertFalse(b.allowed())
        now += 59_000
        assertFalse("not before a minute", b.allowed())
        now += 1_000
        assertTrue(b.allowed())
        assertFalse("logs only once", b.refused())
        now += 4 * 60_000
        assertFalse(b.allowed())
        now += 60_000
        assertTrue("then 5 minutes", b.allowed())
        b.refused()
        now += 30 * 60_000
        assertTrue("then 30 minutes", b.allowed())
        b.refused()
        now += 6 * 60 * 60_000 - 1
        assertFalse(b.allowed())
        now += 1
        assertTrue("then every 6 hours", b.allowed())
        b.refused()
        now += 6 * 60 * 60_000
        assertTrue("stays at 6 hours", b.allowed())
        b.succeeded()
        assertTrue(b.allowed())
    }
}
