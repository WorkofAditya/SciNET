package com.workofaditya.scinet

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Native WebRTC receiver for SciNET's continuously captured Windows desktop video track.
 *
 * This deliberately follows the browser client's negotiation sequence: create a receive-only
 * offer, set it locally, allow ICE gathering a bounded window, then submit the SDP even when
 * the platform has not reported ICE gathering COMPLETE. The host API uses non-trickle SDP too.
 */
internal class NativeWebRtcSession(
    private val context: Context,
    private val renderer: SurfaceViewRenderer
) {
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var initialized = false
    private val attachedVideoTracks = mutableSetOf<VideoTrack>()

    fun initialize() {
        if (initialized) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
        egl = EglBase.create()
        val eglContext = egl!!.eglBaseContext
        renderer.init(eglContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(false)

        // Explicit decoder setup is important on Android: decode the incoming VP8/H.264
        // desktop track into continuously rendered frames for SurfaceViewRenderer.
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()
        initialized = true
    }

    suspend fun start(host: String, resolution: String, fps: Int) {
        if (!initialized) initialize()
        stopPeerOnly()

        val connectionReady = CompletableDeferred<Unit>()
        val videoTrackReady = CompletableDeferred<Unit>()
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> connectionReady.complete(Unit)
                    PeerConnection.IceConnectionState.FAILED -> connectionReady.completeExceptionally(
                        IllegalStateException("WebRTC could not establish a direct connection to the PC. Check that both devices are on the same reachable network.")
                    )
                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidate(candidate: IceCandidate?) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.forEach { attachVideoTrack(it, videoTrackReady) }
            }

            override fun onRemoveStream(stream: MediaStream?) {
                stream?.videoTracks?.forEach(::detachVideoTrack)
            }

            override fun onDataChannel(channel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                attachVideoTrack(receiver?.track() as? VideoTrack, videoTrackReady)
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                attachVideoTrack(transceiver?.receiver?.track() as? VideoTrack, videoTrackReady)
            }
        }

        // The desktop web client intentionally uses an empty ICE server list for the trusted
        // LAN connection. Keep the native peer identical: local host candidates are preferred.
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val connection = factory?.createPeerConnection(configuration, observer)
            ?: throw IllegalStateException("Could not create the WebRTC connection.")
        peer = connection

        val transceiver = connection.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        ) ?: throw IllegalStateException("Could not create the receive-only desktop video track.")

        val offer = createOffer(connection)
        setDescription(connection, offer, local = true)

        // Android's ICE gatherer can remain in GATHERING even when usable LAN candidates have
        // already been added to localDescription. The browser implementation also continues
        // after its five-second gather window instead of treating this state as fatal.
        withTimeoutOrNull(5000) {
            while (connection.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) {
                delay(50)
            }
        }

        val local = connection.localDescription
            ?: throw IllegalStateException("WebRTC did not produce a local offer.")
        if (!local.description.contains("m=video")) {
            throw IllegalStateException("The WebRTC offer does not contain a video media track.")
        }

        val answer = SciNETApi.post(
            host,
            "/api/screen/offer",
            JSONObject()
                .put("sdp", local.description)
                .put("type", local.type.canonicalForm())
                .put("resolution", resolution)
                .put("fps", fps)
        )
        val answerSdp = answer.optString("sdp")
        if (answerSdp.isBlank()) {
            throw IllegalStateException("The PC returned an empty WebRTC answer.")
        }
        val remote = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(answer.optString("type", "answer")),
            answerSdp
        )
        setDescription(connection, remote, local = false)

        // Do not label the view LIVE just because the SDP exchange returned. Wait for ICE to
        // connect so the UI only reports success once the actual real-time video path is up.
        val connected = withTimeoutOrNull(20000) {
            connectionReady.await()
            true
        } ?: false
        if (!connected) {
            throw IllegalStateException("WebRTC did not connect to the PC. Check the Wi-Fi/hotspot connection and try again.")
        }

        val receivedTrack = withTimeoutOrNull(10000) {
            videoTrackReady.await()
            true
        } ?: false
        if (!receivedTrack) {
            throw IllegalStateException("The PC connected but did not send a desktop video track.")
        }
    }

    suspend fun stop(host: String) {
        stopPeerOnly()
        try {
            SciNETApi.post(host, "/api/screen/stop", JSONObject())
        } catch (_: Exception) {
        }
    }

    fun close() {
        stopPeerOnly()
        if (initialized) {
            try { renderer.release() } catch (_: Exception) { }
            try { factory?.dispose() } catch (_: Exception) { }
            try { egl?.release() } catch (_: Exception) { }
        }
        factory = null
        egl = null
        initialized = false
    }

    private fun attachVideoTrack(track: VideoTrack?, ready: CompletableDeferred<Unit>) {
        if (track == null) return
        val newlyAttached = synchronized(attachedVideoTracks) { attachedVideoTracks.add(track) }
        if (newlyAttached) {
            track.setEnabled(true)
            track.addSink(renderer)
        }
        ready.complete(Unit)
    }

    private fun detachVideoTrack(track: VideoTrack?) {
        if (track == null) return
        val wasAttached = synchronized(attachedVideoTracks) { attachedVideoTracks.remove(track) }
        if (wasAttached) {
            try { track.removeSink(renderer) } catch (_: Exception) { }
        }
    }

    private fun stopPeerOnly() {
        val tracks = synchronized(attachedVideoTracks) {
            val copy = attachedVideoTracks.toList()
            attachedVideoTracks.clear()
            copy
        }
        tracks.forEach { track ->
            try { track.removeSink(renderer) } catch (_: Exception) { }
        }
        try { peer?.close() } catch (_: Exception) { }
        try { peer?.dispose() } catch (_: Exception) { }
        peer = null
    }

    private suspend fun createOffer(connection: PeerConnection): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            connection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description != null && continuation.isActive) continuation.resume(description)
                    else if (continuation.isActive) continuation.resumeWithException(
                        IllegalStateException("WebRTC offer was empty.")
                    )
                }

                override fun onSetSuccess() = Unit

                override fun onCreateFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IllegalStateException(error ?: "Could not create WebRTC offer.")
                    )
                }

                override fun onSetFailure(error: String?) = Unit
            }, MediaConstraints())
        }

    private suspend fun setDescription(
        connection: PeerConnection,
        description: SessionDescription,
        local: Boolean
    ) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) = Unit

                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onCreateFailure(error: String?) = Unit

                override fun onSetFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IllegalStateException(error ?: "Could not apply WebRTC session description.")
                    )
                }
            }
            if (local) connection.setLocalDescription(observer, description)
            else connection.setRemoteDescription(observer, description)
        }
    }
}
