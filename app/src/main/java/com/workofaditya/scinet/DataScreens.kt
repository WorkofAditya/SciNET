package com.workofaditya.scinet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

@Composable
internal fun FilesScreen(host: String, onMessage: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renameItem by remember { mutableStateOf<JSONObject?>(null) }
    var renameName by remember { mutableStateOf("") }
    var deleteItem by remember { mutableStateOf<JSONObject?>(null) }
    var editPath by remember { mutableStateOf("") }
    var editName by remember { mutableStateOf("") }
    var editText by remember { mutableStateOf("") }
    var originalText by remember { mutableStateOf("") }
    var editorOpen by remember { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf("") }

    suspend fun reload(path: String = folder) {
        loading = true
        try {
            val result = SciNETApi.get(host, "/api/files?path=" + SciNETApi.encode(path))
            items = result.optJSONArray("files").objects()
        } catch (e: Exception) {
            onMessage(e.message ?: "Could not load files.", true)
        } finally { loading = false }
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            var count = 0
            for (uri in uris) {
                try {
                    val name = displayName(context, uri)
                    SciNETApi.upload(context, host, uri, name, folder)
                    count++
                } catch (e: Exception) { onMessage(e.message ?: "Upload failed.", true) }
            }
            if (count > 0) {
                onMessage("Uploaded " + count + " file(s).", false)
                reload()
            }
        }
    }
    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val path = pendingDownload
        if (uri != null && path.isNotBlank()) scope.launch {
            try {
                SciNETApi.downloadTo(context, host, path, uri)
                onMessage("Download saved.", false)
            } catch (e: Exception) { onMessage(e.message ?: "Download failed.", true) }
            pendingDownload = ""
        }
    }

    LaunchedEffect(host, folder) { reload(folder) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeading("STORAGE", "File manager", "Browse and manage files stored on the SciNET PC.")
        PanelCard {
            SectionHeading("SHARED STORAGE", if (folder.isBlank()) "Shared files" else folder)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) { Text("↑ UPLOAD", fontSize = 10.sp) }
                OutlinedButton(onClick = { newFolderName = ""; showNewFolder = true }, modifier = Modifier.weight(1f), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) { Text("+ NEW FOLDER", fontSize = 10.sp) }
                OutlinedButton(onClick = { scope.launch { reload() } }, border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) { Text("↻", fontSize = 14.sp) }
            }
            if (folder.isNotBlank()) {
                OutlinedButton(onClick = { folder = folder.substringBeforeLast('/', "") }, modifier = Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) {
                    Text("← UP ONE FOLDER", fontSize = 10.sp)
                }
            }
            OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search this folder…") }, colors = fieldColors())
            Text(if (loading) "Loading…" else items.size.toString() + " items", color = Muted, fontSize = 10.sp)
        }
        if (items.isEmpty() && !loading) PanelCard { EmptyText("This folder is empty.") }
        items.filter { it.optString("name").contains(search, ignoreCase = true) }.forEach { item ->
            val name = item.optString("name")
            val path = item.optString("path")
            val isFolder = item.optString("type") == "folder"
            PanelCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(38.dp).background(Raised, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        Text(if (isFolder) "▰" else fileGlyph(name), color = if (isFolder) Accent else Ink, fontSize = 18.sp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (isFolder) "FOLDER" else formatBytes(item.optLong("size")), color = Muted, fontSize = 9.sp)
                    }
                    TextButton(onClick = {
                        if (isFolder) folder = path
                        else if (isEditableText(name)) {
                            scope.launch {
                                try {
                                    val data = SciNETApi.get(host, "/api/files/content?path=" + SciNETApi.encode(path))
                                    editPath = path
                                    editName = name
                                    editText = data.optString("content")
                                    originalText = editText
                                    editorOpen = true
                                } catch (e: Exception) { onMessage(e.message ?: "Could not open text file.", true) }
                            }
                        } else {
                            val url = SciNETApi.normalizeHost(host) + "/api/files/preview?path=" + SciNETApi.encode(path)
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            catch (_: Exception) { pendingDownload = path; downloadLauncher.launch(name) }
                        }
                    }) { Text(if (isFolder) "OPEN" else if (isEditableText(name)) "EDIT" else "PREVIEW", fontSize = 9.sp, color = Accent) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!isFolder) TextButton(onClick = { pendingDownload = path; downloadLauncher.launch(name) }) { Text("DOWNLOAD", fontSize = 8.sp) }
                    TextButton(onClick = { renameItem = item; renameName = name }) { Text("RENAME", fontSize = 8.sp) }
                    TextButton(onClick = { deleteItem = item }) { Text("DELETE", fontSize = 8.sp, color = Bad) }
                }
            }
        }
    }

    if (showNewFolder) AlertDialog(
        onDismissRequest = { showNewFolder = false },
        title = { Text("Create folder") },
        text = { OutlinedTextField(value = newFolderName, onValueChange = { newFolderName = it }, label = { Text("Folder name") }, singleLine = true, colors = fieldColors()) },
        confirmButton = {
            TextButton(onClick = {
                val name = newFolderName.trim()
                if (name.isNotEmpty()) scope.launch {
                    try {
                        SciNETApi.post(host, "/api/folders", JSONObject().put("path", folder).put("name", name))
                        showNewFolder = false
                        reload()
                        onMessage("Folder created.", false)
                    } catch (e: Exception) { onMessage(e.message ?: "Could not create folder.", true) }
                }
            }) { Text("CREATE", color = Accent) }
        },
        dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("CANCEL") } },
        containerColor = Panel
    )

    if (renameItem != null) AlertDialog(
        onDismissRequest = { renameItem = null },
        title = { Text("Rename item") },
        text = { OutlinedTextField(value = renameName, onValueChange = { renameName = it }, label = { Text("New name") }, singleLine = true, colors = fieldColors()) },
        confirmButton = {
            TextButton(onClick = {
                val target = renameItem ?: return@TextButton
                scope.launch {
                    try {
                        SciNETApi.post(host, "/api/files/rename", JSONObject().put("path", target.optString("path")).put("name", renameName.trim()))
                        renameItem = null
                        reload()
                        onMessage("Renamed successfully.", false)
                    } catch (e: Exception) { onMessage(e.message ?: "Rename failed.", true) }
                }
            }) { Text("RENAME", color = Accent) }
        },
        dismissButton = { TextButton(onClick = { renameItem = null }) { Text("CANCEL") } },
        containerColor = Panel
    )

    if (deleteItem != null) AlertDialog(
        onDismissRequest = { deleteItem = null },
        title = { Text("Delete " + deleteItem!!.optString("name") + "?") },
        text = { Text("This permanently deletes the selected item from SciNET shared storage.") },
        confirmButton = {
            TextButton(onClick = {
                val target = deleteItem ?: return@TextButton
                scope.launch {
                    try {
                        SciNETApi.delete(host, "/api/files?path=" + SciNETApi.encode(target.optString("path")))
                        deleteItem = null
                        reload()
                        onMessage("Deleted successfully.", false)
                    } catch (e: Exception) { onMessage(e.message ?: "Delete failed.", true) }
                }
            }) { Text("DELETE", color = Bad) }
        },
        dismissButton = { TextButton(onClick = { deleteItem = null }) { Text("CANCEL") } },
        containerColor = Panel
    )

    if (editorOpen) AlertDialog(
        onDismissRequest = { editorOpen = false },
        title = { Text(editName, maxLines = 1) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("UTF-8 text editor · files up to 2 MB", color = Muted, fontSize = 10.sp)
                OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 420.dp), minLines = 8, colors = fieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                Text(if (editText == originalText) "No unsaved changes" else "Unsaved changes", color = if (editText == originalText) Muted else Accent, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = editText != originalText, onClick = {
                scope.launch {
                    try {
                        SciNETApi.put(host, "/api/files/content", JSONObject().put("path", editPath).put("content", editText))
                        originalText = editText
                        editorOpen = false
                        onMessage("Changes saved.", false)
                    } catch (e: Exception) { onMessage(e.message ?: "Could not save changes.", true) }
                }
            }) { Text("SAVE", color = Accent) }
        },
        dismissButton = { TextButton(onClick = { editorOpen = false }) { Text("CLOSE") } },
        containerColor = Panel
    )
}

@Composable
internal fun ClipboardScreen(host: String, onMessage: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var lastUpdated by remember { mutableStateOf(0.0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(host) {
        while (true) {
            try {
                val data = SciNETApi.get(host, "/api/clipboard")
                val updated = data.optDouble("updated", 0.0)
                if (!dirty && updated != lastUpdated) {
                    text = data.optString("text")
                    lastUpdated = updated
                }
            } catch (_: Exception) { }
            delay(1600)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeading("SYNC", "Shared clipboard", "Text shared between native SciNET and connected web clients.")
        PanelCard {
            SectionHeading("LIVE CLIPBOARD", "Text content")
            OutlinedTextField(value = text, onValueChange = { text = it; dirty = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), minLines = 7, placeholder = { Text("Type or paste text to share…") }, colors = fieldColors())
            PrimaryButton("PUSH TO SHARED CLIPBOARD", enabled = dirty || text.isNotEmpty()) {
                scope.launch {
                    try {
                        val data = SciNETApi.post(host, "/api/clipboard", JSONObject().put("text", text).put("source", "SciNET Android"))
                        dirty = false
                        lastUpdated = System.currentTimeMillis() / 1000.0
                        onMessage("Clipboard shared with connected clients.", false)
                    } catch (e: Exception) { onMessage(e.message ?: "Clipboard sync failed.", true) }
                }
            }
            OutlinedButton(onClick = {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("SciNET", text))
                onMessage("Copied to this phone's clipboard.", false)
            }, modifier = Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) { Text("COPY TO PHONE CLIPBOARD") }
            Text("Android limits background clipboard access. Copy to the phone clipboard only when you tap the button.", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
internal fun ProcessScreen(host: String, onMessage: (String, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var monitoring by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("name") }
    var groupApps by remember { mutableStateOf(true) }
    var processes by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var overview by remember { mutableStateOf(JSONObject()) }
    var pendingForceStop by remember { mutableStateOf<JSONObject?>(null) }

    suspend fun refresh() {
        val data = SciNETApi.get(host, "/api/processes")
        processes = data.optJSONArray("processes").objects()
        overview = data
    }

    LaunchedEffect(monitoring, host) {
        if (monitoring) {
            while (true) {
                try { refresh() } catch (e: Exception) { onMessage(e.message ?: "Could not refresh process list.", true) }
                delay(2000)
            }
        }
    }

    fun startMonitoring() {
        loading = true
        scope.launch {
            try {
                SciNETApi.post(host, "/api/processes/monitor", JSONObject().put("enabled", true))
                monitoring = true
                refresh()
                onMessage("Process monitoring started.", false)
            } catch (e: Exception) { onMessage(e.message ?: "Could not start process monitoring.", true) }
            finally { loading = false }
        }
    }
    fun stopMonitoring() {
        scope.launch {
            try { SciNETApi.post(host, "/api/processes/monitor", JSONObject().put("enabled", false)) }
            catch (_: Exception) { }
            monitoring = false
            processes = emptyList()
            onMessage("Process monitoring stopped.", false)
        }
    }
    fun processAction(proc: JSONObject, action: String) {
        scope.launch {
            try {
                val result = SciNETApi.post(host, "/api/processes/action", JSONObject().put("pid", proc.optInt("pid")).put("action", action))
                onMessage(result.optString("message", "Process action completed."), false)
                refresh()
            } catch (e: Exception) { onMessage(e.message ?: "Process action failed.", true) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeading("PROCESS MANAGER", "Running processes", "On-demand inspection of Windows processes on the host.")
        PanelCard {
            SectionHeading("MONITORING", if (monitoring) "Live snapshot enabled" else "Monitoring is off")
            Text("Process enumeration starts only after you request it. Protected Windows and SciNET processes cannot be terminated.", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
            PrimaryButton(if (loading) "STARTING…" else if (monitoring) "STOP MONITORING" else "DISPLAY CURRENT PROCESSES", !loading) {
                if (monitoring) stopMonitoring() else startMonitoring()
            }
        }
        if (monitoring) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { StatCard("PROCESSES", overview.optInt("total").toString(), "Detected") }
                Box(Modifier.weight(1f)) { StatCard("CPU", overview.optDouble("cpu_percent").toInt().toString() + "%", "Host total") }
            }
            PanelCard {
                SectionHeading("FILTER", "Find a process")
                OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search name, executable, PID…") }, colors = fieldColors())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Group by app", color = Muted, fontSize = 11.sp)
                    Switch(checked = groupApps, onCheckedChange = { groupApps = it }, colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Color(0xFF3A4A2B)))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("name", "cpu", "memory").forEach { value ->
                        FilterChip(selected = sort == value, onClick = { sort = value }, label = { Text(value.uppercase(), fontSize = 9.sp) })
                    }
                }
            }
            val filtered = processes.filter {
                val query = search.trim()
                query.isBlank() || it.optString("name").contains(query, true) ||
                    it.optString("app_name").contains(query, true) || it.optString("exe").contains(query, true) ||
                    it.optInt("pid").toString().contains(query)
            }.sortedWith(when (sort) {
                "cpu" -> compareByDescending<JSONObject> { it.optDouble("cpu_percent") }
                "memory" -> compareByDescending<JSONObject> { it.optLong("memory_bytes") }
                else -> compareBy<JSONObject> { it.optString("app_name", it.optString("name")).lowercase(Locale.getDefault()) }
            })
            val categories = listOf("user" to "USER ACTIVITY", "system" to "WINDOWS SYSTEM ACTIVITY", "background" to "OTHER BACKGROUND ACTIVITY")
            categories.forEach { (category, title) ->
                val group = filtered.filter { it.optString("category") == category }
                PanelCard {
                    SectionHeading(title, group.size.toString() + " processes")
                    if (group.isEmpty()) EmptyText("No matching processes.")
                    val visible = group.take(80)
                    val processBuckets: Map<String, List<JSONObject>> = if (groupApps) {
                        visible.groupBy { it.optString("app_name", it.optString("name")) }
                    } else {
                        visible.associateBy { it.optInt("pid").toString() }.mapValues { listOf(it.value) }
                    }
                    processBuckets.forEach { (appTitle, instances) ->
                        if (groupApps && instances.size > 1) Text(appTitle + " · " + instances.size + " instances", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        instances.forEach { proc ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(proc.optString("app_name", proc.optString("name")), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(proc.optString("name") + " · PID " + proc.optInt("pid"), color = Muted, fontSize = 9.sp)
                            Text("CPU " + proc.optDouble("cpu_percent") + "%  ·  RAM " + formatBytes(proc.optLong("memory_bytes")), color = Muted, fontSize = 9.sp)
                            if (proc.optString("window_title").isNotBlank()) Text(proc.optString("window_title"), color = Muted, fontSize = 9.sp, maxLines = 2)
                            if (!proc.optBoolean("protected")) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { processAction(proc, "minimize") }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("MINIMIZE", fontSize = 8.sp) }
                                    TextButton(onClick = { processAction(proc, "maximize") }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("MAXIMIZE", fontSize = 8.sp) }
                                    TextButton(onClick = { processAction(proc, "stop") }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("END TASK", fontSize = 8.sp, color = Bad) }
                                    TextButton(onClick = { pendingForceStop = proc }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("FORCE", fontSize = 8.sp, color = Bad) }
                                }
                            } else Text("PROTECTED PROCESS", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
                        }
                        HorizontalDivider(color = Edge)
                        }
                    }
                }
            }
            TextButton(onClick = { stopMonitoring() }, modifier = Modifier.fillMaxWidth()) { Text("STOP MONITORING AND CLEAR SNAPSHOT", color = Muted, fontSize = 9.sp) }
        }
    }
    if (pendingForceStop != null) AlertDialog(
        onDismissRequest = { pendingForceStop = null },
        title = { Text("Force stop this process?") },
        text = { Text("This can cause unsaved work to be lost. Only continue if you recognize the process.") },
        confirmButton = { TextButton(onClick = { val proc = pendingForceStop ?: return@TextButton; pendingForceStop = null; processAction(proc, "force-stop") }) { Text("FORCE STOP", color = Bad) } },
        dismissButton = { TextButton(onClick = { pendingForceStop = null }) { Text("CANCEL") } },
        containerColor = Panel
    )
}

private fun isEditableText(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return ext in setOf("txt", "md", "json", "xml", "html", "htm", "css", "js", "py", "kt", "java", "c", "h", "cpp", "yml", "yaml", "ini", "log", "csv", "sql", "gradle", "toml", "properties")
}
private fun fileGlyph(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (ext) {
        "png", "jpg", "jpeg", "gif", "webp", "svg" -> "▧"
        "pdf" -> "PDF"
        "mp3", "wav", "ogg", "flac" -> "♫"
        "zip", "7z", "rar" -> "▦"
        "mp4", "mkv", "mov" -> "▶"
        else -> "FILE"
    }
}
private fun displayName(context: Context, uri: Uri): String {
    var result = "upload.bin"
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) result = cursor.getString(0) ?: result
    }
    return result
}
