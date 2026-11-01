package com.workofaditya.scinet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Persistent binary input channel for high-frequency relative camera movement.
 * Each Android pointer sample is sent immediately without JSON encoding or timers.
 */
internal class CameraInputClient(
    host: String,
    private val onError: (String) -> Unit
) {
    companion object {
        private val client = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile private var closed = false
    @Volatile private var connected = false
    @Volatile private var socket: WebSocket? = null
    private val movementLock = Any()
    private var remainderX = 0f
    private var remainderY = 0f

    private val url: String = run {
        val base = SciNETApi.normalizeHost(host).trimEnd('/')
        require(base.isNotBlank()) { "Enter your PC's SciNET address first." }
        val wsBase = when {
            base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substring(8)
            base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substring(7)
            else -> "ws://$base"
        }
        "$wsBase/ws/controller"
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
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                if (!closed) onError(t.message ?: "Camera input connection failed.")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                if (!closed && code != 1000) {
                    onError(reason.ifBlank { "Camera input connection closed." })
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"error\"")) {
                    onError(runCatching {
                        org.json.JSONObject(text).optString("error")
                    }.getOrDefault("Camera input was rejected."))
                }
            }
        })
    }

    /**
     * Convert pointer pixels to integral Windows mouse counts while retaining
     * sub-count remainders. Sending happens immediately on the existing socket.
     */
    fun sendDelta(deltaX: Float, deltaY: Float, sensitivity: Float) {
        if (closed || !connected) return
        synchronized(movementLock) {
            if (closed || !connected) return
            remainderX += deltaX * sensitivity
            remainderY += deltaY * sensitivity
            val dx = remainderX.toInt()
            val dy = remainderY.toInt()
            remainderX -= dx
            remainderY -= dy
            if (dx == 0 && dy == 0) return

            val payload = ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(dx.coerceIn(-1200, 1200))
                .putInt(dy.coerceIn(-1200, 1200))
                .array()
            socket?.send(ByteString.of(*payload))
        }
    }

    fun close() {
        closed = true
        connected = false
        socket?.close(1000, "Controller closed")
        socket = null
    }
}
