package com.pindrop.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HomeScreen() }
    }
}

/** One line in the ACTIVITY list. */
class Transfer(val name: String, val doneLabel: String) {
    var fraction by mutableFloatStateOf(0f)
    var state by mutableStateOf("SENDING")
    var note by mutableStateOf("")
}

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var addr by remember { mutableStateOf(Prefs.getAddr(ctx)) }
    var kind by remember { mutableIntStateOf(0) } // 0 not tested, 1 connected, 2 failed, 3 checking
    var msg by remember { mutableStateOf("") }
    val transfers = remember { mutableStateListOf<Transfer>() }
    val waiting = remember { mutableStateListOf<OutItem>() }
    var checking by remember { mutableStateOf(false) }
    var waitingMsg by remember { mutableStateOf("") }

    val mood = when {
        kind == 2 -> Mood.ERROR
        kind == 3 || checking -> Mood.BUSY
        kind == 1 -> Mood.DONE
        else -> Mood.IDLE
    }

    suspend fun loadWaiting() {
        if (addr.isBlank()) {
            waitingMsg = "Set the laptop address first."
            return
        }
        checking = true
        waitingMsg = ""
        try {
            val items = withContext(Dispatchers.IO) { Net.listOutbox(addr) }.filter { it.status == "waiting" }
            waiting.clear()
            waiting.addAll(items)
            if (items.isEmpty()) waitingMsg = "Nothing waiting."
        } catch (e: Exception) {
            waiting.clear()
            waitingMsg = Net.friendly(e)
        }
        checking = false
    }

    fun sendUri(uri: Uri) {
        scope.launch {
            val info = try {
                withContext(Dispatchers.IO) { fileInfo(ctx, uri) }
            } catch (e: Exception) {
                null
            }
            if (info == null) {
                val bad = Transfer("file", "SENT")
                bad.state = "FAILED"
                bad.note = "Can't read this file."
                transfers.add(0, bad)
                return@launch
            }
            val t = Transfer(info.name, "SENT")
            transfers.add(0, t)
            try {
                withContext(Dispatchers.IO) {
                    Net.upload(addr, info) { sent ->
                        t.fraction = if (info.size > 0) sent.toFloat() / info.size.toFloat() else 0f
                    }
                }
                t.fraction = 1f
                t.state = "SENT"
            } catch (e: Exception) {
                t.state = "FAILED"
                t.note = Net.friendly(e)
            } finally {
                info.release()
            }
        }
    }

    fun saveItem(item: OutItem) {
        scope.launch {
            val t = Transfer(item.name, "SAVED")
            t.state = "SAVING"
            transfers.add(0, t)
            try {
                withContext(Dispatchers.IO) {
                    Net.download(ctx, addr, item) { got ->
                        t.fraction = if (item.size > 0) got.toFloat() / item.size.toFloat() else 0f
                    }
                }
                t.fraction = 1f
                t.state = "SAVED"
                t.note = "In Downloads/Pindrop"
                waiting.remove(item)
            } catch (e: Exception) {
                t.state = "FAILED"
                t.note = Net.friendly(e)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            Prefs.setAddr(ctx, addr)
            uris.forEach { sendUri(it) }
        }
    }

    LaunchedEffect(Unit) {
        if (addr.isNotBlank()) loadWaiting()
    }

    val scene = ImageBitmap.imageResource(R.drawable.pixel_scene)

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        Image(
            bitmap = scene,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            alignment = Alignment.BottomCenter,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            // top row: logo and status
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.pindrop_logo),
                    contentDescription = "Pindrop",
                    modifier = Modifier.size(38.dp)
                )
                when (kind) {
                    1 -> StatusPill("Connected", Orange)
                    2 -> StatusPill("Can't reach", RedErr)
                    3 -> StatusPill("Checking", Muted)
                    else -> StatusPill("Not tested", Muted)
                }
            }

            // hero
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Mascot(mood, width = 190.dp)
                Spacer(Modifier.height(6.dp))
                Wordmark(width = 300.dp)
                Spacer(Modifier.height(10.dp))
                Px("Send files to your laptop", size = 12.sp, color = Muted, spacing = 3.sp, align = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))

            // address
            PixelCard("Your laptop's address", PixelData.ICON_LAPTOP) {
                DashedBox {
                    BasicTextField(
                        value = addr,
                        onValueChange = { addr = it.trim() },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Orange,
                            fontFamily = PixelFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = SolidColor(Orange),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                if (addr.isEmpty()) {
                                    Px("192.168.43.25", size = 22.sp, color = SteelDim, bold = true)
                                }
                                inner()
                            }
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Px(
                    "http://" + (if (addr.isBlank()) "..." else addr) + ":8765",
                    modifier = Modifier.fillMaxWidth(),
                    size = 12.sp,
                    color = Muted,
                    align = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                PxButton(
                    "Save & test",
                    Modifier.fillMaxWidth(),
                    icon = PixelData.ICON_CHECK,
                    enabled = addr.isNotBlank() && kind != 3
                ) {
                    Prefs.setAddr(ctx, addr)
                    kind = 3
                    msg = ""
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Net.ping(addr) }
                        kind = if (ok) 1 else 2
                        msg = if (ok) {
                            "Pindrop found your laptop."
                        } else {
                            "Can't reach the laptop. Is Pindrop open, and are both on the same hotspot?"
                        }
                        if (ok) loadWaiting()
                    }
                }
                if (msg.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Px(msg, size = 12.sp, color = if (kind == 2) RedErr else Cream)
                }
            }
            Spacer(Modifier.height(14.dp))

            // send
            PixelCard("Send to laptop", PixelData.ICON_UP) {
                PxButton(
                    "Choose files",
                    Modifier.fillMaxWidth(),
                    icon = PixelData.ICON_UP,
                    enabled = addr.isNotBlank()
                ) { picker.launch(arrayOf("*/*")) }
                Spacer(Modifier.height(10.dp))
                Px("Or open any photo or file, tap Share, then pick Pindrop.", size = 12.sp, color = Muted)
            }
            Spacer(Modifier.height(14.dp))

            // receive
            PixelCard("From laptop", PixelData.ICON_DOWN) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PxButton(
                        if (checking) "Checking" else "Check",
                        Modifier.weight(1f),
                        filled = false,
                        enabled = !checking
                    ) { scope.launch { loadWaiting() } }
                    if (waiting.size > 1) {
                        PxButton("Save all", Modifier.weight(1f), icon = PixelData.ICON_DOWN) {
                            waiting.toList().forEach { saveItem(it) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (waiting.isEmpty()) {
                    DashedBox {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Px(
                                if (waitingMsg.isEmpty()) "Nothing waiting." else waitingMsg,
                                size = 13.sp,
                                bold = true,
                                align = TextAlign.Center,
                                color = if (waitingMsg.startsWith("Can't")) RedErr else Cream
                            )
                            Spacer(Modifier.height(4.dp))
                            Px(
                                "Queue files on the laptop, then tap Check.",
                                size = 11.sp,
                                color = Muted,
                                align = TextAlign.Center
                            )
                        }
                    }
                } else {
                    waiting.forEach { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PxIcon(PixelData.ICON_DOC, Orange, 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Px(item.name, size = 13.sp, bold = true, maxLines = 1)
                                Px(fmtSize(item.size), size = 11.sp, color = Muted)
                            }
                            Spacer(Modifier.width(8.dp))
                            PxButton("Save", compact = true) { saveItem(item) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // activity
            PixelCard("Activity", PixelData.ICON_DOC) {
                if (transfers.isEmpty()) {
                    DashedBox {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Px("Nothing yet.", size = 13.sp, bold = true, align = TextAlign.Center)
                            Spacer(Modifier.height(4.dp))
                            Px(
                                "Send a photo or file from your phone.",
                                size = 11.sp,
                                color = Muted,
                                align = TextAlign.Center
                            )
                        }
                    }
                } else {
                    transfers.forEach { t -> ActivityRow(t) }
                }
            }
            Spacer(Modifier.height(18.dp))
            Px(
                "Keep Pindrop open on your laptop.",
                modifier = Modifier.fillMaxWidth(),
                size = 11.sp,
                color = Muted,
                align = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
fun ActivityRow(t: Transfer) {
    val busy = t.state == "SENDING" || t.state == "SAVING"
    val failed = t.state == "FAILED"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PxIcon(PixelData.ICON_DOC, if (failed) RedErr else Orange, 2.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Px(t.name, size = 13.sp, bold = true, maxLines = 1)
            if (busy) {
                Spacer(Modifier.height(6.dp))
                PixelBar(t.fraction)
            } else if (t.note.isNotEmpty()) {
                Px(t.note, size = 11.sp, color = if (failed) RedErr else Muted)
            }
        }
        Spacer(Modifier.width(8.dp))
        TagPill(
            t.state,
            color = if (failed) RedErr else if (busy) Muted else Cream,
            border = if (failed) RedErr else Steel
        )
    }
}
