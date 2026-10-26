package com.remotecontrol.config

import org.junit.Assert.*
import org.junit.Test

class VideoStreamProfileTest {
    @Test fun connectedPhoneGetsEvenDimensionsWithoutUpscaling() {
        assertEquals(VideoStreamProfile.Size(540, 1202), VideoStreamProfile.captureSize(720, 1604))
    }

    @Test fun highResolutionAndLandscapeHaveTheSameBoundedEncoderWork() {
        val portrait = VideoStreamProfile.captureSize(1440, 3200)
        assertEquals(VideoStreamProfile.Size(540, 1200), portrait)
        assertEquals(VideoStreamProfile.Size(portrait.height, portrait.width),
            VideoStreamProfile.captureSize(3200, 1440))
    }

    @Test fun veryTallAndSmallScreensStayBoundedAndKeepTheirAspectRatio() {
        for ((width, height) in listOf(1080 to 3000, 480 to 800, 1080 to 2400)) {
            val size = VideoStreamProfile.captureSize(width, height)
            assertTrue(minOf(size.width, size.height) <= 540)
            assertTrue(maxOf(size.width, size.height) <= 1280)
            assertTrue(size.width <= width && size.height <= height)
            assertEquals(0, size.width % 2)
            assertEquals(0, size.height % 2)
            assertEquals(width.toDouble() / height, size.width.toDouble() / size.height, .005)
        }
    }
}
