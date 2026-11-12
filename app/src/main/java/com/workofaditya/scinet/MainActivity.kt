package com.workofaditya.scinet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val Background = Color(0xFF080B10)
private val LocalNodeColor = Color(0xFFF0F4F8)
private val PeerNodeColor = Color(0xFF83C9B0)
private val OfflineNodeColor = Color(0xFF596575)
private val ActiveLinkColor = Color(0xFF6CA995)
private val InactiveLinkColor = Color(0xFF35404D)
private val PanelColor = Color(0xF0111720)
private val LabelColor = Color(0xFFB1BECA)
private val LabelPaintColor = android.graphics.Color.rgb(177, 190, 202)

private data class PeerTarget(val id: String, val name: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, PeerNetworkService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Background,
                    surface = Background,
                    primary = LocalNodeColor,
                    onPrimary = Background,
                    onBackground = LocalNodeColor,
                    onSurface = LocalNodeColor
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                    MeshGraphScreen()
                }
            }
        }
    }
}

@Composable
private fun MeshGraphScreen() {
    val context = LocalContext.current
    var topology by remember { mutableStateOf(JSONObject()) }
    var transfers by remember { mutableStateOf(emptyList<TransferUiItem>()) }
    var messagePulses by remember { mutableStateOf(emptyList<MessagePulse>()) }
    var incomingMessage by remember { mutableStateOf<IncomingTextMessage?>(null) }
    var pendingFilePeer by remember { mutableStateOf<PeerTarget?>(null) }
    var messageTarget by remember { mutableStateOf<PeerTarget?>(null) }
    var messageDraft by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pendingFilePeer
        if (uri != null && target != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            FileTransferManager.beginOutgoing(context, target.id, target.name, uri)
        }
        pendingFilePeer = null
    }

    LaunchedEffect(Unit) {
        while (true) {
            topology = PeerNetworkState.topology()
            transfers = FileTransferManager.items()
            if (incomingMessage == null) {
                incomingMessage = FileTransferManager.pendingMessages().firstOrNull()
            }
            delay(300)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            messagePulses = FileTransferManager.messagePulses()
            delay(40)
        }
    }

    val nodes = remember(topology) {
        val array = topology.optJSONArray("nodes")
        if (array == null) emptyList() else
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { JSONObject(it.toString()) }
            }
    }
    val links = remember(topology) {
        val array = topology.optJSONArray("links")
        if (array == null) emptyList() else
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { JSONObject(it.toString()) }
            }
    }
    val selfId = topology.optString("selfId")
    val visibleTransfers = transfers.filter { it.status != "offered" }.takeLast(3)
    val offeredFile = transfers.firstOrNull { it.direction == "incoming" && it.status == "offered" }

    Box(modifier = Modifier.fillMaxSize()) {
        MeshGraph(
            nodes = nodes,
            links = links,
            selfId = selfId,
            activeTransfers = transfers.filter { it.status == "transferring" },
            messagePulses = messagePulses,
            onPeerTap = { peer ->
                pendingFilePeer = peer
                filePicker.launch(arrayOf("*/*"))
            },
            onPeerLongPress = { peer ->
                messageTarget = peer
                messageDraft = ""
            }
        )

        if (visibleTransfers.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                visibleTransfers.forEach { item ->
                    TransferRow(
                        item = item,
                        onPauseResume = {
                            FileTransferManager.control(
                                context, item.id,
                                if (item.status == "paused") "resume" else "pause"
                            )
                        },
                        onCancel = { FileTransferManager.dismissTransfer(context, item.id) }
                    )
                }
            }
        }
    }

    if (messageTarget != null) {
        val target = messageTarget!!
        AlertDialog(
            onDismissRequest = { messageTarget = null },
            title = { Text("Message · " + target.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                OutlinedTextField(
                    value = messageDraft,
                    onValueChange = { messageDraft = it.take(16000) },
                    placeholder = { Text("Write a message") },
                    minLines = 2,
                    maxLines = 6
                )
            },
            confirmButton = {
                TextButton(
                    enabled = messageDraft.isNotBlank(),
                    onClick = {
                        FileTransferManager.sendText(target.id, messageDraft)
                        messageTarget = null
                    }
                ) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { messageTarget = null }) { Text("Cancel") }
            },
            containerColor = Color(0xFF121923),
            titleContentColor = LocalNodeColor,
            textContentColor = LabelColor
        )
    }

    if (incomingMessage != null) {
        val message = incomingMessage!!
        AlertDialog(
            onDismissRequest = {
                FileTransferManager.dismissMessage(message.id)
                incomingMessage = null
            },
            title = { Text("Message · " + message.peerName) },
            text = { Text(message.text) },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("SciNET message", message.text))
                    FileTransferManager.dismissMessage(message.id)
                    incomingMessage = null
                }) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    FileTransferManager.dismissMessage(message.id)
                    incomingMessage = null
                }) { Text("Close") }
            },
            containerColor = Color(0xFF121923),
            titleContentColor = LocalNodeColor,
            textContentColor = LabelColor
        )
    } else if (offeredFile != null) {
        AlertDialog(
            onDismissRequest = { FileTransferManager.declineIncoming(offeredFile.id) },
            title = { Text("Incoming file") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(offeredFile.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        offeredFile.peerName + " · " + formatBytes(offeredFile.totalBytes),
                        color = LabelColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { FileTransferManager.acceptIncoming(context, offeredFile.id) }) {
                    Text("Accept")
                }
            },
            dismissButton = {
                TextButton(onClick = { FileTransferManager.declineIncoming(offeredFile.id) }) {
                    Text("Decline")
                }
            },
            containerColor = Color(0xFF121923),
            titleContentColor = LocalNodeColor,
            textContentColor = LabelColor
        )
    }
}

@Composable
private fun TransferRow(
    item: TransferUiItem,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit
) {
    val progress = if (item.totalBytes > 0L) {
        item.transferredBytes.toFloat() / item.totalBytes.toFloat()
    } else if (item.status == "completed") 1f else 0f
    val terminal = item.status in setOf("completed", "cancelled", "rejected", "failed")
    val statusLabel = when (item.status) {
        "offering" -> "Waiting"
        "paused" -> "Paused"
        "verifying" -> "Saving"
        "completed" -> "Complete"
        "cancelled" -> "Cancelled"
        "rejected" -> "Declined"
        "failed" -> "Failed"
        else -> formatBytes(item.transferredBytes) + " / " + formatBytes(item.totalBytes)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = PanelColor)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    item.fileName,
                    color = LocalNodeColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(3.dp),
                        color = PeerNodeColor,
                        trackColor = InactiveLinkColor
                    )
                    Text(
                        statusLabel,
                        color = LabelColor,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
            if (!terminal && item.status != "offering" && item.status != "verifying") {
                IconButton(onClick = onPauseResume, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (item.status == "paused") Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (item.status == "paused") "Resume transfer" else "Pause transfer",
                        tint = LocalNodeColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = if (terminal) "Dismiss transfer" else "Cancel transfer",
                    tint = LabelColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun MeshGraph(
    nodes: List<JSONObject>,
    links: List<JSONObject>,
    selfId: String,
    activeTransfers: List<TransferUiItem>,
    messagePulses: List<MessagePulse>,
    onPeerTap: (PeerTarget) -> Unit,
    onPeerLongPress: (PeerTarget) -> Unit
) {
    val density = LocalDensity.current
    val labelSize = with(density) { 11.sp.toPx() }
    val peerRadius = with(density) { 7.dp.toPx() }
    val labelOffset = with(density) { 20.dp.toPx() }
    val currentNodes by rememberUpdatedState(nodes)
    val currentSelfId by rememberUpdatedState(selfId)
    val currentLabelSize by rememberUpdatedState(labelSize)
    val currentPeerRadius by rememberUpdatedState(peerRadius)
    val currentLabelOffset by rememberUpdatedState(labelOffset)
    val currentOnPeerTap by rememberUpdatedState(onPeerTap)
    val currentOnPeerLongPress by rememberUpdatedState(onPeerLongPress)
    val hasAnimations = activeTransfers.isNotEmpty() || messagePulses.isNotEmpty()
    var animationTime by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(hasAnimations) {
        if (hasAnimations) {
            while (true) {
                animationTime = System.currentTimeMillis()
                delay(16)
            }
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { position ->
                        findPeerAt(
                            position.x, position.y, currentNodes, currentSelfId,
                            size.width.toFloat(), size.height.toFloat(),
                            currentLabelSize, currentPeerRadius, currentLabelOffset
                        )?.let(currentOnPeerTap)
                    },
                    onLongPress = { position ->
                        findPeerAt(
                            position.x, position.y, currentNodes, currentSelfId,
                            size.width.toFloat(), size.height.toFloat(),
                            currentLabelSize, currentPeerRadius, currentLabelOffset
                        )?.let(currentOnPeerLongPress)
                    }
                )
            }
    ) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val peers = nodes
            .filterNot { it.optString("id") == selfId || it.optBoolean("self") }
            .sortedBy { it.optString("id") }
        val positions = graphPeerPositions(
            peers, size.width, size.height
        ).toMutableMap()
        nodes.firstOrNull { it.optString("id") == selfId || it.optBoolean("self") }?.let {
            positions[it.optString("id")] = center
        }
        if (selfId.isNotBlank()) positions[selfId] = center

        links.forEach { link ->
            val a = positions[link.optString("a")] ?: return@forEach
            val b = positions[link.optString("b")] ?: return@forEach
            val connected = link.optBoolean("connected")
            drawLine(
                color = if (connected) ActiveLinkColor.copy(alpha = 0.78f) else InactiveLinkColor,
                start = a,
                end = b,
                strokeWidth = if (connected) 1.6.dp.toPx() else 1.dp.toPx()
            )
        }

        fun drawFlash(start: androidx.compose.ui.geometry.Offset, end: androidx.compose.ui.geometry.Offset, progress: Float, radius: Float) {
            val point = androidx.compose.ui.geometry.Offset(
                start.x + (end.x - start.x) * progress,
                start.y + (end.y - start.y) * progress
            )
            drawCircle(Color.White.copy(alpha = 0.18f), radius = radius * 2.8f, center = point)
            drawCircle(Color.White.copy(alpha = 0.42f), radius = radius * 1.7f, center = point)
            drawCircle(Color.White, radius = radius, center = point)
        }

        activeTransfers.forEach { transfer ->
            val peerPoint = positions[transfer.peerId] ?: return@forEach
            val localToPeer = transfer.direction == "outgoing"
            val start = if (localToPeer) center else peerPoint
            val end = if (localToPeer) peerPoint else center
            val progress = (animationTime % 1450L).toFloat() / 1450f
            drawFlash(start, end, progress, 2.4.dp.toPx())
        }

        messagePulses.forEach { pulse ->
            val age = (animationTime - pulse.createdAt).coerceAtLeast(0L)
            if (age > 700L) return@forEach
            val peerPoint = positions[pulse.peerId] ?: return@forEach
            val localToPeer = pulse.fromLocal
            val start = if (localToPeer) center else peerPoint
            val end = if (localToPeer) peerPoint else center
            drawFlash(start, end, age.toFloat() / 700f, 2.8.dp.toPx())
        }

        val nativeCanvas = drawContext.canvas.nativeCanvas
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LabelPaintColor
            textSize = labelSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        nodes.forEach { node ->
            val id = node.optString("id")
            val point = positions[id] ?: return@forEach
            val isSelf = id == selfId || node.optBoolean("self")
            val isConnected = isSelf || node.optBoolean("connected")
            val nodeColor = when {
                isSelf -> LocalNodeColor
                isConnected -> PeerNodeColor
                else -> OfflineNodeColor
            }
            val radius = if (isSelf) 9.dp.toPx() else peerRadius

            if (isSelf) {
                drawCircle(
                    color = LocalNodeColor.copy(alpha = 0.10f),
                    radius = 25.dp.toPx(),
                    center = point
                )
                drawCircle(
                    color = LocalNodeColor.copy(alpha = 0.42f),
                    radius = 17.dp.toPx(),
                    center = point,
                    style = Stroke(width = 1.dp.toPx())
                )
            } else if (isConnected) {
                drawCircle(
                    color = PeerNodeColor.copy(alpha = 0.10f),
                    radius = 17.dp.toPx(),
                    center = point
                )
            }

            drawCircle(color = nodeColor, radius = radius, center = point)
            nativeCanvas.drawText(
                node.optString("name").ifBlank { "SciNET node" }.take(22),
                point.x,
                point.y + radius + labelOffset,
                labelPaint
            )
        }
    }
}

private fun graphPeerPositions(
    peers: List<JSONObject>,
    width: Float,
    height: Float
): Map<String, androidx.compose.ui.geometry.Offset> {
    val centerX = width / 2f
    val centerY = height / 2f
    val availableRadius = minOf(width, height) * 0.37f
    val positions = HashMap<String, androidx.compose.ui.geometry.Offset>()
    peers.forEachIndexed { index, node ->
        val ring = index / 10
        val indexInRing = index % 10
        val countInRing = minOf(10, peers.size - ring * 10)
        val ringRadius = availableRadius * (0.72f + ring * 0.34f)
        val angle = -PI / 2.0 + (2.0 * PI * indexInRing / countInRing)
        positions[node.optString("id")] = androidx.compose.ui.geometry.Offset(
            centerX + cos(angle).toFloat() * ringRadius,
            centerY + sin(angle).toFloat() * ringRadius
        )
    }
    return positions
}

private fun findPeerAt(
    x: Float,
    y: Float,
    nodes: List<JSONObject>,
    selfId: String,
    width: Float,
    height: Float,
    labelSize: Float,
    peerRadius: Float,
    labelOffset: Float
): PeerTarget? {
    val peers = nodes
        .filterNot { it.optString("id") == selfId || it.optBoolean("self") }
        .filter { it.optBoolean("connected") }
        .sortedBy { it.optString("id") }
    val positions = graphPeerPositions(peers, width, height)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = labelSize }
    return peers.firstOrNull { node ->
        val point = positions[node.optString("id")] ?: return@firstOrNull false
        val name = node.optString("name").ifBlank { "SciNET node" }.take(22)
        val halfWidth = paint.measureText(name) / 2f + 9f
        val baseline = point.y + peerRadius + labelOffset
        x >= point.x - halfWidth && x <= point.x + halfWidth &&
            y >= baseline - labelSize - 4f && y <= baseline + 5f
    }?.let { PeerTarget(it.optString("id"), it.optString("name").ifBlank { "SciNET device" }) }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}
