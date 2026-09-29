package com.devreply.sdk

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** What happens in the messenger, for your analytics ([DevReply.addEventListener]). */
public sealed class DevReplyEvent {
    /** The messenger appeared (from [DevReply.present], the unread bubble, a notification or a link). */
    public data object MessengerOpened : DevReplyEvent()

    /** The messenger was dismissed, whichever way. */
    public data object MessengerClosed : DevReplyEvent()

    /** The user started a conversation (the server accepted it). [MessageSent] for its first message follows. */
    public data class ConversationStarted(val conversationId: String, val category: DevReplyCategory?) : DevReplyEvent()

    /** The server accepted a message from the user, the first one of a conversation included. */
    public data class MessageSent(val conversationId: String) : DevReplyEvent()
}

/** Returned by [DevReply.addEventListener]: [cancel] stops the events. */
public class DevReplySubscription internal constructor(private val onCancel: () -> Unit) {
    /** No more events for this listener. Safe to call more than once. */
    public fun cancel() {
        onCancel()
    }
}

/** Any number of listeners; each gets every event, in order. */
internal class EventHub {
    private val listeners = CopyOnWriteArrayList<(DevReplyEvent) -> Unit>()

    fun add(listener: (DevReplyEvent) -> Unit): DevReplySubscription {
        // A wrapper per call, so adding the same function twice gives two independent subscriptions.
        val entry: (DevReplyEvent) -> Unit = { listener(it) }
        listeners.add(entry)
        return DevReplySubscription { listeners.remove(entry) }
    }

    /** Calls the listeners now, on this thread. */
    fun deliver(event: DevReplyEvent) {
        listeners.forEach { it(event) }
    }

    companion object {
        /** The events after the user's first message of a new conversation, in the order they're sent. */
        fun firstMessage(conversationId: String, category: DevReplyCategory?): List<DevReplyEvent> =
            listOf(DevReplyEvent.ConversationStarted(conversationId, category), DevReplyEvent.MessageSent(conversationId))
    }
}

internal object Events {
    val hub = EventHub()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Posted to the main thread, in order: a listener that throws never interrupts the messenger
     * (e.g. marks a delivered message as failed).
     */
    fun emit(event: DevReplyEvent) {
        main.post { hub.deliver(event) }
    }
}
