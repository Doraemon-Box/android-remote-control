package com.remotecontrol.config

import org.junit.Assert.*
import org.junit.Test

class CaptureAuthorizationRequestsTest {
    @Test fun cancelledRequestDoesNotBlockTheNextSession() {
        val requests = CaptureAuthorizationRequests()
        val old = requests.begin("old-room")
        assertEquals(old, requests.cancel())
        assertNull(requests.pending)
        val next = requests.begin("next-room")
        assertNotEquals(old.code, next.code)
        assertEquals(next, requests.pending)
    }

    @Test fun lateResultCannotClearOrAuthorizeANewerRequest() {
        val requests = CaptureAuthorizationRequests()
        val old = requests.begin("old-room")
        requests.cancel()
        val next = requests.begin("next-room")
        assertNull(requests.complete(old.code))
        assertEquals(next, requests.pending)
        assertEquals(next, requests.complete(next.code))
        assertNull(requests.pending)
        assertNull(requests.complete(next.code))
    }

    @Test fun repeatedCancellationAndReconnectionDoNotLeavePendingState() {
        val requests = CaptureAuthorizationRequests()
        repeat(100) { i ->
            val request = requests.begin("room-$i")
            if (i % 2 == 0) requests.cancel()
            else assertEquals(request, requests.complete(request.code))
            assertNull(requests.pending)
        }
    }

    @Test fun recreatedActivityDoesNotReuseAnOldRequestCode() {
        val old = CaptureAuthorizationRequests().begin("old-room")
        val recreated = CaptureAuthorizationRequests()
        val next = recreated.begin("next-room")
        assertNotEquals(old.code, next.code)
        assertNull(recreated.complete(old.code))
        assertEquals(next, recreated.pending)
    }
}
