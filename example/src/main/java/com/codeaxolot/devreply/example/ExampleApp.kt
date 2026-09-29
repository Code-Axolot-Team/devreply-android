package com.codeaxolot.devreply.example

import android.app.Application
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.devreply.sdk.DevReply
import com.devreply.sdk.DevReplyTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Dark for this demo only with -Pdevreply.night=yes; otherwise follow the phone again.
            getSystemService(android.app.UiModeManager::class.java).setApplicationNightMode(
                if (BuildConfig.DEVREPLY_NIGHT) android.app.UiModeManager.MODE_NIGHT_YES else android.app.UiModeManager.MODE_NIGHT_AUTO,
            )
        }
        // Android public key of the app in the DevReply dashboard. Safe to ship.
        DevReply.configure(this, BuildConfig.DEVREPLY_PK, BuildConfig.DEVREPLY_API)
        // What the app knows about this user shows up next to them in the dashboard.
        DevReply.setAttributes(mapOf("demo_app" to true, "build" to BuildConfig.VERSION_CODE))
        // DevReply's own dark look when the phone is in dark mode (without it, the chat stays light).
        DevReply.darkTheme = DevReplyTheme.Dark
        // Your own colours (-Pdevreply.theme=custom): DevReply derives the rest of the chat from them.
        if (BuildConfig.DEVREPLY_CUSTOM_THEME) {
            DevReply.theme = DevReplyTheme(primary = Color(0xFF0A84FF), accent = Color(0xFFFF9F0A))
        }
        // What happens in the chat, for analytics (here: Logcat, tag DevReplyDemo).
        DevReply.addEventListener { Log.i("DevReplyDemo", "event: $it") }
        // Pushes for replies: pass DevReply the FCM token (only when the build has Firebase set up).
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { DevReply.registerPush(this, it) }
        }
    }
}
