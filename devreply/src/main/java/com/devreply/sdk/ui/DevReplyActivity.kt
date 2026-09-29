package com.devreply.sdk.ui

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.devreply.sdk.DevReply
import com.devreply.sdk.DevReplyCategory
import com.devreply.sdk.Messenger
import java.util.UUID

/** Where the messenger is: home, or one conversation (existing, or new in a category). */
internal sealed interface Route {
    data object Home : Route
    data class Chat(val conversationId: UUID?, val category: DevReplyCategory?) : Route
}

/**
 * The messenger, opened with [DevReply.present]: home (greeting, start buttons, past conversations)
 * → one conversation. Its own activity and theme, so nothing depends on the host's.
 */
internal class DevReplyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // The brand is a light look: dark status and navigation bar icons in dark mode too.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Messenger.restore(this)
        if (intent.getBooleanExtra(EXTRA_FROM_PUSH, false)) com.devreply.sdk.PushManager.reportOpened()
        val category = intent.getStringExtra(EXTRA_CATEGORY)?.let { raw -> DevReplyCategory.entries.firstOrNull { it.wire == raw } }
        val conversation = intent.getStringExtra(EXTRA_CONVERSATION)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        current = java.lang.ref.WeakReference(this)
        setContent { MessengerScreen(start = category, conversation = conversation, close = ::finish) }
    }

    override fun onResume() {
        super.onResume()
        // Back from the notification settings or the permission dialog.
        com.devreply.sdk.PushManager.recheck()
    }

    internal companion object {
        const val EXTRA_CATEGORY = "com.devreply.sdk.category"
        const val EXTRA_CONVERSATION = "com.devreply.sdk.conversation"
        const val EXTRA_FROM_PUSH = "com.devreply.sdk.from_push"

        /** The open messenger, if any: closed when the user logs out. */
        var current: java.lang.ref.WeakReference<DevReplyActivity>? = null
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MessengerScreen(start: DevReplyCategory?, conversation: UUID?, close: () -> Unit) {
    // A start category opens a new conversation straight away, a conversation id (from the unread
    // bubble) opens that one; back from either goes home.
    var stack by rememberSaveable(saver = RouteStackSaver) {
        mutableStateOf(
            when {
                conversation != null -> listOf(Route.Home, Route.Chat(conversation, null))
                start != null -> listOf(Route.Home, Route.Chat(null, start))
                else -> listOf(Route.Home)
            },
        )
    }
    val route = stack.last()
    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }
    DisposableEffect(Unit) {
        Messenger.isPresented = true
        onDispose { Messenger.isPresented = false }
    }

    AnimatedContent(
        targetState = route,
        modifier = Modifier
            .fillMaxSize()
            .background(DevReply.theme.background)
            // UI tests (UiAutomator) find views by these tags.
            .semantics { testTagsAsResourceId = true },
        transitionSpec = {
            val forward = targetState is Route.Chat
            (slideInHorizontally(tween(260)) { if (forward) it else -it / 3 })
                .togetherWith(slideOutHorizontally(tween(260)) { if (forward) -it / 3 else it })
        },
        contentKey = { if (it is Route.Home) "home" else "chat" },
        label = "route",
    ) { current ->
        when (current) {
            Route.Home -> HomeScreen(
                open = { stack = stack + it },
                close = close,
            )
            is Route.Chat -> ConversationScreen(
                conversationId = current.conversationId,
                category = current.category,
                back = { stack = stack.dropLast(1) },
            )
        }
    }
}

private val RouteStackSaver = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.MutableState<List<Route>>, List<String>>(
    save = { state ->
        state.value.map { r ->
            when (r) {
                Route.Home -> "home"
                is Route.Chat -> "chat|${r.conversationId ?: ""}|${r.category?.name ?: ""}"
            }
        }
    },
    restore = { saved ->
        mutableStateOf(saved.map { s ->
            if (s == "home") Route.Home else {
                val parts = s.split("|")
                Route.Chat(
                    parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(UUID::fromString),
                    parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { DevReplyCategory.valueOf(it) },
                )
            }
        })
    },
)
