package com.devreply.sdk

import android.content.Context
import android.os.Build
import org.json.JSONObject

/** What an install tells the server about itself. No identifiers, no user data. */
internal data class DeviceInfo(val deviceModel: String, val osVersion: String, val appVersion: String) {
    fun json(): JSONObject = JSONObject()
        .put("platform", "android")
        .put("device_model", deviceModel.take(100))
        .put("os_version", osVersion)
        .put("app_version", appVersion.take(100))
        .put("sdk_version", DEVREPLY_SDK_VERSION)

    companion object {
        fun current(context: Context): DeviceInfo {
            val app = runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
                "${info.versionName ?: "?"} ($code)"
            }.getOrDefault("?")
            // e.g. "Samsung SM-S918B"
            val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
            return DeviceInfo(model, Build.VERSION.RELEASE, app)
        }
    }
}
