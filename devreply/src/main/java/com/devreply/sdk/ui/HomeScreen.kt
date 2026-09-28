package com.devreply.sdk.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devreply.sdk.Conversation
import com.devreply.sdk.DevReply
import com.devreply.sdk.DevReplyCategory
import com.devreply.sdk.Messenger
import com.devreply.sdk.MessengerConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun HomeScreen(open: (Route) -> Unit, close: () -> Unit) {
    val theme = DevReply.theme
    val config = Messenger.config
    val conversations = Messenger.conversations
    val error = Messenger.lastError
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            Messenger.refresh()
            delay(10_000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        Header(config, close)
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 26.dp, bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            StartSection(config, open)
            if (conversations.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    BasicText("Your conversations", style = display(22.sp))
                    // Open ones first, resolved below.
                    conversations.sortedBy { if (it.isClosed) 1 else 0 }.take(20).forEach { c ->
                        BrutalButton({ open(Route.Chat(c.id, c.category)) }, Modifier.fillMaxWidth(), shadow = 4.dp) {
                            ConversationRow(c)
                        }
                    }
                }
            }
            if (error != null && conversations.isEmpty()) {
                ErrorNote(error) { scope.launch { Messenger.refresh() } }
            }
            BasicText(
                "Powered by DevReply",
                Modifier.fillMaxWidth(),
                style = text(12.sp, FontWeight.Medium, Brand.muted).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            )
        }
    }
}

@Composable
private fun Header(config: MessengerConfig, close: () -> Unit) {
    val theme = DevReply.theme
    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.primary)
            .drawBehind {
                val h = 3.dp.toPx()
                drawRect(theme.ink, topLeft = Offset(0f, size.height - h), size = size.copy(height = h))
            }
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 29.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TeamAvatar(config.teamName, 44.dp)
            Box(Modifier.weight(1f)) {
                Kicker(config.teamName.ifEmpty { "Support" }, inverted = true)
            }
            IconSquareButton(com.devreply.sdk.R.drawable.devreply_ic_close, "Close", close, Modifier.testTag("devreply.close"))
        }
        BasicText(config.greeting, style = display(38.sp))
        BasicText(config.intro, style = text(17.sp, FontWeight.Medium))
        if (config.replyTime.isNotEmpty()) {
            Row(
                Modifier.brutal(shadow = 3.dp, lineWidth = 2.5.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).background(Brand.online).border(1.5.dp, theme.ink))
                BasicText(config.replyTime, style = text(14.sp, FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun StartSection(config: MessengerConfig, open: (Route) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BasicText("Start a conversation", style = display(22.sp))
        config.startButtons.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEach { button ->
                    BrutalButton(
                        { open(Route.Chat(null, button.category)) },
                        Modifier.weight(1f).testTag("devreply.start.${button.category.name.lowercase()}"),
                    ) { StartTile(button) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StartTile(button: MessengerConfig.StartButton) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 144.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(painterResource(button.category.icon), null, Modifier.size(46.dp))
        BasicText(button.title, style = text(16.sp, FontWeight.Bold, Brand.ink))
    }
}

@Composable
private fun ConversationRow(c: Conversation) {
    val category = c.category ?: DevReplyCategory.Other
    Row(
        Modifier.fillMaxWidth().padding(14.dp).alpha(if (c.isClosed) 0.7f else 1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(painterResource(category.icon), null, Modifier.size(34.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kicker(if (c.lastAuthor == "user") "You" else "Team")
                if (c.isClosed) {
                    BasicText(
                        "✓ Resolved",
                        Modifier.background(Brand.resolved).border(1.5.dp, Brand.ink).padding(horizontal = 6.dp, vertical = 2.dp),
                        style = text(11.sp, FontWeight.Bold, Brand.ink),
                    )
                }
                Spacer(Modifier.weight(1f))
                BasicText(
                    DateUtils.getRelativeTimeSpanString(
                        c.lastMessageAt.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                    style = text(12.sp, FontWeight.Medium, Brand.muted),
                    maxLines = 1,
                )
            }
            BasicText(
                c.lastText ?: "Photo",
                style = text(15.sp, if (c.unread > 0) FontWeight.Bold else FontWeight.Normal, Brand.ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (c.unread > 0) {
            Box(
                Modifier
                    .sizeIn(minWidth = 24.dp, minHeight = 24.dp)
                    .background(Brand.pink)
                    .border(2.dp, Brand.ink)
                    .semantics { contentDescription = "${c.unread} unread" },
                contentAlignment = Alignment.Center,
            ) {
                BasicText("${c.unread}", Modifier.padding(horizontal = 4.dp), style = text(13.sp, FontWeight.Bold, Brand.ink))
            }
        }
    }
}

