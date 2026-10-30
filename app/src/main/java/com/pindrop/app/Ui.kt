package com.pindrop.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF0A0C11)
val PanelBg = Color(0xE60D1118)
val Steel = Color(0xFF3A4457)
val SteelDim = Color(0xFF2A3242)
val Cream = Color(0xFFF3EAD9)
val CreamShade = Color(0xFFE2D2BC)
val Muted = Color(0xFF8A92A3)
val Orange = Color(0xFFFF5A1F)
val OrangeDeep = Color(0xFFE6460E)
val Ink = Color(0xFF14110D)
val RedErr = Color(0xFFFF3B30)

val PixelFont = FontFamily(
    Font(R.font.pixel_regular, FontWeight.Normal),
    Font(R.font.pixel_bold, FontWeight.Bold)
)

enum class Mood { IDLE, BUSY, DONE, ERROR }

/** All text in Pindrop goes through here: pixel font, capital letters. */
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
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        fontSize = size,
        fontFamily = PixelFont,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        letterSpacing = spacing,
        textAlign = align,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        lineHeight = size * 1.35f
    )
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

@Composable
fun PxIcon(rows: List<String>, color: Color = Orange, cell: Dp = 3.dp) {
    Sprite(rows, cell, mapOf('#' to color))
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

/** PIN in cream, DROP in orange. */
@Composable
fun Wordmark(width: Dp, modifier: Modifier = Modifier) {
    val cols = PixelData.WORDMARK[0].length
    Sprite(PixelData.WORDMARK, width / cols, mapOf('c' to Cream, 'o' to Orange), modifier)
}

@Composable
fun StatusPill(text: String, dot: Color) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .clip(shape)
            .background(PanelBg)
            .border(2.dp, Steel, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.width(8.dp))
        Px(text, size = 13.sp, bold = true)
    }
}

@Composable
fun PixelCard(
    title: String,
    icon: List<String>,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PanelBg)
            .border(2.dp, Steel, shape)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PxIcon(icon, Orange, 3.dp)
            Spacer(Modifier.width(12.dp))
            Px(title, size = 15.sp, bold = true)
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
fun DashedBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val sw = 2.dp.toPx()
                drawRoundRect(
                    color = SteelDim,
                    topLeft = Offset(sw / 2, sw / 2),
                    size = Size(size.width - sw, size.height - sw),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(
                        width = sw,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))
                    )
                )
            }
            .padding(14.dp),
        content = content
    )
}

@Composable
fun PxButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: List<String>? = null,
    filled: Boolean = true,
    enabled: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val fg = if (filled) Ink else Cream
    val base = if (filled) {
        Modifier.background(Brush.verticalGradient(listOf(Color(0xFFFF6A2A), OrangeDeep)), shape)
    } else {
        Modifier.background(Color.Transparent, shape).border(2.dp, Steel, shape)
    }
    Box(
        modifier
            .height(if (compact) 40.dp else 54.dp)
            .clip(shape)
            .then(base)
            .clickable(enabled = enabled, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .padding(horizontal = if (compact) 14.dp else 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                PxIcon(icon, fg, if (compact) 2.dp else 3.dp)
                Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
            }
            Px(label, size = if (compact) 13.sp else 15.sp, color = fg, bold = true)
        }
    }
}

/** Chunky progress bar made of little blocks. */
@Composable
fun PixelBar(fraction: Float, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "bar")
    Canvas(
        modifier
            .fillMaxWidth()
            .height(14.dp)
    ) {
        val n = 24
        val gap = 3.dp.toPx()
        val cw = (size.width - gap * (n - 1)) / n
        val filled = (f * n + 0.5f).toInt()
        for (i in 0 until n) {
            drawRect(
                color = if (i < filled) Orange else SteelDim,
                topLeft = Offset(i * (cw + gap), 0f),
                size = Size(cw, size.height)
            )
        }
    }
}

@Composable
fun TagPill(text: String, color: Color = Cream, border: Color = Steel) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .clip(shape)
            .border(2.dp, border, shape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Px(text, size = 11.sp, color = color, bold = true)
    }
}
