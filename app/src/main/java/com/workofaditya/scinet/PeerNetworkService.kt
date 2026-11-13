package com.workofaditya.scinet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.media.AudioManager
import android.media.ToneGenerator
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

private const val PEER_PORT = 8765
private const val PEER_SERVICE_TYPE = "_scinet._tcp."
private const val PEER_PROTOCOL_VERSION = 2
private const val PEER_CHANNEL_ID = "scinet-mesh-network"
private const val PEER_NOTIFICATION_ID = 8765

internal object PeerNetworkState {
    @Volatile var localDeviceId: String = ""
    @Volatile var localDeviceName: String = "Android device"

    val peers = ConcurrentHashMap<String, JSONObject>()
    val knownPeers = ConcurrentHashMap<String, JSONObject>()
    val reportedNodes = ConcurrentHashMap<String, JSONObject>()
    val reportedLinks = ConcurrentHashMap<String, JSONObject>()
    private val messageSenders = ConcurrentHashMap<String, (String) -> Boolean>()

    fun registerMessageSender(peerId: String, sender: (String) -> Boolean) {
        messageSenders[peerId] = sender
    }

    fun unregisterMessageSender(peerId: String) {
        messageSenders.remove(peerId)
    }

    fun sendPeerMessage(peerId: String, message: String): Boolean =
        messageSenders[peerId]?.invoke(message) ?: false

    private fun linkKey(a: String, b: String): String = listOf(a, b).sorted().joinToString("|")

    fun mergeTopology(topology: JSONObject?) {
        if (topology == null) return
        val now = System.currentTimeMillis()
        val nodes = topology.optJSONArray("nodes") ?: org.json.JSONArray()
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            val id = node.optString("id")
            if (id.isBlank() || id == localDeviceId || id.length > 64) continue
            val incoming = JSONObject(node.toString())
            if (!incoming.has("updatedAt")) incoming.put("updatedAt", now)
            val old = reportedNodes[id]
            if (old == null || incoming.optLong("updatedAt") >= old.optLong("updatedAt")) {
                reportedNodes[id] = incoming
            }
        }

        val links = topology.optJSONArray("links") ?: org.json.JSONArray()
        for (index in 0 until links.length()) {
            val link = links.optJSONObject(index) ?: continue
            val a = link.optString("a")
            val b = link.optString("b")
            if (a.isBlank() || b.isBlank() || a == b || a.length > 64 || b.length > 64) continue
            val incoming = JSONObject(link.toString())
            if (!incoming.has("updatedAt")) incoming.put("updatedAt", now)
            val key = linkKey(a, b)
            val old = reportedLinks[key]
            if (old == null || incoming.optLong("updatedAt") >= old.optLong("updatedAt")) {
                reportedLinks[key] = incoming
            }
        }
    }

    fun topology(): JSONObject {
        val now = System.currentTimeMillis()
        val nodes = LinkedHashMap<String, JSONObject>()
        if (localDeviceId.isNotBlank()) {
            nodes[localDeviceId] = JSONObject()
                .put("id", localDeviceId)
                .put("name", localDeviceName)
                .put("platform", "android")
                .put("connected", true)
                .put("self", true)
                .put("updatedAt", now)
        }

        for ((id, node) in reportedNodes) nodes[id] = JSONObject(node.toString())
        for ((id, node) in knownPeers) {
            val current = peers[id]
            nodes[id] = JSONObject()
                .put("id", id)
                .put("name", current?.optString("name")?.ifBlank { node.optString("name") } ?: node.optString("name", "SciNET device"))
                .put("platform", current?.optString("platform")?.ifBlank { node.optString("platform") } ?: node.optString("platform", "unknown"))
                .put("connected", current?.optBoolean("connected") == true)
                .put("self", false)
                .put("updatedAt", current?.optLong("lastSeen")?.takeIf { it > 0L } ?: node.optLong("updatedAt", now))
        }
        for ((id, node) in peers) {
            if (!nodes.containsKey(id)) nodes[id] = JSONObject(node.toString()).put("self", false)
        }

        val links = LinkedHashMap<String, JSONObject>()
        val allPeerIds = (knownPeers.keys + peers.keys).toSet()
        for (id in allPeerIds) {
            if (id.isBlank() || id == localDeviceId) continue
            val peer = peers[id]
            val known = knownPeers[id]
            val connected = peer?.optBoolean("connected") == true
            val updatedAt = peer?.optLong("lastSeen")?.takeIf { it > 0L }
                ?: known?.optLong("updatedAt")?.takeIf { it > 0L }
                ?: now
            links[linkKey(localDeviceId, id)] = JSONObject()
                .put("a", localDeviceId)
                .put("b", id)
                .put("connected", connected)
                .put("updatedAt", updatedAt)
        }

        for ((key, remote) in reportedLinks) {
            if (links.containsKey(key)) continue
            val updatedAt = remote.optLong("updatedAt")
            val fresh = updatedAt > 0L && now - updatedAt <= 20000L
            links[key] = JSONObject(remote.toString()).put("connected", remote.optBoolean("connected") && fresh)
        }

        for (link in links.values) {
            for (id in listOf(link.optString("a"), link.optString("b"))) {
                if (!nodes.containsKey(id) && id.isNotBlank() && id != localDeviceId) {
                    val known = reportedNodes[id]
                    nodes[id] = JSONObject()
                        .put("id", id)
                        .put("name", known?.optString("name", "SciNET device") ?: "SciNET device")
                        .put("platform", known?.optString("platform", "unknown") ?: "unknown")
                        .put("connected", false)
                        .put("self", false)
                        .put("updatedAt", known?.optLong("updatedAt") ?: 0L)
                }
            }
        }

        for ((id, node) in nodes) {
            if (id == localDeviceId) continue
            node.put("connected", links.values.any {
                (it.optString("a") == id || it.optString("b") == id) && it.optBoolean("connected")
            })
        }

        return JSONObject()
            .put("selfId", localDeviceId)
            .put("nodes", org.json.JSONArray(nodes.values.toList()))
            .put("links", org.json.JSONArray(links.values.toList()))
            .put("updatedAt", now)
    }
}

/** Keeps this Android device available as a peer server while dialing discovered peers. */
class PeerNetworkService : Service() {
    private data class PeerEndpoint(
        val id: String,
        val name: String,
        val platform: String,
        val host: String,
        val port: Int
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val endpoints = ConcurrentHashMap<String, PeerEndpoint>()
    private val serviceResolutionQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolvingService = AtomicBoolean(false)
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
        val preferences = getSharedPreferences("scinet-peer", Context.MODE_PRIVATE)
        deviceId = preferences.getString("device_id", null)
            ?: UUID.randomUUID().toString().replace("-", "").also {
                preferences.edit().putString("device_id", it).apply()
            }
        deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"

        PeerNetworkState.localDeviceId = deviceId
        PeerNetworkState.localDeviceName = deviceName
        nsdManager = getSystemService(NsdManager::class.java)
        createNotificationChannel()
        acquireMulticastLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Starting local node and discovering peers")
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
        startConnectionFeedbackMonitor()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startConnectionFeedbackMonitor() {
        scope.launch {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)
            val previous = mutableSetOf<String>()
            try {
                while (isActive) {
                    val current = PeerNetworkState.peers.keys.toSet()
                    val connected = current - previous
                    val disconnected = previous - current
                    if (connected.isNotEmpty()) {
                        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
                        delay(130)
                        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
                    }
                    if (disconnected.isNotEmpty()) {
                        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 110)
                    }
                    previous.clear()
                    previous.addAll(current)
                    delay(350)
                }
            } finally {
                tone.release()
            }
        }
    }

    private fun startPeerServer() {
        try {
            httpServer = embeddedServer(CIO, host = "0.0.0.0", port = PEER_PORT) {
                install(WebSockets) {
                    pingPeriod = 20.seconds
                    timeout = 35.seconds
                    maxFrameSize = 64L * 1024L
                }
                routing {

                    get("/apk/{token}/SciNET.apk") {
                        val remoteHost = call.request.local.remoteHost
                        val token = call.parameters["token"].orEmpty()
                        val apk = ApkBootstrap.fileForToken(token)
                        if (!isPrivateLanAddress(remoteHost) || apk == null || !apk.isFile || !apk.canRead()) {
                            call.respondText(
                                "This SciNET APK link is invalid or has expired.",
                                ContentType.Text.Plain,
                                HttpStatusCode.Forbidden
                            )
                            return@get
                        }
                        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=SciNET.apk")
                        call.respondFile(apk)
                    }
                    get("/health") {
                        call.respondText(
                            JSONObject()
                                .put("service", "SciNET")
                                .put("status", "online")
                                .put("deviceId", deviceId)
                                .put("protocolVersion", PEER_PROTOCOL_VERSION)
                                .toString(),
                            ContentType.Application.Json
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
                            ContentType.Application.Json
                        )
                    }
                    get("/api/peer/topology") {
                        call.respondText(PeerNetworkState.topology().toString(), ContentType.Application.Json)
                    }
                    get("/api/transfers/{transferId}/chunk") {
                        val remoteHost = call.request.local.remoteHost
                        val senderId = call.request.headers["X-SciNET-From"].orEmpty()
                        val transferId = call.parameters["transferId"].orEmpty()
                        val peer = PeerNetworkState.peers[senderId]
                        if (!isPrivateLanAddress(remoteHost) || peer?.optBoolean("connected") != true ||
                            (peer.optString("host").isNotBlank() && peer.optString("host") != remoteHost)
                        ) {
                            call.respondText(
                                JSONObject().put("error", "Only a directly connected LAN peer can download").toString(),
                                ContentType.Application.Json,
                                HttpStatusCode.Forbidden
                            )
                            return@get
                        }

                        val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: -1L
                        val length = call.request.queryParameters["length"]?.toIntOrNull() ?: -1
                        if (transferId.isBlank() || offset < 0L || length !in 1..(2 * 1024 * 1024)) {
                            call.respondText(
                                JSONObject().put("error", "Invalid chunk request").toString(),
                                ContentType.Application.Json,
                                HttpStatusCode.BadRequest
                            )
                            return@get
                        }

                        val bytes = FileTransferManager.readOutgoingChunk(
                            this@PeerNetworkService, senderId, transferId, offset, length
                        )
                        if (bytes == null) {
                            call.respondText(
                                JSONObject().put("error", "Transfer is not available").toString(),
                                ContentType.Application.Json,
                                HttpStatusCode.Conflict
                            )
                        } else {
                            call.respondBytes(bytes, ContentType.Application.OctetStream)
                        }
                    }
                    webSocket("/ws/peer") {
                        val remoteAddress = call.request.local.remoteHost
                        if (!isPrivateLanAddress(remoteAddress)) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "SciNET peers must use a private local network"))
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
                            remoteId.isBlank() || remoteId.length > 64 ||
                            remoteId == deviceId || version != PEER_PROTOCOL_VERSION
                        ) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid SciNET peer hello"))
                            return@webSocket
                        }

                        val peerRecord = JSONObject()
                            .put("id", remoteId)
                            .put("name", remoteName.ifBlank { "SciNET device" }.take(80))
                            .put("platform", remotePlatform.ifBlank { "unknown" }.take(24))
                            .put("host", remoteAddress)
                            .put("port", PEER_PORT)
                            .put("connected", true)
                            .put("lastSeen", System.currentTimeMillis())
                        PeerNetworkState.peers[remoteId] = peerRecord
                        PeerNetworkState.knownPeers[remoteId] =
                            JSONObject(peerRecord.toString()).put("updatedAt", System.currentTimeMillis())
                        PeerNetworkState.mergeTopology(hello.optJSONObject("topology"))
                        PeerNetworkState.registerMessageSender(remoteId) { payload ->
                            scope.launch { runCatching { outgoing.send(Frame.Text(payload)) } }
                            true
                        }

                        outgoing.send(
                            Frame.Text(
                                JSONObject()
                                    .put("type", "welcome")
                                    .put("protocolVersion", PEER_PROTOCOL_VERSION)
                                    .put("deviceId", deviceId)
                                    .put("deviceName", deviceName)
                                    .put("platform", "android")
                                    .put("capabilities", org.json.JSONArray(listOf("mesh-v2", "topology-v1", "file-transfer-v1", "text-v1")))
                                    .put("topology", PeerNetworkState.topology())
                                    .toString()
                            )
                        )

                        try {
                            for (frame in incoming) {
                                if (frame !is Frame.Text) continue
                                val message = runCatching { JSONObject(frame.readText()) }.getOrNull() ?: continue
                                PeerNetworkState.mergeTopology(message.optJSONObject("topology"))
                                FileTransferManager.handleSignal(applicationContext, remoteId, message)
                                if (message.optString("type") == "heartbeat") {
                                    peerRecord.put("lastSeen", System.currentTimeMillis())
                                    PeerNetworkState.knownPeers[remoteId]
                                        ?.put("updatedAt", System.currentTimeMillis())
                                    outgoing.send(
                                        Frame.Text(
                                            JSONObject()
                                                .put("type", "heartbeat_ack")
                                                .put("deviceId", deviceId)
                                                .put("sentAt", message.optLong("sentAt"))
                                                .put("topology", PeerNetworkState.topology())
                                                .toString()
                                        )
                                    )
                                }
                            }
                        } finally {
                            PeerNetworkState.unregisterMessageSender(remoteId)
                            PeerNetworkState.peers.remove(remoteId, peerRecord)
                            PeerNetworkState.knownPeers[remoteId]
                                ?.put("connected", false)
                                ?.put("updatedAt", System.currentTimeMillis())
                        }
                    }
                }
            }.start(wait = false)

            runCatching { startNsd() }
            updateNotification("Peer server active • discovering nearby SciNET nodes")
        } catch (error: Exception) {
            httpServer = null
            updateNotification("Peer server could not start: " + (error.message ?: "port unavailable"))
        }
    }

    private fun startNsd() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "SciNET-" + deviceId.take(8)
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
                if (!service.serviceName.startsWith("SciNET-")) return
                serviceResolutionQueue.add(service)
                resolveNextService()
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                serviceResolutionQueue.removeIf { it.serviceName == service.serviceName }
                val shortId = service.serviceName.substringAfter("SciNET-", "").takeIf { it.isNotBlank() }
                if (shortId != null) {
                    val peerId = endpoints.keys.firstOrNull { it.startsWith(shortId, ignoreCase = true) }
                    if (peerId != null) {
                        endpoints.remove(peerId)
                        PeerNetworkState.knownPeers[peerId]
                            ?.put("connected", false)
                            ?.put("updatedAt", System.currentTimeMillis())
                        sockets[peerId]?.cancel()
                    }
                }
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener!!)
        nsdManager.discoverServices(PEER_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener!!)
    }

    private fun resolveNextService() {
        if (!resolvingService.compareAndSet(false, true)) return
        val service = serviceResolutionQueue.poll()
        if (service == null) {
            resolvingService.set(false)
            if (serviceResolutionQueue.isNotEmpty()) resolveNextService()
            return
        }

        fun finishResolution() {
            resolvingService.set(false)
            if (serviceResolutionQueue.isNotEmpty()) resolveNextService()
        }

        try {
            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    finishResolution()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    try {
                        val id = info.attributes["id"]?.toString(Charsets.UTF_8).orEmpty()
                        val host = info.host?.hostAddress.orEmpty()
                        val version = info.attributes["protocol"]?.toString(Charsets.UTF_8)?.toIntOrNull()
                        if (id.isBlank() || id == deviceId || host.isBlank() || host.contains(":") ||
                            version != PEER_PROTOCOL_VERSION || info.port !in 1..65535
                        ) return

                        val endpoint = PeerEndpoint(
                            id = id,
                            name = info.attributes["name"]?.toString(Charsets.UTF_8).orEmpty()
                                .ifBlank { info.serviceName },
                            platform = info.attributes["platform"]?.toString(Charsets.UTF_8).orEmpty()
                                .ifBlank { "unknown" },
                            host = host,
                            port = info.port
                        )
                        endpoints[id] = endpoint
                        PeerNetworkState.knownPeers[id] = JSONObject()
                            .put("id", id)
                            .put("name", endpoint.name)
                            .put("platform", endpoint.platform)
                            .put("host", host)
                            .put("port", endpoint.port)
                            .put("connected", PeerNetworkState.peers[id]?.optBoolean("connected") == true)
                            .put("updatedAt", System.currentTimeMillis())
                        startPeerConnection(endpoint)
                    } finally {
                        finishResolution()
                    }
                }
            })
        } catch (_: Exception) {
            finishResolution()
        }
    }

    private fun startPeerConnection(peer: PeerEndpoint) {
        // The lower stable ID dials; the other node accepts that incoming socket.
        if (deviceId >= peer.id) return
        if (connectionJobs[peer.id]?.isActive == true) return

        connectionJobs[peer.id] = scope.launch {
            while (isActive && endpoints.containsKey(peer.id)) {
                val opened = CompletableDeferred<Boolean>()
                val closed = CompletableDeferred<Unit>()
                val request = Request.Builder()
                    .url("ws://" + peer.host + ":" + peer.port + "/ws/peer")
                    .header("User-Agent", "SciNET Android Mesh/1")
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
                                .put("port", PEER_PORT)
                                .put("capabilities", org.json.JSONArray(listOf("mesh-v2", "topology-v1", "file-transfer-v1", "text-v1")))
                                .put("topology", PeerNetworkState.topology())
                                .toString()
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                        PeerNetworkState.mergeTopology(message.optJSONObject("topology"))
                        FileTransferManager.handleSignal(applicationContext, peer.id, message)
                        when (message.optString("type")) {
                            "welcome" -> {
                                if (message.optString("deviceId") != peer.id ||
                                    message.optInt("protocolVersion", -1) != PEER_PROTOCOL_VERSION
                                ) {
                                    webSocket.close(1008, "Peer identity or protocol mismatch")
                                    return
                                }
                                val record = JSONObject()
                                    .put("id", peer.id)
                                    .put("name", message.optString("deviceName", peer.name))
                                    .put("platform", message.optString("platform", peer.platform))
                                    .put("host", peer.host)
                                    .put("port", peer.port)
                                    .put("connected", true)
                                    .put("lastSeen", System.currentTimeMillis())
                                PeerNetworkState.peers[peer.id] = record
                                PeerNetworkState.knownPeers[peer.id] =
                                    JSONObject(record.toString()).put("updatedAt", System.currentTimeMillis())
                                PeerNetworkState.mergeTopology(message.optJSONObject("topology"))
                                PeerNetworkState.registerMessageSender(peer.id) { payload -> webSocket.send(payload) }
                                opened.complete(true)
                            }
                            "heartbeat" -> webSocket.send(
                                JSONObject()
                                    .put("type", "heartbeat_ack")
                                    .put("deviceId", deviceId)
                                    .put("sentAt", message.optLong("sentAt"))
                                    .put("topology", PeerNetworkState.topology())
                                    .toString()
                            )
                            "heartbeat_ack" -> {
                                PeerNetworkState.peers[peer.id]?.put("lastSeen", System.currentTimeMillis())
                                PeerNetworkState.knownPeers[peer.id]?.put("updatedAt", System.currentTimeMillis())
                            }
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        opened.complete(false)
                        closed.complete(Unit)
                        PeerNetworkState.unregisterMessageSender(peer.id)
                        PeerNetworkState.peers.remove(peer.id)
                        PeerNetworkState.knownPeers[peer.id]
                            ?.put("connected", false)
                            ?.put("updatedAt", System.currentTimeMillis())
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        opened.complete(false)
                        closed.complete(Unit)
                        PeerNetworkState.unregisterMessageSender(peer.id)
                        PeerNetworkState.peers.remove(peer.id)
                        PeerNetworkState.knownPeers[peer.id]
                            ?.put("connected", false)
                            ?.put("updatedAt", System.currentTimeMillis())
                    }
                })
                sockets[peer.id] = socket

                val connected = withTimeoutOrNull(8.seconds) { opened.await() } ?: false
                if (!connected) {
                    socket.cancel()
                } else {
                    while (isActive && !closed.isCompleted && endpoints.containsKey(peer.id)) {
                        delay(5.seconds)
                        if (!socket.send(
                                JSONObject()
                                    .put("type", "heartbeat")
                                    .put("sentAt", System.currentTimeMillis())
                                    .put("topology", PeerNetworkState.topology())
                                    .toString()
                            )
                        ) break
                    }
                }

                sockets.remove(peer.id, socket)
                PeerNetworkState.peers.remove(peer.id)
                PeerNetworkState.knownPeers[peer.id]
                    ?.put("connected", false)
                    ?.put("updatedAt", System.currentTimeMillis())
                if (isActive && endpoints.containsKey(peer.id)) delay(3.seconds)
            }
        }
    }

    private fun isPrivateLanAddress(host: String): Boolean {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress
    }

    private fun acquireMulticastLock() {
        runCatching {
            multicastLock = getSystemService(WifiManager::class.java)
                .createMulticastLock("SciNETMeshDiscovery")
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
                "SciNET mesh network",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps this SciNET node discoverable on the local network."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification =
        Notification.Builder(this, PEER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("SciNET mesh node")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(PEER_NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        runCatching { discoveryListener?.let { nsdManager.stopServiceDiscovery(it) } }
        runCatching { registrationListener?.let { nsdManager.unregisterService(it) } }
        serviceResolutionQueue.clear()
        resolvingService.set(false)
        sockets.values.forEach { it.close(1001, "SciNET node stopped") }
        connectionJobs.values.forEach { it.cancel() }
        runCatching { httpServer?.stop(250, 1000) }
        multicastLock?.let { lock -> if (lock.isHeld) lock.release() }
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
