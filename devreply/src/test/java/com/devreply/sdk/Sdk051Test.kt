package com.devreply.sdk

import com.devreply.sdk.ui.downloadImage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

// SDK 0.5.1 (spec 14, "The chat"): the message box always appears, an unread reply comes first, hardening.

class ProfileRetryTest : MessengerHarness() {
    @Test fun retriesWithBackoffUntilTheProfileComes() {
        var failures = 3
        api.routes["GET /v1/me"] = { if (failures-- > 0) 500 to "{}" else 200 to """{"name":"Debra"}""" }
        val waits = mutableListOf<Long>()
        val shown = mutableListOf<DevReplyError?>()
        runBlocking {
            Messenger.loadProfile(wait = { ms ->
                waits += ms
                // What the conversation shows while it waits: the error (with Try again), not an empty bottom.
                shown += Messenger.profileError
            })
        }
        assertEquals(listOf(1_000L, 2_000L, 5_000L), waits)
        assertTrue(shown.all { it is DevReplyError.Server })
        assertEquals("Debra", Messenger.profile?.name)
        assertNull("loaded: no error", Messenger.profileError)
        assertEquals(4, api.count("GET /v1/me"))
    }

    @Test fun theWaitsLevelOffAt30Seconds() {
        var failures = 9
        api.routes["GET /v1/me"] = { if (failures-- > 0) 503 to "{}" else 200 to """{"name":"Debra"}""" }
        val waits = mutableListOf<Long>()
        runBlocking { Messenger.loadProfile(wait = { waits += it }) }
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L, 30_000L, 30_000L, 30_000L), waits)
        assertEquals("Debra", Messenger.profile?.name)
    }

    @Test fun loadedAlreadyMeansNoRequest() {
        runBlocking { Messenger.loadProfile(wait = { fail("no wait") }) }
        assertEquals(1, api.count("GET /v1/me"))
        runBlocking { Messenger.loadProfile(wait = { fail("no wait") }) }
        assertEquals(1, api.count("GET /v1/me"))
    }

    @Test fun anotherLoadEndsTheRetries() {
        // The profile arrives some other way (home's refresh) while the conversation waits to retry.
        api.routes["GET /v1/me"] = { 500 to "{}" }
        runBlocking {
            Messenger.loadProfile(wait = {
                api.routes["GET /v1/me"] = { 200 to """{"name":"Debra"}""" }
                Messenger.refresh()
            })
        }
        assertEquals("Debra", Messenger.profile?.name)
        assertNull(Messenger.profileError)
    }

    @Test fun tryAgainStartsOverAtOnce() {
        val before = Messenger.profileRetries
        Messenger.retryProfile()
        assertEquals(before + 1, Messenger.profileRetries)
        assertNull(Messenger.profileError)
    }

    @Test fun refreshKeepsTheListWhenOnlyTheProfileFails() {
        api.routes["GET /v1/me"] = { 500 to "{}" }
        api.routes["GET /v1/conversations"] = { 200 to "[${conversation("billing", "open", "admin", 1)}]" }
        runBlocking { Messenger.refresh() }
        assertEquals(1, Messenger.conversations.size)
        assertNull("the list loaded: home shows no error", Messenger.lastError)
        assertNull(Messenger.profile)
    }
}

private fun conversation(category: String?, status: String, lastAuthor: String, unread: Int, at: String = "2026-11-05T10:00:00Z", id: UUID = UUID.randomUUID()) =
    """{"id":"$id","status":"$status","category":${category?.let { "\"$it\"" } ?: "null"},"last_text":"Refunded!",
        "last_author":"$lastAuthor","unread":$unread,"last_message_at":"$at"}"""

private fun parse(vararg json: String) = json.map { Conversation.parse(JSONObject(it)) }

class HomeChoicesTest {
    @Test fun theNewestUnreadTeamReplyComesFirst() {
        val older = UUID.randomUUID()
        val newer = UUID.randomUUID()
        val list = parse(
            conversation("bug", "open", "admin", 1, "2026-11-04T10:00:00Z", older),
            conversation("billing", "open", "agent", 2, "2026-11-05T10:00:00Z", newer),
            conversation("idea", "open", "user", 0, "2026-11-06T10:00:00Z"),
        )
        assertEquals(newer, list.newestUnreadReply()?.id)
    }

    @Test fun nothingUnreadNoCard() {
        assertNull(parse(conversation("bug", "open", "admin", 0)).newestUnreadReply())
        // The user wrote last: they've seen the reply.
        assertNull(parse(conversation("bug", "open", "user", 1)).newestUnreadReply())
        // "Marked as resolved" isn't a reply to read.
        assertNull(parse(conversation("bug", "closed", "system", 1)).newestUnreadReply())
        assertNull(emptyList<Conversation>().newestUnreadReply())
    }

    @Test fun aTileContinuesTheTopicsOpenConversation() {
        val open = UUID.randomUUID()
        val list = parse(
            conversation("billing", "closed", "admin", 0, "2026-11-06T10:00:00Z"),
            conversation("billing", "open", "admin", 1, "2026-11-05T10:00:00Z", open),
            conversation("billing", "open", "user", 0, "2026-11-01T10:00:00Z"),
            conversation("bug", "open", "user", 0, "2026-11-07T10:00:00Z"),
        )
        assertEquals("the newest open one, not the resolved one", open, list.openIn(DevReplyCategory.Billing)?.id)
        assertNull("no open conversation in the topic: a new one", list.openIn(DevReplyCategory.Idea))
        assertNull(parse(conversation("question", "closed", "admin", 0)).openIn(DevReplyCategory.Question))
    }

    @Test fun noTopicCountsAsOther() {
        val id = UUID.randomUUID()
        val list = parse(conversation(null, "open", "user", 0, id = id))
        assertEquals(id, list.openIn(DevReplyCategory.Other)?.id)
        assertNull(list.openIn(DevReplyCategory.Bug))
    }

    @Test fun theTextsAreThere() {
        L10n.override = "en"
        try {
            assertEquals("New reply from KneeFit", t("home.new_reply", "team" to "KneeFit"))
            assertEquals("Start a new conversation", t("home.new_conversation"))
            L10n.override = "de"
            assertTrue(t("home.new_reply", "team" to "KneeFit").contains("KneeFit"))
            assertTrue(t("home.new_reply", "team" to "KneeFit") != "New reply from KneeFit")
        } finally {
            L10n.override = null
        }
    }
}

/**
 * Servers that hang, on a real local socket: one that never answers, one that trickles a byte at a time
 * (no single read ever times out). Both fail as Network by the deadline, like a refused connection.
 */
class HangingServerTest {
    private val server = ServerSocket(0)
    private val url = "http://127.0.0.1:${server.localPort}"

    private fun serve(trickle: Boolean) = thread(isDaemon = true) {
        runCatching {
            while (true) {
                val socket = server.accept()
                thread(isDaemon = true) {
                    runCatching {
                        socket.getInputStream().read(ByteArray(4096))
                        if (trickle) {
                            val out = socket.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100000\r\n\r\n".toByteArray())
                            repeat(1_000) {
                                out.write(' '.code)
                                out.flush()
                                Thread.sleep(100)
                            }
                        } else {
                            Thread.sleep(60_000)
                        }
                    }
                    runCatching { socket.close() }
                }
            }
        }
    }

    @After fun close() = server.close()

    private fun failsByTheDeadline(trickle: Boolean) {
        serve(trickle)
        val started = System.nanoTime()
        try {
            runBlocking { ApiClient(url, deadlineMs = 1_500).config("it_x") }
            fail("expected a network error")
        } catch (e: DevReplyError.Network) {
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("gave up after $ms ms", ms in 1_400..5_000)
    }

    @Test fun silentServer() = failsByTheDeadline(trickle = false)

    @Test fun tricklingServer() = failsByTheDeadline(trickle = true)

    @Test fun anImageThatNeverComesTimesOut() {
        serve(trickle = false)
        val started = System.nanoTime()
        try {
            downloadImage("$url/photo.jpg", connectTimeoutMs = 1_000, readTimeoutMs = 1_000)
            fail("expected a timeout")
        } catch (e: java.io.IOException) {
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }
}
