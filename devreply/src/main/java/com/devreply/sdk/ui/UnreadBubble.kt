package com.devreply.sdk.ui

import android.app.Activity
import android.content.Intent
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.devreply.sdk.Messenger
import com.devreply.sdk.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A small round DevReply button over the host app, bottom right, while a reply is waiting (spec 05).
 * Tap opens that conversation. Nothing for the host app to build: it shows itself on the app's screens.
 *
 * - Only while there are unread replies, the messenger is closed and the keyboard is down.
 * - A see-through overlay on each of the app's screens: touches outside the button reach the app.
 * - Drag it up or down to move it; swipe it right to hide it until the next reply.
 * - Off with `DevReply.showsUnreadBubble = false` for apps that show `DevReply.unreadCount` themselves.
 */
internal object UnreadBubble {
    var enabled by mutableStateOf(true)

    /** Hidden by a swipe while this many were unread; shows again when more arrive. */
    var dismissedAt by mutableStateOf<Int?>(null)

    /** How far it was dragged up (negative) or down from its place. Shared by all screens. */
    var offsetY by mutableFloatStateOf(0f)

    private const val TAG = "devreply.bubble.overlay"

    /** Adds the overlay to a screen of the app, once. Needs a lifecycle owner (any AndroidX activity). */
    fun attach(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<ComposeView>(TAG) != null) return
        if (content.findViewTreeLifecycleOwner() == null && activity.window.decorView.findViewTreeLifecycleOwner() == null) return
        val keyboardUp = mutableStateOf(false)
        val view = ComposeView(activity).apply {
            tag = TAG
            setContent { Overlay(keyboardUp) { open(activity) } }
        }
        // The keyboard, read from the window (works whether or not the app draws edge to edge).
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            keyboardUp.value = ViewCompat.getRootWindowInsets(content)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        content.viewTreeObserver.addOnGlobalLayoutListener(listener)
        content.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun open(activity: Activity) {
        val id = Messenger.conversations.firstOrNull { it.unread > 0 }?.id
        val intent = Intent(activity, DevReplyActivity::class.java)
        if (id != null) intent.putExtra(DevReplyActivity.EXTRA_CONVERSATION, id.toString())
        activity.startActivity(intent)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Overlay(keyboardUp: MutableState<Boolean>, open: () -> Unit) {
    val unread = Messenger.unreadCount
    LaunchedEffect(unread) {
        val dismissed = UnreadBubble.dismissedAt
        if (unread == 0 || (dismissed != null && unread > dismissed)) UnreadBubble.dismissedAt = null
    }
    val shows = UnreadBubble.enabled && unread > 0 && !Messenger.isPresented && !keyboardUp.value &&
        UnreadBubble.dismissedAt == null
    // Fills the screen but handles no touches itself: taps outside the button go to the app.
    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        AnimatedVisibility(
            visible = shows,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                // Clear of a bottom navigation bar by default.
                .padding(end = 12.dp, bottom = 72.dp)
                .offset { IntOffset(0, UnreadBubble.offsetY.roundToInt()) },
            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            Bubble(unread, Messenger.config.teamName, open)
        }
    }
}

@Composable
private fun Bubble(count: Int, teamName: String, open: () -> Unit) {
    var dragX by remember { mutableFloatStateOf(0f) }
    var sideways by remember { mutableStateOf<Boolean?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shift = if (pressed) 3.dp else 0.dp
    val who = teamName.ifEmpty { "the team" }
    val label = if (count == 1) "New reply from $who" else "$count new replies from $who"

    Box(
        Modifier
            .offset { IntOffset(dragX.coerceAtLeast(0f).roundToInt(), 0) }
            .alpha(1f - (dragX.coerceAtLeast(0f) / 300f).coerceAtMost(0.7f))
            .padding(top = 8.dp, end = 8.dp) // room for the badge
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        // It sits at the edge, so a short swipe to the right is enough.
                        if (sideways == true && dragX > 30.dp.toPx()) UnreadBubble.dismissedAt = Messenger.unreadCount
                        sideways = null
                        dragX = 0f
                    },
                    onDragCancel = {
                        sideways = null
                        dragX = 0f
                    },
                ) { change, amount ->
                    change.consume()
                    // The first clear movement decides: sideways hides, up/down moves.
                    if (sideways == null) sideways = abs(amount.x) > abs(amount.y)
                    if (sideways == true) dragX += amount.x else UnreadBubble.offsetY += amount.y
                }
            }
            // One element for accessibility (and for UI tests, by its tag).
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                testTag = "devreply.bubble"
                onClick("Open the chat") { open(); true }
            },
    ) {
        // Lemon circle, ink outline, hard shadow; presses down into the shadow like the brand's buttons.
        Box(
            Modifier
                .size(60.dp)
                .offset(shift, shift)
                .drawBehind {
                    val o = (4.dp - shift).toPx()
                    drawCircle(Brand.ink, radius = size.minDimension / 2, center = center + Offset(o, o))
                }
                .background(Brand.lemon, CircleShape)
                .border(3.dp, Brand.ink, CircleShape)
                .clickable(interaction, indication = null, onClick = open),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.devreply_mark), null, Modifier.size(32.dp).offset(y = 2.dp))
        }
        BasicText(
            if (count > 9) "9+" else "$count",
            Modifier
                .align(Alignment.TopEnd)
                .offset(8.dp, (-8).dp)
                .sizeIn(minWidth = 24.dp, minHeight = 24.dp)
                .background(Brand.pink, CircleShape)
                .border(2.5.dp, Brand.ink, CircleShape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            style = text(13.sp, FontWeight.Bold, Brand.ink).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        )
    }
}
