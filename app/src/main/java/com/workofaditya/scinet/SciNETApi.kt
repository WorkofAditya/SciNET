package com.workofaditya.scinet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

internal object SciNETApi {
    fun normalizeHost(value: String): String {
        var host = value.trim().trimEnd('/')
        if (host.isNotEmpty() && !host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://$host"
        }
        return host
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    suspend fun get(host: String, path: String): JSONObject = request(host, path)

    suspend fun post(host: String, path: String, body: JSONObject): JSONObject =
        request(host, path, "POST", body)

    suspend fun put(host: String, path: String, body: JSONObject): JSONObject =
        request(host, path, "PUT", body)

    suspend fun delete(host: String, path: String): JSONObject =
        request(host, path, "DELETE")

    suspend fun request(
        host: String,
        path: String,
        method: String = "GET",
        body: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val base = normalizeHost(host)
        require(base.isNotBlank()) { "Enter your PC's SciNET address first." }
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SciNET Android/0.1")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = try { if (text.isBlank()) JSONObject() else JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (code !in 200..299) throw IllegalStateException(json.optString("error").ifBlank { "SciNET returned HTTP $code." })
            json
        } finally {
            connection.disconnect()
        }
    }

    suspend fun upload(
        context: Context,
        host: String,
        uri: Uri,
        fileName: String,
        folder: String,
        onProgress: suspend (Long, Long?) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val totalBytes = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0L }
                else null
            }
        }.getOrNull()

        val safeFileName = fileName.replace("\"", "_")
        val boundary = "----SciNETAndroid" + System.currentTimeMillis()
        val path = "/api/files/upload?path=" + encode(folder)
        val connection = (URL(normalizeHost(host) + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 120000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "SciNET Android/0.1")
        }

        try {
            var transferred = 0L
            var lastProgressAt = 0L
            onProgress(0L, totalBytes)

            connection.outputStream.use { output ->
                output.write("--$boundary\r\n".toByteArray())
                output.write("Content-Disposition: form-data; name=\"files\"; filename=\"$safeFileName\"\r\n".toByteArray())
                output.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())

                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        transferred += count

                        val now = System.nanoTime()
                        if (now - lastProgressAt >= 120_000_000L ||
                            (totalBytes != null && transferred >= totalBytes)
                        ) {
                            onProgress(transferred, totalBytes)
                            lastProgressAt = now
                        }
                    }
                } ?: throw IllegalStateException("Android could not open the selected file.")

                output.write("\r\n--$boundary--\r\n".toByteArray())
            }

            onProgress(transferred, totalBytes)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (code !in 200..299) {
                throw IllegalStateException(json.optString("error").ifBlank { "Upload failed (HTTP $code)." })
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    suspend fun uploadToPeer(
        context: Context,
        host: String,
        senderId: String,
        peerKey: String,
        uri: Uri,
        fileName: String,
        onProgress: suspend (Long, Long?) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        require(senderId.isNotBlank() && peerKey.isNotBlank()) { "Pair with this device before sending files." }
        val totalBytes = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0L } else null
            }
        }.getOrNull()
        val connection = (URL(normalizeHost(host).trimEnd('/') + "/api/peer/transfers").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 120000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("User-Agent", "SciNET Android/0.1")
            setRequestProperty("X-SciNET-Peer-ID", senderId)
            setRequestProperty("X-SciNET-File-Name", encode(fileName))
            totalBytes?.let { setRequestProperty("X-SciNET-File-Size", it.toString()) }
            setRequestProperty("Authorization", "Bearer " + peerKey)
        }
        try {
            var transferred = 0L
            var lastProgressAt = 0L
            onProgress(0L, totalBytes)
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Android could not open the selected file.")
            input.use { source ->
                connection.outputStream.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        transferred += count
                        val now = System.nanoTime()
                        if (now - lastProgressAt >= 120_000_000L ||
                            (totalBytes != null && transferred >= totalBytes)
                        ) {
                            onProgress(transferred, totalBytes)
                            lastProgressAt = now
                        }
                    }
                }
            }
            onProgress(transferred, totalBytes)
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(response) }.getOrElse { JSONObject() }
            if (code !in 200..299) {
                throw IllegalStateException(json.optString("error").ifBlank { "Peer upload failed (HTTP $code)." })
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadTo(
        context: Context,
        host: String,
        path: String,
        destination: Uri,
        onProgress: suspend (Long, Long?) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val urlPath = "/api/download?path=" + encode(path)
        val connection = (URL(normalizeHost(host) + urlPath).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 120000
            setRequestProperty("User-Agent", "SciNET Android/0.1")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException(
                    try { JSONObject(message).optString("error") }
                    catch (_: Exception) { "Download failed (HTTP $code)." }
                )
            }

            val totalBytes = connection.contentLengthLong.takeIf { it >= 0L }
            var transferred = 0L
            var lastProgressAt = 0L
            onProgress(0L, totalBytes)

            context.contentResolver.openOutputStream(destination)?.use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        transferred += count

                        val now = System.nanoTime()
                        if (now - lastProgressAt >= 120_000_000L ||
                            (totalBytes != null && transferred >= totalBytes)
                        ) {
                            onProgress(transferred, totalBytes)
                            lastProgressAt = now
                        }
                    }
                }
            } ?: throw IllegalStateException("Android could not create the destination file.")

            onProgress(transferred, totalBytes)
        } finally {
            connection.disconnect()
        }
    }

    fun jsonObject(vararg values: Pair<String, Any?>): JSONObject {
        val json = JSONObject()
        values.forEach { (key, value) -> json.put(key, value) }
        return json
    }

    fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
}
