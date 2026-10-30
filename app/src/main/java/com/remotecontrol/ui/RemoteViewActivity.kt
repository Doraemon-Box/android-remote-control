package com.remotecontrol.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.gson.JsonObject
import com.remotecontrol.databinding.ActivityRemoteViewBinding
import com.remotecontrol.input.TouchGestureTracker
import com.remotecontrol.network.SignalingClient
import com.remotecontrol.network.WebRTCManager
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

/**
 * Full-screen activity for the controller to see and interact with
 * the remote device's screen via WebRTC video and touch forwarding.
 */
class RemoteViewActivity : AppCompatActivity(), WebRTCManager.WebRTCListener {

    companion object {
        private const val TAG = "RemoteViewActivity"
        private const val REMOTE_CONTENT_SCALE = 0.9f
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_IS_CONTROLLER = "is_controller"
    }

    private lateinit var binding: ActivityRemoteViewBinding
    private var signalingClient: SignalingClient? = null
    private var webRTCManager: WebRTCManager? = null
    private var roomId: String? = null
    private var isActive = true

    private val gestureTracker = TouchGestureTracker()
    private val longPressRunnable = Runnable {
        if (isActive) gestureTracker.longPress(SystemClock.uptimeMillis())?.let(::sendGesture)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideNavigationBar()
        binding = ActivityRemoteViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val width = (view.width * REMOTE_CONTENT_SCALE).toInt()
            val height = (view.height * REMOTE_CONTENT_SCALE).toInt()
            val params = binding.remoteContent.layoutParams as FrameLayout.LayoutParams
            if (params.width != width || params.height != height) {
                params.width = width
                params.height = height
                binding.remoteContent.layoutParams = params
            }
        }

        roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        if (roomId == null) {
            finish()
            return
        }

        signalingClient = RemoteControlHolder.signalingClient
        webRTCManager = RemoteControlHolder.webRTCManager

        if (signalingClient == null || webRTCManager == null) {
            finish()
            return
        }

        // The manager is created by MainActivity, but controller callbacks belong
        // to this screen while the remote session is visible.
        webRTCManager?.setListener(this)

        // Initialize video renderer
        try {
            val eglBase = webRTCManager?.getEglBase()
            if (eglBase != null) {
                binding.remoteVideoView.init(eglBase.eglBaseContext, null)
                binding.remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                binding.remoteVideoView.setEnableHardwareScaler(false)
                binding.remoteVideoView.setMirror(false)
            } else {
                Log.e(TAG, "EglBase is null, cannot init video view")
                finish()
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init video renderer", e)
            finish()
            return
        }

        // Start as controller
        webRTCManager?.startAsController(roomId!!, binding.remoteVideoView)

        setupTouchOverlay()
        setupToolbar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    private fun hideNavigationBar() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchOverlay() {
        binding.touchOverlay.setOnTouchListener { view, event ->
            if (!isActive) return@setOnTouchListener true

            val viewWidth = view.width.toFloat()
            val viewHeight = view.height.toFloat()
            if (viewWidth == 0f || viewHeight == 0f) return@setOnTouchListener true

            val normX = (event.x / viewWidth).coerceIn(0f, 1f)
            val normY = (event.y / viewHeight).coerceIn(0f, 1f)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureTracker.down(normX, normY, event.eventTime)
                    view.removeCallbacks(longPressRunnable)
                    view.postDelayed(longPressRunnable, TouchGestureTracker.LONG_PRESS_MS)
                    showToolbar()
                }
                MotionEvent.ACTION_MOVE -> {
                    // Include batched samples so moving out and back cannot become a long press.
                    for (i in 0 until event.historySize) {
                        gestureTracker.move(
                            (event.getHistoricalX(i) / viewWidth).coerceIn(0f, 1f),
                            (event.getHistoricalY(i) / viewHeight).coerceIn(0f, 1f)
                        )
                    }
                    gestureTracker.move(normX, normY)
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPressRunnable)
                    gestureTracker.up(normX, normY, event.eventTime)?.let(::sendGesture)
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelTouchGesture()
            }
            true
        }
    }

    private fun sendGesture(gesture: TouchGestureTracker.Gesture) {
        sendInputEvent(JsonObject().apply {
            addProperty("action", gesture.action)
            addProperty("x", gesture.x)
            addProperty("y", gesture.y)
            if (gesture.action == "swipe") {
                addProperty("x2", gesture.x2)
                addProperty("y2", gesture.y2)
            }
            if (gesture.duration > 0) addProperty("duration", gesture.duration)
        })
    }

    private fun cancelTouchGesture() {
        binding.touchOverlay.removeCallbacks(longPressRunnable)
        gestureTracker.cancel()
    }

    override fun onPause() {
        cancelTouchGesture()
        super.onPause()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            sendInputEvent(JsonObject().apply { addProperty("action", "back") })
        }
        binding.btnHome.setOnClickListener {
            sendInputEvent(JsonObject().apply { addProperty("action", "home") })
        }
        binding.btnKeyboard.setOnClickListener {
            showTextInputDialog()
        }
        binding.btnDisconnectSession.setOnClickListener {
            disconnect()
        }

        // Auto-hide toolbar after 3 seconds
        scheduleHideToolbar()
    }

    private fun showToolbar() {
        binding.toolbar.animate().alpha(0.9f).setDuration(150).start()
        scheduleHideToolbar()
    }

    private fun scheduleHideToolbar() {
        binding.toolbar.removeCallbacks(hideToolbarRunnable)
        binding.toolbar.postDelayed(hideToolbarRunnable, 3000)
    }

    private val hideToolbarRunnable = Runnable {
        if (!isFinishing) {
            binding.toolbar.animate().alpha(0.2f).setDuration(500).start()
        }
    }

    private fun showTextInputDialog() {
        val editText = EditText(this).apply {
            hint = "输入要发送的文字"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("远程输入文字")
            .setView(editText)
            .setPositiveButton("发送") { _, _ ->
                val text = editText.text.toString()
                if (text.isNotEmpty()) {
                    sendInputEvent(JsonObject().apply {
                        addProperty("action", "type_text")
                        addProperty("text", text)
                    })
                }
            }
            .setNegativeButton("取消", null)
            .show()
        editText.requestFocus()
    }

    private fun sendInputEvent(event: JsonObject) {
        try {
            roomId?.let { signalingClient?.sendInputEvent(it, event) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send input event", e)
        }
    }

    private fun disconnect() {
        isActive = false
        roomId?.let { signalingClient?.disconnectRoom(it) }
        finish()
    }

    // ========== WebRTCListener ==========

    override fun onPeerConnected() {
        runOnUiThread {
            binding.tvConnectionInfo.text = "已连接"
            binding.tvConnectionInfo.visibility = View.VISIBLE
            binding.tvConnectionInfo.postDelayed({
                if (!isFinishing) {
                    binding.tvConnectionInfo.animate().alpha(0f).setDuration(1000).start()
                }
            }, 3000)
        }
    }

    override fun onPeerDisconnected() {
        runOnUiThread {
            binding.tvConnectionInfo.alpha = 1f
            binding.tvConnectionInfo.visibility = View.VISIBLE
            binding.tvConnectionInfo.text = "连接断开"
        }
    }

    override fun onRemoteVideoTrackReceived(track: VideoTrack) {
        runOnUiThread {
            binding.tvConnectionInfo.text = "正在接收画面"
            binding.remoteVideoView.post {
                Log.d(
                    TAG,
                    "Video renderer bounds: ${binding.remoteVideoView.width}x${binding.remoteVideoView.height}, touch bounds: ${binding.touchOverlay.width}x${binding.touchOverlay.height}"
                )
            }
        }
    }

    override fun onDestroy() {
        isActive = false
        cancelTouchGesture()
        binding.toolbar.removeCallbacks(hideToolbarRunnable)
        try {
            binding.remoteVideoView.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing video view", e)
        }
        RemoteControlHolder.mainListener?.let { webRTCManager?.setListener(it) }
        super.onDestroy()
    }
}

/**
 * Simple holder for sharing instances between activities.
 */
object RemoteControlHolder {
    var signalingClient: SignalingClient? = null
    var webRTCManager: WebRTCManager? = null
    var mainListener: WebRTCManager.WebRTCListener? = null
}
