package com.devreply.sdk.ui

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
import androidx.compose.foundation.layout.offset
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
import com.devreply.sdk.L10n
import com.devreply.sdk.greetingText
import com.devreply.sdk.introText
import com.devreply.sdk.replyTimeText
import com.devreply.sdk.t
import com.devreply.sdk.title
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun HomeScreen(open: (Route) -> Unit, close: () -> Unit) {
    val theme = LocalTheme.current
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
                    BasicText(t("your_conversations"), style = display(22.sp))
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
                t("powered"),
                Modifier.fillMaxWidth(),
                style = text(12.sp, FontWeight.Medium, theme.muted).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            )
        }
    }
}

@Composable
private fun Header(config: MessengerConfig, close: () -> Unit) {
    // On the header colour: onHeader text; the cards on it keep their surface, ink and outlines.
    val theme = LocalTheme.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.headerFill)
            .drawBehind {
                val h = theme.stroke(3.dp).toPx()
                drawRect(theme.line, topLeft = Offset(0f, size.height - h), size = size.copy(height = h))
            }
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 29.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TeamAvatar(config.teamName, 44.dp, imageUrl = config.appIconUrl, modifier = Modifier.testTag("devreply.appicon"))
            Box(Modifier.weight(1f)) {
                Kicker(config.teamName.ifEmpty { t("team") }, inverted = true)
            }
            IconSquareButton(
                com.devreply.sdk.R.drawable.devreply_ic_close, t("close"), close, Modifier.testTag("devreply.close"),
            )
        }
        BasicText(config.greetingText, style = display(38.sp, theme.onHeaderColor))
        BasicText(config.introText, style = text(17.sp, FontWeight.Medium, theme.onHeaderColor))
        if (config.replyTimeText.isNotEmpty()) {
            Row(
                Modifier.brutal(theme, shadow = 3.dp, lineWidth = 2.5.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (config.team.isEmpty()) {
                    // Status green reads on light and dark alike.
                    Box(Modifier.size(10.dp).background(theme.success).border(theme.stroke(1.5.dp), theme.line))
                } else {
                    TeamFaces(config.team)
                }
                BasicText(config.replyTimeText, style = text(14.sp, FontWeight.Bold))
            }
        }
    }
}

/** The people who answer, overlapping squares (up to 3), like a team on a support page. */
@Composable
private fun TeamFaces(team: List<com.devreply.sdk.Persona>) {
    val face = 24.dp
    val step = 16.dp
    Box(
        Modifier
            .size(width = face + step * (team.size - 1), height = face)
            .testTag("devreply.team")
            .semantics { contentDescription = team.joinToString(", ") { it.name } },
    ) {
        team.forEachIndexed { i, p ->
            TeamAvatar(
                p.name, face, imageUrl = p.avatarUrl, lineWidth = 2.dp, modifier = Modifier.offset(x = step * i),
            )
        }
    }
}

@Composable
private fun StartSection(config: MessengerConfig, open: (Route) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BasicText(t("start_title"), style = display(22.sp))
        config.startButtons.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEach { button ->
                    BrutalButton(
                        { open(Route.Chat(null, button.category)) },
                        Modifier.weight(1f).testTag("devreply.start.${button.category.name.lowercase()}"),
                    ) { StartTile(button, config.title(button)) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StartTile(button: MessengerConfig.StartButton, title: String) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 144.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(categoryArt(button.category), null, Modifier.size(46.dp))
        BasicText(title, style = text(16.sp, FontWeight.Bold))
    }
}

@Composable
private fun ConversationRow(c: Conversation) {
    val theme = LocalTheme.current
    val category = c.category ?: DevReplyCategory.Other
    Row(
        Modifier.fillMaxWidth().padding(14.dp).alpha(if (c.isClosed) 0.7f else 1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(categoryArt(category), null, Modifier.size(34.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kicker(if (c.lastAuthor == "user") t("you") else t("team"))
                if (c.isClosed) {
                    BasicText(
                        t("resolved"),
                        // Mint is light in both looks: dark text on it.
                        Modifier.background(theme.resolved).border(theme.stroke(1.5.dp), theme.line).padding(horizontal = 6.dp, vertical = 2.dp),
                        style = text(11.sp, FontWeight.Bold, theme.onBrand),
                    )
                }
                Spacer(Modifier.weight(1f))
                BasicText(
                    L10n.relative(c.lastMessageAt),
                    style = text(12.sp, FontWeight.Medium, theme.muted),
                    maxLines = 1,
                )
            }
            BasicText(
                c.lastText?.let { if (it == L10n.LEGACY_RESOLVED) t("system.resolved") else it } ?: t("photo"),
                style = text(15.sp, if (c.unread > 0) FontWeight.Bold else FontWeight.Normal),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (c.unread > 0) {
            Box(
                Modifier
                    .sizeIn(minWidth = 24.dp, minHeight = 24.dp)
                    .background(theme.accent)
                    .border(theme.stroke(2.dp), theme.line)
                    .semantics {
                        contentDescription = t("a11y.unread", "count" to c.unread)
                    },
                contentAlignment = Alignment.Center,
            ) {
                BasicText("${c.unread}", Modifier.padding(horizontal = 4.dp), style = text(13.sp, FontWeight.Bold, theme.onAccentColor))
            }
        }
    }
}

