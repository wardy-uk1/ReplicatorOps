package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.data.SlotInfo
import dev.vantara.replicatorops.data.Spool
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsChip
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsSectionHeader
import dev.vantara.replicatorops.ui.lcars.LocalLcars

/** What a slot shows: the user's setting first, then what the printer reports. */
private data class SlotView(val index: Int, val loaded: Boolean, val material: String?, val color: Long?, val spool: Spool?)

/** QIDI Box slots on the Control screen. Tap a slot to select it; "Edit slot" sets its type and colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxSection(vm: AppViewModel, canAct: Boolean) {
    val s = LocalLcars.current
    val p by vm.printer.collectAsState()
    val slotInfo by vm.slotInfo.collectAsState()
    val spools by vm.spools.collectAsState()
    var selected by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Int?>(null) }

    LcarsSectionHeader("Filament bay · QIDI Box", color = s.nav[3])
    val box = p.box
    val views = (0 until 4).map { i ->
        val fromPrinter = box?.slots?.getOrNull(i)
        val mine = slotInfo[i]
        val spool = mine?.spoolId?.let { id -> spools.firstOrNull { it.id == id } } ?: spools.firstOrNull { it.slot == i }
        SlotView(
            index = i,
            loaded = fromPrinter?.loaded ?: (mine != null),
            material = mine?.material ?: fromPrinter?.material,
            color = mine?.color ?: fromPrinter?.color,
            spool = spool,
        )
    }

    if (box == null) {
        LcarsLabel("No Box data from the printer yet. Slots below are your own settings.", color = s.dim, size = 13.sp, maxLines = 3)
        Spacer(Modifier.height(6.dp))
    } else {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val active = box.activeSlot?.let { a -> views[a] }
            LcarsLabel(
                active?.let { "Active: slot ${it.index + 1} · ${it.material ?: "unknown"}" } ?: "No active slot",
                color = s.accent2, size = 14.sp,
            )
            val climate = listOfNotNull(box.temperature?.let { "%.0f°".format(it) }, box.humidity?.let { "%.0f%% RH".format(it) })
            if (climate.isNotEmpty()) LcarsLabel(climate.joinToString(" · "), color = s.text, size = 14.sp)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        views.forEach { v ->
            val isActive = box?.activeSlot == v.index
            val isSelected = selected == v.index
            Column(
                Modifier
                    .weight(1f)
                    .border(
                        if (isSelected) 3.dp else 2.dp,
                        when { isSelected -> s.accent; isActive -> s.nav[3]; else -> s.dim },
                        RoundedCornerShape(10.dp),
                    )
                    .background(Color(0xFF0B0B0B), RoundedCornerShape(10.dp))
                    .clickable { Chirp.tap(); selected = v.index }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                LcarsLabel("Slot ${v.index + 1}", color = s.accent2, size = 12.sp)
                SpoolIcon(if (v.loaded || v.color != null) Color(v.color ?: 0xFF777777) else null)
                LcarsLabel(if (v.loaded || v.material != null) v.material ?: "?" else "Empty", color = s.text, size = 12.sp)
                LcarsLabel(v.spool?.let { "%.0f g".format(it.remainingGrams) } ?: " ", color = Color(0xFF9A8A7A), size = 11.sp)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LcarsButton("Edit slot ${selected + 1}", color = s.accent, height = 38.dp) { editing = selected }
        LcarsButton("Load", color = s.nav[0], height = 38.dp, enabled = canAct) { vm.boxLoad(selected) }
        LcarsButton("Unload", color = s.nav[2], height = 38.dp, enabled = canAct) { vm.boxUnload(selected) }
        LcarsButton("Read tag", color = s.nav[3], height = 38.dp, enabled = canAct) { vm.boxReadRfid(selected) }
    }

    editing?.let { slot ->
        SlotDialog(
            slot = slot,
            current = slotInfo[slot] ?: views[slot].let { v -> v.material?.let { SlotInfo(it, v.color ?: 0xFF8A8F98, v.spool?.id) } },
            spools = spools,
            onSave = { vm.setSlot(slot, it); editing = null },
            onClear = { vm.setSlot(slot, null); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotDialog(
    slot: Int,
    current: SlotInfo?,
    spools: List<Spool>,
    onSave: (SlotInfo) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalLcars.current
    var material by remember { mutableStateOf(current?.material ?: "PLA") }
    var color by remember { mutableStateOf(current?.color ?: 0xFF1E1E22) }
    var spoolId by remember { mutableStateOf(current?.spoolId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, s.nav[3], RoundedCornerShape(24.dp)),
        title = { LcarsLabel("Slot ${slot + 1} filament", color = s.nav[3], size = 22.sp, bold = true) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (spools.isNotEmpty()) {
                    LcarsLabel("From Replicator stores", color = s.accent2, size = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LcarsChip("None", selected = spoolId == null) { spoolId = null }
                        spools.forEach { sp ->
                            LcarsChip("${sp.material} ${sp.colourName}".trim(), selected = spoolId == sp.id) {
                                spoolId = sp.id
                                material = sp.material
                                color = sp.color
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                LcarsLabel("Type", color = s.accent2, size = 14.sp)
                Spacer(Modifier.height(6.dp))
                MaterialPicker(material) { material = it; spoolId = null }
                Spacer(Modifier.height(12.dp))
                LcarsLabel("Colour", color = s.accent2, size = 14.sp)
                Spacer(Modifier.height(6.dp))
                ColourPicker(color) { _, argb -> color = argb; spoolId = null }
                Spacer(Modifier.height(10.dp))
                LcarsLabel(
                    "Colour is sent to the printer. Type is kept on this phone, as the printer only stores QIDI filament codes.",
                    color = Color(0xFF9A8A7A), size = 12.sp, maxLines = 3,
                )
            }
        },
        confirmButton = {
            LcarsButton("Save", color = s.nav[3], sound = Chirp::confirm) { onSave(SlotInfo(material, color, spoolId)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LcarsButton("Clear", color = s.alert) { onClear() }
                LcarsButton("Cancel", color = s.frameAlt) { onDismiss() }
            }
        },
    )
}
