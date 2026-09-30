package com.devreply.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import com.devreply.sdk.ui.Brand
import com.devreply.sdk.ui.DevReplyActivity
import kotlinx.coroutines.launch
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

    /**
     * After your user signs in: your own id for them (never an email or anything secret). The team sees
     * it next to the user, and your backend can delete the user by it. If another user was signed in on
     * this device, DevReply logs them out first, so nobody sees someone else's chats.
     */
    @JvmStatic
    public fun login(userId: String) {
        Messenger.login(userId)
    }

    /**
     * When your user signs out: DevReply forgets this device's chats, and the next person starts empty.
     * Their conversations stay with your team. Call it on every sign-out (and account switch).
     */
    @JvmStatic
    public fun logout() {
        Messenger.logout()
    }

    /**
     * When your user deletes their account: deletes their name, email, attributes, conversations,
     * messages and files from DevReply, and this device forgets the user (like [logout]).
     *
     * Returns `true` when the server deleted the user now. Returns `false` when DevReply couldn't be
     * reached (offline, or the server answered 5xx/429): the device has forgotten the user all the same,
     * and DevReply keeps the deletion queued (with the old install's token, encrypted on the device) and
     * retries it at every [configure] and whenever the app comes back to the foreground, until the server
     * confirms. Nothing for your app to do; your backend can also delete the user
     * (`DELETE /v1/project/users?user_id=…` with a secret key). Also `false`, with nothing forgotten, when
     * DevReply isn't configured or the server refused the request for another reason.
     */
    public suspend fun deleteUser(): Boolean = Messenger.deleteUser()

    /**
     * [deleteUser] for Java and callbacks: [done] runs on the main thread with the result
     * (`true` deleted now, `false` queued; see [deleteUser]).
     */
    @JvmStatic
    public fun deleteUser(done: (Boolean) -> Unit) {
        Messenger.scope.launch { done(Messenger.deleteUser()) }
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

    /** Colours of the messenger (and of its dark look, unless you set [darkTheme]). Set before presenting. */
    @JvmStatic
    public var theme: DevReplyTheme = DevReplyTheme()

    /**
     * Colours for dark appearance, or `null` (the default): the messenger stays light, as before 0.4.4.
     * When set, the messenger (and the unread bubble) use it whenever the configuration is in night mode:
     * the system's dark theme, or your app's own choice (`AppCompatDelegate.setDefaultNightMode`).
     * [DevReplyTheme.Dark] is DevReply's own dark look:
     *
     * ```kotlin
     * DevReply.darkTheme = DevReplyTheme.Dark
     * ```
     */
    @JvmStatic
    public var darkTheme: DevReplyTheme? = null

    /**
     * Every colour for this appearance: derived from [darkTheme] in night mode when the app set one, from
     * [theme] otherwise (without a dark theme, the chat stays light, as before 0.4.4).
     */
    internal fun paletteFor(night: Boolean): com.devreply.sdk.ui.Palette {
        val dark = darkTheme
        return if (night && dark != null) com.devreply.sdk.ui.Palette.dark(dark) else com.devreply.sdk.ui.Palette.light(theme)
    }

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

    /**
     * Opens the messenger over the current screen. With a [category], it goes straight to a new
     * conversation; without one, the user picks a start button first.
     *
     * - [message] prefills the composer of the new conversation this opens (it isn't sent: the user sees
     *   it and can edit it first). Not applied to existing conversations.
     * - [attributes] are the context of that new conversation, shown to your team next to it (the screen
     *   the user came from, an order id…): text, number or true/false, up to 20, names of 1–40 letters,
     *   digits, `_ - .` or space. Sent with the first conversation the user starts from here, then
     *   dropped (also when the messenger closes). Values the server would refuse are left out.
     * - [askName] `false` skips "Before we start" (the name form) while this messenger is open, e.g. from a
     *   failed purchase, where one tap to the message matters more than a name. The team sees the user unnamed.
     *
     * ```kotlin
     * DevReply.present(context, DevReplyCategory.Bug, "Export fails: ", mapOf("screen" to "export", "items" to 3))
     * ```
     *
     * Returns `false` and shows nothing when DevReply isn't configured or your team switched the chat off
     * in the dashboard ([isAvailable]); `true` when the messenger opened.
     */
    @JvmStatic
    @JvmOverloads
    public fun present(
        context: Context,
        category: DevReplyCategory? = null,
        message: String? = null,
        attributes: Map<String, Any> = emptyMap(),
        askName: Boolean = true,
    ): Boolean {
        if (Messenger.client == null) {
            android.util.Log.w("DevReply", "DevReply.present: call DevReply.configure first")
            return false
        }
        if (!Messenger.isAvailable) return false
        Messenger.startPresentation(message, attributes, askName)
        val intent = Intent(context, DevReplyActivity::class.java)
        if (category != null) intent.putExtra(DevReplyActivity.EXTRA_CATEGORY, category.wire)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    /**
     * Whether the chat can open: DevReply is configured and your team hasn't switched it off in the
     * dashboard (read from the config DevReply fetches and caches; `true` until the first one arrives).
     * When it's `false`, [present] does nothing, the unread bubble hides and DevReply's notifications
     * aren't shown; hide your own "Message us" button too. Login, logout, attributes and pushes keep working.
     * Compose state: composables that read it update on their own.
     */
    @JvmStatic
    public val isAvailable: Boolean get() = Messenger.isAvailable

    /**
     * Events for your analytics: the messenger opened or closed, a conversation started, a message sent.
     * [listener] is called on the main thread; add as many as you like, and [DevReplySubscription.cancel]
     * when done.
     *
     * ```kotlin
     * DevReply.addEventListener { event ->
     *     when (event) {
     *         is DevReplyEvent.ConversationStarted -> analytics.log("support_started", event.category?.name)
     *         else -> Unit
     *     }
     * }
     * ```
     */
    @JvmStatic
    public fun addEventListener(listener: (DevReplyEvent) -> Unit): DevReplySubscription = Events.hub.add(listener)

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

    /**
     * A DevReply notification the user tapped, when your app's push library showed it (`data` as that
     * library passes it on): opens the conversation. Returns false for your own notifications. Not
     * needed for the notifications [handlePush] shows: those open the conversation by themselves.
     */
    @JvmStatic
    public fun handleNotificationOpened(context: Context, data: Map<String, String>): Boolean = PushManager.open(context, data)

    /** Fetches unread replies, e.g. when the app returns to the foreground. */
    public suspend fun refresh() {
        Messenger.refresh()
    }
}

/**
 * The messenger's colours. Defaults are DevReply's own "Loud" brand; [Dark] is DevReply's dark look. Set them
 * for light ([DevReply.theme]) and for dark ([DevReply.darkTheme]) separately; DevReply works out every other
 * colour (cards, outlines, shadows, text on buttons) from these six and the mode, so the chat keeps its look.
 *
 * ```kotlin
 * DevReply.theme = DevReplyTheme(primary = Color(0xFF0A84FF), accent = Color(0xFFFF9F0A))
 * DevReply.darkTheme = DevReplyTheme.Dark.copy(primary = Color(0xFF6A3FD8))
 * ```
 */
public data class DevReplyTheme(
    /** The header and highlight colour. In dark, text on it is black or white, whichever reads. */
    val primary: Color = Brand.lemon,
    /** Buttons that act: send, start, save; the unread badges. */
    val accent: Color = Brand.pink,
    /** The user's own message bubbles. */
    val userBubble: Color = Brand.cobalt,
    /** Text on [userBubble]. */
    val userBubbleText: Color = Color.White,
    /** The page. In dark, cards are a step lighter than it. */
    val background: Color = Brand.chalk,
    /** Text and icons; outlines and shadows in light, the thin outlines in dark. */
    val ink: Color = Brand.ink,
) {
    public companion object {
        /** DevReply's dark look, "Deep blue": a navy page, a cobalt header, pink buttons. */
        @JvmField
        public val Dark: DevReplyTheme = DevReplyTheme(
            primary = Color(0xFF2B50E0),
            accent = Brand.pink,
            userBubble = Color(0xFF3F6BFF),
            userBubbleText = Color.White,
            background = Color(0xFF0E1320),
            ink = Color(0xFFEEF1F8),
        )
    }
}
