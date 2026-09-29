package com.devreply.sdk.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devreply.sdk.DevReply
import com.devreply.sdk.DevReplyCategory
import com.devreply.sdk.DevReplyError
import com.devreply.sdk.R

/** DevReply brand colours, the same as devreply.com and the iOS SDK. */
public object Brand {
    public val lemon: Color = Color(0xFFF6EB37)
    public val pink: Color = Color(0xFFFF5FA2)
    public val cobalt: Color = Color(0xFF2B50E0)
    public val ink: Color = Color(0xFF111111)
    public val chalk: Color = Color(0xFFFFFDF2)
    public val grey: Color = Color(0xFFE9E6D8)
    public val muted: Color = Color(0xFF4A4740)
    public val online: Color = Color(0xFF1FB85A)
    internal val error: Color = Color(0xFFB32619)
    internal val resolved: Color = Color(0xFFCFF2E3)
}

// Fonts: Archivo Black for headlines, Space Grotesk for everything else. Sizes in sp follow the user's font scale.
internal val DisplayFont = FontFamily(Font(R.font.devreply_archivo_black))
internal val TextFont = FontFamily(
    Font(R.font.devreply_space_grotesk_regular, FontWeight.Normal),
    Font(R.font.devreply_space_grotesk_medium, FontWeight.Medium),
    Font(R.font.devreply_space_grotesk_bold, FontWeight.Bold),
)

internal fun display(size: TextUnit, color: Color = DevReply.theme.ink) =
    TextStyle(fontFamily = DisplayFont, fontSize = size, color = color, lineHeight = size * 1.1f)

internal fun text(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = DevReply.theme.ink) =
    TextStyle(fontFamily = TextFont, fontWeight = weight, fontSize = size, color = color, lineHeight = size * 1.3f)

// Category icons (same artwork as iOS) and wording.

@get:DrawableRes
internal val DevReplyCategory.icon: Int
    get() = when (this) {
        DevReplyCategory.Bug -> R.drawable.devreply_category_bug
        DevReplyCategory.Billing -> R.drawable.devreply_category_billing
        DevReplyCategory.Idea -> R.drawable.devreply_category_idea
        DevReplyCategory.Question -> R.drawable.devreply_category_question
        DevReplyCategory.Other -> R.drawable.devreply_category_other
    }

internal val DevReplyCategory.defaultTitle: String get() = com.devreply.sdk.t("category.$wire")

internal val DevReplyCategory.prompt: String get() = com.devreply.sdk.t("prompt.$wire")

// The brutal look: ink outline + hard offset shadow.

internal fun Modifier.brutal(
    fill: Color = Color.White,
    shadow: Dp = 5.dp,
    lineWidth: Dp = 3.dp,
    cornerRadius: Dp = 0.dp,
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .drawBehind {
            if (shadow > 0.dp) {
                val o = shadow.toPx()
                drawRoundRect(
                    color = DevReply.theme.ink,
                    topLeft = Offset(o, o),
                    size = size,
                    cornerRadius = CornerRadius(cornerRadius.toPx()),
                )
            }
        }
        .background(fill, shape)
        .border(lineWidth, DevReply.theme.ink, shape)
}

/** Pressing pushes the element into its shadow, like `.btn:active` on devreply.com. */
@Composable
internal fun BrutalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Color.White,
    shadow: Dp = 5.dp,
    enabled: Boolean = true,
    label: String? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shift by animateDpAsState(if (pressed) shadow - 1.dp else 0.dp, tween(80), label = "press")
    Box(
        modifier
            .offset(shift, shift)
            .brutal(fill = fill, shadow = shadow - shift)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
    ) { content() }
}

/** Small uppercase label, like the site's `.kicker`. */
@Composable
internal fun Kicker(text: String, modifier: Modifier = Modifier, inverted: Boolean = false) {
    BasicText(
        text.uppercase(),
        modifier
            .then(if (inverted) Modifier.background(Brand.ink).padding(horizontal = 8.dp, vertical = 4.dp) else Modifier),
        style = text(12.sp, FontWeight.Bold, if (inverted) Brand.lemon else Brand.ink).copy(letterSpacing = 1.2.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Square icon button with an ink outline (close, back, attach). */
@Composable
internal fun IconSquareButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Color.White,
    size: Dp = 40.dp,
) {
    BrutalButton(onClick, modifier, fill = fill, shadow = 3.dp, label = label) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Image(
                painterResource(icon), null, Modifier.size(size * 0.5f),
                colorFilter = ColorFilter.tint(DevReply.theme.ink),
            )
        }
    }
}

/** The team's avatar: initials on a square, ink outline. */
@Composable
internal fun TeamAvatar(
    name: String,
    size: Dp,
    fill: Color = Color.White,
    imageUrl: String? = null,
    lineWidth: Dp = 2.5.dp,
    modifier: Modifier = Modifier,
) {
    // A photo or the app's icon when there is one (loaded once, kept in memory), initials meanwhile
    // and as the fallback.
    val image by produceState(imageUrl?.let { ImageCache.cached(it) }, imageUrl) {
        value = imageUrl?.let { ImageCache.image(it) }
    }
    val initials = name.split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }
    Box(
        modifier.size(size).background(fill).border(lineWidth, Brand.ink),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap, name,
                Modifier.size(size).padding(lineWidth),
                contentScale = ContentScale.Crop,
            )
        } else {
            BasicText(
                initials.ifEmpty { "DR" }.uppercase(),
                style = display((size.value * 0.4f).sp, Brand.ink).copy(lineHeight = (size.value * 0.4f).sp),
            )
        }
    }
}

@Composable
internal fun ErrorNote(error: DevReplyError, retry: () -> Unit) {
    val message = when (error) {
        DevReplyError.Network -> com.devreply.sdk.t("error.offline")
        DevReplyError.InvalidPublicKey -> com.devreply.sdk.t("error.key")
        else -> com.devreply.sdk.t("error.generic")
    }
    Column(
        Modifier.fillMaxWidth().brutal(shadow = 0.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(message, style = text(15.sp, FontWeight.Medium))
        BrutalButton(retry, fill = Brand.pink, shadow = 4.dp) {
            BasicText(
                com.devreply.sdk.t("try_again"),
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp).sizeIn(minHeight = 20.dp),
                style = text(15.sp, FontWeight.Bold),
            )
        }
    }
}
