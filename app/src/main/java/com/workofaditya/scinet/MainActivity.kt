package com.workofaditya.scinet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import org.json.JSONArray
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
        val peerService = Intent(this, PeerNetworkService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(peerService) else startService(peerService)
        setContent { MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel, primary = Accent, onPrimary = Bg, onBackground = Ink, onSurface = Ink, secondary = Muted, error = Bad)) { SciNETApp() } }
    }
}

@Composable
private fun SciNETApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("scinet-native", Context.MODE_PRIVATE) }
    val localHost = "http://127.0.0.1:8765"
    var pcHost by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var pcAddress by remember { mutableStateOf(pcHost) }
    var host by remember { mutableStateOf(localHost) }
    var connected by remember { mutableStateOf(true) }
    var peerDevices by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var activePeer by remember { mutableStateOf<JSONObject?>(null) }
    var targetMenuExpanded by remember { mutableStateOf(false) }
    var showPcDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val trustedPeers = context.getSharedPreferences("scinet-trust", Context.MODE_PRIVATE)
            peerDevices = PeerNetworkState.peers.values
                .map { peer -> JSONObject(peer.toString()).put("trusted", trustedPeers.contains(peer.optString("id"))) }
                .sortedBy { it.optString("name").lowercase() }
            delay(1000)
        }
    }
    var busy by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf("HOME") }
    var screenFullscreen by remember { mutableStateOf(false) }
    var system by remember { mutableStateOf(JSONObject()) }
    var events by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var notice by remember { mutableStateOf("") }
    var noticeBad by remember { mutableStateOf(false) }
    var noticeVisible by remember { mutableStateOf(false) }
    var noticeExpanded by remember { mutableStateOf(false) }
    var noticeVersion by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val keyboardMutex = remember { Mutex() }

    fun notify(text: String, bad: Boolean = false) {
        notice = text
        noticeBad = bad
        noticeExpanded = false
        noticeVisible = true
        noticeVersion++
    }
    LaunchedEffect(noticeVersion, noticeExpanded) {
        if (notice.isNotBlank()) {
            delay(if (noticeExpanded) 8000 else if (noticeBad) 5000 else 2500)
            noticeVisible = false
            noticeExpanded = false
        }
    }
    fun control(action: String, extra: Map<String, Any?> = emptyMap()) {
        scope.launch {
            suspend fun sendRequest() {
                try {
                    val body = JSONObject().put("action", action)
                    extra.forEach { (key, value) -> body.put(key, value) }
                    SciNETApi.post(host, "/api/control", body)
                    if (action != "key-hold" && action != "mouse-hold" && action != "mouse-move") notify("Sent: " + action.replace('-', ' '))
                } catch (e: Exception) { notify(e.message ?: "Could not reach the PC.", true) }
            }
            if (action == "type" || action == "key" || action == "key-hold" || action == "mouse-hold") keyboardMutex.withLock { sendRequest() }
            else sendRequest()
        }
    }
    fun connect(targetHost: String = pcAddress) {
        if (targetHost.isBlank()) { notify("Enter the PC's SciNET address first.", true); return }
        if (busy) return
        busy = true
        scope.launch {
            try {
                val normalized = SciNETApi.normalizeHost(targetHost)
                val snapshot = SciNETApi.get(normalized, "/api/system")
                host = normalized
                activePeer = null
                pcHost = normalized
                pcAddress = normalized
                prefs.edit().putString("host", normalized).apply()
                system = snapshot
                events = SciNETApi.get(normalized, "/api/events").optJSONArray("events").objects()
                connected = true
                page = "HOME"
                targetMenuExpanded = false
                notify("Connected to " + system.optString("hostname", "SciNET PC"))
            } catch (e: Exception) { notify(e.message ?: "Connection failed.", true) }
            finally { busy = false }
        }
    }

    fun selectLocalTarget() {
        activePeer = null
        host = localHost
        connected = true
        page = "HOME"
        targetMenuExpanded = false
        scope.launch {
            try {
                system = SciNETApi.get(localHost, "/api/system")
                events = SciNETApi.get(localHost, "/api/events").optJSONArray("events").objects()
            } catch (_: Exception) {
                system = JSONObject().put("hostname", "THIS PHONE")
            }
        }
    }

    fun selectPeerTarget(peer: JSONObject) {
        val peerId = peer.optString("id")
        if (!peer.optBoolean("trusted") && !context.getSharedPreferences("scinet-trust", Context.MODE_PRIVATE).contains(peerId)) {
            notify("Pair with this device by scanning its SciNET QR code first.", true)
            return
        }
        val peerHost = peer.optString("host")
        val port = peer.optInt("port", 8765)
        if (peerHost.isBlank()) {
            notify("This device has no reachable local-network address yet.", true)
            return
        }
        val endpoint = JSONObject(peer.toString()).put("trusted", true)
        activePeer = endpoint
        host = "http://$peerHost:$port"
        connected = true
        targetMenuExpanded = false
        page = "HOME"
        scope.launch {
            try {
                system = SciNETApi.get(host, "/api/system")
                events = SciNETApi.get(host, "/api/events").optJSONArray("events").objects()
                notify("Selected " + peer.optString("name", "SciNET device"))
            } catch (e: Exception) {
                notify(e.message ?: "Could not reach this device.", true)
            }
        }
    }

    val qrScannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra("scinet_host")?.trim().orEmpty()
            if (scanned.startsWith("http://") || scanned.startsWith("https://")) {
                val scannedUri = android.net.Uri.parse(scanned)
                val peerId = scannedUri.getQueryParameter("deviceId")
                if (scannedUri.port == 8765 && scannedUri.getQueryParameter("scinet") == "peer" && !peerId.isNullOrBlank()) {
                    val pairingToken = scannedUri.getQueryParameter("token").orEmpty()
                    if (pairingToken.isBlank()) {
                        notify("This phone QR has no pairing token. Refresh the QR on the other phone.", true)
                    } else if (!busy) {
                        busy = true
                        scope.launch {
                            try {
                                val remoteHost = "http://" + (scannedUri.host ?: throw IllegalStateException("QR has no device address")) + ":8765"
                                val localName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"
                                val pairing = SciNETApi.post(
                                    remoteHost,
                                    "/api/pair/claim",
                                    JSONObject()
                                        .put("deviceId", PeerNetworkState.localDeviceId)
                                        .put("deviceName", localName)
                                        .put("platform", "android")
                                        .put("token", pairingToken)
                                )
                                val peerKey = pairing.optString("peerKey")
                                if (peerKey.isBlank()) throw IllegalStateException("The other phone did not return a pairing key.")
                                context.getSharedPreferences("scinet-trust", Context.MODE_PRIVATE)
                                    .edit().putString(peerId, peerKey).apply()
                                val peerIntent = Intent(context, PeerNetworkService::class.java).apply {
                                    action = PeerNetworkService.ACTION_CONNECT_PEER
                                    putExtra(PeerNetworkService.EXTRA_PEER_ID, peerId)
                                    putExtra(PeerNetworkService.EXTRA_PEER_NAME, pairing.optString("deviceName", scannedUri.getQueryParameter("name") ?: "SciNET phone"))
                                    putExtra(PeerNetworkService.EXTRA_PEER_HOST, scannedUri.host ?: "")
                                    putExtra(PeerNetworkService.EXTRA_PEER_PORT, 8765)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(peerIntent) else context.startService(peerIntent)
                                selectPeerTarget(
                                    JSONObject()
                                        .put("id", peerId)
                                        .put("name", pairing.optString("deviceName", scannedUri.getQueryParameter("name") ?: "SciNET phone"))
                                        .put("platform", "android")
                                        .put("host", scannedUri.host ?: "")
                                        .put("port", 8765)
                                        .put("connected", true)
                                        .put("trusted", true)
                                )
                            } catch (error: Exception) {
                                notify(error.message ?: "Could not pair with this phone.", true)
                            } finally {
                                busy = false
                            }
                        }
                    }
                } else {
                    connect(scanned)
                }
            } else if (scanned.isNotBlank()) {
                notify("That QR code is not a valid SciNET address.", true)
            }
        }
    }

    LaunchedEffect(Unit) {
        scope.launch {
            repeat(20) {
                try {
                    system = SciNETApi.get(localHost, "/api/system")
                    events = SciNETApi.get(localHost, "/api/events").optJSONArray("events").objects()
                    connected = true
                    return@launch
                } catch (_: Exception) {
                    delay(500)
                }
            }
            system = JSONObject().put("hostname", "THIS PHONE")
        }
        if (pcHost.isNotBlank()) {
            scope.launch {
                runCatching { SciNETApi.get(pcHost, "/api/system") }
                    .onSuccess {
                        host = pcHost
                        activePeer = null
                        system = it
                        connected = true
                    }
            }
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
            delay(if (page == "HOME") 1000 else 5000)
        }
    }
    LaunchedEffect(page, connected, host) {
        if (connected && page != "PROCESSES") try {
            SciNETApi.post(host, "/api/processes/monitor", JSONObject().put("enabled", false))
        } catch (_: Exception) { }
    }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        Column(Modifier.fillMaxSize().then(if (screenFullscreen || page == "CONTROLLER") Modifier else Modifier.statusBarsPadding())) {
            if (!screenFullscreen && page != "CONTROLLER") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(36.dp).background(Raised, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text("S", fontSize = 20.sp, fontWeight = FontWeight.Black) }
                    Column {
                        Text("SCINET", fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                        Text("ANDROID / DEVICE LINK", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (connected) Good else Muted, RoundedCornerShape(50)))
                    Spacer(Modifier.width(6.dp))
                    Text(if (connected) "ONLINE" else "RETRYING", color = Muted, fontSize = 9.sp)
                    TextButton(onClick = { targetMenuExpanded = true }) {
                        Text(
                            activePeer?.optString("name")?.takeIf { it.isNotBlank() }
                                ?: if (host == localHost) "THIS PHONE" else "PC",
                            fontSize = 10.sp,
                            color = Ink
                        )
                    }
                    DropdownMenu(expanded = targetMenuExpanded, onDismissRequest = { targetMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("THIS PHONE") },
                            onClick = { selectLocalTarget() }
                        )
                        DropdownMenuItem(
                            text = { Text("CONNECT TO PC…") },
                            onClick = { targetMenuExpanded = false; pcAddress = pcHost; showPcDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("SCAN DEVICE QR CODE") },
                            onClick = {
                                targetMenuExpanded = false
                                qrScannerLauncher.launch(Intent(context, QrScannerActivity::class.java))
                            }
                        )
                        HorizontalDivider(color = Edge)
                        if (peerDevices.none { it.optBoolean("trusted") }) {
                            DropdownMenuItem(
                                text = { Text("NO PAIRED DEVICES", color = Muted) },
                                enabled = false,
                                onClick = {}
                            )
                        }
                        peerDevices.filter { it.optBoolean("trusted") }.forEach { peer ->
                            DropdownMenuItem(
                                text = { Text(peer.optString("name", "SciNET device")) },
                                onClick = { selectPeerTarget(peer) }
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF20242A))
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().then(
                    if (screenFullscreen || page == "CONTROLLER") Modifier
                    else Modifier.verticalScroll(rememberScrollState()).padding(15.dp)
                ),
                verticalArrangement = if (screenFullscreen || page == "CONTROLLER") Arrangement.Top else Arrangement.spacedBy(13.dp)
            ) {
                when (page) {
                    "HOME" -> DashboardScreen(system, events, host, onRefresh = {
                        scope.launch {
                            try {
                                system = SciNETApi.get(host, "/api/system")
                                events = SciNETApi.get(host, "/api/events").optJSONArray("events").objects()
                                notify("Dashboard refreshed")
                            } catch (e: Exception) { notify(e.message ?: "Refresh failed", true) }
                        }
                    }, onRename = { name ->
                        scope.launch {
                            try {
                                SciNETApi.put(host, "/api/device-name", JSONObject().put("name", name))
                                system = SciNETApi.get(host, "/api/system")
                                notify("Device name saved")
                            } catch (e: Exception) { notify(e.message ?: "Could not rename device", true) }
                        }
                    }, onClearEvents = {
                        scope.launch {
                            try {
                                SciNETApi.delete(host, "/api/events")
                                events = emptyList()
                                notify("Activity cleared")
                            } catch (e: Exception) { notify(e.message ?: "Could not clear activity", true) }
                        }
                    }, onLogout = { selectLocalTarget() })
                    "CONTROL" -> ControlScreen(host = host, onAction = { action, extra -> control(action, extra) }, onMessage = { text, bad -> notify(text, bad) })
                    "FILES" -> FilesScreen(host, peerTarget = activePeer, onMessage = { text, bad -> notify(text, bad) })
                    "PROCESSES" -> ProcessScreen(host, onMessage = { text, bad -> notify(text, bad) })
                    "CONTROLLER" -> ControllerScreen(
                        host = host,
                        onAction = { action, extra -> control(action, extra) },
                        onExit = { page = "CONTROL" },
                        onError = { message -> notify(message, true) },
                        onCheat = { code, onComplete ->
                            scope.launch {
                                try {
                                    SciNETApi.post(host, "/api/game/cheat", JSONObject().put("code", code))
                                    onComplete(null)
                                } catch (e: Exception) {
                                    val message = e.message.orEmpty()
                                    onComplete(
                                        if (message.contains("404")) {
                                            "PC SciNET server is outdated. Update server.py, then restart the PC server."
                                        } else {
                                            message.ifBlank { "Could not send the game shortcut." }
                                        }
                                    )
                                }
                            }
                        }
                    )
                    else -> ScreenMirrorScreen(host = host, onMessage = { text, bad -> notify(text, bad) }, fullscreen = screenFullscreen, onFullscreenChanged = { screenFullscreen = it })
                }
            }
            if (!screenFullscreen && page != "CONTROLLER") {
                NotificationToast(
                    message = notice,
                    isError = noticeBad,
                    visible = noticeVisible,
                    connected = true,
                    expanded = noticeExpanded,
                    onClick = { noticeExpanded = !noticeExpanded }
                )
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF0C0F13)).padding(horizontal = 3.dp, vertical = 5.dp).navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    listOf("HOME" to "⌂", "CONTROL" to "⌁", "FILES" to "▤", "PROCESSES" to "≋", "CONTROLLER" to "✣", "SCREEN" to "▱").forEach { (key, symbol) ->
                        Column(
                            Modifier.weight(1f).background(if (page == key) Raised else Color.Transparent, RoundedCornerShape(7.dp))
                                .padding(vertical = 7.dp).pointerInput(key) { detectTapGestures { page = key } },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(symbol, color = if (page == key) Accent else Muted, fontSize = 17.sp)
                            Text(key, color = if (page == key) Ink else Muted, fontSize = 6.sp, maxLines = 1)
                        }
                    }
                }
            }
            if (showPcDialog) AlertDialog(
                onDismissRequest = { showPcDialog = false },
                title = { Text("Connect to SciNET PC") },
                text = {
                    OutlinedTextField(
                        value = pcAddress,
                        onValueChange = { pcAddress = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("PC address") },
                        placeholder = { Text("http://192.168.1.25:8000") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = fieldColors()
                    )
                },
                confirmButton = {
                    TextButton(enabled = !busy && pcAddress.isNotBlank(), onClick = {
                        showPcDialog = false
                        connect(pcAddress)
                    }) { Text(if (busy) "CONNECTING…" else "CONNECT", color = Accent) }
                },
                dismissButton = {
                    TextButton(onClick = { showPcDialog = false }) { Text("CANCEL") }
                },
                containerColor = Panel
            )
        }
    }
}

@Composable
private fun NotificationToast(
    message: String,
    isError: Boolean,
    visible: Boolean,
    connected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    if (message.isNotBlank()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        Popup(
            alignment = Alignment.BottomCenter,
            offset = IntOffset(
                0,
                with(density) { (if (connected) (-88).dp else (-24).dp).roundToPx() }
            ),
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .animateContentSize()
                            .clickable(onClick = onClick),
                        color = if (isError) Color(0xFF4A2024) else Color(0xFF173D29),
                        contentColor = Color(0xFFF2F7F3),
                        shape = RoundedCornerShape(50),
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            Modifier
                                .defaultMinSize(minWidth = 44.dp, minHeight = 42.dp)
                                .padding(horizontal = if (expanded) 15.dp else 0.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                if (isError) "×" else "✓",
                                fontSize = 21.sp,
                                lineHeight = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (expanded) {
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    message,
                                    modifier = Modifier.widthIn(max = 260.dp),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = Color(0xFFF2F7F3)
                                )
                            }
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


private fun localNetworkIpv4(): String {
    return try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress.orEmpty()
    } catch (_: Exception) { "" }
}
