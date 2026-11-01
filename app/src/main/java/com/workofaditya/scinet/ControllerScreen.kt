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
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.math.roundToInt

private data class PadButton(val id: String, val label: String, val key: String, val size: Int = 56, val accent: Boolean = false)

private val defaultPadPositions = mapOf(
    "w" to Offset(.13f, .32f), "a" to Offset(.075f, .49f),
    "s" to Offset(.13f, .66f), "d" to Offset(.185f, .49f),
    "space" to Offset(.78f, .28f), "shift" to Offset(.88f, .43f),
    "e" to Offset(.77f, .53f), "f" to Offset(.88f, .63f),
    "r" to Offset(.68f, .68f), "q" to Offset(.91f, .25f),
    "ctrl" to Offset(.91f, .79f), "mouse-left" to Offset(.66f, .34f),
    "mouse-right" to Offset(.76f, .83f), "esc" to Offset(.91f, .17f)
)

@Composable
internal fun ControllerScreen(onAction: (String, Map<String, Any?>) -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val prefs = remember { context.getSharedPreferences("scinet-controller", Context.MODE_PRIVATE) }
    var editMode by remember { mutableStateOf(false) }
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
    val cameraDeltas = remember { Channel<Offset>(Channel.UNLIMITED) }

    LaunchedEffect(cameraDeltas) {
        while (true) {
            val first = cameraDeltas.receiveCatching().getOrNull() ?: break
            var totalX = first.x
            var totalY = first.y
            withTimeoutOrNull(16L) {
                while (true) {
                    val next = cameraDeltas.receive()
                    totalX += next.x
                    totalY += next.y
                }
            }
            val dx = totalX.roundToInt()
            val dy = totalY.roundToInt()
            if (dx != 0 || dy != 0) {
                onAction("mouse-move", mapOf("dx" to dx, "dy" to dy))
            }
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
                .pointerInput(editMode) {
                    if (!editMode) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            cameraDeltas.trySend(Offset(dragAmount.x * 1.25f, dragAmount.y * 1.25f))
                        }
                    }
                }
        )
        buttons.forEach { button ->
            val pos = positions[button.id] ?: defaultPadPositions.getValue(button.id)
            val diameter = button.size.dp
            Box(
                Modifier.offset(x = areaWidth * pos.x - diameter / 2, y = areaHeight * pos.y - diameter / 2)
                    .size(diameter)
                    .clip(CircleShape)
                    .background(if (editMode) Color(0xFF273847) else if (button.accent) Color(0xFF344C5B) else Color(0xCC171E26))
                    .border(if (editMode) 2.dp else 1.dp, if (editMode) Color(0xFF80C5E8) else Color(0xFF52616F), CircleShape)
                    .pointerInput(button.id, editMode) {
                        if (editMode) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val nx = (positions.getValue(button.id).x + drag.x / constraints.maxWidth).coerceIn(.035f, .965f)
                                val ny = (positions.getValue(button.id).y + drag.y / constraints.maxHeight).coerceIn(.09f, .91f)
                                positions[button.id] = Offset(nx, ny)
                            }
                        } else {
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
                TextButton(onClick = {
                    if (editMode) {
                        val saved = JSONObject()
                        positions.forEach { (id, p) -> saved.put(id, JSONObject().put("x", p.x).put("y", p.y)) }
                        prefs.edit().putString("positions", saved.toString()).apply()
                    }
                    editMode = !editMode
                }) { Text(if (editMode) "SAVE LAYOUT" else "EDIT LAYOUT", color = Color(0xFFB8DDF0), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                if (editMode) TextButton(onClick = { positions.clear(); positions.putAll(defaultPadPositions) }) {
                    Text("RESET", color = Color(0xFF9AA7B2), fontSize = 10.sp)
                }
                TextButton(onClick = onExit) { Text("EXIT  ✕", color = Color.White, fontSize = 10.sp) }
            }
        }
    }
}
