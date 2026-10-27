package com.workofaditya.scinet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val Canvas = Color(0xFF0B0D10)
private val Panel = Color(0xFF14181E)
private val Accent = Color(0xFFB8F36B)
private val Muted = Color(0xFF8D98A7)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Canvas, surface = Panel, primary = Accent,
                onPrimary = Canvas, onBackground = Color(0xFFF2F5F8),
                onSurface = Color(0xFFF2F5F8), secondary = Muted
            )) {
                SciNETApp()
            }
        }
    }
}

@Composable
private fun SciNETApp() {
    var host by remember { mutableStateOf("http://192.168.1.1:8000") }
    var status by remember { mutableStateOf("NOT CONNECTED") }
    var detail by remember { mutableStateOf("Connect to the SciNET server running on your PC's local network.") }
    var connected by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = Canvas) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("SCINET", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                    Text("PC LINK  /  ANDROID", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
                }
                Text("●", color = if (connected) Accent else Muted, fontSize = 20.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text("Your PC.\nIn your hands.", fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
            Text("A native Android client for your local SciNET host.", color = Muted, fontSize = 14.sp)
            Column(
                modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("HOST CONNECTION", color = Accent, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("PC address") },
                    placeholder = { Text("http://192.168.1.25:8000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Button(
                    onClick = {
                        status = "CONNECTING"
                        detail = "Checking the host..."
                        // The actual connection check is wired by the activity through a local HTTP request.
                        SciNETConnection.check(host, onResult = { ok, message ->
                            connected = ok
                            status = if (ok) "CONNECTED" else "CONNECTION FAILED"
                            detail = message
                        })
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Canvas),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("CONNECT TO PC", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
                Text(status, color = if (connected) Accent else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text(detail, color = Muted, fontSize = 13.sp)
            }
            Text("MODULES", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            ModuleRow("01", "System dashboard", "CPU · memory · disk · devices")
            ModuleRow("02", "Remote control", "Touchpad · keyboard · media · power")
            ModuleRow("03", "File manager", "Browse · upload · download · edit")
            ModuleRow("04", "Screen & clipboard", "Live desktop · sync · future controller")
            Spacer(Modifier.weight(1f))
            Text("LOCAL NETWORK ONLY  •  SCI NET LINK", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun ModuleRow(number: String, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(number, color = Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
    }
}

private object SciNETConnection {
    fun check(host: String, onResult: (Boolean, String) -> Unit) {
        // Use a background thread for networking; this callback is delivered on the main thread by the caller.
        Thread {
            var connection: HttpURLConnection? = null
            val result = try {
                val normalized = host.trim().trimEnd('/')
                connection = (URL("$normalized/api/system").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    requestMethod = "GET"
                }
                if (connection.responseCode in 200..299) true to "SciNET host is reachable on your local network."
                else false to "Host responded with HTTP ${connection.responseCode}. Check the address and server."
            } catch (_: Exception) {
                false to "Can't reach the host. Check the PC IP, port 8000, Wi-Fi/hotspot and Windows Firewall."
            } finally {
                connection?.disconnect()
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(result.first, result.second) }
        }.start()
    }
}
