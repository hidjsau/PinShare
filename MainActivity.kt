package com.pinshare.app

import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.iki.elonen.NanoHTTPD
import kotlin.concurrent.thread
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private var server: FileServer? = null
    private var discovery: Discovery? = null
    private var port = DEFAULT_PORT

    private val peers = mutableStateListOf<Peer>()
    private val hasAccess = mutableStateOf(false)
    private val myPin = mutableStateOf("")
    private val myAddr = mutableStateOf("")
    private val serverNote = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The sharing phone must stay awake or Android pauses the network.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("pinshare", MODE_PRIVATE)
        val saved = prefs.getString("pin", null)
        if (saved != null) {
            myPin.value = saved
        } else {
            regenPin()
        }

        startServer()
        setContent { App() }
    }

    override fun onResume() {
        super.onResume()
        hasAccess.value = Environment.isExternalStorageManager()
        myAddr.value = localIp()?.let { "$it:$port" } ?: "not on Wi-Fi"
    }

    override fun onDestroy() {
        discovery?.stop()
        server?.stop()
        super.onDestroy()
    }

    private fun regenPin() {
        val p = Random.nextInt(1000, 10000).toString()
        getSharedPreferences("pinshare", MODE_PRIVATE).edit().putString("pin", p).apply()
        myPin.value = p
    }

    private fun startServer() {
        val name = Build.MODEL
        for (p in listOf(DEFAULT_PORT, 0)) {
            try {
                val s = FileServer(p, { myPin.value }, name)
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                port = s.listeningPort
                break
            } catch (e: Exception) {
                // try next port
            }
        }
        if (server == null) {
            serverNote.value = "Could not start sharing server"
            return
        }
        val d = Discovery(this) { list ->
            peers.clear()
            peers.addAll(list)
        }
        d.start(port, "PinShare-$name")
        discovery = d
    }

    @Composable
    private fun App() {
        MaterialTheme(colorScheme = lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                var pairing by remember { mutableStateOf<Pairing?>(null) }
                var selected by remember { mutableStateOf<Peer?>(null) }
                var manual by remember { mutableStateOf("") }
                var pinIn by remember { mutableStateOf("") }
                var msg by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                val files = remember { mutableStateListOf<RemoteFile>() }
                val status = remember { mutableStateMapOf<String, String>() }

                val chosen = selected?.takeIf { it in peers } ?: peers.singleOrNull()
                val target: Peer? = if (manual.isNotBlank()) {
                    val t = manual.trim()
                    val h = t.substringBefore(':')
                    val pt = t.substringAfter(':', "").toIntOrNull() ?: DEFAULT_PORT
                    Peer(h, h, pt)
                } else chosen

                val p = pairing

                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text("PinShare", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }

                    // This phone's PIN
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("This phone's PIN")
                                Text(
                                    myPin.value,
                                    fontSize = 40.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 8.sp
                                )
                                Text("Give this PIN to the other phone.")
                                Text("My address: ${myAddr.value}")
                                if (serverNote.value.isNotEmpty()) Text(serverNote.value)
                                OutlinedButton(onClick = { regenPin() }) { Text("New PIN") }
                            }
                        }
                    }

                    // Downloads folder access
                    if (!hasAccess.value) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Allow access to the Downloads folder (one time).")
                                    Button(onClick = {
                                        startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                Uri.parse("package:$packageName")
                                            )
                                        )
                                    }) { Text("Allow") }
                                }
                            }
                        }
                    }

                    if (p == null) {
                        // Not paired yet
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Pair with the other phone", fontWeight = FontWeight.Bold)
                                    if (peers.isEmpty()) {
                                        Text("Searching for nearby phones... Both need the app open on the same Wi-Fi.")
                                    }
                                    peers.forEach { peer ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { selected = peer; manual = "" },
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = manual.isBlank() && peer == chosen,
                                                onClick = { selected = peer; manual = "" }
                                            )
                                            Text("${peer.name}  (${peer.host})")
                                        }
                                    }
                                    OutlinedTextField(
                                        value = manual,
                                        onValueChange = { manual = it },
                                        label = { Text("Or type its address (shown on that phone)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = pinIn,
                                        onValueChange = { v -> pinIn = v.filter { it.isDigit() }.take(4) },
                                        label = { Text("Other phone's PIN") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        enabled = target != null && pinIn.length == 4 && !busy,
                                        onClick = {
                                            val t = target ?: return@Button
                                            val pin = pinIn
                                            busy = true
                                            msg = "Connecting..."
                                            thread {
                                                try {
                                                    val n = Api.ping(t.host, t.port, pin)
                                                    files.clear()
                                                    status.clear()
                                                    pairing = Pairing(t.host, t.port, pin, n)
                                                    pinIn = ""
                                                    msg = "Paired with $n"
                                                } catch (e: Exception) {
                                                    msg = e.message ?: "Could not connect"
                                                }
                                                busy = false
                                            }
                                        }
                                    ) { Text("Pair") }
                                    if (msg.isNotEmpty()) Text(msg)
                                }
                            }
                        }
                    } else {
                        // Paired
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Paired with ${p.name}", fontWeight = FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            enabled = !busy,
                                            onClick = {
                                                busy = true
                                                msg = "Loading..."
                                                thread {
                                                    try {
                                                        val l = Api.list(p)
                                                        files.clear()
                                                        files.addAll(l)
                                                        msg = if (l.isEmpty()) "No files in its Downloads" else "${l.size} files"
                                                    } catch (e: Exception) {
                                                        msg = e.message ?: "Failed to load"
                                                    }
                                                    busy = false
                                                }
                                            }
                                        ) { Text("View downloads") }
                                        OutlinedButton(onClick = {
                                            pairing = null
                                            files.clear()
                                            status.clear()
                                            msg = ""
                                        }) { Text("Unpair") }
                                    }
                                    if (msg.isNotEmpty()) Text(msg)
                                }
                            }
                        }
                        items(files, key = { it.name }) { f ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(f.name, maxLines = 2)
                                        Text(
                                            fmt(f.size) + (status[f.name]?.let { "  -  $it" } ?: ""),
                                            fontSize = 12.sp
                                        )
                                    }
                                    Button(
                                        enabled = status[f.name] != "Saving...",
                                        onClick = {
                                            status[f.name] = "Saving..."
                                            thread {
                                                try {
                                                    val saved = Api.download(p, f.name)
                                                    MediaScannerConnection.scanFile(
                                                        this@MainActivity, arrayOf(saved.path), null, null
                                                    )
                                                    status[f.name] = "Saved"
                                                } catch (e: Exception) {
                                                    status[f.name] = "Failed: ${e.message}"
                                                }
                                            }
                                        }
                                    ) { Text("Save") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fmt(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }
}
