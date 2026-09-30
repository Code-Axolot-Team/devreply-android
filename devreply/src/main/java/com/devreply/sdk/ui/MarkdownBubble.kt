package com.devreply.sdk.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.devreply.sdk.Markdown

/** The team's bubble: white (or a step above the page in dark), a thin outline and a small hard shadow. */
internal fun Modifier.teamBubble(theme: Palette): Modifier =
    brutal(theme, fill = theme.teamBubbleFill, shadow = 3.dp, lineWidth = 2.5.dp, cornerRadius = 14.dp)

/**
 * A team or agent reply in DevReply Markdown (spec 05), drawn natively: one selectable column of paragraphs,
 * headings, lists, quotes and code blocks inside the team bubble. Links open in the browser; nothing is HTML.
 */
@Composable
internal fun MarkdownBubble(nodes: List<Markdown.Node>, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val ink = theme.teamBubbleTextColor
    val base = text(17.sp, FontWeight.Medium, ink)
    val look = remember(theme) { MarkdownLook(theme) }
    fun rich(spans: List<Markdown.Span>) = annotated(spans, look) { url -> openLink(context, url) }

    SelectionContainer {
        Column(
            modifier.teamBubble(theme).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (node in nodes) {
                when (node) {
                    is Markdown.Node.Paragraph -> BasicText(rich(node.spans), style = base)
                    // Every level the same: bold, one step larger than the text.
                    is Markdown.Node.Heading -> BasicText(
                        rich(node.spans),
                        style = base.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp),
                    )
                    is Markdown.Node.Quote -> QuoteView(rich(node.spans), base.copy(color = theme.muted), look)
                    is Markdown.Node.ListBlock -> ListView(node, base) { rich(it) }
                    is Markdown.Node.Code -> CodeBlockView(node.text, look)
                }
            }
        }
    }
}

/** The colours Markdown adds to the palette: a tint for code (a step from the bubble toward the text). */
private class MarkdownLook(val theme: Palette) {
    val codeFill: Color = mix(theme.teamBubbleFill, theme.teamBubbleTextColor, if (theme.dark) 0.12f else 0.07f)
    val link = TextLinkStyles(
        style = SpanStyle(color = theme.teamBubbleTextColor, textDecoration = TextDecoration.Underline),
        // Pressed: the brand's highlighter under the link (lemon by default), like buttons on devreply.com.
        pressedStyle = SpanStyle(background = theme.brand, color = theme.onBrand),
    )
}

/** Opens a link in the browser (or the mail app); never crashes the app when nothing can open it. */
private fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun annotated(spans: List<Markdown.Span>, look: MarkdownLook, open: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var k = 0
    while (k < spans.size) {
        val link = spans[k].link
        // Neighbouring spans of one link (e.g. "[see **this**](…)") are one tappable link.
        var end = k + 1
        while (link != null && end < spans.size && spans[end].link == link) end++
        val part = spans.subList(k, end)
        if (link != null) {
            withLink(LinkAnnotation.Url(link, look.link) { open(link) }) { part.forEach { appendSpan(it, look) } }
        } else {
            part.forEach { appendSpan(it, look) }
        }
        k = end
    }
}

private fun AnnotatedString.Builder.appendSpan(span: Markdown.Span, look: MarkdownLook) {
    val style = SpanStyle(
        fontWeight = if (span.bold) FontWeight.Bold else null,
        fontStyle = if (span.italic) FontStyle.Italic else null,
        textDecoration = if (span.strike) TextDecoration.LineThrough else null,
        fontFamily = if (span.code) FontFamily.Monospace else null,
        fontSize = if (span.code) 0.88.em else androidx.compose.ui.unit.TextUnit.Unspecified,
        background = if (span.code) look.codeFill else Color.Unspecified,
    )
    withStyle(style) { append(span.text) }
}

/** A bar on the start side (the right in Hebrew and Arabic), the text in the secondary colour. */
@Composable
private fun QuoteView(text: AnnotatedString, style: TextStyle, look: MarkdownLook) {
    val bar = look.theme.stroke(4.dp)
    val color = look.theme.line
    BasicText(
        text,
        Modifier
            .drawBehind {
                val w = bar.toPx()
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
                drawRect(color, topLeft = Offset(x, 0f), size = Size(w, size.height))
            }
            .padding(start = bar + 10.dp),
        style = style,
    )
}

/** Bullets or numbers in a column of their own, so wrapped lines hang under the item's text. */
@Composable
private fun ListView(node: Markdown.Node.ListBlock, base: TextStyle, rich: (List<Markdown.Span>) -> AnnotatedString) {
    val markers = node.items.indices.map { n -> if (node.ordered) "${node.start + n}." else "•" }
    val markerStyle = base.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val markerWidth = remember(markers, markerStyle, density) {
        with(density) { markers.maxOf { measurer.measure(it, markerStyle).size.width }.toDp() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        node.items.forEachIndexed { n, item ->
            Row {
                BasicText(markers[n], Modifier.width(markerWidth), style = markerStyle)
                Spacer(Modifier.width(8.dp))
                BasicText(rich(item), Modifier.weight(1f, fill = false), style = base)
            }
        }
    }
}

/** Monospace on the code tint with a thin outline; long lines scroll sideways. Code reads left to right. */
@Composable
private fun CodeBlockView(code: String, look: MarkdownLook) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .clip(shape)
            .background(look.codeFill, shape)
            .border(look.theme.stroke(1.5.dp), look.theme.line, shape),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BasicText(
                code,
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = look.theme.teamBubbleTextColor,
                    textDirection = TextDirection.Ltr,
                    textAlign = TextAlign.Left,
                ),
                softWrap = false,
            )
        }
    }
}
