package com.pindrop.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/** Spoken status of the laptop for the pill at the top. */
private fun stateText(kind: Int): String = when (kind) {
    1 -> "Laptop connected"
    2 -> "Can't reach laptop"
    3 -> "Checking"
    4 -> "Needs access code"
    5 -> "Laptop stopped"
    else -> "Laptop not tested"
}

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
    var code by remember { mutableStateOf(Prefs.getCode(ctx)) }
    var kind by remember { mutableIntStateOf(0) } // 0 not tested, 1 connected, 2 failed, 3 checking, 4 needs code, 5 stopped
    var activityOpen by remember { mutableStateOf(true) }
    var settingsOpen by remember { mutableStateOf(false) }
    var background by remember { mutableStateOf(Prefs.getBackground(ctx)) }
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
    var qrTick by remember { mutableIntStateOf(0) }

    // sending without a shared Wi-Fi
    var hotOn by remember { mutableStateOf(false) }
    var hotQr by remember { mutableStateOf<Array<BooleanArray>?>(null) }
    var hotMsg by remember { mutableStateOf("") }
    val hotQueue = remember { mutableStateListOf<Uri>() }
    var joined by remember { mutableStateOf(false) }

    val mood = when {
        kind == 2 || kind == 4 || kind == 5 -> Mood.ERROR
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
            return if (addr.isBlank()) null else Target("Laptop", addr, null, code)
        }
        return peers.firstOrNull { it.key == selected }?.target()
    }

    val qr: Array<BooleanArray>? = remember(myIp, myPort, qrTick) {
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
            val items = withContext(Dispatchers.IO) { Net.listOutbox(addr, code) }.filter { it.status == "waiting" }
            waiting.clear()
            waiting.addAll(items)
            if (items.isEmpty()) waitingMsg = "Nothing waiting."
        } catch (e: Exception) {
            waiting.clear()
            waitingMsg = Net.friendly(e)
        }
        checking = false
    }

    suspend fun sendOne(uri: Uri, target: Target) {
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
            return
        }
        val t = Transfer(info.name, "SENT")
        t.note = "To " + target.name
        transfers.add(0, t)
        TransferService.hold(ctx)
        try {
            withContext(Dispatchers.IO) {
                Net.upload(target.addr, info, target.key, code = target.code) { sent ->
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
            TransferService.release(ctx)
        }
    }

    fun sendUri(uri: Uri, target: Target) {
        scope.launch { sendOne(uri, target) }
    }

    fun sendMany(uris: List<Uri>, target: Target, whenDone: () -> Unit) {
        scope.launch {
            for (u in uris) sendOne(u, target)
            whenDone()
        }
    }

    fun saveItem(item: OutItem) {
        scope.launch {
            val t = Transfer(item.name, "SAVED")
            t.state = "SAVING"
            transfers.add(0, t)
            TransferService.hold(ctx)
            try {
                withContext(Dispatchers.IO) {
                    Net.download(ctx, addr, code, item) { got ->
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
            } finally {
                TransferService.release(ctx)
            }
        }
    }

    fun stopHot() {
        Hotspot.stop()
        PhoneServer.hotMode = false
        PhoneServer.session = null
        hotOn = false
        hotQr = null
        hotQueue.clear()
    }

    fun beginHot() {
        if (hotQueue.isEmpty()) return
        if (myPort <= 0) {
            hotMsg = "Couldn't start. Tap Refresh in Phones, then try again."
            hotQueue.clear()
            return
        }
        hotMsg = "Starting the temporary Wi-Fi..."
        val before = Hotspot.allIps()
        PhoneServer.session = Secure.newKey() // a throw-away key just for this session
        PhoneServer.hotMode = true
        hotOn = true
        Hotspot.start(
            ctx,
            { ssid, pass, wpa3 ->
                scope.launch {
                    var ip: String? = null
                    var tries = 0
                    while (ip == null && tries < 20) {
                        ip = (Hotspot.allIps() - before).firstOrNull()
                        if (ip == null) {
                            delay(300)
                            tries++
                        }
                    }
                    if (ip == null) {
                        stopHot()
                        hotMsg = "Couldn't find the temporary Wi-Fi address. Try again."
                        return@launch
                    }
                    val code = try {
                        Qr.encode(Hotspot.hotUri(ctx, ip, myPort, ssid, pass, wpa3))
                    } catch (e: Exception) {
                        null
                    }
                    if (code == null) {
                        stopHot()
                        hotMsg = "Couldn't make the code. Try again."
                        return@launch
                    }
                    hotQr = code
                    hotMsg = "Ask the other phone to open Pindrop, tap Scan a code, and point it here."
                }
            },
            { why ->
                scope.launch {
                    stopHot()
                    hotMsg = why
                }
            }
        )
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            beginHot()
        } else {
            hotQueue.clear()
            hotMsg = "Pindrop needs the Location permission to make a temporary Wi-Fi."
        }
    }

    val hotPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            hotQueue.clear()
            hotQueue.addAll(uris)
            val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            val missing = need.filter {
                ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) beginHot() else permLauncher.launch(missing.toTypedArray())
        }
    }

    fun endJoin() {
        Hotspot.leave()
        PhoneServer.session = null
        joined = false
    }

    fun joinHot(hot: HotCode) {
        pairMsg = "Joining " + hot.peer.name + "'s Wi-Fi. Tap Connect if Android asks."
        val mine = Secure.newKey() // this phone's throw-away key for the session
        PhoneServer.session = mine
        Hotspot.join(
            ctx,
            hot,
            {
                scope.launch {
                    delay(800)
                    try {
                        withContext(Dispatchers.IO) {
                            Net.pair(hot.peer, Peers.myName(), myPort, mine)
                        }
                        joined = true
                        pairMsg = "Connected. Waiting for files from " + hot.peer.name + "..."
                    } catch (e: Exception) {
                        endJoin()
                        pairMsg = Net.friendly(e, hot.peer.name)
                    }
                }
            },
            { why ->
                scope.launch {
                    PhoneServer.session = null
                    pairMsg = why
                }
            },
            {
                scope.launch {
                    PhoneServer.session = null
                    joined = false
                    pairMsg = "Disconnected from the temporary Wi-Fi."
                }
            }
        )
    }

    fun addPeerFromCode(raw: String) {
        val hot = Hotspot.parseHot(raw)
        if (hot != null) {
            if (myPort <= 0) {
                pairMsg = "This phone isn't ready yet. Tap Refresh, then try again."
            } else {
                joinHot(hot)
            }
            return
        }
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

    // This phone listens for files from other phones while the app is open.
    DisposableEffect(Unit) {
        PhoneServer.listener = { what, name, size ->
            scope.launch {
                if (what == "pair") {
                    refreshPeers()
                    pairMsg = name + " paired with you."
                } else if (what == "hot") {
                    val p = PhoneServer.lastHot
                    if (p != null && hotQueue.isNotEmpty()) {
                        val files = hotQueue.toList()
                        hotMsg = "Sending to " + p.name + "..."
                        sendMany(files, p.target()) {
                            stopHot()
                            hotMsg = "Done. The temporary Wi-Fi is off."
                        }
                    }
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
            PhoneServer.hotMode = false
            PhoneServer.session = null
            Hotspot.stop()
            Hotspot.leave()
            PhoneServer.stop()
        }
    }

    // While the app is open, keep it alive in the background so files can still arrive with the screen off.
    DisposableEffect(background) {
        if (background) TransferService.hold(ctx)
        onDispose { if (background) TransferService.release(ctx) }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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

    Box(Modifier.fillMaxSize()) {
        SceneBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // top row: small logo and status
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppBadge()
                StatusPill(
                    stateText(kind),
                    when (kind) {
                        1 -> GreenOk
                        2, 4, 5 -> RedErr
                        else -> Muted
                    }
                )
            }
            Spacer(Modifier.height(8.dp))

            // hero
            Reveal(0) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Mascot(mood, width = 128.dp)
                    Spacer(Modifier.height(4.dp))
                    Wordmark(size = 34.sp)
                    Spacer(Modifier.height(8.dp))
                    Body(
                        "Send files to your laptop",
                        size = 14.sp,
                        color = Muted,
                        align = TextAlign.Center,
                        spacing = 1.5.sp
                    )
                }
            }
            Spacer(Modifier.height(22.dp))

            // send
            Reveal(1) {
                PixelCard("Send files", Ic.SEND) {
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
                        icon = Ic.SEND,
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
            }

            if (selected == "laptop") {
                Spacer(Modifier.height(14.dp))

                // address and access code
                Reveal(2) {
                    PixelCard("Your laptop's address", Ic.LAPTOP) {
                        FieldBox {
                            BasicTextField(
                                value = addr,
                                onValueChange = { v ->
                                    if (v.contains("?c=") || v.contains("&c=")) {
                                        val (h, c) = splitLaptopInput(v)
                                        addr = h
                                        if (c.isNotEmpty()) code = c
                                    } else {
                                        addr = v.trim()
                                    }
                                },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = Cream,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 26.sp,
                                    letterSpacing = 2.sp,
                                    textAlign = TextAlign.Center
                                ),
                                cursorBrush = SolidColor(Orange),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        if (addr.isEmpty()) {
                                            Body("192.168.43.25", size = 24.sp, color = Color(0xFF3A4458), weight = FontWeight.SemiBold, spacing = 2.sp)
                                        }
                                        inner()
                                    }
                                }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Body(
                            "http://" + (if (addr.isBlank()) "..." else addr) + ":8765",
                            modifier = Modifier.fillMaxWidth(),
                            size = 12.sp,
                            color = Muted,
                            align = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Label("Access code", color = Muted)
                        Spacer(Modifier.height(6.dp))
                        FieldBox {
                            BasicTextField(
                                value = code,
                                onValueChange = { code = cleanCode(it) },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = Orange,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 22.sp,
                                    letterSpacing = 5.sp,
                                    textAlign = TextAlign.Center
                                ),
                                cursorBrush = SolidColor(Orange),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Characters,
                                    keyboardType = KeyboardType.Ascii
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        if (code.isEmpty()) {
                                            Body("K7MQ2XR9", size = 20.sp, color = Color(0xFF3A4458), weight = FontWeight.SemiBold, spacing = 5.sp)
                                        }
                                        inner()
                                    }
                                }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Body(
                            "Shown in the laptop's Pindrop window. You can also paste the whole link into the address box.",
                            size = 12.sp,
                            color = Muted
                        )
                        Spacer(Modifier.height(14.dp))
                        PxButton(
                            "Save & test",
                            Modifier.fillMaxWidth(),
                            icon = Ic.CHECK,
                            enabled = addr.isNotBlank() && kind != 3
                        ) {
                            Prefs.setAddr(ctx, addr)
                            Prefs.setCode(ctx, code)
                            kind = 3
                            msg = ""
                            scope.launch {
                                val state = withContext(Dispatchers.IO) { Net.pingLaptop(addr, code) }
                                kind = when (state) {
                                    LaptopState.OK -> 1
                                    LaptopState.NEEDS_CODE -> 4
                                    LaptopState.STOPPED -> 5
                                    LaptopState.DOWN -> 2
                                }
                                msg = when (state) {
                                    LaptopState.OK -> "Pindrop found your laptop."
                                    LaptopState.NEEDS_CODE -> "The laptop is there, but the access code is missing or wrong."
                                    LaptopState.STOPPED -> "Pindrop is stopped on the laptop. Press Start there."
                                    LaptopState.DOWN -> "Can't reach the laptop. Is Pindrop open, and are both on the same Wi-Fi or hotspot?"
                                }
                                if (state == LaptopState.OK) loadWaiting()
                            }
                        }
                        if (msg.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Body(msg, size = 13.sp, color = if (kind == 1) Cream else RedErr)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                // receive from laptop
                PixelCard("From laptop", Ic.RECEIVE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PxButton(
                            if (checking) "Checking" else "Check",
                            Modifier.weight(1f),
                            filled = false,
                            enabled = !checking
                        ) { scope.launch { loadWaiting() } }
                        if (waiting.size > 1) {
                            PxButton("Save all", Modifier.weight(1f), icon = Ic.RECEIVE) {
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
                                    color = if (waitingMsg.startsWith("Can't") || waitingMsg.startsWith("The laptop") || waitingMsg.startsWith("Pindrop is")) RedErr else Cream
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
                                IconView(Ic.DOC, Orange, 22.dp)
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

            // no shared Wi-Fi
            PixelCard("No Wi-Fi? Send directly", Ic.QR) {
                if (hotOn) {
                    val q = hotQr
                    if (q != null) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            QrView(q, 200.dp)
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Body(
                        hotMsg,
                        modifier = Modifier.fillMaxWidth(),
                        size = 13.sp,
                        color = Cream,
                        align = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    PxButton("Stop", Modifier.fillMaxWidth(), filled = false) {
                        stopHot()
                        hotMsg = "Stopped."
                    }
                } else {
                    Body(
                        "No shared Wi-Fi or hotspot? Pindrop can make a temporary Wi-Fi. " +
                            "Choose your files, then the other phone scans your code and joins it.",
                        size = 13.sp,
                        color = Muted
                    )
                    Spacer(Modifier.height(6.dp))
                    Body(
                        "Both phones need Pindrop open. Location must be on. The other phone leaves the internet for a moment. " +
                            "The code only works for this one transfer.",
                        size = 12.sp,
                        color = Muted
                    )
                    Spacer(Modifier.height(12.dp))
                    PxButton("Choose files and start", Modifier.fillMaxWidth(), icon = Ic.SEND) {
                        hotMsg = ""
                        hotPicker.launch(arrayOf("*/*"))
                    }
                    if (hotMsg.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Body(
                            hotMsg,
                            size = 13.sp,
                            color = if (hotMsg.startsWith("Couldn't") || hotMsg.startsWith("Turn off") ||
                                hotMsg.startsWith("Pindrop needs")
                            ) RedErr else Cream
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // phones
            PixelCard("Phones", Ic.PHONE) {
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
                    PxButton("Scan a code", Modifier.weight(1f), icon = Ic.QR) {
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
                        icon = Ic.COPY,
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
                    FieldBox {
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
                if (joined) {
                    Spacer(Modifier.height(10.dp))
                    PxButton("Disconnect", Modifier.fillMaxWidth(), filled = false) {
                        endJoin()
                        pairMsg = "Disconnected."
                    }
                }
                Spacer(Modifier.height(16.dp))
                Label("Paired phones", color = Muted)
                Spacer(Modifier.height(8.dp))
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
                            IconView(Ic.PHONE, Orange, 22.dp)
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
            FoldCard("Activity", Ic.DOC, activityOpen, { activityOpen = !activityOpen }) {
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
            Spacer(Modifier.height(14.dp))

            // settings
            FoldCard("Settings", Ic.LOCK, settingsOpen, { settingsOpen = !settingsOpen }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Body("Keep working in the background", size = 14.sp, weight = FontWeight.SemiBold)
                        Body(
                            "Shows a small notification so files keep moving with the screen off.",
                            size = 12.sp,
                            color = Muted
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    PxButton(if (background) "On" else "Off", compact = true, filled = background) {
                        background = !background
                        Prefs.setBackground(ctx, background)
                    }
                }
                Spacer(Modifier.height(16.dp))
                PxButton("Make a new phone code", Modifier.fillMaxWidth(), icon = Ic.LOCK, filled = false) {
                    Prefs.resetKey(ctx)
                    qrTick++
                    pairMsg = "New code made. Phones paired before must scan it again to send to this phone."
                }
                Spacer(Modifier.height(8.dp))
                Body(
                    "Use this if someone else may have seen your code. Old pairings stop working for sending to this phone.",
                    size = 12.sp,
                    color = Muted
                )
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
        IconView(Ic.DOC, if (failed) RedErr else Orange, 22.dp)
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
