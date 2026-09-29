package com.devreply.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import com.devreply.sdk.ui.Brand
import com.devreply.sdk.ui.DevReplyActivity
import java.util.UUID

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

    /**
     * Call once at launch with the app's Android public key. It is safe to ship inside the app.
     * From an Application, or from the current Activity if you configure later (the unread bubble
     * then shows on it straight away).
     */
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

    /**
     * Opens a DevReply link: the "Reply in the app" button in DevReply's emails opens your app with
     * `yourapp://devreply?devreply=<conversation>`. Pass every link your app receives; this returns
     * `true` and opens that conversation when the link is DevReply's, `false` otherwise (handle it yourself).
     *
     * Set the same deep link in the dashboard (app → Settings → Emails to your users), and add to the
     * activity that opens links, in `AndroidManifest.xml`:
     * ```xml
     * <intent-filter>
     *     <action android:name="android.intent.action.VIEW" />
     *     <category android:name="android.intent.category.DEFAULT" />
     *     <category android:name="android.intent.category.BROWSABLE" />
     *     <data android:scheme="yourapp" android:host="devreply" />
     * </intent-filter>
     * ```
     * ```kotlin
     * override fun onCreate(savedInstanceState: Bundle?) {
     *     super.onCreate(savedInstanceState)
     *     DevReply.handle(this, intent?.data)   // false for any other link (or none)
     * }
     * override fun onNewIntent(intent: Intent) {
     *     super.onNewIntent(intent)
     *     if (DevReply.handle(this, intent.data)) return
     * }
     * ```
     */
    @JvmStatic
    public fun handle(context: Context, uri: Uri?): Boolean {
        if (uri == null) return false
        val raw = runCatching { uri.getQueryParameter("devreply") }.getOrNull() ?: return false
        val id = runCatching { UUID.fromString(raw.trim()) }.getOrNull() ?: return false
        if (Messenger.client == null) {
            android.util.Log.w("DevReply", "DevReply.handle: call DevReply.configure first")
            return true
        }
        Messenger.openFromLink(context, id)
        return true
    }

    /**
     * The chat's language, e.g. `"es"`, `"pt-BR"`, `"de-AT"`. By default (null) it follows the
     * device's languages. Supported: English, Spanish, Portuguese (Brazil), French, German, Italian,
     * Dutch, Polish, Russian, Ukrainian, Turkish, Greek, Japanese, Korean and Chinese (Simplified);
     * anything else shows in English. Open screens switch at once.
     */
    @JvmStatic
    public fun setLocale(tag: String?) {
        Messenger.setLocale(tag)
    }

    /** The language the app chose with [setLocale], or null when the chat follows the device. */
    @JvmStatic
    public val locale: String? get() = L10n.override

    /**
     * Push notifications for replies, like Intercom: your app keeps its own Firebase Cloud Messaging
     * setup and passes DevReply the device's FCM token, at launch and whenever it changes. Upload the
     * Firebase service account in the dashboard (app → Settings → Push notifications (Android)).
     *
     * ```kotlin
     * class MessagingService : FirebaseMessagingService() {
     *     override fun onNewToken(token: String) = DevReply.registerPush(this, token)
     *     override fun onMessageReceived(message: RemoteMessage) {
     *         if (DevReply.handlePush(this, message.data)) return
     *         // your app's own pushes
     *     }
     * }
     * // and once at launch, after configure:
     * FirebaseMessaging.getInstance().token.addOnSuccessListener { DevReply.registerPush(context, it) }
     * ```
     * DevReply never asks for the notification permission on its own before the user writes: the chat
     * offers it after their first message (Android 13+).
     */
    @JvmStatic
    public fun registerPush(context: Context, token: String) {
        PushManager.register(context, token)
    }

    /** Whether this FCM message (`RemoteMessage.data`) is one of DevReply's. */
    @JvmStatic
    public fun isDevReplyPush(data: Map<String, String>): Boolean = PushManager.isDevReplyPush(data)

    /**
     * Shows DevReply's push (a reply from the team) as a notification; a tap opens that conversation.
     * Returns `false` for any other message: handle those yourself. Works while the app is closed too.
     */
    @JvmStatic
    public fun handlePush(context: Context, data: Map<String, String>): Boolean = PushManager.handle(context, data)

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
