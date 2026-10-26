package com.remotecontrol.input

import org.junit.Assert.*
import org.junit.Test

class GestureQueueTest {
    @Test fun serializesRapidGesturesAndIgnoresDuplicateCompletion() {
        val seen = mutableListOf<Int>()
        val completions = mutableListOf<() -> Unit>()
        val queue = GestureQueue<Int>({ 0L }) { value, done ->
            seen.add(value)
            completions.add(done)
        }
        queue.submit(1)
        queue.submit(2)
        queue.submit(3)
        assertEquals(listOf(1), seen)
        completions[0]()
        completions[0]()
        assertEquals(listOf(1, 2), seen)
        completions[1]()
        assertEquals(listOf(1, 2, 3), seen)
    }

    @Test fun dropsExpiredAndExcessBacklog() {
        var time = 0L
        val seen = mutableListOf<Int>()
        var complete: () -> Unit = {}
        val queue = GestureQueue<Int>({ time }) { value, done -> seen.add(value); complete = done }
        queue.submit(0)
        (1..10).forEach(queue::submit)
        complete()
        assertEquals(listOf(0, 3), seen)
        time = 1001L
        complete()
        assertEquals(listOf(0, 3), seen)
        queue.submit(11)
        assertEquals(listOf(0, 3, 11), seen)
    }

    @Test fun sessionResetInvalidatesPendingActionsAndOldCallbacks() {
        val seen = mutableListOf<Int>()
        val completions = mutableListOf<() -> Unit>()
        val queue = GestureQueue<Int>({ 0L }) { value, done -> seen.add(value); completions.add(done) }
        queue.submit(1)
        queue.submit(2)
        queue.clear()
        queue.submit(3)
        queue.submit(4)
        completions[0]()
        assertEquals(listOf(1, 3), seen)
        completions[1]()
        assertEquals(listOf(1, 3, 4), seen)
    }
}
