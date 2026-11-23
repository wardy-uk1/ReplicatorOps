package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.data.FileMeta
import dev.vantara.replicatorops.data.FilamentMapping
import dev.vantara.replicatorops.data.GcodeFile
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LocalLcars

/**
 * Filament check before Replicate: pick which QIDI Box slot feeds each filament
 * in the file (as QIDI Studio does), and warn about empty or mismatched slots.
 */
@Composable
fun MappingDialog(vm: AppViewModel, file: GcodeFile, meta: FileMeta?, onDismiss: () -> Unit) {
    val s = LocalLcars.current
    val p by vm.printer.collectAsState()
    val box = p.box ?: return
    val needs = remember(meta) { FilamentMapping.needs(meta) }
    var map by remember(meta) { mutableStateOf(FilamentMapping.suggest(needs, box.slots, box.toolMap)) }
    val spools by vm.spools.collectAsState()
    var choosingFor by remember { mutableStateOf<Int?>(null) }
    val problems = FilamentMapping.problems(needs, box.slots, map)
    val notes = FilamentMapping.notes(needs, box.slots, map)
    // Spools in Replicator stores that are linked to a Box slot.
    fun spoolIn(slot: Int) = spools.firstOrNull { it.slot == slot }
    val blocking = needs.any { n -> map[n.tool]?.let { s -> box.slots.getOrNull(s)?.loaded } != true }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, s.accent, RoundedCornerShape(24.dp)),
        title = { LcarsLabel("Filament check", color = s.accent, size = 20.sp, bold = true) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LcarsLabel(file.name.substringBeforeLast('.'), color = s.accent2, size = 14.sp)
                Spacer(Modifier.height(4.dp))
                LcarsLabel(
                    "Choose the slot or spool for each filament. Any filament can be used, even a different type or colour from the file.",
                    color = s.hint, size = 13.sp, maxLines = 3,
                )
                needs.forEach { n ->
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Swatch(n.color)
                        Spacer(Modifier.width(8.dp))
                        LcarsLabel("Filament ${n.tool + 1} · ${n.type ?: "unknown"}", color = s.text, size = 16.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        box.slots.forEach { slot ->
                            val chosen = map[n.tool] == slot.index
                            Column(
                                Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(2.dp, if (chosen) s.accent else s.dim, RoundedCornerShape(12.dp))
                                    .alpha(if (slot.loaded) 1f else 0.4f)
                                    .clickable(enabled = slot.loaded) { Chirp.tap(); map = map + (n.tool to slot.index) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                LcarsLabel("${slot.index + 1}", color = if (chosen) s.accent else s.hint, size = 12.sp)
                                Swatch(if (slot.loaded) slot.color else null)
                                LcarsLabel(if (slot.loaded) slot.material ?: "?" else "Empty", color = s.hint, size = 10.sp)
                                spoolIn(slot.index)?.let { sp -> LcarsLabel("%.0f g".format(sp.remainingGrams), color = s.accent2, size = 10.sp) }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    val chosen = map[n.tool]?.let { spoolIn(it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LcarsLabel(
                            chosen?.let { "${it.material} ${it.colourName} · %.0f g left".format(it.remainingGrams) } ?: "No spool linked to this slot",
                            color = s.accent2, size = 12.sp, modifier = Modifier.weight(1f),
                        )
                        LcarsButton("Spools", color = s.nav[3], height = 30.dp) { choosingFor = n.tool }
                    }
                    // Grams check for single-filament files (the slicer only gives a total).
                    if (needs.size == 1 && chosen != null && meta?.filamentGrams != null && chosen.remainingGrams < meta.filamentGrams) {
                        LcarsLabel(
                            "⚠ Needs %.0f g, spool has %.0f g left".format(meta.filamentGrams, chosen.remainingGrams),
                            color = s.alert, size = 12.sp,
                        )
                    }
                }
                if (notes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    notes.forEach { LcarsLabel(it, color = s.accent, size = 13.sp, maxLines = 3) }
                }
                if (problems.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    problems.forEach { LcarsLabel("⚠ $it", color = s.alert, size = 13.sp, maxLines = 2) }
                }
            }
        },
        confirmButton = {
            LcarsButton("Replicate", color = s.accent, enabled = !blocking, sound = Chirp::confirm) {
                vm.startPrintMapped(file.path, map); onDismiss()
            }
        },
        dismissButton = { LcarsButton("Cancel", color = s.frameAlt) { onDismiss() } },
    )

    choosingFor?.let { tool ->
        AlertDialog(
            onDismissRequest = { choosingFor = null },
            containerColor = Color.Black,
            modifier = Modifier.border(2.dp, s.nav[3], RoundedCornerShape(24.dp)),
            title = { LcarsLabel("Replicator stores · filament ${tool + 1}", color = s.nav[3], size = 18.sp, bold = true) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (spools.isEmpty()) LcarsLabel("No spools in Replicator stores yet (Control > Replicator stores).", color = s.hint, size = 13.sp, maxLines = 3)
                    spools.sortedBy { it.slot ?: 99 }.forEach { sp ->
                        val slot = sp.slot?.let { box.slots.getOrNull(it) }
                        val usable = slot?.loaded == true
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = usable) { Chirp.tap(); map = map + (tool to slot!!.index); choosingFor = null }
                                .alpha(if (usable) 1f else 0.45f)
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Swatch(sp.color)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                LcarsLabel("${sp.material} ${sp.colourName}".trim(), color = s.text, size = 15.sp)
                                LcarsLabel(
                                    (if (usable) "Box slot ${slot!!.index + 1}" else "Not loaded in the Box") + " · %.0f g left".format(sp.remainingGrams),
                                    color = if (usable) s.accent2 else s.hint, size = 12.sp,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LcarsLabel("To use a spool that isn't in the Box, load it into a slot and set it in Edit slot first.", color = s.hint, size = 12.sp, maxLines = 3)
                }
            },
            confirmButton = { LcarsButton("Close", color = s.frameAlt) { choosingFor = null } },
        )
    }
}

@Composable
private fun Swatch(color: Long?) {
    val s = LocalLcars.current
    Box(
        Modifier.size(22.dp).clip(CircleShape)
            .background(color?.let { Color(it) } ?: Color.Black)
            .border(2.dp, if (color == null) s.dim else Color.White.copy(alpha = 0.6f), CircleShape)
    )
}
