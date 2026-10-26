package com.remotecontrol.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenConsentPolicyTest {
    @Test fun acceptsOnlyOwnSystemRecordingPrompts() {
        assertTrue(ScreenConsentPolicy.isOwnPrompt("com.android.systemui", "要开始使用 LinkView 录制或投放吗？", "LinkView"))
        assertTrue(ScreenConsentPolicy.isOwnPrompt("com.android.systemui", "Start recording or casting with LinkView?", "LinkView"))
        assertTrue(ScreenConsentPolicy.isOwnPrompt("com.android.systemui", "Share your screen with LinkView?", "LinkView"))
        assertFalse(ScreenConsentPolicy.isOwnPrompt("other.app", "要开始使用 LinkView 录制或投放吗？", "LinkView"))
        assertFalse(ScreenConsentPolicy.isOwnPrompt("com.android.systemui", "要开始使用 OtherApp 录制或投放吗？", "LinkView"))
        assertFalse(ScreenConsentPolicy.isOwnPrompt("com.android.systemui", "LinkView 请求读取联系人", "LinkView"))
        assertFalse(ScreenConsentPolicy.isOwnPrompt("com.android.systemui", "Start recording?", ""))
    }

    @Test fun neverTreatsExplanatoryTextAsAButtonOrScopeSelection() {
        assertTrue(ScreenConsentPolicy.isEntireScreen("整个屏幕"))
        assertTrue(ScreenConsentPolicy.isEntireScreen("Your entire screen"))
        assertFalse(ScreenConsentPolicy.isEntireScreen("可以访问整个屏幕的内容"))
        assertTrue(ScreenConsentPolicy.isSingleApp("单个应用"))
        assertFalse(ScreenConsentPolicy.isEntireScreen("单个应用"))
        assertTrue(ScreenConsentPolicy.isStart("立即开始"))
        assertTrue(ScreenConsentPolicy.isStart("START NOW"))
        assertTrue(ScreenConsentPolicy.isStart("Share screen"))
        listOf("取消", "Cancel", "允许", "Allow", "下一步", "Next", "Start recording or casting with LinkView?").forEach {
            assertFalse(ScreenConsentPolicy.isStart(it))
        }
    }
}
