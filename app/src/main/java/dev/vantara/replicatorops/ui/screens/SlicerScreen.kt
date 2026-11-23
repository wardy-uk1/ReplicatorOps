package dev.vantara.replicatorops.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.slicer.ModelFile
import dev.vantara.replicatorops.slicer.Plate
import dev.vantara.replicatorops.slicer.SliceSettings
import dev.vantara.replicatorops.slicer.SliceState
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsChip
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsProgress
import dev.vantara.replicatorops.ui.lcars.LcarsReadout
import dev.vantara.replicatorops.ui.lcars.LocalLcars
import dev.vantara.replicatorops.ui.lcars.formatDuration
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Slice STL and 3MF files on the phone (CuraEngine), then send them to the printer.
 * The 3D preview stays pinned (top in portrait, left in landscape); settings scroll in
 * fold-away sections; the Slice / Send bar stays at the bottom.
 */
@Composable
fun SlicerScreen(vm: AppViewModel) {
    val s = LocalLcars.current
    val engine = vm.slicer ?: return
    val state by engine.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.receiveForSlicing(it) }
    }
    val model = when (val st = state) {
        is SliceState.Ready -> st.model to st.plate
        is SliceState.Slicing -> st.model to st.plate
        is SliceState.Done -> st.model to st.plate
        is SliceState.Failed -> st.model?.let { m -> st.plate?.let { m to it } }
        else -> null
    }
    val busy = state is SliceState.Slicing
    DisposableEffect(Unit) { onDispose { engine.detach() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxWidth > 560.dp
        val previewHeight = (maxHeight * 0.34f).coerceIn(180.dp, 360.dp)

        val (status, busyStatus) = when (state) {
            SliceState.Starting -> "Initialising" to true
            is SliceState.Loading -> "Loading pattern" to true
            else -> (if (model == null) "Awaiting pattern" else null) to false
        }

        @Composable
        fun Preview(mod: Modifier) {
            Box(mod.clip(RoundedCornerShape(14.dp)).border(2.dp, s.frameAlt, RoundedCornerShape(14.dp))) {
                MeshPreview(model?.second, status, busyStatus, Modifier.fillMaxSize())
            }
        }

        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(vm, model, busy) { picker.launch(arrayOf("*/*")) }
                    Preview(Modifier.fillMaxWidth().weight(1f))
                    LcarsLabel("Drag to turn · pinch to zoom · double-tap to centre", color = s.hint, size = 11.sp)
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Body(vm, state, model, busy) }
                    ActionBar(vm, state, model)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(vm, model, busy) { picker.launch(arrayOf("*/*")) }
                // Pinned preview: about a third of the screen.
                Preview(Modifier.fillMaxWidth().height(previewHeight))
                LcarsLabel("Drag to turn · pinch to zoom · double-tap to centre", color = s.hint, size = 11.sp)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Body(vm, state, model, busy) }
                ActionBar(vm, state, model)
            }
        }
    }
}

@Composable
private fun TopBar(vm: AppViewModel, model: Pair<ModelFile, Plate>?, busy: Boolean, onLoad: () -> Unit) {
    val s = LocalLcars.current
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LcarsLabel(model?.first?.name ?: "Pattern synthesis · beta", color = s.accent, size = 16.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        LcarsButton(if (model == null) "Load model" else "Load", color = s.accent, height = 34.dp, enabled = !busy) { onLoad() }
        if (model != null && !busy) {
            Spacer(Modifier.width(6.dp))
            LcarsButton("Clear", color = s.frameAlt, height = 34.dp) { vm.slicer?.clear() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Body(vm: AppViewModel, state: SliceState, model: Pair<ModelFile, Plate>?, busy: Boolean) {
    val s = LocalLcars.current
    val settings by vm.sliceSettings.collectAsState()

    when (state) {
        SliceState.Starting -> LcarsLabel("Starting the slicer…", color = s.accent2, size = 14.sp)
        SliceState.Empty -> LcarsLabel(
            "Load an STL or 3MF (MakerWorld, Printables…), or share one to Replicator Ops. It's sliced here on the phone for your Q2C.",
            color = s.hint, size = 13.sp, maxLines = 4,
        )
        is SliceState.Loading -> LcarsLabel("Loading ${state.name}…", color = s.accent2, size = 14.sp)
        is SliceState.Failed -> LcarsLabel("⚠ ${state.message}", color = s.alert, size = 14.sp, maxLines = 4)
        else -> Unit
    }
    val (m, plate) = model ?: return

    // ---- Model -----------------------------------------------------------------
    if (m.plates.size > 1) {
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            m.plates.forEachIndexed { i, pl ->
                LcarsChip("Plate ${pl.number}", selected = pl.number == plate.number) { if (!busy) vm.choosePlate(i) }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    LcarsReadout("Objects", plate.objectNames.joinToString(", "))
    val (x, y, z) = plate.size
    val fits = x <= 270 && y <= 270 && z <= 256
    LcarsReadout("Size", "%.0f × %.0f × %.0f mm".format(x, y, z), valueColor = if (fits) s.text else s.alert)
    if (!fits) LcarsLabel("⚠ Bigger than the Q2C's 270 × 270 × 256 mm build area", color = s.alert, size = 12.sp, maxLines = 2)
    Spacer(Modifier.height(4.dp))
    LcarsLabel(settings.source, color = s.hint, size = 12.sp)

    // ---- Settings in fold-away sections -------------------------------------------
    SettingsSections(settings, enabled = !busy) { vm.setSliceSettings(it) }
    Spacer(Modifier.height(10.dp))
    LcarsButton("Reset settings", color = s.frameAlt, height = 34.dp, enabled = !busy) { vm.resetSliceSettings() }
    Spacer(Modifier.height(10.dp))
    LcarsLabel(
        "Slicing by CuraEngine (Ultimaker, AGPL-3.0 licence) with QIDI's Q2C start and end G-code. Check the first layer of new prints.",
        color = s.hint, size = 11.sp, maxLines = 4,
    )
    Spacer(Modifier.height(16.dp))
}

/** Slice / progress / Send, kept at the bottom of the screen. */
@Composable
private fun ActionBar(vm: AppViewModel, state: SliceState, model: Pair<ModelFile, Plate>?) {
    val s = LocalLcars.current
    val p by vm.printer.collectAsState()
    model ?: return
    val (x, y, z) = model.second.size
    val fits = x <= 270 && y <= 270 && z <= 256
    Column(Modifier.fillMaxWidth().background(Color.Black).padding(top = 6.dp)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(s.frameAlt))
        Spacer(Modifier.height(6.dp))
        when (state) {
            is SliceState.Slicing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LcarsLabel("${state.stage}… ${state.percent}%", color = s.accent, size = 15.sp, modifier = Modifier.weight(1f))
                    LcarsButton("Cancel", color = s.alert, height = 34.dp) { vm.cancelSlice() }
                }
                Spacer(Modifier.height(4.dp))
                LcarsProgress(state.percent / 100.0)
                LcarsLabel("Keep the app open while slicing.", color = s.hint, size = 11.sp)
            }
            is SliceState.Done -> {
                val bits = listOfNotNull(
                    state.layers.takeIf { it > 0 }?.let { "$it layers" },
                    state.seconds.takeIf { it > 0 }?.let { formatDuration(it) },
                    state.filamentMm.takeIf { it > 0 }?.let { "%.2f m".format(it / 1000) },
                )
                LcarsLabel("Pattern ready · " + bits.joinToString(" · "), color = s.nav[1], size = 14.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LcarsButton("Send to printer", color = s.accent, height = 40.dp, enabled = p.online) { vm.sendSliced() }
                    LcarsButton("Slice again", color = s.nav[1], height = 40.dp) { vm.startSlice() }
                }
                if (!p.online) LcarsLabel("Printer offline: connect in Config to send it.", color = s.alert, size = 11.sp)
            }
            else -> LcarsButton("Slice", color = s.accent, height = 42.dp, enabled = fits, modifier = Modifier.fillMaxWidth()) { vm.startSlice() }
        }
    }
}

// ---- Settings sections ------------------------------------------------------------------

private class FilamentPreset(val name: String, val nozzle: Int, val bed: Int, val flow: Double, val fan: Int)
private val FILAMENTS = listOf(
    FilamentPreset("PLA", 220, 60, 21.0, 100),
    FilamentPreset("PETG", 250, 80, 12.0, 40),
    FilamentPreset("ABS", 260, 100, 18.0, 20),
    FilamentPreset("ASA", 260, 100, 18.0, 20),
    FilamentPreset("TPU", 230, 50, 3.6, 100),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsSections(cfg: SliceSettings, enabled: Boolean, onChange: (SliceSettings) -> Unit) {
    val s = LocalLcars.current
    fun set(c: SliceSettings) { if (enabled) onChange(c) }

    Section("Filament", "${cfg.filament} · ${cfg.nozzleTemp}° / ${cfg.bedTemp}°", s.nav[0], startOpen = true) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FILAMENTS.forEach { f ->
                LcarsChip(f.name, selected = cfg.filament.equals(f.name, true)) {
                    set(cfg.copy(filament = f.name, nozzleTemp = f.nozzle, firstLayerNozzleTemp = f.nozzle, bedTemp = f.bed,
                        firstLayerBedTemp = f.bed, maxFlow = f.flow, fan = f.fan))
                }
            }
        }
        Stepper("Nozzle", "${cfg.nozzleTemp}°", enabled,
            { set(cfg.copy(nozzleTemp = cfg.nozzleTemp - 5, firstLayerNozzleTemp = cfg.firstLayerNozzleTemp - 5)) },
            { set(cfg.copy(nozzleTemp = (cfg.nozzleTemp + 5).coerceAtMost(300), firstLayerNozzleTemp = (cfg.firstLayerNozzleTemp + 5).coerceAtMost(300))) })
        Stepper("Bed", "${cfg.bedTemp}°", enabled,
            { set(cfg.copy(bedTemp = (cfg.bedTemp - 5).coerceAtLeast(0), firstLayerBedTemp = (cfg.firstLayerBedTemp - 5).coerceAtLeast(0))) },
            { set(cfg.copy(bedTemp = (cfg.bedTemp + 5).coerceAtMost(110), firstLayerBedTemp = (cfg.firstLayerBedTemp + 5).coerceAtMost(110))) })
        Stepper("Part fan", "${cfg.fan}%", enabled,
            { set(cfg.copy(fan = (cfg.fan - 10).coerceAtLeast(0))) }, { set(cfg.copy(fan = (cfg.fan + 10).coerceAtMost(100))) })
    }

    val seamName = SliceSettings.SEAMS.firstOrNull { it.first == cfg.seam }?.second ?: cfg.seam
    Section("Quality", "%.2f mm layers · seam: %s%s".format(cfg.layerHeight, seamName.lowercase(), if (cfg.ironing) " · ironing" else ""), s.nav[1]) {
        SubLabel("Layer height (mm)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.08, 0.12, 0.16, 0.2, 0.24, 0.28).forEach { h ->
                LcarsChip("%.2f".format(h), selected = abs(cfg.layerHeight - h) < 0.001) { set(cfg.copy(layerHeight = h)) }
            }
        }
        Stepper("First layer", "%.2f mm".format(cfg.firstLayerHeight), enabled,
            { set(cfg.copy(firstLayerHeight = (((cfg.firstLayerHeight - 0.04) * 100).roundToInt() / 100.0).coerceAtLeast(0.12))) },
            { set(cfg.copy(firstLayerHeight = (((cfg.firstLayerHeight + 0.04) * 100).roundToInt() / 100.0).coerceAtMost(0.32))) })
        SubLabel("Seam position")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SliceSettings.SEAMS.forEach { (id, label) -> LcarsChip(label, selected = cfg.seam == id) { set(cfg.copy(seam = id)) } }
        }
        SubLabel("Ironing (smooth top surfaces)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LcarsChip("Off", selected = !cfg.ironing) { set(cfg.copy(ironing = false)) }
            LcarsChip("On", selected = cfg.ironing) { set(cfg.copy(ironing = true)) }
        }
    }

    val patternName = SliceSettings.INFILL_PATTERNS.firstOrNull { it.first == cfg.infillPattern }?.second ?: cfg.infillPattern
    Section("Strength", "${cfg.walls} walls · ${cfg.infill}% ${patternName.lowercase()}", s.nav[2]) {
        Stepper("Walls", cfg.walls.toString(), enabled,
            { set(cfg.copy(walls = (cfg.walls - 1).coerceAtLeast(1))) }, { set(cfg.copy(walls = (cfg.walls + 1).coerceAtMost(10))) })
        Stepper("Top layers", cfg.topLayers.toString(), enabled,
            { set(cfg.copy(topLayers = (cfg.topLayers - 1).coerceAtLeast(0))) }, { set(cfg.copy(topLayers = (cfg.topLayers + 1).coerceAtMost(20))) })
        Stepper("Bottom layers", cfg.bottomLayers.toString(), enabled,
            { set(cfg.copy(bottomLayers = (cfg.bottomLayers - 1).coerceAtLeast(0))) }, { set(cfg.copy(bottomLayers = (cfg.bottomLayers + 1).coerceAtMost(20))) })
        var infill by remember(cfg.infill) { mutableFloatStateOf(cfg.infill.toFloat()) }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SubLabel("Infill", Modifier.weight(1f))
            LcarsLabel("${infill.roundToInt()}%", color = s.text, size = 17.sp)
        }
        Slider(
            value = infill, onValueChange = { infill = it }, onValueChangeFinished = { set(cfg.copy(infill = infill.roundToInt())) },
            valueRange = 0f..100f, steps = 19, enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = s.accent, activeTrackColor = s.accent, inactiveTrackColor = s.dim),
        )
        SubLabel("Pattern")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SliceSettings.INFILL_PATTERNS.forEach { (id, label) -> LcarsChip(label, selected = cfg.infillPattern == id) { set(cfg.copy(infillPattern = id)) } }
        }
    }

    val supportText = if (!cfg.supports) "No supports" else if (cfg.treeSupports) "Organic tree supports" else "Supports"
    val brimText = if (cfg.brim <= 0) "no brim" else "${cfg.brim.roundToInt()} mm brim"
    Section("Support & adhesion", "$supportText · $brimText", s.nav[3]) {
        SubLabel("Supports")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LcarsChip("Off", selected = !cfg.supports) { set(cfg.copy(supports = false)) }
            LcarsChip("Normal", selected = cfg.supports && !cfg.treeSupports) { set(cfg.copy(supports = true, treeSupports = false)) }
            LcarsChip("Organic tree", selected = cfg.supports && cfg.treeSupports) { set(cfg.copy(supports = true, treeSupports = true)) }
        }
        if (cfg.supports) {
            Stepper("Overhang angle", "${cfg.supportAngle}°", enabled,
                { set(cfg.copy(supportAngle = (cfg.supportAngle - 5).coerceAtLeast(10))) },
                { set(cfg.copy(supportAngle = (cfg.supportAngle + 5).coerceAtMost(80))) })
        }
        SubLabel("Brim")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.0, 3.0, 5.0, 8.0).forEach { b ->
                LcarsChip(if (b == 0.0) "None" else "${b.roundToInt()} mm", selected = abs(cfg.brim - b) < 0.01) { set(cfg.copy(brim = b)) }
            }
        }
    }

    Section("Speed", "Walls ${cfg.wallSpeed} · infill ${cfg.infillSpeed} mm/s", s.accent2) {
        Stepper("Wall speed", "${cfg.wallSpeed}", enabled,
            { set(cfg.copy(wallSpeed = (cfg.wallSpeed - 25).coerceAtLeast(25))) }, { set(cfg.copy(wallSpeed = (cfg.wallSpeed + 25).coerceAtMost(400))) })
        Stepper("Infill speed", "${cfg.infillSpeed}", enabled,
            { set(cfg.copy(infillSpeed = (cfg.infillSpeed - 25).coerceAtLeast(25))) }, { set(cfg.copy(infillSpeed = (cfg.infillSpeed + 25).coerceAtMost(500))) })
        Stepper("First layer speed", "${cfg.firstLayerSpeed}", enabled,
            { set(cfg.copy(firstLayerSpeed = (cfg.firstLayerSpeed - 5).coerceAtLeast(10))) }, { set(cfg.copy(firstLayerSpeed = (cfg.firstLayerSpeed + 5).coerceAtMost(150))) })
        LcarsLabel("Speeds in mm/s. The filament's flow limit (%.1f mm³/s) still caps them.".format(cfg.maxFlow), color = s.hint, size = 11.sp, maxLines = 2)
    }
}

/** LCARS fold-away section: a coloured header with a one-line summary; tap to open or close. */
@Composable
private fun Section(title: String, summary: String, color: Color, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val s = LocalLcars.current
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 17.dp, bottomStart = 17.dp))
            .clickable { Chirp.tap(); open = !open },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp).height(34.dp).clip(RoundedCornerShape(topStart = 17.dp, bottomStart = 17.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            LcarsLabel(title, color = color, size = 17.sp, bold = true)
            if (!open) LcarsLabel(summary, color = s.hint, size = 12.sp)
        }
        LcarsLabel(if (open) "▲" else "▼", color = color, size = 14.sp)
        Spacer(Modifier.width(6.dp))
    }
    if (open) {
        Column(Modifier.fillMaxWidth().padding(start = 26.dp, top = 2.dp), content = content)
    }
}

@Composable
private fun SubLabel(text: String, modifier: Modifier = Modifier) {
    LcarsLabel(text, color = LocalLcars.current.accent2, size = 13.sp, modifier = modifier.padding(top = 6.dp, bottom = 4.dp))
}

/** Compact "label  (−) value (+)" row. */
@Composable
private fun Stepper(label: String, value: String, enabled: Boolean, minus: () -> Unit, plus: () -> Unit) {
    val s = LocalLcars.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        LcarsLabel(label, color = s.accent2, size = 14.sp, modifier = Modifier.weight(1f))
        RoundKey("−", enabled, minus)
        LcarsLabel(value, color = s.text, size = 17.sp, align = TextAlign.Center, modifier = Modifier.widthIn(min = 64.dp).padding(horizontal = 4.dp))
        RoundKey("+", enabled, plus)
    }
}

@Composable
private fun RoundKey(text: String, enabled: Boolean, onClick: () -> Unit) {
    val s = LocalLcars.current
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(if (enabled) s.nav[1] else s.dim)
            .clickable(enabled = enabled) { Chirp.tap(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        LcarsLabel(text, color = Color.Black, size = 18.sp, bold = true)
    }
}
