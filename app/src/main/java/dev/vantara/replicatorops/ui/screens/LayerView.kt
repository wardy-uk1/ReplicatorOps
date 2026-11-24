package dev.vantara.replicatorops.ui.screens

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.slicer.GcodeLayers
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsChip
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LocalLcars
import kotlin.math.roundToInt

private const val BED = 270f

enum class LayerColours { TYPE, FILAMENT }

/** Colour for each kind of line (walls, infill, support…). */
@Composable
fun kindColours(): List<Color> {
    val s = LocalLcars.current
    return listOf(
        s.accent,                 // outer wall
        Color(0xFFFFCC66),        // inner wall
        s.nav[1],                 // skin (top/bottom)
        s.nav[2],                 // infill
        Color(0xFF77AA88),        // support
        Color(0xFF888888),        // skirt / brim
        Color(0xFFFFFFFF),        // ironing
        Color(0xFF666666),        // other
    )
}

/**
 * One layer of a G-code file seen from above on the Q2C's bed. Lines up to [done] are
 * printed (bright); the rest are still to come (dim). The layer below is drawn faintly,
 * and [nozzle] (bed mm) is marked if given.
 */
@Composable
fun LayerCanvas(
    layers: GcodeLayers,
    index: Int,
    modifier: Modifier = Modifier,
    done: Int? = null,
    colours: LayerColours = LayerColours.TYPE,
    toolColours: List<Color> = emptyList(),
    nozzle: Offset? = null,
) {
    val s = LocalLcars.current
    val kinds = kindColours()
    val layer = layers.layers.getOrNull(index)
    val below = layers.layers.getOrNull(index - 1)
    val paint = remember { Paint().apply { isAntiAlias = true; strokeCap = Paint.Cap.ROUND } }
    Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0B0B))) {
        val k = size.width / BED
        // Bed grid and front edge.
        for (g in 0..9) {
            val v = g * 30f * k
            drawLine(Color(0xFF1E1E1E), Offset(v, 0f), Offset(v, size.height), 1f)
            drawLine(Color(0xFF1E1E1E), Offset(0f, v), Offset(size.width, v), 1f)
        }
        drawRect(s.frameAlt, style = Stroke(1.5.dp.toPx()))
        drawLine(s.accent, Offset(0f, size.height), Offset(size.width, size.height), 3.dp.toPx())
        layer ?: return@Canvas
        val lineW = maxOf(1f, 0.42f * k)
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            fun draw(l: GcodeLayers.Layer, from: Int, to: Int, colour: (Int) -> Int, width: Float) {
                if (to <= from) return
                paint.strokeWidth = width
                val pts = FloatArray((to - from) * 4)
                for (i in from until to) {
                    val b = i * 4; val o = (i - from) * 4
                    pts[o] = l.lines[b] * k; pts[o + 1] = (BED - l.lines[b + 1]) * k
                    pts[o + 2] = l.lines[b + 2] * k; pts[o + 3] = (BED - l.lines[b + 3]) * k
                }
                // Draw runs of the same colour in one go.
                var runStart = from
                var runColour = colour(from)
                for (i in from + 1..to) {
                    val col = if (i < to) colour(i) else 0
                    if (i == to || col != runColour) {
                        paint.color = runColour
                        nc.drawLines(pts, (runStart - from) * 4, (i - runStart) * 4, paint)
                        runStart = i; runColour = col
                    }
                }
            }
            val faint = Color(0xFF2A2A2A).toArgb()
            below?.let { b -> draw(b, 0, b.count, { faint }, lineW) }
            fun col(l: GcodeLayers.Layer, i: Int): Int = when (colours) {
                LayerColours.TYPE -> kinds[l.kinds[i].toInt().coerceIn(0, kinds.size - 1)].toArgb()
                LayerColours.FILAMENT -> (toolColours.getOrNull(l.tools[i].toInt()) ?: s.nav[l.tools[i] % s.nav.size]).toArgb()
            }
            val upTo = (done ?: layer.count).coerceIn(0, layer.count)
            draw(layer, 0, upTo, { col(layer, it) }, lineW)
            if (upTo < layer.count) {
                val dim = Color(0xFF444444).toArgb()
                draw(layer, upTo, layer.count, { dim }, lineW)
            }
        }
        nozzle?.let { n ->
            val p = Offset(n.x * k, (BED - n.y) * k)
            drawCircle(Color.White, 5.dp.toPx(), p, style = Stroke(2.dp.toPx()))
            drawCircle(s.alert, 2.5.dp.toPx(), p)
        }
    }
}

/** Layer-by-layer look through a G-code file: slider, step buttons and colour modes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayerPreview(
    layers: GcodeLayers,
    startLayer: Int = 0,
    toolColours: List<Color> = emptyList(),
    /** For a print in progress: the layer it's on and how much of it is done. */
    live: Pair<Int, Int>? = null,
    nozzle: Offset? = null,
) {
    val s = LocalLcars.current
    val n = layers.layers.size
    var index by remember { mutableFloatStateOf(startLayer.coerceIn(0, maxOf(0, n - 1)).toFloat()) }
    var colours by remember { mutableStateOf(if (toolColours.size > 1) LayerColours.FILAMENT else LayerColours.TYPE) }
    val i = index.roundToInt().coerceIn(0, maxOf(0, n - 1))
    val layer = layers.layers.getOrNull(i)
    Column {
        LayerCanvas(
            layers, i, Modifier.fillMaxWidth().aspectRatio(1f),
            done = if (live != null && live.first == i) live.second else if (live != null && i > live.first) 0 else null,
            colours = colours, toolColours = toolColours,
            nozzle = if (live != null && live.first == i) nozzle else null,
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            LcarsLabel("Layer ${i + 1} / $n" + (layer?.let { " · %.2f mm".format(it.z) } ?: ""), color = s.text, size = 15.sp, modifier = Modifier.weight(1f))
            LcarsButton("◀", color = s.nav[1], height = 32.dp) { index = (i - 1).coerceAtLeast(0).toFloat() }
            Spacer(Modifier.width(6.dp))
            LcarsButton("▶", color = s.nav[1], height = 32.dp) { index = (i + 1).coerceAtMost(n - 1).toFloat() }
        }
        if (n > 1) {
            Slider(
                value = index, onValueChange = { index = it }, valueRange = 0f..(n - 1).toFloat(),
                colors = SliderDefaults.colors(thumbColor = s.accent, activeTrackColor = s.accent, inactiveTrackColor = s.dim),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LcarsChip("By type", selected = colours == LayerColours.TYPE) { colours = LayerColours.TYPE }
            LcarsChip("By filament", selected = colours == LayerColours.FILAMENT) { colours = LayerColours.FILAMENT }
            live?.let { (l, _) -> LcarsChip("Now", selected = i == l) { index = l.toFloat() } }
        }
        if (colours == LayerColours.TYPE) {
            val kinds = kindColours()
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf("Outer wall", "Inner wall", "Top/bottom", "Infill", "Support", "Skirt/brim", "Ironing").forEachIndexed { k, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(kinds[k]))
                        Spacer(Modifier.width(4.dp))
                        LcarsLabel(label, color = s.hint, size = 11.sp)
                    }
                }
            }
        }
    }
}

/** Full-size layer preview in a pop-up. */
@Composable
fun LayerPreviewDialog(
    title: String,
    layers: GcodeLayers?,
    loading: String?,
    onDismiss: () -> Unit,
    startLayer: Int = 0,
    toolColours: List<Color> = emptyList(),
    live: Pair<Int, Int>? = null,
    nozzle: Offset? = null,
) {
    val s = LocalLcars.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, s.nav[1], RoundedCornerShape(24.dp)),
        title = { LcarsLabel(title, color = s.nav[1], size = 18.sp, bold = true) },
        text = {
            when {
                layers != null -> LayerPreview(layers, startLayer, toolColours, live, nozzle)
                else -> LcarsLabel(loading ?: "Reading the G-code…", color = s.accent2, size = 14.sp, maxLines = 3)
            }
        },
        confirmButton = { LcarsButton("Close", color = s.frameAlt) { onDismiss() } },
    )
}
