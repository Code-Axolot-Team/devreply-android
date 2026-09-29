package com.codeaxolot.devreply.example

import com.devreply.sdk.DevReply
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** The app's own FCM service. DevReply's messages go to the SDK; the app's other pushes stay the app's. */
class MessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        DevReply.registerPush(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (DevReply.handlePush(this, message.data)) return
        // The app's own pushes would be handled here.
    }
}
