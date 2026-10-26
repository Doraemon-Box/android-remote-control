package com.remotecontrol.config

import kotlin.math.max
import kotlin.math.min

/** Bounds encoder work for interactive screen sharing, independent of display resolution. */
object VideoStreamProfile {
    const val FPS = 20
    const val MIN_BITRATE_BPS = 100_000
    const val MAX_BITRATE_BPS = 1_200_000

    data class Size(val width: Int, val height: Int)

    fun captureSize(width: Int, height: Int): Size {
        require(width > 0 && height > 0)
        val scale = min(0.75, min(540.0 / min(width, height), 1280.0 / max(width, height)))
        fun even(value: Int) = ((value * scale).toInt() / 2 * 2).coerceAtLeast(2)
        return Size(even(width), even(height))
    }
}
