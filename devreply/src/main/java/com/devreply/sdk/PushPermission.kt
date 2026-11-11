package com.devreply.sdk

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * The phone's notification permission for this app, as the team sees it next to the user (spec 14, 0.6.0):
 * sent with `PATCH /v1/install {"push_permission": …}` at configure and whenever the app comes back to the
 * foreground, only when it changed since the value this install last sent.
 */
internal enum class PushPermission(val wire: String) {
    Allowed("allowed"), Denied("denied"), NotAsked("not_asked");

    companion object {
        /**
         * - Below Android 13 there's no prompt: [enabled] (`areNotificationsEnabled()`) says allowed or denied.
         * - Android 13+: granted and enabled → allowed; granted but switched off in Settings → denied; not granted
         *   after DevReply asked ([asked]) → denied; never asked → not_asked. (Without the permission
         *   `areNotificationsEnabled()` is false on 13+ too, so it can't tell "denied" from "not asked" by itself.)
         */
        fun of(sdkInt: Int, granted: Boolean, enabled: Boolean, asked: Boolean): PushPermission = when {
            sdkInt < 33 -> if (enabled) Allowed else Denied
            granted -> if (enabled) Allowed else Denied
            asked -> Denied
            else -> NotAsked
        }

        fun current(context: Context): PushPermission {
            val manager = context.getSystemService(NotificationManager::class.java)
            val enabled = manager?.areNotificationsEnabled() == true
            val granted = Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            return of(Build.VERSION.SDK_INT, granted, enabled, PushManager.hasAsked(context))
        }

        /**
         * What's stored after a successful send: the install (a fingerprint of its token, never the token) and the
         * value. A new install (logout, a revoked token) doesn't match, so it gets the value again.
         */
        fun sentMark(token: String, permission: PushPermission): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            return digest.take(8).joinToString("") { "%02x".format(it) } + "|" + permission.wire
        }
    }
}
