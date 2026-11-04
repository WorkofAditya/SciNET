package com.workofaditya.scinet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
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
    val peers = ConcurrentHashMap<String, JSONObject>()
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
    private var httpServer: ApplicationEngine? = null
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
                    webSocket("/ws/peer") {
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
                            .put("connected", true)
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
            setAttribute("id", deviceId.toByteArray(Charsets.UTF_8))
            setAttribute("name", deviceName.take(60).toByteArray(Charsets.UTF_8))
            setAttribute("platform", "android".toByteArray(Charsets.UTF_8))
            setAttribute("protocol", PEER_PROTOCOL_VERSION.toString().toByteArray(Charsets.UTF_8))
            setAttribute("ws", "/ws/peer".toByteArray(Charsets.UTF_8))
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
                                PeerNetworkState.peers[peer.id] = JSONObject()
                                    .put("id", peer.id)
                                    .put("name", message.optString("deviceName", peer.name))
                                    .put("platform", message.optString("platform", peer.platform))
                                    .put("connected", true)
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
