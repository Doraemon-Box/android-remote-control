package com.remotecontrol.service

import java.util.Locale

/** Deliberately narrow vocabulary: unrecognized OEM dialogs remain manual. */
internal object ScreenConsentPolicy {
    private fun normalized(text: String) = text.trim().lowercase(Locale.ROOT)

    fun isOwnPrompt(packageName: String, text: String, appLabel: String): Boolean {
        if (packageName != "com.android.systemui" || appLabel.isBlank()) return false
        if (!text.contains(appLabel, ignoreCase = true)) return false
        val value = normalized(text)
        return listOf("录制", "录屏", "投放", "共享屏幕", "屏幕共享", "recording", "casting", "sharing your screen", "share your screen")
            .any { value.contains(it) }
    }

    fun isEntireScreen(text: String): Boolean = normalized(text) in setOf(
        "整个屏幕", "整个螢幕", "整个萤幕", "整個螢幕", "整個畫面", "entire screen", "your entire screen", "share entire screen", "full screen"
    )

    fun isSingleApp(text: String): Boolean = normalized(text) in setOf(
        "单个应用", "單一應用程式", "单个应用程序", "單個應用", "a single app", "single app", "one app"
    )

    fun isStart(text: String): Boolean = normalized(text) in setOf(
        "开始", "立即开始", "开始录制", "开始共享", "共享屏幕", "分享屏幕", "開始", "立即開始", "start", "start now", "start recording", "start sharing", "share screen"
    )
}
