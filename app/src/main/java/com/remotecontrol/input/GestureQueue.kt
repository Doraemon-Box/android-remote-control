package com.remotecontrol.input

/** Serializes asynchronous injection without keeping a backlog of stale operations. Main thread only. */
class GestureQueue<T>(
    private val now: () -> Long,
    private val execute: (T, () -> Unit) -> Unit
) {
    private data class Pending<T>(val value: T, val time: Long)
    private val pending = ArrayDeque<Pending<T>>()
    private var active = false
    private var generation = 0

    fun submit(value: T) {
        if (pending.size >= 8) pending.removeFirst()
        pending.addLast(Pending(value, now()))
        drain()
    }

    /** Invalidates old completion callbacks as well as queued operations. */
    fun clear() {
        generation++
        pending.clear()
        active = false
    }

    private fun drain() {
        if (active) return
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (now() - next.time > 1000L) continue
            active = true
            val token = generation
            var completed = false
            execute(next.value) {
                if (!completed && token == generation) {
                    completed = true
                    active = false
                    drain()
                }
            }
            return
        }
    }
}
