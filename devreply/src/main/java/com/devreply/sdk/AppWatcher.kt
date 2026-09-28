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
 * - back in the app: fetch unread replies; while it's open and the messenger closed, check every 30 s;
 * - on each of the app's own screens: the unread bubble.
 */
internal object AppWatcher : Application.ActivityLifecycleCallbacks {
    private var started = false
    private var resumed = 0
    private var lastRefresh = 0L
    private var polling: Job? = null

    fun start(app: Application) {
        if (started) return
        started = true
        app.registerActivityLifecycleCallbacks(this)
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
        Messenger.scope.launch { Messenger.refresh() }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
