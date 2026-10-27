package com.workofaditya.scinet

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.DataChannel
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
 * Receives the host's existing WebRTC offer/answer stream. No screenshots are polled over HTTP.
 * All SDP exchange and host-side capture remain handled by SciNET's Python server.
 */
internal class NativeWebRtcSession(private val context: Context, private val renderer: SurfaceViewRenderer) {
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var initialized = false

    fun initialize() {
        if (initialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        egl = EglBase.create()
        renderer.init(egl!!.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(false)
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        initialized = true
    }

    suspend fun start(host: String, resolution: String, fps: Int) {
        if (!initialized) initialize()
        stopPeerOnly()
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.forEach { it.addSink(renderer) }
            }
            override fun onRemoveStream(stream: MediaStream?) {
                stream?.videoTracks?.forEach { it.removeSink(renderer) }
            }
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                (receiver?.track() as? VideoTrack)?.addSink(renderer)
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                (transceiver?.receiver?.track() as? VideoTrack)?.addSink(renderer)
            }
        }
        val configuration = PeerConnection.RTCConfiguration(emptyList())
        val connection = factory?.createPeerConnection(configuration, observer)
            ?: throw IllegalStateException("Could not create the WebRTC connection.")
        peer = connection
        connection.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
        val offer = createOffer(connection)
        setDescription(connection, offer, local = true)
        val gathered = withTimeoutOrNull(8000) {
            while (connection.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) delay(50)
            true
        } ?: false
        if (!gathered) throw IllegalStateException("WebRTC ICE gathering timed out. Check the local network.")
        val local = connection.localDescription ?: throw IllegalStateException("WebRTC did not produce a local offer.")
        val answer = SciNETApi.post(
            host,
            "/api/screen/offer",
            JSONObject().put("sdp", local.description).put("type", local.type.canonicalForm()).put("resolution", resolution).put("fps", fps)
        )
        val remote = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(answer.optString("type", "answer")),
            answer.optString("sdp")
        )
        setDescription(connection, remote, local = false)
    }

    suspend fun stop(host: String) {
        stopPeerOnly()
        try { SciNETApi.post(host, "/api/screen/stop", JSONObject()) } catch (_: Exception) { }
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

    private fun stopPeerOnly() {
        try { peer?.close() } catch (_: Exception) { }
        try { peer?.dispose() } catch (_: Exception) { }
        peer = null
    }

    private suspend fun createOffer(connection: PeerConnection): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            connection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description != null && continuation.isActive) continuation.resume(description)
                    else if (continuation.isActive) continuation.resumeWithException(IllegalStateException("WebRTC offer was empty."))
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error ?: "Could not create WebRTC offer."))
                }
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }

    private suspend fun setDescription(connection: PeerConnection, description: SessionDescription, local: Boolean) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {}
                override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error ?: "Could not apply WebRTC session description."))
                }
            }
            if (local) connection.setLocalDescription(observer, description)
            else connection.setRemoteDescription(observer, description)
        }
    }
}
