package com.devreply.sdk

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import com.devreply.sdk.ui.Brand
import com.devreply.sdk.ui.DevReplyActivity

/**
 * DevReply: a native chat between your app's users and you (spec 05).
 *
 * ```kotlin
 * DevReply.configure(context, "pk_…")   // once, in Application.onCreate
 * DevReply.present(activity)            // from any button
 * ```
 */
public object DevReply {
    /** The production API. Override only for local development. */
    public const val DEFAULT_API_URL: String = "https://api.devreply.com"

    /** Call once at launch with the app's Android public key. It is safe to ship inside the app. */
    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context, publicKey: String, apiUrl: String = DEFAULT_API_URL) {
        Messenger.configure(context, publicKey, apiUrl)
    }

    /** Tells DevReply who the user is, if your app knows. With a name set, the chat doesn't ask for one. */
    @JvmStatic
    @JvmOverloads
    public fun setUser(name: String?, email: String? = null) {
        Messenger.setUser(name, email)
    }

    /**
     * Custom attributes the team sees next to the user (plan, locale, deck count…). `null` removes one.
     * Text, number or true/false; up to 50 per user.
     *
     * ```kotlin
     * DevReply.setAttributes(mapOf("plan" to "pro", "decks" to 12, "trial" to false))
     * ```
     */
    @JvmStatic
    public fun setAttributes(attributes: Map<String, Any?>) {
        Messenger.setAttributes(attributes)
    }

    /** Colours of the messenger. Set before presenting. */
    @JvmStatic
    public var theme: DevReplyTheme = DevReplyTheme()

    /** Unread replies from the team. Compose state: composables that read it update on their own. */
    @JvmStatic
    public val unreadCount: Int get() = Messenger.unreadCount

    /**
     * While a reply is waiting, a small round DevReply button floats over the app's screens (bottom
     * right) and opens it. On by default; turn it off if your app shows [unreadCount] in its own UI.
     */
    @JvmStatic
    public var showsUnreadBubble: Boolean
        get() = com.devreply.sdk.ui.UnreadBubble.enabled
        set(value) {
            com.devreply.sdk.ui.UnreadBubble.enabled = value
        }

    /** Opens the messenger over the current screen. With a category, it goes straight to a new conversation. */
    @JvmStatic
    @JvmOverloads
    public fun present(context: Context, category: DevReplyCategory? = null) {
        val intent = Intent(context, DevReplyActivity::class.java)
        if (category != null) intent.putExtra(DevReplyActivity.EXTRA_CATEGORY, category.wire)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Fetches unread replies, e.g. when the app returns to the foreground. */
    public suspend fun refresh() {
        Messenger.refresh()
    }
}

/** The messenger's look. Defaults are DevReply's own "Loud" brand. The host app can pass its colours (spec 05). */
public data class DevReplyTheme(
    /** Header and highlight colour. */
    val primary: Color = Brand.lemon,
    /** Buttons that act: send, start. */
    val accent: Color = Brand.pink,
    /** The user's own message bubbles. */
    val userBubble: Color = Brand.cobalt,
    /** Text on [userBubble]. */
    val userBubbleText: Color = Color.White,
    /** Page background. */
    val background: Color = Brand.chalk,
    /** Outlines, shadows, text. */
    val ink: Color = Brand.ink,
)
