package com.workofaditya.scinet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.InetAddress
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private const val PEER_PORT = 8765
private const val PEER_SERVICE_TYPE = "_scinet._tcp."
private const val PEER_PROTOCOL_VERSION = 1
private const val PEER_CHANNEL_ID = "scinet-peer-network"
private const val PEER_NOTIFICATION_ID = 8765

internal object PeerNetworkState {
    @Volatile var localDeviceId: String = ""
    @Volatile var serverRunning: Boolean = false
    @Volatile var pairingToken: String = ""
    @Volatile var pairingTokenExpiresAt: Long = 0L
    val peers = ConcurrentHashMap<String, JSONObject>()
    val incomingTransfers = ConcurrentHashMap<String, JSONObject>()

    fun rotatePairingToken() {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        pairingToken = bytes.joinToString("") { "%02x".format(it) }
        pairingTokenExpiresAt = System.currentTimeMillis() + 5 * 60 * 1000L
    }

    fun persistIncoming(context: Context) {
        val items = org.json.JSONArray(incomingTransfers.values.toList())
        context.getSharedPreferences("scinet-peer-inbox", Context.MODE_PRIVATE)
            .edit().putString("items", items.toString()).apply()
    }

    fun restoreIncoming(context: Context) {
        val encoded = context.getSharedPreferences("scinet-peer-inbox", Context.MODE_PRIVATE)
            .getString("items", "[]") ?: "[]"
        val items = runCatching { org.json.JSONArray(encoded) }.getOrNull() ?: org.json.JSONArray()
        val directory = File(context.filesDir, "peer-inbox")
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.isNotBlank() && File(directory, id).isFile) incomingTransfers[id] = item
        }
    }

    fun removeIncoming(context: Context, id: String) {
        incomingTransfers.remove(id)
        File(File(context.filesDir, "peer-inbox"), id).delete()
        persistIncoming(context)
    }
}

/**
 * Permanent Android peer node.
 *
 * It serves the peer control channel, advertises itself over mDNS, and discovers
 * Windows/Android nodes. The first protocol milestone deliberately handles only
 * identity and liveness messages; privileged commands and file payloads are not
 * accepted on this channel.
 */
class PeerNetworkService : Service() {
    companion object {
        const val ACTION_CONNECT_PEER = "com.workofaditya.scinet.CONNECT_PEER"
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_PEER_NAME = "peer_name"
        const val EXTRA_PEER_HOST = "peer_host"
        const val EXTRA_PEER_PORT = "peer_port"
    }

    private data class PeerEndpoint(
        val id: String,
        val name: String,
        val platform: String,
        val host: String,
        val port: Int
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val endpoints = ConcurrentHashMap<String, PeerEndpoint>()
    private val connectionJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private lateinit var nsdManager: NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var httpServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var deviceId: String
    private lateinit var deviceName: String
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        val preferences = getSharedPreferences("scinet-peer", MODE_PRIVATE)
        deviceId = preferences.getString("device_id", null)
            ?: UUID.randomUUID().toString().replace("-", "").also {
                preferences.edit().putString("device_id", it).apply()
            }
        deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"
        PeerNetworkState.localDeviceId = deviceId
        PeerNetworkState.rotatePairingToken()
        PeerNetworkState.restoreIncoming(this)
        nsdManager = getSystemService(NsdManager::class.java)
        createNotificationChannel()
        acquireMulticastLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Starting peer server and device discovery")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                PEER_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(PEER_NOTIFICATION_ID, notification)
        }
        if (httpServer == null) startPeerServer()
        if (intent?.action == ACTION_CONNECT_PEER) {
            val id = intent.getStringExtra(EXTRA_PEER_ID).orEmpty()
            val host = intent.getStringExtra(EXTRA_PEER_HOST).orEmpty()
            val name = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty()
            val port = intent.getIntExtra(EXTRA_PEER_PORT, PEER_PORT)
            if (id.isNotBlank() && id != deviceId && host.isNotBlank() && port in 1..65535) {
                val endpoint = PeerEndpoint(id, name.ifBlank { "SciNET device" }, "android", host, port)
                endpoints[id] = endpoint
                startPeerConnection(endpoint)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPeerServer() {
        try {
            httpServer = embeddedServer(CIO, host = "0.0.0.0", port = PEER_PORT) {
                install(WebSockets) {
                    pingPeriod = 20.seconds
                    timeout = 35.seconds
                    maxFrameSize = 64L * 1024L
                }
                routing {
                    get("/") {
                        val html = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#0a0c0f"><title>SciNET Phone</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#0a0c0f;color:#e7e9ed;font:16px system-ui,sans-serif;min-height:100vh;display:grid;place-items:center;padding:24px}.panel{width:min(100%,520px);background:#101419;border:1px solid #292f37;border-radius:18px;padding:28px}h1{letter-spacing:.16em;font-size:18px}p{color:#9aa3ae;line-height:1.6}.status{color:#79b98b;font-weight:700;font-size:12px;letter-spacing:.12em}.device{padding:14px;background:#15191e;border-radius:10px;margin-top:18px}.small{font-size:12px;color:#858d98}</style></head>
<body><main class="panel"><div class="status">● PHONE SERVER ONLINE</div><h1>SCINET</h1><p>This phone is running a SciNET peer node on your local network.</p><div class="device"><strong id="name">SciNET phone</strong><p class="small" id="identity">Loading device information…</p><p class="small" id="peers"></p></div><p class="small">To connect another Android device, install SciNET and use <b>SCAN PC QR CODE</b> to scan the QR shown on this phone. Browser access currently provides device status only.</p></main>
<script>fetch('/api/peer/info').then(r=>r.json()).then(d=>{document.getElementById('name').textContent=d.deviceName||'SciNET phone';document.getElementById('identity').textContent='Device ID: '+d.deviceId;document.getElementById('peers').textContent='Connected SciNET peers: '+(d.peers||[]).filter(p=>p.connected).length}).catch(()=>{document.getElementById('identity').textContent='Could not load device information'})</script></body></html>"""
                        call.respondText(html, io.ktor.http.ContentType.Text.Html)
                    }
                    get("/health") {
                        call.respondText(
                            JSONObject()
                                .put("service", "SciNET")
                                .put("status", "online")
                                .put("deviceId", deviceId)
                                .put("protocolVersion", PEER_PROTOCOL_VERSION)
                                .toString(),
                            io.ktor.http.ContentType.Application.Json
                        )
                    }
                    get("/api/peer/info") {
                        call.respondText(
                            JSONObject()
                                .put("deviceId", deviceId)
                                .put("deviceName", deviceName)
                                .put("platform", "android")
                                .put("protocolVersion", PEER_PROTOCOL_VERSION)
                                .put("port", PEER_PORT)
                                .put("peers", org.json.JSONArray(PeerNetworkState.peers.values.toList()))
                                .toString(),
                            io.ktor.http.ContentType.Application.Json
                        )
                    }
                    post("/api/pair/claim") {
                        val payload = runCatching { JSONObject(call.receiveText()) }.getOrNull()
                        val remoteId = payload?.optString("deviceId").orEmpty()
                        val token = payload?.optString("token").orEmpty()
                        val now = System.currentTimeMillis()
                        val validId = remoteId.length in 16..64 &&
                            remoteId.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
                            remoteId != deviceId
                        if (!validId || token.isBlank() || token != PeerNetworkState.pairingToken ||
                            now > PeerNetworkState.pairingTokenExpiresAt
                        ) {
                            call.respondText(
                                JSONObject().put("error", "Pairing QR is invalid, expired, or already used. Refresh the QR on the other phone.").toString(),
                                ContentType.Application.Json,
                                HttpStatusCode.Unauthorized
                            )
                            return@post
                        }

                        val remoteHost = call.request.local.remoteHost
                        val remoteIp = runCatching { InetAddress.getByName(remoteHost) }.getOrNull()
                        if (remoteIp == null ||
                            !(remoteIp.isSiteLocalAddress || remoteIp.isLoopbackAddress || remoteIp.isLinkLocalAddress)
                        ) {
                            call.respondText(
                                JSONObject().put("error", "SciNET pairing is available on a private local network only.").toString(),
                                ContentType.Application.Json,
                                HttpStatusCode.Forbidden
                            )
                            return@post
                        }

                        val remoteName = payload?.optString("deviceName").orEmpty().take(80).ifBlank { "SciNET device" }
                        val remotePlatform = payload?.optString("platform").orEmpty().take(24).ifBlank { "android" }
                        val peerKey = UUID.randomUUID().toString().replace("-", "") +
                            UUID.randomUUID().toString().replace("-", "")
                        getSharedPreferences("scinet-trust", MODE_PRIVATE)
                            .edit().putString(remoteId, peerKey).apply()

                        if (remoteHost.isNotBlank() && !remoteHost.contains(':')) {
                            val endpoint = PeerEndpoint(remoteId, remoteName, remotePlatform, remoteHost, PEER_PORT)
                            endpoints[remoteId] = endpoint
                            startPeerConnection(endpoint)
                        }

                        PeerNetworkState.rotatePairingToken()
                        call.respondText(
                            JSONObject()
                                .put("ok", true)
                                .put("deviceId", deviceId)
                                .put("deviceName", deviceName)
                                .put("peerKey", peerKey)
                                .toString(),
                            ContentType.Application.Json
                        )
                    }
                    post("/api/peer/transfers") {
                        val peerId = call.request.headers["X-SciNET-Peer-ID"].orEmpty()
                        val suppliedKey = call.request.headers[HttpHeaders.Authorization]
                            ?.removePrefix("Bearer ")?.trim().orEmpty()
                        val trustedKey = getSharedPreferences("scinet-trust", MODE_PRIVATE)
                            .getString(peerId, null)
                        val authorized = !peerId.isBlank() && trustedKey != null &&
                            java.security.MessageDigest.isEqual(
                                trustedKey.toByteArray(Charsets.UTF_8),
                                suppliedKey.toByteArray(Charsets.UTF_8)
                            )
                        if (!authorized) {
                            call.respondText(
                                JSONObject().put("error", "This device is not paired with the sender.").toString(),
                                ContentType.Application.Json,
                                HttpStatusCode.Unauthorized
                            )
                            return@post
                        }

                        val rawName = call.request.headers["X-SciNET-File-Name"].orEmpty()
                        val fileName = URLDecoder.decode(rawName, Charsets.UTF_8.name())
                            .replace("/", "_").replace("\\", "_").replace("..", "_")
                            .filter { it >= ' ' && it != '\u007f' }
                            .take(180).ifBlank { "scinet-transfer.bin" }
                        val expectedSize = call.request.headers["X-SciNET-File-Size"]?.toLongOrNull() ?: -1L
                        if (expectedSize > 2L * 1024L * 1024L * 1024L) {
                            call.respondText(
                                JSONObject().put("error", "Files larger than 2 GB are not supported yet.").toString(),
                                ContentType.Application.Json,
                                HttpStatusCode(413, "Payload Too Large")
                            )
                            return@post
                        }

                        val transferId = UUID.randomUUID().toString().replace("-", "")
                        val directory = File(filesDir, "peer-inbox").apply { mkdirs() }
                        val destination = File(directory, transferId)
                        var received = 0L
                        try {
                            call.receiveStream().use { input ->
                                FileOutputStream(destination).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        received += count
                                        if (received > 2L * 1024L * 1024L * 1024L) {
                                            throw IllegalStateException("Transfer exceeded the 2 GB limit.")
                                        }
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                            if (expectedSize >= 0L && received != expectedSize) {
                                throw IllegalStateException("Transfer size mismatch. The file was not saved.")
                            }
                        } catch (error: Exception) {
                            destination.delete()
                            call.respondText(
                                JSONObject().put("error", error.message ?: "File transfer failed.").toString(),
                                ContentType.Application.Json,
                                HttpStatusCode.BadRequest
                            )
                            return@post
                        }

                        val senderName = PeerNetworkState.peers[peerId]?.optString("name")
                            ?.takeIf { it.isNotBlank() } ?: "Paired SciNET device"
                        val item = JSONObject()
                            .put("id", transferId)
                            .put("name", fileName)
                            .put("size", received)
                            .put("senderId", peerId)
                            .put("senderName", senderName)
                            .put("receivedAt", System.currentTimeMillis())
                        PeerNetworkState.incomingTransfers[transferId] = item
                        PeerNetworkState.persistIncoming(this@PeerNetworkService)
                        call.respondText(
                            JSONObject().put("ok", true).put("transferId", transferId).put("receivedBytes", received).toString(),
                            ContentType.Application.Json
                        )
                    }
                    webSocket("/ws/peer") {
                        val remoteAddress = call.request.local.remoteHost
                        val remoteIp = runCatching { InetAddress.getByName(remoteAddress) }.getOrNull()
                        if (remoteIp == null ||
                            !(remoteIp.isSiteLocalAddress || remoteIp.isLoopbackAddress || remoteIp.isLinkLocalAddress)
                        ) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "SciNET peers must be on a private local network"))
                            return@webSocket
                        }

                        val firstFrame = withTimeoutOrNull(8.seconds) { incoming.receive() }
                        val helloText = (firstFrame as? Frame.Text)?.readText()
                        val hello = runCatching { JSONObject(helloText ?: "") }.getOrNull()
                        val remoteId = hello?.optString("deviceId").orEmpty()
                        val remoteName = hello?.optString("deviceName").orEmpty()
                        val remotePlatform = hello?.optString("platform").orEmpty()
                        val version = hello?.optInt("protocolVersion", -1) ?: -1

                        if (hello?.optString("type") != "hello" ||
                            remoteId.isBlank() || remoteId == deviceId ||
                            version != PEER_PROTOCOL_VERSION
                        ) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid SciNET peer hello"))
                            return@webSocket
                        }

                        val peerRecord = JSONObject()
                            .put("id", remoteId)
                            .put("name", remoteName.ifBlank { "SciNET device" })
                            .put("platform", remotePlatform)
                            .put("host", remoteAddress)
                            .put("port", call.request.local.remotePort)
                            .put("connected", true)
                            .put("trusted", getSharedPreferences("scinet-trust", MODE_PRIVATE).contains(remoteId))
                            .put("lastSeen", System.currentTimeMillis())
                        PeerNetworkState.peers[remoteId] = peerRecord

                        outgoing.send(
                            Frame.Text(
                                JSONObject()
                                    .put("type", "welcome")
                                    .put("protocolVersion", PEER_PROTOCOL_VERSION)
                                    .put("deviceId", deviceId)
                                    .put("deviceName", deviceName)
                                    .put("platform", "android")
                                    .put("capabilities", org.json.JSONArray(listOf("control-v1", "http-stream-v1", "webrtc-v1")))
                                    .toString()
                            )
                        )

                        try {
                            for (frame in incoming) {
                                if (frame !is Frame.Text) continue
                                val message = runCatching { JSONObject(frame.readText()) }.getOrNull() ?: continue
                                if (message.optString("type") == "heartbeat") {
                                    peerRecord.put("lastSeen", System.currentTimeMillis())
                                    outgoing.send(
                                        Frame.Text(
                                            JSONObject()
                                                .put("type", "heartbeat_ack")
                                                .put("deviceId", deviceId)
                                                .put("sentAt", message.optLong("sentAt"))
                                                .toString()
                                        )
                                    )
                                }
                            }
                        } finally {
                            PeerNetworkState.peers.remove(remoteId, peerRecord)
                        }
                    }
                }
            }.start(wait = false)
            PeerNetworkState.serverRunning = true
            startNsd()
            updateNotification("Peer server active • discovering nearby devices")
        } catch (error: Exception) {
            PeerNetworkState.serverRunning = false
            updateNotification("Peer server could not start: ${error.message ?: "port unavailable"}")
        }
    }

    private fun startNsd() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "SciNET-${deviceId.take(8)}"
            serviceType = PEER_SERVICE_TYPE
            port = PEER_PORT
            setAttribute("id", deviceId)
            setAttribute("name", deviceName.take(60))
            setAttribute("platform", "android")
            setAttribute("protocol", PEER_PROTOCOL_VERSION.toString())
            setAttribute("ws", "/ws/peer")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(service: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(service: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(service: NsdServiceInfo, errorCode: Int) = Unit
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceName == serviceInfo.serviceName) return
                runCatching {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val id = info.attributes["id"]?.toString(Charsets.UTF_8).orEmpty()
                            val host = info.host?.hostAddress.orEmpty()
                            val version = info.attributes["protocol"]?.toString(Charsets.UTF_8)?.toIntOrNull()
                            if (id.isBlank() || id == deviceId || host.isBlank() || host.contains(':') ||
                                version != PEER_PROTOCOL_VERSION || info.port <= 0
                            ) return
                            val endpoint = PeerEndpoint(
                                id = id,
                                name = info.attributes["name"]?.toString(Charsets.UTF_8).orEmpty()
                                    .ifBlank { info.serviceName },
                                platform = info.attributes["platform"]?.toString(Charsets.UTF_8).orEmpty(),
                                host = host,
                                port = info.port
                            )
                            endpoints[id] = endpoint
                            startPeerConnection(endpoint)
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                // A service can be re-resolved when it returns to the LAN. Existing
                // sockets are allowed to close naturally before the retry loop exits.
                val id = service.serviceName.substringAfter("SciNET-", "").takeIf { it.isNotBlank() }
                if (id != null) {
                    val peerId = endpoints.keys.firstOrNull { it.startsWith(id, ignoreCase = true) }
                    if (peerId != null) {
                        endpoints.remove(peerId)
                        sockets[peerId]?.cancel()
                    }
                }
            }
        }

        nsdManager.registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            registrationListener!!
        )
        nsdManager.discoverServices(
            PEER_SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener!!
        )
    }

    private fun startPeerConnection(peer: PeerEndpoint) {
        // Every pair has one deterministic dialer: the lexicographically smaller ID.
        if (deviceId >= peer.id) return
        val existing = connectionJobs[peer.id]
        if (existing?.isActive == true) return

        connectionJobs[peer.id] = scope.launch {
            while (isActive && endpoints.containsKey(peer.id)) {
                val opened = CompletableDeferred<Boolean>()
                val closed = CompletableDeferred<Unit>()
                val request = Request.Builder()
                    .url("ws://${peer.host}:${peer.port}/ws/peer")
                    .header("User-Agent", "SciNET Android Peer/1")
                    .build()

                val socket = httpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            JSONObject()
                                .put("type", "hello")
                                .put("protocolVersion", PEER_PROTOCOL_VERSION)
                                .put("deviceId", deviceId)
                                .put("deviceName", deviceName)
                                .put("platform", "android")
                                .put("capabilities", org.json.JSONArray(listOf("control-v1", "http-stream-v1", "webrtc-v1")))
                                .toString()
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                        when (message.optString("type")) {
                            "welcome" -> {
                                if (message.optString("deviceId") != peer.id ||
                                    message.optInt("protocolVersion", -1) != PEER_PROTOCOL_VERSION
                                ) {
                                    webSocket.close(1008, "Peer identity or protocol mismatch")
                                    return
                                }
                                PeerNetworkState.peers[peer.id] = JSONObject()
                                    .put("id", peer.id)
                                    .put("name", message.optString("deviceName", peer.name))
                                    .put("platform", message.optString("platform", peer.platform))
                                    .put("host", peer.host)
                                    .put("port", peer.port)
                                    .put("connected", true)
                                    .put("trusted", getSharedPreferences("scinet-trust", MODE_PRIVATE).contains(peer.id))
                                    .put("lastSeen", System.currentTimeMillis())
                                opened.complete(true)
                            }
                            "heartbeat" -> webSocket.send(
                                JSONObject().put("type", "heartbeat_ack")
                                    .put("deviceId", deviceId)
                                    .put("sentAt", message.optLong("sentAt"))
                                    .toString()
                            )
                            "heartbeat_ack" -> PeerNetworkState.peers[peer.id]?.put("lastSeen", System.currentTimeMillis())
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        opened.complete(false)
                        closed.complete(Unit)
                        PeerNetworkState.peers.remove(peer.id)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        opened.complete(false)
                        closed.complete(Unit)
                        PeerNetworkState.peers.remove(peer.id)
                    }
                })
                sockets[peer.id] = socket

                val connected = withTimeoutOrNull(8.seconds) { opened.await() } ?: false
                if (!connected) {
                    socket.cancel()
                } else {
                    while (isActive && !closed.isCompleted) {
                        delay(5.seconds)
                        socket.send(
                            JSONObject().put("type", "heartbeat")
                                .put("sentAt", System.currentTimeMillis())
                                .toString()
                        )
                    }
                }
                sockets.remove(peer.id, socket)
                PeerNetworkState.peers.remove(peer.id)
                if (isActive && endpoints.containsKey(peer.id)) delay(3.seconds)
            }
        }
    }

    private fun acquireMulticastLock() {
        runCatching {
            multicastLock = getSystemService(WifiManager::class.java)
                .createMulticastLock("SciNETPeerDiscovery")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PEER_CHANNEL_ID,
                "SciNET peer network",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the SciNET device server available on the local network." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification =
        Notification.Builder(this, PEER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("SciNET peer network")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(PEER_NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        PeerNetworkState.serverRunning = false
        runCatching { discoveryListener?.let { nsdManager.stopServiceDiscovery(it) } }
        runCatching { registrationListener?.let { nsdManager.unregisterService(it) } }
        sockets.values.forEach { it.close(1001, "SciNET service stopped") }
        connectionJobs.values.forEach { it.cancel() }
        runCatching { httpServer?.stop(250, 1000) }
        multicastLock?.let { lock -> if (lock.isHeld) lock.release() }
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
