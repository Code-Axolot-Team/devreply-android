package com.devreply.sdk

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.devreply.sdk.ui.DevReplyActivity
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Push notifications for replies (spec 05), the way Intercom does it on Android: the app keeps its own
 * Firebase Cloud Messaging setup and forwards two things, the token ([DevReply.registerPush]) and
 * DevReply's messages ([DevReply.handlePush]). DevReply's pushes are data messages; this shows them.
 *
 * Never forced: the chat offers "Turn on" after the user's first message (Android 13+ asks for the
 * permission), "Open Settings" if they're off, and "Not now" hides it for 3 days. Only when the app
 * forwards a token: without one, no push can arrive, so the chat doesn't ask.
 */
internal object PushManager {
    private const val CHANNEL = "devreply_replies"
    private const val SNOOZE_MS = 3L * 24 * 60 * 60 * 1000
    private const val KEY = "com.devreply.sdk.push"

    /** The latest token from the app, and the one the server has. */
    private var token: String? = null
    private var sentToken: String? = null

    /** Compose state, so the chat's ask card updates when the permission or the snooze changes. */
    private var askVersion by mutableStateOf(0)

    fun register(context: Context, token: String) {
        val clean = token.trim().takeIf { it.isNotEmpty() } ?: return
        this.token = clean
        prefs(context).edit().putString("token", clean).apply()
        askVersion++
        if (Messenger.client != null) Messenger.scope.launch { sendTokenIfNeeded() }
    }

    /** After a logout the device gets a new install: send the token to it again. */
    fun forgetSentToken() {
        sentToken = null
    }

    /** Sends the token once the install exists; called again after `configure`. */
    suspend fun sendTokenIfNeeded() {
        val token = token ?: Messenger.context?.let { prefs(it).getString("token", null) }?.also { token = it } ?: return
        if (token == sentToken) return
        runCatching { Messenger.authorized { api, t -> api.updatePushToken(t, token) } }
            .onSuccess { sentToken = token }
    }

    // ---- showing DevReply's pushes ----

    fun isDevReplyPush(data: Map<String, String>): Boolean = conversationId(data) != null

    private fun conversationId(data: Map<String, String>): UUID? =
        data["devreply_conversation_id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    fun handle(context: Context, data: Map<String, String>): Boolean {
        val id = conversationId(data) ?: return false
        val app = context.applicationContext
        // Unread counts and the bubble catch up (when the app is running and configured).
        if (Messenger.client != null) Messenger.scope.launch { Messenger.refresh() }
        // The team switched the chat off: still DevReply's message, but nothing to show.
        if (Messenger.switchedOff(app)) return true
        // The user is reading that conversation right now: it's on screen already.
        if (Messenger.isPresented && Messenger.visibleConversation == id) return true
        if (!notificationsAllowed(app)) return true
        val manager = app.getSystemService(NotificationManager::class.java) ?: return true
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, t("push.channel"), NotificationManager.IMPORTANCE_HIGH),
        )
        val title = data["devreply_title"]?.takeIf { it.isNotBlank() }
            ?: app.applicationInfo.loadLabel(app.packageManager).toString()
        val body = data["devreply_body"].orEmpty()
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.devreply_push_icon)
            // The icon and app name in the theme's accent (the dark theme's when the app is in night mode).
            .setColor(DevReply.paletteFor(isNight(app)).accent.toArgb())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openIntent(app, id))
            .build()
        runCatching { manager.notify(KEY, id.hashCode(), notification) }
        return true
    }

    /**
     * A tap opens the app, then the conversation on top of it: back goes to the app, and React Native
     * and Flutter apps start their own code as usual. The messenger configures itself from the last
     * key if the app hasn't yet.
     */
    private fun openIntent(context: Context, id: UUID): PendingIntent {
        val chat = Intent(context, DevReplyActivity::class.java)
            .putExtra(DevReplyActivity.EXTRA_CONVERSATION, id.toString())
            .putExtra(DevReplyActivity.EXTRA_FROM_PUSH, true)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val intents = if (launch != null) {
            arrayOf(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), chat)
        } else {
            arrayOf(chat.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return PendingIntent.getActivities(
            context, id.hashCode(), intents, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * A DevReply notification was tapped where the app handles taps itself (its push library showed it):
     * opens the conversation. DevReply's own notifications open it by themselves.
     */
    fun open(context: Context, data: Map<String, String>): Boolean {
        val id = conversationId(data) ?: return false
        val intent = Intent(context, DevReplyActivity::class.java)
            .putExtra(DevReplyActivity.EXTRA_CONVERSATION, id.toString())
            .putExtra(DevReplyActivity.EXTRA_FROM_PUSH, true)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        return true
    }

    private var reportedOpen = false

    /** Once per launch: taps reach the conversation (Settings and the setup status show it). */
    fun reportOpened() {
        if (reportedOpen || Messenger.client == null) return
        reportedOpen = true
        Messenger.scope.launch { runCatching { Messenger.authorized { api, t -> api.pushOpened(t) } } }
    }

    /** A reply for this conversation is on screen: clear its notification. */
    fun clear(context: Context, id: UUID) {
        context.getSystemService(NotificationManager::class.java)?.cancel(KEY, id.hashCode())
    }

    // ---- the ask in the chat ----

    enum class AskState { FirstAsk, OpenSettings }

    /** What the chat offers now, or null (no token from the app, allowed already, or "Not now" recently). */
    fun askState(context: Context): AskState? {
        askVersion // read: Compose re-checks when it changes
        val prefs = prefs(context)
        if (token == null && prefs.getString("token", null) == null) return null
        if (System.currentTimeMillis() < prefs.getLong("snoozed_until", 0)) return null
        if (notificationsAllowed(context)) return null
        // Android 13+ asks once in the app; after a no (or before 13), only Settings can turn them on.
        val canAsk = Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked", false)
        return if (canAsk) AskState.FirstAsk else AskState.OpenSettings
    }

    fun asked(context: Context) {
        prefs(context).edit().putBoolean("asked", true).apply()
        askVersion++
    }

    fun notNow(context: Context) {
        prefs(context).edit().putLong("snoozed_until", System.currentTimeMillis() + SNOOZE_MS).apply()
        askVersion++
    }

    /** Back from Settings or the permission dialog. */
    fun recheck() {
        askVersion++
    }

    fun openSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        runCatching { activity.startActivity(intent) }
    }

    fun notificationsAllowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val channel = manager.getNotificationChannel(CHANNEL)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun isNight(context: Context) =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("devreply-push", Context.MODE_PRIVATE)
}
