package com.workofaditya.scinet

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.InetAddresses
import android.os.Build
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Short-lived, read-only HTTP server for sharing this installation's APK over the local network.
 * The URL contains a random session token and is valid only while the sharing panel remains open.
 */
internal object InviteApkServer {
    data class Session(
        val server: InviteApkServerInstance,
        val url: String,
        val qrCode: Bitmap
    )

    fun start(context: Context): Session {
        val packageInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        val splitPaths = packageInfo.splitSourceDirs
        require(splitPaths.isNullOrEmpty()) {
            "This installation uses split APKs. A single APK cannot be shared safely."
        }

        val apk = File(packageInfo.sourceDir)
        require(apk.isFile && apk.length() > 0L) { "Installed APK is unavailable" }

        val localAddress = findLocalIpv4(context)
            ?: throw IllegalStateException("Connect to the same Wi-Fi network first")
        val token = UUID.randomUUID().toString().replace("-", "")
        val server = InviteApkServerInstance(apk, token)
        val port = server.start()
        val url = "http://$localAddress:$port/$token/SciNET.apk"
        val matrix = QRCodeWriter().encode(
            url,
            BarcodeFormat.QR_CODE,
            512,
            512,
            mapOf(EncodeHintType.MARGIN to 1)
        )
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (y in 0 until 512) {
            for (x in 0 until 512) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return Session(server, url, bitmap)
    }

    private fun findLocalIpv4(context: Context): String? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // On a phone providing a hotspot, activeNetwork can be the mobile-data
        // network. Its 10.x carrier address is not reachable by hotspot clients.
        // Prefer addresses assigned to Android's Wi-Fi/AP interfaces instead.
        val interfaces = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .mapNotNull { networkInterface ->
                    val addresses = Collections.list(networkInterface.inetAddresses)
                        .filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
                    if (addresses.isEmpty()) null else networkInterface.name.lowercase() to addresses
                }
        } catch (_: Exception) {
            emptyList()
        }

        val apInterfacePriority = listOf(
            "ap0", "swlan0", "wlan1", "wlan0", "softap0", "wifiap"
        )
        for (name in apInterfacePriority) {
            val address = interfaces.firstOrNull { (interfaceName, _) ->
                interfaceName == name || interfaceName.startsWith("$name.")
            }?.second?.firstOrNull()
            if (address != null) return address.hostAddress
        }

        // Some manufacturers use different AP interface names. Prefer any
        // non-cellular, non-VPN Wi-Fi-like interface before consulting Android's
        // active network properties.
        val likelyWifi = interfaces.firstOrNull { (name, _) ->
            (name.contains("wlan") || name.contains("wifi") || name.contains("softap") ||
                name.contains("ap")) &&
                listOf("rmnet", "ccmni", "pdp", "wwan", "tun", "tap", "p2p", "clat")
                    .none { name.contains(it) }
        }?.second?.firstOrNull()
        if (likelyWifi != null) return likelyWifi.hostAddress

        val active = manager.activeNetwork
        if (active != null) {
            val capabilities = manager.getNetworkCapabilities(active)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            ) {
                val address = manager.getLinkProperties(active)?.linkAddresses
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                if (address != null) return address.hostAddress
            }
        }

        // Last resort: a private address that is not clearly cellular/VPN.
        return interfaces.firstOrNull { (name, _) ->
            listOf("rmnet", "ccmni", "pdp", "wwan", "tun", "tap", "p2p", "clat", "lo")
                .none { name.contains(it) }
        }?.second?.firstOrNull()?.hostAddress
    }
}

internal class InviteApkServerInstance(
    private val apk: File,
    private val token: String
) {
    @Volatile private var running = true
    private var socket: ServerSocket? = null
    private var worker: Thread? = null

    fun start(): Int {
        val server = ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
        server.soTimeout = 1000
        socket = server
        worker = thread(name = "SciNET-APK-share", isDaemon = true) {
            val expiresAt = System.currentTimeMillis() + SESSION_LIFETIME_MS
            while (running && System.currentTimeMillis() < expiresAt) {
                try {
                    server.accept().use { client ->
                        client.soTimeout = 15_000
                        handle(client)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Check whether the temporary sharing session has expired.
                } catch (_: Exception) {
                    if (running) continue
                }
            }
            close()
        }
        return server.localPort
    }

    private fun handle(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
        val requestLine = reader.readLine() ?: return
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        if (method != "GET" && method != "HEAD") {
            respond(client, "405 Method Not Allowed", "text/plain", "Method not allowed".toByteArray())
            return
        }
        if (path != "/$token/SciNET.apk") {
            respond(client, "404 Not Found", "text/plain", "Not found".toByteArray())
            return
        }

        val output = client.getOutputStream()
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/vnd.android.package-archive\r\n")
            append("Content-Disposition: attachment; filename=\"SciNET.apk\"\r\n")
            append("Content-Length: ${apk.length()}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.flush()
        if (method == "GET") apk.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
        output.flush()
    }

    private fun respond(client: Socket, status: String, mime: String, body: ByteArray) {
        val output = client.getOutputStream()
        output.write(
            ("HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.US_ASCII)
        )
        output.write(body)
        output.flush()
    }

    fun close() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val SESSION_LIFETIME_MS = 10 * 60 * 1000L
    }
}
