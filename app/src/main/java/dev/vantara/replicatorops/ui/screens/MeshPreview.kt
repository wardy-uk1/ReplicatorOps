package dev.vantara.replicatorops.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.slicer.MeshRenderer
import dev.vantara.replicatorops.slicer.Plate
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LocalLcars

/**
 * The Slicer's viewscreen: the plate's model on the Q2C bed, drawn on the phone.
 * Drag to turn and tilt, pinch to zoom, double-tap to frame it again. With nothing
 * loaded it shows a flashing LCARS status ("Awaiting pattern").
 */
@Composable
fun MeshPreview(plate: Plate?, status: String?, busyStatus: Boolean, modifier: Modifier = Modifier) {
    val s = LocalLcars.current
    val renderer = remember(plate) { plate?.let { MeshRenderer(it.triangles) } }
    // A new plate starts framed.
    var turn by remember(plate) { mutableFloatStateOf(-0.6f) }
    var tilt by remember(plate) { mutableFloatStateOf(0.95f) }
    var zoom by remember(plate) { mutableFloatStateOf(1f) }
    val base = 0xFFFFCC66.toInt()

    BoxWithConstraints(modifier.background(Color.Black)) {
        val density = LocalDensity.current
        // Render at up to ~900 px on the long side to keep it quick.
        val wPx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
        val hPx = with(density) { maxHeight.toPx() }.toInt().coerceAtLeast(1)
        val k = (900f / maxOf(wPx, hPx)).coerceAtMost(1f)
        val bw = (wPx * k).toInt().coerceAtLeast(1)
        val bh = (hPx * k).toInt().coerceAtLeast(1)
        val bitmap = remember(bw, bh) { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) }
        val image = remember(renderer, bitmap, turn, tilt, zoom) {
            renderer?.draw(Canvas(bitmap), bw, bh, turn, tilt, zoom, base)
            bitmap.asImageBitmap()
        }
        if (renderer != null) {
            Image(
                image, contentDescription = "3D preview of the plate. Drag to turn, pinch to zoom, double-tap to re-centre.",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(renderer) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            turn += pan.x / 220f
                            tilt = (tilt - pan.y / 260f).coerceIn(0.05f, 1.55f)
                            zoom = (zoom * gestureZoom).coerceIn(0.3f, 8f)
                        }
                    }
                    .pointerInput(renderer) {
                        detectTapGestures(onDoubleTap = { turn = -0.6f; tilt = 0.95f; zoom = 1f })
                    },
            )
        }
        if (status != null) Viewscreen(status, busyStatus, Modifier.align(Alignment.Center))
    }
}

/** Flashing LCARS status in the middle of the viewscreen. */
@Composable
private fun Viewscreen(text: String, busy: Boolean, modifier: Modifier) {
    val s = LocalLcars.current
    val colour = if (busy) s.accent2 else s.frame
    val blink by rememberInfiniteTransition(label = "viewscreen").animateFloat(
        initialValue = 1f, targetValue = 0.15f,
        // On for 60% of the cycle, then off: a hard blink, not a fade.
        animationSpec = infiniteRepeatable(tween(if (busy) 700 else 1400, easing = { f -> if (f < 0.6f) 0f else 1f }), RepeatMode.Restart),
        label = "blink",
    )
    Row(modifier.alpha(blink), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(10.dp).height(28.dp).clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)).background(colour))
        Spacer(Modifier.width(10.dp))
        LcarsLabel(text, color = colour, size = 22.sp, bold = true)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(10.dp).height(28.dp).clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)).background(colour))
    }
}
