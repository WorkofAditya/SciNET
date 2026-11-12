package com.workofaditya.scinet

import android.content.Context
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.io.File

data class ApkShareSession(
    val urls: List<String>,
    val qr: Bitmap
)

internal object ApkBootstrap {
    @Volatile private var activeToken: String? = null
    @Volatile private var activeApk: File? = null

    fun create(context: Context): ApkShareSession {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        require(info.splitSourceDirs.isNullOrEmpty()) {
            "This installation uses split APKs and cannot be shared as one APK file."
        }
        val apk = File(info.sourceDir)
        require(apk.isFile && apk.canRead()) { "Installed APK is not readable." }

        val tokenBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = tokenBytes.joinToString("") { "%02x".format(it) }
        val addresses = localIpv4Addresses()
        require(addresses.isNotEmpty()) {
            "No local Wi-Fi or hotspot IPv4 address was found. Turn on Wi-Fi or your hotspot and try again."
        }

        activeApk = apk
        activeToken = token
        val urls = addresses.map { "http://$it:8765/apk/$token/SciNET.apk" }
        val matrix = MultiFormatWriter().encode(urls.first(), BarcodeFormat.QR_CODE, 640, 640)
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        for (y in 0 until 640) {
            for (x in 0 until 640) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return ApkShareSession(urls, bitmap)
    }

    fun fileForToken(token: String): File? {
        if (token.isBlank() || token != activeToken) return null
        return activeApk
    }

    private fun localIpv4Addresses(): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (runCatching { !network.isUp || network.isLoopback }.getOrDefault(true)) continue
            val name = network.name.lowercase()
            val priority = when {
                "ap" in name || "swlan" in name || "softap" in name -> 0
                "wlan" in name || "wifi" in name -> 1
                "rndis" in name || "usb" in name -> 2
                "eth" in name -> 3
                else -> 4
            }
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && address.isSiteLocalAddress &&
                    !address.isLoopbackAddress && !address.isLinkLocalAddress
                ) {
                    found += priority to address.hostAddress.orEmpty()
                }
            }
        }
        return found.distinctBy { it.second }.sortedWith(compareBy<Pair<Int, String>> { it.first }.thenBy { it.second }).map { it.second }
    }
}
