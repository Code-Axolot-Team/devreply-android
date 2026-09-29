# DevReply for Android

The in-app chat between your app's users and you: a native "message the developer" screen in Kotlin +
Jetpack Compose, answered from the [DevReply dashboard](https://app.devreply.com). No third-party dependencies
(AndroidX Compose and kotlinx.coroutines only).

- Home with start buttons (bug, billing, idea, question), the user's conversations, and the chat.
- Photos and files, name first, optional email, "we got it" with your reply time, a long drag hides the keyboard.
- An unread bubble over your app's screens. Your colours, or DevReply's own look.
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
    implementation("com.github.Code-Axolot-Team:devreply-android:0.4.0")
}
```

Using a coding agent? Give it your app's setup guide from the dashboard (Settings → Add DevReply to your app):
it has your keys and does these steps for you.

## Use

```kotlin
import com.devreply.sdk.DevReply

// Once, in Application.onCreate (your Android public key from the dashboard; it's safe to ship)
DevReply.configure(this, "pk_…")

// From any button
DevReply.present(context)             // or present(context, DevReplyCategory.Bug)

// Optional
DevReply.setUser("Ana", "ana@example.com")
DevReply.setAttributes(mapOf("plan" to "pro", "trial" to false))
DevReply.showsUnreadBubble = false    // if you show DevReply.unreadCount (Compose state) yourself
DevReply.setLocale("es")              // your app's own language setting; null follows the device
```

Each reply shows who wrote it (the teammate's name, title and photo) and the header shows your app icon. The chat
speaks the device's language (15 languages: English, Spanish, Portuguese, French, German, Italian, Dutch, Polish,
Russian, Ukrainian, Turkish, Greek, Japanese, Korean, Chinese).

Never put a secret key (`sk_…`) in an app.

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

Then upload the Firebase service account in the dashboard (app → Settings → Push notifications (Android)):
Firebase console → Project settings → Service accounts → Generate new private key. To use your own
notification icon, add a white-on-transparent drawable named `devreply_push_icon`.

## Build, example app and tests

```sh
./gradlew :devreply:testDebugUnitTest                      # JVM model tests
./gradlew :example:installDebug -Pdevreply.pk=pk_…         # demo app on a connected phone
./gradlew :example:installDebug :example:installDebugAndroidTest -Pdevreply.pk=pk_…
adb shell am instrument -w -e class com.codeaxolot.devreply.example.MessengerUiTest \
  com.codeaxolot.devreply.example.test/androidx.test.runner.AndroidJUnitRunner
```

`ChatFlowsTest` (notice, email ask, keyboard, unread bubble) runs against a throwaway app: build with its
`-Pdevreply.pk`, `adb shell pm clear com.codeaxolot.devreply.example`, then instrument with `-e nonce <n>`
while a script replies "Founder reply <n>" and, ~45 s later, "Founder reply <n> again" from the dashboard API.

Needs JDK 17 and `local.properties` with `sdk.dir`. Fonts: Archivo Black and Space Grotesk, SIL OFL (`devreply/FONTS-OFL.txt`).

## License

MIT, see [LICENSE](LICENSE). Issues and ideas welcome: see [CONTRIBUTING](CONTRIBUTING.md).
