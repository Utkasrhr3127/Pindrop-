package com.pindrop.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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

/** "your laptop" or the phone's name, for messages. */
fun whoName(t: Target): String = if (t.key == null) "your laptop" else t.name

/** A paired phone as a place to send files. */
fun Peer.target(): Target = Target(name, addr, Secure.unb64(key))

@Composable
fun TargetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) Orange else Color(0x0FFFFFFF), shape)
            .border(1.dp, if (selected) Orange else Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Body(
            label,
            size = 13.sp,
            color = if (selected) Ink else Cream,
            weight = FontWeight.SemiBold,
            spacing = 0.sp,
            maxLines = 1
        )
    }
}

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var addr by remember { mutableStateOf(Prefs.getAddr(ctx)) }
    var kind by remember { mutableIntStateOf(0) } // 0 not tested, 1 connected, 2 failed, 3 checking
    var msg by remember { mutableStateOf("") }
    val transfers = remember { mutableStateListOf<Transfer>() }
    val waiting = remember { mutableStateListOf<OutItem>() }
    var checking by remember { mutableStateOf(false) }
    var waitingMsg by remember { mutableStateOf("") }

    // phone-to-phone
    val peers = remember { mutableStateListOf<Peer>().apply { addAll(Peers.list(ctx)) } }
    var selected by remember { mutableStateOf("laptop") } // "laptop" or the key of a paired phone
    var myIp by remember { mutableStateOf<String?>(null) }
    var myPort by remember { mutableIntStateOf(0) }
    var pairMsg by remember { mutableStateOf("") }
    var pasted by remember { mutableStateOf("") }
    var showCode by remember { mutableStateOf(false) }

    val mood = when {
        kind == 2 -> Mood.ERROR
        kind == 3 || checking -> Mood.BUSY
        kind == 1 -> Mood.DONE
        else -> Mood.IDLE
    }

    fun refreshPeers() {
        peers.clear()
        peers.addAll(Peers.list(ctx))
    }

    fun refreshAddress() {
        myPort = PhoneServer.port
        myIp = Peers.localIp()
    }

    fun currentTarget(): Target? {
        if (selected == "laptop") {
            return if (addr.isBlank()) null else Target("Laptop", addr, null)
        }
        return peers.firstOrNull { it.key == selected }?.target()
    }

    // This phone listens for files from other phones while the app is open.
    DisposableEffect(Unit) {
        PhoneServer.listener = { what, name, size ->
            scope.launch {
                if (what == "pair") {
                    refreshPeers()
                    pairMsg = name + " paired with you."
                } else {
                    val t = Transfer(name, "RECEIVED")
                    t.fraction = 1f
                    t.state = "RECEIVED"
                    t.note = "In Downloads/Pindrop (" + fmtSize(size) + ")"
                    transfers.add(0, t)
                }
            }
        }
        PhoneServer.start(ctx)
        refreshAddress()
        onDispose {
            PhoneServer.listener = null
            PhoneServer.stop()
        }
    }

    val qr: Array<BooleanArray>? = remember(myIp, myPort) {
        val ip = myIp
        if (ip == null || myPort <= 0) {
            null
        } else {
            try {
                Qr.encode(Peers.pairingUri(ctx, ip, myPort))
            } catch (e: Exception) {
                null
            }
        }
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

    fun sendUri(uri: Uri, target: Target) {
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
            t.note = "To " + target.name
            transfers.add(0, t)
            try {
                withContext(Dispatchers.IO) {
                    Net.upload(target.addr, info, target.key) { sent ->
                        t.fraction = if (info.size > 0) sent.toFloat() / info.size.toFloat() else 0f
                    }
                }
                t.fraction = 1f
                t.state = "SENT"
            } catch (e: Exception) {
                t.state = "FAILED"
                t.note = Net.friendly(e, whoName(target))
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

    fun addPeerFromCode(raw: String) {
        val p = Peers.parse(raw)
        if (p == null) {
            pairMsg = "That isn't a Pindrop code."
            return
        }
        if (myPort <= 0) {
            pairMsg = "This phone isn't ready yet. Tap Refresh, then try again."
            return
        }
        pairMsg = "Pairing with " + p.name + "..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Net.pair(p, Peers.myName(), myPort, Prefs.getKey(ctx)) }
                Peers.add(ctx, p)
                refreshPeers()
                selected = p.key
                pasted = ""
                pairMsg = "Paired with " + p.name + ". Now choose files to send."
            } catch (e: Exception) {
                pairMsg = Net.friendly(e, p.name)
            }
        }
    }

    fun testPeer(p: Peer) {
        scope.launch {
            pairMsg = "Checking " + p.name + "..."
            val ok = withContext(Dispatchers.IO) { Net.ping(p.addr, Secure.unb64(p.key)) }
            pairMsg = if (ok) {
                p.name + " is ready."
            } else {
                "Can't reach " + p.name + ". Open Pindrop there, or scan its code again."
            }
        }
    }

    fun removePeer(p: Peer) {
        Peers.remove(ctx, p)
        refreshPeers()
        if (selected == p.key) selected = "laptop"
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val target = currentTarget()
            if (target != null) {
                if (target.key == null) Prefs.setAddr(ctx, addr)
                uris.forEach { sendUri(it, target) }
            }
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text != null) addPeerFromCode(text)
    }

    LaunchedEffect(Unit) {
        if (addr.isNotBlank()) loadWaiting()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF151A26), Bg)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
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
                    modifier = Modifier.size(34.dp)
                )
                when (kind) {
                    1 -> StatusPill("Laptop connected", Orange)
                    2 -> StatusPill("Can't reach laptop", RedErr)
                    3 -> StatusPill("Checking", Muted)
                    else -> StatusPill("Laptop not tested", Muted)
                }
            }

            // hero
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Mascot(mood, width = 120.dp)
                Spacer(Modifier.height(6.dp))
                Wordmark(width = 220.dp)
                Spacer(Modifier.height(8.dp))
                Body(
                    "Send files to your laptop or a friend's phone",
                    size = 13.sp,
                    color = Muted,
                    align = TextAlign.Center
                )
            }
            Spacer(Modifier.height(20.dp))

            // send
            PixelCard("Send files", PixelData.ICON_UP) {
                Body("Send to", size = 12.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TargetChip("Laptop", selected == "laptop") { selected = "laptop" }
                    peers.forEach { p ->
                        TargetChip(p.name, selected == p.key) { selected = p.key }
                    }
                }
                Spacer(Modifier.height(14.dp))
                PxButton(
                    "Choose files",
                    Modifier.fillMaxWidth(),
                    icon = PixelData.ICON_UP,
                    enabled = currentTarget() != null
                ) { picker.launch(arrayOf("*/*")) }
                Spacer(Modifier.height(10.dp))
                Body(
                    if (selected == "laptop" && addr.isBlank()) {
                        "Type your laptop's address below first."
                    } else {
                        "Or open any photo or file, tap Share, then pick Pindrop."
                    },
                    size = 12.sp,
                    color = Muted
                )
            }

            if (selected == "laptop") {
                Spacer(Modifier.height(14.dp))

                // address
                PixelCard("Laptop address", PixelData.ICON_LAPTOP) {
                    DashedBox {
                        BasicTextField(
                            value = addr,
                            onValueChange = { addr = it.trim() },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Orange,
                                fontFamily = PixelFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp,
                                textAlign = TextAlign.Center
                            ),
                            cursorBrush = SolidColor(Orange),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    if (addr.isEmpty()) {
                                        Px("192.168.43.25", size = 20.sp, color = SteelDim, bold = true)
                                    }
                                    inner()
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Body(
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
                        Body(msg, size = 13.sp, color = if (kind == 2) RedErr else Cream)
                    }
                }
                Spacer(Modifier.height(14.dp))

                // receive from laptop
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
                                Body(
                                    if (waitingMsg.isEmpty()) "Nothing waiting." else waitingMsg,
                                    size = 14.sp,
                                    weight = FontWeight.SemiBold,
                                    align = TextAlign.Center,
                                    color = if (waitingMsg.startsWith("Can't")) RedErr else Cream
                                )
                                Spacer(Modifier.height(4.dp))
                                Body(
                                    "Queue files on the laptop, then tap Check.",
                                    size = 12.sp,
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
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Body(item.name, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
                                    Body(fmtSize(item.size), size = 12.sp, color = Muted)
                                }
                                Spacer(Modifier.width(8.dp))
                                PxButton("Save", compact = true) { saveItem(item) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // phones
            PixelCard("Phones", PixelData.ICON_PHONE) {
                if (qr != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        QrView(qr, 200.dp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Body(
                        "Your code. On the other phone, open Pindrop and tap Scan a code.",
                        modifier = Modifier.fillMaxWidth(),
                        size = 12.sp,
                        color = Muted,
                        align = TextAlign.Center
                    )
                    Body(
                        (myIp ?: "") + ":" + myPort,
                        modifier = Modifier.fillMaxWidth(),
                        size = 12.sp,
                        color = Muted,
                        align = TextAlign.Center
                    )
                } else {
                    DashedBox {
                        Body(
                            "No Wi-Fi found. Join the same Wi-Fi or hotspot as the other phone, then tap Refresh.",
                            size = 13.sp,
                            color = Muted,
                            align = TextAlign.Center
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PxButton("Scan a code", Modifier.weight(1f), icon = PixelData.ICON_QR) {
                        scanner.launch(
                            ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setPrompt("Point at the code on the other phone")
                                setBeepEnabled(false)
                                setOrientationLocked(false)
                            }
                        )
                    }
                    PxButton("Refresh", Modifier.weight(1f), filled = false) { refreshAddress() }
                }
                Spacer(Modifier.height(6.dp))
                Body(
                    if (showCode) "Hide code options" else "Can't scan? Use a code instead",
                    modifier = Modifier
                        .clickable { showCode = !showCode }
                        .padding(vertical = 8.dp),
                    size = 13.sp,
                    color = Orange,
                    weight = FontWeight.Medium
                )
                if (showCode) {
                    PxButton(
                        "Copy my code",
                        Modifier.fillMaxWidth(),
                        icon = PixelData.ICON_COPY,
                        filled = false,
                        enabled = qr != null
                    ) {
                        val ip = myIp
                        if (ip != null && myPort > 0) {
                            clipboard.setText(AnnotatedString(Peers.pairingUri(ctx, ip, myPort)))
                            pairMsg = "Code copied. Send it only to a phone you trust."
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    DashedBox {
                        BasicTextField(
                            value = pasted,
                            onValueChange = { pasted = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Cream, fontSize = 14.sp),
                            cursorBrush = SolidColor(Orange),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (pasted.isEmpty()) {
                                        Body("Paste the other phone's code here", size = 14.sp, color = Muted)
                                    }
                                    inner()
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    PxButton(
                        "Add this code",
                        Modifier.fillMaxWidth(),
                        filled = false,
                        enabled = pasted.isNotBlank()
                    ) { addPeerFromCode(pasted) }
                }
                if (pairMsg.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Body(pairMsg, size = 13.sp, color = if (pairMsg.startsWith("Can't")) RedErr else Cream)
                }
                Spacer(Modifier.height(16.dp))
                Body("Paired phones", size = 12.sp, color = Muted)
                Spacer(Modifier.height(6.dp))
                if (peers.isEmpty()) {
                    DashedBox {
                        Body(
                            "No phones yet.",
                            modifier = Modifier.fillMaxWidth(),
                            size = 14.sp,
                            weight = FontWeight.SemiBold,
                            align = TextAlign.Center
                        )
                    }
                } else {
                    peers.forEach { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PxIcon(PixelData.ICON_PHONE, Orange, 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Body(p.name, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
                                Body(p.addr, size = 12.sp, color = Muted, maxLines = 1)
                            }
                            Spacer(Modifier.width(8.dp))
                            PxButton("Test", compact = true, filled = false) { testPeer(p) }
                            Spacer(Modifier.width(6.dp))
                            PxButton("Remove", compact = true, filled = false) { removePeer(p) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Body(
                        "If a phone can't be reached, its address may have changed. Scan its code again.",
                        size = 12.sp,
                        color = Muted
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // activity
            PixelCard("Activity", PixelData.ICON_DOC) {
                if (transfers.isEmpty()) {
                    DashedBox {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Body("Nothing yet.", size = 14.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
                            Spacer(Modifier.height(4.dp))
                            Body(
                                "Send a photo or file from your phone.",
                                size = 12.sp,
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
            Body(
                "Keep Pindrop open on the device that is receiving.",
                modifier = Modifier.fillMaxWidth(),
                size = 12.sp,
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
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Body(t.name, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
            if (busy) {
                Spacer(Modifier.height(6.dp))
                PixelBar(t.fraction)
            } else if (t.note.isNotEmpty()) {
                Body(t.note, size = 12.sp, color = if (failed) RedErr else Muted)
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
