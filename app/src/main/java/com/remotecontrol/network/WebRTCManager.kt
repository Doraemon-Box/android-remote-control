package com.remotecontrol.network

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.google.gson.JsonObject
import com.remotecontrol.config.ServerConfigStore
import com.remotecontrol.config.VideoStreamProfile
import org.webrtc.*

/**
 * Manages WebRTC peer connections for screen sharing.
 *
 * Controller mode: receives remote video stream
 * Controlled mode: captures screen and sends video stream
 */
class WebRTCManager(
    private val context: Context,
    private val signalingClient: SignalingClient,
    private var listener: WebRTCListener
) {
    companion object {
        private const val TAG = "WebRTCManager"

    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var eglBase: EglBase? = null
    private var roomId: String? = null
    private var isController = false

    fun initialize() {
        eglBase = EglBase.create()

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // Prefer H264 hardware codec for lower latency
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun setListener(listener: WebRTCListener) {
        this.listener = listener
    }

    fun getEglBase(): EglBase? = eglBase

    /**
     * Start as controlled device: capture screen and stream via WebRTC
     */
    fun startAsControlled(roomId: String, projectionData: Intent) {
        this.roomId = roomId
        this.isController = false

        createPeerConnection()

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val screenCapturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
            }
        })
        videoCapturer = screenCapturer

        val captureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        surfaceTextureHelper = captureHelper
        localVideoSource = peerConnectionFactory!!.createVideoSource(screenCapturer.isScreencast)
        screenCapturer.initialize(captureHelper, context, localVideoSource!!.capturerObserver)

        val size = VideoStreamProfile.captureSize(width, height)
        // ScreenCapturerAndroid explicitly ignores startCapture's framerate argument.
        // Adapt the source before starting, so excess display frames never reach the encoder.
        localVideoSource!!.adaptOutputFormat(size.width, size.height, VideoStreamProfile.FPS)
        screenCapturer.startCapture(size.width, size.height, VideoStreamProfile.FPS)
        Log.d(TAG, "Capture configured: ${size.width}x${size.height}, source cap=${VideoStreamProfile.FPS}fps")

        localVideoTrack = peerConnectionFactory!!.createVideoTrack("screen_track", localVideoSource)
        localVideoTrack?.setEnabled(true)

        peerConnection?.addTrack(localVideoTrack, listOf("screen_stream"))
        applyBitrateLimit()

        // Create offer
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                // Prefer H264 and set bitrate in SDP
                val modifiedSdp = preferH264(sdp)
                peerConnection?.setLocalDescription(SdpObserverAdapter(), modifiedSdp)
                val sdpJson = JsonObject().apply {
                    addProperty("type", modifiedSdp.type.canonicalForm())
                    addProperty("sdp", modifiedSdp.description)
                }
                signalingClient.sendOffer(roomId, sdpJson)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Failed to create offer: $error")
            }
        }, constraints)
    }

    /**
     * Start as controller: receive remote video stream
     */
    fun startAsController(roomId: String, remoteVideoSink: VideoSink) {
        this.roomId = roomId
        this.isController = true
        createPeerConnection(remoteVideoSink)
    }

    fun handleOffer(roomId: String, data: JsonObject) {
        val type = data.get("type")?.asString ?: return
        val sdpStr = data.get("sdp")?.asString ?: return
        val sdp = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdpStr)
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), sdp)

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                val modifiedSdp = preferH264(sdp)
                peerConnection?.setLocalDescription(SdpObserverAdapter(), modifiedSdp)
                val sdpJson = JsonObject().apply {
                    addProperty("type", modifiedSdp.type.canonicalForm())
                    addProperty("sdp", modifiedSdp.description)
                }
                signalingClient.sendAnswer(roomId, sdpJson)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Failed to create answer: $error")
            }
        }, constraints)
    }

    fun handleAnswer(data: JsonObject) {
        val type = data.get("type")?.asString ?: return
        val sdpStr = data.get("sdp")?.asString ?: return
        val sdp = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdpStr)
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                // Apply bitrate limit after connection established
                applyBitrateLimit()
            }
        }, sdp)
    }

    fun handleIceCandidate(data: JsonObject) {
        val sdpMid = data.get("sdpMid")?.asString ?: return
        val sdpMLineIndex = data.get("sdpMLineIndex")?.asInt ?: return
        val candidateStr = data.get("candidate")?.asString ?: return
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateStr)
        val added = peerConnection?.addIceCandidate(candidate)
        Log.d(TAG, "Remote ICE ${candidateSummary(candidate)} accepted=$added")
    }

    private fun candidateSummary(candidate: IceCandidate): String {
        val parts = candidate.sdp.split(" ")
        val typeIndex = parts.indexOf("typ")
        val type = if (typeIndex >= 0) parts.getOrNull(typeIndex + 1) else "unknown"
        return "protocol=${parts.getOrNull(2)} type=$type"
    }

    /**
     * Set max bitrate on video sender to control bandwidth usage
     */
    private fun applyBitrateLimit() {
        val senders = peerConnection?.senders ?: return
        for (sender in senders) {
            if (sender.track()?.kind() == "video") {
                val params = sender.parameters
                if (params.encodings.isNotEmpty()) {
                    params.encodings.forEach {
                        it.maxBitrateBps = VideoStreamProfile.MAX_BITRATE_BPS
                        it.minBitrateBps = VideoStreamProfile.MIN_BITRATE_BPS
                        it.maxFramerate = VideoStreamProfile.FPS
                    }
                    params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                    val applied = sender.setParameters(params)
                    Log.d(TAG, "Video limits applied=$applied: ${VideoStreamProfile.MIN_BITRATE_BPS}-${VideoStreamProfile.MAX_BITRATE_BPS}bps, ${VideoStreamProfile.FPS}fps")
                }
            }
        }
    }

    /**
     * Modify SDP to prefer H264 codec for hardware acceleration and lower latency
     */
    private fun preferH264(sdp: SessionDescription): SessionDescription {
        val lines = sdp.description.split("\r\n").toMutableList()
        val h264PayloadTypes = mutableListOf<String>()
        val mLineIndex = lines.indexOfFirst { it.startsWith("m=video") }

        if (mLineIndex == -1) return sdp

        // Find H264 payload types
        for (line in lines) {
            if (line.contains("a=rtpmap:") && line.contains("H264/90000", ignoreCase = true)) {
                val pt = line.substringAfter("a=rtpmap:").substringBefore(" ")
                h264PayloadTypes.add(pt)
            }
        }

        if (h264PayloadTypes.isEmpty()) return sdp

        // Reorder m=video line to put H264 first
        val mLine = lines[mLineIndex]
        val parts = mLine.split(" ").toMutableList()
        if (parts.size > 3) {
            val header = parts.subList(0, 3)
            val payloads = parts.subList(3, parts.size).toMutableList()
            // Move H264 to front
            for (pt in h264PayloadTypes.reversed()) {
                payloads.remove(pt)
                payloads.add(0, pt)
            }
            lines[mLineIndex] = (header + payloads).joinToString(" ")
        }

        // Add bitrate constraint
        val bitrateLine = "b=AS:${VideoStreamProfile.MAX_BITRATE_BPS / 1000}"
        if (lines.none { it.startsWith("b=AS:") }) {
            lines.add(mLineIndex + 1, bitrateLine)
        }

        return SessionDescription(sdp.type, lines.joinToString("\r\n"))
    }

    private fun createPeerConnection(remoteVideoSink: VideoSink? = null) {
        val config = ServerConfigStore(context).load()
        Log.d(TAG, "Creating peer controller=$isController room=$roomId TURN URLs=${config.turnUrls}")
        val iceServers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        config.turnUrls.forEach { url ->
            iceServers.add(PeerConnection.IceServer.builder(url)
                .setUsername(config.turnUsername)
                .setPassword(config.turnPassword)
                .createIceServer())
        }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Optimize for low latency
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "Local ICE ${candidateSummary(candidate)}")
                    val candidateJson = JsonObject().apply {
                        addProperty("sdpMid", candidate.sdpMid)
                        addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
                        addProperty("candidate", candidate.sdp)
                    }
                    roomId?.let { signalingClient.sendIceCandidate(it, candidateJson) }
                }

                override fun onTrack(transceiver: RtpTransceiver) {
                    val track = transceiver.receiver.track()
                    if (track is VideoTrack && remoteVideoSink != null) {
                        track.addSink(remoteVideoSink)
                        listener.onRemoteVideoTrackReceived(track)
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "ICE connection state: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            applyBitrateLimit()
                            listener.onPeerConnected()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED -> listener.onPeerDisconnected()
                        else -> {}
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    Log.d(TAG, "ICE gathering state: $state")
                }
                override fun onIceCandidateError(event: IceCandidateErrorEvent) {
                    Log.w(TAG, "ICE server error url=${event.url} code=${event.errorCode} text=${event.errorText}")
                }
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(channel: DataChannel) {}
                override fun onRenegotiationNeeded() {}
            }
        )
    }

    fun release() {
        try { videoCapturer?.stopCapture() } catch (e: Exception) { Log.e(TAG, "stopCapture error", e) }
        try { videoCapturer?.dispose() } catch (e: Exception) { Log.e(TAG, "dispose capturer error", e) }
        try { surfaceTextureHelper?.dispose() } catch (e: Exception) { Log.e(TAG, "dispose capture helper error", e) }
        try { localVideoTrack?.dispose() } catch (e: Exception) { Log.e(TAG, "dispose track error", e) }
        try { localVideoSource?.dispose() } catch (e: Exception) { Log.e(TAG, "dispose source error", e) }
        try { peerConnection?.close() } catch (e: Exception) { Log.e(TAG, "close pc error", e) }
        try { peerConnection?.dispose() } catch (e: Exception) { Log.e(TAG, "dispose pc error", e) }
        try { peerConnectionFactory?.dispose() } catch (e: Exception) { Log.e(TAG, "dispose factory error", e) }
        try { eglBase?.release() } catch (e: Exception) { Log.e(TAG, "release egl error", e) }

        videoCapturer = null
        surfaceTextureHelper = null
        localVideoTrack = null
        localVideoSource = null
        peerConnection = null
        peerConnectionFactory = null
        eglBase = null
    }

    interface WebRTCListener {
        fun onPeerConnected()
        fun onPeerDisconnected()
        fun onRemoteVideoTrackReceived(track: VideoTrack)
    }
}

/** Convenience adapter so callers only override what they need */
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {
        Log.e("SdpObserver", "Create failed: $error")
    }
    override fun onSetFailure(error: String) {
        Log.e("SdpObserver", "Set failed: $error")
    }
}
