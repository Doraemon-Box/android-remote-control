package com.remotecontrol.input

import org.junit.Assert.*
import org.junit.Test

class TouchGestureTrackerTest {
    @Test fun stationaryPressUnderThresholdIsATapIncludingOldDeadZone() {
        for (duration in listOf(40L, 299L, 350L, 499L)) {
            val tracker = TouchGestureTracker()
            tracker.down(.5f, .5f, 1000L)
            assertEquals("tap", tracker.up(.5f, .5f, 1000L + duration)?.action)
        }
    }

    @Test fun longPressIsEmittedBeforeReleaseAndNeverDuplicated() {
        val tracker = TouchGestureTracker()
        tracker.down(.5f, .5f, 1000L)
        assertNull(tracker.longPress(1499L))
        assertEquals("long_press", tracker.longPress(1500L)?.action)
        assertNull(tracker.longPress(1800L))
        assertNull(tracker.up(.5f, .5f, 5000L))
    }

    @Test fun delayedTimerStillProducesExactlyOneLongPress() {
        val tracker = TouchGestureTracker()
        tracker.down(.5f, .5f, 1000L)
        assertEquals(500L, tracker.up(.5f, .5f, 3000L)?.duration)
        assertNull(tracker.longPress(3001L))
    }

    @Test fun movementOutAndBackIsNotAStationaryPress() {
        val tracker = TouchGestureTracker()
        tracker.down(.5f, .5f, 1000L)
        tracker.move(.8f, .5f)
        tracker.move(.5f, .5f)
        assertNull(tracker.longPress(1600L))
        assertEquals("swipe", tracker.up(.6f, .5f, 1800L)?.action)
    }

    @Test fun swipeReplayIsBoundedButKeepsEndpoints() {
        for ((elapsed, expected) in listOf(30L to 80L, 150L to 150L, 3000L to 250L)) {
            val tracker = TouchGestureTracker()
            tracker.down(.2f, .8f, 1000L)
            val swipe = tracker.up(.2f, .1f, 1000L + elapsed)!!
            assertEquals("swipe", swipe.action)
            assertEquals(expected, swipe.duration)
            assertEquals(.8f, swipe.y, 0f)
            assertEquals(.1f, swipe.y2, 0f)
        }
    }

    @Test fun cancellationAndNewGestureCannotSendOldLongPress() {
        val tracker = TouchGestureTracker()
        tracker.down(.5f, .5f, 1000L)
        tracker.cancel()
        assertNull(tracker.longPress(1600L))
        assertNull(tracker.up(.5f, .5f, 1700L))
        tracker.down(.2f, .2f, 2000L)
        assertEquals("tap", tracker.up(.2f, .2f, 2050L)?.action)
    }
}
