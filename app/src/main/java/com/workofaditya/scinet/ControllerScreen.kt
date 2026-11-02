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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.text.style.TextOverflow
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

private data class ControllerKeyOption(val key: String, val label: String, val category: String)

private val controllerKeyOptions = buildList {
    addAll(('a'..'z').map { ControllerKeyOption(it.toString(), it.uppercase(), "LETTERS") })
    addAll((0..9).map { ControllerKeyOption(it.toString(), it.toString(), "NUMBER ROW") })
    addAll((1..24).map { ControllerKeyOption("f$it", "F$it", "FUNCTION KEYS") })
    addAll(listOf(
        ControllerKeyOption("esc", "ESC", "NAVIGATION & EDITING"),
        ControllerKeyOption("tab", "TAB", "NAVIGATION & EDITING"),
        ControllerKeyOption("enter", "ENTER", "NAVIGATION & EDITING"),
        ControllerKeyOption("backspace", "BACKSPACE", "NAVIGATION & EDITING"),
        ControllerKeyOption("space", "SPACE", "NAVIGATION & EDITING"),
        ControllerKeyOption("insert", "INSERT", "NAVIGATION & EDITING"),
        ControllerKeyOption("delete", "DELETE", "NAVIGATION & EDITING"),
        ControllerKeyOption("home", "HOME", "NAVIGATION & EDITING"),
        ControllerKeyOption("end", "END", "NAVIGATION & EDITING"),
        ControllerKeyOption("pageup", "PAGE UP", "NAVIGATION & EDITING"),
        ControllerKeyOption("pagedown", "PAGE DOWN", "NAVIGATION & EDITING"),
        ControllerKeyOption("up", "↑", "NAVIGATION & EDITING"),
        ControllerKeyOption("down", "↓", "NAVIGATION & EDITING"),
        ControllerKeyOption("left", "←", "NAVIGATION & EDITING"),
        ControllerKeyOption("right", "→", "NAVIGATION & EDITING"),
        ControllerKeyOption("capslock", "CAPS LOCK", "MODIFIERS & SYSTEM"),
        ControllerKeyOption("shift", "SHIFT", "MODIFIERS & SYSTEM"),
        ControllerKeyOption("ctrl", "CTRL", "MODIFIERS & SYSTEM"),
        ControllerKeyOption("alt", "ALT", "MODIFIERS & SYSTEM"),
        ControllerKeyOption("meta", "WIN", "MODIFIERS & SYSTEM"),
        ControllerKeyOption("apps", "MENU", "MODIFIERS & SYSTEM"),
        ControllerKeyOption("printscreen", "PRINT SCREEN", "MODIFIERS & SYSTEM"),
        ControllerKeyOption("scrolllock", "SCROLL LOCK", "MODIFIERS & SYSTEM"),
        ControllerKeyOption("pause", "PAUSE", "MODIFIERS & SYSTEM"),
        ControllerKeyOption("numlock", "NUM LOCK", "MODIFIERS & SYSTEM"),
        ControllerKeyOption("grave", "`", "PUNCTUATION"),
        ControllerKeyOption("minus", "-", "PUNCTUATION"),
        ControllerKeyOption("equal", "=", "PUNCTUATION"),
        ControllerKeyOption("left-bracket", "[", "PUNCTUATION"),
        ControllerKeyOption("right-bracket", "]", "PUNCTUATION"),
        ControllerKeyOption("backslash", "\\", "PUNCTUATION"),
        ControllerKeyOption("semicolon", ";", "PUNCTUATION"),
        ControllerKeyOption("apostrophe", "'", "PUNCTUATION"),
        ControllerKeyOption("comma", ",", "PUNCTUATION"),
        ControllerKeyOption("period", ".", "PUNCTUATION"),
        ControllerKeyOption("slash", "/", "PUNCTUATION"),
        ControllerKeyOption("oem-102", "ISO \\", "PUNCTUATION")
    ))
    addAll((0..9).map { ControllerKeyOption("numpad$it", "NUM $it", "NUMERIC KEYPAD") })
    addAll(listOf(
        ControllerKeyOption("numpad-divide", "NUM ÷", "NUMERIC KEYPAD"),
        ControllerKeyOption("numpad-multiply", "NUM ×", "NUMERIC KEYPAD"),
        ControllerKeyOption("numpad-subtract", "NUM −", "NUMERIC KEYPAD"),
        ControllerKeyOption("numpad-add", "NUM +", "NUMERIC KEYPAD"),
        ControllerKeyOption("numpad-enter", "NUM ENTER", "NUMERIC KEYPAD"),
        ControllerKeyOption("numpad-decimal", "NUM .", "NUMERIC KEYPAD"),
        ControllerKeyOption("numpad-separator", "NUM SEP", "NUMERIC KEYPAD")
    ))
}

private fun customKeyDefaultPosition(index: Int): Offset {
    val column = index.coerceAtLeast(0) % 4
    val row = index.coerceAtLeast(0) / 4
    return Offset(0.29f + column * 0.115f, (0.84f - row * 0.13f).coerceAtLeast(0.14f))
}

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
    val positions: Map<String, Offset> = defaultPadPositions,
    val cheatPack: String = "",
    val customKeys: List<String> = emptyList()
)

private data class GameCheat(
    val title: String,
    val code: String,
    val category: String,
    val detail: String
)

private val gtaVStoryModeCheats = listOf(
    GameCheat("Invincibility", "PAINKILLER", "PLAYER", "Temporary protection for five minutes."),
    GameCheat("Max health & armor", "TURTLE", "PLAYER", "Restores health and armor."),
    GameCheat("Recharge special ability", "POWERUP", "PLAYER", "Refills the character's special ability."),
    GameCheat("Fast run", "CATCHME", "PLAYER", "Increases running speed."),
    GameCheat("Fast swim", "GOTGILLS", "PLAYER", "Increases swimming speed."),
    GameCheat("Super jump", "HOPTOIT", "PLAYER", "Makes the player jump much higher."),
    GameCheat("Drunk mode", "LIQUOR", "PLAYER", "Makes the player intoxicated."),
    GameCheat("Skyfall", "SKYFALL", "PLAYER", "Drops the player from the sky."),
    GameCheat("Parachute", "SKYDIVE", "PLAYER", "Gives the player a parachute."),
    GameCheat("Slow-motion aim", "DEADEYE", "PLAYER", "Slows time while aiming. Re-enter to cycle intensity."),
    GameCheat("Give weapons & ammo", "TOOLUP", "COMBAT", "Gives the player weapons and ammunition."),
    GameCheat("Explosive melee", "HOTHANDS", "COMBAT", "Makes melee attacks explosive."),
    GameCheat("Explosive ammo", "HIGHEX", "COMBAT", "Makes fired ammunition explosive."),
    GameCheat("Flaming bullets", "INCENDIARY", "COMBAT", "Sets fired bullets on fire."),
    GameCheat("Raise wanted level", "FUGITIVE", "POLICE", "Increases the wanted level."),
    GameCheat("Lower wanted level", "LAWYERUP", "POLICE", "Decreases the wanted level."),
    GameCheat("Change weather", "MAKEITRAIN", "WORLD", "Cycles to the next weather condition."),
    GameCheat("Moon gravity", "FLOATER", "WORLD", "Reduces gravity."),
    GameCheat("Slidey cars", "SNOWDAY", "WORLD", "Makes vehicles lose traction."),
    GameCheat("Slow motion", "SLOWMO", "WORLD", "Slows the game world. Re-enter to cycle intensity."),
    GameCheat("Spawn Buzzard", "BUZZOFF", "VEHICLES", "Spawns a Buzzard attack helicopter."),
    GameCheat("Spawn Comet", "COMET", "VEHICLES", "Spawns a Comet sports car."),
    GameCheat("Spawn Rapid GT", "RAPIDGT", "VEHICLES", "Spawns a Rapid GT."),
    GameCheat("Spawn PCJ-600", "ROCKET", "VEHICLES", "Spawns a PCJ-600 motorcycle."),
    GameCheat("Spawn Sanchez", "OFFROAD", "VEHICLES", "Spawns a Sanchez dirt bike."),
    GameCheat("Spawn BMX", "BANDIT", "VEHICLES", "Spawns a BMX."),
    GameCheat("Spawn Caddy", "HOLEIN1", "VEHICLES", "Spawns a golf caddy."),
    GameCheat("Spawn Limo", "VINEWOOD", "VEHICLES", "Spawns a limousine."),
    GameCheat("Spawn Trashmaster", "TRASHED", "VEHICLES", "Spawns a Trashmaster."),
    GameCheat("Spawn Stunt Plane", "BARNSTORM", "VEHICLES", "Spawns a stunt plane."),
    GameCheat("Spawn Duster", "FLYSPRAY", "VEHICLES", "Spawns a Duster crop duster."),
    GameCheat("Spawn Dodo", "EXTINCT", "VEHICLES", "Spawns a Dodo seaplane. Requires unlock."),
    GameCheat("Spawn Duke O'Death", "DEATHCAR", "VEHICLES", "Spawns the Duke O'Death. Requires unlock."),
    GameCheat("Spawn Kraken", "BUBBLES", "VEHICLES", "Spawns the Kraken submarine. Requires unlock."),
    GameCheat("Director mode", "JRTALENT", "SPECIAL", "Opens Director Mode."),
)

private const val PROFILE_STORAGE_KEY = "controller_profiles_v1"
private const val GTA_V_STORY_CHEAT_PACK = "gta_v_story"

private fun movementKey(slot: String, scheme: String): String = when (scheme) {
    "ARROWS" -> when (slot) { "up" -> "up"; "left" -> "left"; "down" -> "down"; else -> "right" }
    "IJKL" -> when (slot) { "up" -> "i"; "left" -> "j"; "down" -> "k"; else -> "l" }
    else -> when (slot) { "up" -> "w"; "left" -> "a"; "down" -> "s"; else -> "d" }
}

private fun movementLabel(slot: String, scheme: String): String =
    if (scheme == "ARROWS") when (slot) {
        "up" -> "↑"; "left" -> "←"; "down" -> "↓"; else -> "→"
    } else movementKey(slot, scheme).uppercase()

private fun readProfilePositions(value: JSONObject?): Map<String, Offset> {
    val result = defaultPadPositions.toMutableMap()
    if (value != null) {
        val keys = value.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val saved = value.optJSONObject(id) ?: continue
            val default = result[id] ?: Offset(.5f, .5f)
            result[id] = Offset(
                saved.optDouble("x", default.x.toDouble()).toFloat().coerceIn(.03f, .97f),
                saved.optDouble("y", default.y.toDouble()).toFloat().coerceIn(.08f, .92f)
            )
        }
    }
    return result
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
                    positions = readProfilePositions(item.optJSONObject("positions")),
                    cheatPack = item.optString("cheatPack").ifBlank {
                        if (name.equals("Default", ignoreCase = true)) GTA_V_STORY_CHEAT_PACK else ""
                    },
                    customKeys = item.optJSONArray("customKeys")?.let { savedKeys ->
                        (0 until savedKeys.length()).mapNotNull { keyIndex ->
                            savedKeys.optString(keyIndex).takeIf { key ->
                                controllerKeyOptions.any { it.key == key }
                            }
                        }.distinct()
                    } ?: emptyList()
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
        positions = readProfilePositions(legacyPositions),
        cheatPack = GTA_V_STORY_CHEAT_PACK
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
            .put("positions", positions)
            .put("cheatPack", profile.cheatPack)
            .put("customKeys", JSONArray(profile.customKeys)))
    }
    prefs.edit().putString(PROFILE_STORAGE_KEY, array.toString()).apply()
}

@Composable
internal fun ControllerScreen(host: String, onAction: (String, Map<String, Any?>) -> Unit, onExit: () -> Unit, onError: (String) -> Unit, onCheat: (String, (String?) -> Unit) -> Unit) {
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
    var cheatsOpen by remember { mutableStateOf(false) }
    var customKeysOpen by remember { mutableStateOf(false) }
    var cheatStatus by remember(activeProfileId) { mutableStateOf("") }
    var cheatStatusBad by remember(activeProfileId) { mutableStateOf(false) }
    var sendingCheatCode by remember { mutableStateOf<String?>(null) }
    var favoriteCheatCodes by remember {
        mutableStateOf(prefs.getStringSet("gta_v_favorite_cheats", emptySet())?.toSet() ?: emptySet())
    }
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
        mutableStateMapOf<String, Offset>().apply {
            putAll(activeProfile.positions)
            activeProfile.customKeys.forEachIndexed { index, key ->
                val id = "custom-$key"
                if (!containsKey(id)) this[id] = customKeyDefaultPosition(index)
            }
        }
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

    fun toggleFavoriteCheat(code: String) {
        val updated = if (code in favoriteCheatCodes) favoriteCheatCodes - code else favoriteCheatCodes + code
        favoriteCheatCodes = updated
        prefs.edit().putStringSet("gta_v_favorite_cheats", updated).apply()
        cheatStatus = if (code in updated) "Added to favorites." else "Removed from favorites."
        cheatStatusBad = false
    }

    fun setCustomKeyEnabled(key: String, enabled: Boolean) {
        val nextKeys = activeProfile.customKeys.toMutableList()
        if (enabled) {
            if (key !in nextKeys) {
                positions["custom-$key"] = customKeyDefaultPosition(nextKeys.size)
                nextKeys.add(key)
            }
        } else {
            nextKeys.remove(key)
            positions.remove("custom-$key")
        }
        updateProfile(activeProfileId) {
            it.copy(customKeys = nextKeys, positions = positions.toMap())
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
        cheatsOpen = false
        customKeysOpen = false
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
        val profile = ControllerProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            movementScheme = movementScheme,
            movementMode = movementMode,
            cameraSensitivity = sensitivity,
            positions = positions.toMap(),
            customKeys = activeProfile.customKeys
        )
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
            cheatsOpen = false
            customKeysOpen = false
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
    BackHandler {
        when {
            customKeysOpen -> customKeysOpen = false
            cheatsOpen -> cheatsOpen = false
            profilesOpen -> profilesOpen = false
            settingsOpen -> settingsOpen = false
            editMode -> editMode = false
            else -> onExit()
        }
    }

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
    ) + activeProfile.customKeys.map { key ->
        PadButton("custom-$key", controllerKeyOptions.first { it.key == key }.label, key, 48)
    }

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
            val pos = positions[button.id] ?: customKeyDefaultPosition(activeProfile.customKeys.indexOf(button.key))
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
                    cheatsOpen = false
                    profilesOpen = true
                }) {
                    Text("PROFILES", color = Color(0xFFB8DDF0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = {
                    saveCurrentLayout()
                    editMode = false
                    settingsOpen = false
                    profilesOpen = false
                    cheatStatus = ""
                    cheatStatusBad = false
                    cheatsOpen = true
                }) {
                    Text("CHEATS", color = Color(0xFFB8DDF0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { settingsOpen = !settingsOpen }) {
                    Text("⚙ SETTINGS", color = Color(0xFFB8DDF0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onExit) { Text("EXIT  ✕", color = Color.White, fontSize = 10.sp) }
            }
        }


        if (cheatsOpen) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                color = Color(0xFF111922),
                contentColor = Color(0xFFEAF0F4),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF344653)),
                shadowElevation = 18.dp
            ) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                "GTA V  /  CHEATS",
                                color = Color(0xFFEAF0F4),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp
                            )
                            Text(
                                "TAP TO RUN  ·  HOLD TO FAVORITE",
                                color = Color(0xFF82909B),
                                fontSize = 9.sp,
                                letterSpacing = .6.sp
                            )
                        }
                        TextButton(
                            onClick = { cheatsOpen = false },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text("CLOSE  ×", color = Color(0xFFB8DDF0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    HorizontalDivider(color = Color(0xFF2B3944))

                    if (activeProfile.cheatPack != GTA_V_STORY_CHEAT_PACK) {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "NO CHEAT PRESET",
                                color = Color(0xFFEAF0F4),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Attach the GTA V Story Mode preset to this profile.",
                                color = Color(0xFF82909B),
                                fontSize = 10.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    updateProfile(activeProfileId) { it.copy(cheatPack = GTA_V_STORY_CHEAT_PACK) }
                                    cheatStatus = "GTA V Story Mode preset added."
                                    cheatStatusBad = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFB8DDF0),
                                    contentColor = Color(0xFF101820)
                                )
                            ) {
                                Text("ADD GTA V PRESET", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        val favoriteCheats = gtaVStoryModeCheats.filter { it.code in favoriteCheatCodes }
                        val favoriteCodes = favoriteCheats.map { it.code }.toSet()

                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 145.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            if (favoriteCheats.isNotEmpty()) {
                                item(
                                    key = "favorites-header",
                                    span = { GridItemSpan(maxLineSpan) }
                                ) {
                                    Text(
                                        "FAVORITES",
                                        color = Color(0xFF9CC8E0),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.1.sp,
                                        modifier = Modifier.padding(top = 3.dp, bottom = 1.dp)
                                    )
                                }
                                items(
                                    favoriteCheats,
                                    key = { "favorite-${it.code}" }
                                ) { cheat ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(42.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF1B2B36))
                                            .border(1.dp, Color(0xFF55758A), RoundedCornerShape(8.dp))
                                            .pointerInput(cheat.code, favoriteCheatCodes, sendingCheatCode) {
                                                detectTapGestures(
                                                    onTap = {
                                                        if (sendingCheatCode == null) {
                                                            sendingCheatCode = cheat.code
                                                            cheatStatus = "Sending ${cheat.title}…"
                                                            cheatStatusBad = false
                                                            onCheat(cheat.code) { error ->
                                                                sendingCheatCode = null
                                                                cheatStatus = error ?: (cheat.title + " submitted.")
                                                                cheatStatusBad = error != null
                                                            }
                                                        }
                                                    },
                                                    onLongPress = { toggleFavoriteCheat(cheat.code) }
                                                )
                                            }
                                            .padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            cheat.title,
                                            modifier = Modifier.weight(1f),
                                            color = Color(0xFFEAF0F4),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text("★", color = Color(0xFFB8DDF0), fontSize = 14.sp, modifier = Modifier.padding(start = 7.dp))
                                    }
                                }
                            }

                            listOf("PLAYER", "COMBAT", "POLICE", "WORLD", "VEHICLES", "SPECIAL").forEach { category ->
                                val categoryCheats = gtaVStoryModeCheats.filter {
                                    it.category == category && it.code !in favoriteCodes
                                }
                                if (categoryCheats.isNotEmpty()) {
                                    item(
                                        key = "category-$category",
                                        span = { GridItemSpan(maxLineSpan) }
                                    ) {
                                        Text(
                                            category,
                                            color = Color(0xFF9CC8E0),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.1.sp,
                                            modifier = Modifier.padding(top = 5.dp, bottom = 1.dp)
                                        )
                                    }
                                    items(
                                        categoryCheats,
                                        key = { "$category-${it.code}" }
                                    ) { cheat ->
                                        val isFavorite = cheat.code in favoriteCheatCodes
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(42.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF151E27))
                                                .border(1.dp, Color(0xFF2B3944), RoundedCornerShape(8.dp))
                                                .pointerInput(cheat.code, favoriteCheatCodes, sendingCheatCode) {
                                                    detectTapGestures(
                                                        onTap = {
                                                            if (sendingCheatCode == null) {
                                                                sendingCheatCode = cheat.code
                                                                cheatStatus = "Sending ${cheat.title}…"
                                                                cheatStatusBad = false
                                                                onCheat(cheat.code) { error ->
                                                                    sendingCheatCode = null
                                                                    cheatStatus = error ?: (cheat.title + " submitted.")
                                                                    cheatStatusBad = error != null
                                                                }
                                                            }
                                                        },
                                                        onLongPress = { toggleFavoriteCheat(cheat.code) }
                                                    )
                                                }
                                                .padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                cheat.title,
                                                modifier = Modifier.weight(1f),
                                                color = Color(0xFFEAF0F4),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                if (isFavorite) "★" else "☆",
                                                color = if (isFavorite) Color(0xFFB8DDF0) else Color(0xFF657785),
                                                fontSize = 14.sp,
                                                modifier = Modifier.padding(start = 7.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (cheatStatus.isNotBlank()) {
                        Text(
                            cheatStatus,
                            color = if (cheatStatusBad) Color(0xFFE0A0A0) else Color(0xFF9CBBA8),
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }


        if (customKeysOpen) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                color = Color(0xFF111922),
                contentColor = Color(0xFFEAF0F4),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF344653)),
                shadowElevation = 18.dp
            ) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("CONTROLLER  /  CUSTOM KEYS", color = Color(0xFFEAF0F4), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                            Text("SELECT KEYS TO ADD TO ${activeProfile.name.uppercase()}", color = Color(0xFF82909B), fontSize = 9.sp, letterSpacing = .6.sp)
                        }
                        TextButton(
                            onClick = { saveCurrentLayout(); customKeysOpen = false },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text("DONE  ×", color = Color(0xFFB8DDF0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    HorizontalDivider(color = Color(0xFF2B3944))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        controllerKeyOptions.groupBy { it.category }.forEach { (category, categoryKeys) ->
                            item(key = "custom-key-category-$category", span = { GridItemSpan(maxLineSpan) }) {
                                Text(category, color = Color(0xFF9CC8E0), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp, modifier = Modifier.padding(top = 5.dp, bottom = 1.dp))
                            }
                            items(categoryKeys, key = { "custom-key-${it.key}" }) { option ->
                                val selected = option.key in activeProfile.customKeys
                                Row(
                                    Modifier.fillMaxWidth().height(43.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) Color(0xFF1B2B36) else Color(0xFF151E27))
                                        .border(1.dp, if (selected) Color(0xFF55758A) else Color(0xFF2B3944), RoundedCornerShape(8.dp))
                                        .clickable { setCustomKeyEnabled(option.key, !selected) }
                                        .padding(start = 10.dp, end = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(option.label, modifier = Modifier.weight(1f), color = Color(0xFFEAF0F4), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = { setCustomKeyEnabled(option.key, it) },
                                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF9CC8E0), uncheckedColor = Color(0xFF657785), checkmarkColor = Color(0xFF101820))
                                    )
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${activeProfile.customKeys.size} EXTRA KEYS ACTIVE", modifier = Modifier.weight(1f), color = Color(0xFF9CC8E0), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                        TextButton(onClick = {
                            activeProfile.customKeys.forEach { key -> positions.remove("custom-$key") }
                            updateProfile(activeProfileId) {
                                it.copy(customKeys = emptyList(), positions = positions.toMap())
                            }
                        }) {
                            Text("CLEAR ALL", color = Color(0xFFCC9696), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("Changes are saved to this controller profile automatically. Use EDIT LAYOUT to reposition added buttons.", color = Color(0xFF82909B), fontSize = 9.sp, lineHeight = 13.sp)
                }
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
                            Modifier.heightIn(max = (areaHeight - 180.dp).coerceAtLeast(90.dp)).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            profiles.forEach { profile ->
                                val selected = profile.id == activeProfileId
                                val inputLabel = if (profile.movementMode == "joystick") "JOYSTICK" else "KEYS"
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
                                            if (selected) "ACTIVE  ·  ${profile.movementScheme}  ·  ${inputLabel}"
                                            else "${profile.movementScheme}  ·  ${inputLabel}",
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
                        Text("Starts from your current setup. Changes to this profile stay independent.", color = Color(0xFF82909B), fontSize = 10.sp, lineHeight = 15.sp)
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
                    Text("ADDITIONAL CONTROLS", color = Color(0xFFB8C7D2), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                    Text("Choose extra keyboard keys to add to this profile's on-screen controller.", color = Color(0xFF82909B), fontSize = 9.sp, lineHeight = 13.sp)
                    Button(
                        onClick = {
                            saveCurrentLayout()
                            editMode = false
                            settingsOpen = false
                            customKeysOpen = true
                        },
                        modifier = Modifier.fillMaxWidth().height(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B2B36), contentColor = Color(0xFFB8DDF0))
                    ) {
                        Text("MANAGE CUSTOM KEYS  ·  ${activeProfile.customKeys.size}", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
                    }
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
