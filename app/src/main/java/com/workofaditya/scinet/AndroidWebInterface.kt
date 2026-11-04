package com.workofaditya.scinet

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.net.TrafficStats
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

internal class AndroidWebInterface(private val context: Context) {
    private data class Upload(
        val id: String, val name: String, val total: Long, val temp: File,
        var received: Long = 0, var speed: Long = 0, var status: String = "uploading",
        var lastChunkAt: Long = System.nanoTime()
    )

    private val root: File by lazy {
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "SciNET-Web")
            .apply { mkdirs() }.canonicalFile
    }
    private val uploads = ConcurrentHashMap<String, Upload>()
    private val browserClients = ConcurrentHashMap<String, JSONObject>()
    private val clipboardClients = CopyOnWriteArraySet<DefaultWebSocketServerSession>()
    private val eventLock = Any()
    private val events = ArrayList<JSONObject>()
    private val clipboardLock = Any()
    private var clipboard = JSONObject().put("text", "").put("updated", System.currentTimeMillis() / 1000.0).put("source", "SciNET Android")
    private val startedAt = SystemClock.elapsedRealtime()
    private var hostName = context.getSharedPreferences("scinet-web", Context.MODE_PRIVATE)
        .getString("device_name", android.os.Build.MODEL ?: "Android device") ?: "Android device"
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L

    fun install(route: Route) = with(route) {
        get("/") {
            val page = asset("index.html")
            val start = page.indexOf("""<article class="card transfer-card"><div class="section-head"><div><div class="label">TRANSFER SERVICE</div>""")
            val end = if (start >= 0) page.indexOf("</article>", start) else -1
            val phoneStorageCard = """<article class="card transfer-card"><div class="section-head"><div><div class="label">PHONE STORAGE</div><h2>Local files</h2></div><span class="pill live">ACTIVE</span></div><p class="ftp-note">Web uploads are saved in this phone's SciNET-Web storage folder.</p></article>"""
            val rendered = if (start >= 0 && end > start) page.replaceRange(start, end + "</article>".length, phoneStorageCard) else page
            call.respondText(rendered, ContentType.Text.Html)
        }
        get("/control") { call.respondText(asset("control.html"), ContentType.Text.Html) }
        get("/files") { call.respondText(asset("files.html").replace("Shared files", "Phone storage"), ContentType.Text.Html) }
        get("/manifest.webmanifest") { call.respondText(asset("manifest.webmanifest"), ContentType.parse("application/manifest+json")) }
        get("/sw.js") { call.respondText(asset("sw.js"), ContentType.Application.JavaScript) }
        get("/static/{file}") {
            val file = call.parameters["file"].orEmpty()
            if (file !in setOf("style.css", "app.js", "control.js", "files.css", "files.js", "icon.svg")) {
                call.respond(HttpStatusCode.NotFound); return@get
            }
            val type = when (file.substringAfterLast('.')) {
                "css" -> ContentType.Text.CSS
                "js" -> ContentType.Application.JavaScript
                "svg" -> ContentType.parse("image/svg+xml")
                else -> ContentType.Application.OctetStream
            }
            if (file == "files.js") {
                call.respondText(asset(file).replace("Shared files", "Phone storage"), type)
            } else {
                call.respondBytes(assetBytes(file), type)
            }
        }

        get("/api/system") {
            val id = "web:" + call.request.local.remoteHost
            browserClients[id] = JSONObject().put("id", id).put("ip", call.request.local.remoteHost)
                .put("port", call.request.local.remotePort).put("type", "Web")
                .put("agent", call.request.headers[HttpHeaders.UserAgent] ?: "Browser")
                .put("connected", System.currentTimeMillis() / 1000.0)
            call.respondText(systemInfo(id).toString(), ContentType.Application.Json)
        }
        get("/api/events") {
            val snapshot = synchronized(eventLock) { JSONArray(events.map { JSONObject(it.toString()) }) }
            call.respondText(JSONObject().put("events", snapshot).toString(), ContentType.Application.Json)
        }
        delete("/api/events") {
            synchronized(eventLock) { events.clear() }
            call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
        }
        put("/api/device-name") {
            val data = call.jsonBody()
            val name = data.optString("name").trim().take(40)
            if (name.isBlank()) { call.errorJson(HttpStatusCode.BadRequest, "Device name cannot be empty"); return@put }
            hostName = name
            context.getSharedPreferences("scinet-web", Context.MODE_PRIVATE).edit().putString("device_name", name).apply()
            addEvent("Device renamed: " + name, "system")
            call.respondText(JSONObject().put("ok", true).put("name", name).toString(), ContentType.Application.Json)
        }
        get("/api/qr") {
            val matrix = QRCodeWriter().encode("http://" + localIpv4() + ":8765", BarcodeFormat.QR_CODE, 480, 480)
            val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
            for (y in 0 until 480) for (x in 0 until 480) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
            call.respondBytes(out.toByteArray(), ContentType.Image.PNG)
        }

        webSocket("/ws") {
            val id = "web:" + call.request.local.remoteHost
            browserClients[id] = JSONObject().put("id", id).put("ip", call.request.local.remoteHost)
                .put("port", call.request.local.remotePort).put("type", "Web")
                .put("agent", call.request.headers[HttpHeaders.UserAgent] ?: "Browser")
                .put("connected", System.currentTimeMillis() / 1000.0)
            try { while (true) { send(Frame.Text(systemInfo(id).toString())); delay(1000) } }
            finally { browserClients.remove(id) }
        }
        webSocket("/ws/clipboard") {
            clipboardClients.add(this)
            try {
                val initialClipboard = synchronized(clipboardLock) { clipboard.toString() }
                send(Frame.Text(initialClipboard))
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val data = runCatching { JSONObject(frame.readText()) }.getOrNull() ?: continue
                    val payload = JSONObject().put("text", data.optString("text").take(20000))
                        .put("updated", System.currentTimeMillis() / 1000.0).put("source", data.optString("source", "SciNET web"))
                    synchronized(clipboardLock) { clipboard = payload }
                    for (client in clipboardClients) runCatching { client.send(Frame.Text(payload.toString())) }
                    addEvent("Clipboard updated", "system")
                }
            } finally { clipboardClients.remove(this) }
        }

        get("/api/files") {
            val relative = call.request.queryParameters["path"].orEmpty()
            val directory = safeFile(relative)
            if (!directory.isDirectory) { call.errorJson(HttpStatusCode.NotFound, "Folder not found"); return@get }
            val entries = directory.listFiles().orEmpty().filterNot { it.name.startsWith(".scinet-") }
                .map { fileEntry(it) }
                .sortedWith(compareBy<JSONObject> { it.optString("type") != "folder" }.thenBy { it.optString("name").lowercase() })
            val normalized = relative.replace("\\", "/").trim('/')
            val parent = normalized.substringBeforeLast('/', "")
            call.respondText(JSONObject().put("path", normalized).put("parent", parent).put("files", JSONArray(entries)).toString(), ContentType.Application.Json)
        }
        get("/api/files/content") {
            val target = safeFile(call.request.queryParameters["path"].orEmpty())
            if (!target.isFile) { call.errorJson(HttpStatusCode.NotFound, "File not found"); return@get }
            if (target.length() > 2L * 1024L * 1024L) { call.errorJson(HttpStatusCode(413, "Payload Too Large"), "Text editor supports files up to 2 MB"); return@get }
            val content = runCatching { target.readText(Charsets.UTF_8) }.getOrElse {
                call.errorJson(HttpStatusCode.UnsupportedMediaType, "This file is not valid UTF-8 text"); return@get
            }
            call.respondText(JSONObject().put("path", relativePath(target)).put("name", target.name)
                .put("content", content).put("size", target.length()).toString(), ContentType.Application.Json)
        }
        put("/api/files/content") {
            val data = call.jsonBody(); val target = safeFile(data.optString("path"))
            val bytes = data.optString("content", "").toByteArray(Charsets.UTF_8)
            if (!target.isFile) { call.errorJson(HttpStatusCode.NotFound, "File not found"); return@put }
            if (bytes.size > 2 * 1024 * 1024) { call.errorJson(HttpStatusCode(413, "Payload Too Large"), "Text editor supports files up to 2 MB"); return@put }
            target.writeBytes(bytes); addEvent("Edited: " + target.name, "file")
            call.respondText(JSONObject().put("ok", true).put("size", bytes.size).toString(), ContentType.Application.Json)
        }
        get("/api/files/preview") {
            val target = safeFile(call.request.queryParameters["path"].orEmpty())
            if (!target.isFile) { call.errorJson(HttpStatusCode.NotFound, "File not found"); return@get }
            if (target.length() > 64L * 1024L * 1024L) { call.errorJson(HttpStatusCode(413, "Payload Too Large"), "Preview is limited to 64 MB. Download the file instead."); return@get }
            call.response.header(HttpHeaders.ContentDisposition, "inline; filename=\"" + safeHeader(target.name) + "\"")
            call.respondBytes(target.readBytes(), mimeType(target.name))
        }
        get("/api/download") {
            val target = safeFile(call.request.queryParameters["path"].orEmpty())
            if (!target.isFile) { call.errorJson(HttpStatusCode.NotFound, "File not found"); return@get }
            call.response.header(HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, target.name).toString())
            addEvent("Download requested: " + target.name, "transfer")
            call.respondFile(target)
        }
        post("/api/folders") {
            val data = call.jsonBody(); val parent = safeFile(data.optString("path"))
            val name = safeName(data.optString("name").ifBlank { "New Folder" })
            if (!parent.isDirectory || !File(parent, name).mkdir()) {
                call.errorJson(HttpStatusCode.Conflict, "Could not create folder (it may already exist)"); return@post
            }
            addEvent("Folder created: " + name, "file")
            call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
        }
        post("/api/files/rename") {
            val data = call.jsonBody(); val source = safeFile(data.optString("path"))
            val name = safeName(data.optString("name")); val destination = File(source.parentFile, name)
            if (!source.exists() || destination.exists() || !source.renameTo(destination)) {
                call.errorJson(HttpStatusCode.BadRequest, "Could not rename item"); return@post
            }
            addEvent("Renamed: " + name, "file")
            call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
        }
        delete("/api/files") {
            val target = safeFile(call.request.queryParameters["path"].orEmpty())
            if (target == root || !target.exists()) { call.errorJson(HttpStatusCode.BadRequest, "Could not delete item"); return@delete }
            val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
            if (!deleted) { call.errorJson(HttpStatusCode.InternalServerError, "Could not delete item"); return@delete }
            addEvent("Deleted: " + target.name, "file")
            call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
        }
        post("/api/files/upload") {
            val relative = call.request.queryParameters["path"].orEmpty()
            val directory = safeFile(relative)
            if (!directory.isDirectory) { call.errorJson(HttpStatusCode.NotFound, "Destination folder not found"); return@post }
            val multi = call.receiveMultipart(formFieldLimit = 2L * 1024L * 1024L * 1024L); val uploaded = JSONArray()
            while (true) {
                val part = multi.readPart() ?: break
                if (part is PartData.FileItem) {
                    val name = safeName(part.originalFileName ?: "upload.bin")
                    val destination = File(directory, name)
                    if (destination.exists()) { part.dispose(); call.errorJson(HttpStatusCode.Conflict, "A file named '" + name + "' already exists"); return@post }
                    val temp = File(directory, ".scinet-" + UUID.randomUUID().toString().replace("-", "") + ".part")
                    try {
                        part.streamProvider().use { input ->
                            FileOutputStream(temp).use { output -> input.copyTo(output) }
                        }
                        if (!temp.renameTo(destination)) throw IllegalStateException("Could not finalize upload")
                        uploaded.put(JSONObject().put("name", name).put("size", destination.length()))
                    } catch (e: Exception) {
                        temp.delete(); part.dispose(); call.errorJson(HttpStatusCode.InternalServerError, e.message ?: "Could not save upload"); return@post
                    }
                }
                part.dispose()
            }
            if (uploaded.length() == 0) { call.errorJson(HttpStatusCode.BadRequest, "Choose at least one file"); return@post }
            for (i in 0 until uploaded.length()) addEvent("File uploaded: " + uploaded.getJSONObject(i).optString("name"), "file")
            call.respondText(JSONObject().put("ok", true).put("path", relative).put("files", uploaded).toString(), ContentType.Application.Json)
        }
        post("/api/upload/start") {
            val data = call.jsonBody(); val name = safeName(data.optString("name").ifBlank { "unnamed" })
            val relative = data.optString("path").replace("\\", "/").trim('/')
            val directory = safeFile(relative)
            if (!directory.mkdirs() && !directory.isDirectory) { call.errorJson(HttpStatusCode.BadRequest, "Invalid upload destination"); return@post }
            val total = data.optLong("size", 0L).coerceAtLeast(0L)
            val id = UUID.randomUUID().toString().replace("-", "")
            val temp = File(directory, ".scinet-" + id + ".part")
            if (!temp.createNewFile()) { call.errorJson(HttpStatusCode.InternalServerError, "Could not start upload"); return@post }
            uploads[id] = Upload(id, name, total, temp); addEvent("Upload started: " + name, "transfer")
            call.respondText(JSONObject().put("id", id).toString(), ContentType.Application.Json)
        }
        post("/api/upload/{id}/chunk") {
            val id = call.parameters["id"].orEmpty(); val item = uploads[id]
            if (item == null) { call.errorJson(HttpStatusCode.NotFound, "Upload not found"); return@post }
            if (item.status != "uploading") { call.errorJson(HttpStatusCode.Conflict, "Upload is not active"); return@post }
            val chunk = call.receive<ByteArray>()
            val supplied = call.request.headers["X-Chunk-SHA256"].orEmpty().lowercase()
            val actual = MessageDigest.getInstance("SHA-256").digest(chunk).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (chunk.isEmpty() || supplied != actual) { call.errorJson(HttpStatusCode(422, "Unprocessable Entity"), "Chunk integrity check failed; retry this chunk"); return@post }
            var exceedsExpectedSize = false
            synchronized(item) {
                if (item.received + chunk.size > item.total) {
                    exceedsExpectedSize = true
                } else {
                    FileOutputStream(item.temp, true).use { it.write(chunk) }
                    item.received += chunk.size
                    val now = System.nanoTime()
                    item.speed = chunk.size * 1_000_000_000L / (now - item.lastChunkAt).coerceAtLeast(1L)
                    item.lastChunkAt = now
                }
            }
            if (exceedsExpectedSize) {
                call.errorJson(HttpStatusCode(413, "Payload Too Large"), "Chunk exceeds expected file size")
                return@post
            }
            call.respondText(JSONObject().put("received", item.received).put("total", item.total).toString(), ContentType.Application.Json)
        }
        post("/api/upload/{id}/finish") {
            val id = call.parameters["id"].orEmpty(); val item = uploads[id]
            if (item == null) { call.errorJson(HttpStatusCode.NotFound, "Upload not found"); return@post }
            if (item.status != "uploading" || item.received != item.total || item.temp.length() != item.total) {
                call.errorJson(HttpStatusCode.BadRequest, "Upload is incomplete or size verification failed"); return@post
            }
            val target = File(item.temp.parentFile, item.name)
            if (target.exists() || !item.temp.renameTo(target)) { call.errorJson(HttpStatusCode.Conflict, "A file with this name already exists"); return@post }
            uploads.remove(id); addEvent("Upload completed: " + item.name, "transfer")
            call.respondText(JSONObject().put("id", id).put("name", item.name).put("size", item.received).put("status", "complete").toString(), ContentType.Application.Json)
        }
        delete("/api/upload/{id}") {
            val id = call.parameters["id"].orEmpty(); val item = uploads.remove(id)
            if (item == null) { call.errorJson(HttpStatusCode.NotFound, "Upload not found"); return@delete }
            item.temp.delete(); addEvent("Upload cancelled: " + item.name, "transfer")
            call.respondText(JSONObject().put("id", id).put("status", "cancelled").toString(), ContentType.Application.Json)
        }
        post("/api/control") { call.errorJson(HttpStatusCode.NotImplemented, "Android remote control requires SciNET's Accessibility permission. These controls are available on the Windows host.") }
        post("/api/screen/offer") { call.errorJson(HttpStatusCode.NotImplemented, "Start and approve Android screen sharing in the SciNET app first. This browser viewer currently targets the Windows host.") }
        post("/api/screen/stop") { call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json) }
        get("/api/processes") { call.errorJson(HttpStatusCode.NotImplemented, "Android process management is not exposed by the SciNET phone web host.") }
        post("/api/processes/monitor") { call.errorJson(HttpStatusCode.NotImplemented, "Process monitoring is available on the Windows host only.") }
        post("/api/processes/action") { call.errorJson(HttpStatusCode.NotImplemented, "Process actions are available on the Windows host only.") }
    }

    private fun asset(name: String): String = assetBytes(name).toString(Charsets.UTF_8)
    private fun assetBytes(name: String): ByteArray = context.assets.open(name).use { it.readBytes() }
    private suspend fun ApplicationCall.jsonBody(): JSONObject = runCatching { JSONObject(receiveText()) }.getOrElse { JSONObject() }
    private suspend fun ApplicationCall.errorJson(status: HttpStatusCode, message: String) {
        respondText(JSONObject().put("error", message).toString(), ContentType.Application.Json, status)
    }
    private fun safeFile(path: String): File {
        val candidate = File(root, path.replace("\\", "/").trim('/')).canonicalFile
        require(candidate == root || candidate.path.startsWith(root.path + File.separator)) { "Invalid path" }
        return candidate
    }
    private fun safeName(value: String): String {
        val name = value.replace("\\", "/").substringAfterLast('/').trim().replace(Regex("[\\u0000-\\u001f]"), "")
        require(name.isNotBlank() && name != "." && name != ".." && name.length <= 180) { "Invalid file name" }
        return name
    }
    private fun relativePath(file: File): String = file.canonicalPath.removePrefix(root.path)
        .trimStart(File.separatorChar).replace(File.separatorChar, '/')
    private fun fileEntry(file: File): JSONObject = JSONObject().put("name", file.name).put("path", relativePath(file))
        .put("type", if (file.isDirectory) "folder" else "file").put("size", if (file.isFile) file.length() else 0)
        .put("modified", file.lastModified() / 1000.0)
    private fun addEvent(message: String, kind: String) {
        synchronized(eventLock) {
            events.add(0, JSONObject().put("time", System.currentTimeMillis() / 1000.0).put("kind", kind).put("message", message))
            while (events.size > 100) events.removeAt(events.lastIndex)
        }
    }
    private fun systemInfo(browserId: String? = null): JSONObject {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo(); manager?.getMemoryInfo(memory)
        val disk = StatFs(root.absolutePath); val totalDisk = disk.totalBytes; val freeDisk = disk.availableBytes
        val totalMemory = memory.totalMem; val usedMemory = (totalMemory - memory.availMem).coerceAtLeast(0L)
        val devices = ArrayList<JSONObject>()
        devices.add(JSONObject().put("id", "host").put("ip", localIpv4()).put("port", 8765).put("type", "Host").put("agent", hostName).put("name", hostName).put("connected", 1))
        for (peer in PeerNetworkState.peers.values) if (peer.optBoolean("connected")) {
            devices.add(JSONObject().put("id", peer.optString("id")).put("ip", peer.optString("host")).put("port", peer.optInt("port", 0))
                .put("type", peer.optString("platform", "Peer").replaceFirstChar { it.uppercase() })
                .put("agent", peer.optString("name")).put("name", peer.optString("name")).put("connected", 1))
        }
        for ((id, client) in browserClients) if (id != browserId) devices.add(JSONObject(client.toString()))
        val active = JSONArray()
        for (upload in uploads.values) active.put(JSONObject().put("id", upload.id).put("name", upload.name)
            .put("size", upload.received).put("total", upload.total).put("speed", upload.speed).put("status", upload.status))
        return JSONObject().put("hostname", hostName).put("cpu", cpuPercent())
            .put("memory", JSONObject().put("percent", if (totalMemory > 0) usedMemory * 100.0 / totalMemory else 0.0).put("used", usedMemory).put("total", totalMemory))
            .put("disk", JSONObject().put("percent", if (totalDisk > 0) (totalDisk - freeDisk) * 100.0 / totalDisk else 0.0).put("used", totalDisk - freeDisk).put("total", totalDisk))
            .put("network", JSONObject().put("sent", TrafficStats.getTotalTxBytes().coerceAtLeast(0L)).put("received", TrafficStats.getTotalRxBytes().coerceAtLeast(0L)))
            .put("uptime", (SystemClock.elapsedRealtime() - startedAt) / 1000L)
            .put("devices", JSONArray(devices)).put("device_count", devices.size).put("uploads", active)
            .put("self_device_id", browserId ?: ("android:" + PeerNetworkState.localDeviceId))
    }
    private fun cpuPercent(): Double = runCatching {
        val values = File("/proc/stat").bufferedReader().use { it.readLine().trim().split(Regex("\\s+")).drop(1).map(String::toLong) }
        val idle = values[3] + (values.getOrNull(4) ?: 0L); val total = values.sum()
        val totalDelta = total - lastCpuTotal; val idleDelta = idle - lastCpuIdle
        lastCpuTotal = total; lastCpuIdle = idle
        if (totalDelta <= 0L) 0.0 else ((totalDelta - idleDelta).toDouble() * 100.0 / totalDelta).coerceIn(0.0, 100.0)
    }.getOrDefault(0.0)
    private fun localIpv4(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().asSequence().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }.filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }?.hostAddress ?: "127.0.0.1"
    }.getOrDefault("127.0.0.1")
    private fun safeHeader(value: String): String = value.replace("\"", "").replace("\r", "").replace("\n", "")
    private fun mimeType(name: String): ContentType = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> ContentType.Text.Html
        "css" -> ContentType.Text.CSS
        "js" -> ContentType.Application.JavaScript
        "json" -> ContentType.Application.Json
        "txt", "log", "md" -> ContentType.Text.Plain
        "png" -> ContentType.Image.PNG
        "jpg", "jpeg" -> ContentType.Image.JPEG
        "gif" -> ContentType.Image.GIF
        "svg" -> ContentType.parse("image/svg+xml")
        "webp" -> ContentType.parse("image/webp")
        "pdf" -> ContentType.Application.Pdf
        else -> ContentType.Application.OctetStream
    }
}
