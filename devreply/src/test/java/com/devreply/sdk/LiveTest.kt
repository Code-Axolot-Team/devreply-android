package com.devreply.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Socket
import java.util.UUID

// Live updates over WebSocket (spec 05, 0.5.0): the RFC 6455 client, the reconnect rules, and what a
// message event does to the chat.

/** A server frame (never masked). */
private fun serverFrame(opcode: Int, payload: ByteArray, fin: Boolean = true): ByteArray {
    val out = ByteArrayOutputStream()
    out.write((if (fin) 0x80 else 0) or opcode)
    val n = payload.size
    when {
        n <= 125 -> out.write(n)
        n <= 0xFFFF -> { out.write(126); out.write(n ushr 8); out.write(n and 0xFF) }
        else -> { out.write(127); for (s in 56 downTo 0 step 8) out.write((n.toLong() ushr s and 0xFF).toInt()) }
    }
    out.write(payload)
    return out.toByteArray()
}

/** Reads one client frame from [bytes] at [offset]: checks it's masked and returns (opcode, unmasked payload, next offset). */
private fun clientFrame(bytes: ByteArray, offset: Int = 0): Triple<Int, ByteArray, Int> {
    var i = offset
    val b0 = bytes[i++].toInt() and 0xFF
    val b1 = bytes[i++].toInt() and 0xFF
    assertTrue("client frames are masked", b1 and 0x80 != 0)
    var len = (b1 and 0x7F).toLong()
    if (len == 126L) { len = ((bytes[i].toLong() and 0xFF) shl 8) or (bytes[i + 1].toLong() and 0xFF); i += 2 }
    else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or (bytes[i++].toLong() and 0xFF) } }
    val mask = bytes.copyOfRange(i, i + 4); i += 4
    val payload = ByteArray(len.toInt()) { k -> (bytes[i + k].toInt() xor mask[k and 3].toInt()).toByte() }
    return Triple(b0 and 0x0F, payload, i + len.toInt())
}

class WebSocketFramingTest {
    private val mask = byteArrayOf(0x37, 0xFA.toByte(), 0x21, 0x3D)

    @Test fun rfcExampleMaskedHello() {
        // RFC 6455 §5.7: a single-frame masked text message "Hello".
        val frame = WsFrame.encode(WsFrame.TEXT, "Hello".toByteArray(), mask)
        assertArrayEquals(
            byteArrayOf(0x81.toByte(), 0x85.toByte(), 0x37, 0xFA.toByte(), 0x21, 0x3D, 0x7F, 0x9F.toByte(), 0x4D, 0x51, 0x58),
            frame,
        )
    }

    @Test fun lengthsUseTheRightEncoding() {
        for ((size, headerLen, marker) in listOf(Triple(125, 2, 125), Triple(126, 4, 126), Triple(65535, 4, 126), Triple(65536, 10, 127))) {
            val payload = ByteArray(size) { (it % 251).toByte() }
            val frame = WsFrame.encode(WsFrame.TEXT, payload, mask)
            assertEquals("size $size", headerLen + 4 + size, frame.size)
            assertEquals(0x80 or marker, frame[1].toInt() and 0xFF)
            val (op, unmasked, end) = clientFrame(frame)
            assertEquals(WsFrame.TEXT, op)
            assertArrayEquals(payload, unmasked)
            assertEquals(frame.size, end)
            // The bytes on the wire aren't the payload (masked).
            assertTrue(!frame.copyOfRange(headerLen + 4, headerLen + 4 + 8).contentEquals(payload.copyOfRange(0, 8)))
        }
    }

    @Test fun readsServerFramesOfEveryLength() {
        for (size in listOf(0, 125, 126, 65535, 65536)) {
            val payload = ByteArray(size) { (it % 7).toByte() }
            val frame = WsFrame.read(ByteArrayInputStream(serverFrame(WsFrame.TEXT, payload)), 1 shl 20)
            assertEquals(WsFrame.TEXT, frame.opcode)
            assertTrue(frame.fin)
            assertArrayEquals(payload, frame.payload)
        }
    }

    @Test fun refusesMaskedServerFramesAndOversizedControlFrames() {
        val masked = WsFrame.encode(WsFrame.TEXT, "x".toByteArray(), mask)
        assertThrows { WsFrame.read(ByteArrayInputStream(masked), 1000) }
        assertThrows { WsFrame.read(ByteArrayInputStream(serverFrame(WsFrame.PING, ByteArray(126))), 1000) }
        assertThrows { WsFrame.read(ByteArrayInputStream(serverFrame(WsFrame.TEXT, ByteArray(2000))), 1000) }
        // Cut short.
        assertThrows { WsFrame.read(ByteArrayInputStream(serverFrame(WsFrame.TEXT, ByteArray(10)).copyOf(6)), 1000) }
    }

    /** A client over in-memory streams: [incoming] is what the server sends; returns the client and what it wrote. */
    private fun client(vararg incoming: ByteArray): Pair<WebSocketClient, ByteArrayOutputStream> {
        val out = ByteArrayOutputStream()
        val input = ByteArrayInputStream(incoming.fold(ByteArray(0)) { a, b -> a + b })
        return WebSocketClient.over(Socket(), input, out) to out
    }

    @Test fun answersPingWithPongCarryingItsPayload() {
        val (ws, out) = client(serverFrame(WsFrame.PING, "hb".toByteArray()), serverFrame(WsFrame.TEXT, "{\"type\":\"ready\"}".toByteArray()))
        assertEquals("{\"type\":\"ready\"}", ws.receive())
        val (op, payload, _) = clientFrame(out.toByteArray())
        assertEquals(WsFrame.PONG, op)
        assertArrayEquals("hb".toByteArray(), payload)
    }

    @Test fun putsFragmentedTextTogetherAcrossAPing() {
        val (ws, out) = client(
            serverFrame(WsFrame.TEXT, "{\"type\":".toByteArray(), fin = false),
            serverFrame(WsFrame.CONTINUATION, "\"mess".toByteArray(), fin = false),
            serverFrame(WsFrame.PING, ByteArray(0)),
            serverFrame(WsFrame.CONTINUATION, "age\", \"é\":1}".toByteArray()),
        )
        assertEquals("{\"type\":\"message\", \"é\":1}", ws.receive())
        assertEquals(WsFrame.PONG, clientFrame(out.toByteArray()).first)
    }

    @Test fun skipsBinaryMessages() {
        val (ws, _) = client(
            serverFrame(WsFrame.BINARY, byteArrayOf(1, 2), fin = false),
            serverFrame(WsFrame.CONTINUATION, byteArrayOf(3)),
            serverFrame(WsFrame.TEXT, "after".toByteArray()),
        )
        assertEquals("after", ws.receive())
    }

    @Test fun serverCloseIsEchoedAndEndsTheStream() {
        val (ws, out) = client(serverFrame(WsFrame.CLOSE, WsFrame.closePayload(1001, "going away")))
        assertNull(ws.receive())
        val (op, payload, _) = clientFrame(out.toByteArray())
        assertEquals(WsFrame.CLOSE, op)
        assertArrayEquals(byteArrayOf(0x03, 0xE9.toByte()), payload) // 1001 echoed
    }

    @Test fun normalCloseSendsCode1000AndWaitsForTheServer() {
        val serverIn = PipedInputStream()
        val serverOut = PipedOutputStream(serverIn)
        val out = ByteArrayOutputStream()
        val ws = WebSocketClient.over(Socket(), serverIn, out)
        val reader = Thread { assertNull(ws.receive()) }.apply { start() }
        Thread {
            Thread.sleep(100)
            serverOut.write(serverFrame(WsFrame.CLOSE, WsFrame.closePayload(1000)))
            serverOut.flush()
        }.start()
        val started = System.nanoTime()
        ws.close(waitMs = 3_000)
        reader.join(3_000)
        assertTrue("returned when the server's close came", (System.nanoTime() - started) / 1_000_000 < 2_500)
        val (op, payload, end) = clientFrame(out.toByteArray())
        assertEquals(WsFrame.CLOSE, op)
        assertArrayEquals(byteArrayOf(0x03, 0xE8.toByte()), payload)
        assertEquals("only one close frame", out.size(), end)
    }

    @Test fun protocolErrorClosesWith1002() {
        val (ws, out) = client(serverFrame(WsFrame.CONTINUATION, "x".toByteArray()))
        assertThrows { ws.receive() }
        val (op, payload, _) = clientFrame(out.toByteArray())
        assertEquals(WsFrame.CLOSE, op)
        assertArrayEquals(byteArrayOf(0x03, 0xEA.toByte()), payload)
    }

    @Test fun sendMasksText() {
        val (ws, out) = client()
        ws.send("{\"type\":\"read\"}")
        val (op, payload, _) = clientFrame(out.toByteArray())
        assertEquals(WsFrame.TEXT, op)
        assertEquals("{\"type\":\"read\"}", payload.toString(Charsets.UTF_8))
    }
}

class WebSocketHandshakeTest {
    @Test fun acceptIsTheRfcValue() {
        // RFC 6455 §1.3.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsHandshake.accept("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test fun keysAreRandom16Bytes() {
        val a = WsHandshake.newKey()
        assertEquals(16, java.util.Base64.getDecoder().decode(a).size)
        assertTrue(a != WsHandshake.newKey())
    }

    @Test fun requestCarriesTheUpgradeHeaders() {
        val r = WsHandshake.request(java.net.URI("wss://api.devreply.com/v1/live/ws?ticket=lt_x&after=0192"), "k==", "ua")
        assertTrue(r.startsWith("GET /v1/live/ws?ticket=lt_x&after=0192 HTTP/1.1\r\nHost: api.devreply.com\r\n"))
        assertTrue("Upgrade: websocket\r\n" in r && "Connection: Upgrade\r\n" in r && "Sec-WebSocket-Version: 13\r\n" in r)
        assertTrue("Sec-WebSocket-Key: k==\r\n" in r && r.endsWith("\r\n\r\n"))
        assertTrue("Host: 127.0.0.1:8192\r\n" in WsHandshake.request(java.net.URI("ws://127.0.0.1:8192/v1/live/ws?ticket=t"), "k", "ua"))
    }

    private val key = "dGhlIHNhbXBsZSBub25jZQ=="
    private fun head(accept: String, status: String = "101 Switching Protocols") =
        "HTTP/1.1 $status\r\nupgrade: websocket\r\nconnection: upgrade\r\nsec-websocket-accept: $accept"

    @Test fun verifiesTheAcceptValue() {
        WsHandshake.verify(head("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="), key)
        assertThrows { WsHandshake.verify(head("wrong="), key) }
        assertThrows { WsHandshake.verify("HTTP/1.1 101 Switching Protocols\r\nsec-websocket-accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", key) }
    }

    @Test fun aRefusedUpgradeKeepsItsStatus() {
        try {
            WsHandshake.verify("HTTP/1.1 401 Unauthorized\r\ncontent-length: 0", key)
            fail()
        } catch (e: ProtocolException) {
            assertEquals(401, e.status)
        }
    }

    @Test fun readsTheHeadWithoutEatingFrameBytes() {
        val bytes = "HTTP/1.1 101 OK\r\nA: b\r\n\r\n".toByteArray() + serverFrame(WsFrame.TEXT, "hi".toByteArray())
        val input = ByteArrayInputStream(bytes)
        assertEquals("HTTP/1.1 101 OK\r\nA: b", WsHandshake.readHead(input))
        assertEquals("hi", WsFrame.read(input, 100).payload.toString(Charsets.UTF_8))
    }
}

class LiveBackoffTest {
    @Test fun oneTwoFourEightSixteenThirtyWithJitter() {
        val low = LiveBackoff { 0.0 }
        low.connected()
        // A drop after "ready" isn't a failure: 1 s, then each failed try longer, capped at 30 s.
        assertEquals(800L, low.next(reachedReady = true))
        val mid = LiveBackoff { 0.5 }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), (1..4).map { mid.next(reachedReady = false) })
        val high = LiveBackoff { 1.0 }
        assertEquals(1_200L, high.next(reachedReady = true))
        // The table tops out at 30 s.
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), LiveBackoff.STEPS_MS.toList())
    }

    @Test fun givesUpAfterFiveFailuresInARow() {
        val b = LiveBackoff { 0.5 }
        repeat(4) { assertTrue(b.next(reachedReady = false) != null) }
        assertNull(b.next(reachedReady = false))
    }

    @Test fun readyStartsTheCountOver() {
        val b = LiveBackoff { 0.5 }
        repeat(4) { b.next(reachedReady = false) }
        b.connected()
        assertEquals(1_000L, b.next(reachedReady = true))
        repeat(4) { assertTrue(b.next(reachedReady = false) != null) }
        assertNull(b.next(reachedReady = false))
    }
}

/** A scripted socket: hands out [messages], then ends (null) or breaks ([breaks]). Records what's sent. */
private class FakeSocket(messages: List<String>, private val breaks: Boolean = false) : LiveSocket {
    private val queue = ArrayDeque(messages)
    val sent = mutableListOf<String>()
    var closed = false
    override fun receive(): String? {
        if (queue.isNotEmpty()) return queue.removeFirst()
        if (breaks) throw IOException("dead")
        return null
    }
    override fun send(text: String) { sent += text }
    override fun close() { closed = true }
    override fun abort() = Unit
}

class LiveSessionTest {
    private val ready = """{"type":"ready"}"""
    private fun message(id: String, text: String = "Hi") =
        """{"type":"message","id":"$id","conversation_id":"0192a000-0000-7000-8000-000000000001","message":{"id":"$id","author":"admin","created_at":"2026-09-30T10:00:00Z","blocks":[{"type":"text","text":"$text"}]}}"""

    private class Run(
        val sockets: MutableList<() -> LiveSocket>,
        val tickets: MutableList<() -> String> = mutableListOf(),
        var after: String? = null,
    ) {
        val urls = mutableListOf<String>()
        val states = mutableListOf<LiveState>()
        val events = mutableListOf<JSONObject>()
        val waits = mutableListOf<Long>()

        fun run() = runBlocking {
            withTimeout(5_000) {
                LiveSession(
                    ticket = { (tickets.removeFirstOrNull() ?: { "wss://api.test/v1/live/ws?ticket=lt_${urls.size}" })() },
                    open = { url -> urls += url; (sockets.removeFirstOrNull() ?: { throw IOException("refused") })() },
                    after = { after },
                    onEvent = { events += it; it.optString("id").takeIf(String::isNotEmpty)?.let { id -> after = id } },
                    onState = { states += it },
                    backoff = LiveBackoff { 0.5 },
                    sleep = { waits += it },
                ).run()
            }
        }
    }

    @Test fun eventsFlowAndADropReconnectsWithAfter() {
        val id1 = "0192a000-0000-7000-8000-00000000000a"
        val id2 = "0192a000-0000-7000-8000-00000000000b"
        val r = Run(mutableListOf({ FakeSocket(listOf(ready, message(id1), """{"type":"typing"}"""), breaks = true) }, { FakeSocket(listOf(message(id2), ready)) }))
        r.run()
        // Events are handed on in order (LiveUpdates ignores unknown types: parseMessageEvent); the second
        // connection resumes after the last message seen.
        assertEquals(listOf("message", "typing", "message"), r.events.map { it.getString("type") })
        assertEquals(listOf(id1, id2), r.events.mapNotNull { it.optString("id").takeIf(String::isNotEmpty) })
        assertEquals("wss://api.test/v1/live/ws?ticket=lt_0", r.urls[0])
        assertEquals("wss://api.test/v1/live/ws?ticket=lt_1&after=$id1", r.urls[1])
        // Live, dropped, 1 s, live again, then (the server closed) 1 s and five failed tries: polling.
        assertEquals(listOf(LiveState.Connecting, LiveState.Live, LiveState.Down, LiveState.Connecting, LiveState.Live), r.states.take(5))
        assertEquals(LiveState.Off, r.states.last())
        assertEquals(listOf(1_000L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L), r.waits)
    }

    @Test fun switchedOffMeansPollingWithoutRetries() {
        val r = Run(mutableListOf(), tickets = mutableListOf({ throw DevReplyError.Unavailable }))
        r.run()
        assertEquals(listOf(LiveState.Connecting, LiveState.Off), r.states)
        assertTrue(r.urls.isEmpty() && r.waits.isEmpty())
    }

    @Test fun ticketFailuresCountTowardsGivingUp() {
        val offline: () -> String = { throw DevReplyError.Network }
        val r = Run(mutableListOf(), tickets = MutableList(10) { offline })
        r.run()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), r.waits)
        assertEquals(LiveState.Off, r.states.last())
        assertEquals(5, r.states.count { it == LiveState.Connecting })
    }

    @Test fun withAfter() {
        assertEquals("ws://h/x?ticket=t&after=a", LiveSession.withAfter("ws://h/x?ticket=t", "a"))
        assertEquals("ws://h/x?ticket=t", LiveSession.withAfter("ws://h/x?ticket=t", null))
    }
}

class LiveMessagesTest {
    private fun msg(id: String, author: String, at: String, text: String = "x") = Message.parse(
        JSONObject("""{"id":"$id","author":"$author","created_at":"$at","blocks":[{"type":"text","text":"$text"}]}"""),
    )

    private val conv = Conversation(
        UUID.fromString("0192a000-0000-7000-8000-000000000001"), "open", DevReplyCategory.fromWire("bug"),
        "Export broken", "user", 0, parseRfc3339("2026-09-30T10:00:00Z")!!,
    )

    @Test fun parsesMessageEventsAndIgnoresOthers() {
        val e = JSONObject("""{"type":"message","id":"0192a000-0000-7000-8000-00000000000a","conversation_id":"0192a000-0000-7000-8000-000000000001",
            "message":{"id":"0192a000-0000-7000-8000-00000000000a","author":"admin","created_at":"2026-09-30T10:01:00Z","blocks":[{"type":"text","text":"On it"}]}}""")
        val (c, m) = LiveUpdates.parseMessageEvent(e)!!
        assertEquals(conv.id, c)
        assertEquals("On it", m.plainText)
        assertNull(LiveUpdates.parseMessageEvent(JSONObject("""{"type":"typing"}""")))
        assertNull(LiveUpdates.parseMessageEvent(JSONObject("""{"type":"message","conversation_id":"nope"}""")))
    }

    @Test fun mergeDedupesReplacesAndKeepsTimeOrder() {
        val a = msg("0192a000-0000-7000-8000-00000000000a", "user", "2026-09-30T10:00:00Z")
        val c = msg("0192a000-0000-7000-8000-00000000000c", "admin", "2026-09-30T10:02:00Z")
        val b = msg("0192a000-0000-7000-8000-00000000000b", "admin", "2026-09-30T10:01:00Z")
        val list = mergeMessage(mergeMessage(listOf(a), c), b)
        assertEquals(listOf(a, b, c), list)
        assertEquals(list, mergeMessage(list, b))
        val edited = msg(b.id.toString(), "admin", "2026-09-30T10:01:00Z", "edited")
        assertEquals("edited", mergeMessage(list, edited)[1].plainText)
        assertEquals(3, mergeMessage(list, edited).size)
    }

    @Test fun aReplyUpdatesTheListRowLikeAPoll() {
        val reply = msg("0192a000-0000-7000-8000-00000000000b", "admin", "2026-09-30T10:01:00Z", "On it")
        val off = conv.applying(reply, onScreen = false)
        assertEquals("On it", off.lastText)
        assertEquals("admin", off.lastAuthor)
        assertEquals(1, off.unread)
        assertEquals(reply.createdAt, off.lastMessageAt)
        // On screen: read at once.
        assertEquals(0, conv.copy(unread = 2).applying(reply, onScreen = true).unread)
        // The user's own message (another device) isn't unread.
        assertEquals(0, conv.applying(msg("0192a000-0000-7000-8000-00000000000c", "user", "2026-09-30T10:02:00Z"), false).unread)
        // Already covered by the row (a backlog message the list has): nothing changes, no double count.
        assertEquals(off, off.applying(reply, onScreen = false))
    }

    @Test fun lastSeenKeepsTheNewestId() {
        LiveUpdates.forget()
        LiveUpdates.saw(UUID.fromString("0192a000-0000-7000-8000-00000000000b"))
        LiveUpdates.saw(UUID.fromString("0192a000-0000-7000-8000-00000000000a"))
        assertEquals("0192a000-0000-7000-8000-00000000000b", LiveUpdates.lastSeen)
        LiveUpdates.forget()
        assertNull(LiveUpdates.lastSeen)
    }
}

/**
 * The client against a real DevReply server (plain ws://), when one is given:
 * `DEVREPLY_LIVE_API=http://127.0.0.1:8192 DEVREPLY_LIVE_PK=pk_… ./gradlew :devreply:testDebugUnitTest`.
 * Skipped otherwise.
 */
class LiveServerTest {
    @Test fun connectsGetsReadyAndItsOwnMessageLive() = runBlocking {
        val api = System.getenv("DEVREPLY_LIVE_API")
        val pk = System.getenv("DEVREPLY_LIVE_PK")
        assumeTrue("no local server given", api != null && pk != null)
        val client = ApiClient(api!!)
        val token = client.registerInstall(pk!!, DeviceInfo("JVM", "17", "test"), null)
        val started = client.startConversation(token, DevReplyCategory.fromWire("question"), "Live test", emptyList())
        val url = client.liveTicket(token)
        assertTrue(url, url.startsWith("ws://"))
        val ws = WebSocketClient.connect(LiveSession.withAfter(url, started.message.id.toString()), readTimeoutMs = 10_000)
        assertEquals("ready", JSONObject(ws.receive()!!).getString("type"))
        // Used ticket: refused with 401.
        try {
            WebSocketClient.connect(url).abort()
            fail("a ticket is single-use")
        } catch (e: ProtocolException) {
            assertEquals(401, e.status)
        }
        val sent = client.sendMessage(token, started.conversation.id, "Over the socket?", emptyList())
        val event = JSONObject(ws.receive()!!)
        val (conversation, message) = LiveUpdates.parseMessageEvent(event)!!
        assertEquals(started.conversation.id, conversation)
        assertEquals(sent.id, message.id)
        ws.send(JSONObject().put("type", "read").put("conversation_id", conversation.toString()).toString())
        ws.close()

        // Resume: what was said while disconnected comes first, then "ready".
        val missed = client.sendMessage(token, started.conversation.id, "While offline", emptyList())
        val again = WebSocketClient.connect(LiveSession.withAfter(client.liveTicket(token), sent.id.toString()), readTimeoutMs = 10_000)
        assertEquals(missed.id, LiveUpdates.parseMessageEvent(JSONObject(again.receive()!!))!!.second.id)
        assertEquals("ready", JSONObject(again.receive()!!).getString("type"))
        again.close()
    }
}

private inline fun assertThrows(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        return
    }
    fail("expected an exception")
}
