package com.remotecontrol.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.remotecontrol.App
import com.remotecontrol.R
import com.remotecontrol.config.ServerConfig
import com.remotecontrol.config.ServerConfigStore
import com.remotecontrol.config.CaptureSettings
import com.remotecontrol.config.CaptureAuthorizationRequests
import com.remotecontrol.databinding.ActivityMainBinding
import com.remotecontrol.databinding.DialogServerSettingsBinding
import com.remotecontrol.network.SignalingClient
import com.remotecontrol.network.SignalingListener
import com.remotecontrol.network.WebRTCManager
import com.remotecontrol.service.ConnectionService
import com.remotecontrol.service.InputInjectionService
import com.remotecontrol.service.ScreenCaptureService
import com.remotecontrol.service.RemoteScreenWakeController
import com.remotecontrol.util.CoordinateMapper
import org.webrtc.VideoTrack

class MainActivity : AppCompatActivity(), SignalingListener, WebRTCManager.WebRTCListener {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var signalingClient: SignalingClient
    private lateinit var webRTCManager: WebRTCManager
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var serverConfigStore: ServerConfigStore
    private lateinit var activeServerUrl: String

    private var currentRoomId: String? = null
    private var onlineDeviceIds = emptySet<String>()
    private var connectionDialog: androidx.appcompat.app.AlertDialog? = null
    private var permissionDialog: androidx.appcompat.app.AlertDialog? = null
    private var savedProjectionData: Intent? = null
    private val captureRequests = CaptureAuthorizationRequests()
    private var isDestroyed_ = false
    private val remoteScreenWake by lazy { RemoteScreenWakeController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Global crash handler — show error instead of silent crash
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            Log.e(TAG, "UNCAUGHT EXCEPTION", e)
            try {
                val errorFile = java.io.File(getExternalFilesDir(null), "crash.txt")
                errorFile.writeText("${java.util.Date()}\n${e.stackTraceToString()}")
            } catch (_: Exception) {}
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        serverConfigStore = ServerConfigStore(this)

        RemoteControlHolder.signalingClient?.disconnect()
        RemoteControlHolder.webRTCManager?.release()
        signalingClient = SignalingClient(this, this)
        webRTCManager = WebRTCManager(this, signalingClient, this)
        webRTCManager.initialize()

        RemoteControlHolder.signalingClient = signalingClient
        RemoteControlHolder.webRTCManager = webRTCManager
        RemoteControlHolder.mainListener = this

        setupDeviceList()
        setupSetupGuide()
        setupAutoConsent()
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val home = item.itemId == R.id.navigation_home
            binding.pageHome.visibility = if (home) View.VISIBLE else View.GONE
            binding.pageSettings.visibility = if (home) View.GONE else View.VISIBLE
            true
        }
        binding.bottomNavigation.selectedItemId = savedInstanceState?.getInt("selected_page")
            ?: R.id.navigation_home
        binding.btnServerSettings.setOnClickListener { showServerSettings() }

        // Start keep-alive foreground service
        try {
            val connIntent = Intent(this, ConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(connIntent)
            } else {
                startService(connIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ConnectionService", e)
        }

        // Auto-connect
        updateStatus(getString(R.string.status_connecting), false)
        val isDebuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val savedConfig = serverConfigStore.load()
        activeServerUrl = if (isDebuggable && !serverConfigStore.hasSavedConfig()) {
            intent.getStringExtra("server_url") ?: savedConfig.signalingUrl
        } else savedConfig.signalingUrl
        signalingClient.connect(activeServerUrl, "${Build.MANUFACTURER} ${Build.MODEL}")

        // Request projection only when a remote session needs it.
    }

    override fun onResume() {
        super.onResume()
        checkSetupNeeded()
    }

    private fun setupDeviceList() {
        deviceAdapter = DeviceAdapter { device ->
            if (connectionDialog?.isShowing != true) {
                connectionDialog = MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.confirm_connection_title)
                    .setMessage(getString(R.string.confirm_connection_message, device.deviceName))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.confirm_connection) { _, _ ->
                        if (signalingClient.isConnected && device.deviceId in onlineDeviceIds) {
                            Toast.makeText(this, "正在连接 ${device.deviceName}…", Toast.LENGTH_SHORT).show()
                            signalingClient.connectTo(device.deviceId)
                        } else {
                            Toast.makeText(this, R.string.device_no_longer_online, Toast.LENGTH_SHORT).show()
                        }
                    }.show()
            }
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter
    }

    private fun setupAutoConsent() {
        val settings = CaptureSettings(this)
        binding.switchAutoConsent.isChecked = settings.autoConsentEnabled
        binding.switchAutoConsent.setOnClickListener {
            val checked = binding.switchAutoConsent.isChecked
            if (checked && !InputInjectionService.isRunning()) {
                binding.switchAutoConsent.isChecked = false
                Toast.makeText(this, R.string.auto_consent_accessibility_required, Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else if (!checked) {
                binding.switchAutoConsent.isChecked = settings.autoConsentEnabled
                confirmPermissionChange(R.string.disable_auto_consent_message, R.string.confirm_disable_permission) {
                    settings.autoConsentEnabled = false
                    binding.switchAutoConsent.isChecked = false
                    InputInjectionService.instance?.cancelScreenConsent()
                }
            } else {
                settings.autoConsentEnabled = true
            }
        }
    }

    private fun confirmPermissionChange(message: Int, action: Int, onConfirmed: () -> Unit) {
        if (permissionDialog?.isShowing == true) return
        permissionDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_change_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(action) { _, _ -> onConfirmed() }
            .show()
    }

    private fun showServerSettings() {
        if (currentRoomId != null) {
            Toast.makeText(this, R.string.server_settings_in_session, Toast.LENGTH_SHORT).show()
            return
        }
        val form = DialogServerSettingsBinding.inflate(layoutInflater)
        val config = serverConfigStore.load()
        form.etSignalingUrl.setText(activeServerUrl)
        form.etTurnUrls.setText(config.turnUrls.joinToString("\n"))
        form.etTurnUsername.setText(config.turnUsername)
        form.etTurnPassword.setText(config.turnPassword)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.server_settings)
            .setView(form.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save_settings, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val signalingUrl = form.etSignalingUrl.text.toString().trim()
                val turnUrls = form.etTurnUrls.text.toString().lineSequence()
                    .map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
                form.signalingUrlLayout.error = if (ServerConfig.isValidSignalingUrl(signalingUrl))
                    null else getString(R.string.invalid_signaling_url)
                form.turnUrlsLayout.error = if (turnUrls.all { ServerConfig.isValidTurnUrl(it) })
                    null else getString(R.string.invalid_turn_url)
                if (form.signalingUrlLayout.error != null || form.turnUrlsLayout.error != null) return@setOnClickListener
                if (currentRoomId != null) {
                    Toast.makeText(this, R.string.server_settings_in_session, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val updated = ServerConfig(signalingUrl, turnUrls,
                    form.etTurnUsername.text.toString(), form.etTurnPassword.text.toString())
                if (!serverConfigStore.save(updated)) {
                    Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                intent.removeExtra("server_url")
                if (activeServerUrl != signalingUrl) {
                    activeServerUrl = signalingUrl
                    onDisconnected()
                    updateStatus(getString(R.string.status_connecting), false)
                    signalingClient.connect(signalingUrl, "${Build.MANUFACTURER} ${Build.MODEL}")
                }
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun setupSetupGuide() {
        binding.btnSetupAccessibility.setOnClickListener {
            if (InputInjectionService.isRunning()) {
                confirmPermissionChange(R.string.manage_accessibility_message, R.string.continue_to_settings) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        binding.btnSetupBattery.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(packageName)) {
                    confirmPermissionChange(R.string.manage_battery_message, R.string.continue_to_settings) {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                } else {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun checkSetupNeeded() {
        val accessibilityOk = InputInjectionService.isRunning()
        val batteryOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true

        if (!accessibilityOk || !batteryOk) {
            binding.cardSetup.visibility = View.VISIBLE
            binding.btnSetupAccessibility.text = if (accessibilityOk)
                "1. 无障碍服务 已开启 ✓" else "1. 开启无障碍服务（被控端必须）"
            binding.btnSetupAccessibility.isEnabled = true
            binding.btnSetupBattery.text = if (batteryOk)
                "2. 电池优化 已关闭 ✓" else "2. 关闭电池优化（保持后台运行）"
            binding.btnSetupBattery.isEnabled = true
        } else {
            binding.cardSetup.visibility = View.VISIBLE
            binding.btnSetupAccessibility.text = "无障碍服务 已开启 ✓"
            binding.btnSetupBattery.text = "电池优化 已关闭 ✓"
        }
    }

    private fun updateStatus(text: String, connected: Boolean) {
        binding.tvStatus.text = text
        binding.tvStatus.setBackgroundResource(
            if (connected) R.drawable.bg_status_connected else R.drawable.bg_status_pill
        )
        binding.tvStatus.setTextColor(
            if (connected) 0xFF2E7D32.toInt() else 0xFF999999.toInt()
        )
    }

    private fun updateDeviceCount(count: Int) {
        binding.tvDeviceCount.text = getString(R.string.online_devices, count)
    }

    // ========== SignalingListener ==========

    override fun onConnected() {
        updateStatus(getString(R.string.status_connected), true)
        updateServiceNotification("待机中，等待连接...")
    }

    override fun onDisconnected() {
        if (isDestroyed_) return
        onlineDeviceIds = emptySet()
        connectionDialog?.dismiss()
        cancelScreenCaptureRequest()
        InputInjectionService.instance?.cancelScreenConsent()
        if (currentRoomId != null) onRoomClosed()
        updateStatus(getString(R.string.status_disconnected), false)
        deviceAdapter.updateDevices(emptyList())
        updateDeviceCount(0)
        binding.layoutEmpty.visibility = View.VISIBLE
        binding.rvDevices.visibility = View.GONE
    }

    override fun onRegistered(deviceId: String) {
        updateStatus("已连接", true)
    }

    override fun onDeviceListUpdated(devices: JsonArray) {
        val myId = signalingClient.deviceId
        val list = mutableListOf<DeviceInfo>()
        for (element in devices) {
            val obj = element.asJsonObject
            val id = obj.get("deviceId")?.asString ?: continue
            if (id != myId) {
                val name = obj.get("deviceName")?.asString ?: "未知设备"
                list.add(DeviceInfo(id, name))
            }
        }
        onlineDeviceIds = list.map { it.deviceId }.toSet()
        deviceAdapter.updateDevices(list)
        updateDeviceCount(list.size)
        if (list.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.rvDevices.visibility = View.GONE
        } else {
            binding.layoutEmpty.visibility = View.GONE
            binding.rvDevices.visibility = View.VISIBLE
        }
    }

    override fun onControlAccepted(roomId: String) {
        currentRoomId = roomId
        updateStatus(getString(R.string.status_controlling), true)
        updateServiceNotification("远程控制中...")
        val intent = Intent(this, RemoteViewActivity::class.java).apply {
            putExtra(RemoteViewActivity.EXTRA_ROOM_ID, roomId)
            putExtra(RemoteViewActivity.EXTRA_IS_CONTROLLER, true)
        }
        startActivity(intent)
    }

    override fun onRoomJoined(roomId: String) {
        val pending = captureRequests.pending
        if (pending != null && pending.roomId != roomId) {
            signalingClient.disconnectRoom(roomId)
            Toast.makeText(this, "请先完成或取消当前屏幕录制授权", Toast.LENGTH_LONG).show()
            return
        }
        currentRoomId = roomId
        updateStatus(getString(R.string.status_controlled), true)
        remoteScreenWake.start()
        updateServiceNotification("被控制中，等待屏幕录制授权...")

        val projData = savedProjectionData
        if (projData != null) {
            // Android 14+ projection consent is single-use.
            savedProjectionData = null
            try {
                // Start screen capture foreground service NOW (with valid token)
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_PROJECTION_DATA, projData)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                // Small delay to let service start, then begin WebRTC
                binding.root.postDelayed({
                    if (isDestroyed_ || currentRoomId != roomId) return@postDelayed
                    try {
                        webRTCManager.startAsControlled(roomId, projData)
                        updateServiceNotification("被控制中，屏幕共享中...")
                    } catch (e: Exception) {
                        Log.e(TAG, "WebRTC start failed", e)
                        signalingClient.disconnectRoom(roomId)
                        onRoomClosed()
                        Toast.makeText(this, "屏幕采集失败，请重新连接并授权", Toast.LENGTH_LONG).show()
                    }
                }, 300)
            } catch (e: Exception) {
                Log.e(TAG, "Screen capture failed", e)
                signalingClient.disconnectRoom(roomId)
                onRoomClosed()
                Toast.makeText(this, "屏幕采集失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            requestScreenCapture()
        }
    }

    override fun onRoomClosed() {
        InputInjectionService.instance?.clearPendingGestures()
        cancelScreenCaptureRequest()
        remoteScreenWake.stop()
        InputInjectionService.instance?.cancelScreenConsent()
        savedProjectionData = null
        try {
            webRTCManager.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WebRTC", e)
        }
        webRTCManager = WebRTCManager(this, signalingClient, this)
        webRTCManager.initialize()
        RemoteControlHolder.webRTCManager = webRTCManager
        RemoteControlHolder.mainListener = this
        currentRoomId = null
        stopService(Intent(this, ScreenCaptureService::class.java))
        updateStatus(getString(R.string.status_connected), true)
        Toast.makeText(this, "远程会话已结束", Toast.LENGTH_SHORT).show()
        updateServiceNotification("待机中，等待连接...")
    }

    private fun updateServiceNotification(text: String) {
        try {
            val notification = NotificationCompat.Builder(this, App.CHANNEL_ID)
                .setContentTitle("LinkView")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(ConnectionService.NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    override fun onOffer(roomId: String, data: JsonObject) {
        try {
            webRTCManager.handleOffer(roomId, data)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling offer", e)
        }
    }

    override fun onAnswer(roomId: String, data: JsonObject) {
        try {
            webRTCManager.handleAnswer(data)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling answer", e)
        }
    }

    override fun onIceCandidate(roomId: String, data: JsonObject) {
        try {
            webRTCManager.handleIceCandidate(data)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ICE candidate", e)
        }
    }

    override fun onInputEvent(event: JsonObject) {
        try {
            val mapped = CoordinateMapper.mapToScreen(this, event)
            InputInjectionService.instance?.dispatchRemoteEvent(mapped)
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching input", e)
        }
    }

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ========== WebRTCListener ==========

    override fun onPeerConnected() {
        runOnUiThread { Toast.makeText(this, "P2P连接已建立", Toast.LENGTH_SHORT).show() }
    }

    override fun onPeerDisconnected() {
        runOnUiThread { Toast.makeText(this, "P2P连接断开", Toast.LENGTH_SHORT).show() }
    }

    override fun onRemoteVideoTrackReceived(track: VideoTrack) {}

    // ========== Screen Capture ==========

    private fun requestScreenCapture() {
        if (captureRequests.pending != null) return
        val roomId = currentRoomId ?: return
        try {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                projectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            val request = captureRequests.begin(roomId)
            if (CaptureSettings(this).autoConsentEnabled) {
                val inputService = InputInjectionService.instance
                if (inputService != null) inputService.beginScreenConsent()
                else Toast.makeText(this, R.string.auto_consent_unavailable, Toast.LENGTH_LONG).show()
            }
            Log.d(TAG, "Requesting screen consent code=${request.code} room=$roomId")
            startActivityForResult(captureIntent, request.code)
        } catch (e: Exception) {
            cancelScreenCaptureRequest()
            Log.e(TAG, "Failed to request screen capture", e)
            currentRoomId?.let {
                signalingClient.disconnectRoom(it)
                onRoomClosed()
            }
        }
    }

    private fun cancelScreenCaptureRequest() {
        InputInjectionService.instance?.cancelScreenConsent()
        val request = captureRequests.cancel() ?: return
        Log.d(TAG, "Cancelling screen consent code=${request.code} room=${request.roomId}")
        // Clear the pending request first: finishActivity may deliver a late result.
        try {
            finishActivity(request.code)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to close cancelled screen consent dialog", e)
        }
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val request = captureRequests.complete(requestCode)
        if (request != null) {
            InputInjectionService.instance?.cancelScreenConsent()
            val requestedRoom = request.roomId
            Log.d(TAG, "Screen consent result code=$requestCode room=$requestedRoom granted=${resultCode == Activity.RESULT_OK}")
            // Never use a late result for a cancelled or different remote session.
            if (currentRoomId != requestedRoom) return
            if (resultCode == Activity.RESULT_OK && data != null) {
                savedProjectionData = data
                onRoomJoined(requestedRoom)
                checkSetupNeeded()
            } else {
                savedProjectionData = null
                currentRoomId?.let {
                    signalingClient.disconnectRoom(it)
                    onRoomClosed()
                }
                Toast.makeText(this, "未授权屏幕录制，远程会话已取消", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_page", binding.bottomNavigation.selectedItemId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        connectionDialog?.dismiss()
        permissionDialog?.dismiss()
        cancelScreenCaptureRequest()
        remoteScreenWake.stop()
        InputInjectionService.instance?.cancelScreenConsent()
        isDestroyed_ = true
        // A destroyed Activity must not retain its listener or reconnect forever.
        // Merely backgrounding the Activity does not call onDestroy.
        signalingClient.disconnect()
        webRTCManager.release()
        if (RemoteControlHolder.signalingClient === signalingClient) {
            RemoteControlHolder.signalingClient = null
            RemoteControlHolder.webRTCManager = null
        }
        super.onDestroy()
    }
}
