package com.remotecontrol.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/** Keeps the display awake only for an incoming, active controlled session. */
internal class RemoteScreenWakeController(context: Context) {
    private val powerManager = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private val renew = object : Runnable {
        override fun run() {
            val lock = wakeLock ?: return
            try {
                lock.acquire(60_000L)
                handler.postDelayed(this, 30_000L)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to keep remote session screen awake", e)
                stop()
            }
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        if (wakeLock != null) return
        try {
            // Activity.setTurnScreenOn only applies to a visible Activity. A remote
            // request can arrive while this Activity is stopped in the background.
            val lock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "LinkView::RemoteScreenWakeLock"
            )
            lock.setReferenceCounted(false)
            wakeLock = lock
            lock.acquire(60_000L)
            handler.postDelayed(renew, 30_000L)
            Log.d(TAG, "Remote screen wake requested; interactive=${powerManager.isInteractive}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to wake screen for remote session", e)
            stop()
        }
    }

    fun stop() {
        handler.removeCallbacks(renew)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private companion object {
        const val TAG = "RemoteScreenWake"
    }
}
