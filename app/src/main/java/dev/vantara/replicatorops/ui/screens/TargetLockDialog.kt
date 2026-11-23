package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.data.PrintObject
import dev.vantara.replicatorops.data.PrinterState
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsConfirm
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LocalLcars
import dev.vantara.replicatorops.ui.prettyObjectName
import kotlin.math.max
import kotlin.math.min

/**
 * Target lock: cancel one object on a multi-part plate (Klipper EXCLUDE_OBJECT).
 * Shows a top-down bed map you can tap, plus a list of names.
 */
@Composable
fun TargetLockDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val s = LocalLcars.current
    val p by vm.printer.collectAsState()
    var locked by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val active = p.objects.filter { it.name !in p.excludedObjects }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, s.alert, RoundedCornerShape(24.dp)),
        title = { LcarsLabel("Target lock", color = s.alert, size = 20.sp, bold = true) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    p.objects.isEmpty() -> LcarsLabel(
                        "No targets: this file has no object labels. In QIDI Studio or OrcaSlicer, turn on \"Label objects\" and slice again.",
                        color = s.hint, size = 14.sp, maxLines = 5,
                    )
                    else -> {
                        BedMap(p, locked, Modifier.fillMaxWidth()) { name ->
                            if (name !in p.excludedObjects) { Chirp.tap(); locked = name }
                        }
                        Spacer(Modifier.size(8.dp))
                        Legend()
                        Spacer(Modifier.size(6.dp))
                        p.objects.forEach { o -> TargetRow(o, p, locked == o.name) { locked = o.name } }
                        if (active.size <= 1) {
                            LcarsLabel(
                                "Only one target left. To stop it, abort the whole print instead.",
                                color = s.hint, size = 13.sp, maxLines = 3,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            LcarsButton(
                "Fire",
                color = s.alert,
                enabled = locked != null && locked !in p.excludedObjects && active.size > 1 && p.isBusy,
            ) { confirm = true }
        },
        dismissButton = { LcarsButton("Close", color = s.frameAlt) { onDismiss() } },
    )

    val target = locked
    if (confirm && target != null) {
        LcarsConfirm(
            title = "Cancel ${prettyObjectName(target)}?",
            text = "The printer will stop printing this object. The rest of the plate carries on. This can't be undone.",
            confirmLabel = "Fire phasers",
            onConfirm = { confirm = false; vm.excludeObject(target); locked = null },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun Legend() {
    val s = LocalLcars.current
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(s.accent2); LcarsLabel("Target", color = s.hint, size = 12.sp)
            Spacer(Modifier.width(6.dp))
            Dot(s.accent); LcarsLabel("Printing now", color = s.hint, size = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(s.alert); LcarsLabel("Locked", color = s.hint, size = 12.sp)
            Spacer(Modifier.width(6.dp))
            Dot(s.dim); LcarsLabel("Cancelled", color = s.hint, size = 12.sp)
        }
    }
}

@Composable
private fun Dot(c: Color) = Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(c))

@Composable
private fun TargetRow(o: PrintObject, p: PrinterState, selected: Boolean, onClick: () -> Unit) {
    val s = LocalLcars.current
    val gone = o.name in p.excludedObjects
    val colour = when {
        gone -> s.hint
        selected -> s.alert
        o.name == p.currentObject -> s.accent
        else -> s.accent2
    }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(50))
            .border(2.dp, if (selected) s.alert else Color.Transparent, RoundedCornerShape(50))
            .clickable(enabled = !gone) { Chirp.tap(); onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(if (gone) s.dim else colour)
        Spacer(Modifier.width(8.dp))
        LcarsLabel(prettyObjectName(o.name), color = colour, size = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
        LcarsLabel(
            when {
                gone -> "Cancelled"
                o.name == p.currentObject -> "Printing"
                selected -> "Locked"
                else -> ""
            },
            color = colour, size = 13.sp,
        )
    }
}

/** Top-down bed map with each object's outline. Tap an object to lock on. */
@Composable
private fun BedMap(p: PrinterState, locked: String?, modifier: Modifier, onTap: (String) -> Unit) {
    val s = LocalLcars.current
    val pts = p.objects.flatMap { it.polygon + listOfNotNull(it.center) }
    // Bed limits from Klipper; fall back to the objects' own extent.
    val minX = p.bedMin?.first ?: (pts.minOfOrNull { it.first } ?: 0.0) - 10
    val minY = p.bedMin?.second ?: (pts.minOfOrNull { it.second } ?: 0.0) - 10
    val maxX = p.bedMax?.first ?: (pts.maxOfOrNull { it.first } ?: 100.0) + 10
    val maxY = p.bedMax?.second ?: (pts.maxOfOrNull { it.second } ?: 100.0) + 10
    val w = max(1.0, maxX - minX)
    val h = max(1.0, maxY - minY)

    fun toScreen(x: Double, y: Double, size: Size) =
        Offset(((x - minX) / w * size.width).toFloat(), ((maxY - y) / h * size.height).toFloat())

    Canvas(
        modifier
            .aspectRatio((w / h).toFloat().coerceIn(0.5f, 2f))
            .pointerInput(p.objects, p.excludedObjects) {
                detectTapGestures { tap ->
                    val sz = Size(size.width.toFloat(), size.height.toFloat())
                    val x = minX + tap.x / sz.width * w
                    val y = maxY - tap.y / sz.height * h
                    val hit = p.objects.firstOrNull { it.polygon.size >= 3 && inside(x, y, it.polygon) }
                        ?: p.objects.minByOrNull { o ->
                            val c = o.center ?: o.polygon.firstOrNull() ?: return@minByOrNull Double.MAX_VALUE
                            (c.first - x) * (c.first - x) + (c.second - y) * (c.second - y)
                        }
                    hit?.let { onTap(it.name) }
                }
            }
    ) {
        // Bed plate with a 50 mm grid.
        drawRect(s.dim.copy(alpha = 0.35f))
        var gx = (kotlin.math.ceil(minX / 50) * 50)
        while (gx < maxX) {
            val a = toScreen(gx, minY, size); drawLine(s.dim, Offset(a.x, 0f), Offset(a.x, size.height), 1f); gx += 50
        }
        var gy = (kotlin.math.ceil(minY / 50) * 50)
        while (gy < maxY) {
            val a = toScreen(minX, gy, size); drawLine(s.dim, Offset(0f, a.y), Offset(size.width, a.y), 1f); gy += 50
        }
        drawRect(s.frame, style = Stroke(width = 3f))

        p.objects.forEach { o ->
            val gone = o.name in p.excludedObjects
            val colour = when {
                gone -> s.dim
                o.name == locked -> s.alert
                o.name == p.currentObject -> s.accent
                else -> s.accent2
            }
            if (o.polygon.size >= 3) {
                val path = Path().apply {
                    o.polygon.forEachIndexed { i, (x, y) ->
                        val pt = toScreen(x, y, size)
                        if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                    }
                    close()
                }
                drawPath(path, colour.copy(alpha = if (gone) 0.5f else 0.35f))
                drawPath(path, colour, style = Stroke(width = if (o.name == locked) 5f else 3f))
            }
            o.center?.let { (cx, cy) ->
                val c = toScreen(cx, cy, size)
                if (o.name == locked) {
                    // Targeting reticle.
                    val r = min(size.width, size.height) * 0.06f
                    drawCircle(s.alert, r, c, style = Stroke(width = 3f))
                    drawLine(s.alert, Offset(c.x - r * 1.6f, c.y), Offset(c.x + r * 1.6f, c.y), 3f)
                    drawLine(s.alert, Offset(c.x, c.y - r * 1.6f), Offset(c.x, c.y + r * 1.6f), 3f)
                } else if (gone) {
                    val r = 10f
                    drawLine(s.alert, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), 3f)
                    drawLine(s.alert, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y - r), 3f)
                } else {
                    drawCircle(colour, 6f, c)
                }
            }
        }
    }
}

/** Ray-casting point-in-polygon test. */
private fun inside(x: Double, y: Double, poly: List<Pair<Double, Double>>): Boolean {
    var hit = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val (xi, yi) = poly[i]
        val (xj, yj) = poly[j]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) hit = !hit
        j = i
    }
    return hit
}
