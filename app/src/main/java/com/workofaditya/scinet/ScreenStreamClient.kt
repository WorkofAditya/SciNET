package com.workofaditya.scinet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Reads the multipart JPEG stream produced by server.py.
 * There is deliberately no WebRTC or browser dependency in the Android viewer.
 */
internal object ScreenStreamClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun receive(
        host: String,
        resolution: String,
        fps: Int,
        onFrame: (Bitmap) -> Unit
    ) = withContext(Dispatchers.IO) {
        val base = SciNETApi.normalizeHost(host).trimEnd('/')
        require(base.isNotBlank()) { "Enter your PC's SciNET address first." }

        val url = "$base/api/screen/stream?resolution=${SciNETApi.encode(resolution)}&fps=$fps"
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "SciNET Android/0.1")
            .build()

        val call = client.newCall(request)
        val job = currentCoroutineContext().job
        val cancelCall = job.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorText = response.body?.string().orEmpty()
                    val message = try {
                        JSONObject(errorText).optString("error")
                    } catch (_: Exception) {
                        ""
                    }
                    throw IOException(message.ifBlank { "Screen stream failed (HTTP ${response.code})." })
                }

                val source = response.body?.source()
                    ?: throw IOException("The PC returned an empty screen stream.")

                while (currentCoroutineContext().isActive) {
                    val boundary = source.readUtf8Line() ?: break
                    if (!boundary.startsWith("--frame")) continue

                    var contentLength = -1
                    while (true) {
                        val header = source.readUtf8Line() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = header.substringAfter(':').trim().toIntOrNull() ?: -1
                        }
                    }

                    if (contentLength <= 0) continue
                    if (contentLength > 30 * 1024 * 1024) {
                        throw IOException("The PC sent an unexpectedly large screen frame.")
                    }

                    val jpeg = source.readByteArray(contentLength.toLong())
                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        ?: throw IOException("Could not decode a desktop frame.")

                    withContext(Dispatchers.Main.immediate) {
                        onFrame(bitmap)
                    }
                }
            }
        } catch (error: Exception) {
            if (currentCoroutineContext().isActive) throw error
        } finally {
            cancelCall.dispose()
            call.cancel()
        }
    }
}
