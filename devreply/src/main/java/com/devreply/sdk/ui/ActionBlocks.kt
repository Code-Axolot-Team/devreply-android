package com.devreply.sdk.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devreply.sdk.Block
import com.devreply.sdk.FormAnswer
import com.devreply.sdk.LinkPolicy
import com.devreply.sdk.Messenger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

// The 0.6.0 blocks (spec 14, "0.6 wire formats"): link buttons, a gift, several questions; and the offer line.

/**
 * Opens [url] with the OS when the chat may (https or the app's own scheme): the app's own deep-link handling
 * does the rest. Never crashes when nothing can open it. Returns whether it was handed to the OS.
 */
internal fun openAllowedLink(context: Context, url: String): Boolean {
    if (!LinkPolicy.isAllowed(url)) return false
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}

/**
 * Link buttons (0.6.0): the intro in the team bubble, then the buttons in the outlined look of the answer buttons,
 * as wide as the widest. Only links the chat may open show; with none left, the fallback text does.
 */
@Composable
internal fun LinksBlock(block: Block.Links, onTap: (Block.Links.Link) -> Unit) {
    val links = block.links.filter { LinkPolicy.isAllowed(it.url) }
    if (links.isEmpty()) {
        if (block.nodes.isNotEmpty()) MarkdownBubble(block.nodes) else PlainTeamText(block.fallback)
        return
    }
    if (block.nodes.isNotEmpty()) MarkdownBubble(block.nodes)
    val theme = LocalTheme.current
    Column(
        Modifier.width(IntrinsicSize.Max).padding(top = 2.dp, end = 3.dp, bottom = 3.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        links.forEach { link ->
            BrutalButton(
                onClick = { onTap(link) },
                modifier = Modifier.fillMaxWidth().testTag("devreply.link.${link.id}"),
                fill = theme.surface,
                shadow = 3.dp,
                label = link.label,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 11.dp).heightIn(min = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BasicText(link.label, Modifier.weight(1f, fill = false), style = text(16.sp, FontWeight.Bold))
                    BasicText("↗", style = text(16.sp, FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
private fun PlainTeamText(text: String) {
    val theme = LocalTheme.current
    BasicText(text, Modifier.teamBubble(theme).padding(horizontal = 14.dp, vertical = 11.dp), style = text(17.sp, FontWeight.Medium, theme.teamBubbleTextColor))
}

/**
 * A gift (0.6.0): a card with a small wrapped box, "A gift for you", the title, one line and "Open gift". Past its
 * expiry the button turns into "This gift has expired" (also while the card is on screen).
 */
@Composable
internal fun GiftCard(block: Block.Gift, onOpen: () -> Unit) {
    val theme = LocalTheme.current
    var expired by remember(block.giftId, block.expiresAt) { mutableStateOf(block.isExpired()) }
    LaunchedEffect(block.giftId, block.expiresAt) {
        val at = block.expiresAt ?: return@LaunchedEffect
        val wait = Duration.between(Instant.now(), at).toMillis()
        if (wait > 0) {
            delay(wait)
            expired = true
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(end = 3.dp, bottom = 3.dp)
            .brutal(theme, fill = theme.teamBubbleFill, shadow = 3.dp, lineWidth = 2.5.dp, cornerRadius = 14.dp)
            .testTag("devreply.gift"),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .background(theme.brand, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .border(theme.stroke(2.5.dp), theme.line, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            WrappedBox(Modifier.size(58.dp).alpha(if (expired) 0.5f else 1f))
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Kicker(com.devreply.sdk.t("gift.label"))
            BasicText(block.title, style = display(20.sp, theme.teamBubbleTextColor).copy(lineHeight = 24.sp))
            if (block.text.isNotEmpty()) {
                BasicText(block.text, style = text(16.sp, FontWeight.Medium, theme.teamBubbleTextColor))
            }
            Box(Modifier.padding(top = 6.dp)) {
                if (expired) {
                    BasicText(
                        com.devreply.sdk.t("gift.expired"),
                        Modifier.testTag("devreply.gift.expired"),
                        style = text(15.sp, FontWeight.Bold, theme.muted),
                    )
                } else {
                    val label = com.devreply.sdk.t("gift.open")
                    BrutalButton(onOpen, Modifier.fillMaxWidth().padding(end = 3.dp).testTag("devreply.gift.open"), fill = theme.accent, shadow = 3.dp, label = label) {
                        Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                            BasicText(label, style = text(16.sp, FontWeight.Bold, theme.onAccentColor))
                        }
                    }
                }
            }
        }
    }
}

/** The gift look: a box with a ribbon and a bow, in the theme's colours. */
@Composable
private fun WrappedBox(modifier: Modifier) {
    val theme = LocalTheme.current
    Canvas(modifier) {
        val line = theme.stroke(2.5.dp).toPx()
        val w = size.width
        val h = size.height
        val lidTop = h * 0.30f
        val lidBottom = h * 0.48f
        val ribbon = w * 0.16f
        // Box and lid, with the hard shadow.
        val shadow = 3.dp.toPx()
        drawRect(theme.shadowColor, Offset(w * 0.12f + shadow, lidBottom + shadow), Size(w * 0.76f, h - lidBottom - shadow))
        drawRect(theme.surface, Offset(w * 0.12f, lidBottom), Size(w * 0.76f, h - lidBottom - shadow))
        drawRect(theme.line, Offset(w * 0.12f, lidBottom), Size(w * 0.76f, h - lidBottom - shadow), style = Stroke(line))
        drawRect(theme.surface, Offset(w * 0.05f, lidTop), Size(w * 0.9f, lidBottom - lidTop))
        drawRect(theme.line, Offset(w * 0.05f, lidTop), Size(w * 0.9f, lidBottom - lidTop), style = Stroke(line))
        // The ribbon, down the middle.
        drawRect(theme.accent, Offset((w - ribbon) / 2, lidTop + line / 2), Size(ribbon, h - lidTop - shadow - line))
        drawLine(theme.line, Offset((w - ribbon) / 2, lidTop), Offset((w - ribbon) / 2, h - shadow), line)
        drawLine(theme.line, Offset((w + ribbon) / 2, lidTop), Offset((w + ribbon) / 2, h - shadow), line)
        // The bow: two loops on the lid.
        val bow = h * 0.17f
        drawOval(theme.accent, Offset(w / 2 - bow * 1.9f, lidTop - bow), Size(bow * 1.8f, bow * 1.1f))
        drawOval(theme.line, Offset(w / 2 - bow * 1.9f, lidTop - bow), Size(bow * 1.8f, bow * 1.1f), style = Stroke(line))
        drawOval(theme.accent, Offset(w / 2 + bow * 0.1f, lidTop - bow), Size(bow * 1.8f, bow * 1.1f))
        drawOval(theme.line, Offset(w / 2 + bow * 0.1f, lidTop - bow), Size(bow * 1.8f, bow * 1.1f), style = Stroke(line))
    }
}

/**
 * Several questions in one card (0.6.0): single choice, multiple choice ("Pick any") and free text ("Your answer"),
 * then "Send answers", which sends them together, once. A required question left empty says so. Once answered
 * ([answered]: the user's answer message, or the answers being sent) the card is read-only with the answers.
 */
@Composable
internal fun FormCard(
    block: Block.Form,
    draft: Map<String, Any>,
    answered: FormAnswer?,
    error: String?,
    onChange: (Map<String, Any>) -> Unit,
    onSend: () -> List<String>,
) {
    val theme = LocalTheme.current
    var missing by remember { mutableStateOf(emptyList<String>()) }
    val readOnly = answered != null
    if (block.nodes.isNotEmpty()) MarkdownBubble(block.nodes)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(end = 3.dp, bottom = 3.dp)
            .brutal(theme, fill = theme.teamBubbleFill, shadow = 3.dp, lineWidth = 2.5.dp, cornerRadius = 14.dp)
            .padding(14.dp)
            .testTag("devreply.form"),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        block.questions.forEach { q ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(q.text, style = text(16.sp, FontWeight.Bold, theme.teamBubbleTextColor))
                if (q.kind == Block.Form.Kind.Multi && !readOnly) {
                    BasicText(com.devreply.sdk.t("form.pick_any"), style = text(13.sp, FontWeight.Medium, theme.muted))
                }
                when (q.kind) {
                    Block.Form.Kind.Single, Block.Form.Kind.Multi -> {
                        val picked = if (answered != null) answered.chosen(q.id) else when (val v = draft[q.id]) {
                            is String -> listOf(v)
                            is List<*> -> v.filterIsInstance<String>()
                            else -> emptyList()
                        }
                        q.options.forEach { option ->
                            FormOption(
                                label = option.label,
                                multi = q.kind == Block.Form.Kind.Multi,
                                selected = option.id in picked,
                                readOnly = readOnly,
                                tag = "devreply.form.${q.id}.${option.id}",
                            ) {
                                val next = if (q.kind == Block.Form.Kind.Single) option.id
                                else if (option.id in picked) picked - option.id else picked + option.id
                                onChange(draft + (q.id to next))
                                missing = missing - q.id
                            }
                        }
                    }
                    Block.Form.Kind.Text -> {
                        if (answered != null) {
                            BasicText(
                                answered.text(q.id)?.takeIf { it.isNotBlank() } ?: "—",
                                Modifier.testTag("devreply.form.${q.id}.answer"),
                                style = text(16.sp, FontWeight.Medium, theme.teamBubbleTextColor),
                            )
                        } else {
                            FormTextField((draft[q.id] as? String).orEmpty(), "devreply.form.${q.id}") {
                                onChange(draft + (q.id to it))
                                if (it.isNotBlank()) missing = missing - q.id
                            }
                        }
                    }
                }
                if (!readOnly && q.id in missing) {
                    BasicText(
                        com.devreply.sdk.t("form.required"),
                        Modifier.testTag("devreply.form.${q.id}.required"),
                        style = text(13.sp, FontWeight.Bold, theme.error),
                    )
                }
            }
        }
        if (!readOnly) {
            error?.let { BasicText(it, style = text(13.sp, FontWeight.Bold, theme.error)) }
            val label = com.devreply.sdk.t("form.send")
            BrutalButton(
                { missing = onSend() },
                Modifier.fillMaxWidth().padding(end = 3.dp).testTag("devreply.form.send"),
                fill = theme.accent,
                shadow = 3.dp,
                label = label,
            ) {
                Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
                    BasicText(label, style = text(16.sp, FontWeight.Bold, theme.onAccentColor))
                }
            }
        }
    }
}

/** One option of a form question: a square tick for multiple choice, a round one for single; primary when picked. */
@Composable
private fun FormOption(label: String, multi: Boolean, selected: Boolean, readOnly: Boolean, tag: String, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val quiet = readOnly && !selected
    val shape = if (multi) androidx.compose.foundation.shape.RoundedCornerShape(3.dp) else androidx.compose.foundation.shape.CircleShape
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (quiet) 0.4f else 1f)
            .brutal(theme, fill = if (selected) theme.primary else theme.surface, shadow = 0.dp, lineWidth = 2.dp, cornerRadius = 8.dp)
            .clickable(enabled = !readOnly, role = if (multi) Role.Checkbox else Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val on = if (selected) theme.onHeaderColor else theme.ink
        Box(
            Modifier.size(20.dp).background(theme.surface, shape).border(theme.stroke(2.dp), theme.line, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                if (multi) BasicText("✓", style = text(14.sp, FontWeight.Bold, theme.ink))
                else Box(Modifier.size(10.dp).background(theme.ink, shape))
            }
        }
        BasicText(label, style = text(16.sp, FontWeight.Bold, on))
    }
}

@Composable
private fun FormTextField(value: String, tag: String, onChange: (String) -> Unit) {
    val theme = LocalTheme.current
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.take(com.devreply.sdk.Forms.MAX_TEXT)) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .background(theme.surface)
            .border(theme.stroke(2.5.dp), theme.line)
            .testTag(tag),
        textStyle = text(16.sp, color = theme.ink),
        cursorBrush = SolidColor(theme.ink),
        maxLines = 5,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) BasicText(com.devreply.sdk.t("form.your_answer"), style = text(16.sp, color = theme.muted.copy(alpha = 0.7f)))
                inner()
            }
        },
    )
}

/**
 * Under a promotional message (0.6.0): a small Offer tag and "Don't send me offers"; once tapped, "You won't get
 * offers. Undo". The state is the profile's `no_promotions`.
 */
@Composable
internal fun OfferLine() {
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val optedOut = Messenger.profile?.noPromotions == true
    fun set(value: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { Messenger.setNoPromotions(value) }
            busy = false
        }
    }
    Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Kicker(com.devreply.sdk.t("offer.label"), Modifier.testTag("devreply.offer"), inverted = true)
        if (optedOut) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(com.devreply.sdk.t("offer.opted_out"), style = text(13.sp, FontWeight.Medium, theme.muted))
                BasicText(
                    com.devreply.sdk.t("offer.undo"),
                    Modifier.clickable(enabled = !busy, role = Role.Button) { set(false) }.padding(vertical = 4.dp).testTag("devreply.offer.undo"),
                    style = text(13.sp, FontWeight.Bold).copy(textDecoration = TextDecoration.Underline),
                )
            }
        } else {
            BasicText(
                com.devreply.sdk.t("offer.opt_out"),
                Modifier.clickable(enabled = !busy, role = Role.Button) { set(true) }.padding(vertical = 4.dp).testTag("devreply.offer.optout"),
                style = text(13.sp, FontWeight.Bold, theme.muted).copy(textDecoration = TextDecoration.Underline),
            )
        }
    }
}
