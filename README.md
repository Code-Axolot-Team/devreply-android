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
    implementation("com.github.Code-Axolot-Team:devreply-android:0.3.0")
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
```

Never put a secret key (`sk_…`) in an app.

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
