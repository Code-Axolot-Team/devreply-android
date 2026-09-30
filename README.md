# DevReply for Android

The in-app chat between your app's users and you: a native "message the developer" screen in Kotlin +
Jetpack Compose, answered from the [DevReply dashboard](https://app.devreply.com). No third-party dependencies
(AndroidX Compose and kotlinx.coroutines only).

- Home with start buttons (bug, billing, idea, question), the user's conversations, and the chat.
- Photos and files, name first, optional email, "we got it" with your reply time, a long drag hides the keyboard.
- An unread bubble over your app's screens. Your colours, or DevReply's own look, light and (opt-in) dark.
- Forward compatible: a newer server never breaks an app already shipped.

Requires minSdk 26, Kotlin 2 and Compose.

## Install

From GitHub through [JitPack](https://jitpack.io/#Code-Axolot-Team/devreply-android):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.Code-Axolot-Team:devreply-android:0.5.0")
}
```

Using a coding agent? Give it your app's setup guide from the dashboard (Settings → Add DevReply to your app):
it has your keys and does these steps for you.

## Use

```kotlin
import com.devreply.sdk.DevReply

// Once, in Application.onCreate (your Android public key from the dashboard; it's safe to ship)
DevReply.configure(this, "pk_…")

// From any button (false when not configured or the chat is switched off in the dashboard)
DevReply.present(context)             // or present(context, DevReplyCategory.Bug)

// Optional
DevReply.setUser("Ana", "ana@example.com")
DevReply.setAttributes(mapOf("plan" to "pro", "trial" to false))
DevReply.showsUnreadBubble = false    // if you show DevReply.unreadCount (Compose state) yourself
DevReply.setLocale("es")              // your app's own language setting; null follows the device
```

Each reply shows who wrote it (the teammate's name, title and photo) and the header shows your app icon. The chat
speaks the device's language: 34 languages, the same set as iOS, including Hebrew and Arabic, which lay
out right to left (English otherwise).

Never put a secret key (`sk_…`) in an app.

### Start a conversation with a message and context

```kotlin
DevReply.present(
    context, DevReplyCategory.Bug,
    message = "Export failed: ",                                      // in the composer, not sent
    attributes = mapOf("screen" to "export", "order_id" to 1042),    // shown to your team with the conversation
)
```

`message` prefills the composer of the new conversation (the user can edit it before sending). `attributes`
are that conversation's context: text, number or true/false, up to 20, names of 1–40 letters, digits,
`_ - .` or space. They go with the first conversation started from this `present`, then are dropped.
`askName = false` skips "Before we start" (the name form) while that messenger is open, for a screen where one
tap to the message matters more than a name, like a failed purchase. The email ask after the first message stays.

### Switched off in the dashboard

Your team can switch the chat off in the dashboard. Then `DevReply.isAvailable` is `false` (Compose state):
`present` returns `false`, the unread bubble hides, DevReply's notifications aren't shown and an open chat
closes. Hide your own "Message us" button with it:

```kotlin
if (DevReply.isAvailable) Button(onClick = { DevReply.present(context) }) { Text("Message us") }
```

### Dark mode

Off by default: the chat stays light. Opt in with DevReply's dark look, or your own colours:

```kotlin
DevReply.darkTheme = DevReplyTheme.Dark                                     // DevReply's "Deep blue"
DevReply.theme = DevReplyTheme(primary = Color(0xFF0A84FF), accent = Color(0xFFFF9F0A))
DevReply.darkTheme = DevReplyTheme.Dark.copy(primary = Color(0xFF6A3FD8))  // tweak the preset
```

The dark theme is used whenever the configuration is in night mode (the system's dark theme, or your app's
`AppCompatDelegate.setDefaultNightMode`). Six colours, for light and dark separately: `primary` (header and
highlights), `accent` (buttons that act, unread badges), `userBubble`, `userBubbleText`, `background` (the page)
and `ink` (text, icons and outlines). DevReply derives every other colour from them and the mode: in light,
exactly the look above; in dark, cards a step lighter than the page, thin light outlines over dark shadows, black
or white text on the header and buttons (whichever reads), DevReply's lemon for the small brand touches and its
own dark category icons. The attach menu, the photo viewer, the unread bubble, text selection and DevReply's
notification colour follow the theme too. Line widths, shadows, fonts and square corners are DevReply's look.

### Events for analytics

```kotlin
val subscription = DevReply.addEventListener { event ->
    when (event) {
        DevReplyEvent.MessengerOpened -> analytics.log("support_opened")
        DevReplyEvent.MessengerClosed -> analytics.log("support_closed")
        is DevReplyEvent.ConversationStarted -> analytics.log("support_started", event.category?.name)
        is DevReplyEvent.MessageSent -> analytics.log("support_message")
    }
}
subscription.cancel()   // when you no longer want them
```

Called on the main thread. The first message of a conversation sends `ConversationStarted`, then `MessageSent`.

### Open the chat from DevReply's emails (deep link)

Users who gave their email get your replies by email, with a "Reply in the app" button. It opens
`yourapp://devreply?devreply=<conversation>`; pass the link to DevReply and the chat opens on that
conversation. Set the same deep link in the dashboard (app → Settings → Emails to your users); it shows
"✓ Working" once your app has opened one.

```xml
<!-- AndroidManifest.xml, on the activity that should open (launchMode singleTop recommended) -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="yourapp" android:host="devreply" />
</intent-filter>
```

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    intent?.data?.let { DevReply.handle(this, it) }   // true when it was DevReply's link
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.data?.let { DevReply.handle(this, it) }
}
```

Who replied (the teammate's name, title and photo), your app icon and your team's faces come from the
dashboard; nothing to set up in the app.

### Push notifications

Like Intercom: your app keeps its own Firebase Cloud Messaging setup and passes DevReply the token and
DevReply's messages. DevReply shows its own notification (who replied and the reply, on a "Replies" channel);
a tap opens the conversation. The chat asks for the notification permission (Android 13+) only after the
user's first message, and "Not now" hides the ask for 3 days. The SDK itself doesn't depend on Firebase.

```kotlin
class MessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = DevReply.registerPush(this, token)

    override fun onMessageReceived(message: RemoteMessage) {
        if (DevReply.handlePush(this, message.data)) return
        // your app's own pushes
    }
}

// Once at launch, after DevReply.configure:
FirebaseMessaging.getInstance().token.addOnSuccessListener { DevReply.registerPush(context, it) }
```

Then upload the Firebase service account in the dashboard (app → Settings → Push notifications (Android)),
or let your coding agent do it with DevReply's MCP tool `set_android_push_key`:
Firebase console → Project settings → Service accounts → Generate new private key. To use your own
notification icon, add a white-on-transparent drawable named `devreply_push_icon`.

If your app's own code or a library (notifee…) shows DevReply's notifications instead of `handlePush`, pass the
tapped notification's data on: `DevReply.handleNotificationOpened(context, data)` (false for your own). The
dashboard's push card shows "✓ Taps open the chat" once a tap opened a conversation.

## Sign-in, sign-out and account deletion

If your app has accounts:

```kotlin
DevReply.login(account.id)             // after sign-in: your own id for the user, never an email or a secret
DevReply.logout()                      // on every sign-out and account switch
val ok = DevReply.deleteUser()         // suspend, in your delete-account flow; true = deleted now, false = queued
DevReply.deleteUser { ok -> }          // the same with a callback (Java)
```

- `login` labels the user for your team (the dashboard shows it as "User ID (your app)") and lets your backend
  delete them by it. It doesn't merge chats across devices: the id isn't verified, so it never gives one device
  another's conversations. If another id was signed in on this device, DevReply logs out first.
- `logout` revokes this install and its push token; the device forgets the chat and the next person starts empty.
  The conversations stay with your team.
- `deleteUser` deletes the user's name, email, attributes, conversations, messages and files, and the device
  forgets the user. If DevReply can't be reached (offline, server error), the device forgets the user anyway and
  DevReply retries the deletion by itself at every `configure` and whenever the app comes back, until the server
  confirms (`false` means queued, not failed).

Your backend can delete a user too, with a read-and-write secret key (never in an app):

```sh
curl -X DELETE "https://api.devreply.com/v1/project/users?user_id=<your id>" \
  -H "Authorization: Bearer $DEVREPLY_SECRET_KEY"
# {"deleted": 1}: every DevReply user with that id, on every device. ?id=<DevReply's user id> for one user.
```

Your team can also delete a user in the dashboard (the inbox's user panel → Delete user).

## Build, example app and tests

```sh
./gradlew :devreply:testDebugUnitTest                      # JVM model tests
./gradlew :example:installDebug -Pdevreply.pk=pk_…         # demo app on a connected phone
./gradlew :example:installDebug :example:installDebugAndroidTest -Pdevreply.pk=pk_…
adb shell am instrument -w -e class com.codeaxolot.devreply.example.MessengerUiTest \
  com.codeaxolot.devreply.example.test/androidx.test.runner.AndroidJUnitRunner
```

`PresentTest` checks the demo's "Report a bug" link: `present` with a prefilled message, nothing sent.

`ChatFlowsTest` (notice, email ask, keyboard, unread bubble) runs against a throwaway app: build with its
`-Pdevreply.pk`, `adb shell pm clear com.codeaxolot.devreply.example`, then instrument with `-e nonce <n>`
while a script replies "Founder reply <n>" and, ~45 s later, "Founder reply <n> again" from the dashboard API.

Needs JDK 17 and `local.properties` with `sdk.dir`. Fonts: Archivo Black and Space Grotesk, SIL OFL (`devreply/FONTS-OFL.txt`).

## License

MIT, see [LICENSE](LICENSE). Issues and ideas welcome: see [CONTRIBUTING](CONTRIBUTING.md).
