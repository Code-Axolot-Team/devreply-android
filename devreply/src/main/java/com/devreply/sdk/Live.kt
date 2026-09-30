package com.devreply.sdk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToLong

// Live updates over WebSocket (spec 05, 0.5.0): while the messenger is on screen, new messages arrive the
// moment they're sent; the 3 s poll runs only while the socket is down.

/** Where the live channel is. The chat polls in every state but [Live]. */
internal enum class LiveState {
    /** Asking for a ticket and connecting (or receiving the backlog). */
    Connecting,
    /** Connected and caught up (`ready`). */
    Live,
    /** Dropped: waiting to reconnect. */
    Down,
    /** Not connected until the messenger opens again: closed, live switched off (503), or 5 failures in a row. */
    Off,
}

/**
 * Reconnect delays after a drop: 1, 2, 4, 8, 16, 30 s, each ±20%; 5 failed tries in a row = give up
 * (poll until the messenger opens again). A connection that got to `ready` starts the count over.
 */
internal class LiveBackoff(private val random: () -> Double = Math::random) {
    private var failures = 0
    private var retries = 0

    fun connected() {
        failures = 0
        retries = 0
    }

    /** How long to wait before the next try after a connection ended, or null to give up. */
    fun next(reachedReady: Boolean): Long? {
        if (reachedReady) connected() else if (++failures >= MAX_FAILURES) return null
        val base = STEPS_MS[min(retries, STEPS_MS.lastIndex)]
        retries++
        return (base * (1 + JITTER * (2 * random() - 1))).roundToLong()
    }

    companion object {
        val STEPS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
        const val JITTER = 0.2
        const val MAX_FAILURES = 5
    }
}

/** One open WebSocket, as the live session needs it (a fake one in unit tests). Blocking calls. */
internal interface LiveSocket {
    /** The next text message; null once the connection closed. Throws when it broke (or went silent). */
    fun receive(): String?
    fun send(text: String)
    /** A normal close. */
    fun close()
    /** Closes without a goodbye (after an error). */
    fun abort()
}

internal fun WebSocketClient.asLiveSocket(): LiveSocket = object : LiveSocket {
    override fun receive() = this@asLiveSocket.receive()
    override fun send(text: String) = this@asLiveSocket.send(text)
    override fun close() = this@asLiveSocket.close()
    override fun abort() = this@asLiveSocket.abort()
}

/**
 * The live channel while the messenger is open: gets a ticket, connects with `after` (the last message
 * seen), hands events to [onEvent], and reconnects after a drop (see [LiveBackoff]). [run] returns when
 * it gives up or live is switched off; cancel it (and [close]) to stop.
 */
internal class LiveSession(
    private val ticket: suspend () -> String,
    private val open: (String) -> LiveSocket,
    private val after: () -> String?,
    private val onEvent: (JSONObject) -> Unit,
    private val onState: (LiveState) -> Unit,
    private val backoff: LiveBackoff = LiveBackoff(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    @Volatile private var socket: LiveSocket? = null

    suspend fun run() {
        while (true) {
            onState(LiveState.Connecting)
            var reachedReady = false
            val url = try {
                ticket()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DevReplyError.Unavailable) {
                // 503: live is switched off (or full). The chat polls.
                onState(LiveState.Off)
                return
            } catch (e: DevReplyError.NotFound) {
                // A server without live updates.
                onState(LiveState.Off)
                return
            } catch (e: Exception) {
                null
            }
            if (url != null) {
                val s = try {
                    runInterruptible { open(withAfter(url, after())) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (s != null) {
                    socket = s
                    try {
                        while (true) {
                            val text = runInterruptible { s.receive() } ?: break
                            val event = runCatching { JSONObject(text) }.getOrNull() ?: continue
                            if (event.optString("type") == "ready") {
                                reachedReady = true
                                backoff.connected()
                                onState(LiveState.Live)
                            } else {
                                onEvent(event)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Broke, or 75 s without a frame: dead. Reconnect below.
                    } finally {
                        socket = null
                        s.abort()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val wait = backoff.next(reachedReady)
            if (wait == null) {
                onState(LiveState.Off)
                return
            }
            onState(LiveState.Down)
            sleep(wait)
        }
    }

    /** Sends on the open socket, if there is one. Blocking. */
    fun send(text: String) {
        runCatching { socket?.send(text) }
    }

    /** A normal close of the open socket (the messenger closed). Blocking: call off the main thread. */
    fun close() {
        socket?.let { runCatching { it.close() } }
    }

    companion object {
        fun withAfter(url: String, after: String?): String =
            if (after == null) url else url + (if ('?' in url) "&" else "?") + "after=" + after
    }
}

/** The live channel of the open messenger: started and stopped by [com.devreply.sdk.ui.DevReplyActivity]. */
internal object LiveUpdates {
    /** Connected and caught up: the chat's 3 s poll pauses. Compose state. */
    var isLive by mutableStateOf(false)
        private set

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: LiveSession? = null
    private var job: Job? = null
    private var visible = 0

    /** The newest message id this install has seen (ids are time-ordered UUID v7): where a reconnect resumes. */
    @Volatile var lastSeen: String? = null
        private set

    fun saw(id: UUID) {
        val s = id.toString()
        val current = lastSeen
        if (current == null || s > current) lastSeen = s
    }

    /** A messenger came on screen (main thread). */
    fun messengerVisible() {
        if (++visible == 1) start()
    }

    /** A messenger left the screen: closed, or the app went to the background (main thread). */
    fun messengerHidden() {
        visible = maxOf(0, visible - 1)
        if (visible == 0) stop()
    }

    /** The install changed (logout, deleted user): nothing of the old one carries over. */
    fun forget() {
        lastSeen = null
        if (session != null) {
            stop()
            if (visible > 0) start()
        }
    }

    private fun start() {
        if (Messenger.client == null) return
        stop()
        lateinit var s: LiveSession
        s = LiveSession(
            ticket = { Messenger.authorized { api, t -> api.liveTicket(t) } },
            open = { url -> WebSocketClient.connect(url).asLiveSocket() },
            after = { lastSeen },
            onEvent = { event -> received(s, event) },
            onState = { state -> Messenger.scope.launch { if (session === s) isLive = state == LiveState.Live } },
        )
        session = s
        job = io.launch { s.run() }
    }

    private fun stop() {
        val s = session ?: return
        session = null
        isLive = false
        job?.cancel()
        job = null
        io.launch { s.close() }
    }

    /** `{"type":"read"}`: the conversation on screen got a reply, so the team's replies count as read. */
    fun sendRead(conversationId: UUID) {
        val s = session ?: return
        val frame = JSONObject().put("type", "read").put("conversation_id", conversationId.toString()).toString()
        io.launch { s.send(frame) }
    }

    /** An event from the server (on the socket's thread). Unknown types are ignored. */
    private fun received(s: LiveSession, event: JSONObject) {
        val parsed = parseMessageEvent(event) ?: return
        Messenger.scope.launch {
            if (session === s) Messenger.receiveLive(parsed.first, parsed.second)
        }
    }

    /** A `message` event's conversation and message, or null for anything else (or a broken one). */
    fun parseMessageEvent(event: JSONObject): Pair<UUID, Message>? {
        if (event.optString("type") != "message") return null
        return runCatching {
            UUID.fromString(event.getString("conversation_id")) to Message.parse(event.getJSONObject("message"))
        }.getOrNull()
    }
}

/**
 * A poll result's view of one new message: the list row shows it as the newest, and a team reply that
 * isn't on screen counts as unread. Messages the row already covers (older than its last one) change nothing.
 */
internal fun Conversation.applying(message: Message, onScreen: Boolean): Conversation {
    if (message.createdAt <= lastMessageAt) return this
    val author = when (message.author) {
        Message.Author.User -> "user"
        Message.Author.Admin -> "admin"
        Message.Author.Agent -> "agent"
        Message.Author.System -> "system"
    }
    return copy(
        lastText = message.plainText.takeIf { it.isNotBlank() },
        lastAuthor = author,
        lastMessageAt = message.createdAt,
        unread = if (onScreen) 0 else unread + if (message.isFromUser) 0 else 1,
    )
}

/** [messages] with [incoming] in it: replaces the one with the same id, else joins in time order. */
internal fun mergeMessage(messages: List<Message>, incoming: Message): List<Message> {
    val i = messages.indexOfFirst { it.id == incoming.id }
    if (i >= 0) return if (messages[i] == incoming) messages else messages.toMutableList().also { it[i] = incoming }
    return (messages + incoming).sortedWith(compareBy<Message> { it.createdAt }.thenBy { it.id.toString() })
}
