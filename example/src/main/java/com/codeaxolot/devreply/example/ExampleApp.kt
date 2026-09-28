package com.codeaxolot.devreply.example

import android.app.Application
import com.devreply.sdk.DevReply

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android public key of the app in the DevReply dashboard. Safe to ship.
        DevReply.configure(this, BuildConfig.DEVREPLY_PK)
        // What the app knows about this user shows up next to them in the dashboard.
        DevReply.setAttributes(mapOf("demo_app" to true, "build" to BuildConfig.VERSION_CODE))
    }
}
