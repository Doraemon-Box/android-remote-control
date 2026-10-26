package com.remotecontrol.config

import java.util.concurrent.atomic.AtomicInteger

/** Matches each system consent result to the session that actually requested it. */
internal class CaptureAuthorizationRequests {
    data class Request(val code: Int, val roomId: String)

    var pending: Request? = null
        private set

    private companion object {
        // Do not reuse a code if MainActivity is recreated within the same process.
        val nextCode = AtomicInteger(1001)
    }

    fun begin(roomId: String): Request {
        check(pending == null) { "Screen consent already pending" }
        // Android reserves the high 16 bits of Activity request codes.
        val code = nextCode.getAndIncrement()
        check(code <= 65535) { "Screen consent request codes exhausted" }
        return Request(code, roomId).also { pending = it }
    }

    fun complete(code: Int): Request? {
        val request = pending?.takeIf { it.code == code } ?: return null
        pending = null
        return request
    }

    fun cancel(): Request? = pending.also { pending = null }
}
