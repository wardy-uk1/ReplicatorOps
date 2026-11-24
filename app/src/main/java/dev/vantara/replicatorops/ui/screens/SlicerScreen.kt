package dev.vantara.replicatorops.ui.screens

import dev.vantara.replicatorops.slicer.SlicePreset
import androidx.compose.runtime.LaunchedEffect
import dev.vantara.replicatorops.slicer.ModelAdvisor
import dev.vantara.replicatorops.slicer.Calibration
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
    if (model == null) {
        CalibrationSection(vm, settings, enabled = !busy)
        return
    }
    val (m, plate) = model

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
    val names = plate.objectNames.groupingBy { it }.eachCount().entries.joinToString(", ") { (n, c) -> if (c > 1) "$n ×$c" else n }
    LcarsReadout("Objects", names)
    val (x, y, z) = plate.size
    val fits = plate.fits()
    LcarsReadout("Size", "%.0f × %.0f × %.0f mm".format(x, y, z), valueColor = if (fits) s.text else s.alert)
    if (!fits) LcarsLabel("⚠ Something is off the Q2C's 270 × 270 × 256 mm build area. Arrange it below.", color = s.alert, size = 12.sp, maxLines = 2)

    // ---- Arrange and filaments ----------------------------------------------------------
    Section("Arrange on the bed", "${plate.objects.size} object${if (plate.objects.size == 1) "" else "s"} · tap to move, turn, scale or copy", s.nav[3]) {
        ArrangePanel(vm, plate, enabled = !busy)
    }
    val used = plate.usedFilaments
    Section("Filaments & Box slots", if (used.size > 1) "${used.size} colours" else "1 filament", s.nav[4 % s.nav.size]) {
        FilamentsPanel(vm, plate)
    }
    Spacer(Modifier.height(4.dp))
    LcarsLabel(settings.source, color = s.hint, size = 12.sp)

    // ---- Ship's computer: suggestions ---------------------------------------------------
    ComputerCard(vm, plate, settings, busy)

    // ---- Presets ---------------------------------------------------------------------
    PresetsRow(vm, plate, settings, enabled = !busy)

    // ---- Settings in fold-away sections -------------------------------------------
    SettingsSections(settings, enabled = !busy) { vm.setSliceSettings(it) }
    Spacer(Modifier.height(10.dp))
    LcarsButton("Reset settings", color = s.frameAlt, height = 34.dp, enabled = !busy) { vm.resetSliceSettings() }
    CalibrationSection(vm, settings, enabled = !busy)
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
    val fits = model?.second?.fits() ?: true
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
                var preview by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LcarsButton("Send to printer", color = s.accent, height = 40.dp, enabled = p.online) { vm.sendSliced() }
                    LcarsButton("Layers", color = s.nav[2], height = 40.dp) { vm.loadPreview(state.gcode); preview = true }
                    LcarsButton("Slice again", color = s.nav[1], height = 40.dp) { vm.startSlice() }
                }
                if (!p.online) LcarsLabel("Printer offline: connect in Config to send it.", color = s.alert, size = 11.sp)
                if (preview) {
                    val pv by vm.preview.collectAsState()
                    val pl = state.plate
                    LayerPreviewDialog(
                        title = "Layer preview",
                        layers = (pv as? AppViewModel.LayersState.Ready)?.layers,
                        loading = (pv as? AppViewModel.LayersState.Loading)?.what ?: (pv as? AppViewModel.LayersState.Failed)?.message,
                        onDismiss = { preview = false },
                        toolColours = (0..(pl.usedFilaments.maxOrNull() ?: 0)).map { t ->
                            pl.filaments.getOrNull(t)?.color?.let { Color(it) } ?: s.nav[t % s.nav.size]
                        },
                    )
                }
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
        SubLabel("Adaptive layers (finer on curves)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LcarsChip("Off", selected = !cfg.adaptiveLayers) { set(cfg.copy(adaptiveLayers = false)) }
            LcarsChip("On", selected = cfg.adaptiveLayers) { set(cfg.copy(adaptiveLayers = true)) }
        }
        SubLabel("Fuzzy skin (textured outside)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LcarsChip("Off", selected = !cfg.fuzzySkin) { set(cfg.copy(fuzzySkin = false)) }
            LcarsChip("On", selected = cfg.fuzzySkin) { set(cfg.copy(fuzzySkin = true)) }
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
        SubLabel("Thicken thin parts (holes stay the same)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.0 to "Off", 0.1 to "+0.1 mm", 0.2 to "+0.2 mm").forEach { (v, label) ->
                LcarsChip(label, selected = abs(cfg.xyOffset - v) < 0.001) { set(cfg.copy(xyOffset = v)) }
            }
        }
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


/**
 * The ship's computer: checks the model on the phone and suggests changes for a stronger
 * or more detailed print. Settings changes apply at once; turning and smoothing change the
 * model itself (shown in the viewscreen).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComputerCard(vm: AppViewModel, plate: Plate, settings: SliceSettings, busy: Boolean) {
    val s = LocalLcars.current
    val purpose by vm.purpose.collectAsState()
    val analysis by vm.analysis.collectAsState()
    val done by vm.adviceDone.collectAsState()
    val working by vm.meshWorking.collectAsState()
    LaunchedEffect(plate) { vm.analysePlate(plate) }

    Spacer(Modifier.height(10.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(2.dp, s.nav[1], RoundedCornerShape(14.dp)).padding(10.dp),
    ) {
        LcarsLabel("COMPUTER ANALYSIS", color = s.nav[1], size = 16.sp, bold = true)
        SubLabel("What's it for?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ModelAdvisor.Purpose.entries.forEach { p -> LcarsChip(p.label, selected = purpose == p) { vm.setPurpose(p) } }
        }
        val a = analysis?.takeIf { it.first === plate }?.second
        when {
            working != null -> LcarsLabel("$working…", color = s.accent2, size = 14.sp, modifier = Modifier.padding(top = 8.dp))
            a == null -> LcarsLabel("Analysing the model…", color = s.accent2, size = 14.sp, modifier = Modifier.padding(top = 8.dp))
            else -> {
                val list = ModelAdvisor.suggest(a, settings, purpose).filter { it.id !in done }
                LcarsLabel(
                    buildString {
                        append("%,d triangles".format(a.triangles))
                        a.thinWall?.let { append(" · thinnest walls about %.1f mm".format(it)) }
                        append(" · %.0f mm² on the bed".format(a.bedContact))
                    },
                    color = s.hint, size = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp),
                )
                if (list.isEmpty()) {
                    LcarsLabel("No changes recommended for a ${purpose.label.lowercase()} print.", color = s.text, size = 14.sp,
                        maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                } else {
                    list.forEach { sug ->
                        Spacer(Modifier.height(8.dp))
                        LcarsLabel(sug.title, color = s.accent, size = 15.sp, bold = true)
                        LcarsLabel(sug.why, color = s.text, size = 12.sp, maxLines = 5)
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LcarsButton(if (sug.change is ModelAdvisor.Change.Settings) "Apply" else "Change model",
                                color = s.accent, height = 32.dp, enabled = !busy) { vm.applyAdvice(sug) }
                            LcarsButton("Dismiss", color = s.frameAlt, height = 32.dp) { vm.dismissAdvice(sug.id) }
                        }
                    }
                    if (list.size > 1 && list.all { it.change is ModelAdvisor.Change.Settings }) {
                        Spacer(Modifier.height(8.dp))
                        LcarsButton("Apply all", color = s.nav[1], height = 34.dp, enabled = !busy) { list.forEach { vm.applyAdvice(it) } }
                    }
                }
            }
        }
    }
}


/**
 * Saved presets: tap to use one (Remove appears for the one in use), "Save as" to keep the current
 * settings. A dot marks presets for the filament in the Box slot chosen for the first filament.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetsRow(vm: AppViewModel, plate: Plate, settings: SliceSettings, enabled: Boolean) {
    val s = LocalLcars.current
    val presets by vm.presets.collectAsState()
    val choice by vm.slotChoice.collectAsState()
    val p by vm.printer.collectAsState()
    var naming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SlicePreset?>(null) }
    val firstTool = plate.usedFilaments.firstOrNull() ?: 0
    val boxType = choice[firstTool]?.let { slot -> p.box?.slots?.firstOrNull { it.index == slot }?.material }
    Spacer(Modifier.height(10.dp))
    LcarsLabel("Presets" + (boxType?.let { " · Box has $it" } ?: ""), color = s.accent2, size = 13.sp)
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.forEach { pr ->
            val matches = boxType != null && dev.vantara.replicatorops.data.FilamentMapping.sameType(boxType, pr.settings.filament)
            LcarsChip((if (matches) "● " else "") + pr.name, selected = settings.source == "Preset: ${pr.name}") { if (enabled) vm.applyPreset(pr) }
        }
        LcarsButton("Save as…", color = s.frameAlt, height = 32.dp, enabled = enabled) { naming = true }
        presets.firstOrNull { settings.source == "Preset: ${it.name}" }?.let { cur ->
            LcarsButton("Remove", color = s.alert, height = 32.dp, enabled = enabled) { removing = cur }
        }
    }

    if (naming) {
        var text by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { naming = false },
            containerColor = Color.Black,
            modifier = Modifier.border(2.dp, s.nav[1], RoundedCornerShape(24.dp)),
            title = { LcarsLabel("Save preset", color = s.nav[1], size = 20.sp, bold = true) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it.take(32) }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = dev.vantara.replicatorops.ui.lcars.LcarsFont, fontSize = 18.sp, color = s.text),
                    label = { LcarsLabel("Name, e.g. PETG strong", color = s.accent2, size = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { LcarsButton("Save", color = s.nav[1], enabled = text.isNotBlank()) { vm.savePreset(text); naming = false } },
            dismissButton = { LcarsButton("Cancel", color = s.frameAlt) { naming = false } },
        )
    }
    removing?.let { pr ->
        dev.vantara.replicatorops.ui.lcars.LcarsConfirm(
            title = "Remove \"${pr.name}\"?",
            text = "Deletes this preset from the phone.",
            confirmLabel = "Remove",
            color = s.alert,
            onConfirm = { vm.deletePreset(pr); removing = null },
            onDismiss = { removing = null },
        )
    }
}


/**
 * Calibration prints for the filament in the Slicer's settings: send a test, look at the
 * result, tap the best one, and it goes into the settings (save a preset to keep it).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalibrationSection(vm: AppViewModel, settings: SliceSettings, enabled: Boolean) {
    val s = LocalLcars.current
    val p by vm.printer.collectAsState()
    Section("Calibration prints", "Flow ${settings.flow}% · PA ${settings.pressureAdvance?.let { "%.3f".format(it) } ?: "printer"} · retraction ${"%.1f".format(settings.retraction)} mm", s.nav[0]) {
        LcarsLabel("Uses the ${settings.filament} settings above (${settings.nozzleTemp}° / ${settings.bedTemp}°). Print a test, then tap the best result.",
            color = s.hint, size = 12.sp, maxLines = 3)
        @Composable
        fun Test(test: Calibration.Test, howTo: String, labels: List<String>, current: Int?) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LcarsLabel(test.label, color = s.accent, size = 16.sp, bold = true, modifier = Modifier.weight(1f))
                LcarsButton("Print test", color = s.accent, height = 32.dp, enabled = enabled && p.online && !p.isBusy) { vm.sendCalibration(test) }
            }
            LcarsLabel(howTo, color = s.text, size = 12.sp, maxLines = 5)
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                labels.forEachIndexed { i, l -> LcarsChip(l, selected = current == i) { if (enabled) vm.applyCalibration(test, i) } }
            }
        }
        Test(Calibration.Test.FLOW,
            "Seven squares, 90% on the left to 110% on the right (square n has n tabs in front). Pick the one whose top is smooth and full without ridges.",
            Calibration.FLOW_STEPS.map { "$it%" }, Calibration.FLOW_STEPS.indexOf(settings.flow).takeIf { it >= 0 })
        Test(Calibration.Test.PRESSURE_ADVANCE,
            "Lines printed slow–fast–slow, 0.000 at the front to 0.080 at the back (a tick every 5th line). Pick the line that stays evenly thick where the speed changes.",
            Calibration.PA_STEPS.map { "%.3f".format(it) }, settings.pressureAdvance?.let { pa -> Calibration.PA_STEPS.indexOfFirst { kotlin.math.abs(it - pa) < 1e-6 } }?.takeIf { it >= 0 })
        Test(Calibration.Test.RETRACTION,
            "Two towers, 3 mm bands from 0.2 mm retraction at the bottom to 2.0 mm at the top. Pick the lowest band with no strings between them.",
            Calibration.RETRACTION_STEPS.map { "%.1f".format(it) }, Calibration.RETRACTION_STEPS.indexOfFirst { kotlin.math.abs(it - settings.retraction) < 1e-6 }.takeIf { it >= 0 })
        if (!p.online) LcarsLabel("Connect to the printer to print a test.", color = s.alert, size = 11.sp)
    }
}
