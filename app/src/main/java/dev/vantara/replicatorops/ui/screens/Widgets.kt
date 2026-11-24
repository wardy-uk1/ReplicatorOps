package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.data.StoresRepo
import dev.vantara.replicatorops.data.TempSample
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsChip
import dev.vantara.replicatorops.ui.lcars.LcarsFont
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LocalLcars

/** LCARS line drawing of a QIDI Q2C with markers on the nozzle, bed and chamber sensor. */
@Composable
fun PrinterDrawing(modifier: Modifier = Modifier) {
    val s = LocalLcars.current
    Canvas(modifier.size(124.dp, 150.dp).semantics { contentDescription = "Printer drawing" }) {
        val k = size.width / 124f
        scale(k, k, pivot = Offset.Zero) {
            val thick = Stroke(width = 3f)
            val thin = Stroke(width = 2f)
            drawRoundRect(s.frame, Offset(4f, 4f), Size(116f, 134f), CornerRadius(8f), style = thick)
            drawLine(s.frame, Offset(4f, 18f), Offset(120f, 18f), 2f)
            drawRoundRect(s.accent2, Offset(14f, 26f), Size(96f, 88f), CornerRadius(4f), style = thin)
            drawLine(s.frame, Offset(18f, 44f), Offset(106f, 44f), 2f)
            drawRoundRect(s.frame, Offset(52f, 38f), Size(20f, 14f), CornerRadius(2f))
            drawPath(Path().apply { moveTo(58f, 52f); lineTo(66f, 52f); lineTo(62f, 58f); close() }, s.accent)
            drawCircle(s.accent, 4f, Offset(62f, 64f))
            val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))
            drawLine(s.accent2, Offset(24f, 48f), Offset(24f, 104f), 1.5f, pathEffect = dash)
            drawLine(s.accent2, Offset(100f, 48f), Offset(100f, 104f), 1.5f, pathEffect = dash)
            drawRoundRect(s.nav[2], Offset(26f, 96f), Size(72f, 5f), CornerRadius(1f))
            drawCircle(s.nav[2], 4f, Offset(62f, 108f))
            drawCircle(s.accent2, 4f, Offset(96f, 32f))
            drawRoundRect(s.frame, Offset(86f, 120f), Size(26f, 12f), CornerRadius(2f), style = thin)
            drawLine(s.frame, Offset(12f, 138f), Offset(12f, 146f), 4f, cap = StrokeCap.Round)
            drawLine(s.frame, Offset(112f, 138f), Offset(112f, 146f), 4f, cap = StrokeCap.Round)
        }
    }
}

/** Temperature readout next to the drawing, with a dot matching its marker. */
@Composable
fun TempTile(label: String, current: Double?, target: Double?, color: Color) {
    val s = LocalLcars.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            LcarsLabel(label, color = s.accent2, size = 13.sp)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            LcarsLabel(current?.let { "%.0f°".format(it) } ?: "--", color = color, size = 24.sp, bold = true)
            if (target != null && target > 0) {
                LcarsLabel(" / %.0f°".format(target), color = s.text, size = 14.sp)
            }
        }
    }
}

/**
 * Nozzle and bed temperatures over time, 0-300 °C (one sample every 2 s, up to an hour).
 * Shows the last 5 minutes; drag sideways to scroll back, pick 5/15/30/60 min to see more,
 * double-tap to jump back to now. Up-and-down drags still scroll the page.
 */
@Composable
fun TempChart(samples: List<TempSample>, modifier: Modifier = Modifier) {
    val s = LocalLcars.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = s.hint, fontSize = 10.sp, fontFamily = LcarsFont)
    val secondsPerSample = 2f
    // How many samples fit across, and how far back from now the right edge is (0 = following now).
    var window by remember { mutableFloatStateOf(150f) }
    var back by remember { mutableFloatStateOf(0f) }
    var plotWidth by remember { mutableFloatStateOf(1f) }
    val count = samples.size
    fun clampBack(b: Float) = b.coerceIn(0f, (count - window).coerceAtLeast(0f))
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(150.dp)
                .semantics { contentDescription = "Temperature graph. Drag sideways to scroll back in time" }
                .pointerInput(count) {
                    detectHorizontalDragGestures { change, dx ->
                        change.consume()
                        back = clampBack(back + dx / plotWidth * window)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { back = 0f }) }
        ) {
            val left = 30.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val top = 6.dp.toPx()
            plotWidth = size.width - left
            fun y(v: Float) = bottom - (v.coerceIn(0f, 300f) / 300f) * (bottom - top)
            for (v in listOf(0, 100, 200, 300)) {
                drawLine(s.dim, Offset(left, y(v.toFloat())), Offset(size.width, y(v.toFloat())), 1f)
                drawText(measurer, "$v°", Offset(0f, y(v.toFloat()) - 7.dp.toPx()), labelStyle)
            }
            if (count < 2) return@Canvas
            val b = clampBack(back)
            val endIdx = count - 1 - b          // sample at the right edge (fractional)
            val startIdx = endIdx - window      // sample at the left edge
            val px = plotWidth / window
            fun x(i: Int) = left + (i - startIdx) * px
            // Time marks along the bottom.
            val span = window * secondsPerSample
            val stepSec = listOf(30, 60, 120, 300, 600, 900, 1800).firstOrNull { span / it <= 5 } ?: 1800
            var t = (b * secondsPerSample / stepSec).toInt() * stepSec
            while (t <= (b + window) * secondsPerSample) {
                val xx = left + plotWidth - (t / secondsPerSample - b) * px
                if (xx in left..size.width) {
                    drawLine(s.dim, Offset(xx, bottom), Offset(xx, bottom + 4.dp.toPx()), 1f)
                    val label = if (t == 0) "now" else if (t % 60 == 0) "-${t / 60}m" else "-${t / 60}m${t % 60}s"
                    val w = measurer.measure(label, labelStyle).size.width
                    drawText(measurer, label, Offset((xx - w / 2f).coerceIn(left, size.width - w), bottom + 4.dp.toPx()), labelStyle)
                }
                t += stepSec
            }
            val from = maxOf(0, kotlin.math.floor(startIdx).toInt())
            val to = minOf(count - 1, kotlin.math.ceil(endIdx).toInt())
            if (to <= from) return@Canvas
            clipRect(left = left) {
                fun line(pick: (TempSample) -> Float, color: Color) {
                    val path = Path()
                    for (i in from..to) {
                        if (i == from) path.moveTo(x(i), y(pick(samples[i]))) else path.lineTo(x(i), y(pick(samples[i])))
                    }
                    drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
                }
                line({ it.bed }, s.nav[2])
                line({ it.nozzle }, s.accent)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(5, 15, 30, 60).forEach { m ->
                LcarsChip("$m min", selected = kotlin.math.abs(window - m * 30f) < 1f) {
                    window = m * 30f; back = clampBack(back)
                }
                Spacer(Modifier.width(6.dp))
            }
        }
        val mins = (count * secondsPerSample / 60).toInt()
        LcarsLabel(
            (if (back > 0.5f) "Scrolled back · double-tap for now" else "Drag sideways to scroll back") +
                if (mins > 0) " · $mins min recorded" else "",
            color = s.hint, size = 11.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Filament spool icon; an empty slot draws as a dashed ring. */
@Composable
fun SpoolIcon(color: Color?, size: Dp = 40.dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2 - 2f
        if (color == null) {
            drawCircle(Color(0xFF666666), r, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
        } else {
            drawCircle(color, r)
            drawCircle(Color(0xFF666666), r, style = Stroke(2f))
            drawCircle(Color.Black, r * 0.38f)
            drawCircle(Color(0xFF888888), r * 0.38f, style = Stroke(2f))
        }
    }
}

/** Material chips for slot and spool editors. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MaterialPicker(selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StoresRepo.MATERIALS.forEach { m -> LcarsChip(m, selected = selected == m) { onPick(m) } }
    }
}

/** Colour swatches for slot and spool editors. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColourPicker(selected: Long, onPick: (name: String, argb: Long) -> Unit) {
    val s = LocalLcars.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StoresRepo.COLOURS.forEach { (name, argb) ->
            val on = argb == selected
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(if (on) 3.dp else 1.dp, if (on) s.accent else Color(0xFF555555), CircleShape)
                    .clickable { Chirp.tap(); onPick(name, argb) }
                    .semantics { contentDescription = name },
            )
        }
    }
}

/**
 * Pinch to zoom (1–5×), drag to pan while zoomed, double-tap to zoom in on a spot or reset.
 * Used for model thumbnails and the camera view.
 */
@Composable
fun ZoomableBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = boxSize.width * (s - 1f) / 2f
        val maxY = boxSize.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        val target = 2.5f
                        val centre = Offset(boxSize.width / 2f, boxSize.height / 2f)
                        scale = target
                        offset = clamp((centre - tap) * (target - 1f), target)
                    }
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (newScale == 1f) Offset.Zero else clamp(offset + pan, newScale)
                    scale = newScale
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
