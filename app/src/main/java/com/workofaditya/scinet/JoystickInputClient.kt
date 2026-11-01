package com.workofaditya.scinet

import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Low-latency analog X/Y transport for SciNET's virtual Xbox controller. */
internal class JoystickInputClient(
    host: String,
    private val onError: (String) -> Unit
) {
    companion object {
        private val lowLatencySocketFactory = object : SocketFactory() {
            private val delegate = SocketFactory.getDefault()

            private fun noDelay(socket: Socket): Socket = socket.apply { tcpNoDelay = true }

            override fun createSocket(): Socket = noDelay(delegate.createSocket())
            override fun createSocket(host: String, port: Int): Socket = noDelay(delegate.createSocket(host, port))
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                noDelay(delegate.createSocket(host, port, localHost, localPort))
            override fun createSocket(host: InetAddress, port: Int): Socket = noDelay(delegate.createSocket(host, port))
            override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
                noDelay(delegate.createSocket(host, port, localHost, localPort))
        }

        private val client = OkHttpClient.Builder()
            .socketFactory(lowLatencySocketFactory)
            .pingInterval(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile private var closed = false
    @Volatile private var connected = false
    @Volatile private var socket: WebSocket? = null
    @Volatile private var latestX = 0f
    @Volatile private var latestY = 0f

    private val url: String = run {
        val base = SciNETApi.normalizeHost(host).trimEnd('/')
        require(base.isNotBlank()) { "Enter your PC's SciNET address first." }
        val wsBase = when {
            base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substring(8)
            base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substring(7)
            else -> "ws://$base"
        }
        "$wsBase/ws/joystick"
    }

    fun connect() {
        if (closed || socket != null) return
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SciNET Android/0.1")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                sendAxes(latestX, latestY)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                if (!closed) onError(t.message ?: "Joystick input connection failed.")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                if (!closed && code != 1000) onError(reason.ifBlank { "Joystick input connection closed." })
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"error\"")) {
                    onError(runCatching {
                        org.json.JSONObject(text).optString("error")
                    }.getOrDefault("Joystick input was rejected."))
                }
            }
        })
    }

    /** Send normalized analog axes in the -1.0..1.0 range. */
    @Synchronized
    fun sendAxes(x: Float, y: Float) {
        latestX = x.coerceIn(-1f, 1f)
        latestY = y.coerceIn(-1f, 1f)
        if (closed || !connected) return
        val payload = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(latestX)
            .putFloat(latestY)
            .array()
        socket?.send(ByteString.of(*payload))
    }

    fun close() {
        closed = true
        connected = false
        socket?.close(1000, "Joystick mode closed")
        socket = null
    }
}
