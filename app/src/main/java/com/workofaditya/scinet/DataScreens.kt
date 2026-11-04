package com.workofaditya.scinet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL

private data class FileTransferProgress(
    val direction: String,
    val fileName: String,
    val transferredBytes: Long,
    val totalBytes: Long?,
    val fileIndex: Int = 1,
    val fileCount: Int = 1
)

@Composable
internal fun FilesScreen(host: String, peerTarget: JSONObject? = null, onMessage: (String, Boolean) -> Unit) {
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
    var transferProgress by remember { mutableStateOf<FileTransferProgress?>(null) }
    val peerId = peerTarget?.optString("id").orEmpty()
    val peerKey = remember(peerId) {
        if (peerId.isBlank()) "" else context.getSharedPreferences("scinet-trust", Context.MODE_PRIVATE).getString(peerId, "").orEmpty()
    }
    val transferFraction = transferProgress?.totalBytes?.takeIf { it > 0L }?.let { total ->
        (transferProgress!!.transferredBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } ?: 0f
    val animatedTransferFraction by animateFloatAsState(
        targetValue = transferFraction,
        animationSpec = tween(durationMillis = 160)
    )

    suspend fun reload(path: String = folder) {
        loading = true
        try {
            if (peerTarget != null) {
                items = PeerNetworkState.incomingTransfers.values
                    .filter { it.optString("senderId") == peerId }
                    .sortedByDescending { it.optLong("receivedAt") }
                    .map { JSONObject(it.toString()).put("path", it.optString("id")).put("type", "peer-transfer") }
            } else {
                val result = SciNETApi.get(host, "/api/files?path=" + SciNETApi.encode(path))
                items = result.optJSONArray("files").objects()
            }
        } catch (e: Exception) {
            onMessage(e.message ?: "Could not load files.", true)
        } finally { loading = false }
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            var count = 0
            try {
                uris.forEachIndexed { index, uri ->
                    val name = displayName(context, uri)
                    try {
                        if (peerTarget != null) {
                            require(peerKey.isNotBlank()) { "Pair with this device before sending files." }
                            PeerNetworkState.activeTransfers.add(peerId)
                            try {
                                SciNETApi.uploadToPeer(
                                    context = context,
                                    host = "http://" + peerTarget.optString("host") + ":" + peerTarget.optInt("port", 8765),
                                    senderId = PeerNetworkState.localDeviceId,
                                    peerKey = peerKey,
                                    uri = uri,
                                    fileName = name
                                ) { transferred, total ->
                                    withContext(Dispatchers.Main.immediate) {
                                        transferProgress = FileTransferProgress(
                                            direction = "SENDING",
                                            fileName = name,
                                            transferredBytes = transferred,
                                            totalBytes = total,
                                            fileIndex = index + 1,
                                            fileCount = uris.size
                                        )
                                    }
                                }
                            } finally {
                                PeerNetworkState.activeTransfers.remove(peerId)
                            }
                        } else {
                            SciNETApi.upload(context, host, uri, name, folder) { transferred, total ->
                                withContext(Dispatchers.Main.immediate) {
                                    transferProgress = FileTransferProgress(
                                        direction = "UPLOADING",
                                        fileName = name,
                                        transferredBytes = transferred,
                                        totalBytes = total,
                                        fileIndex = index + 1,
                                        fileCount = uris.size
                                    )
                                }
                            }
                        }
                        count++
                    } catch (e: Exception) {
                        onMessage(e.message ?: ("Upload failed: " + name), true)
                    }
                }
                if (count > 0) {
                    onMessage(if (peerTarget != null) "Sent " + count + " file(s) to " + peerTarget.optString("name", "device") + "." else "Uploaded " + count + " file(s).", false)
                    reload()
                }
            } finally {
                transferProgress = null
            }
        }
    }
    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val path = pendingDownload
        if (uri != null && path.isNotBlank()) scope.launch {
            val name = path.substringAfterLast('/').ifBlank { "download" }
            try {
                if (peerTarget != null) {
                    val item = PeerNetworkState.incomingTransfers[path]
                        ?: throw IllegalStateException("This incoming transfer is no longer available.")
                    val stagedFile = java.io.File(java.io.File(context.filesDir, "peer-inbox"), path)
                    if (!stagedFile.isFile) throw IllegalStateException("The incoming file is missing.")
                    val total = stagedFile.length()
                    var copied = 0L
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException("Android could not create the destination file.")
                        java.io.FileInputStream(stagedFile).use { input ->
                            output.use { sink ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    sink.write(buffer, 0, count)
                                    copied += count
                                    withContext(Dispatchers.Main.immediate) {
                                        transferProgress = FileTransferProgress(
                                            direction = "DOWNLOADING",
                                            fileName = name,
                                            transferredBytes = copied,
                                            totalBytes = total
                                        )
                                    }
                                }
                            }
                        }
                    }
                    PeerNetworkState.removeIncoming(context, path)
                    onMessage("Downloaded " + item.optString("name") + " to your chosen location.", false)
                    reload()
                } else {
                    SciNETApi.downloadTo(context, host, path, uri) { transferred, total ->
                        withContext(Dispatchers.Main.immediate) {
                            transferProgress = FileTransferProgress(
                                direction = "DOWNLOADING",
                                fileName = name,
                                transferredBytes = transferred,
                                totalBytes = total
                            )
                        }
                    }
                    onMessage("Download saved.", false)
                }
            } catch (e: Exception) {
                onMessage(e.message ?: "Download failed.", true)
            } finally {
                transferProgress = null
                pendingDownload = ""
            }
        } else {
            pendingDownload = ""
        }
    }

    LaunchedEffect(host, folder, peerId) {
        folder = ""
        reload("")
    }
    LaunchedEffect(peerId) {
        if (peerId.isNotBlank()) {
            while (true) {
                items = PeerNetworkState.incomingTransfers.values
                    .filter { it.optString("senderId") == peerId }
                    .sortedByDescending { it.optLong("receivedAt") }
                    .map { JSONObject(it.toString()).put("path", it.optString("id")).put("type", "peer-transfer") }
                delay(1000)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeading(
            "STORAGE",
            "File manager",
            if (peerTarget != null) "Send files directly to " + peerTarget.optString("name", "this device") + " and download files received from it."
            else if (host == "http://127.0.0.1:8765") "Browse files stored on this phone's SciNET web storage."
            else "Browse and manage files stored on the SciNET PC."
        )
        PanelCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (peerTarget != null) "DIRECT TRANSFER" else "SHARED STORAGE", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (peerTarget != null) "Incoming from " + peerTarget.optString("name", "device")
                        else if (folder.isBlank()) "All files" else folder,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                }
                IconButton(onClick = { scope.launch { reload() } }, enabled = !loading) {
                    Text("↻", fontSize = 20.sp, color = Accent)
                }
            }
            if (peerTarget == null && folder.isNotBlank()) {
                TextButton(onClick = { folder = folder.substringBeforeLast('/', "") }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                    Text("←  UP ONE LEVEL", color = Accent, fontSize = 9.sp, letterSpacing = .8.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f).height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text(if (peerTarget != null) "↑  SEND FILES" else "↑  UPLOAD FILES", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                if (peerTarget == null) {
                    OutlinedButton(
                        onClick = { newFolderName = ""; showNewFolder = true },
                        modifier = Modifier.weight(1f).height(44.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Edge),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("+  NEW FOLDER", fontSize = 10.sp) }
                }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(if (peerTarget != null) "Search incoming transfers…" else "Search files and folders…") },
                colors = fieldColors()
            )
            val visibleItems = items.filter { it.optString("name").contains(search.trim(), ignoreCase = true) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (loading) "SYNCING STORAGE…"
                    else visibleItems.size.toString() + if (peerTarget != null) " INCOMING" else " ITEMS",
                    color = Muted, fontSize = 9.sp, letterSpacing = 1.sp
                )
                if (search.isNotBlank()) Text("FILTERED", color = Accent, fontSize = 8.sp, letterSpacing = 1.sp)
            }
        }

        transferProgress?.let { current ->
            val percent = current.totalBytes?.takeIf { it > 0L }?.let {
                (current.transferredBytes * 100L / it).toInt().coerceIn(0, 100)
            }
            PanelCard {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (current.fileCount > 1) "${current.direction}  ·  ${current.fileIndex}/${current.fileCount}"
                            else current.direction,
                            color = Accent,
                            fontSize = 9.sp,
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            percent?.let { "$it%" } ?: formatBytes(current.transferredBytes),
                            color = Ink,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        current.fileName,
                        color = Ink,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (percent != null) {
                        LinearProgressIndicator(
                            progress = animatedTransferFraction,
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = Accent,
                            trackColor = Edge
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = Accent,
                            trackColor = Edge
                        )
                    }
                    Text(
                        formatBytes(current.transferredBytes) + " / " +
                            (current.totalBytes?.let { formatBytes(it) } ?: "size unknown"),
                        color = Muted,
                        fontSize = 9.sp
                    )
                }
            }
        }

        val visibleItems = items.filter { it.optString("name").contains(search.trim(), ignoreCase = true) }
        if (loading && items.isEmpty()) {
            PanelCard { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
                Text(if (peerTarget != null) "Loading incoming transfers…" else "Loading shared storage…", color = Muted, fontSize = 11.sp)
            } }
        } else if (visibleItems.isEmpty()) {
            PanelCard {
                Text(
                    if (search.isBlank()) {
                        if (peerTarget != null) "No incoming files from this device" else "This folder is empty"
                    } else "No matching files",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (search.isBlank()) {
                        if (peerTarget != null) "Files sent by this device will appear here. Use SEND FILES to transfer in the other direction."
                        else "Upload files or create a folder to get started."
                    } else "Try a different search term.",
                    color = Muted, fontSize = 11.sp
                )
            }
        } else {
            visibleItems.chunked(2).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    rowItems.forEach { item ->
                        Box(Modifier.weight(1f)) {
                            val name = item.optString("name")
                            val path = item.optString("path")
                            val isFolder = item.optString("type") == "folder"
                            val isIncomingPeerFile = item.optString("type") == "peer-transfer"
                            var menuExpanded by remember(path) { mutableStateOf(false) }
                            Column(
                                Modifier.fillMaxWidth()
                                    .aspectRatio(0.94f)
                                    .background(Panel, RoundedCornerShape(11.dp))
                                    .border(1.dp, Edge, RoundedCornerShape(11.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    if (isIncomingPeerFile) {
                                        Box(Modifier.size(48.dp).background(Raised, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                            Text("FILE", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        FileThumbnail(host, path, name, isFolder, Modifier.size(48.dp))
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(name, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 13.sp, softWrap = true)
                                        Text(
                                            if (isFolder) "FOLDER" else formatBytes(item.optLong("size")),
                                            color = Muted, fontSize = 8.sp
                                        )
                                    }
                                    Box {
                                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(28.dp)) {
                                            Text("⋮", color = Muted, fontSize = 19.sp)
                                        }
                                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                            if (isIncomingPeerFile) {
                                                DropdownMenuItem(
                                                    text = { Text("Download") },
                                                    onClick = { menuExpanded = false; pendingDownload = path; downloadLauncher.launch(name) }
                                                )
                                            } else {
                                                DropdownMenuItem(
                                                    text = { Text("Rename") },
                                                    onClick = { menuExpanded = false; renameItem = item; renameName = name }
                                                )
                                                if (!isFolder) DropdownMenuItem(
                                                    text = { Text("Download") },
                                                    onClick = { menuExpanded = false; pendingDownload = path; downloadLauncher.launch(name) }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Delete", color = Bad) },
                                                    onClick = { menuExpanded = false; deleteItem = item }
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                Button(
                                    onClick = {
                                        if (isIncomingPeerFile) {
                                            pendingDownload = path
                                            downloadLauncher.launch(name)
                                        } else if (isFolder) {
                                            folder = path
                                        } else if (isEditableText(name)) {
                                            scope.launch {
                                                try {
                                                    val data = SciNETApi.get(host, "/api/files/content?path=" + SciNETApi.encode(path))
                                                    editPath = path
                                                    editName = name
                                                    editText = data.optString("content")
                                                    originalText = editText
                                                    editorOpen = true
                                                } catch (e: Exception) {
                                                    onMessage(e.message ?: "Could not open text file.", true)
                                                }
                                            }
                                        } else {
                                            val url = SciNETApi.normalizeHost(host) + "/api/files/preview?path=" + SciNETApi.encode(path)
                                            try {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                            } catch (_: Exception) {
                                                pendingDownload = path
                                                downloadLauncher.launch(name)
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(34.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Raised, contentColor = Accent),
                                    shape = RoundedCornerShape(7.dp),
                                    contentPadding = PaddingValues(horizontal = 3.dp)
                                ) {
                                    Text(
                                        if (isIncomingPeerFile) "DOWNLOAD" else if (isFolder) "OPEN" else if (isEditableText(name)) "EDIT" else "PREVIEW",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
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
internal fun ClipboardScreen(host: String, onMessage: (String, Boolean) -> Unit) = LiveClipboardPanel(host, onMessage)

@Composable
internal fun LiveClipboardPanel(host: String, onMessage: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var lastUpdated by remember { mutableStateOf(0.0) }
    var lastSentText by remember { mutableStateOf("") }
    var localEditAt by remember { mutableStateOf(0L) }
    var socketConnected by remember { mutableStateOf(false) }
    var socket by remember { mutableStateOf<WebSocket?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val client = remember { OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build() }

    LaunchedEffect(host) {
        var reconnectDelay = 1000L
        try {
            while (true) {
                val closed = CompletableDeferred<Unit>()
                val base = SciNETApi.normalizeHost(host).trimEnd('/')
                val socketBase = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
                val request = Request.Builder().url("$socketBase/ws/clipboard").build()
                val current = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        scope.launch {
                            socket = webSocket
                            socketConnected = true
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, textMessage: String) {
                        val payload = runCatching { JSONObject(textMessage) }.getOrNull() ?: return
                        scope.launch {
                            val updated = payload.optDouble("updated", 0.0)
                            val incoming = payload.optString("text")
                            if (updated > lastUpdated && incoming != lastSentText) {
                                if (System.currentTimeMillis() - localEditAt > 900) {
                                    lastUpdated = updated
                                    lastSentText = incoming
                                    text = incoming
                                    if (incoming.isNotEmpty()) {
                                        manager.setPrimaryClip(ClipData.newPlainText("SciNET shared clipboard", incoming))
                                    }
                                }
                            } else if (updated > lastUpdated) {
                                lastUpdated = updated
                            }
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        scope.launch {
                            if (socket === webSocket) {
                                socket = null
                                socketConnected = false
                            }
                        }
                        closed.complete(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        scope.launch {
                            if (socket === webSocket) {
                                socket = null
                                socketConnected = false
                            }
                        }
                        closed.complete(Unit)
                    }
                })
                socket = current
                try {
                    closed.await()
                } finally {
                    if (socket === current) socket = null
                    socketConnected = false
                    current.cancel()
                }
                delay(reconnectDelay)
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(15000L)
            }
        } finally {
            socket?.close(1000, "Clipboard screen closed")
            socket = null
            socketConnected = false
        }
    }

    LaunchedEffect(text, socketConnected) {
        delay(180)
        if (socketConnected && text != lastSentText) {
            val outgoing = text.take(20000)
            val payload = JSONObject().put("text", outgoing).put("source", "SciNET Android")
            if (socket?.send(payload.toString()) == true) {
                lastSentText = outgoing
                lastUpdated = System.currentTimeMillis() / 1000.0
            } else {
                socketConnected = false
            }
        }
    }

    DisposableEffect(manager, host) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = manager.primaryClip
            val clipText = if (clip != null && clip.itemCount > 0) runCatching {
                clip.getItemAt(0).coerceToText(context).toString()
            }.getOrDefault("") else ""
            if (clipText.isNotBlank() && clipText != lastSentText) {
                localEditAt = System.currentTimeMillis()
                text = clipText.take(20000)
            }
        }
        manager.addPrimaryClipChangedListener(listener)
        onDispose { manager.removePrimaryClipChangedListener(listener) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionHeading("LIVE CLIPBOARD", "Shared text")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).background(if (socketConnected) Good else Muted, RoundedCornerShape(50)))
                Text(if (socketConnected) "LIVE" else "CONNECTING", color = Muted, fontSize = 8.sp, letterSpacing = .8.sp)
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(20000); localEditAt = System.currentTimeMillis() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 145.dp),
            minLines = 5,
            placeholder = { Text("Type or paste text. It syncs automatically…") },
            colors = fieldColors()
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(7.dp).background(if (socketConnected) Good else Muted, RoundedCornerShape(50)))
            Text(if (socketConnected) "TWO-WAY AUTO-SYNC" else "RECONNECTING TO HOST", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                manager.setPrimaryClip(ClipData.newPlainText("SciNET", text))
                onMessage("Copied to phone clipboard.", false)
            }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("COPY TO PHONE", fontSize = 9.sp, color = Accent) }
        }
        Text("Text typed here and copied on the phone is sent to the PC browser live. Incoming PC text also updates the phone clipboard.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun FileThumbnail(host: String, path: String, name: String, isFolder: Boolean, modifier: Modifier = Modifier) {
    var bitmap by remember(host, path) { mutableStateOf<Bitmap?>(null) }
    val imageFile = !isFolder && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif")
    LaunchedEffect(host, path, imageFile) {
        bitmap = if (imageFile) withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(SciNETApi.normalizeHost(host) + "/api/files/preview?path=" + SciNETApi.encode(path)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", "SciNET Android/0.1")
                }
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null } finally { connection?.disconnect() }
        } else null
    }
    Box(
        modifier.background(Color(0xFF0B0E12), RoundedCornerShape(9.dp))
            .border(1.dp, Edge, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = name, modifier = Modifier.fillMaxSize().padding(2.dp))
        } else {
            Text(
                if (isFolder) "▰" else fileGlyph(name),
                color = if (isFolder) Accent else Ink,
                fontSize = if (isFolder) 23.sp else 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
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
    var expandedProcessGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
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
                    listOf("name", "cpu", "memory", "runtime").forEach { value ->
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
                "runtime" -> compareByDescending<JSONObject> { it.optDouble("created_at") }
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
                        visible.groupBy {
                            it.optString("exe").ifBlank { it.optString("name") }.lowercase(Locale.ROOT)
                        }
                    } else {
                        visible.associateBy { it.optInt("pid").toString() }.mapValues { listOf(it.value) }
                    }
                    processBuckets.forEach { (appKey, instances) ->
                        val representative = instances.firstOrNull { it.optString("window_title").isNotBlank() } ?: instances.first()
                        val titleText = representative.optString("window_title").ifBlank {
                            representative.optString("app_name", representative.optString("name"))
                        }
                        val expanded = !groupApps || appKey in expandedProcessGroups
                        if (groupApps) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(titleText, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                    Text(
                                        instances.size.toString() + " process" + (if (instances.size == 1) "" else "es") + " · " + representative.optString("app_name", representative.optString("name")),
                                        color = Muted, fontSize = 8.sp
                                    )
                                }
                                if (instances.size > 1) {
                                    TextButton(
                                        onClick = {
                                            expandedProcessGroups = if (expanded) expandedProcessGroups - appKey else expandedProcessGroups + appKey
                                        },
                                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
                                    ) { Text(if (expanded) "SHOW LESS" else "SHOW ALL", color = Accent, fontSize = 8.sp) }
                                }
                            }
                        }
                        val shownInstances = if (expanded) instances else listOf(representative)
                        shownInstances.forEach { proc ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (!groupApps) Text(proc.optString("app_name", proc.optString("name")), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
