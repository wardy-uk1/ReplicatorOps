package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.data.BedMesh
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsReadout
import dev.vantara.replicatorops.ui.lcars.LocalLcars
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Colour for a height: blue (low) → green (level) → orange → red (high). */
private fun heightColour(t: Float): Color {
    val stops = listOf(Color(0xFF3A6EE8), Color(0xFF2BB673), Color(0xFFFFCC66), Color(0xFFDD4444))
    val x = t.coerceIn(0f, 1f) * (stops.size - 1)
    val i = x.toInt().coerceAtMost(stops.size - 2)
    return lerp(stops[i], stops[i + 1], x - i)
}

/**
 * 3D view of the bed mesh: drag sideways to turn it, up and down to tilt.
 * Heights are exaggerated so small differences show up.
 */
@Composable
fun BedMeshView(mesh: BedMesh, modifier: Modifier = Modifier) {
    val s = LocalLcars.current
    var turn by remember { mutableFloatStateOf(-0.6f) }
    var tilt by remember { mutableFloatStateOf(0.9f) }
    val rows = mesh.heights.size
    val cols = mesh.heights.maxOf { it.size }
    val lo = mesh.lowest
    val span = max(mesh.range, 0.01)
    // Symmetric colour scale around 0 so "level" is green when the mesh straddles zero.
    val limit = max(abs(mesh.lowest), abs(mesh.highest)).coerceAtLeast(0.01)

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1.4f)
                .pointerInput(Unit) {
                    detectDragGestures { _, drag ->
                        turn += drag.x / 180f
                        tilt = (tilt - drag.y / 240f).coerceIn(0.25f, 1.45f)
                    }
                }
        ) {
            val cx = size.width / 2
            val cy = size.height / 2
            val scale = size.minDimension * 0.42f
            val zScale = scale * 0.35f / span.toFloat()
            fun project(c: Int, r: Int): Offset {
                // Grid in -1..1, rotated around the vertical axis, then tilted.
                val x = (c / (cols - 1f)) * 2f - 1f
                val y = (r / (rows - 1f)) * 2f - 1f
                val z = ((mesh.heights[r].getOrElse(c) { lo } - lo) * zScale).toFloat()
                val xr = x * cos(turn) - y * sin(turn)
                val yr = x * sin(turn) + y * cos(turn)
                return Offset(cx + xr * scale, cy - yr * scale * cos(tilt) - z * sin(tilt) + scale * 0.12f)
            }
            fun colour(r: Int, c: Int) = heightColour(((mesh.heights[r].getOrElse(c) { 0.0 } / limit + 1) / 2).toFloat())

            // Draw cells back to front so nearer ones cover farther ones.
            val cells = buildList { for (r in 0 until rows - 1) for (c in 0 until cols - 1) add(r to c) }
                .sortedBy { (r, c) -> -(project(c, r).y + project(c + 1, r + 1).y) }
            for ((r, c) in cells) {
                val a = project(c, r); val b = project(c + 1, r); val d = project(c + 1, r + 1); val e = project(c, r + 1)
                val path = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(d.x, d.y); lineTo(e.x, e.y); close() }
                drawPath(path, colour(r, c).copy(alpha = 0.85f))
                drawPath(path, Color.Black.copy(alpha = 0.6f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
            }
        }
        // Colour key
        Row(verticalAlignment = Alignment.CenterVertically) {
            LcarsLabel("%+.2f".format(-limit), color = s.hint, size = 11.sp)
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.weight(1f).height(8.dp).background(
                    Brush.horizontalGradient((0..10).map { heightColour(it / 10f) })
                )
            )
            Spacer(Modifier.width(6.dp))
            LcarsLabel("%+.2f mm".format(limit), color = s.hint, size = 11.sp)
        }
        LcarsLabel("Drag to turn and tilt", color = s.hint, size = 11.sp)
        Spacer(Modifier.height(6.dp))
        LcarsReadout("Profile", mesh.profile)
        LcarsReadout("Variance", "%.3f mm".format(mesh.range), valueColor = when {
            mesh.range < 0.15 -> s.accent2
            mesh.range < 0.3 -> s.accent
            else -> s.alert
        })
        LcarsReadout("Lowest / highest", "%+.3f / %+.3f".format(mesh.lowest, mesh.highest))
        LcarsReadout("Grid", "${cols} × ${rows}")
    }
}
