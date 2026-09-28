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
