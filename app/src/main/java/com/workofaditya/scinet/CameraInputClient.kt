package com.workofaditya.scinet

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response

/**
 * Persistent low-latency channel for continuous camera movement.
 * One WebSocket avoids creating a new HTTP request for every finger movement.
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

    @Volatile
    private var closed = false
    @Volatile
    private var socket: WebSocket? = null

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
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed) {
                    onError(t.message ?: "Camera input connection failed.")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
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

    fun sendDelta(dx: Int, dy: Int) {
        if (closed || (dx == 0 && dy == 0)) return
        val message = org.json.JSONObject()
            .put("dx", dx.coerceIn(-1200, 1200))
            .put("dy", dy.coerceIn(-1200, 1200))
        socket?.send(message.toString())
    }

    fun close() {
        closed = true
        socket?.close(1000, "Controller closed")
        socket = null
    }
}
