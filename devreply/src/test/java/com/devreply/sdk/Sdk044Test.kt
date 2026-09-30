package com.devreply.sdk

import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.devreply.sdk.ui.Palette
import com.devreply.sdk.ui.stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

// SDK 0.4.4 (shared spec): context with a new conversation, deleteUser that retries, the on/off switch,
// events, the dark theme.

class ContextTest {
    @Test fun valuesLikeAttributes() {
        val json = contextJson(mapOf("screen" to "export", "items" to 3, "ratio" to 0.5, "pro" to true, "whole" to 2.0))!!
        assertEquals("export", json.get("screen"))
        assertEquals(3L, json.get("items"))
        assertEquals(0.5, json.get("ratio"))
        assertEquals(true, json.get("pro"))
        assertEquals(2L, json.get("whole"))
    }

    @Test fun dropsWhatTheServerWouldRefuse() {
        val json = contextJson(
            mapOf(
                "ok key_1-2.3" to "yes",
                "" to "empty key",
                "x".repeat(41) to "long key",
                "émoji" to "not ascii",
                "slash/key" to "bad char",
                "nothing" to null,
                "list" to listOf(1),
                "nan" to Double.NaN,
                "inf" to Double.POSITIVE_INFINITY,
            ),
        )!!
        assertEquals(setOf("ok key_1-2.3"), json.keys().asSequence().toSet())
    }

    @Test fun textCutAt500() {
        val json = contextJson(mapOf("note" to "a".repeat(700)))!!
        assertEquals(500, (json.get("note") as String).length)
        // Never half an emoji.
        val emoji = contextJson(mapOf("note" to "a".repeat(499) + "😀"))!!
        assertEquals("a".repeat(499), emoji.get("note"))
    }

    @Test fun atMost20InOrder() {
        val many = (1..25).associate { "k$it" to it }
        val json = contextJson(many)!!
        assertEquals(20, json.length())
        assertTrue(json.has("k20"))
        assertFalse(json.has("k21"))
    }

    @Test fun nothingLeftIsNoContext() {
        assertNull(contextJson(emptyMap()))
        assertNull(contextJson(mapOf("bad/key" to 1, "gone" to null)))
    }
}

class PendingDeletionsTest {
    private var stored: String? = null
    private val pending = PendingDeletions(read = { stored }, write = { stored = it })

    @Test fun queueAddsRemovesAndEmpties() {
        assertEquals(emptyList<String>(), pending.all())
        pending.add("it_old1")
        pending.add("it_old2")
        pending.add("it_old1") // once only, now newest
        assertEquals(listOf("it_old2", "it_old1"), pending.all())
        pending.remove("it_old2")
        assertEquals(listOf("it_old1"), pending.all())
        pending.remove("it_old1")
        assertNull("the encrypted entry goes when the queue is empty", stored)
    }

    @Test fun keepsTheNewestTen() {
        (1..12).forEach { pending.add("it_$it") }
        assertEquals((3..12).map { "it_$it" }, pending.all())
    }

    @Test fun damagedEntryReadsEmpty() {
        stored = "not json"
        assertEquals(emptyList<String>(), pending.all())
        stored = """["it_a", 5, "", null, "it_b"]"""
        assertEquals(listOf("it_a", "it_b"), pending.all())
    }

    @Test fun whatEachAnswerMeans() {
        val done = PendingDeletions.Outcome.Done
        val retry = PendingDeletions.Outcome.Retry
        assertEquals(done, PendingDeletions.outcome(null))
        assertEquals("401: the token is gone already", done, PendingDeletions.outcome(DevReplyError.Unauthenticated))
        assertEquals("404: the user is gone already", done, PendingDeletions.outcome(DevReplyError.NotFound))
        assertEquals(retry, PendingDeletions.outcome(DevReplyError.Network))
        assertEquals("503", retry, PendingDeletions.outcome(DevReplyError.Unavailable))
        assertEquals(retry, PendingDeletions.outcome(DevReplyError.Server(500)))
        assertEquals(retry, PendingDeletions.outcome(DevReplyError.Server(502)))
        assertEquals(retry, PendingDeletions.outcome(DevReplyError.Server(429)))
        assertEquals(PendingDeletions.Outcome.Failed, PendingDeletions.outcome(DevReplyError.Invalid("bad")))
        assertEquals(PendingDeletions.Outcome.Failed, PendingDeletions.outcome(DevReplyError.Server(403)))
    }
}

class AvailabilityTest {
    @Test fun enabledFromConfig() {
        assertTrue("before any config: on", MessengerConfig.placeholder("App").enabled)
        assertTrue(MessengerConfig.parse(JSONObject("""{"enabled":true}"""), "App").enabled)
        assertFalse(MessengerConfig.parse(JSONObject("""{"enabled":false}"""), "App").enabled)
        assertTrue("an older server sends none: on", MessengerConfig.parse(JSONObject("""{"app_name":"A"}"""), "App").enabled)
        assertTrue("only false switches it off", MessengerConfig.parse(JSONObject("""{"enabled":"no"}"""), "App").enabled)
        assertTrue(MessengerConfig.parse(JSONObject("""{"enabled":null}"""), "App").enabled)
    }

    @Test fun notConfiguredIsNotAvailable() {
        assertFalse(DevReply.isAvailable)
    }
}

class EventsTest {
    @Test fun firstMessageStartsThenSends() {
        val events = EventHub.firstMessage("c1", DevReplyCategory.Bug)
        assertEquals(
            listOf(DevReplyEvent.ConversationStarted("c1", DevReplyCategory.Bug), DevReplyEvent.MessageSent("c1")),
            events,
        )
    }

    @Test fun everyListenerInOrderUntilCancelled() {
        val hub = EventHub()
        val a = mutableListOf<DevReplyEvent>()
        val b = mutableListOf<DevReplyEvent>()
        val subA = hub.add { a += it }
        hub.add { b += it }
        hub.deliver(DevReplyEvent.MessengerOpened)
        EventHub.firstMessage("c1", null).forEach(hub::deliver)
        subA.cancel()
        subA.cancel() // twice is fine
        hub.deliver(DevReplyEvent.MessengerClosed)
        assertEquals(
            listOf(DevReplyEvent.MessengerOpened, DevReplyEvent.ConversationStarted("c1", null), DevReplyEvent.MessageSent("c1")),
            a,
        )
        assertEquals(DevReplyEvent.MessengerClosed, b.last())
        assertEquals(4, b.size)
    }

    @Test fun sameFunctionTwiceIsTwoSubscriptions() {
        val hub = EventHub()
        var count = 0
        val listener: (DevReplyEvent) -> Unit = { count++ }
        val first = hub.add(listener)
        hub.add(listener)
        first.cancel()
        hub.deliver(DevReplyEvent.MessengerOpened)
        assertEquals(1, count)
    }
}

class ThemeTest {
    private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    @Test fun publicThemeIsTheSixColoursOf043() {
        val t = DevReplyTheme()
        assertEquals(Color(0xFFF6EB37), t.primary)
        assertEquals(Color(0xFFFF5FA2), t.accent)
        assertEquals(Color(0xFF2B50E0), t.userBubble)
        assertEquals(Color.White, t.userBubbleText)
        assertEquals(Color(0xFFFFFDF2), t.background)
        assertEquals(Color(0xFF111111), t.ink)
        // 0.4.3's positional constructor still works.
        assertEquals(t, DevReplyTheme(t.primary, t.accent, t.userBubble, t.userBubbleText, t.background, t.ink))
        assertEquals(6, DevReplyTheme::class.java.declaredFields.count { it.type == Long::class.javaPrimitiveType })
    }

    @Test fun lightIsExactly043() {
        val p = Palette.light(DevReplyTheme())
        assertFalse(p.dark)
        assertEquals("header = primary", p.primary, p.headerFill)
        assertEquals("card = primary", p.primary, p.cardFill)
        assertEquals(Color.White, p.surface)
        assertEquals(Color.White, p.teamBubbleFill)
        assertEquals(Color.White, p.noticeFill)
        assertEquals("outline = ink", p.ink, p.line)
        assertEquals("shadow = ink", p.ink, p.shadowColor)
        assertEquals("the Loud widths", 1f, p.outlineWidth)
        assertEquals(3.dp, p.stroke(3.dp))
        assertEquals(Color(0xFF4A4740), p.muted)
        assertEquals(Color(0xFFB32619), p.error)
        assertEquals("onPrimary = ink", p.ink, p.onHeaderColor)
        assertEquals(p.ink, p.onAccentColor)
        assertEquals(p.ink, p.onCardColor)
        assertEquals("small touches in primary", p.primary, p.brand)
        // An app's light colours follow the same rules.
        val blue = Palette.light(DevReplyTheme(primary = Color(0xFF0A84FF), ink = Color(0xFF222222)))
        assertEquals(Color(0xFF0A84FF), blue.headerFill)
        assertEquals(Color(0xFF0A84FF), blue.cardFill)
        assertEquals(Color(0xFF222222), blue.line)
    }

    @Test fun darkPresetIsDeepBlue() {
        val d = DevReplyTheme.Dark
        assertEquals(Color(0xFF0E1320), d.background)
        assertEquals(Color(0xFFEEF1F8), d.ink)
        assertEquals(Color(0xFF2B50E0), d.primary)
        assertEquals(Color(0xFFFF5FA2), d.accent)
        assertEquals(Color(0xFF3F6BFF), d.userBubble)
        assertEquals(Color.White, d.userBubbleText)
        val p = Palette.dark(d)
        assertTrue(p.dark)
        assertEquals("cobalt header", Color(0xFF2B50E0), p.headerFill)
        assertEquals("white text on cobalt", Color.White, p.onHeaderColor)
        assertEquals("dark text on pink", Color(0xFF111111), p.onAccentColor)
        // Cards a step above the page, toward mix(ink, primary) (variant C had #182033), muted text (≈ #9AA3B8).
        assertEquals("#181E30", hex(p.surface))
        assertEquals("#A0A3AC", hex(p.muted))
        assertEquals(p.surface, p.cardFill)
        assertEquals(p.surface, p.noticeFill)
        assertEquals(p.surface, p.teamBubbleFill)
        assertEquals(p.ink, p.onCardColor)
        assertEquals("thin light outlines", p.ink, p.line)
        assertEquals(0.5f, p.outlineWidth)
        assertEquals(1.5.dp, p.stroke(3.dp))
        assertEquals("shadow: the page toward black", "#06080D", hex(p.shadowColor))
        assertEquals(Color(0xFFFF8A7E), p.error)
        assertEquals("lemon for the small touches", Color(0xFFF6EB37), p.brand)
        assertEquals(Color(0xFF111111), p.onBrand)
    }

    @Test fun darkTextOnButtonsIsBlackOrWhiteWhicheverReads() {
        assertEquals(Color(0xFF111111), Palette.readableOn(Color(0xFFF6EB37)))
        assertEquals(Color(0xFF111111), Palette.readableOn(Color(0xFFFF5FA2)))
        assertEquals(Color.White, Palette.readableOn(Color(0xFF2B50E0)))
        assertEquals(Color.White, Palette.readableOn(Color(0xFF6A3FD8)))
        // An app's own dark colours: header = primary, lemon touches stay.
        val purple = Palette.dark(DevReplyTheme.Dark.copy(primary = Color(0xFFF6EB37)))
        assertEquals(Color(0xFF111111), purple.onHeaderColor)
        assertEquals(Color(0xFFF6EB37), purple.brand)
    }

    @Test fun darkOnlyWhenSet() {
        val saved = DevReply.darkTheme
        try {
            DevReply.darkTheme = null
            assertEquals("no dark theme: light in night mode too", Palette.light(DevReply.theme), DevReply.paletteFor(night = true))
            DevReply.darkTheme = DevReplyTheme.Dark
            assertEquals(Palette.dark(DevReplyTheme.Dark), DevReply.paletteFor(night = true))
            assertEquals(Palette.light(DevReply.theme), DevReply.paletteFor(night = false))
        } finally {
            DevReply.darkTheme = saved
        }
    }
}

class AskNameTest {
    @Test fun askNameFalseSkipsTheNameFormUntilTheMessengerCloses() {
        Messenger.startPresentation(null, emptyMap())
        assertTrue(Messenger.needsName) // no profile name yet

        Messenger.startPresentation(null, emptyMap(), askName = false)
        assertFalse(Messenger.needsName)
        // Still skipped after the first message: the composer stays for the follow-ups.
        Messenger.presentationConversationStarted()
        assertFalse(Messenger.needsName)

        Messenger.endPresentation()
        assertTrue(Messenger.needsName)
    }
}
