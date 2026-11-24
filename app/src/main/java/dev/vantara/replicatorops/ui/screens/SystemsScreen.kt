package dev.vantara.replicatorops.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import dev.vantara.replicatorops.ui.lcars.LcarsFont
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsConfirm
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.lcars.LcarsChip
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsReadout
import dev.vantara.replicatorops.ui.lcars.LcarsSectionHeader
import dev.vantara.replicatorops.ui.lcars.LocalLcars

/** Ship systems: the printer's own settings (case light, spaghetti detection, pre-print check). */
@Composable
fun SystemsScreen(vm: AppViewModel) {
    val s = LocalLcars.current
    val p by vm.printer.collectAsState()
    val sys = p.systems
    val ready = p.ready

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (!ready) {
            LcarsLabel("Ship systems are offline until the printer is connected.", color = s.alert, size = 15.sp, maxLines = 3)
        }

        LcarsSectionHeader("Illumination", color = s.nav[0])
        if (sys.caseLight == null) {
            NotReported("Case light")
        } else {
            val on = sys.caseLight > 0.0
            LcarsReadout("Case light", if (on) "On" else "Off", valueColor = if (on) s.accent else s.hint)
            OnOff(on, ready, vm::setCaseLight)
        }

        LcarsSectionHeader("Spaghetti detection", color = s.nav[1])
        LcarsLabel(
            "The printer's camera watches for scattered filament and pauses the print.",
            color = s.hint, size = 13.sp, maxLines = 3,
        )
        Spacer(Modifier.height(6.dp))
        if (sys.noodleDetection == null) {
            NotReported("Spaghetti detection")
        } else {
            OnOff(sys.noodleDetection, ready, vm::setNoodleDetection)
            Spacer(Modifier.height(10.dp))
            LcarsReadout("Sensitivity for pausing", sys.noodleSensitivity ?: "Not set", valueColor = s.accent)
            val current = sys.noodleSensitivity?.lowercase()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SENSITIVITY.forEach { (label, value) ->
                    val selected = current == value || (value == "medium" && current == "middle")
                    LcarsChip(label, selected = selected) {
                        if (ready && sys.noodleDetection) vm.setNoodleSensitivity(if (current == "middle" && value == "medium") "middle" else value)
                    }
                }
            }
            if (!sys.noodleDetection) {
                LcarsLabel("Turn detection on to change sensitivity.", color = s.hint, size = 12.sp)
            }
        }

        LcarsSectionHeader("Pre-flight scan", color = s.nav[2])
        LcarsLabel(
            "Checks the build plate for foreign objects before printing.",
            color = s.hint, size = 13.sp, maxLines = 3,
        )
        Spacer(Modifier.height(6.dp))
        if (sys.prePrintCheck == null) {
            NotReported("Pre-flight scan")
        } else {
            OnOff(sys.prePrintCheck, ready, vm::setPrePrintCheck)
        }

        Spacer(Modifier.height(14.dp))
        LcarsLabel(
            "Detection settings are saved on the printer and apply from the next print.",
            color = s.hint, size = 12.sp, maxLines = 3,
        )

        BedMeshSection(vm)

        Subroutines(vm)
        Spacer(Modifier.height(24.dp))
    }
}

private val SENSITIVITY = listOf("Low" to "low", "Medium" to "medium", "High" to "high")

@Composable
private fun OnOff(on: Boolean, enabled: Boolean, set: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LcarsChip("Online", selected = on) { if (enabled && !on) set(true) }
        LcarsChip("Offline", selected = !on) { if (enabled && on) set(false) }
    }
}

@Composable
private fun NotReported(what: String) {
    LcarsLabel("$what isn't reported by this printer.", color = s().hint, size = 14.sp, maxLines = 2)
}

@Composable
private fun s() = LocalLcars.current

/** Subroutines: Klipper macros. Starred ones sit at the top; the full list folds away. */
@Composable
internal fun Subroutines(vm: AppViewModel) {
    val s = LocalLcars.current
    val p by vm.printer.collectAsState()
    val favs by vm.favMacros.collectAsState()
    val help by vm.macroHelp.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val starred = p.macros.filter { it in favs }

    fun run(name: String) { if (p.isBusy) confirm = name else vm.runMacro(name) }

    LcarsSectionHeader("Subroutines", color = s.nav[3])
    if (p.macros.isEmpty()) {
        NotReported("Macro list")
        return
    }
    if (starred.isEmpty()) {
        LcarsLabel("Star your favourite macros below to keep them here.", color = s.hint, size = 13.sp, maxLines = 2)
    } else {
        starred.forEach { m ->
            MacroRow(m, help[m.uppercase()], starred = true, enabled = p.ready, onStar = { vm.toggleFavouriteMacro(m) }) { run(m) }
        }
    }
    Spacer(Modifier.height(8.dp))
    LcarsButton(
        if (showAll) "Hide all subroutines ▲" else "All subroutines (${p.macros.size}) ▼",
        color = s.nav[3], height = 38.dp,
    ) { showAll = !showAll }
    if (showAll) {
        p.macros.forEach { m ->
            MacroRow(m, help[m.uppercase()], starred = m in favs, enabled = p.ready, onStar = { vm.toggleFavouriteMacro(m) }) { run(m) }
        }
    }

    confirm?.let { m ->
        LcarsConfirm(
            title = "Run $m during a print?",
            text = "A print is running. Some macros move the toolhead or change temperatures.",
            confirmLabel = "Engage",
            color = s.nav[3],
            onConfirm = { confirm = null; vm.runMacro(m) },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun MacroRow(name: String, description: String?, starred: Boolean, enabled: Boolean, onStar: () -> Unit, onRun: () -> Unit) {
    val s = LocalLcars.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable { Chirp.tap(); onStar() },
            contentAlignment = Alignment.Center,
        ) {
            LcarsLabel(if (starred) "★" else "☆", color = if (starred) s.accent else s.hint, size = 22.sp)
        }
        Column(Modifier.weight(1f)) {
            LcarsLabel(name.replace('_', ' '), color = s.text, size = 15.sp)
            description?.takeIf { it.isNotBlank() && !it.equals("G-Code macro", true) }?.let {
                LcarsLabel(it, color = s.hint, size = 11.sp)
            }
        }
        Spacer(Modifier.width(6.dp))
        LcarsButton("Run", color = s.nav[3], height = 34.dp, enabled = enabled) { onRun() }
    }
}

/**
 * Bed mesh: 3D map of the bed's levelling, the profiles saved on the printer (view, load,
 * remove), saving the current mesh under a name, and measuring the bed again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BedMeshSection(vm: AppViewModel) {
    val s = LocalLcars.current
    val p by vm.printer.collectAsState()
    var confirm by remember { mutableStateOf<MeshAction?>(null) }
    var naming by remember { mutableStateOf<MeshAction?>(null) }
    // Which mesh the map shows: null = the one in use.
    var viewing by remember { mutableStateOf<String?>(null) }
    LcarsSectionHeader("Bed mesh", color = s.nav[0])
    val active = p.bedMesh
    val saved = p.meshProfiles
    val shown = viewing?.let { saved[it] } ?: active
    val idle = p.ready && !p.isBusy

    if (active != null || saved.isNotEmpty()) {
        LcarsLabel("Profiles", color = s.accent2, size = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (active != null) LcarsChip("In use: ${active.profile}", selected = viewing == null) { viewing = null }
            saved.keys.forEach { name -> LcarsChip(name, selected = viewing == name) { viewing = name } }
        }
        Spacer(Modifier.height(6.dp))
    }
    if (shown == null) {
        LcarsLabel("No bed mesh loaded. Calibrate to measure the bed.", color = s.hint, size = 14.sp, maxLines = 2)
    } else {
        // Keep the map a sensible size on wide tablets and landscape.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            BedMeshView(shown, Modifier.widthIn(max = 460.dp).fillMaxWidth())
        }
    }
    Spacer(Modifier.height(8.dp))
    val v = viewing
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (v != null) {
            LcarsButton("Load \"$v\"", color = s.nav[0], height = 38.dp, enabled = idle) { vm.loadMeshProfile(v); viewing = null }
            LcarsButton("Remove", color = s.alert, height = 38.dp, enabled = idle) { confirm = MeshAction.Remove(v) }
        } else {
            LcarsButton("Calibrate bed mesh", color = s.nav[0], height = 38.dp, enabled = idle) { confirm = MeshAction.Calibrate }
            if (active != null) LcarsButton("Save as profile", color = s.nav[1], height = 38.dp, enabled = idle) { naming = MeshAction.Save("") }
            LcarsButton("New profile", color = s.frameAlt, height = 38.dp, enabled = idle) { naming = MeshAction.CalibrateAs("") }
        }
    }
    LcarsLabel(
        "KAMP measures just the print area at the start of each print. Saved profiles keep a whole-bed map, " +
            "e.g. one per bed temperature or plate. Saving or removing a profile restarts Klipper.",
        color = s.hint, size = 11.sp, maxLines = 4, modifier = Modifier.padding(top = 6.dp),
    )

    naming?.let { action ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = null },
            containerColor = Color.Black,
            modifier = Modifier.border(2.dp, s.nav[0], RoundedCornerShape(24.dp)),
            title = { LcarsLabel(if (action is MeshAction.Save) "Save mesh as" else "Measure a new profile", color = s.nav[0], size = 20.sp, bold = true) },
            text = {
                Column {
                    LcarsLabel(
                        if (action is MeshAction.Save) "Saves the mesh in use under this name, then restarts Klipper."
                        else "Homes, probes the whole bed and saves it under this name, then restarts Klipper. Heat the bed first for the most accurate result.",
                        color = s.hint, size = 13.sp, maxLines = 4,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { t -> text = t.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == ' ' }.take(32) },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = LcarsFont, fontSize = 18.sp, color = s.text),
                        label = { LcarsLabel("Profile name, e.g. pei_100c", color = s.accent2, size = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                LcarsButton(if (action is MeshAction.Save) "Save" else "Measure", color = s.nav[0], enabled = text.isNotBlank()) {
                    if (action is MeshAction.Save) vm.saveMeshProfile(text) else vm.calibrateMeshAs(text)
                    naming = null
                }
            },
            dismissButton = { LcarsButton("Cancel", color = s.frameAlt) { naming = null } },
        )
    }
    confirm?.let { action ->
        LcarsConfirm(
            title = if (action is MeshAction.Remove) "Remove \"${action.name}\"?" else "Calibrate bed mesh?",
            text = if (action is MeshAction.Remove) "Deletes this saved profile from the printer and restarts Klipper."
                else "The printer homes and probes the whole bed. This takes a few minutes; heat the bed first for the most accurate result.",
            confirmLabel = if (action is MeshAction.Remove) "Remove" else "Calibrate",
            color = if (action is MeshAction.Remove) s.alert else s.nav[0],
            onConfirm = {
                confirm = null
                when (action) {
                    is MeshAction.Remove -> { vm.removeMeshProfile(action.name); viewing = null }
                    else -> vm.calibrateMesh()
                }
            },
            onDismiss = { confirm = null },
        )
    }
}

private sealed interface MeshAction {
    data object Calibrate : MeshAction
    data class Remove(val name: String) : MeshAction
    data class Save(val name: String) : MeshAction
    data class CalibrateAs(val name: String) : MeshAction
}
