package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.slicer.Geometry
import dev.vantara.replicatorops.slicer.Plate
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsChip
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LocalLcars
import kotlin.math.roundToInt

private const val BED = 270f

/** Colour for filament [i] of [plate] (the 3MF's colour, or an LCARS colour). */
@Composable
private fun filamentColour(plate: Plate, i: Int): Color {
    val s = LocalLcars.current
    return plate.filaments.getOrNull(i)?.color?.let { Color(it) } ?: s.nav[i % s.nav.size]
}

/**
 * Arrange on the bed: a top-down view of the Q2C's bed. Tap an object to pick it, drag to
 * move it; buttons turn, scale, copy and remove it, or arrange/centre everything.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArrangePanel(vm: AppViewModel, plate: Plate, enabled: Boolean) {
    val s = LocalLcars.current
    var selected by remember(plate.number) { mutableStateOf<Int?>(null) }
    val sel = plate.objects.firstOrNull { it.id == selected } ?: plate.objects.singleOrNull()
    // Where the dragged object is while your finger is down (committed on release).
    var drag by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    val colours = plate.objects.associate { o -> o.id to filamentColour(plate, o.parts.firstOrNull()?.filament ?: 0) }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Canvas(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0B0B))
                .pointerInput(plate, enabled) {
                    detectTapGestures { p ->
                        val scale = size.width / BED
                        val bx = p.x / scale; val by = BED - p.y / scale
                        selected = plate.objects.lastOrNull { Geometry.inside(it.worldHull(), bx, by) }?.id
                    }
                }
                .pointerInput(plate, enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { p ->
                            val scale = size.width / BED
                            val bx = p.x / scale; val by = BED - p.y / scale
                            val hit = plate.objects.lastOrNull { Geometry.inside(it.worldHull(), bx, by) }
                            if (hit != null) { selected = hit.id; drag = hit.id to Offset.Zero }
                        },
                        onDrag = { change, amount ->
                            val d = drag ?: return@detectDragGestures
                            change.consume()
                            val scale = size.width / BED
                            drag = d.first to (d.second + Offset(amount.x / scale, -amount.y / scale))
                        },
                        onDragEnd = {
                            drag?.let { (id, off) ->
                                plate.objects.firstOrNull { it.id == id }?.let { o -> vm.moveObject(id, o.x + off.x, o.y + off.y) }
                            }
                            drag = null
                        },
                        onDragCancel = { drag = null },
                    )
                },
        ) {
            val k = size.width / BED
            fun pt(x: Float, y: Float) = Offset(x * k, (BED - y) * k)
            // Bed grid every 30 mm, border, and the front edge marked.
            for (g in 0..9) {
                val v = g * 30f
                drawLine(Color(0xFF262626), pt(v, 0f), pt(v, BED), 1f)
                drawLine(Color(0xFF262626), pt(0f, v), pt(BED, v), 1f)
            }
            drawRect(s.frameAlt, style = Stroke(2.dp.toPx()))
            drawLine(s.accent, pt(0f, 0f), pt(BED, 0f), 4.dp.toPx())
            for (o in plate.objects) {
                val d = drag?.takeIf { it.first == o.id }?.second ?: Offset.Zero
                val h = o.worldHull()
                if (h.size < 6) continue
                val path = Path()
                for (i in 0 until h.size / 2) {
                    val p = pt(h[i * 2] + d.x, h[i * 2 + 1] + d.y)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                val c = colours[o.id] ?: s.accent
                val isSel = o.id == sel?.id
                drawPath(path, c.copy(alpha = if (isSel) 0.85f else 0.5f))
                drawPath(path, if (isSel) Color.White else c, style = Stroke((if (isSel) 2.5f else 1.5f).dp.toPx()))
                // Off the bed: outline in red.
                val b = o.bounds()
                if (b[0] + d.x < 0 || b[1] + d.y < 0 || b[2] + d.x > BED || b[3] + d.y > BED) {
                    drawPath(path, s.alert, style = Stroke(3.dp.toPx()))
                }
            }
        }
    }
    LcarsLabel("Front of the bed at the bottom (orange edge). Tap to pick, drag to move.", color = s.hint, size = 11.sp,
        maxLines = 2, modifier = Modifier.padding(top = 4.dp))

    if (sel != null) {
        Spacer(Modifier.height(6.dp))
        LcarsLabel(sel.name + " · %.0f° · %d%%".format(sel.rotation, (sel.scale * 100).roundToInt()), color = s.accent, size = 15.sp, bold = true)
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LcarsButton("⟲ 15°", color = s.nav[1], height = 34.dp, enabled = enabled) { vm.turnObject(sel.id, 15f) }
            LcarsButton("⟳ 15°", color = s.nav[1], height = 34.dp, enabled = enabled) { vm.turnObject(sel.id, -15f) }
            LcarsButton("90°", color = s.nav[1], height = 34.dp, enabled = enabled) { vm.turnObject(sel.id, 90f) }
            LcarsButton("− 5%", color = s.nav[2], height = 34.dp, enabled = enabled) { vm.scaleObject(sel.id, sel.scale - 0.05f) }
            LcarsButton("+ 5%", color = s.nav[2], height = 34.dp, enabled = enabled) { vm.scaleObject(sel.id, sel.scale + 0.05f) }
            LcarsButton("100%", color = s.nav[2], height = 34.dp, enabled = enabled && sel.scale != 1f) { vm.scaleObject(sel.id, 1f) }
            LcarsButton("Copy", color = s.accent, height = 34.dp, enabled = enabled) { vm.copyObject(sel.id) }
            LcarsButton("Remove", color = s.alert, height = 34.dp, enabled = enabled && plate.objects.size > 1) { vm.deleteObject(sel.id); selected = null }
        }
        // Filament for a one-part object (multi-colour parts keep theirs).
        if (sel.parts.size == 1) {
            val f = sel.parts[0].filament
            LcarsLabel("Filament", color = s.accent2, size = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until maxOf(4, plate.filaments.size)) {
                    FilamentDot(i + 1, filamentColour(plate, i), selected = i == f) { if (enabled) vm.setObjectFilament(sel.id, i) }
                }
            }
        } else {
            LcarsLabel("Multi-colour object: each part keeps the filament from the 3MF.", color = s.hint, size = 11.sp, maxLines = 2,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LcarsButton("Arrange all", color = s.accent, height = 36.dp, enabled = enabled) { vm.arrangeAll() }
        LcarsButton("Centre", color = s.frameAlt, height = 36.dp, enabled = enabled) { vm.centreAll() }
    }
}

@Composable
private fun FilamentDot(number: Int, colour: Color, selected: Boolean, onClick: () -> Unit) {
    val s = LocalLcars.current
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(colour)
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color(0xFF555555), CircleShape)
            .clickable { Chirp.tap(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        LcarsLabel("$number", color = if (colour.luminance() > 0.5f) Color.Black else Color.White, size = 14.sp, bold = true)
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/**
 * Filaments the plate uses, each matched to a QIDI Box slot (closest type and colour);
 * tap a slot to change it. The choice is sent to the printer with the file.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilamentsPanel(vm: AppViewModel, plate: Plate) {
    val s = LocalLcars.current
    val p by vm.printer.collectAsState()
    val choice by vm.slotChoice.collectAsState()
    val box = p.box
    LaunchedEffect(plate, box?.slots) { vm.suggestSlots(plate) }
    val needs = vm.filamentNeeds(plate)
    for (n in needs) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilamentDot(n.tool + 1, filamentColour(plate, n.tool), selected = false) {}
            Spacer(Modifier.width(8.dp))
            LcarsLabel(n.type ?: "PLA", color = s.text, size = 15.sp, modifier = Modifier.width(64.dp))
            if (box == null) {
                LcarsLabel("No QIDI Box connected", color = s.hint, size = 12.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    box.slots.forEach { slot ->
                        val on = choice[n.tool] == slot.index
                        Box(
                            Modifier.size(34.dp).clip(CircleShape)
                                .background(slot.color?.let { Color(it) } ?: Color(0xFF222222))
                                .border(if (on) 3.dp else 1.dp, if (on) Color.White else Color(0xFF555555), CircleShape)
                                .clickable(enabled = slot.loaded) { Chirp.tap(); vm.chooseSlot(n.tool, slot.index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            LcarsLabel(slotName(slot.index), color = if (slot.loaded) Color.Black else s.hint, size = 11.sp, bold = true)
                        }
                    }
                }
            }
        }
    }
    LcarsLabel(
        if (needs.size > 1) "Each filament change cuts, flushes and loads in the Box like QIDI Studio's prints." else
            "Tap a slot to choose where the filament comes from.",
        color = s.hint, size = 11.sp, maxLines = 2,
    )
}

/** Box slot 0-3 → "1A".."1D", 4-7 → "2A".. */
private fun slotName(i: Int) = "${i / 4 + 1}${'A' + i % 4}"
