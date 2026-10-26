package com.remotecontrol.input

import kotlin.math.hypot

/** Recognizes one finger using monotonic event times; timers are owned by the view. */
class TouchGestureTracker {
    data class Gesture(
        val action: String, val x: Float, val y: Float,
        val x2: Float = x, val y2: Float = y, val duration: Long = 0
    )

    companion object {
        const val LONG_PRESS_MS = 500L
        private const val MOVE_THRESHOLD = 0.02f
    }

    private var down: Gesture? = null
    private var downTime = 0L
    private var moved = false
    private var sentLongPress = false

    fun down(x: Float, y: Float, time: Long) {
        down = Gesture("tap", x, y)
        downTime = time
        moved = false
        sentLongPress = false
    }

    fun move(x: Float, y: Float) {
        val start = down ?: return
        if (hypot(x - start.x, y - start.y) >= MOVE_THRESHOLD) moved = true
    }

    fun longPress(time: Long): Gesture? {
        val start = down ?: return null
        if (moved || sentLongPress || time - downTime < LONG_PRESS_MS) return null
        sentLongPress = true
        return start.copy(action = "long_press", duration = LONG_PRESS_MS)
    }

    fun up(x: Float, y: Float, time: Long): Gesture? {
        val start = down ?: return null
        move(x, y)
        val result = when {
            sentLongPress -> null
            moved -> start.copy(action = "swipe", x2 = x, y2 = y,
                duration = (time - downTime).coerceIn(80L, 250L))
            time - downTime >= LONG_PRESS_MS -> start.copy(action = "long_press", duration = LONG_PRESS_MS)
            else -> start
        }
        cancel()
        return result
    }

    fun cancel() { down = null }
}
