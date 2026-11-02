package com.pindrop.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

val Bg = Color(0xFF0A0C11)
val PanelBg = Color(0xD911151D)
val Steel = Color(0x26FFFFFF)
val Line = Color(0x1AFFFFFF)
val Line2 = Color(0x2EFFFFFF)
val SteelDim = Color(0xFF2A3242)
val Cream = Color(0xFFF3EAD9)
val CreamShade = Color(0xFFE2D2BC)
val Muted = Color(0xFF8B93A5)
val Orange = Color(0xFFFF8A3C)
val OrangeDeep = Color(0xFFE8651A)
val Ink = Color(0xFF14110D)
val RedErr = Color(0xFFFF453A)
val GreenOk = Color(0xFF34D399)

/** Pixel font. Only the logo and the big PINDROP word use it. Everything else uses the phone's own font. */
val PixelFont = FontFamily(
    Font(R.font.pixel_regular, FontWeight.Normal),
    Font(R.font.pixel_bold, FontWeight.Bold)
)

enum class Mood { IDLE, BUSY, DONE, ERROR }

/** The thin line icons used across the app. */
enum class Ic(val res: Int) {
    LAPTOP(R.drawable.ic_laptop),
    DOC(R.drawable.ic_doc),
    FOLDER(R.drawable.ic_folder),
    SEND(R.drawable.ic_send),
    RECEIVE(R.drawable.ic_receive),
    PHONE(R.drawable.ic_phone),
    QR(R.drawable.ic_qr),
    CHECK(R.drawable.ic_check),
    COPY(R.drawable.ic_copy),
    CHEVRON(R.drawable.ic_chevron),
    LOCK(R.drawable.ic_lock)
}

@Composable
fun IconView(ic: Ic, tint: Color = Orange, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(ic.res),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint)
    )
}

/** Pixel-font text, always in capitals. Only for the logo. */
@Composable
fun Px(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    color: Color = Cream,
    bold: Boolean = false,
    align: TextAlign? = null,
    spacing: TextUnit = 1.sp,
    maxLines: Int = Int.MAX_VALUE
) {
    BasicText(
        text = text.uppercase(),
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontFamily = PixelFont,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = spacing,
            textAlign = align ?: TextAlign.Unspecified,
            lineHeight = size * 1.35f
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines
    )
}

/** Everyday text: clean and readable, in the phone's own font. */
@Composable
fun Body(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    color: Color = Cream,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign? = null,
    spacing: TextUnit = 0.1.sp,
    maxLines: Int = Int.MAX_VALUE
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            letterSpacing = spacing,
            textAlign = align ?: TextAlign.Unspecified,
            lineHeight = size * 1.4f
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines
    )
}

/** Small spaced-out capitals, like the card headings in the design. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Cream) {
    Body(text.uppercase(), modifier, size = 12.sp, color = color, weight = FontWeight.Medium, spacing = 1.6.sp, maxLines = 1)
}

fun DrawScope.drawRows(
    rows: List<String>,
    colors: Map<Char, Color>,
    cell: Float,
    ox: Float = 0f,
    oy: Float = 0f
) {
    rows.forEachIndexed { r, row ->
        var i = 0
        while (i < row.length) {
            val ch = row[i]
            var j = i
            while (j < row.length && row[j] == ch) j++
            val col = colors[ch]
            if (col != null) {
                drawRect(
                    color = col,
                    topLeft = Offset(ox + i * cell, oy + r * cell),
                    size = Size((j - i) * cell + 0.6f, cell + 0.6f)
                )
            }
            i = j
        }
    }
}

@Composable
fun Sprite(rows: List<String>, cell: Dp, colors: Map<Char, Color>, modifier: Modifier = Modifier) {
    val w = rows.maxOf { it.length }
    Canvas(modifier.size(cell * w, cell * rows.size)) {
        drawRows(rows, colors, cell.toPx())
    }
}

/** The little cloud. Bobs up and down, sparkles twinkle, and its face changes. */
@Composable
fun Mascot(mood: Mood, width: Dp = 170.dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "mascot")
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse),
        label = "bob"
    )
    val twinkle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "twinkle"
    )
    val face = when (mood) {
        Mood.IDLE -> PixelData.FACE_IDLE
        Mood.BUSY -> PixelData.FACE_BUSY
        Mood.DONE -> PixelData.FACE_DONE
        Mood.ERROR -> PixelData.FACE_ERROR
    }
    Canvas(
        modifier
            .size(width, width * 38f / 58f)
            .graphicsLayer { translationY = bob * density }
    ) {
        val c = size.width / 58f
        val ox = 8f * c
        val oy = 6f * c
        val sparks = listOf(-5 to 2, 44 to -3, 48 to 8)
        sparks.forEachIndexed { i, (sx, sy) ->
            val a = if ((twinkle + i * 0.33f) % 1f < 0.6f) 1f else 0.15f
            drawRows(PixelData.ICON_PLUS, mapOf('#' to Orange.copy(alpha = a)), c, ox + sx * c, oy + sy * c)
        }
        drawRows(PixelData.CLOUD, mapOf('w' to Cream, 's' to CreamShade), c, ox, oy)
        for (r in PixelData.CHEEKS) {
            drawRect(Orange, Offset(ox + r[0] * c, oy + r[1] * c), Size(r[2] * c + 0.6f, r[3] * c + 0.6f))
        }
        for (r in face) {
            drawRect(Ink, Offset(ox + r[0] * c, oy + r[1] * c), Size(r[2] * c + 0.6f, r[3] * c + 0.6f))
        }
    }
}

/** PIN in cream, DROP in orange, in the pixel font. */
@Composable
fun Wordmark(size: TextUnit = 40.sp, modifier: Modifier = Modifier) {
    BasicText(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = Cream)) { append("PIN") }
            withStyle(SpanStyle(color = OrangeDeep)) { append("DROP") }
        },
        modifier = modifier,
        style = TextStyle(
            fontFamily = PixelFont,
            fontWeight = FontWeight.Bold,
            fontSize = size,
            letterSpacing = size * 0.22f,
            textAlign = TextAlign.Center
        ),
        maxLines = 1
    )
}

/** The small cloud and PINDROP in spaced capitals, top left. */
@Composable
fun AppBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(30.dp, 19.dp)) {
            drawRows(PixelData.CLOUD, mapOf('w' to Cream, 's' to CreamShade), size.width / 40f)
        }
        Spacer(Modifier.width(10.dp))
        Body("PINDROP", size = 13.sp, weight = FontWeight.SemiBold, spacing = 3.sp, maxLines = 1)
    }
}

/** The night-town picture behind everything. Drawn crisp, never smoothed, and it stays put while the page scrolls. */
@Composable
fun SceneBackdrop() {
    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = ImageBitmap.imageResource(R.drawable.pixel_scene),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            alignment = Alignment.BottomCenter,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xB30A0C11), Color(0x4D0A0C11), Color(0x800A0C11))))
        )
    }
}

/** Fades and slides a block in once when the screen first opens. */
@Composable
fun Reveal(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 60L)
        shown = true
    }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(420), label = "reveal")
    Box(
        Modifier.graphicsLayer {
            alpha = a
            translationY = (1f - a) * 28f
        }
    ) { content() }
}

@Composable
fun StatusPill(text: String, dot: Color, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(Color(0x14FFFFFF))
            .border(1.dp, Line2, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.width(8.dp))
        Body(text, size = 13.sp, weight = FontWeight.Medium, spacing = 0.sp, maxLines = 1)
    }
}

/** A dark rounded card with a thin line icon and a small spaced heading. */
@Composable
fun PixelCard(
    title: String,
    icon: Ic,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onHeaderClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PanelBg)
            .border(1.dp, Line, shape)
            .animateContentSize(tween(220))
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onHeaderClick != null) Modifier.clickable(onClick = onHeaderClick) else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconView(icon, Orange, 22.dp)
            Spacer(Modifier.width(12.dp))
            Label(title, Modifier.weight(1f))
            if (trailing != null) trailing()
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** A card whose body folds away when you tap its heading. */
@Composable
fun FoldCard(
    title: String,
    icon: Ic,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(220), label = "chevron")
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PanelBg)
            .border(1.dp, Line, shape)
            .animateContentSize(tween(240))
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconView(icon, Orange, 22.dp)
            Spacer(Modifier.width(12.dp))
            Label(title, Modifier.weight(1f))
            IconView(Ic.CHEVRON, Muted, 20.dp, Modifier.graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(visible = open) {
            Column {
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
}

/** A box with a dashed outline, for "nothing here yet" messages. */
@Composable
fun DashedBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0x08FFFFFF))
            .drawBehind {
                drawRoundRect(
                    color = Color(0x38FFFFFF),
                    cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                )
            }
            .padding(14.dp),
        content = content
    )
}

/** A solid-outline box for text fields. */
@Composable
fun FieldBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0x0DFFFFFF))
            .border(1.dp, Line2, shape)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        content = content
    )
}

@Composable
fun PxButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: Ic? = null,
    filled: Boolean = true,
    enabled: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val fg = if (filled) Ink else Cream
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(90), label = "press")
    val base = if (filled) {
        Modifier.background(Brush.verticalGradient(listOf(Color(0xFFFF9A52), Color(0xFFFF7A2E))), shape)
    } else {
        Modifier.background(Color(0x14FFFFFF), shape).border(1.dp, Line2, shape)
    }
    Box(
        modifier
            .height(if (compact) 40.dp else 52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.4f
            }
            .clip(shape)
            .then(base)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                IconView(icon, fg, if (compact) 18.dp else 20.dp)
                Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
            }
            Body(label, size = if (compact) 13.sp else 15.sp, color = fg, weight = FontWeight.SemiBold, spacing = 0.sp, maxLines = 1)
        }
    }
}

/** Small square button with just an icon, like the copy button next to the address. */
@Composable
fun SquareButton(icon: Ic, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(44.dp)
            .clip(shape)
            .background(Color(0x14FFFFFF), shape)
            .border(1.dp, Line2, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        IconView(icon, Cream, 20.dp)
    }
}

/** Smooth rounded progress bar. */
@Composable
fun PixelBar(fraction: Float, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(160), label = "bar")
    Canvas(
        modifier
            .fillMaxWidth()
            .height(8.dp)
    ) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(color = SteelDim, cornerRadius = r)
        if (f > 0f) {
            drawRoundRect(color = Orange, size = Size(size.width * f, size.height), cornerRadius = r)
        }
    }
}

@Composable
fun TagPill(text: String, color: Color = Cream, border: Color = Steel) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, border, shape)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Body(text, size = 11.sp, color = color, weight = FontWeight.Medium, spacing = 0.3.sp)
    }
}

/** Draws a QR code made by [Qr]. White background and dark squares so cameras can read it. */
@Composable
fun QrView(modules: Array<BooleanArray>, dimension: Dp = 220.dp) {
    Canvas(
        Modifier
            .size(dimension)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
    ) {
        val n = modules.size
        val quiet = 3
        val cell = size.width / (n + quiet * 2)
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (modules[y][x]) {
                    drawRect(
                        color = Ink,
                        topLeft = Offset((x + quiet) * cell, (y + quiet) * cell),
                        size = Size(cell + 0.5f, cell + 0.5f)
                    )
                }
            }
        }
    }
}
