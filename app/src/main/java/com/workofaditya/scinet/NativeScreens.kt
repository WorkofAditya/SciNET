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
    val activity = context as? android.app.Activity
    var pageReady by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf("") }
    var fullscreenCustomView by remember { mutableStateOf<android.view.View?>(null) }
    var fullscreenCallback by remember {
        mutableStateOf<android.webkit.WebChromeClient.CustomViewCallback?>(null)
    }
    val originalOrientation = remember(activity) {
        activity?.requestedOrientation
            ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    var originalSystemUiVisibility by remember { mutableIntStateOf(0) }

    val webView = remember(context, host) { android.webkit.WebView(context) }
    val screenOnlyStyle = """
        (() => {
          const existingCard = document.querySelector('.screen-card');
          if (!existingCard) {
            return JSON.stringify({ error: 'The PC page did not include its screen mirroring panel.' });
          }

          // Detach the existing, fully wired screen panel from the desktop site's constrained
          // app-shell/main layout. Moving (not cloning) preserves all control.js event handlers.
          const nativeRoot = document.createElement('div');
          nativeRoot.id = 'scinet-native-screen-root';
          existingCard.parentNode.removeChild(existingCard);
          const oldShell = document.querySelector('.app-shell');
          if (oldShell) oldShell.style.display = 'none';
          document.body.appendChild(nativeRoot);
          nativeRoot.appendChild(existingCard);

          const css = document.createElement('style');
          css.id = 'scinet-native-screen-only';
          css.textContent = `
            *, *::before, *::after { box-sizing: border-box !important; }
            html, body {
              display: block !important;
              width: 100% !important;
              min-width: 0 !important;
              height: auto !important;
              min-height: 100% !important;
              margin: 0 !important;
              padding: 0 !important;
              overflow-x: hidden !important;
              background: #0a0c0f !important;
            }
            #scinet-native-screen-root {
              display: block !important;
              position: relative !important;
              width: 100% !important;
              max-width: none !important;
              min-width: 0 !important;
              margin: 0 !important;
              padding: 14px 14px 18px !important;
              overflow: visible !important;
              box-sizing: border-box !important;
            }
            #scinet-native-screen-root .screen-card {
              display: flex !important;
              flex-direction: column !important;
              align-items: stretch !important;
              width: 100% !important;
              max-width: none !important;
              min-width: 0 !important;
              margin: 0 !important;
              padding: 16px !important;
              gap: 0 !important;
              border: 1px solid #2b3540 !important;
              border-radius: 16px !important;
              background: linear-gradient(155deg,#151c24 0%,#10161d 55%,#0d1218 100%) !important;
              box-shadow: 0 14px 36px rgba(0,0,0,.30), inset 0 1px rgba(255,255,255,.035) !important;
              box-sizing: border-box !important;
            }
            #scinet-native-screen-root .screen-card > .section-head {
              display: flex !important;
              align-items: center !important;
              justify-content: space-between !important;
              width: 100% !important;
              min-width: 0 !important;
              margin: 0 0 15px !important;
              padding: 0 0 13px !important;
              border-bottom: 1px solid #2a3540 !important;
            }
            #scinet-native-screen-root .screen-card > .section-head .label {
              color: #94a3b3 !important;
              font-size: 9px !important;
              font-weight: 700 !important;
              letter-spacing: .18em !important;
            }
            #scinet-native-screen-root .screen-card > .section-head h2 {
              margin: 5px 0 0 !important;
              color: #f3f6f9 !important;
              font-size: 22px !important;
              font-weight: 700 !important;
              line-height: 1.2 !important;
              letter-spacing: -.04em !important;
            }
            #scinet-native-screen-root #screenState {
              flex: 0 0 auto !important;
              padding: 6px 10px !important;
              border: 1px solid #394653 !important;
              border-radius: 999px !important;
              color: #aab7c4 !important;
              background: #1a232d !important;
              font-size: 9px !important;
              font-weight: 700 !important;
              letter-spacing: .12em !important;
            }
            #scinet-native-screen-root #screenState.live {
              border-color: #286e4b !important;
              color: #9aebba !important;
              background: #112d20 !important;
            }
            #scinet-native-screen-root .screen-stream-options {
              display: grid !important;
              grid-template-columns: repeat(2,minmax(0,1fr)) !important;
              align-items: end !important;
              gap: 10px !important;
              width: 100% !important;
              min-width: 0 !important;
              margin: 0 0 14px !important;
            }
            #scinet-native-screen-root .screen-stream-options label {
              display: flex !important;
              flex-direction: column !important;
              min-width: 0 !important;
              gap: 6px !important;
              color: #99a7b5 !important;
              font-size: 9px !important;
              font-weight: 700 !important;
              letter-spacing: .10em !important;
            }
            #scinet-native-screen-root .screen-stream-options select {
              display: block !important;
              width: 100% !important;
              min-width: 0 !important;
              min-height: 44px !important;
              margin: 0 !important;
              padding: 0 10px !important;
              border: 1px solid #354452 !important;
              border-radius: 9px !important;
              outline: none !important;
              background: #0c131b !important;
              color: #e7edf3 !important;
              font-family: inherit !important;
              font-size: 12px !important;
            }
            #scinet-native-screen-root .screen-viewer {
              position: relative !important;
              display: grid !important;
              place-items: center !important;
              width: 100% !important;
              max-width: none !important;
              min-width: 0 !important;
              min-height: 0 !important;
              height: auto !important;
              aspect-ratio: 16 / 9 !important;
              margin: 0 !important;
              overflow: hidden !important;
              border: 1px solid #364553 !important;
              border-radius: 12px !important;
              background: #05080c !important;
              box-shadow: 0 10px 26px rgba(0,0,0,.28) !important;
            }
            #scinet-native-screen-root .screen-viewer video {
              display: block !important;
              width: 100% !important;
              height: 100% !important;
              max-width: 100% !important;
              max-height: none !important;
              object-fit: contain !important;
              background: #05080c !important;
            }
            #scinet-native-screen-root .screen-placeholder {
              display: flex !important;
              align-items: center !important;
              justify-content: center !important;
              flex-direction: column !important;
              gap: 9px !important;
              width: 100% !important;
              min-width: 0 !important;
              min-height: 100% !important;
              padding: 18px !important;
              text-align: center !important;
            }
            #scinet-native-screen-root .screen-placeholder[hidden] { display: none !important; }
            #scinet-native-screen-root .screen-placeholder-icon {
              display: grid !important;
              place-items: center !important;
              width: 48px !important;
              height: 48px !important;
              border: 1px solid #34414e !important;
              border-radius: 13px !important;
              background: #151e27 !important;
              color: #c3cfda !important;
              font-size: 23px !important;
            }
            #scinet-native-screen-root .screen-placeholder strong {
              color: #d8e0e8 !important;
              font-size: 12px !important;
              font-weight: 600 !important;
            }
            #scinet-native-screen-root .screen-placeholder small {
              max-width: 270px !important;
              color: #82909e !important;
              font-size: 10px !important;
              line-height: 1.5 !important;
            }
            #scinet-native-screen-root .screen-live-indicator {
              position: absolute !important;
              top: 10px !important;
              left: 10px !important;
              z-index: 4 !important;
              padding: 6px 9px !important;
              border: 1px solid #256a48 !important;
              border-radius: 7px !important;
              background: rgba(12,35,24,.94) !important;
              color: #a2efbe !important;
              font-size: 9px !important;
              font-weight: 800 !important;
              letter-spacing: .12em !important;
            }
            #scinet-native-screen-root .screen-live-indicator[hidden] { display: none !important; }
            #scinet-native-screen-root .screen-actions {
              display: grid !important;
              grid-template-columns: minmax(0,1fr) minmax(0,1fr) !important;
              align-items: stretch !important;
              gap: 9px !important;
              width: 100% !important;
              margin: 14px 0 0 !important;
            }
            #scinet-native-screen-root .screen-actions .button {
              display: inline-flex !important;
              align-items: center !important;
              justify-content: center !important;
              width: 100% !important;
              min-width: 0 !important;
              min-height: 46px !important;
              margin: 0 !important;
              padding: 0 10px !important;
              border: 1px solid #394857 !important;
              border-radius: 10px !important;
              background: #19232e !important;
              color: #e7edf3 !important;
              font-family: inherit !important;
              font-size: 11px !important;
              font-weight: 700 !important;
              letter-spacing: .01em !important;
            }
            #scinet-native-screen-root #screenStart {
              grid-column: 1 / -1 !important;
              border-color: #347c58 !important;
              background: linear-gradient(135deg,#236344,#17452f) !important;
              color: #d2f6e0 !important;
              box-shadow: inset 0 1px rgba(255,255,255,.08), 0 5px 14px rgba(12,52,31,.28) !important;
            }
            #scinet-native-screen-root #screenStop {
              grid-column: 1 !important;
              border-color: #713d43 !important;
              background: #321d21 !important;
              color: #ffd0d1 !important;
            }
            #scinet-native-screen-root #screenFullscreen {
              grid-column: 2 !important;
              grid-row: 2 !important;
            }
            #scinet-native-screen-root #screenFullscreen:disabled {
              opacity: .45 !important;
            }
            #scinet-native-screen-root .screen-fps {
              grid-column: 1 / -1 !important;
              width: 100% !important;
              margin: 2px 0 0 !important;
              color: #91a0ae !important;
              font-size: 10px !important;
              line-height: 1.5 !important;
              text-align: left !important;
            }
            #scinet-native-screen-root .control-note { display: none !important; }
            #scinet-native-screen-root .screen-viewer:fullscreen {
              position: fixed !important;
              inset: 0 !important;
              width: 100vw !important;
              height: 100vh !important;
              height: 100dvh !important;
              max-width: none !important;
              max-height: none !important;
              aspect-ratio: auto !important;
              margin: 0 !important;
              border: 0 !important;
              border-radius: 0 !important;
              background: #000 !important;
              z-index: 99999 !important;
            }
            #scinet-native-screen-root .screen-viewer:fullscreen video {
              width: 100% !important;
              height: 100% !important;
              object-fit: contain !important;
            }
            #screenTouchControls { z-index: 100000 !important; }
          `;
          document.head.appendChild(css);

          const rootRect = nativeRoot.getBoundingClientRect();
          const cardRect = existingCard.getBoundingClientRect();
          window.scrollTo(0, 0);
          return JSON.stringify({
            page: !!document.querySelector('#scinet-native-screen-root .screen-card'),
            rootWidth: Math.round(rootRect.width),
            cardWidth: Math.round(cardRect.width),
            viewportWidth: window.innerWidth,
            start: !!document.getElementById('screenStart'),
            fullscreen: !!document.getElementById('screenFullscreen'),
            video: !!document.getElementById('screenFrame'),
            rtc: !!window.RTCPeerConnection
          });
        })();
    """.trimIndent()

    fun leaveFullscreen() {
        val view = fullscreenCustomView
        if (view != null) {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            fullscreenCustomView = null
        }
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        activity?.let { hostActivity ->
            hostActivity.requestedOrientation = originalOrientation
            hostActivity.window.decorView.systemUiVisibility = originalSystemUiVisibility
        }
    }

    androidx.activity.compose.BackHandler(enabled = fullscreenCustomView != null) {
        webView.evaluateJavascript("if (typeof leaveScreenFullscreen === 'function') leaveScreenFullscreen()", null)
    }

    androidx.compose.runtime.DisposableEffect(webView, host) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowContentAccess = false
        webView.setBackgroundColor(android.graphics.Color.rgb(10, 12, 15))
        webView.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowCustomView(
                view: android.view.View?,
                callback: android.webkit.WebChromeClient.CustomViewCallback?
            ) {
                if (view == null || callback == null || activity == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                if (fullscreenCustomView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenCustomView = view
                fullscreenCallback = callback
                val decor = activity.window.decorView as android.view.ViewGroup
                originalSystemUiVisibility = decor.systemUiVisibility
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                decor.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                decor.addView(
                    view,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            override fun onHideCustomView() {
                leaveFullscreen()
            }

            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                val line = "Web control JS ${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                android.util.Log.d("SciNETMirrorWeb", line)
                return true
            }
        }
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                webView.evaluateJavascript(screenOnlyStyle) { result ->
                    android.util.Log.i("SciNETMirrorWeb", "Embedded PC screen page: $result")
                    pageReady = true
                    pageError = ""
                }
            }

            override fun onReceivedError(
                view: android.webkit.WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    val detail = error?.description?.toString() ?: "Could not load the PC screen control page."
                    pageError = detail
                    android.util.Log.e("SciNETMirrorWeb", detail)
                }
            }

            override fun onReceivedHttpError(
                view: android.webkit.WebView?,
                request: android.webkit.WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    val detail = "PC screen page returned HTTP ${errorResponse?.statusCode ?: "error"}."
                    pageError = detail
                    android.util.Log.e("SciNETMirrorWeb", detail)
                }
            }
        }

        webView.loadUrl(host.trimEnd('/') + "/control")

        onDispose {
            leaveFullscreen()
            try {
                webView.evaluateJavascript(
                    "if (typeof stopScreenStream === 'function') stopScreenStream('Leaving screen view')",
                    null
                )
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            } catch (_: Exception) { }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(maxWidth * (9f / 16f) + 245.dp)
                    .background(Color(0xFF0A0C0F))
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { _ -> webView },
                    modifier = Modifier.fillMaxSize()
                )
                if (!pageReady && pageError.isBlank()) {
                    Text(
                        "LOADING SCREEN VIEWER…",
                        color = Muted,
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                if (pageError.isNotBlank()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color(0xEE0C0F13), RoundedCornerShape(8.dp))
                            .padding(18.dp)
                    ) {
                        Text("PC SCREEN VIEWER UNAVAILABLE", color = Bad, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(pageError, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                        OutlinedButton(onClick = {
                            pageReady = false
                            pageError = ""
                            webView.reload()
                        }) { Text("RELOAD VIEWER") }
                    }
                }
            }
        }
    }
}
