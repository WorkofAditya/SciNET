package com.workofaditya.scinet

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Locale

private val Background = Color(0xFF090C10)
private val Panel = Color(0xFF111820)
private val Raised = Color(0xFF1A2530)
private val Border = Color(0xFF293541)
private val TextPrimary = Color(0xFFE8EDF2)
private val TextMuted = Color(0xFF8B98A5)
private val Connected = Color(0xFF83C99A)
private val Disconnected = Color(0xFFE58C86)
private val LocalNode = Color(0xFFE5EAF0)

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
                    surface = Panel,
                    primary = LocalNode,
                    onPrimary = Background,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary,
                    secondary = TextMuted
                )
            ) {
                SciNETMeshApp()
            }
        }
    }
}

@Composable
private fun SciNETMeshApp() {
    var topology by remember { mutableStateOf(JSONObject()) }
    var serverRunning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            topology = PeerNetworkState.topology()
            serverRunning = PeerNetworkState.serverRunning
            delay(1000)
        }
    }

    val nodes = remember(topology) {
        val array = topology.optJSONArray("nodes")
        if (array == null) emptyList() else
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { node -> JSONObject(node.toString()) } }
    }
    val links = remember(topology) {
        val array = topology.optJSONArray("links")
        if (array == null) emptyList() else
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { link -> JSONObject(link.toString()) } }
    }
    val selfId = topology.optString("selfId")
    val connectedLinks = links.count { it.optBoolean("connected") }
    val directLinks = links.count {
        it.optBoolean("connected") &&
            (it.optString("a") == selfId || it.optString("b") == selfId)
    }
    val ready = serverRunning && selfId.isNotBlank()

    Surface(Modifier.fillMaxSize(), color = Background) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.material3.Text(
                        "SCINET",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp
                    )
                    androidx.compose.material3.Text(
                        "DECENTRALIZED MESH NETWORK",
                        color = TextMuted,
                        fontSize = 9.sp,
                        letterSpacing = 1.4.sp
                    )
                }
                Surface(
                    color = if (ready) Color(0xFF173323) else Raised,
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (ready) Color(0xFF315F42) else Border)
                ) {
                    Row(
                        Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Box(
                            Modifier.size(7.dp)
                                .background(if (ready) Connected else TextMuted, RoundedCornerShape(50))
                        )
                        androidx.compose.material3.Text(
                            if (ready) "NODE ONLINE" else "STARTING NODE",
                            color = if (ready) Connected else TextMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = .7.sp
                        )
                    }
                }
            }

            androidx.compose.material3.Text(
                "Every device is a node.",
                color = TextPrimary,
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold
            )
            androidx.compose.material3.Text(
                "SciNET runs a server and a client on this device. Nearby nodes are discovered automatically and connected directly. No central host is required.",
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricCard("NODES", nodes.size.toString(), Modifier.weight(1f))
                MetricCard("LINKS UP", connectedLinks.toString(), Modifier.weight(1f))
                MetricCard("DIRECT", directLinks.toString(), Modifier.weight(1f))
            }

            Column(
                Modifier.fillMaxWidth()
                    .background(Panel, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        androidx.compose.material3.Text(
                            "LIVE NETWORK",
                            color = TextMuted,
                            fontSize = 9.sp,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        androidx.compose.material3.Text(
                            "Mesh topology",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    androidx.compose.material3.Text(
                        nodes.size.toString() + " NODES",
                        color = TextMuted,
                        fontSize = 9.sp
                    )
                }

                if (nodes.isEmpty()) {
                    androidx.compose.material3.Text(
                        "Waiting for the local node to start…",
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    MeshGraph(nodes, links, selfId)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegendDot(Connected, "CONNECTED")
                    LegendDot(Disconnected, "LINK DOWN")
                    LegendDot(LocalNode, "THIS DEVICE")
                }
            }

            Column(
                Modifier.fillMaxWidth()
                    .background(Panel, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                androidx.compose.material3.Text(
                    "DISCOVERED NODES",
                    color = TextMuted,
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold
                )
                if (nodes.size <= 1) {
                    androidx.compose.material3.Text(
                        "No other SciNET devices found yet. Open SciNET on another device connected to the same local network.",
                        color = TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    nodes.sortedWith(compareBy<JSONObject> { !it.optBoolean("self") }.thenBy { it.optString("name").lowercase(Locale.ROOT) })
                        .forEach { node ->
                            MeshNodeRow(node, node.optString("id") == selfId || node.optBoolean("self"))
                            androidx.compose.material3.HorizontalDivider(color = Border)
                        }
                }
            }

            androidx.compose.material3.Text(
                "AUTO DISCOVERY  ·  mDNS / DNS-SD\nDIRECT PEER LINKS  ·  WEBSOCKET\nTOPOLOGY SYNC  ·  HEARTBEAT",
                color = TextMuted,
                fontSize = 9.sp,
                lineHeight = 16.sp,
                letterSpacing = .7.sp
            )
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Panel, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        androidx.compose.material3.Text(label, color = TextMuted, fontSize = 8.sp, letterSpacing = 1.sp)
        androidx.compose.material3.Text(value, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MeshGraph(nodes: List<JSONObject>, links: List<JSONObject>, selfId: String) {
    Canvas(
        Modifier.fillMaxWidth().height(285.dp)
            .background(Background, RoundedCornerShape(9.dp))
    ) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val radius = if (nodes.size <= 1) 0f else minOf(size.width * .34f, size.height * .32f)
        val positions = nodes.mapIndexed { index, _ ->
            if (nodes.size <= 1) center else {
                val angle = (-Math.PI / 2.0) + (2.0 * Math.PI * index / nodes.size)
                androidx.compose.ui.geometry.Offset(
                    center.x + kotlin.math.cos(angle).toFloat() * radius,
                    center.y + kotlin.math.sin(angle).toFloat() * radius
                )
            }
        }
        fun findLink(a: String, b: String): JSONObject? = links.firstOrNull {
            (it.optString("a") == a && it.optString("b") == b) ||
                (it.optString("a") == b && it.optString("b") == a)
        }

        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val link = findLink(nodes[i].optString("id"), nodes[j].optString("id"))
                drawLine(
                    color = if (link?.optBoolean("connected") == true) Color(0xFF718394) else Color(0xFF874E50),
                    start = positions[i],
                    end = positions[j],
                    strokeWidth = 1.8.dp.toPx()
                )
            }
        }

        val native = drawContext.canvas.nativeCanvas
        val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(229, 235, 242)
            textSize = 10.sp.toPx()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(139, 152, 165)
            textSize = 8.sp.toPx()
            textAlign = Paint.Align.CENTER
        }

        nodes.forEachIndexed { index, node ->
            val point = positions[index]
            val isSelf = node.optString("id") == selfId || node.optBoolean("self")
            val online = isSelf || node.optBoolean("connected")
            nodePaint.color = when {
                isSelf -> android.graphics.Color.rgb(229, 234, 240)
                online -> android.graphics.Color.rgb(131, 201, 154)
                else -> android.graphics.Color.rgb(229, 140, 134)
            }
            val nodeRadius = if (nodes.size > 12) 7.dp.toPx() else 10.dp.toPx()
            drawCircle(Color(nodePaint.color), nodeRadius, point)
            val name = node.optString("name", "SciNET").ifBlank { "SciNET" }
            val label = if (name.length > 13) name.take(11) + "…" else name
            native.drawText(label, point.x, point.y + nodeRadius + 14.dp.toPx(), labelPaint)
            native.drawText(
                if (isSelf) "THIS DEVICE" else if (online) "ONLINE" else "OFFLINE",
                point.x,
                point.y + nodeRadius + 25.dp.toPx(),
                detailPaint
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).background(color, RoundedCornerShape(50)))
        androidx.compose.material3.Text(label, color = TextMuted, fontSize = 8.sp)
    }
}

@Composable
private fun MeshNodeRow(node: JSONObject, isSelf: Boolean) {
    val connected = isSelf || node.optBoolean("connected")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(32.dp).background(Raised, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Text(
                if (isSelf) "●" else "◉",
                color = if (isSelf) LocalNode else if (connected) Connected else Disconnected,
                fontSize = 14.sp
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            androidx.compose.material3.Text(
                node.optString("name", "SciNET device").ifBlank { "SciNET device" },
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            androidx.compose.material3.Text(
                node.optString("platform", "unknown").uppercase(Locale.ROOT) +
                    "  ·  " + node.optString("id").take(8).uppercase(Locale.ROOT),
                color = TextMuted,
                fontSize = 8.sp,
                letterSpacing = .5.sp
            )
        }
        androidx.compose.material3.Text(
            if (isSelf) "THIS NODE" else if (connected) "ONLINE" else "OFFLINE",
            color = if (connected) Connected else Disconnected,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
