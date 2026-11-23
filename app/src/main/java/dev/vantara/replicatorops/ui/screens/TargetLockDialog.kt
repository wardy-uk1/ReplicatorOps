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
import dev.vantara.replicatorops.ui.OutlineState
import dev.vantara.replicatorops.data.Outline
import androidx.compose.runtime.LaunchedEffect
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
    val outlineState by vm.outlines.collectAsState()
    var locked by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val active = p.objects.filter { it.name !in p.excludedObjects }
    // Drop locks on anything already cancelled.
    val targets = locked.filter { n -> active.any { it.name == n } }.toSet()
    val leavesOne = active.size - targets.size >= 1
    LaunchedEffect(p.filename) { vm.loadOutlines() }
    val shapes = (outlineState as? OutlineState.Ready)?.takeIf { it.file == p.filename }?.shapes.orEmpty()
    fun toggle(name: String) {
        if (name in p.excludedObjects) return
        Chirp.tap()
        locked = if (name in locked) locked - name else locked + name
    }

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
                        BedMap(p, targets, shapes, Modifier.fillMaxWidth()) { toggle(it) }
                        when (val o = outlineState) {
                            is OutlineState.Loading -> LcarsLabel("Scanning the pattern for target outlines…", color = s.hint, size = 12.sp, maxLines = 2)
                            is OutlineState.Failed -> LcarsLabel("Outlines unavailable: ${o.message}", color = s.hint, size = 12.sp, maxLines = 3)
                            else -> {}
                        }
                        Spacer(Modifier.size(8.dp))
                        Legend()
                        Spacer(Modifier.size(6.dp))
                        LcarsLabel(
                            "Tap targets on the map or in the list. You can lock more than one.",
                            color = s.hint, size = 13.sp, maxLines = 2,
                        )
                        p.objects.forEach { o -> TargetRow(o, p, o.name in targets) { toggle(o.name) } }
                        if (!leavesOne) {
                            LcarsLabel(
                                "At least one target must keep printing. To stop everything, abort the print instead.",
                                color = s.alert, size = 13.sp, maxLines = 3,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            LcarsButton(
                if (targets.size > 1) "Fire (${targets.size})" else "Fire",
                color = s.alert,
                enabled = targets.isNotEmpty() && leavesOne && p.isBusy,
            ) { confirm = true }
        },
        dismissButton = { LcarsButton("Close", color = s.frameAlt) { onDismiss() } },
    )

    if (confirm && targets.isNotEmpty()) {
        val one = targets.size == 1
        LcarsConfirm(
            title = if (one) "Cancel ${prettyObjectName(targets.first())}?" else "Cancel ${targets.size} objects?",
            text = (if (one) "" else targets.joinToString("\n", postfix = "\n\n") { "• " + prettyObjectName(it) }) +
                "The printer will stop printing ${if (one) "this object" else "these objects"}. The rest of the plate carries on. This can't be undone.",
            confirmLabel = "Fire phasers",
            onConfirm = { confirm = false; vm.excludeObjects(targets); locked = emptySet() },
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
            .clickable(enabled = !gone) { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(if (gone) s.dim else colour)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            LcarsLabel(prettyObjectName(o.name), color = colour, size = 16.sp, maxLines = 1)
            val status = when {
                gone -> "Cancelled"
                selected -> "Locked"
                o.name == p.currentObject -> "Printing now"
                else -> null
            }
            status?.let { LcarsLabel(it, color = colour, size = 11.sp) }
        }
    }
}

/** Top-down bed map with each object's outline. Tap an object to lock on. */
@Composable
private fun BedMap(p: PrinterState, locked: Set<String>, shapes: Map<String, Outline>, modifier: Modifier, onTap: (String) -> Unit) {
    val s = LocalLcars.current
    // Prefer outlines from the print file; Klipper's own polygon is often missing.
    fun hullOf(o: PrintObject) = shapes[o.name]?.hull?.takeIf { it.size >= 3 } ?: o.polygon
    val pts = p.objects.flatMap { hullOf(it) + listOfNotNull(it.center) }
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
            .pointerInput(p.objects, p.excludedObjects, shapes) {
                detectTapGestures { tap ->
                    val sz = Size(size.width.toFloat(), size.height.toFloat())
                    val x = minX + tap.x / sz.width * w
                    val y = maxY - tap.y / sz.height * h
                    val hit = p.objects.firstOrNull { o -> hullOf(o).let { it.size >= 3 && inside(x, y, it) } }
                        ?: p.objects.mapNotNull { o ->
                            val h = hullOf(o)
                            val c = o.center ?: h.takeIf { it.isNotEmpty() }?.let { pts -> pts.sumOf { it.first } / pts.size to pts.sumOf { it.second } / pts.size }
                                ?: return@mapNotNull null
                            o to ((c.first - x) * (c.first - x) + (c.second - y) * (c.second - y))
                        }.filter { it.second < (w * 0.08) * (w * 0.08) }.minByOrNull { it.second }?.first
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
            val isLocked = o.name in locked
            val colour = when {
                gone -> s.dim
                isLocked -> s.alert
                o.name == p.currentObject -> s.accent
                else -> s.accent2
            }
            val hull = hullOf(o)
            if (hull.size >= 3) {
                val path = Path().apply {
                    hull.forEachIndexed { i, (x, y) ->
                        val pt = toScreen(x, y, size)
                        if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                    }
                    close()
                }
                drawPath(path, colour.copy(alpha = if (isLocked) 0.30f else 0.12f))
                if (isLocked || shapes[o.name] == null) drawPath(path, colour, style = Stroke(width = if (isLocked) 4f else 2f))
            }
            // The object's actual first-layer lines, so you can see what you're aiming at.
            shapes[o.name]?.segments?.let { seg ->
                var i = 0
                while (i + 3 < seg.size) {
                    val a = toScreen(seg[i].toDouble(), seg[i + 1].toDouble(), size)
                    val b = toScreen(seg[i + 2].toDouble(), seg[i + 3].toDouble(), size)
                    drawLine(colour.copy(alpha = if (gone) 0.5f else 0.9f), a, b, 1.5f)
                    i += 4
                }
            }
            val c = o.center?.let { (cx, cy) -> toScreen(cx, cy, size) }
                ?: hull.takeIf { it.isNotEmpty() }?.let { h -> toScreen(h.sumOf { it.first } / h.size, h.sumOf { it.second } / h.size, size) }
            if (c != null) {
                if (isLocked) {
                    val r = min(size.width, size.height) * 0.04f
                    drawCircle(s.alert, r, c, style = Stroke(width = 3f))
                    drawLine(s.alert, Offset(c.x - r * 1.6f, c.y), Offset(c.x + r * 1.6f, c.y), 3f)
                    drawLine(s.alert, Offset(c.x, c.y - r * 1.6f), Offset(c.x, c.y + r * 1.6f), 3f)
                } else if (gone) {
                    val r = 10f
                    drawLine(s.alert, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), 3f)
                    drawLine(s.alert, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y - r), 3f)
                } else if (hull.size < 3) {
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
