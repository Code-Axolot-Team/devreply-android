package com.codeaxolot.devreply.example

import android.app.Application
import com.devreply.sdk.DevReply
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android public key of the app in the DevReply dashboard. Safe to ship.
        DevReply.configure(this, BuildConfig.DEVREPLY_PK, BuildConfig.DEVREPLY_API)
        // What the app knows about this user shows up next to them in the dashboard.
        DevReply.setAttributes(mapOf("demo_app" to true, "build" to BuildConfig.VERSION_CODE))
        // Pushes for replies: pass DevReply the FCM token (only when the build has Firebase set up).
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { DevReply.registerPush(this, it) }
        }
    }
}
