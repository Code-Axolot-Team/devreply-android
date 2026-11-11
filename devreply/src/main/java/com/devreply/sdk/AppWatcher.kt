package com.devreply.sdk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import com.devreply.sdk.ui.DevReplyActivity
import com.devreply.sdk.ui.UnreadBubble
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Follows the host app's activities (spec 05):
 * - back in the app: fetch unread replies (and retry a pending user deletion); while it's open and the messenger closed, check every 30 s;
 * - on each of the app's own screens: the unread bubble.
 */
internal object AppWatcher : Application.ActivityLifecycleCallbacks {
    private var started = false
    private var resumed = 0
    /** The app's activities between onStart and onStop: 0 → 1 is the app coming to the foreground. */
    private var startedCount = 0
    private var lastRefresh = 0L
    private var polling: Job? = null

    /**
     * [current]: the screen already on display when configure runs late (React Native and Flutter
     * configure from their own code, after the activity resumed). It gets the bubble straight away.
     */
    fun start(app: Application, current: Activity? = null) {
        if (started) return
        started = true
        app.registerActivityLifecycleCallbacks(this)
        if (current != null && !current.isFinishing) {
            startedCount = 1
            onActivityResumed(current)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        resumed++
        if (activity is DevReplyActivity) return
        UnreadBubble.attach(activity)
        if (SystemClock.elapsedRealtime() - lastRefresh > 5_000) refresh()
        if (polling?.isActive != true) {
            polling = Messenger.scope.launch {
                while (true) {
                    delay(30_000)
                    if (resumed > 0 && !Messenger.isPresented && Messenger.conversations.isNotEmpty()) refresh()
                }
            }
        }
    }

    override fun onActivityPaused(activity: Activity) {
        resumed = maxOf(0, resumed - 1)
        if (resumed == 0) {
            polling?.cancel()
            polling = null
        }
    }

    private fun refresh() {
        lastRefresh = SystemClock.elapsedRealtime()
        Messenger.scope.launch {
            // A user deletion that couldn't reach the server before: try again now the app is back.
            Messenger.retryPendingDeletions()
            Messenger.refresh()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) {
        // Back in the foreground (the process's lifecycle, without a dependency): the user may have changed the
        // notification permission in Settings meanwhile (0.6.0).
        if (startedCount++ == 0) Messenger.scope.launch { Messenger.syncPushPermission() }
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount = maxOf(0, startedCount - 1)
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
