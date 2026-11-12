package com.workofaditya.scinet

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
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
private val LabelColor = android.graphics.Color.rgb(177, 190, 202)

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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Background
                ) {
                    MeshGraphScreen()
                }
            }
        }
    }
}

@Composable
private fun MeshGraphScreen() {
    var topology by remember { mutableStateOf(JSONObject()) }

    LaunchedEffect(Unit) {
        while (true) {
            topology = PeerNetworkState.topology()
            delay(800)
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

    MeshGraph(
        nodes = nodes,
        links = links,
        selfId = topology.optString("selfId")
    )
}

@Composable
private fun MeshGraph(
    nodes: List<JSONObject>,
    links: List<JSONObject>,
    selfId: String
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val self = nodes.firstOrNull {
            it.optString("id") == selfId || it.optBoolean("self")
        }
        val peers = nodes
            .filterNot { it.optString("id") == selfId || it.optBoolean("self") }
            .sortedBy { it.optString("id") }

        val positions = HashMap<String, androidx.compose.ui.geometry.Offset>()
        if (self != null && selfId.isNotBlank()) positions[selfId] = center
        else if (self != null) positions[self.optString("id")] = center

        val availableRadius = minOf(size.width, size.height) * 0.37f
        peers.forEachIndexed { index, node ->
            val ring = index / 10
            val indexInRing = index % 10
            val countInRing = minOf(10, peers.size - ring * 10)
            val ringRadius = availableRadius * (0.72f + ring * 0.34f)
            val angle = -PI / 2.0 + (2.0 * PI * indexInRing / countInRing)
            val point = androidx.compose.ui.geometry.Offset(
                center.x + cos(angle).toFloat() * ringRadius,
                center.y + sin(angle).toFloat() * ringRadius
            )
            positions[node.optString("id")] = point
        }

        // Draw only links that are actually present in the reported topology.
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

        val nativeCanvas = drawContext.canvas.nativeCanvas
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LabelColor
            textSize = 11.sp.toPx()
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
            val radius = if (isSelf) 9.dp.toPx() else 7.dp.toPx()

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

            drawCircle(
                color = nodeColor,
                radius = radius,
                center = point
            )

            val name = node.optString("name")
                .ifBlank { "SciNET node" }
                .take(22)
            nativeCanvas.drawText(
                name,
                point.x,
                point.y + radius + 20.dp.toPx(),
                labelPaint
            )
        }
    }
}
