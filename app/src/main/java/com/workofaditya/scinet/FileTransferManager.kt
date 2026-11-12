package com.workofaditya.scinet

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

internal data class TransferUiItem(
    val id: String,
    val peerId: String,
    val peerName: String,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val direction: String,
    val status: String,
    val openUri: Uri?,
    val mimeType: String
)

internal data class IncomingTextMessage(
    val id: String,
    val peerName: String,
    val text: String
)

internal data class MessagePulse(
    val peerId: String,
    val fromLocal: Boolean,
    val createdAt: Long
)

internal data class ChunkWriteResult(
    val accepted: Boolean,
    val nextOffset: Long,
    val complete: Boolean,
    val error: String? = null
)

private class TransferRecord(
    val id: String,
    val peerId: String,
    val peerName: String,
    val fileName: String,
    val totalBytes: Long,
    val direction: String,
    val mimeType: String,
    val sourceUri: Uri? = null,
    @Volatile var savedUri: Uri? = null,
    @Volatile var completedAt: Long = 0L,
    @Volatile var transferredBytes: Long = 0L,
    @Volatile var status: String
) {
    fun snapshot() = TransferUiItem(
        id, peerId, peerName, fileName, totalBytes,
        transferredBytes.coerceIn(0L, totalBytes),
        direction, status,
        if (direction == "outgoing") sourceUri else savedUri,
        mimeType
    )
}

/**
 * File payloads use raw HTTP binary chunks. WebSockets carry only small transfer-control
 * messages, so file contents are never Base64-encoded or mixed into mesh heartbeats.
 */
internal object FileTransferManager {
    private const val CHUNK_SIZE = 1024 * 1024
    private const val MAX_CHUNK_SIZE = 2 * 1024 * 1024
    private const val MAX_FILE_SIZE = 50L * 1024L * 1024L * 1024L
    private const val TRANSFER_DIRECTORY = "scinet-transfers"

    private val records = ConcurrentHashMap<String, TransferRecord>()
    private val messages = CopyOnWriteArrayList<IncomingTextMessage>()
    private val pulses = CopyOnWriteArrayList<MessagePulse>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun items(): List<TransferUiItem> {
        val now = System.currentTimeMillis()
        records.entries.removeIf { entry ->
            val record = entry.value
            record.status == "completed" &&
                record.completedAt > 0L &&
                now - record.completedAt >= 3_000L
        }
        return records.values
            .map { synchronized(it) { it.snapshot() } }
            .sortedBy { it.id }
    }

    fun pendingMessages(): List<IncomingTextMessage> = messages.toList()

    fun dismissMessage(id: String) {
        messages.removeIf { it.id == id }
    }

    fun messagePulses(): List<MessagePulse> {
        val now = System.currentTimeMillis()
        pulses.removeIf { now - it.createdAt > 900L }
        return pulses.toList()
    }

    private fun addMessagePulse(peerId: String, fromLocal: Boolean) {
        pulses.add(MessagePulse(peerId, fromLocal, System.currentTimeMillis()))
        while (pulses.size > 64) pulses.removeAt(0)
    }

    fun beginOutgoingToAll(context: Context, uri: Uri): Int {
        val connectedPeers = PeerNetworkState.peers.entries
            .map { it.key to it.value.optString("name").ifBlank { "SciNET device" } }
            .filter { it.first.isNotBlank() }
            .distinctBy { it.first }
        return connectedPeers.count { (peerId, peerName) ->
            beginOutgoing(context, peerId, peerName, uri)
        }
    }

    fun beginOutgoing(context: Context, peerId: String, peerName: String, uri: Uri): Boolean {
        val resolver = context.contentResolver
        var fileName = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "file" } ?: "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) fileName = cursor.getString(nameIndex)?.ifBlank { fileName } ?: fileName
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        if (size < 0L) {
            runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length } }
        }
        if (size < 0L || size > MAX_FILE_SIZE) return false

        val safeName = safeFileName(fileName)
        val extension = safeName.substringAfterLast('.', "").lowercase()
        val mime = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        val id = UUID.randomUUID().toString().replace("-", "")
        val record = TransferRecord(
            id = id,
            peerId = peerId,
            peerName = peerName,
            fileName = safeName,
            totalBytes = size,
            direction = "outgoing",
            mimeType = mime,
            sourceUri = uri,
            status = "offering"
        )
        records[id] = record

        val offered = send(
            peerId,
            JSONObject()
                .put("type", "file_offer")
                .put("transferId", id)
                .put("fileName", safeName)
                .put("totalBytes", size)
                .put("mimeType", mime)
        )
        if (!offered) {
            record.status = "failed"
            return false
        }
        return true
    }

    fun acceptIncoming(context: Context, transferId: String) {
        val record = records[transferId] ?: return
        synchronized(record) {
            if (record.direction != "incoming" || record.status != "offered") return
            val part = partialFile(context, transferId)
            part.parentFile?.mkdirs()
            if (!part.exists()) part.createNewFile()
            record.transferredBytes = part.length().coerceAtMost(record.totalBytes)
            record.status = "transferring"
        }
        send(record.peerId, JSONObject().put("type", "file_accept").put("transferId", transferId))
        if (record.totalBytes == 0L) {
            finishIncoming(context, record)
        } else {
            scope.launch { download(context.applicationContext, record) }
        }
    }

    fun declineIncoming(transferId: String) {
        val record = records[transferId] ?: return
        record.status = "rejected"
        send(record.peerId, JSONObject().put("type", "file_reject").put("transferId", transferId))
    }

    fun control(context: Context, transferId: String, action: String) {
        val record = records[transferId] ?: return
        when (action) {
            "pause" -> if (record.status == "transferring") record.status = "paused"
            "resume" -> if (record.status == "paused") record.status = "transferring"
            "cancel" -> {
                if (record.status in setOf("completed", "cancelled", "rejected", "failed")) {
                    records.remove(transferId)
                    return
                }
                record.status = "cancelled"
                if (record.direction == "incoming") partialFile(context, transferId).delete()
            }
            else -> return
        }
        send(
            record.peerId,
            JSONObject().put("type", "file_control")
                .put("transferId", transferId)
                .put("action", action)
        )
    }

    fun sendTextToAll(text: String): Int {
        val connectedPeerIds = PeerNetworkState.peers.keys.toList()
        return connectedPeerIds.count { peerId -> sendText(peerId, text) }
    }

    fun sendText(peerId: String, text: String): Boolean {
        val message = text.trim()
        if (message.isEmpty() || message.length > 16000) return false
        val sent = send(
            peerId,
            JSONObject()
                .put("type", "text_message")
                .put("messageId", UUID.randomUUID().toString())
                .put("text", message)
        )
        if (sent) addMessagePulse(peerId, fromLocal = true)
        return sent
    }

    fun handleSignal(context: Context, peerId: String, message: JSONObject) {
        val type = message.optString("type")
        val transferId = message.optString("transferId")
        when (type) {
            "file_offer" -> {
                val id = transferId.takeIf { it.matches(Regex("[A-Za-z0-9-]{8,64}")) } ?: return
                val size = message.optLong("totalBytes", -1L)
                if (size < 0L || size > MAX_FILE_SIZE) {
                    send(peerId, JSONObject().put("type", "file_reject").put("transferId", id))
                    return
                }
                val peerName = PeerNetworkState.peers[peerId]?.optString("name")?.ifBlank { "SciNET device" }
                    ?: "SciNET device"
                records[id] = TransferRecord(
                    id = id,
                    peerId = peerId,
                    peerName = peerName,
                    fileName = safeFileName(message.optString("fileName", "file")),
                    totalBytes = size,
                    direction = "incoming",
                    mimeType = message.optString("mimeType", "application/octet-stream"),
                    status = "offered"
                )
            }
            "file_accept" -> {
                val record = records[transferId] ?: return
                if (record.direction == "outgoing" && record.status == "offering") {
                    record.status = "transferring"
                }
            }
            "file_reject" -> records[transferId]?.status = "rejected"
            "file_progress" -> {
                val record = records[transferId] ?: return
                val bytes = message.optLong("transferredBytes", record.transferredBytes)
                record.transferredBytes = bytes.coerceIn(0L, record.totalBytes)
            }
            "file_control" -> {
                val record = records[transferId] ?: return
                when (message.optString("action")) {
                    "pause" -> if (record.status == "transferring") record.status = "paused"
                    "resume" -> if (record.status == "paused") record.status = "transferring"
                    "cancel" -> {
                        record.status = "cancelled"
                        if (record.direction == "incoming") partialFile(context, transferId).delete()
                    }
                }
            }
            "file_complete" -> {
                records[transferId]?.let {
                    it.transferredBytes = it.totalBytes
                    it.status = "completed"
                    it.completedAt = System.currentTimeMillis()
                }
            }
            "file_error" -> records[transferId]?.status = "failed"
            "text_message" -> {
                val text = message.optString("text").take(16000)
                if (text.isNotBlank()) {
                    val peerName = PeerNetworkState.peers[peerId]?.optString("name")?.ifBlank { "SciNET device" }
                        ?: "SciNET device"
                    messages.add(IncomingTextMessage(message.optString("messageId").ifBlank { UUID.randomUUID().toString() }, peerName, text))
                    addMessagePulse(peerId, fromLocal = false)
                }
            }
        }
    }

    fun writeIncomingChunk(
        context: Context,
        peerId: String,
        transferId: String,
        offset: Long,
        totalBytes: Long,
        bytes: ByteArray
    ): ChunkWriteResult {
        if (bytes.isEmpty() || bytes.size > MAX_CHUNK_SIZE) {
            return ChunkWriteResult(false, 0L, false, "Invalid chunk size")
        }
        val record = records[transferId] ?: return ChunkWriteResult(false, 0L, false, "Unknown transfer")
        var nextOffset = 0L
        var complete = false
        synchronized(record) {
            if (record.direction != "incoming" || record.peerId != peerId ||
                record.totalBytes != totalBytes || record.status !in setOf("transferring", "paused")
            ) {
                return ChunkWriteResult(false, record.transferredBytes, false, "Transfer is not active")
            }
            val part = partialFile(context, transferId)
            part.parentFile?.mkdirs()
            val currentLength = if (part.exists()) part.length() else 0L
            if (offset != currentLength || offset + bytes.size > totalBytes) {
                return ChunkWriteResult(false, currentLength, false, "Offset mismatch")
            }
            RandomAccessFile(part, "rw").use { file ->
                file.seek(offset)
                file.write(bytes)
            }
            nextOffset = part.length()
            record.transferredBytes = nextOffset
            complete = nextOffset == totalBytes
        }

        send(
            peerId,
            JSONObject().put("type", "file_progress")
                .put("transferId", transferId)
                .put("transferredBytes", nextOffset)
        )
        if (complete) finishIncoming(context, record)
        return ChunkWriteResult(true, nextOffset, complete)
    }

    fun dismissTransfer(context: Context, transferId: String) {
        control(context, transferId, "cancel")
    }

    fun readOutgoingChunk(
        context: Context,
        peerId: String,
        transferId: String,
        offset: Long,
        requestedLength: Int
    ): ByteArray? {
        val record = records[transferId] ?: return null
        if (record.direction != "outgoing" || record.peerId != peerId ||
            record.status !in setOf("transferring", "paused") ||
            offset < 0L || offset >= record.totalBytes ||
            requestedLength !in 1..MAX_CHUNK_SIZE
        ) return null
        val count = minOf(requestedLength.toLong(), record.totalBytes - offset).toInt()
        val uri = record.sourceUri ?: return null
        val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (descriptor != null) {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    input.channel.position(offset)
                    val bytes = ByteArray(count)
                    return if (readChunk(input, bytes) == count) bytes else null
                }
            } catch (_: Exception) {
                // Some document providers expose a pipe rather than a seekable file descriptor.
            }
        }
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return input.use { stream ->
            skipFully(stream, offset)
            val bytes = ByteArray(count)
            if (readChunk(stream, bytes) != count) null else bytes
        }
    }

    private suspend fun download(context: Context, record: TransferRecord) {
        try {
            val peer = PeerNetworkState.peers[record.peerId]
                ?: throw IllegalStateException("Peer disconnected")
            val host = peer.optString("host")
            val port = peer.optInt("port", 8765)
            if (host.isBlank() || port !in 1..65535) throw IllegalStateException("Peer endpoint unavailable")

            var offset = record.transferredBytes
            while (offset < record.totalBytes && scope.isActive) {
                while (record.status == "paused" && scope.isActive) delay(150)
                if (record.status == "cancelled") return
                if (record.status != "transferring") throw IllegalStateException("Transfer stopped")

                val length = minOf(CHUNK_SIZE.toLong(), record.totalBytes - offset).toInt()
                val url = "http://$host:$port/api/transfers/" + record.id +
                    "/chunk?offset=$offset&length=$length"
                val request = Request.Builder()
                    .url(url)
                    .header("X-SciNET-From", PeerNetworkState.localDeviceId)
                    .get()
                    .build()
                val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
                val bytes = response.use {
                    if (!it.isSuccessful) {
                        throw IllegalStateException("Sender could not provide a file chunk (${it.code})")
                    }
                    it.body?.bytes() ?: throw IllegalStateException("Empty file chunk")
                }
                if (bytes.size != length) throw IllegalStateException("Received an incomplete file chunk")
                val result = writeIncomingChunk(
                    context, record.peerId, record.id, offset, record.totalBytes, bytes
                )
                if (!result.accepted) throw IllegalStateException(result.error ?: "Could not write received chunk")
                offset = result.nextOffset
            }
        } catch (error: Exception) {
            if (record.status !in setOf("cancelled", "completed")) {
                record.status = "failed"
                send(
                    record.peerId,
                    JSONObject().put("type", "file_error")
                        .put("transferId", record.id)
                        .put("message", error.message ?: "Transfer failed")
                )
            }
        }
    }

    private fun finishIncoming(context: Context, record: TransferRecord) {
        val part = partialFile(context, record.id)
        if (!part.exists() || part.length() != record.totalBytes) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, record.fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, record.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SciNET")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Cannot create destination file")
                try {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        part.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Cannot open destination file")
                    context.contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null)
                    record.savedUri = uri
                } catch (error: Exception) {
                    context.contentResolver.delete(uri, null, null)
                    throw error
                }
            } else {
                val directory = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "SciNET"
                ).apply { mkdirs() }
                val destination = File(directory, record.fileName)
                part.copyTo(destination, overwrite = true)
                record.savedUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    context.packageName + ".files",
                    destination
                )
            }
            part.delete()
            record.transferredBytes = record.totalBytes
            record.status = "completed"
            record.completedAt = System.currentTimeMillis()
            send(record.peerId, JSONObject().put("type", "file_complete").put("transferId", record.id))
        } catch (error: Exception) {
            record.status = "failed"
            send(
                record.peerId,
                JSONObject().put("type", "file_error")
                    .put("transferId", record.id)
                    .put("message", error.message ?: "Could not save received file")
            )
        }
    }

    private fun partialFile(context: Context, transferId: String): File =
        File(File(context.filesDir, TRANSFER_DIRECTORY), "$transferId.part")

    private fun send(peerId: String, message: JSONObject): Boolean =
        PeerNetworkState.sendPeerMessage(peerId, message.toString())

    private fun safeFileName(value: String): String {
        val cleaned = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F]"), "_")
            .trim()
            .take(180)
        return cleaned.ifBlank { "file" }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val discard = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                if (read < 0) throw IllegalStateException("Cannot seek within selected file")
                remaining -= read
            }
        }
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            if (read == 0) break
            count += read
        }
        return count
    }
}
