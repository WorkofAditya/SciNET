package com.workofaditya.scinet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DashboardScreen(data: JSONObject, events: List<JSONObject>, host: String, onRefresh: () -> Unit) {
    val memory = data.optJSONObject("memory") ?: JSONObject()
    val disk = data.optJSONObject("disk") ?: JSONObject()
    val network = data.optJSONObject("network") ?: JSONObject()
    val devices = data.optJSONArray("devices").objects()
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
            SectionHeading("NETWORK", "Traffic totals")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("RECEIVED", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
                    Text(formatBytes(network.optLong("received")), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("SENT", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
                    Text(formatBytes(network.optLong("sent")), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("Updates automatically while the dashboard is open.", color = Muted, fontSize = 10.sp)
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
                    Text(device.optString("type").uppercase(), color = Muted, fontSize = 8.sp)
                }
                HorizontalDivider(color = Edge)
            }
            Text("HOST ADDRESS", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
            Text(host, fontSize = 11.sp)
        }
        PanelCard {
            SectionHeading("ACTIVITY", "Recent events")
            if (events.isEmpty()) EmptyText("No activity yet.")
            events.take(12).forEach { event ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(event.optString("kind", "info").uppercase(), color = Accent, fontSize = 8.sp, letterSpacing = 1.sp)
                        Text(event.optString("message"), fontSize = 11.sp, lineHeight = 16.sp)
                    }
                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((event.optDouble("time") * 1000).toLong())), color = Muted, fontSize = 9.sp)
                }
                HorizontalDivider(color = Edge)
            }
        }
    }
}

@Composable
internal fun ControlScreen(onAction: (String, Map<String, Any?>) -> Unit) {
    var scrollMode by remember { mutableStateOf(false) }
    var typedText by remember { mutableStateOf("") }
    val modifiers = remember { mutableStateListOf<String>() }
    var pendingPower by remember { mutableStateOf("") }
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
            SectionHeading("INPUT", "Touchpad")
            Box(
                Modifier.fillMaxWidth().height(205.dp).background(Color(0xFF0B0E12), RoundedCornerShape(8.dp))
                    .border(1.dp, Edge, RoundedCornerShape(8.dp))
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
                Box(Modifier.fillMaxHeight().width(1.dp).background(Edge))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Edge))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(if (scrollMode) "↕" else "＋", color = Color(0xFF9BA5B1), fontSize = 28.sp)
                    Text(if (scrollMode) "SCROLL MODE · SWIPE UP / DOWN" else "TOUCH & DRAG TO MOVE", color = Muted, fontSize = 8.sp, letterSpacing = .6.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scrollMode = !scrollMode }, modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (scrollMode) Accent else Edge),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (scrollMode) Accent else Ink),
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
            OutlinedTextField(value = typedText, onValueChange = { typedText = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Type text to send to the PC…") }, minLines = 2, colors = fieldColors())
            PrimaryButton("SEND TEXT TO PC", enabled = typedText.isNotEmpty()) {
                onAction("type", mapOf("text" to typedText.take(4000)))
                typedText = ""
            }
            Text("Select modifiers, then tap a key. They reset after sending.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
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
            val keys = listOf("esc", "tab", "enter", "backspace", "delete", "up", "left", "down", "right", "home", "end", "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10", "f11", "f12")
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val renderer = remember { org.webrtc.SurfaceViewRenderer(context) }
    val session = remember { NativeWebRtcSession(context, renderer) }
    var active by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var resolution by remember { mutableStateOf("720p") }
    var fps by remember { mutableStateOf(30) }
    var streamError by remember { mutableStateOf("") }

    androidx.compose.runtime.DisposableEffect(session, host) {
        onDispose {
            session.close()
            scope.launch { try { SciNETApi.post(host, "/api/screen/stop", org.json.JSONObject()) } catch (_: Exception) { } }
        }
    }

    fun startStream() {
        if (busy) return
        busy = true
        streamError = ""
        scope.launch {
            try {
                session.start(host, resolution, fps)
                active = true
                onMessage("Native desktop stream connected.", false)
            } catch (e: Exception) {
                active = false
                streamError = e.message ?: "Could not start the WebRTC stream."
                onMessage(streamError, true)
            } finally { busy = false }
        }
    }
    fun stopStream() {
        active = false
        busy = true
        scope.launch {
            try { session.stop(host) } finally { busy = false }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeading("LIVE DESKTOP", "Screen mirroring", "Real-time WebRTC video from the Windows host.")
        PanelCard {
            SectionHeading("STREAM OPTIONS", if (active) "LIVE" else "OFF")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { resolution = when (resolution) { "480p" -> "720p"; "720p" -> "1080p"; else -> "480p" }; if (active) { stopStream(); startStream() } }, modifier = Modifier.weight(1f), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) {
                    Text(resolution + " · RESOLUTION", fontSize = 9.sp)
                }
                OutlinedButton(onClick = { fps = when (fps) { 15 -> 24; 24 -> 30; else -> 15 }; if (active) { stopStream(); startStream() } }, modifier = Modifier.weight(1f), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) {
                    Text(fps.toString() + " FPS", fontSize = 9.sp)
                }
            }
            Box(
                Modifier.fillMaxWidth().height(230.dp).background(Color.Black, RoundedCornerShape(8.dp))
                    .border(1.dp, Edge, RoundedCornerShape(8.dp))
                    .pointerInput(active, host) {
                        androidx.compose.foundation.gestures.detectTapGestures { tap ->
                            if (active) scope.launch {
                                try { SciNETApi.post(host, "/api/control", org.json.JSONObject().put("action", "mouse-click").put("button", "left")) }
                                catch (e: Exception) { onMessage(e.message ?: "Click failed.", true) }
                            }
                        }
                    }
                    .pointerInput(active, host) {
                        androidx.compose.foundation.gestures.detectDragGestures { change, _ ->
                            change.consume()
                            if (active && size.width > 0 && size.height > 0) {
                                val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                                val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                                scope.launch {
                                    try { SciNETApi.post(host, "/api/control", org.json.JSONObject().put("action", "mouse-position").put("x", nx).put("y", ny)) }
                                    catch (_: Exception) { }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { view -> session.initialize(); view },
                    modifier = Modifier.fillMaxSize()
                )
                if (!active) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.background(Color(0xEE0C0F13), RoundedCornerShape(8.dp)).padding(18.dp)) {
                        Text("▱", color = Accent, fontSize = 26.sp)
                        Text(if (busy) "CONNECTING TO DESKTOP…" else "DESKTOP STREAM IS OFF", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("Video starts only when requested.", color = Muted, fontSize = 9.sp)
                    }
                }
            }
            Text("Tap the view to click. Drag to move the Windows pointer. Use a lower resolution or frame rate for less network load.", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
            if (streamError.isNotBlank()) Text(streamError, color = Bad, fontSize = 10.sp, lineHeight = 15.sp)
            PrimaryButton(if (busy) "PLEASE WAIT…" else if (active) "STOP VIEWING" else "START VIEWING", !busy) {
                if (active) stopStream() else startStream()
            }
        }
    }
}
