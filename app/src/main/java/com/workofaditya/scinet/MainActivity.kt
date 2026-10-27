package com.workofaditya.scinet

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

internal val Bg = Color(0xFF0A0C0F)
internal val Panel = Color(0xFF101419)
internal val Raised = Color(0xFF15191E)
internal val Edge = Color(0xFF292F37)
internal val Ink = Color(0xFFE7E9ED)
internal val Muted = Color(0xFF858D98)
internal val Accent = Color(0xFFE1E5EA)
internal val Good = Color(0xFF79B98B)
internal val Bad = Color(0xFFE78F87)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel, primary = Accent, onPrimary = Bg, onBackground = Ink, onSurface = Ink, secondary = Muted, error = Bad)) { SciNETApp() } }
    }
}

@Composable
private fun SciNETApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("scinet-native", Context.MODE_PRIVATE) }
    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf("HOME") }
    var system by remember { mutableStateOf(JSONObject()) }
    var events by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var notice by remember { mutableStateOf("") }
    var noticeBad by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun notify(text: String, bad: Boolean = false) { notice = text; noticeBad = bad }
    fun control(action: String, extra: Map<String, Any?> = emptyMap()) {
        scope.launch {
            try {
                val body = JSONObject().put("action", action)
                extra.forEach { (key, value) -> body.put(key, value) }
                SciNETApi.post(host, "/api/control", body)
                notify("Sent: " + action.replace('-', ' '))
            } catch (e: Exception) { notify(e.message ?: "Could not reach the PC.", true) }
        }
    }
    fun connect() {
        if (host.isBlank()) { notify("Enter your PC's SciNET address first.", true); return }
        busy = true
        scope.launch {
            try {
                val normalized = SciNETApi.normalizeHost(host)
                system = SciNETApi.get(normalized, "/api/system")
                host = normalized
                prefs.edit().putString("host", normalized).apply()
                connected = true
                page = "HOME"
                notify("Connected to " + system.optString("hostname", "SciNET host"))
            } catch (e: Exception) { notify(e.message ?: "Connection failed.", true) }
            finally { busy = false }
        }
    }

    LaunchedEffect(connected, host, page) {
        if (!connected) return@LaunchedEffect
        while (true) {
            try {
                val snapshot = SciNETApi.get(host, "/api/system")
                if (page == "HOME") {
                    system = snapshot
                    events = SciNETApi.get(host, "/api/events").optJSONArray("events").objects()
                }
            } catch (e: Exception) {
                if (page == "HOME") notify("Host connection interrupted. Retrying…", true)
            }
            delay(if (page == "HOME") 1800 else 5000)
        }
    }
    LaunchedEffect(page, connected, host) {
        if (connected && page != "PROCESSES") try {
            SciNETApi.post(host, "/api/processes/monitor", JSONObject().put("enabled", false))
        } catch (_: Exception) { }
    }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(36.dp).background(Raised, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text("S", fontSize = 20.sp, fontWeight = FontWeight.Black) }
                    Column {
                        Text("SCINET", fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                        Text("PC LINK / ANDROID", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (connected) Good else Muted, RoundedCornerShape(50)))
                    Spacer(Modifier.width(6.dp))
                    Text(if (connected) "ONLINE" else "OFFLINE", color = Muted, fontSize = 9.sp)
                    if (connected) TextButton(onClick = { connected = false; page = "HOME" }) { Text("HOST", fontSize = 10.sp, color = Ink) }
                }
            }
            HorizontalDivider(color = Color(0xFF20242A))
            if (!connected) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
                    Spacer(Modifier.height(6.dp))
                    Text("LOCAL NETWORK / 01", color = Accent, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    Text("Your PC.\nIn your hands.", fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
                    Text("A native Android companion for your Windows SciNET host.", color = Muted, fontSize = 14.sp)
                    PanelCard {
                        SectionHeading("HOST CONNECTION", "Connect to your PC")
                        OutlinedTextField(value = host, onValueChange = { host = it }, modifier = Modifier.fillMaxWidth(), label = { Text("PC address") }, placeholder = { Text("http://192.168.1.25:8000") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = fieldColors())
                        Text("Use your PC's local IPv4 address and port 8000. Both devices must be on a reachable private network.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                        PrimaryButton(if (busy) "CONNECTING…" else "CONNECT TO PC", !busy) { connect() }
                        if (notice.isNotBlank()) Text(notice, color = if (noticeBad) Bad else Good, fontSize = 11.sp)
                    }
                    PanelCard {
                        SectionHeading("NATIVE TOOLS", "Everything in one place")
                        FeatureLine("01", "System dashboard", "Live CPU, memory, disk, network and devices")
                        FeatureLine("02", "Remote control", "Touchpad, keyboard, media and power")
                        FeatureLine("03", "File manager", "Browse, transfer, rename and edit text")
                        FeatureLine("04", "Process manager", "On-demand process inspection and actions")
                        FeatureLine("05", "Keyboard controller", "Hold mapped keys for keyboard-based games")
                    }
                    Text("PRIVATE NETWORK ONLY • NEVER EXPOSE THE SERVER TO THE PUBLIC INTERNET", color = Muted, fontSize = 9.sp)
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(15.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    when (page) {
                        "HOME" -> DashboardScreen(system, events, host, onRefresh = {
                            scope.launch {
                                try { system = SciNETApi.get(host, "/api/system"); events = SciNETApi.get(host, "/api/events").optJSONArray("events").objects(); notify("Dashboard refreshed") }
                                catch (e: Exception) { notify(e.message ?: "Refresh failed", true) }
                            }
                        }, onRename = { name ->
                            scope.launch {
                                try { SciNETApi.put(host, "/api/device-name", JSONObject().put("name", name)); system = SciNETApi.get(host, "/api/system"); notify("Device name saved") }
                                catch (e: Exception) { notify(e.message ?: "Could not rename device", true) }
                            }
                        }, onClearEvents = {
                            scope.launch {
                                try { SciNETApi.delete(host, "/api/events"); events = emptyList(); notify("Activity cleared") }
                                catch (e: Exception) { notify(e.message ?: "Could not clear activity", true) }
                            }
                        })
                        "CONTROL" -> ControlScreen(onAction = { action, extra -> control(action, extra) })
                        "FILES" -> FilesScreen(host, onMessage = { text, bad -> notify(text, bad) })
                        "CLIPBOARD" -> ClipboardScreen(host, onMessage = { text, bad -> notify(text, bad) })
                        "PROCESSES" -> ProcessScreen(host, onMessage = { text, bad -> notify(text, bad) })
                        "CONTROLLER" -> ControllerScreen(onAction = { action, extra -> control(action, extra) })
                        else -> ScreenMirrorScreen(host = host, onMessage = { text, bad -> notify(text, bad) })
                    }
                    if (notice.isNotBlank()) Text(notice, color = if (noticeBad) Bad else Good, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().background(if (noticeBad) Color(0xFF1B1111) else Color(0xFF101A13), RoundedCornerShape(8.dp)).padding(11.dp))
                }
                Row(Modifier.fillMaxWidth().background(Color(0xFF0C0F13)).padding(horizontal = 3.dp, vertical = 5.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    listOf("HOME" to "⌂", "CONTROL" to "⌁", "FILES" to "▤", "CLIPBOARD" to "▣", "PROCESSES" to "≋", "CONTROLLER" to "✣", "SCREEN" to "▱").forEach { (key, symbol) ->
                        Column(Modifier.weight(1f).background(if (page == key) Raised else Color.Transparent, RoundedCornerShape(7.dp)).padding(vertical = 7.dp).pointerInput(key) { androidx.compose.foundation.gestures.detectTapGestures { page = key } }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(symbol, color = if (page == key) Accent else Muted, fontSize = 17.sp)
                            Text(key, color = if (page == key) Ink else Muted, fontSize = 6.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}
@Composable
internal fun SectionHeading(kicker: String, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(kicker, color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
@Composable
internal fun PageHeading(kicker: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(kicker, color = Accent, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(47.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg), shape = RoundedCornerShape(8.dp)) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
    }
}
@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Edge, focusedLabelColor = Accent, unfocusedLabelColor = Muted, cursorColor = Accent, focusedTextColor = Ink, unfocusedTextColor = Ink)
@Composable
internal fun FeatureLine(number: String, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(number, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}
@Composable
internal fun StatCard(title: String, value: String, detail: String? = null) {
    PanelCard {
        Text(title, color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp)
        Text(value, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        if (detail != null) Text(detail, color = Muted, fontSize = 9.sp)
    }
}
@Composable
internal fun EmptyText(text: String) { Text(text, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp)) }
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    val i = (kotlin.math.ln(bytes.toDouble()) / kotlin.math.ln(1024.0)).toInt().coerceIn(0, 4)
    val value = bytes / Math.pow(1024.0, i.toDouble())
    return String.format(java.util.Locale.getDefault(), if (i == 0) "%.0f %s" else "%.1f %s", value, units[i])
}
internal fun formatUptime(s: Long): String {
    var n = s.coerceAtLeast(0); val days = n / 86400; n %= 86400
    val h = n / 3600; n %= 3600; val m = n / 60; val sec = n % 60
    return (if (days > 0) "%02d:".format(days) else "") + "%02d:%02d:%02d".format(h, m, sec)
}
