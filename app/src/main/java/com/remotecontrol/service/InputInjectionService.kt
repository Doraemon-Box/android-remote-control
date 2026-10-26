package com.remotecontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.JsonObject

/**
 * AccessibilityService that receives remote touch events and
 * dispatches them as gestures on the controlled device.
 *
 * Supports: tap, swipe, long press, pinch, back, home, recents
 */
class InputInjectionService : AccessibilityService() {
    private var consentAssistant: ScreenConsentAssistant? = null

    companion object {
        private const val TAG = "InputInjection"
        var instance: InputInjectionService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        consentAssistant = ScreenConsentAssistant(this)
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // ScreenConsentAssistant polls only during an explicitly armed local request.
    }

    fun beginScreenConsent() { consentAssistant?.begin() }

    fun cancelScreenConsent() { consentAssistant?.cancel() }

    override fun onInterrupt() {
        cancelScreenConsent()
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        cancelScreenConsent()
        consentAssistant = null
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    /**
     * Dispatch a remote input event.
     * Event JSON format:
     * { "action": "tap|swipe|long_press|back|home|recents|pinch",
     *   "x": float, "y": float,
     *   "x2": float, "y2": float,  // for swipe end point
     *   "duration": long,           // gesture duration in ms
     *   "screenWidth": int,         // remote screen dimensions for coordinate mapping
     *   "screenHeight": int }
     */
    fun dispatchRemoteEvent(event: JsonObject) {
        val action = event.get("action")?.asString ?: return

        when (action) {
            "tap" -> {
                val x = event.get("x").asFloat
                val y = event.get("y").asFloat
                performTap(x, y)
            }
            "long_press" -> {
                val x = event.get("x").asFloat
                val y = event.get("y").asFloat
                val duration = event.get("duration")?.asLong ?: 1000L
                performLongPress(x, y, duration)
            }
            "swipe" -> {
                val x1 = event.get("x").asFloat
                val y1 = event.get("y").asFloat
                val x2 = event.get("x2").asFloat
                val y2 = event.get("y2").asFloat
                val duration = event.get("duration")?.asLong ?: 300L
                performSwipe(x1, y1, x2, y2, duration)
            }
            "pinch" -> {
                // Pinch requires API 26+ multi-stroke
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val centerX = event.get("x").asFloat
                    val centerY = event.get("y").asFloat
                    val scale = event.get("scale")?.asFloat ?: 1.0f
                    performPinch(centerX, centerY, scale)
                }
            }
            "type_text" -> {
                val text = event.get("text")?.asString ?: return
                performTypeText(text)
            }
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            else -> Log.w(TAG, "Unknown action: $action")
        }
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun performLongPress(x: Float, y: Float, duration: Long) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun performPinch(centerX: Float, centerY: Float, scale: Float) {
        val offset = 200f
        val builder = GestureDescription.Builder()

        if (scale > 1.0f) {
            // Pinch out (zoom in)
            val path1 = Path().apply {
                moveTo(centerX, centerY)
                lineTo(centerX - offset, centerY - offset)
            }
            val path2 = Path().apply {
                moveTo(centerX, centerY)
                lineTo(centerX + offset, centerY + offset)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path1, 0, 500))
            builder.addStroke(GestureDescription.StrokeDescription(path2, 0, 500))
        } else {
            // Pinch in (zoom out)
            val path1 = Path().apply {
                moveTo(centerX - offset, centerY - offset)
                lineTo(centerX, centerY)
            }
            val path2 = Path().apply {
                moveTo(centerX + offset, centerY + offset)
                lineTo(centerX, centerY)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path1, 0, 500))
            builder.addStroke(GestureDescription.StrokeDescription(path2, 0, 500))
        }

        dispatchGesture(builder.build(), null, null)
    }

    /**
     * Type text into the currently focused input field.
     * Finds the focused EditText via accessibility tree and sets its text.
     */
    private fun performTypeText(text: String) {
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "No active window for text input")
            return
        }
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focusedNode.recycle()
        } else {
            // No focused input — try clipboard paste as fallback
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("remote_text", text)
            clipboard.setPrimaryClip(clip)
            // Find any editable node and paste
            pasteToFirstEditable(rootNode)
        }
        rootNode.recycle()
    }

    private fun pasteToFirstEditable(node: AccessibilityNodeInfo) {
        if (node.isEditable) {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            return
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            pasteToFirstEditable(child)
            child.recycle()
        }
    }
}
