package com.devreply.sdk.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import com.devreply.sdk.DevReplyTheme

/**
 * Every colour the messenger draws, derived from the app's six theme colours ([DevReplyTheme]) and the mode
 * (light, or dark with [com.devreply.sdk.DevReply.darkTheme]). The derivation, line widths and shadows are
 * DevReply's, not the app's. Light is exactly the 0.4.3 look.
 */
internal data class Palette(
    /** The dark look (the app set a dark theme and the configuration is in night mode). */
    val dark: Boolean,
    val primary: Color,
    val accent: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val background: Color,
    /** Text and icons on [background] and [surface]. */
    val ink: Color,
    /** Cards, start buttons, conversation rows, fields, the composer, the attach menu. */
    val surface: Color,
    val muted: Color,
    val error: Color,
    /** A photo while it loads. */
    val subtle: Color,
    /** Borders, dividers, the illustrations' lines. */
    val line: Color,
    val shadowColor: Color,
    /** Multiplies every border and divider width. */
    val outlineWidth: Float,
    val headerFill: Color,
    val onHeaderColor: Color,
    val onAccentColor: Color,
    /** The name, email and notifications cards. */
    val cardFill: Color,
    val onCardColor: Color,
    val teamBubbleFill: Color,
    val teamBubbleTextColor: Color,
    val noticeFill: Color,
    /** The small brand touches: the team-name tag, avatar tiles, the unread bubble, file tiles. */
    val brand: Color,
    val onBrand: Color,
    /** The online dot. */
    val success: Color,
    /** The "Resolved" tag (text [onBrand]). */
    val resolved: Color,
) {
    companion object {
        private val black = Color(0xFF111111)
        private val white = Color.White

        /** DevReply's light look, in the app's colours: exactly 0.4.3. */
        fun light(t: DevReplyTheme) = Palette(
            dark = false,
            primary = t.primary, accent = t.accent, userBubble = t.userBubble, userBubbleText = t.userBubbleText,
            background = t.background, ink = t.ink,
            surface = Color.White, muted = Brand.muted, error = Brand.error, subtle = Brand.grey,
            line = t.ink, shadowColor = t.ink, outlineWidth = 1f,
            headerFill = t.primary, onHeaderColor = t.ink, onAccentColor = t.ink,
            cardFill = t.primary, onCardColor = t.ink,
            teamBubbleFill = Color.White, teamBubbleTextColor = t.ink, noticeFill = Color.White,
            brand = t.primary, onBrand = t.ink,
            success = Brand.online, resolved = Brand.resolved,
        )

        /**
         * The dark look for any dark colours: cards a step lighter than the page, thin light outlines over
         * shadows darker than the page, text on the header and buttons in black or white (whichever reads),
         * and DevReply's lemon for the small brand touches.
         */
        fun dark(t: DevReplyTheme): Palette {
            // Cards a step above the page, tinted toward the header colour (half ink, half primary).
            val raised = mix(t.background, mix(t.ink, t.primary, 0.5f), 0.08f)
            return Palette(
                dark = true,
                primary = t.primary, accent = t.accent, userBubble = t.userBubble, userBubbleText = t.userBubbleText,
                background = t.background, ink = t.ink,
                surface = raised, muted = mix(t.ink, t.background, 0.35f), error = Color(0xFFFF8A7E),
                subtle = mix(t.background, t.ink, 0.14f),
                line = t.ink, shadowColor = mix(t.background, Color.Black, 0.6f), outlineWidth = 0.5f,
                headerFill = t.primary, onHeaderColor = readableOn(t.primary), onAccentColor = readableOn(t.accent),
                cardFill = raised, onCardColor = t.ink,
                teamBubbleFill = raised, teamBubbleTextColor = t.ink, noticeFill = raised,
                brand = Brand.lemon, onBrand = black,
                success = Brand.online, resolved = Brand.resolved,
            )
        }

        /** #111111 or white, whichever contrasts more with [fill] (WCAG contrast ratio). */
        fun readableOn(fill: Color): Color {
            val l = fill.luminance()
            fun ratio(other: Color): Float {
                val o = other.luminance()
                return (maxOf(l, o) + 0.05f) / (minOf(l, o) + 0.05f)
            }
            return if (ratio(black) >= ratio(white)) black else white
        }
    }
}

/** [a] moved [t] of the way to [b], channel by channel in sRGB (like CSS color-mix in srgb, same as iOS and web). */
internal fun mix(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

/** A border or divider width, scaled by [Palette.outlineWidth] (1 = the Loud widths). */
internal fun Palette.stroke(width: Dp): Dp = width * outlineWidth
