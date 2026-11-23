package dev.vantara.replicatorops.ui.screens

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.data.Spool
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsChip
import dev.vantara.replicatorops.ui.lcars.LcarsConfirm
import dev.vantara.replicatorops.ui.lcars.LcarsFont
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsSectionHeader
import dev.vantara.replicatorops.ui.lcars.LocalLcars

/** Replicator stores: the user's spool list, kept on the phone. */
@Composable
fun StoresScreen(vm: AppViewModel) {
    val s = LocalLcars.current
    val spools by vm.spools.collectAsState()
    val activeId by vm.activeSpool.collectAsState()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Spool?>(null) }
    var adding by remember { mutableStateOf(false) }

    val shown = spools.filter { sp ->
        query.isBlank() || listOf(sp.material, sp.colourName, sp.brand).any { it.contains(query.trim(), ignoreCase = true) }
    }
    val totalKg = spools.sumOf { it.remainingGrams } / 1000

    Column(Modifier.fillMaxSize()) {
        BackLink("Control") { vm.back() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { LcarsSectionHeader("Replicator stores", color = s.nav[3]) }
            Spacer(Modifier.width(8.dp))
            LcarsButton("+ Add", color = s.accent, height = 36.dp) { adding = true }
        }
        LcarsLabel("${spools.size} spools · %.2f kg on hand".format(totalKg), color = s.accent2, size = 14.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { LcarsLabel("Search material, colour or brand", color = s.hint, size = 14.sp) },
            textStyle = TextStyle(fontFamily = LcarsFont, fontSize = 17.sp, color = s.text),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (spools.isEmpty()) {
            LcarsLabel("No spools yet. Tap + Add to log your first one.", color = s.hint, size = 14.sp, maxLines = 2)
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.id }) { sp ->
                SpoolRow(sp, active = sp.id == activeId) { Chirp.tap(); editing = sp }
            }
            item {
                LcarsLabel(
                    "Weights drop automatically after each print, from the spool in the active Box slot or the one marked in use. Red bar = running low.",
                    color = s.hint, size = 12.sp, maxLines = 4, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }

    if (adding || editing != null) {
        SpoolDialog(
            spool = editing,
            isActive = editing?.id == activeId && editing != null,
            onSave = { sp, makeActive ->
                vm.saveSpool(sp)
                if (makeActive) vm.setActiveSpool(sp.id) else if (activeId == sp.id) vm.setActiveSpool(null)
                adding = false; editing = null
            },
            onDelete = { id -> vm.deleteSpool(id); editing = null },
            onDismiss = { adding = false; editing = null },
        )
    }
}

@Composable
fun BackLink(label: String, onClick: () -> Unit) {
    val s = LocalLcars.current
    Box(
        Modifier.height(32.dp).clickable { Chirp.nav(); onClick() }.padding(end = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        LcarsLabel("‹ $label", color = s.accent2, size = 15.sp)
    }
}

@Composable
private fun SpoolRow(sp: Spool, active: Boolean, onClick: () -> Unit) {
    val s = LocalLcars.current
    val low = sp.percent <= 15
    Row(
        Modifier
            .fillMaxWidth()
            .border(2.dp, if (active) s.accent else s.dim, RoundedCornerShape(10.dp))
            .background(Color(0xFF0B0B0B), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpoolIcon(Color(sp.color), size = 36.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LcarsLabel("${sp.material} · ${sp.colourName}", color = s.text, size = 15.sp, modifier = Modifier.weight(1f, fill = false))
                sp.slot?.let {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.background(s.nav[3], RoundedCornerShape(50)).padding(horizontal = 7.dp)) {
                        LcarsLabel("Slot ${it + 1}", color = Color.Black, size = 11.sp)
                    }
                }
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.background(s.accent, RoundedCornerShape(50)).padding(horizontal = 7.dp)) {
                        LcarsLabel("In use", color = Color.Black, size = 11.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                LcarsLabel(sp.brand.ifBlank { "—" }, color = s.accent2, size = 12.sp)
                LcarsLabel("%.0f g · %d%%".format(sp.remainingGrams, sp.percent), color = s.accent2, size = 12.sp)
            }
            Box(Modifier.fillMaxWidth().height(6.dp).background(s.dim, RoundedCornerShape(3.dp))) {
                Box(
                    Modifier.fillMaxWidth(sp.percent / 100f).fillMaxHeight()
                        .background(if (low) s.alert else s.accent, RoundedCornerShape(3.dp))
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpoolDialog(
    spool: Spool?,
    isActive: Boolean,
    onSave: (Spool, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalLcars.current
    var material by remember { mutableStateOf(spool?.material ?: "PLA") }
    var colourName by remember { mutableStateOf(spool?.colourName ?: "Black") }
    var color by remember { mutableStateOf(spool?.color ?: 0xFF1E1E22) }
    var brand by remember { mutableStateOf(spool?.brand ?: "") }
    var total by remember { mutableStateOf((spool?.totalGrams ?: 1000).toString()) }
    var remaining by remember { mutableStateOf((spool?.remainingGrams?.toInt() ?: 1000).toString()) }
    var slot by remember { mutableStateOf(spool?.slot) }
    var inUse by remember { mutableStateOf(isActive) }
    var confirmDelete by remember { mutableStateOf(false) }
    val field = TextStyle(fontFamily = LcarsFont, fontSize = 17.sp, color = s.text)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, s.nav[3], RoundedCornerShape(24.dp)),
        title = { LcarsLabel(if (spool == null) "New spool" else "Edit spool", color = s.nav[3], size = 22.sp, bold = true) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LcarsLabel("Type", color = s.accent2, size = 14.sp)
                Spacer(Modifier.height(6.dp))
                MaterialPicker(material) { material = it }
                Spacer(Modifier.height(12.dp))
                LcarsLabel("Colour", color = s.accent2, size = 14.sp)
                Spacer(Modifier.height(6.dp))
                ColourPicker(color) { name, argb -> color = argb; colourName = name }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = colourName, onValueChange = { colourName = it }, singleLine = true, textStyle = field,
                    label = { LcarsLabel("Colour name", color = s.accent2, size = 12.sp) }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = brand, onValueChange = { brand = it }, singleLine = true, textStyle = field,
                    label = { LcarsLabel("Brand", color = s.accent2, size = 12.sp) }, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = total, onValueChange = { v -> total = v.filter { it.isDigit() }.take(5) }, singleLine = true, textStyle = field,
                        label = { LcarsLabel("Spool g", color = s.accent2, size = 12.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = remaining, onValueChange = { v -> remaining = v.filter { it.isDigit() }.take(5) }, singleLine = true, textStyle = field,
                        label = { LcarsLabel("Left g", color = s.accent2, size = 12.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                LcarsLabel("QIDI Box slot", color = s.accent2, size = 14.sp)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LcarsChip("None", selected = slot == null) { slot = null }
                    (0 until 4).forEach { i -> LcarsChip("${i + 1}", selected = slot == i) { slot = i } }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LcarsChip("In use", selected = inUse) { inUse = true }
                    LcarsChip("Not in use", selected = !inUse) { inUse = false }
                }
                if (spool != null) {
                    Spacer(Modifier.height(12.dp))
                    LcarsButton("Delete spool", color = s.alert, height = 38.dp) { confirmDelete = true }
                }
            }
        },
        confirmButton = {
            LcarsButton("Save", color = s.nav[3], sound = Chirp::confirm) {
                val totalG = total.toIntOrNull()?.coerceAtLeast(1) ?: 1000
                val left = (remaining.toDoubleOrNull() ?: totalG.toDouble()).coerceIn(0.0, totalG.toDouble())
                onSave(
                    Spool(
                        id = spool?.id ?: System.currentTimeMillis(),
                        material = material,
                        colourName = colourName.trim(),
                        brand = brand.trim(),
                        color = color,
                        totalGrams = totalG,
                        remainingGrams = left,
                        slot = slot,
                    ),
                    inUse,
                )
            }
        },
        dismissButton = { LcarsButton("Cancel", color = s.frameAlt) { onDismiss() } },
    )

    if (confirmDelete && spool != null) {
        LcarsConfirm(
            title = "Remove spool?",
            text = "Delete ${spool.material} ${spool.colourName} from Replicator stores?",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; onDelete(spool.id) },
            onDismiss = { confirmDelete = false },
        )
    }
}
