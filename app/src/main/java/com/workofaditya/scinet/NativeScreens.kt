package com.workofaditya.scinet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DashboardScreen(data: JSONObject, events: List<JSONObject>, host: String, onRefresh: () -> Unit, onRename: (String) -> Unit, onClearEvents: () -> Unit, onLogout: () -> Unit) {
    val memory = data.optJSONObject("memory") ?: JSONObject()
    val disk = data.optJSONObject("disk") ?: JSONObject()
    val network = data.optJSONObject("network") ?: JSONObject()
    val context = LocalContext.current
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(host) {
        qrBitmap = withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(SciNETApi.normalizeHost(host) + "/api/qr").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "SciNET Android/0.1")
                }
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null } finally { connection?.disconnect() }
        }
    }
    val devices = data.optJSONArray("devices").objects()
    var previousReceived by remember { mutableStateOf(-1L) }
    var previousSent by remember { mutableStateOf(-1L) }
    var previousSample by remember { mutableStateOf(0L) }
    var downloadRate by remember { mutableStateOf(0L) }
    var uploadRate by remember { mutableStateOf(0L) }
    var trafficHistory by remember { mutableStateOf(listOf(0f)) }
    LaunchedEffect(data) {
        val now = System.currentTimeMillis()
        val received = network.optLong("received")
        val sent = network.optLong("sent")
        if (previousReceived >= 0 && previousSent >= 0 && previousSample > 0) {
            val seconds = ((now - previousSample).coerceAtLeast(1L)) / 1000.0
            downloadRate = ((received - previousReceived).coerceAtLeast(0L) / seconds).toLong()
            uploadRate = ((sent - previousSent).coerceAtLeast(0L) / seconds).toLong()
        }
        if (previousReceived >= 0 && previousSent >= 0 && previousSample > 0) {
            trafficHistory = (trafficHistory + (downloadRate + uploadRate).toFloat()).takeLast(40)
        }
        previousReceived = received
        previousSent = sent
        previousSample = now
    }
    val selfId = data.optString("self_device_id")
    var renameOpen by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf(devices.firstOrNull { it.optString("id") == selfId }?.optString("name", "Android device") ?: "Android device") }
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SYSTEM", color = Accent, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Text("Overview", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(data.optString("hostname", "SCINET-CORE").uppercase(Locale.getDefault()), color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            }
            OutlinedButton(onClick = onRefresh, border = androidx.compose.foundation.BorderStroke(1.dp, Edge), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Text("↻ REFRESH", fontSize = 9.sp, color = Ink)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { StatCard("CPU", data.optDouble("cpu").toInt().toString() + "%", "Processor load") }
            Box(Modifier.weight(1f)) { StatCard("MEMORY", memory.optDouble("percent").toInt().toString() + "%", formatBytes(memory.optLong("used")) + " / " + formatBytes(memory.optLong("total"))) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { StatCard("DISK", disk.optDouble("percent").toInt().toString() + "%", formatBytes(disk.optLong("used")) + " / " + formatBytes(disk.optLong("total"))) }
            Box(Modifier.weight(1f)) { StatCard("UPTIME", formatUptime(data.optLong("uptime")), "Current session") }
        }
        PanelCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionHeading("NETWORK", "Traffic")
                Text("LIVE", color = Good, fontSize = 9.sp, letterSpacing = 1.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).background(Bg, RoundedCornerShape(7.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("DOWNLOAD", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
                    Text(formatBytes(downloadRate) + "/s", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f).background(Bg, RoundedCornerShape(7.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("UPLOAD", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
                    Text(formatBytes(uploadRate) + "/s", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Canvas(Modifier.fillMaxWidth().height(105.dp).background(Bg, RoundedCornerShape(7.dp)).padding(horizontal = 3.dp, vertical = 5.dp)) {
                val values = trafficHistory
                val maxValue = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
                val points = values.mapIndexed { index, value ->
                    androidx.compose.ui.geometry.Offset(
                        if (values.size <= 1) 0f else size.width * index / (values.size - 1),
                        size.height - (value / maxValue) * (size.height * .82f) - size.height * .08f
                    )
                }
                for (line in 1..3) {
                    val y = size.height * line / 4f
                    drawLine(Edge, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1.dp.toPx())
                }
                if (points.size > 1) {
                    val path = Path().apply { moveTo(points.first().x, points.first().y); points.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(path, Accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RECEIVED  " + formatBytes(network.optLong("received")), color = Muted, fontSize = 9.sp)
                Text("SENT  " + formatBytes(network.optLong("sent")), color = Muted, fontSize = 9.sp)
            }
            Text("Live traffic history · sampled every second.", color = Muted, fontSize = 10.sp)
        }
        PanelCard {
            SectionHeading("CONNECTION", "Scan to open SciNET")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (qrBitmap != null) Image(bitmap = qrBitmap!!.asImageBitmap(), contentDescription = "SciNET connection QR code", modifier = Modifier.size(124.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(host, fontSize = 10.sp, lineHeight = 15.sp)
                    OutlinedButton(onClick = {
                        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        manager.setPrimaryClip(ClipData.newPlainText("SciNET address", host))
                    }, border = androidx.compose.foundation.BorderStroke(1.dp, Edge), contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp)) { Text("COPY ADDRESS", fontSize = 9.sp) }
                    Text("FTP: " + runCatching { URL(host).host }.getOrDefault(host) + ":2121", color = Muted, fontSize = 9.sp)
                    Text("User scinet · password scinet", color = Muted, fontSize = 9.sp)
                }
            }
        }
        PanelCard {
            SectionHeading("NETWORK", "Connected devices · " + devices.size)
            if (devices.isEmpty()) EmptyText("Waiting for devices…")
            devices.forEach { device ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(34.dp).background(Raised, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        Text(if (device.optString("type") == "Host") "▣" else "⌘", color = Accent, fontSize = 17.sp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(device.optString("name", device.optString("agent", "Device")), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(device.optString("ip", "unknown") + ":" + device.optInt("port"), color = Muted, fontSize = 10.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(device.optString("type").uppercase(), color = Muted, fontSize = 8.sp)
                        if (selfId.isNotBlank() && device.optString("id") == selfId) TextButton(onClick = { renameOpen = true }, contentPadding = PaddingValues(0.dp)) { Text("RENAME", color = Accent, fontSize = 8.sp) }
                    }
                }
                HorizontalDivider(color = Edge)
            }
        }
        PanelCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionHeading("ACTIVITY", "Recent events")
                TextButton(onClick = onClearEvents, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("CLEAR", color = Muted, fontSize = 9.sp) }
            }
            if (events.isEmpty()) EmptyText("No activity yet.")
            Column(Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                events.take(30).forEach { event ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((event.optDouble("time") * 1000).toLong())), color = Muted, fontSize = 9.sp, modifier = Modifier.width(52.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(event.optString("kind", "info").uppercase(), color = Accent, fontSize = 8.sp, letterSpacing = 1.sp)
                            Text(event.optString("message"), fontSize = 11.sp, lineHeight = 15.sp, softWrap = true)
                        }
                    }
                    HorizontalDivider(color = Edge)
                }
            }
        }
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(1.dp, Edge), shape = RoundedCornerShape(8.dp)) {
            Text("LOG OUT / CHANGE PC", color = Ink, fontSize = 10.sp, letterSpacing = 1.sp)
        }
    }
    if (renameOpen) AlertDialog(
        onDismissRequest = { renameOpen = false },
        title = { Text("Rename this device") },
        text = { OutlinedTextField(value = renameValue, onValueChange = { renameValue = it.take(40) }, label = { Text("Device name") }, singleLine = true, colors = fieldColors()) },
        confirmButton = { TextButton(onClick = { val clean = renameValue.trim(); if (clean.isNotEmpty()) { renameOpen = false; onRename(clean) } }) { Text("SAVE", color = Accent) } },
        dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("CANCEL") } },
        containerColor = Panel
    )
}

@Composable
internal fun ControlScreen(host: String, onAction: (String, Map<String, Any?>) -> Unit, onMessage: (String, Boolean) -> Unit) {
    var scrollMode by remember { mutableStateOf(false) }
    var typedText by remember { mutableStateOf("") }
    val modifiers = remember { mutableStateListOf<String>() }
    var pendingPower by remember { mutableStateOf("") }
    val padBackground by animateColorAsState(if (scrollMode) Color(0xFF17251C) else Color(0xFF0B0E12), label = "touchpad background")
    val padBorder by animateColorAsState(if (scrollMode) Color(0xFF4D805C) else Edge, label = "touchpad border")
    val padAccent by animateColorAsState(if (scrollMode) Color(0xFFB9E6C4) else Color(0xFF9BA5B1), label = "touchpad accent")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeading("REMOTE ACCESS", "PC Control", "Control media, input and power on the SciNET host.")
        PanelCard {
            SectionHeading("MEDIA", "Playback")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ControlTile("⏮", "PREVIOUS", Modifier.weight(1f)) { onAction("previous", emptyMap()) }
                ControlTile("⏯", "PLAY / PAUSE", Modifier.weight(1f), true) { onAction("play-pause", emptyMap()) }
                ControlTile("⏭", "NEXT", Modifier.weight(1f)) { onAction("next", emptyMap()) }
            }
        }
        PanelCard {
            SectionHeading("AUDIO", "System volume")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ControlTile("−", "VOLUME DOWN", Modifier.weight(1f)) { onAction("volume-down", emptyMap()) }
                ControlTile("◖", "MUTE", Modifier.weight(1f)) { onAction("mute", emptyMap()) }
                ControlTile("＋", "VOLUME UP", Modifier.weight(1f)) { onAction("volume-up", emptyMap()) }
            }
            Text("Uses standard Windows media keys.", color = Muted, fontSize = 10.sp)
        }
        PanelCard {
            LiveClipboardPanel(host, onMessage)
        }
        PanelCard {
            SectionHeading("INPUT", "Touchpad")
            Box(
                Modifier.fillMaxWidth().height(205.dp).background(padBackground, RoundedCornerShape(8.dp))
                    .border(1.dp, padBorder, RoundedCornerShape(8.dp))
                    .pointerInput(scrollMode) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            if (scrollMode) onAction("mouse-scroll", mapOf("delta" to (-drag.y * 6).toInt().coerceIn(-1200, 1200)))
                            else onAction("mouse-move", mapOf("dx" to (drag.x * 2).toInt().coerceIn(-1200, 1200), "dy" to (drag.y * 2).toInt().coerceIn(-1200, 1200)))
                        }
                    }
                    .pointerInput(scrollMode) {
                        detectTapGestures { if (!scrollMode) onAction("mouse-click", mapOf("button" to "left")) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.fillMaxHeight().width(1.dp).background(padBorder))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Edge))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(if (scrollMode) "↕" else "＋", color = padAccent, fontSize = 28.sp)
                    Text(if (scrollMode) "SCROLL MODE · SWIPE UP / DOWN" else "TOUCH & DRAG TO MOVE", color = if (scrollMode) Color(0xFFB9E6C4) else Muted, fontSize = 8.sp, letterSpacing = .6.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scrollMode = !scrollMode }, modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (scrollMode) Color(0xFF4D805C) else Edge),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (scrollMode) Color(0xFF17251C) else Color.Transparent, contentColor = if (scrollMode) Color(0xFFB9E6C4) else Ink),
                    shape = RoundedCornerShape(7.dp)
                ) { Text("↕ SCROLL " + if (scrollMode) "ON" else "OFF", fontSize = 10.sp) }
                OutlinedButton(onClick = { onAction("mouse-click", mapOf("button" to "right")) }, modifier = Modifier.weight(1f), border = androidx.compose.foundation.BorderStroke(1.dp, Edge), shape = RoundedCornerShape(7.dp)) {
                    Text("RIGHT CLICK", fontSize = 10.sp)
                }
            }
            Text("Tap to left-click when scroll mode is off.", color = Muted, fontSize = 10.sp)
        }
        PanelCard {
            SectionHeading("TEXT INPUT", "Keyboard")
            var lastSentText by remember { mutableStateOf("") }
            OutlinedTextField(value = typedText, onValueChange = { typedText = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Type here. Text is sent live…") }, minLines = 2, colors = fieldColors())
            LaunchedEffect(typedText) {
                kotlinx.coroutines.delay(120)
                val old = lastSentText
                val next = typedText.take(4000)
                if (next != old) {
                    var common = 0
                    while (common < old.length && common < next.length && old[common] == next[common]) common++
                    val removed = old.length - common
                    val activeModifiers = modifiers.toList()
                    if (removed > 0) {
                        repeat(removed) { index ->
                            onAction("key", mapOf(
                                "key" to "backspace",
                                "modifiers" to if (index == 0) activeModifiers else emptyList<String>()
                            ))
                        }
                        if (activeModifiers.isNotEmpty()) modifiers.clear()
                    }
                    val added = next.substring(common)
                    if (added.isNotEmpty()) {
                        if (activeModifiers.isNotEmpty()) {
                            val first = added.first()
                            val key = first.lowercaseChar().toString()
                            if (key.matches(Regex("[a-z0-9]"))) {
                                onAction("key", mapOf("key" to key, "modifiers" to activeModifiers))
                            } else {
                                onAction("type", mapOf("text" to first.toString()))
                            }
                            modifiers.clear()
                            if (added.length > 1) onAction("type", mapOf("text" to added.drop(1)))
                        } else {
                            onAction("type", mapOf("text" to added))
                        }
                    }
                    lastSentText = next
                }
            }
            Text("Text is sent automatically as you type. Select a modifier, then type one key for a shortcut; it resets after that key.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("ctrl" to "CTRL", "alt" to "ALT", "shift" to "SHIFT", "meta" to "WIN").forEach { (key, label) ->
                    val active = modifiers.contains(key)
                    OutlinedButton(
                        onClick = { if (active) modifiers.remove(key) else modifiers.add(key) },
                        modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) Accent else Edge),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (active) Accent else Ink),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) { Text(label, fontSize = 9.sp) }
                }
            }
            val keys = listOf("esc", "tab", "enter", "backspace", "delete", "up", "left", "down", "right", "home", "end")
            keys.chunked(4).forEach { group ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    group.forEach { key ->
                        OutlinedButton(
                            onClick = { onAction("key", mapOf("key" to key, "modifiers" to modifiers.toList())); modifiers.clear() },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Edge),
                            contentPadding = PaddingValues(horizontal = 1.dp, vertical = 3.dp)
                        ) { Text(key.uppercase(), fontSize = 9.sp) }
                    }
                    repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("FUNCTION KEYS", color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                Text("F1–F12", color = Muted, fontSize = 9.sp)
            }
            (1..12).map { "f$it" }.chunked(4).forEach { group ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    group.forEach { key ->
                        OutlinedButton(
                            onClick = { onAction("key", mapOf("key" to key, "modifiers" to modifiers.toList())); modifiers.clear() },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Edge),
                            contentPadding = PaddingValues(horizontal = 1.dp, vertical = 3.dp)
                        ) { Text(key.uppercase(), fontSize = 9.sp) }
                    }
                    repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        PanelCard {
            SectionHeading("POWER", "Windows power options")
            Text("These actions affect the host PC and can interrupt your connection.", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
            listOf("lock" to "LOCK PC", "sleep" to "SLEEP", "restart" to "RESTART", "shutdown" to "SHUT DOWN").forEach { (action, label) ->
                OutlinedButton(
                    onClick = { if (action == "lock") onAction(action, emptyMap()) else pendingPower = action },
                    modifier = Modifier.fillMaxWidth(),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (action == "shutdown") Color(0xFF57302F) else Edge),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (action == "shutdown") Bad else Ink),
                    shape = RoundedCornerShape(8.dp)
                ) { Text(label, fontSize = 10.sp, letterSpacing = 1.sp) }
            }
        }
    }
    if (pendingPower.isNotBlank()) AlertDialog(
        onDismissRequest = { pendingPower = "" },
        title = { Text("Confirm " + pendingPower.replaceFirstChar { it.uppercase() } + "?") },
        text = { Text("This command affects the Windows PC, not only the Android app.") },
        confirmButton = { TextButton(onClick = { val action = pendingPower; pendingPower = ""; onAction(action, emptyMap()) }) { Text("CONFIRM", color = Bad) } },
        dismissButton = { TextButton(onClick = { pendingPower = "" }) { Text("CANCEL") } },
        containerColor = Panel
    )
}

@Composable
private fun ControlTile(symbol: String, label: String, modifier: Modifier = Modifier, highlighted: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = modifier.height(84.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (highlighted) Color(0xFF3A424C) else Edge),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (highlighted) Raised else Bg, contentColor = Ink),
        shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(3.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(symbol, fontSize = 24.sp)
            Text(label, fontSize = 8.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun ControllerScreen(onAction: (String, Map<String, Any?>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeading("INPUT DEVICE", "Keyboard controller", "Hold mapped keys to control games that accept keyboard input.")
        Text("This is a keyboard-mapped controller, not an XInput gamepad. Games that require a physical gamepad need a separate Windows virtual-gamepad driver.", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
        PanelCard {
            SectionHeading("MOVEMENT", "Directional controls")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { HoldKey("W", "w", onAction, Modifier.width(76.dp)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                HoldKey("A", "a", onAction, Modifier.width(76.dp))
                Spacer(Modifier.width(8.dp))
                HoldKey("S", "s", onAction, Modifier.width(76.dp))
                Spacer(Modifier.width(8.dp))
                HoldKey("D", "d", onAction, Modifier.width(76.dp))
            }
            Text("Press and hold a key, then release to stop.", color = Muted, fontSize = 10.sp)
        }
        PanelCard {
            SectionHeading("ACTIONS", "Game keys")
            listOf("SPACE" to "space", "SHIFT" to "shift", "CTRL" to "ctrl", "E" to "e", "R" to "r", "F" to "f", "Q" to "q", "ESC" to "esc").chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (label, key) -> HoldKey(label, key, onAction, Modifier.weight(1f)) }
                }
            }
        }
        PanelCard {
            SectionHeading("MOUSE", "Primary actions")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAction("mouse-click", mapOf("button" to "left")) }, modifier = Modifier.weight(1f)) { Text("LEFT CLICK") }
                OutlinedButton(onClick = { onAction("mouse-click", mapOf("button" to "right")) }, modifier = Modifier.weight(1f)) { Text("RIGHT CLICK") }
            }
        }
    }
}

@Composable
private fun HoldKey(label: String, key: String, onAction: (String, Map<String, Any?>) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(58.dp).background(Raised, RoundedCornerShape(9.dp)).border(1.dp, Edge, RoundedCornerShape(9.dp))
            .pointerInput(key) {
                detectTapGestures(onPress = {
                    onAction("key-hold", mapOf("key" to key, "down" to true))
                    try { tryAwaitRelease() } finally { onAction("key-hold", mapOf("key" to key, "down" to false)) }
                })
            },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}

private class ScreenMirrorJavascriptBridge(
    private val onState: (Boolean, String) -> Unit,
    private val onVideoSize: (Int, Int) -> Unit
) {
    @android.webkit.JavascriptInterface
    fun onState(active: Boolean, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onState(active, message) }
    }

    @android.webkit.JavascriptInterface
    fun onVideoSize(width: Int, height: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onVideoSize(width, height) }
    }
}

private fun mapViewerTouch(
    point: androidx.compose.ui.geometry.Offset,
    viewWidth: Float,
    viewHeight: Float,
    sourceWidth: Int,
    sourceHeight: Int
): androidx.compose.ui.geometry.Offset? {
    if (viewWidth <= 0f || viewHeight <= 0f || sourceWidth <= 0 || sourceHeight <= 0) return null
    // Match object-fit: contain so letterbox margins never map to desktop coordinates.
    val scale = minOf(viewWidth / sourceWidth, viewHeight / sourceHeight)
    val renderedWidth = sourceWidth * scale
    val renderedHeight = sourceHeight * scale
    val left = (viewWidth - renderedWidth) / 2f
    val top = (viewHeight - renderedHeight) / 2f
    if (point.x < left || point.x > left + renderedWidth ||
        point.y < top || point.y > top + renderedHeight
    ) return null
    return androidx.compose.ui.geometry.Offset(
        ((point.x - left) / renderedWidth).coerceIn(0f, 1f),
        ((point.y - top) / renderedHeight).coerceIn(0f, 1f)
    )
}

@Composable
internal fun ScreenMirrorScreen(
    host: String,
    onMessage: (String, Boolean) -> Unit,
    fullscreen: Boolean,
    onFullscreenChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val scope = rememberCoroutineScope()

    var webReady by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf("") }
    var streamActive by remember { mutableStateOf(false) }
    var hasVideo by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Screen stream is off") }
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var resolution by remember { mutableStateOf("720p") }
    var fps by remember { mutableIntStateOf(30) }
    var lastPointerSentAt by remember { mutableStateOf(0L) }

    val webView = remember(context, host) { android.webkit.WebView(context) }
    val bridge = remember(webView, host) {
        ScreenMirrorJavascriptBridge(
            onState = { active, message ->
                streamActive = active
                statusMessage = message
                if (!active) {
                    hasVideo = false
                    videoWidth = 0
                    videoHeight = 0
                    if (message.contains("could not", true) ||
                        message.contains("failed", true) ||
                        message.contains("unavailable", true)
                    ) onMessage(message, true)
                }
            },
            onVideoSize = { width, height ->
                if (width > 0 && height > 0) {
                    videoWidth = width
                    videoHeight = height
                    hasVideo = true
                    streamActive = true
                    statusMessage = "Live desktop · " + width + " × " + height
                }
            }
        )
    }

    // This local document contains only the video element and WebRTC signalling code.
    // The complete screen-mirroring interface is rendered by native Jetpack Compose.
    val mirrorHtml = remember {
        """
        <!doctype html>
        <html><head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
          <style>
            html, body { width:100%; height:100%; margin:0; padding:0; overflow:hidden; background:#05070a; }
            video {
              display:block; width:100%; height:100%; margin:0; padding:0;
              background:#05070a; object-fit:contain; object-position:center center;
              pointer-events:none;
            }
          </style>
        </head><body>
          <video id="desktop" autoplay playsinline muted></video>
          <script>
          (() => {
            const video = document.getElementById('desktop');
            let peer = null, mediaStream = null, active = false, generation = 0;

            function reportState(value, message) {
              try { window.SciNETNative.onState(value, String(message || '')); } catch (_) {}
            }
            function reportSize() {
              if (video.videoWidth > 0 && video.videoHeight > 0) {
                try { window.SciNETNative.onVideoSize(video.videoWidth, video.videoHeight); } catch (_) {}
              }
            }
            video.addEventListener('loadedmetadata', reportSize);
            video.addEventListener('resize', reportSize);

            function waitForIce(connection) {
              return new Promise(resolve => {
                if (connection.iceGatheringState === 'complete') return resolve();
                let finished = false;
                const check = () => {
                  if (connection.iceGatheringState === 'complete') finish();
                };
                const finish = () => {
                  if (finished) return;
                  finished = true;
                  clearTimeout(timeout);
                  connection.removeEventListener('icegatheringstatechange', check);
                  resolve();
                };
                const timeout = setTimeout(finish, 5000);
                connection.addEventListener('icegatheringstatechange', check);
              });
            }

            async function start(selectedResolution, selectedFps) {
              if (active) return;
              active = true;
              const token = ++generation;
              reportState(true, 'Negotiating a local video session…');
              try {
                const connection = new RTCPeerConnection({ iceServers: [] });
                peer = connection;
                connection.addTransceiver('video', { direction: 'recvonly' });
                connection.ontrack = event => {
                  if (!active || token !== generation) return;
                  mediaStream = event.streams[0] || new MediaStream([event.track]);
                  video.srcObject = mediaStream;
                  video.play().catch(error => reportState(true, 'Video ready · ' + error.message));
                  reportState(true, 'Receiving desktop video…');
                };
                connection.onconnectionstatechange = () => {
                  if (!active || token !== generation) return;
                  if (connection.connectionState === 'connected') {
                    reportState(true, 'Live desktop stream connected');
                  } else if (connection.connectionState === 'failed' ||
                             connection.connectionState === 'disconnected') {
                    stop('Connection lost · press Start viewing to reconnect');
                  }
                };

                const offer = await connection.createOffer();
                await connection.setLocalDescription(offer);
                await waitForIce(connection);
                if (!active || token !== generation) { connection.close(); return; }

                const response = await fetch('/api/screen/offer', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({
                    sdp: connection.localDescription.sdp,
                    type: connection.localDescription.type,
                    resolution: selectedResolution,
                    fps: Number(selectedFps)
                  })
                });
                const answer = await response.json().catch(() => ({}));
                if (!response.ok) throw new Error(answer.error || 'Could not establish the desktop stream');
                if (!active || token !== generation) { connection.close(); return; }
                await connection.setRemoteDescription(answer);
                reportState(true, 'Waiting for the first desktop frame…');
              } catch (error) {
                if (token === generation) {
                  active = false;
                  if (peer) { try { peer.close(); } catch (_) {} peer = null; }
                  if (mediaStream) { mediaStream.getTracks().forEach(track => track.stop()); mediaStream = null; }
                  video.pause();
                  video.srcObject = null;
                  reportState(false, 'Could not start screen stream: ' + (error.message || 'Unknown error'));
                  fetch('/api/screen/stop', { method:'POST', keepalive:true }).catch(() => {});
                }
              }
            }

            async function stop(message) {
              generation += 1;
              active = false;
              if (peer) { try { peer.ontrack = null; peer.close(); } catch (_) {} peer = null; }
              if (mediaStream) { mediaStream.getTracks().forEach(track => track.stop()); mediaStream = null; }
              video.pause();
              video.srcObject = null;
              reportState(false, message || 'Screen stream stopped');
              fetch('/api/screen/stop', { method:'POST', keepalive:true }).catch(() => {});
            }

            async function configure(selectedResolution, selectedFps) {
              const restart = active;
              if (restart) await stop('Applying stream settings…');
              if (restart) await start(selectedResolution, selectedFps);
            }
            window.SciNETMirror = { start, stop, configure };
          })();
          </script>
        </body></html>
        """.trimIndent()
    }

    val originalOrientation = remember(activity) {
        activity?.requestedOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    val originalSystemUi = remember(activity) {
        activity?.window?.decorView?.systemUiVisibility ?: 0
    }

    androidx.compose.runtime.DisposableEffect(activity) {
        onDispose {
            activity?.let {
                it.requestedOrientation = originalOrientation
                it.window.decorView.systemUiVisibility = originalSystemUi
                it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
    androidx.compose.runtime.SideEffect {
        activity?.let {
            if (fullscreen) {
                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
            } else {
                it.requestedOrientation = originalOrientation
                it.window.decorView.systemUiVisibility = originalSystemUi
                it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = fullscreen) {
        onFullscreenChanged(false)
    }

    androidx.compose.runtime.DisposableEffect(webView, host, bridge, mirrorHtml) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.setSupportZoom(false)
        webView.settings.builtInZoomControls = false
        webView.settings.displayZoomControls = false
        webView.settings.allowContentAccess = false
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.addJavascriptInterface(bridge, "SciNETNative")
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                webReady = true
                pageError = ""
            }
            override fun onReceivedError(
                view: android.webkit.WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    pageError = error?.description?.toString() ?: "Could not initialise the video surface."
                    webReady = false
                }
            }
        }
        webView.loadDataWithBaseURL(
            SciNETApi.normalizeHost(host).trimEnd('/') + "/",
            mirrorHtml,
            "text/html",
            "UTF-8",
            null
        )
        onDispose {
            try {
                webView.evaluateJavascript(
                    "if (window.SciNETMirror) window.SciNETMirror.stop('Leaving screen view')",
                    null
                )
                webView.stopLoading()
                webView.removeJavascriptInterface("SciNETNative")
                webView.loadUrl("about:blank")
                webView.destroy()
            } catch (_: Exception) { }
        }
    }

    fun startStream() {
        if (!webReady || streamActive) return
        pageError = ""
        webView.evaluateJavascript(
            "if (window.SciNETMirror) window.SciNETMirror.start(" + JSONObject.quote(resolution) + ", " + fps + ")",
            null
        )
    }
    fun stopStream() {
        webView.evaluateJavascript("if (window.SciNETMirror) window.SciNETMirror.stop('Screen stream stopped')", null)
    }
    fun setResolution(value: String) {
        if (resolution == value) return
        resolution = value
        webView.evaluateJavascript(
            "if (window.SciNETMirror) window.SciNETMirror.configure(" + JSONObject.quote(value) + ", " + fps + ")",
            null
        )
    }
    fun setFrameRate(value: Int) {
        if (fps == value) return
        fps = value
        webView.evaluateJavascript(
            "if (window.SciNETMirror) window.SciNETMirror.configure(" + JSONObject.quote(resolution) + ", " + value + ")",
            null
        )
    }

    suspend fun sendPointerPosition(point: androidx.compose.ui.geometry.Offset) {
        SciNETApi.post(
            host, "/api/control",
            JSONObject().put("action", "mouse-position")
                .put("x", point.x.toDouble()).put("y", point.y.toDouble())
        )
    }
    fun sendMouseClick(button: String) {
        scope.launch {
            try {
                SciNETApi.post(host, "/api/control", JSONObject().put("action", "mouse-click").put("button", button))
            } catch (error: Exception) {
                onMessage(error.message ?: "Could not send mouse click.", true)
            }
        }
    }

    val sourceAspect = if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight else 16f / 9f
    val statusLabel = if (hasVideo) "LIVE" else if (streamActive) "CONNECTING" else "OFF"
    val statusColor = if (hasVideo) Good else if (streamActive) Color(0xFFE6B86A) else Muted

    Column(
        modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (fullscreen) 0.dp else 13.dp)
    ) {
        if (!fullscreen) {
            PageHeading("REMOTE DISPLAY", "Screen mirroring", "A live, low-latency view of your Windows desktop.")
            PanelCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionHeading("LIVE DESKTOP", "PC screen")
                    Row(
                        Modifier.background(if (hasVideo) Color(0xFF153323) else Raised, RoundedCornerShape(50))
                            .border(1.dp, if (hasVideo) Color(0xFF2D6846) else Edge, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(Modifier.size(6.dp).background(statusColor, RoundedCornerShape(50)))
                        Text(statusLabel, color = if (hasVideo) Good else Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
                Text("The full desktop is fitted into the viewer. The image is never stretched or cropped.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }

        Box(
            modifier = if (fullscreen) {
                Modifier.fillMaxWidth().weight(1f).background(Color.Black)
            } else {
                Modifier.fillMaxWidth().aspectRatio(sourceAspect).clip(RoundedCornerShape(11.dp))
                    .background(Color.Black).border(1.dp, Edge, RoundedCornerShape(11.dp))
            }
        ) {
            androidx.compose.ui.viewinterop.AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())

            if (!hasVideo) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp)
                        .background(Color(0xDD0C1117), RoundedCornerShape(12.dp))
                        .border(1.dp, Edge, RoundedCornerShape(12.dp))
                        .padding(horizontal = 22.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        if (pageError.isNotBlank()) "VIEWER UNAVAILABLE"
                        else if (streamActive) "CONNECTING TO YOUR PC"
                        else "DESKTOP IS NOT SHARED",
                        color = if (pageError.isNotBlank()) Bad else Ink,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                    Text(
                        when {
                            pageError.isNotBlank() -> pageError
                            streamActive -> statusMessage
                            !webReady -> "Preparing the video receiver…"
                            else -> "Start viewing to open a live stream from your PC."
                        },
                        color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                    )
                }
            }

            if (fullscreen && hasVideo) {
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(videoWidth, videoHeight) {
                            detectTapGestures { position ->
                                val mapped = mapViewerTouch(position, size.width.toFloat(), size.height.toFloat(), videoWidth, videoHeight)
                                if (mapped != null) scope.launch {
                                    try {
                                        sendPointerPosition(mapped)
                                        SciNETApi.post(host, "/api/control", JSONObject().put("action", "mouse-click").put("button", "left"))
                                    } catch (error: Exception) {
                                        onMessage(error.message ?: "Could not send desktop click.", true)
                                    }
                                }
                            }
                        }
                        .pointerInput(videoWidth, videoHeight) {
                            detectDragGestures(
                                onDragStart = { position ->
                                    val mapped = mapViewerTouch(position, size.width.toFloat(), size.height.toFloat(), videoWidth, videoHeight)
                                    if (mapped != null) {
                                        val now = android.os.SystemClock.uptimeMillis()
                                        if (now - lastPointerSentAt >= 25L) {
                                            lastPointerSentAt = now
                                            scope.launch { runCatching { sendPointerPosition(mapped) } }
                                        }
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val mapped = mapViewerTouch(change.position, size.width.toFloat(), size.height.toFloat(), videoWidth, videoHeight)
                                    if (mapped != null) {
                                        val now = android.os.SystemClock.uptimeMillis()
                                        if (now - lastPointerSentAt >= 25L) {
                                            lastPointerSentAt = now
                                            scope.launch { runCatching { sendPointerPosition(mapped) } }
                                        }
                                    }
                                }
                            )
                        }
                )
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(color = Color(0xCC101820), shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x553F596B))) {
                        Text("SCINET  /  LIVE DESKTOP", Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    OutlinedButton(
                        onClick = { onFullscreenChanged(false) },
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xCC101820), contentColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x665A6875)),
                        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("EXIT FULLSCREEN", fontSize = 9.sp, letterSpacing = .5.sp) }
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(14.dp)
                        .background(Color(0xDD101820), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0x553F596B), RoundedCornerShape(12.dp))
                        .padding(horizontal = 5.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TextButton(onClick = { sendMouseClick("left") }) { Text("LEFT CLICK", color = Color.White, fontSize = 9.sp) }
                    TextButton(onClick = { sendMouseClick("right") }) { Text("RIGHT CLICK", color = Color.White, fontSize = 9.sp) }
                    TextButton(onClick = { stopStream() }) { Text("STOP", color = Bad, fontSize = 9.sp) }
                }
            }
        }

        if (!fullscreen) {
            Text(statusMessage, color = if (hasVideo) Good else Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 2.dp))
            PanelCard {
                SectionHeading("STREAM SETTINGS", "Video quality")
                Text("Higher settings use more network bandwidth and PC processing power.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                Text("RESOLUTION", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("480p", "720p", "1080p").forEach { value ->
                        FilterChip(selected = resolution == value, onClick = { setResolution(value) }, label = { Text(value, fontSize = 11.sp) })
                    }
                }
                Text("FRAME RATE", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(15, 24, 30).forEach { value ->
                        FilterChip(selected = fps == value, onClick = { setFrameRate(value) }, label = { Text("$value FPS", fontSize = 11.sp) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (streamActive) {
                        OutlinedButton(
                            onClick = { stopStream() }, modifier = Modifier.fillMaxWidth().height(47.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Edge), shape = RoundedCornerShape(8.dp)
                        ) { Text("STOP VIEWING", fontSize = 10.sp, letterSpacing = .7.sp) }
                    } else {
                        PrimaryButton("START VIEWING", webReady) { startStream() }
                    }
                }
                OutlinedButton(
                    onClick = { onFullscreenChanged(true) }, enabled = hasVideo,
                    modifier = Modifier.weight(1f).height(47.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Edge), shape = RoundedCornerShape(8.dp)
                ) { Text("⛶  FULLSCREEN", fontSize = 10.sp, letterSpacing = .5.sp) }
            }
        }
    }
}
