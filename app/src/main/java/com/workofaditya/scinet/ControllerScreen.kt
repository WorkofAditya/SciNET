package com.workofaditya.scinet

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.ui.window.Dialog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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

private data class ControllerProfile(
    val id: String,
    val name: String,
    val movementScheme: String = "WASD",
    val movementMode: String = "wasd",
    val cameraSensitivity: Float = 3.0f,
    val positions: Map<String, Offset> = defaultPadPositions
)

private const val PROFILE_STORAGE_KEY = "controller_profiles_v1"

private fun movementKey(slot: String, scheme: String): String = when (scheme) {
    "ARROWS" -> when (slot) { "up" -> "up"; "left" -> "left"; "down" -> "down"; else -> "right" }
    "IJKL" -> when (slot) { "up" -> "i"; "left" -> "j"; "down" -> "k"; else -> "l" }
    else -> when (slot) { "up" -> "w"; "left" -> "a"; "down" -> "s"; else -> "d" }
}

private fun movementLabel(slot: String, scheme: String): String =
    if (scheme == "ARROWS") when (slot) {
        "up" -> "↑"; "left" -> "←"; "down" -> "↓"; else -> "→"
    } else movementKey(slot, scheme).uppercase()

private fun readProfilePositions(value: JSONObject?): Map<String, Offset> =
    defaultPadPositions.mapValues { (id, default) ->
        val saved = value?.optJSONObject(id)
        if (saved == null) default else Offset(
            saved.optDouble("x", default.x.toDouble()).toFloat().coerceIn(.03f, .97f),
            saved.optDouble("y", default.y.toDouble()).toFloat().coerceIn(.08f, .92f)
        )
    }

private fun loadControllerProfiles(prefs: SharedPreferences): List<ControllerProfile> {
    val stored = prefs.getString(PROFILE_STORAGE_KEY, null)
    if (!stored.isNullOrBlank()) {
        val decoded = runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
                val name = item.optString("name").trim().ifBlank { "Profile" }
                val scheme = item.optString("movementScheme", "WASD").uppercase()
                    .takeIf { it in setOf("WASD", "ARROWS", "IJKL") } ?: "WASD"
                val mode = item.optString("movementMode", "wasd").lowercase()
                    .takeIf { it == "joystick" } ?: "wasd"
                ControllerProfile(
                    id = id,
                    name = name,
                    movementScheme = scheme,
                    movementMode = mode,
                    cameraSensitivity = item.optDouble("cameraSensitivity", 3.0).toFloat().coerceIn(.5f, 8f),
                    positions = readProfilePositions(item.optJSONObject("positions"))
                )
            }
        }.getOrNull()
        if (!decoded.isNullOrEmpty()) return decoded
    }

    // Migrate the existing single controller setup into a Default profile.
    val legacyPositions = runCatching {
        JSONObject(prefs.getString("positions", "{}") ?: "{}")
    }.getOrNull()
    val migrated = ControllerProfile(
        id = "default",
        name = "Default",
        movementScheme = prefs.getString("movement_scheme", "WASD")?.uppercase()
            ?.takeIf { it in setOf("WASD", "ARROWS", "IJKL") } ?: "WASD",
        movementMode = prefs.getString("movement_mode", "wasd")
            ?.lowercase()?.takeIf { it == "joystick" } ?: "wasd",
        cameraSensitivity = prefs.getFloat("camera_sensitivity", 3.0f).coerceIn(.5f, 8f),
        positions = readProfilePositions(legacyPositions)
    )
    saveControllerProfiles(prefs, listOf(migrated))
    return listOf(migrated)
}

private fun saveControllerProfiles(prefs: SharedPreferences, profiles: List<ControllerProfile>) {
    val array = JSONArray()
    profiles.forEach { profile ->
        val positions = JSONObject()
        profile.positions.forEach { (id, point) ->
            positions.put(id, JSONObject().put("x", point.x).put("y", point.y))
        }
        array.put(JSONObject()
            .put("id", profile.id)
            .put("name", profile.name)
            .put("movementScheme", profile.movementScheme)
            .put("movementMode", profile.movementMode)
            .put("cameraSensitivity", profile.cameraSensitivity)
            .put("positions", positions))
    }
    prefs.edit().putString(PROFILE_STORAGE_KEY, array.toString()).apply()
}

@Composable
internal fun ControllerScreen(host: String, onAction: (String, Map<String, Any?>) -> Unit, onExit: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val prefs = remember { context.getSharedPreferences("scinet-controller", Context.MODE_PRIVATE) }
    val profiles = remember {
        mutableStateListOf<ControllerProfile>().apply { addAll(loadControllerProfiles(prefs)) }
    }
    var activeProfileId by remember {
        mutableStateOf(
            prefs.getString("active_profile_id", null)
                ?.takeIf { savedId -> profiles.any { it.id == savedId } }
                ?: profiles.first().id
        )
    }
    val activeProfile = profiles.first { it.id == activeProfileId }
    var editMode by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var profilesOpen by remember { mutableStateOf(false) }
    var createProfileOpen by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    var profileNameError by remember { mutableStateOf("") }
    var deleteProfileId by remember { mutableStateOf<String?>(null) }
    var sensitivity by remember(activeProfileId) {
        mutableFloatStateOf(activeProfile.cameraSensitivity.coerceIn(0.5f, 8.0f))
    }
    var movementMode by remember(activeProfileId) { mutableStateOf(activeProfile.movementMode) }
    var movementScheme by remember(activeProfileId) { mutableStateOf(activeProfile.movementScheme) }
    var joystickOffset by remember { mutableStateOf(Offset.Zero) }
    val currentOnError by rememberUpdatedState(onError)
    val cameraClient = remember(host) { CameraInputClient(host) { message -> currentOnError(message) } }
    val joystickClient = remember(host, movementMode) {
        if (movementMode == "joystick") JoystickInputClient(host) { message -> currentOnError(message) } else null
    }
    val activeSensitivity = rememberUpdatedState(sensitivity)
    val positions = remember(activeProfileId) {
        mutableStateMapOf<String, Offset>().apply { putAll(activeProfile.positions) }
    }

    fun updateProfile(profileId: String, transform: (ControllerProfile) -> ControllerProfile) {
        val index = profiles.indexOfFirst { it.id == profileId }
        if (index < 0) return
        val updated = transform(profiles[index])
        if (updated != profiles[index]) {
            profiles[index] = updated
            saveControllerProfiles(prefs, profiles)
        }
    }

    fun saveCurrentLayout() {
        updateProfile(activeProfileId) {
            it.copy(
                positions = positions.toMap(),
                cameraSensitivity = sensitivity,
                movementMode = movementMode,
                movementScheme = movementScheme
            )
        }
    }

    fun activateProfile(profileId: String) {
        if (profileId == activeProfileId) {
            profilesOpen = false
            return
        }
        saveCurrentLayout()
        activeProfileId = profileId
        prefs.edit().putString("active_profile_id", profileId).apply()
        editMode = false
        settingsOpen = false
        profilesOpen = false
    }

    fun createProfile() {
        val name = newProfileName.trim().take(32)
        if (name.isBlank()) {
            profileNameError = "Enter a name for this profile."
            return
        }
        if (profiles.any { it.name.equals(name, ignoreCase = true) }) {
            profileNameError = "A profile with this name already exists."
            return
        }
        saveCurrentLayout()
        val profile = ControllerProfile(id = UUID.randomUUID().toString(), name = name)
        profiles.add(profile)
        saveControllerProfiles(prefs, profiles)
        activeProfileId = profile.id
        prefs.edit().putString("active_profile_id", profile.id).apply()
        editMode = false
        settingsOpen = false
        profilesOpen = false
        createProfileOpen = false
        newProfileName = ""
        profileNameError = ""
    }

    fun deleteProfile(profileId: String) {
        val index = profiles.indexOfFirst { it.id == profileId }
        if (index < 0) return
        profiles.removeAt(index)
        if (profiles.isEmpty()) {
            profiles.add(ControllerProfile(id = UUID.randomUUID().toString(), name = "Default"))
        }
        saveControllerProfiles(prefs, profiles)
        if (profileId == activeProfileId) {
            val next = profiles[index.coerceAtMost(profiles.lastIndex)]
            activeProfileId = next.id
            prefs.edit().putString("active_profile_id", next.id).apply()
            editMode = false
            settingsOpen = false
        }
        deleteProfileId = null
    }

    LaunchedEffect(activeProfileId) {
        prefs.edit().putString("active_profile_id", activeProfileId).apply()
    }
    LaunchedEffect(activeProfileId, sensitivity, movementMode, movementScheme) {
        updateProfile(activeProfileId) {
            it.copy(cameraSensitivity = sensitivity, movementMode = movementMode, movementScheme = movementScheme)
        }
    }
    val originalOrientation = remember(activity) { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    val originalUi = remember(activity) { activity?.window?.decorView?.systemUiVisibility ?: 0 }
    LaunchedEffect(activeProfileId, movementMode) {
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
        PadButton("w", movementLabel("up", movementScheme), movementKey("up", movementScheme)),
        PadButton("a", movementLabel("left", movementScheme), movementKey("left", movementScheme)),
        PadButton("s", movementLabel("down", movementScheme), movementKey("down", movementScheme)),
        PadButton("d", movementLabel("right", movementScheme), movementKey("right", movementScheme)),
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
                    .pointerInput(button.id, button.key, editMode, settingsOpen) {
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
                Text(
                    if (editMode) "DRAG ANY CONTROL TO REPOSITION" else "PROFILE  /  ${activeProfile.name.uppercase()}",
                    color = Color(0xFF74818D), fontSize = 8.sp, letterSpacing = 1.sp, maxLines = 1
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    saveCurrentLayout()
                    editMode = false
                    settingsOpen = false
                    profilesOpen = true
                }) {
                    Text("PROFILES", color = Color(0xFFB8DDF0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { settingsOpen = !settingsOpen }) {
                    Text("⚙ SETTINGS", color = Color(0xFFB8DDF0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onExit) { Text("EXIT  ✕", color = Color.White, fontSize = 10.sp) }
            }
        }


        if (profilesOpen) {
            Dialog(onDismissRequest = { profilesOpen = false }) {
                Surface(
                    modifier = Modifier.width(360.dp),
                    color = Color(0xFF111922),
                    contentColor = Color(0xFFEAF0F4),
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF344653)),
                    shadowElevation = 18.dp
                ) {
                    Column(
                        Modifier.padding(19.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("CONTROLLER PROFILES", color = Color(0xFFEAF0F4), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                                Text("${profiles.size} saved  ·  Select a setup for this game", color = Color(0xFF82909B), fontSize = 10.sp)
                            }
                            TextButton(onClick = { profilesOpen = false }) {
                                Text("CLOSE", color = Color(0xFF9FBFD1), fontSize = 10.sp)
                            }
                        }
                        Button(
                            onClick = {
                                newProfileName = ""
                                profileNameError = ""
                                createProfileOpen = true
                            },
                            modifier = Modifier.fillMaxWidth().height(43.dp),
                            shape = RoundedCornerShape(9.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFB8DDF0),
                                contentColor = Color(0xFF101820)
                            )
                        ) {
                            Text("+  NEW PROFILE", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                        }
                        HorizontalDivider(color = Color(0xFF2B3944))
                        Column(
                            Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            profiles.forEach { profile ->
                                val selected = profile.id == activeProfileId
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) Color(0xFF1C2B36) else Color(0xFF151E27))
                                        .border(1.dp, if (selected) Color(0xFF55758A) else Color(0xFF26333D), RoundedCornerShape(10.dp))
                                        .clickable { activateProfile(profile.id) }
                                        .padding(start = 12.dp, top = 10.dp, end = 5.dp, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(profile.name, color = Color(0xFFEAF0F4), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text(
                                            if (selected) "ACTIVE  ·  ${profile.movementScheme}  ·  ${profile.movementMode.uppercase()}"
                                            else "${profile.movementScheme}  ·  ${profile.movementMode.uppercase()}",
                                            color = if (selected) Color(0xFF9CC8E0) else Color(0xFF82909B),
                                            fontSize = 9.sp,
                                            letterSpacing = .35.sp
                                        )
                                    }
                                    if (selected) {
                                        Text("IN USE", color = Color(0xFF9CC8E0), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    } else {
                                        TextButton(onClick = { activateProfile(profile.id) }) {
                                            Text("USE", color = Color(0xFFB8DDF0), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    TextButton(
                                        onClick = { deleteProfileId = profile.id },
                                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 3.dp)
                                    ) {
                                        Text("×", color = Color(0xFFCC9696), fontSize = 18.sp)
                                    }
                                }
                            }
                        }
                        Text("Each profile keeps its own controls, key set and sensitivity.", color = Color(0xFF82909B), fontSize = 9.sp, lineHeight = 13.sp)
                    }
                }
            }
        }

        if (createProfileOpen) {
            Dialog(onDismissRequest = { createProfileOpen = false }) {
                Surface(
                    modifier = Modifier.width(330.dp),
                    color = Color(0xFF111922),
                    contentColor = Color(0xFFEAF0F4),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF344653)),
                    shadowElevation = 18.dp
                ) {
                    Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("CREATE PROFILE", color = Color(0xFFEAF0F4), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("Start with a clean controller layout and configure it for a game.", color = Color(0xFF82909B), fontSize = 10.sp, lineHeight = 15.sp)
                        OutlinedTextField(
                            value = newProfileName,
                            onValueChange = { newProfileName = it.take(32); profileNameError = "" },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Profile name") },
                            placeholder = { Text("e.g. GTA V, Minecraft, RDR2") },
                            isError = profileNameError.isNotBlank(),
                            supportingText = {
                                if (profileNameError.isNotBlank()) Text(profileNameError)
                                else Text("${newProfileName.length}/32")
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF9CC8E0),
                                unfocusedBorderColor = Color(0xFF43515D),
                                focusedLabelColor = Color(0xFF9CC8E0),
                                cursorColor = Color(0xFF9CC8E0)
                            )
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { createProfileOpen = false }) {
                                Text("CANCEL", color = Color(0xFF9BAAB5), fontSize = 10.sp)
                            }
                            Button(
                                onClick = { createProfile() },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB8DDF0), contentColor = Color(0xFF101820))
                            ) {
                                Text("CREATE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        deleteProfileId?.let { targetId ->
            val target = profiles.firstOrNull { it.id == targetId }
            if (target != null) {
                AlertDialog(
                    onDismissRequest = { deleteProfileId = null },
                    containerColor = Color(0xFF111922),
                    titleContentColor = Color(0xFFEAF0F4),
                    textContentColor = Color(0xFF9BAAB5),
                    title = { Text("Delete profile?") },
                    text = { Text("Delete ${target.name} and its saved controller layout? If it is your last profile, a fresh Default profile will be created.") },
                    confirmButton = {
                        TextButton(onClick = { deleteProfile(targetId) }) {
                            Text("DELETE", color = Color(0xFFE0A0A0), fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteProfileId = null }) {
                            Text("CANCEL", color = Color(0xFF9BAAB5))
                        }
                    }
                )
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
                        FilterChip(selected = movementMode == "wasd", onClick = { movementMode = "wasd" }, label = { Text("KEYS") })
                        FilterChip(selected = movementMode == "joystick", onClick = { movementMode = "joystick" }, label = { Text("JOYSTICK") })
                    }
                    Text("Joystick mode sends a true analog Xbox stick to Windows.", color = Color(0xFF82909B), fontSize = 9.sp, lineHeight = 13.sp)
                    HorizontalDivider(color = Color(0xFF34414C))
                    Text("DIRECTIONAL KEYS", color = Color(0xFFB8C7D2), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("WASD", "ARROWS", "IJKL").forEach { scheme ->
                            FilterChip(
                                selected = movementScheme == scheme,
                                onClick = { movementScheme = scheme },
                                label = { Text(scheme, fontSize = 10.sp) }
                            )
                        }
                    }
                    Text("Choose the key set used for up, left, down and right.", color = Color(0xFF82909B), fontSize = 9.sp, lineHeight = 13.sp)
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
                                saveCurrentLayout()
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
