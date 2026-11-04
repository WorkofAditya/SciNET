package com.workofaditya.scinet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

private data class PeerTransferProgress(
    val label: String,
    val fileName: String,
    val transferred: Long,
    val total: Long?
)

@Composable
internal fun PeerTransferScreen(
    peer: JSONObject,
    onBack: () -> Unit,
    onMessage: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val peerId = peer.optString("id")
    val peerName = peer.optString("name", "SciNET device")
    val peerHost = peer.optString("host")
    val peerPort = peer.optInt("port", 8765)
    val peerKey = remember(peerId) {
        context.getSharedPreferences("scinet-trust", Context.MODE_PRIVATE).getString(peerId, "").orEmpty()
    }
    var incoming by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var progress by remember { mutableStateOf<PeerTransferProgress?>(null) }
    var pendingSaveId by remember { mutableStateOf("") }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            if (peerHost.isBlank() || peerKey.isBlank()) {
                onMessage("This peer is not paired or its network address is unavailable.", true)
                return@launch
            }
            var sent = 0
            uris.forEachIndexed { index, uri ->
                val fileName = peerDisplayName(context, uri)
                try {
                    SciNETApi.uploadToPeer(
                        context = context,
                        host = "http://$peerHost:$peerPort",
                        peerId = peerId,
                        peerKey = peerKey,
                        uri = uri,
                        fileName = fileName
                    ) { transferred, total ->
                        withContext(Dispatchers.Main.immediate) {
                            progress = PeerTransferProgress(
                                label = if (uris.size > 1) "SENDING \${index + 1}/\${uris.size}" else "SENDING",
                                fileName = fileName,
                                transferred = transferred,
                                total = total
                            )
                        }
                    }
                    sent++
                } catch (error: Exception) {
                    onMessage(error.message ?: "Could not send $fileName.", true)
                }
            }
            progress = null
            if (sent > 0) onMessage("Sent $sent file(s) to $peerName.", false)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destination ->
        val transferId = pendingSaveId
        pendingSaveId = ""
        if (destination != null && transferId.isNotBlank()) scope.launch {
            val file = File(File(context.filesDir, "peer-inbox"), transferId)
            val item = PeerNetworkState.incomingTransfers[transferId]
            if (!file.isFile || item == null) {
                onMessage("That incoming file is no longer available.", true)
                return@launch
            }
            try {
                val total = item.optLong("size").takeIf { it >= 0L }
                var copied = 0L
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(destination)
                        ?: throw IllegalStateException("Android could not create the destination file.")
                    FileInputStream(file).use { input ->
                        output.use { sink ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                sink.write(buffer, 0, count)
                                copied += count
                                withContext(Dispatchers.Main.immediate) {
                                    progress = PeerTransferProgress("SAVING", item.optString("name"), copied, total)
                                }
                            }
                        }
                    }
                }
                PeerNetworkState.removeIncoming(context, transferId)
                onMessage("Downloaded \${item.optString("name")} to your chosen location.", false)
            } catch (error: Exception) {
                onMessage(error.message ?: "Could not save the incoming file.", true)
            } finally {
                progress = null
            }
        }
    }

    LaunchedEffect(peerId) {
        while (true) {
            incoming = PeerNetworkState.incomingTransfers.values
                .filter { it.optString("senderId") == peerId }
                .sortedByDescending { it.optLong("receivedAt") }
                .map { JSONObject(it.toString()) }
            delay(700)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("←  ALL DEVICES", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text("PAIRED DEVICE", color = Good, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        PageHeading("PEER-TO-PEER", peerName, "Direct transfer between these two SciNET devices. No PC host or shared folder is involved.")
        PanelCard {
            SectionHeading("DEVICE LINK", if (peerHost.isBlank()) "Waiting for a direct network route" else "Connected over your local network")
            Text(peer.optString("platform", "ANDROID").uppercase() + "  ·  " + peerHost, color = Muted, fontSize = 10.sp)
            Text("Files are streamed directly to the receiving phone's private incoming queue. The receiver chooses where to save each file.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            PrimaryButton("↑  SEND FILES TO THIS DEVICE", peerHost.isNotBlank() && peerKey.isNotBlank()) {
                uploadLauncher.launch(arrayOf("*/*"))
            }
        }

        progress?.let { current ->
            val percent = current.total?.takeIf { it > 0L }?.let {
                (current.transferred * 100L / it).toInt().coerceIn(0, 100)
            }
            PanelCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(current.label, color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(percent?.let { "$it%" } ?: peerFormatBytes(current.transferred), color = Ink, fontSize = 10.sp)
                }
                Text(current.fileName, color = Ink, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(
                    progress = { percent?.div(100f) ?: 0f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = Accent,
                    trackColor = Edge
                )
                Text(peerFormatBytes(current.transferred) + " / " + (current.total?.let { peerFormatBytes(it) } ?: "unknown"), color = Muted, fontSize = 9.sp)
            }
        }

        PanelCard {
            SectionHeading("INCOMING FILES", "\${incoming.size} waiting to be downloaded")
            if (incoming.isEmpty()) {
                Text("Nothing waiting here. Files sent by this device will appear in this inbox.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            } else {
                incoming.forEach { item ->
                    val id = item.optString("id")
                    Column(
                        Modifier.fillMaxWidth().background(Raised, RoundedCornerShape(9.dp))
                            .border(1.dp, Edge, RoundedCornerShape(9.dp)).padding(11.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(item.optString("name"), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(peerFormatBytes(item.optLong("size")) + "  ·  from " + item.optString("senderName", peerName), color = Muted, fontSize = 9.sp)
                        OutlinedButton(
                            onClick = {
                                pendingSaveId = id
                                saveLauncher.launch(item.optString("name").ifBlank { "scinet-download" })
                            },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Edge),
                            shape = RoundedCornerShape(7.dp)
                        ) {
                            Text("↓  DOWNLOAD TO THIS PHONE", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        Text("The original PC file manager is unchanged. This inbox is only for direct device-to-device transfers.", color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
    }
}

private fun peerDisplayName(context: Context, uri: Uri): String {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
}

private fun peerFormatBytes(value: Long): String {
    if (value < 1024L) return "$value B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var amount = value.toDouble()
    var unit = -1
    while (amount >= 1024 && unit < units.lastIndex) {
        amount /= 1024.0
        unit++
    }
    return String.format(java.util.Locale.US, "%.1f %s", amount, units[unit])
}


@Composable
internal fun PeerDevicesScreen(
    peers: List<JSONObject>,
    onOpen: (JSONObject) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeading("LOCAL NETWORK", "Nearby devices", "Open a paired device to send files directly or download files it has sent you.")
        if (peers.isEmpty()) {
            PanelCard {
                SectionHeading("PEER DISCOVERY", "No devices connected yet")
                Text("Keep SciNET open on another phone on the same local network. Scan its QR code to pair securely.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        } else {
            peers.forEach { peer ->
                val trusted = peer.optBoolean("trusted")
                PanelCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(peer.optString("name", "SciNET device"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                peer.optString("platform", "device").uppercase() + "  ·  " +
                                    if (peer.optBoolean("connected")) "ONLINE" else "DISCOVERED",
                                color = Muted,
                                fontSize = 9.sp,
                                letterSpacing = 1.sp
                            )
                        }
                        Text(if (trusted) "PAIRED" else "NOT PAIRED", color = if (trusted) Good else Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                    if (trusted) {
                        PrimaryButton("OPEN DEVICE") { onOpen(peer) }
                    } else {
                        Text("Scan this device's QR code to establish trust before sending files.", color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
        PanelCard {
            SectionHeading("DIRECT TRANSFER", "No shared directory")
            Text("A sender streams a file directly to the paired device. The receiving phone gets an incoming item and chooses where to save it. The PC host's existing file workflow remains separate.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}
