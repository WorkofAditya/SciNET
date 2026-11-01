package com.workofaditya.scinet

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private data class PadButton(val id: String, val label: String, val key: String, val size: Int = 56, val accent: Boolean = false)

private val defaultPadPositions = mapOf(
    "joystick" to Offset(.13f, .49f),
    "w" to Offset(.13f, .32f), "a" to Offset(.075f, .49f),
    "s" to Offset(.13f, .66f), "d" to Offset(.185f, .49f),
    "space" to Offset(.78f, .28f), "shift" to Offset(.88f, .43f),
    "e" to Offset(.77f, .53f), "f" to Offset(.88f, .63f),
    "r" to Offset(.68f, .68f), "q" to Offset(.91f, .25f),
    "ctrl" to Offset(.91f, .79f), "mouse-left" to Offset(.66f, .34f),
    "mouse-right" to Offset(.76f, .83f), "esc" to Offset(.91f, .17f)
)

@Composable
internal fun ControllerScreen(host: String, onAction: (String, Map<String, Any?>) -> Unit, onExit: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val prefs = remember { context.getSharedPreferences("scinet-controller", Context.MODE_PRIVATE) }
    var editMode by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var sensitivity by remember { mutableFloatStateOf(prefs.getFloat("camera_sensitivity", 3.0f).coerceIn(0.5f, 8.0f)) }
    var movementMode by remember { mutableStateOf(prefs.getString("movement_mode", "wasd") ?: "wasd") }
    var joystickOffset by remember { mutableStateOf(Offset.Zero) }
    val currentOnError by rememberUpdatedState(onError)
    val cameraClient = remember(host) { CameraInputClient(host) { message -> currentOnError(message) } }
    val joystickClient = remember(host, movementMode) {
        if (movementMode == "joystick") JoystickInputClient(host) { message -> currentOnError(message) } else null
    }
    val activeSensitivity = rememberUpdatedState(sensitivity)
    val positions = remember {
        mutableStateMapOf<String, Offset>().apply {
            putAll(defaultPadPositions)
            runCatching {
                val saved = JSONObject(prefs.getString("positions", "{}") ?: "{}")
                defaultPadPositions.keys.forEach { id ->
                    if (saved.has(id)) {
                        val p = saved.getJSONObject(id)
                        this[id] = Offset(p.optDouble("x", defaultPadPositions.getValue(id).x.toDouble()).toFloat().coerceIn(.03f, .97f),
                            p.optDouble("y", defaultPadPositions.getValue(id).y.toDouble()).toFloat().coerceIn(.08f, .92f))
                    }
                }
            }
        }
    }
    val originalOrientation = remember(activity) { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    val originalUi = remember(activity) { activity?.window?.decorView?.systemUiVisibility ?: 0 }
    LaunchedEffect(sensitivity) {
        prefs.edit().putFloat("camera_sensitivity", sensitivity).apply()
    }
    LaunchedEffect(movementMode) {
        prefs.edit().putString("movement_mode", movementMode).apply()
        joystickOffset = Offset.Zero
    }

    DisposableEffect(cameraClient) {
        cameraClient.connect()
        onDispose { cameraClient.close() }
    }

    DisposableEffect(joystickClient) {
        joystickClient?.connect()
        onDispose {
            joystickClient?.sendAxes(0f, 0f)
            joystickClient?.close()
        }
    }

    DisposableEffect(activity) {
        activity?.let {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                it.window.insetsController?.let { bars ->
                    bars.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    bars.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                }
            } else {
                it.window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            }
        }
        onDispose {
            activity?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    it.window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                }
                it.requestedOrientation = originalOrientation
                it.window.decorView.systemUiVisibility = originalUi
                it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
    BackHandler { onExit() }

    val buttons = listOf(
        PadButton("w", "W", "w"), PadButton("a", "A", "a"), PadButton("s", "S", "s"), PadButton("d", "D", "d"),
        PadButton("space", "JUMP", "space", 60, true), PadButton("shift", "SPRINT", "shift"),
        PadButton("e", "E", "e"), PadButton("f", "F", "f"), PadButton("r", "R", "r"),
        PadButton("q", "Q", "q"), PadButton("ctrl", "C", "ctrl"),
        PadButton("esc", "ESC", "esc", 48), PadButton("mouse-left", "LMB", "mouse-left", 54, true),
        PadButton("mouse-right", "RMB", "mouse-right", 54)
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF080B0F))) {
        val areaWidth = maxWidth
        val areaHeight = maxHeight
        Box(Modifier.fillMaxSize().background(Color(0xFF0D131A)))
        // Invisible camera-look surface. Existing controls are rendered above it,
        // so their taps and their drag-to-reposition behavior keep priority.
        Box(
            Modifier
                .offset(x = areaWidth * 0.22f, y = areaHeight * 0.12f)
                .size(width = areaWidth * 0.43f, height = areaHeight * 0.78f)
                .pointerInput(editMode, settingsOpen) {
                    if (!editMode && !settingsOpen) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            cameraClient.sendDelta(
                                dragAmount.x,
                                dragAmount.y,
                                activeSensitivity.value
                            )
                        }
                    }
                }
        )
        if (movementMode == "joystick") {
            val joystickSize = 148.dp
            val joystickPosition = positions["joystick"] ?: defaultPadPositions.getValue("joystick")
            Box(
                Modifier
                    .offset(x = areaWidth * joystickPosition.x - joystickSize / 2, y = areaHeight * joystickPosition.y - joystickSize / 2)
                    .size(joystickSize)
                    .clip(CircleShape)
                    .background(Color(0xAA18232D))
                    .border(2.dp, if (editMode) Color(0xFF80C5E8) else Color(0xFF526675), CircleShape)
                    .pointerInput(joystickClient, settingsOpen, editMode) {
                        if (editMode) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val nx = (positions.getValue("joystick").x + dragAmount.x / constraints.maxWidth).coerceIn(.06f, .94f)
                                val ny = (positions.getValue("joystick").y + dragAmount.y / constraints.maxHeight).coerceIn(.12f, .88f)
                                positions["joystick"] = Offset(nx, ny)
                            }
                        } else if (!settingsOpen && joystickClient != null) {
                            detectDragGestures(
                                onDragStart = { joystickOffset = Offset.Zero },
                                onDragEnd = {
                                    joystickOffset = Offset.Zero
                                    joystickClient.sendAxes(0f, 0f)
                                },
                                onDragCancel = {
                                    joystickOffset = Offset.Zero
                                    joystickClient.sendAxes(0f, 0f)
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                val maxTravel = size.width * 0.32f
                                val raw = joystickOffset + dragAmount
                                val distance = kotlin.math.sqrt(raw.x * raw.x + raw.y * raw.y)
                                val next = if (distance > maxTravel && distance > 0f) raw * (maxTravel / distance) else raw
                                joystickOffset = next
                                joystickClient.sendAxes(
                                    (next.x / maxTravel).coerceIn(-1f, 1f),
                                    (-next.y / maxTravel).coerceIn(-1f, 1f)
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(86.dp).border(1.dp, Color(0x665F788B), CircleShape))
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset { IntOffset(joystickOffset.x.toInt(), joystickOffset.y.toInt()) }
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF91BBD2))
                        .border(2.dp, Color(0xFFD9EFFB), CircleShape)
                )
                if (editMode) Text("STICK", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        buttons.filterNot { movementMode == "joystick" && it.id in setOf("w", "a", "s", "d") }.forEach { button ->
            val pos = positions[button.id] ?: defaultPadPositions.getValue(button.id)
            val diameter = button.size.dp
            Box(
                Modifier.offset(x = areaWidth * pos.x - diameter / 2, y = areaHeight * pos.y - diameter / 2)
                    .size(diameter)
                    .clip(CircleShape)
                    .background(if (editMode) Color(0xFF273847) else if (button.accent) Color(0xFF344C5B) else Color(0xCC171E26))
                    .border(if (editMode) 2.dp else 1.dp, if (editMode) Color(0xFF80C5E8) else Color(0xFF52616F), CircleShape)
                    .pointerInput(button.id, editMode, settingsOpen) {
                        if (editMode) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val nx = (positions.getValue(button.id).x + drag.x / constraints.maxWidth).coerceIn(.035f, .965f)
                                val ny = (positions.getValue(button.id).y + drag.y / constraints.maxHeight).coerceIn(.09f, .91f)
                                positions[button.id] = Offset(nx, ny)
                            }
                        } else if (!settingsOpen) {
                            detectTapGestures(onPress = {
                                if (button.id == "mouse-left" || button.id == "mouse-right") {
                                    val mouseButton = if (button.id == "mouse-left") "left" else "right"
                                    onAction("mouse-hold", mapOf("button" to mouseButton, "down" to true))
                                    try { tryAwaitRelease() } finally { onAction("mouse-hold", mapOf("button" to mouseButton, "down" to false)) }
                                } else {
                                    onAction("key-hold", mapOf("key" to button.key, "down" to true))
                                    try { tryAwaitRelease() } finally { onAction("key-hold", mapOf("key" to button.key, "down" to false)) }
                                }
                            })
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(button.label, color = Color(0xFFF0F4F7), fontSize = if (button.label.length > 2) 9.sp else 17.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = if (button.label.length > 2) .5.sp else 0.sp)
            }
        }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("SCINET  /  GAMEPAD", color = Color(0xFFB8C7D2), fontSize = 9.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
                Text(if (editMode) "DRAG ANY CONTROL TO REPOSITION" else "TOUCH AND HOLD TO PRESS", color = Color(0xFF74818D), fontSize = 8.sp, letterSpacing = 1.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { settingsOpen = !settingsOpen }) {
                    Text("⚙ SETTINGS", color = Color(0xFFB8DDF0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onExit) { Text("EXIT  ✕", color = Color.White, fontSize = 10.sp) }
            }
        }

        if (settingsOpen) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 54.dp, end = 14.dp)
                    .width(300.dp)
                    .heightIn(max = areaHeight - 70.dp)
                    .border(1.dp, Color(0xFF435361), RoundedCornerShape(14.dp)),
                color = Color(0xF0182029),
                contentColor = Color(0xFFEAF0F4),
                shape = RoundedCornerShape(14.dp),
                shadowElevation = 12.dp
            ) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 15.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text("CONTROLLER SETTINGS", color = Color(0xFFEAF0F4), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    HorizontalDivider(color = Color(0xFF34414C))
                    Text("MOVEMENT INPUT", color = Color(0xFFB8C7D2), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = movementMode == "wasd", onClick = { movementMode = "wasd" }, label = { Text("WASD") })
                        FilterChip(selected = movementMode == "joystick", onClick = { movementMode = "joystick" }, label = { Text("JOYSTICK") })
                    }
                    Text("Joystick mode sends a true analog Xbox stick to Windows.", color = Color(0xFF82909B), fontSize = 9.sp, lineHeight = 13.sp)
                    HorizontalDivider(color = Color(0xFF34414C))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CAMERA SENSITIVITY", color = Color(0xFFB8C7D2), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${(sensitivity * 10).toInt() / 10f}×", color = Color(0xFFB8DDF0), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = sensitivity,
                        onValueChange = { sensitivity = it },
                        valueRange = 0.5f..8.0f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Higher sensitivity moves the game camera farther with the same swipe.",
                        color = Color(0xFF82909B),
                        fontSize = 9.sp,
                        lineHeight = 13.sp
                    )
                    HorizontalDivider(color = Color(0xFF34414C))
                    if (editMode) {
                        Text("LAYOUT EDITING", color = Color(0xFFB8C7D2), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val saved = JSONObject()
                                positions.forEach { (id, p) -> saved.put(id, JSONObject().put("x", p.x).put("y", p.y)) }
                                prefs.edit().putString("positions", saved.toString()).apply()
                                editMode = false
                                settingsOpen = false
                            }) {
                                Text("SAVE LAYOUT", color = Color(0xFFB8DDF0), fontSize = 10.sp)
                            }
                            TextButton(onClick = {
                                positions.clear()
                                positions.putAll(defaultPadPositions)
                            }) {
                                Text("RESET", color = Color(0xFF9AA7B2), fontSize = 10.sp)
                            }
                        }
                    } else {
                        TextButton(onClick = {
                            editMode = true
                            settingsOpen = false
                        }) {
                            Text("EDIT LAYOUT", color = Color(0xFFB8DDF0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
