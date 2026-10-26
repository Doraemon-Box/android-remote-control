package com.remotecontrol.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import java.util.concurrent.TimeUnit

class SignalingClient(
    private val listener: SignalingListener
) {
    companion object {
        private const val TAG = "SignalingClient"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    var deviceId: String? = null
        private set
    var isConnected = false
        private set

    private var serverUrl: String? = null
    private var deviceName: String? = null
    private var reconnectAttempt = 0
    private var intentionalDisconnect = false
    private var connectionGeneration = 0

    fun connect(serverUrl: String, deviceName: String) {
        mainHandler.removeCallbacksAndMessages(null)
        this.serverUrl = serverUrl
        this.deviceName = deviceName
        this.intentionalDisconnect = false
        this.reconnectAttempt = 0
        doConnect(serverUrl, deviceName)
    }

    private fun doConnect(serverUrl: String, deviceName: String) {
        val generation = ++connectionGeneration
        isConnected = false
        deviceId = null
        // Close existing connection first
        webSocket?.close(1000, null)
        webSocket = null

        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post {
                    if (generation != connectionGeneration || intentionalDisconnect) return@post
                    isConnected = true
                    reconnectAttempt = 0
                    val msg = JsonObject().apply {
                        addProperty("type", "register")
                        addProperty("deviceName", deviceName)
                    }
                    webSocket.send(gson.toJson(msg))
                    listener.onConnected()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleMessage(text, generation)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling message: ${e.message}", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mainHandler.post {
                    if (generation != connectionGeneration || intentionalDisconnect) return@post
                    isConnected = false
                    deviceId = null
                    listener.onDisconnected()
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                mainHandler.post {
                    if (generation != connectionGeneration || intentionalDisconnect) return@post
                    isConnected = false
                    deviceId = null
                    listener.onDisconnected()
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (intentionalDisconnect) return
        val url = serverUrl ?: return
        val name = deviceName ?: return
        val generation = connectionGeneration

        reconnectAttempt++
        val delay = (RECONNECT_DELAY_MS * reconnectAttempt).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        Log.d(TAG, "Scheduling reconnect in ${delay}ms (attempt $reconnectAttempt)")

        mainHandler.postDelayed({
            if (generation == connectionGeneration && !isConnected && !intentionalDisconnect) {
                Log.d(TAG, "Reconnecting...")
                doConnect(url, name)
            }
        }, delay)
    }

    private fun handleMessage(text: String, generation: Int) {
        val msg = gson.fromJson(text, JsonObject::class.java)
        val type = msg.get("type")?.asString ?: return

        mainHandler.post {
            if (generation != connectionGeneration || intentionalDisconnect) return@post
            when (type) {
                "registered" -> {
                    deviceId = msg.get("deviceId")?.asString
                    if (deviceId != null) {
                        listener.onRegistered(deviceId!!)
                    }
                }
                "device_list" -> {
                    val devices = msg.getAsJsonArray("devices")
                    if (devices != null) {
                        listener.onDeviceListUpdated(devices)
                    }
                }
                "control_accepted" -> {
                    val roomId = msg.get("roomId")?.asString ?: return@post
                    listener.onControlAccepted(roomId)
                }
                "room_joined" -> {
                    val roomId = msg.get("roomId")?.asString ?: return@post
                    listener.onRoomJoined(roomId)
                }
                "room_closed" -> {
                    listener.onRoomClosed()
                }
                "offer" -> {
                    val roomId = msg.get("roomId")?.asString ?: return@post
                    val data = msg.getAsJsonObject("data") ?: return@post
                    listener.onOffer(roomId, data)
                }
                "answer" -> {
                    val roomId = msg.get("roomId")?.asString ?: return@post
                    val data = msg.getAsJsonObject("data") ?: return@post
                    listener.onAnswer(roomId, data)
                }
                "ice_candidate" -> {
                    val roomId = msg.get("roomId")?.asString ?: return@post
                    val data = msg.getAsJsonObject("data") ?: return@post
                    listener.onIceCandidate(roomId, data)
                }
                "input_event" -> {
                    val event = msg.getAsJsonObject("event") ?: return@post
                    listener.onInputEvent(event)
                }
                "error" -> {
                    val message = msg.get("message")?.asString ?: "Unknown error"
                    listener.onError(message)
                }
            }
        }
    }

    fun connectTo(targetDeviceId: String) {
        send(JsonObject().apply {
            addProperty("type", "connect_to")
            addProperty("targetDeviceId", targetDeviceId)
        })
    }

    fun sendOffer(roomId: String, sdp: JsonObject) {
        send(JsonObject().apply {
            addProperty("type", "offer")
            addProperty("roomId", roomId)
            add("data", sdp)
        })
    }

    fun sendAnswer(roomId: String, sdp: JsonObject) {
        send(JsonObject().apply {
            addProperty("type", "answer")
            addProperty("roomId", roomId)
            add("data", sdp)
        })
    }

    fun sendIceCandidate(roomId: String, candidate: JsonObject) {
        send(JsonObject().apply {
            addProperty("type", "ice_candidate")
            addProperty("roomId", roomId)
            add("data", candidate)
        })
    }

    fun sendInputEvent(roomId: String, event: JsonObject) {
        send(JsonObject().apply {
            addProperty("type", "input_event")
            addProperty("roomId", roomId)
            add("event", event)
        })
    }

    fun disconnectRoom(roomId: String) {
        send(JsonObject().apply {
            addProperty("type", "disconnect_room")
            addProperty("roomId", roomId)
        })
    }

    fun disconnect() {
        connectionGeneration++
        intentionalDisconnect = true
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        isConnected = false
        deviceId = null
    }

    private fun send(msg: JsonObject) {
        try {
            webSocket?.send(gson.toJson(msg))
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}", e)
        }
    }
}
