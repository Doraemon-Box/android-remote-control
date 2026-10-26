package com.remotecontrol.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.remotecontrol.R
import com.remotecontrol.config.CaptureSettings

/** A short-lived helper for a locally opted-in, pending MediaProjection request. */
internal class ScreenConsentAssistant(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private var lastAction = 0L
    private var selectingScope = false
    private var actions = 0
    private val appLabel = service.getString(R.string.app_name)

    private val poll = object : Runnable {
        override fun run() {
            if (deadline == 0L) return
            if (!CaptureSettings(service).autoConsentEnabled) {
                cancel()
                return
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                cancel()
                Toast.makeText(service, R.string.auto_consent_manual, Toast.LENGTH_LONG).show()
                return
            }
            if (SystemClock.uptimeMillis() - lastAction >= 750 && actions < 6) {
                try {
                    service.rootInActiveWindow?.let { root ->
                        try { handleWindow(root) } finally { root.recycle() }
                    }
                } catch (e: Exception) {
                    Log.w("ScreenConsent", "Consent controls unavailable", e)
                    cancel()
                    Toast.makeText(service, R.string.auto_consent_manual, Toast.LENGTH_LONG).show()
                    return
                }
            }
            if (deadline != 0L) handler.postDelayed(this, 250)
        }
    }

    fun begin() {
        cancel()
        if (!CaptureSettings(service).autoConsentEnabled) return
        deadline = SystemClock.uptimeMillis() + 15_000
        handler.post(poll)
    }

    fun cancel() {
        deadline = 0L
        lastAction = 0L
        selectingScope = false
        actions = 0
        handler.removeCallbacks(poll)
    }

    private fun handleWindow(root: AccessibilityNodeInfo) {
        if (root.packageName?.toString() != "com.android.systemui") return
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collect(root, nodes)
        try {
            val visible = nodes.filter { it.isVisibleToUser }
            val text = visible.joinToString("\n") { it.text?.toString().orEmpty() }
            val ownPrompt = ScreenConsentPolicy.isOwnPrompt("com.android.systemui", text, appLabel)
            // Only a dropdown opened from a verified prompt can be handled without the title.
            if (selectingScope) {
                val fullScreen = visible.firstOrNull { ScreenConsentPolicy.isEntireScreen(it.text?.toString().orEmpty()) }
                if (fullScreen != null && click(fullScreen)) {
                    selectingScope = false
                }
                return
            }
            if (!ownPrompt) return
            val selector = visible.firstOrNull { it.className?.toString() == "android.widget.Spinner" }
            val singleApp = visible.firstOrNull { ScreenConsentPolicy.isSingleApp(it.text?.toString().orEmpty()) }
            if (singleApp != null) {
                if (click(selector ?: singleApp)) selectingScope = true
                return
            }
            // A selector must explicitly show entire-screen mode before confirming.
            if (selector != null && visible.none { ScreenConsentPolicy.isEntireScreen(it.text?.toString().orEmpty()) }) return
            val start = visible.firstOrNull { ScreenConsentPolicy.isStart(it.text?.toString().orEmpty()) }
            if (start != null && click(start)) cancel()
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    private fun collect(node: AccessibilityNodeInfo, nodes: MutableList<AccessibilityNodeInfo>) {
        for (i in 0 until node.childCount) {
            if (nodes.size >= 200) return
            val child = node.getChild(i) ?: continue
            nodes.add(child)
            if (nodes.size < 200) collect(child, nodes)
        }
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        try {
            repeat(4) {
                val current = target ?: return false
                if (current.isVisibleToUser && current.isEnabled && current.isClickable) {
                    val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        Log.d("ScreenConsent", "Clicked recognized screen consent control")
                        actions++
                        lastAction = SystemClock.uptimeMillis()
                    }
                    return clicked
                }
                target = current.parent
                current.recycle()
            }
            return false
        } finally {
            target?.recycle()
        }
    }
}
