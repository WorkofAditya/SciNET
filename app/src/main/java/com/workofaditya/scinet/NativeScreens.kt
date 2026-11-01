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

@Composable
internal fun ScreenMirrorScreen(host: String, onMessage: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    var active by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var webReady by remember { mutableStateOf(false) }
    var resolution by remember { mutableStateOf("720p") }
    var fps by remember { mutableIntStateOf(30) }
    var mirrorStatus by remember { mutableStateOf("Ready · video starts only when requested") }
    var firstFrameReceived by remember { mutableStateOf(false) }

    val webView = remember(host) { android.webkit.WebView(context) }
    val mirrorHtml = """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
          <style>
            * { box-sizing: border-box; }
            html, body { margin: 0; width: 100%; height: 100%; overflow: hidden; background: #000; color: #e7e9ed; font-family: sans-serif; }
            #viewer { position: relative; width: 100%; height: 100%; overflow: hidden; background: #000; touch-action: none; }
            #desktop { position: absolute; left: -4px; top: -4px; width: 2px; height: 2px; opacity: 0.01; pointer-events: none; }
            #desktopCanvas { display: none; width: 100%; height: 100%; object-fit: contain; background: #000; }
            #placeholder { position: absolute; inset: 0; display: flex; flex-direction: column; justify-content: center; align-items: center; gap: 9px; padding: 18px; text-align: center; background: #0c0f13; }
            #placeholder strong { font-size: 12px; letter-spacing: 1px; }
            #placeholder small { color: #858d98; font-size: 11px; line-height: 1.5; }
          </style>
        </head>
        <body>
          <div id="viewer">
            <video id="desktop" autoplay playsinline muted></video>
            <canvas id="desktopCanvas"></canvas>
            <div id="placeholder"><strong>DESKTOP STREAM IS OFF</strong><small>Video starts only when requested.</small></div>
          </div>
          <script>
            let mirrorPeer = null;
            let mirrorActive = false;
            let connectTimer = null;
            let statsTimer = null;
            let framePollTimer = null;
            let firstFrameTimer = null;
            let drawLoopId = null;
            let videoFrameSeen = false;
            let lastDrawnTime = -1;
            let canvasFrames = 0;
            const video = document.getElementById('desktop');
            const canvas = document.getElementById('desktopCanvas');
            const canvasContext = canvas.getContext('2d', { alpha: false, desynchronized: true });
            const placeholder = document.getElementById('placeholder');
            const viewer = document.getElementById('viewer');

            function nativeStatus(state, message) {
              try {
                if (window.SciNETBridge && window.SciNETBridge.onStatus) {
                  window.SciNETBridge.onStatus(String(state || ''), String(message || ''));
                }
              } catch (_) {}
            }
            function startCanvasRendering(pc) {
              if (drawLoopId !== null) return;
              const drawNextFrame = () => {
                drawLoopId = null;
                if (!mirrorActive || mirrorPeer !== pc) return;
                if (video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA &&
                    video.videoWidth > 0 && video.videoHeight > 0 &&
                    video.currentTime !== lastDrawnTime) {
                  try {
                    if (canvas.width !== video.videoWidth || canvas.height !== video.videoHeight) {
                      canvas.width = video.videoWidth;
                      canvas.height = video.videoHeight;
                    }
                    canvasContext.drawImage(video, 0, 0, canvas.width, canvas.height);
                    lastDrawnTime = video.currentTime;
                    canvasFrames++;
                    canvas.style.display = 'block';
                    if (!videoFrameSeen) {
                      videoFrameSeen = true;
                      placeholder.style.display = 'none';
                      if (firstFrameTimer) { clearTimeout(firstFrameTimer); firstFrameTimer = null; }
                      // Sample a tiny copy of the actual decoded image. This distinguishes a
                      // compositor problem from a PC capture source that is itself black.
                      const probe = document.createElement('canvas');
                      probe.width = 16;
                      probe.height = 9;
                      const probeContext = probe.getContext('2d', { willReadFrequently: true });
                      probeContext.drawImage(video, 0, 0, probe.width, probe.height);
                      const pixels = probeContext.getImageData(0, 0, probe.width, probe.height).data;
                      let visiblePixels = 0;
                      for (let i = 0; i < pixels.length; i += 4) {
                        if (pixels[i] + pixels[i + 1] + pixels[i + 2] > 36) visiblePixels++;
                      }
                      nativeStatus(
                        'video-frame',
                        'Canvas frame ' + canvas.width + '×' + canvas.height +
                          ' · image sample ' + visiblePixels + '/144 non-black pixels.'
                      );
                    } else if (canvasFrames % 90 === 0) {
                      nativeStatus('canvas-frame', 'Canvas rendered ' + canvasFrames + ' live frames.');
                    }
                  } catch (error) {
                    nativeStatus('error', 'Could not composite the decoded video frame: ' +
                      ((error && error.message) || 'canvas draw failed'));
                    return;
                  }
                }
                drawLoopId = requestAnimationFrame(drawNextFrame);
              };
              drawLoopId = requestAnimationFrame(drawNextFrame);
            }
            async function postControl(payload) {
              try {
                await fetch('/api/control', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify(payload)
                });
              } catch (_) {}
            }
            async function stopMirror(notify) {
              mirrorActive = false;
              if (connectTimer) { clearTimeout(connectTimer); connectTimer = null; }
              if (statsTimer) { clearInterval(statsTimer); statsTimer = null; }
              if (framePollTimer) { clearInterval(framePollTimer); framePollTimer = null; }
              if (firstFrameTimer) { clearTimeout(firstFrameTimer); firstFrameTimer = null; }
              if (drawLoopId !== null) { cancelAnimationFrame(drawLoopId); drawLoopId = null; }
              videoFrameSeen = false;
              lastDrawnTime = -1;
              canvasFrames = 0;
              canvas.style.display = 'none';
              canvas.width = 1;
              canvas.height = 1;
              const oldPeer = mirrorPeer;
              mirrorPeer = null;
              if (oldPeer) {
                try { oldPeer.ontrack = null; oldPeer.onconnectionstatechange = null; oldPeer.close(); } catch (_) {}
              }
              video.pause();
              video.srcObject = null;
              video.style.display = 'block';
              canvas.style.display = 'none';
              placeholder.style.display = 'flex';
              placeholder.querySelector('strong').textContent = 'DESKTOP STREAM IS OFF';
              placeholder.querySelector('small').textContent = 'Video starts only when requested.';
              try { await fetch('/api/screen/stop', { method: 'POST', keepalive: true }); } catch (_) {}
              if (notify) nativeStatus('stopped', 'Screen stream stopped.');
            }
            async function startMirror(selectedResolution, selectedFps) {
              if (mirrorActive) return;
              mirrorActive = true;
              nativeStatus('connecting', 'Negotiating a live WebRTC stream…');
              placeholder.style.display = 'flex';
              placeholder.querySelector('strong').textContent = 'CONNECTING TO DESKTOP…';
              placeholder.querySelector('small').textContent = 'Negotiating real-time video transport.';
              video.style.display = 'block';
              try {
                if (!window.RTCPeerConnection) throw new Error('This Android WebView does not support WebRTC.');
                const pc = new RTCPeerConnection({ iceServers: [] });
                mirrorPeer = pc;
                pc.addTransceiver('video', { direction: 'recvonly' });
                pc.ontrack = event => {
                  if (!mirrorActive || mirrorPeer !== pc) return;
                  const stream = (event.streams && event.streams[0]) || new MediaStream([event.track]);
                  const track = event.track;
                  nativeStatus('track', 'PC sent a video track. Preparing the desktop canvas…');
                  track.onunmute = () => nativeStatus('track', 'Video track unmuted; waiting for decoded frames…');
                  track.onmute = () => nativeStatus('stats', 'The PC video track is currently muted.');
                  track.onended = () => nativeStatus('error', 'The PC ended the desktop video track.');
                  videoFrameSeen = false;
                  lastDrawnTime = -1;
                  canvasFrames = 0;
                  video.srcObject = stream;
                  video.muted = true;
                  video.onloadedmetadata = () => {
                    canvas.width = Math.max(2, video.videoWidth);
                    canvas.height = Math.max(2, video.videoHeight);
                    nativeStatus('track', 'Decoded video size: ' + video.videoWidth + '×' + video.videoHeight + '. Compositing frames…');
                    startCanvasRendering(pc);
                  };
                  video.onloadeddata = () => startCanvasRendering(pc);
                  video.onplaying = () => startCanvasRendering(pc);
                  video.onerror = () => nativeStatus('error', 'Android WebView could not decode the incoming desktop video.');
                  const playback = video.play();
                  if (playback && playback.catch) {
                    playback.catch(error => nativeStatus('error', 'Video playback failed: ' + ((error && error.message) || 'unknown playback error')));
                  }

                  firstFrameTimer = setTimeout(async () => {
                    if (mirrorActive && mirrorPeer === pc && !videoFrameSeen) {
                      const detail = 'No visible canvas frame after 12 seconds (video readyState=' +
                        video.readyState + ', size=' + video.videoWidth + '×' + video.videoHeight +
                        ', currentTime=' + video.currentTime.toFixed(2) + ').';
                      await stopMirror(false);
                      nativeStatus('error', detail + ' The WebRTC track may be decoding, but Android did not composite it.');
                    }
                  }, 12000);
                  startCanvasRendering(pc);
                };
                pc.oniceconnectionstatechange = () => {
                  nativeStatus('ice', 'ICE state: ' + pc.iceConnectionState);
                };
                pc.onconnectionstatechange = () => {
                  if (!mirrorActive || mirrorPeer !== pc) return;
                  if (pc.connectionState === 'connected') {
                    if (connectTimer) { clearTimeout(connectTimer); connectTimer = null; }
                    nativeStatus('connected', 'WebRTC connected. Waiting for the first decoded video frame…');
                    if (statsTimer) clearInterval(statsTimer);
                    const reportStats = async () => {
                      if (!mirrorActive || mirrorPeer !== pc) return;
                      try {
                        const report = await pc.getStats();
                        let inbound = null;
                        report.forEach(stat => {
                          if (stat.type === 'inbound-rtp' && (stat.kind === 'video' || stat.mediaType === 'video')) inbound = stat;
                        });
                        if (inbound) {
                          nativeStatus('stats', 'Video RX ' + (inbound.bytesReceived || 0) +
                            ' bytes · ' + (inbound.packetsReceived || 0) + ' packets · ' +
                            (inbound.framesReceived || 0) + ' received / ' + (inbound.framesDecoded || 0) + ' decoded frames.');
                        } else {
                          nativeStatus('stats', 'WebRTC is connected; waiting for incoming video statistics…');
                        }
                      } catch (error) {
                        nativeStatus('stats', 'Could not read video statistics: ' + ((error && error.message) || 'unknown error'));
                      }
                    };
                    reportStats();
                    statsTimer = setInterval(reportStats, 2000);
                  } else if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
                    if (connectTimer) { clearTimeout(connectTimer); connectTimer = null; }
                    nativeStatus('error', 'WebRTC connection was lost. Check that the phone and PC are on the same reachable network.');
                    stopMirror(false);
                  }
                };

                const offer = await pc.createOffer();
                await pc.setLocalDescription(offer);
                await new Promise(resolve => {
                  if (pc.iceGatheringState === 'complete') return resolve();
                  const timeout = setTimeout(resolve, 5000);
                  pc.addEventListener('icegatheringstatechange', () => {
                    if (pc.iceGatheringState === 'complete') { clearTimeout(timeout); resolve(); }
                  });
                });

                const response = await fetch('/api/screen/offer', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({
                    sdp: pc.localDescription.sdp,
                    type: pc.localDescription.type,
                    resolution: selectedResolution,
                    fps: Number(selectedFps)
                  })
                });
                const answer = await response.json().catch(() => ({}));
                if (!response.ok) throw new Error(answer.error || 'The PC rejected the WebRTC offer.');
                if (!mirrorActive || mirrorPeer !== pc) { pc.close(); return; }
                await pc.setRemoteDescription(answer);

                connectTimer = setTimeout(() => {
                  if (mirrorActive && mirrorPeer === pc && pc.connectionState !== 'connected') {
                    nativeStatus('error', 'The PC accepted the offer, but WebRTC did not connect. Check the PC firewall and local network.');
                    stopMirror(false);
                  }
                }, 25000);
              } catch (error) {
                await stopMirror(false);
                nativeStatus('error', (error && error.message) || 'Could not start screen mirroring.');
              }
            }
            async function restartMirror(selectedResolution, selectedFps) {
              await stopMirror(false);
              await startMirror(selectedResolution, selectedFps);
            }
            window.startMirror = startMirror;
            window.stopMirror = stopMirror;
            window.restartMirror = restartMirror;

            let pointer = null;
            let lastPositionSend = 0;
            function screenPoint(event) {
              const rect = canvas.getBoundingClientRect();
              const vw = video.videoWidth || rect.width;
              const vh = video.videoHeight || rect.height;
              if (!vw || !vh || !rect.width || !rect.height) return null;
              const scale = Math.min(rect.width / vw, rect.height / vh);
              const renderedW = vw * scale, renderedH = vh * scale;
              const left = rect.left + (rect.width - renderedW) / 2;
              const top = rect.top + (rect.height - renderedH) / 2;
              if (event.clientX < left || event.clientX > left + renderedW ||
                  event.clientY < top || event.clientY > top + renderedH) return null;
              return { x: (event.clientX - left) / renderedW, y: (event.clientY - top) / renderedH };
            }
            viewer.addEventListener('pointerdown', event => {
              if (!mirrorActive || canvas.style.display === 'none') return;
              pointer = { id: event.pointerId, x: event.clientX, y: event.clientY, moved: false };
              try { viewer.setPointerCapture(event.pointerId); } catch (_) {}
            });
            viewer.addEventListener('pointermove', event => {
              if (!pointer || pointer.id !== event.pointerId || !mirrorActive) return;
              if (Math.hypot(event.clientX - pointer.x, event.clientY - pointer.y) > 6) pointer.moved = true;
              if (!pointer.moved) return;
              const point = screenPoint(event);
              const now = Date.now();
              if (point && now - lastPositionSend > 35) {
                lastPositionSend = now;
                postControl({ action: 'mouse-position', x: point.x, y: point.y });
              }
            });
            viewer.addEventListener('pointerup', event => {
              if (!pointer || pointer.id !== event.pointerId) return;
              const moved = pointer.moved;
              pointer = null;
              if (mirrorActive && !moved) postControl({ action: 'mouse-click', button: 'left' });
            });
            viewer.addEventListener('pointercancel', () => { pointer = null; });
          </script>
        </body>
        </html>
    """.trimIndent()

    val bridge = remember(webView) {
        ScreenMirrorJavascriptBridge { state, message ->
            android.util.Log.d("SciNETMirror", "$state: $message")
            mirrorStatus = message
            when (state) {
                "connected" -> {
                    active = true
                    busy = false
                }
                "video-frame" -> {
                    active = true
                    busy = false
                    if (!firstFrameReceived) onMessage(message, false)
                    firstFrameReceived = true
                }
                "canvas-frame", "track", "ice", "stats", "connecting" -> Unit
                "error" -> {
                    active = false
                    busy = false
                    onMessage(message, true)
                }
                "stopped" -> {
                    active = false
                    busy = false
                    mirrorStatus = "Screen stream stopped."
                }
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(webView, host) {
        webView.addJavascriptInterface(bridge, "SciNETBridge")
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowContentAccess = false
        // Keep the default hardware-accelerated WebView compositor. Forcing a separate
        // hardware View layer can interfere with independently composed media surfaces.
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                val line = "JS ${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                android.util.Log.d("SciNETMirror", line)
                return true
            }
        }
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                webReady = true
                mirrorStatus = "Ready · WebRTC support: checking…"
                webView.evaluateJavascript(
                    "JSON.stringify({rtc:!!window.RTCPeerConnection,media:!!window.MediaStream,secure:window.isSecureContext})"
                ) { result ->
                    android.util.Log.d("SciNETMirror", "WebView capabilities: $result")
                    android.util.Log.d(
                        "SciNETMirror",
                        "View compositor: hardwareAccelerated=${webView.isHardwareAccelerated}, layerType=${webView.layerType}"
                    )
                }
            }
            override fun onReceivedError(
                view: android.webkit.WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    val detail = error?.description?.toString() ?: "WebView page load failed"
                    mirrorStatus = detail
                    android.util.Log.e("SciNETMirror", detail)
                }
            }
        }
        val baseUrl = host.trimEnd('/') + "/"
        webView.loadDataWithBaseURL(baseUrl, mirrorHtml, "text/html", "UTF-8", null)

        onDispose {
            try {
                webView.evaluateJavascript("window.stopMirror && window.stopMirror(false)", null)
                webView.removeJavascriptInterface("SciNETBridge")
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            } catch (_: Exception) { }
        }
    }

    fun startStream() {
        if (busy || !webReady) return
        busy = true
        firstFrameReceived = false
        mirrorStatus = "Starting live WebRTC stream…"
        webView.evaluateJavascript("window.startMirror('$resolution', $fps)", null)
    }
    fun stopStream() {
        if (busy || !webReady) return
        busy = true
        firstFrameReceived = false
        webView.evaluateJavascript("window.stopMirror(true)", null)
    }
    fun restartStream() {
        if (busy || !webReady) return
        busy = true
        active = false
        firstFrameReceived = false
        mirrorStatus = "Restarting live WebRTC stream…"
        webView.evaluateJavascript("window.restartMirror('$resolution', $fps)", null)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeading("LIVE DESKTOP", "Screen mirroring", "Real-time WebRTC video from the Windows host.")
        PanelCard {
            SectionHeading("STREAM OPTIONS", if (active) "LIVE" else "OFF")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        resolution = when (resolution) { "480p" -> "720p"; "720p" -> "1080p"; else -> "480p" }
                        if (active) restartStream()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy && webReady,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Edge)
                ) { Text("$resolution · RESOLUTION", fontSize = 9.sp) }
                OutlinedButton(
                    onClick = {
                        fps = when (fps) { 15 -> 24; 24 -> 30; else -> 15 }
                        if (active) restartStream()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy && webReady,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Edge)
                ) { Text("$fps FPS", fontSize = 9.sp) }
            }
            Box(
                Modifier.fillMaxWidth().height(230.dp)
                    .background(Color.Black, RoundedCornerShape(8.dp))
                    .border(1.dp, Edge, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize()
                )
                if (!webReady) {
                    Text("PREPARING LIVE VIDEO VIEW…", color = Muted, fontSize = 10.sp)
                }
            }
            Text(
                mirrorStatus,
                color = if (mirrorStatus.contains("failed", true) || mirrorStatus.contains("could not", true) || mirrorStatus.contains("lost", true)) Bad else Muted,
                fontSize = 10.sp,
                lineHeight = 16.sp
            )
            Text(
                "Tap the desktop to click. Drag to move the Windows pointer. The stream is live WebRTC video, not screenshots.",
                color = Muted, fontSize = 10.sp, lineHeight = 16.sp
            )
            PrimaryButton(
                if (busy) "PLEASE WAIT…" else if (active) "STOP VIEWING" else "START VIEWING",
                enabled = webReady && !busy
            ) {
                if (active) stopStream() else startStream()
            }
        }
    }
}

private class ScreenMirrorJavascriptBridge(
    private val callback: (String, String) -> Unit
) {
    @android.webkit.JavascriptInterface
    fun onStatus(state: String, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            callback(state, message)
        }
    }
}
