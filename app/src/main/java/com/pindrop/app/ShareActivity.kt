package com.pindrop.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractUris(intent)
        setContent { ShareSheet(uris) { finish() } }
    }
}

@Suppress("DEPRECATION")
fun extractUris(i: Intent): List<Uri> = when (i.action) {
    Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList() ?: emptyList()
    else -> emptyList()
}

@Composable
fun ShareSheet(uris: List<Uri>, onClose: () -> Unit) {
    val ctx = LocalContext.current

    // Places this file can go: the laptop (if its address is saved) and every paired phone.
    val choices = remember {
        val list = mutableListOf<Target>()
        if (Prefs.getAddr(ctx).isNotBlank()) list.add(laptopTarget(ctx))
        Peers.list(ctx).forEach { list.add(it.target()) }
        list.toList()
    }
    var chosen by remember { mutableStateOf<Target?>(if (choices.size == 1) choices[0] else null) }

    var attempt by remember { mutableIntStateOf(0) }
    var doneCount by remember { mutableIntStateOf(0) }
    var currentFraction by remember { mutableFloatStateOf(0f) }
    var currentName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }

    val setupError: String? = when {
        choices.isEmpty() -> "Add your laptop address or pair a phone first. Open the Pindrop app."
        uris.isEmpty() -> "Nothing to send. Share a photo, video or file."
        else -> null
    }

    LaunchedEffect(attempt, chosen) {
        val target = chosen ?: return@LaunchedEffect
        error = null
        finished = false
        doneCount = 0
        currentFraction = 0f
        if (uris.isEmpty()) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                for ((i, uri) in uris.withIndex()) {
                    val info = fileInfo(ctx, uri)
                    currentName = info.name
                    currentFraction = 0f
                    try {
                        Net.upload(target.addr, info, target.key) { sent ->
                            currentFraction = if (info.size > 0) sent.toFloat() / info.size.toFloat() else 0f
                        }
                    } finally {
                        info.release()
                    }
                    doneCount = i + 1
                }
            }
            finished = true
            delay(1600)
            onClose()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = Net.friendly(e, whoName(target))
        }
    }

    val shownError: String? = error ?: setupError
    val picking = chosen == null && shownError == null
    val total = uris.size.coerceAtLeast(1)
    val overall = ((doneCount + currentFraction) / total).coerceIn(0f, 1f)
    val mood = when {
        shownError != null -> Mood.ERROR
        finished -> Mood.DONE
        picking -> Mood.IDLE
        else -> Mood.BUSY
    }
    val title = when {
        shownError != null -> "Couldn't send"
        finished -> "Dropped!"
        picking -> "Send to..."
        else -> "Dropping..."
    }
    val placeName = chosen?.let { whoName(it) } ?: "your laptop"
    val sub = when {
        shownError != null -> shownError
        picking -> if (uris.size == 1) "1 file" else "${uris.size} files"
        finished -> if (uris.size == 1) "Saved in Downloads/Pindrop on $placeName." else "${uris.size} files are on $placeName."
        else -> currentName + if (uris.size > 1) "  (${doneCount + 1} of ${uris.size})" else ""
    }

    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color(0xFF0D1118))
                .border(2.dp, Steel, shape)
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .width(44.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Steel)
            )
            Spacer(Modifier.height(10.dp))
            Mascot(mood, width = 140.dp)
            Spacer(Modifier.height(4.dp))
            Px(
                title,
                size = 22.sp,
                bold = true,
                color = if (shownError != null) RedErr else Cream,
                align = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Px(sub, size = 12.sp, color = Muted, align = TextAlign.Center, maxLines = 3)
            Spacer(Modifier.height(18.dp))

            if (picking) {
                choices.forEach { t ->
                    PxButton(
                        t.name,
                        Modifier.fillMaxWidth(),
                        icon = if (t.key == null) PixelData.ICON_LAPTOP else PixelData.ICON_PHONE
                    ) { chosen = t }
                    Spacer(Modifier.height(10.dp))
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onClose() }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Px("Cancel", size = 13.sp, color = Muted, bold = true)
                }
            } else if (shownError == null) {
                PixelBar(if (finished) 1f else overall)
                if (!finished) {
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onClose() }
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Px("Cancel", size = 13.sp, color = Muted, bold = true)
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (setupError == null) {
                        PxButton("Try again", Modifier.weight(1f)) { attempt++ }
                    }
                    PxButton("Close", Modifier.weight(1f), filled = false) { onClose() }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
