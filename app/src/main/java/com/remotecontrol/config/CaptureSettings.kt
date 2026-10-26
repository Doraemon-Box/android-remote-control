package com.remotecontrol.config

import android.content.Context

class CaptureSettings(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("capture_settings", Context.MODE_PRIVATE)

    var autoConsentEnabled: Boolean
        get() = preferences.getBoolean("auto_consent", false)
        set(value) { preferences.edit().putBoolean("auto_consent", value).apply() }
}
