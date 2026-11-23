package dev.vantara.replicatorops.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.vantara.replicatorops.slicer.SliceSettings
import dev.vantara.replicatorops.slicer.SliceState
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsChip
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsProgress
import dev.vantara.replicatorops.ui.lcars.LcarsReadout
import dev.vantara.replicatorops.ui.lcars.LcarsSectionHeader
import dev.vantara.replicatorops.ui.lcars.LocalLcars
import dev.vantara.replicatorops.ui.lcars.formatDuration
import kotlin.math.roundToInt

/** Beta: slice STL and 3MF files on the phone (Kiri:Moto engine), then send them to the printer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SlicerScreen(vm: AppViewModel) {
    val s = LocalLcars.current
    val engine = vm.slicer ?: return
    val state by engine.state.collectAsState()
    val settings by vm.sliceSettings.collectAsState()
    val p by vm.printer.collectAsState()
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LcarsSectionHeader("Pattern synthesis · beta", color = s.accent)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LcarsButton("Load model", color = s.accent, height = 36.dp, enabled = !busy) { picker.launch(arrayOf("*/*")) }
            if (model != null && !busy) LcarsButton("Clear", color = s.frameAlt, height = 36.dp) { engine.clear() }
        }
        Spacer(Modifier.height(8.dp))

        // 3D preview (the slicer's own page). Drag to turn, pinch to zoom.
        Box(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().aspectRatio(1.15f)
                .clip(RoundedCornerShape(14.dp)).border(2.dp, s.frameAlt, RoundedCornerShape(14.dp)),
        ) {
            AndroidView(factory = { engine.detach(); engine.webView }, modifier = Modifier.fillMaxSize())
        }
        DisposableEffect(Unit) { onDispose { engine.detach() } }
        LcarsLabel("Drag to turn · pinch to zoom", color = s.hint, size = 11.sp)

        when (val st = state) {
            SliceState.Starting -> LcarsLabel("Starting the slicer…", color = s.accent2, size = 14.sp)
            SliceState.Empty -> LcarsLabel(
                "Load an STL or 3MF (MakerWorld, Printables…), or share one to Replicator Ops Beta. It's sliced here on the phone for your Q2C.",
                color = s.hint, size = 13.sp, maxLines = 4,
            )
            is SliceState.Loading -> LcarsLabel("Loading ${st.name}…", color = s.accent2, size = 14.sp)
            is SliceState.Failed -> LcarsLabel("⚠ ${st.message}", color = s.alert, size = 14.sp, maxLines = 4)
            else -> Unit
        }

        model?.let { (m, plate) ->
            Spacer(Modifier.height(8.dp))
            LcarsLabel(m.name, color = s.accent, size = 16.sp)
            if (m.plates.size > 1) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    m.plates.forEachIndexed { i, pl ->
                        LcarsChip("Plate ${pl.number}", selected = pl.number == plate.number) { if (!busy) vm.choosePlate(i) }
                    }
                }
            }
            LcarsReadout("Objects", plate.objectNames.joinToString(", "))
            val (x, y, z) = plate.size
            val fits = x <= 270 && y <= 270 && z <= 256
            LcarsReadout("Size", "%.0f × %.0f × %.0f mm".format(x, y, z), valueColor = if (fits) s.text else s.alert)
            if (!fits) LcarsLabel("⚠ Bigger than the Q2C's 270 × 270 × 256 mm build area", color = s.alert, size = 12.sp)

            SettingsPanel(settings, enabled = !busy, onReset = { vm.resetSliceSettings() }) { vm.setSliceSettings(it) }

            Spacer(Modifier.height(10.dp))
            when (st) {
                is SliceState.Slicing -> {
                    LcarsLabel("${st.stage}… ${st.percent}%", color = s.accent, size = 15.sp)
                    LcarsProgress(st.percent / 100.0)
                    Spacer(Modifier.height(6.dp))
                    LcarsButton("Cancel", color = s.alert, height = 38.dp) { vm.cancelSlice() }
                    LcarsLabel("Keep the app open while slicing.", color = s.hint, size = 12.sp)
                }
                is SliceState.Done -> {
                    LcarsSectionHeader("Pattern ready", color = s.nav[1])
                    if (st.layers > 0) LcarsReadout("Layers", st.layers.toString())
                    if (st.seconds > 0) LcarsReadout("Print time (est.)", formatDuration(st.seconds))
                    if (st.filamentMm > 0) LcarsReadout("Filament", "%.2f m".format(st.filamentMm / 1000))
                    LcarsReadout("File", st.gcode.name)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LcarsButton("Send to printer", color = s.accent, height = 40.dp, enabled = p.online) { vm.sendSliced() }
                        LcarsButton("Slice again", color = s.nav[1], height = 40.dp) { vm.startSlice() }
                    }
                    if (!p.online) LcarsLabel("Printer offline: connect in Config to send it.", color = s.alert, size = 12.sp)
                }
                else -> LcarsButton("Slice", color = s.accent, height = 42.dp, enabled = fits) { vm.startSlice() }
            }
        }
        Spacer(Modifier.height(10.dp))
        LcarsLabel(
            "Beta: slicing by Kiri:Moto (grid.space, MIT licence) with QIDI's Q2C start and end G-code. Check the first layer of new prints.",
            color = s.hint, size = 11.sp, maxLines = 4,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsPanel(cfg: SliceSettings, enabled: Boolean, onReset: () -> Unit, onChange: (SliceSettings) -> Unit) {
    val s = LocalLcars.current
    fun set(c: SliceSettings) { if (enabled) onChange(c) }
    LcarsSectionHeader("Settings", color = s.nav[2])
    LcarsLabel(cfg.source, color = s.hint, size = 12.sp)

    Label("Filament")
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
        { set(cfg.copy(nozzleTemp = cfg.nozzleTemp + 5, firstLayerNozzleTemp = cfg.firstLayerNozzleTemp + 5)) })
    Stepper("Bed", "${cfg.bedTemp}°", enabled,
        { set(cfg.copy(bedTemp = cfg.bedTemp - 5, firstLayerBedTemp = cfg.firstLayerBedTemp - 5)) },
        { set(cfg.copy(bedTemp = cfg.bedTemp + 5, firstLayerBedTemp = cfg.firstLayerBedTemp + 5)) })

    Label("Layer height")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0.12, 0.16, 0.2, 0.24, 0.28).forEach { h ->
            LcarsChip("%.2f".format(h), selected = kotlin.math.abs(cfg.layerHeight - h) < 0.001) { set(cfg.copy(layerHeight = h)) }
        }
    }
    Stepper("Walls", cfg.walls.toString(), enabled,
        { set(cfg.copy(walls = (cfg.walls - 1).coerceAtLeast(1))) }, { set(cfg.copy(walls = (cfg.walls + 1).coerceAtMost(10))) })
    Stepper("Top / bottom layers", "${cfg.topLayers} / ${cfg.bottomLayers}", enabled,
        { set(cfg.copy(topLayers = (cfg.topLayers - 1).coerceAtLeast(0), bottomLayers = (cfg.bottomLayers - 1).coerceAtLeast(0))) },
        { set(cfg.copy(topLayers = cfg.topLayers + 1, bottomLayers = cfg.bottomLayers + 1)) })

    var infill by remember(cfg.infill) { mutableFloatStateOf(cfg.infill.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label("Infill", Modifier.weight(1f))
        LcarsLabel("${infill.roundToInt()}%", color = s.text, size = 18.sp)
    }
    Slider(
        value = infill, onValueChange = { infill = it }, onValueChangeFinished = { set(cfg.copy(infill = infill.roundToInt())) },
        valueRange = 0f..100f, steps = 19, enabled = enabled,
        colors = SliderDefaults.colors(thumbColor = s.accent, activeTrackColor = s.accent, inactiveTrackColor = s.dim),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SliceSettings.INFILL_PATTERNS.forEach { pt -> LcarsChip(pt, selected = cfg.infillPattern == pt) { set(cfg.copy(infillPattern = pt)) } }
    }

    Label("Supports")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LcarsChip("Off", selected = !cfg.supports) { set(cfg.copy(supports = false)) }
        LcarsChip("Normal", selected = cfg.supports && !cfg.treeSupports) { set(cfg.copy(supports = true, treeSupports = false)) }
        LcarsChip("Tree", selected = cfg.supports && cfg.treeSupports) { set(cfg.copy(supports = true, treeSupports = true)) }
    }
    Label("Brim")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0.0, 3.0, 5.0, 8.0).forEach { b ->
            LcarsChip(if (b == 0.0) "None" else "${b.roundToInt()} mm", selected = kotlin.math.abs(cfg.brim - b) < 0.01) { set(cfg.copy(brim = b)) }
        }
    }
    Stepper("Wall speed", "${cfg.wallSpeed} mm/s", enabled,
        { set(cfg.copy(wallSpeed = (cfg.wallSpeed - 25).coerceAtLeast(25), infillSpeed = (cfg.infillSpeed - 25).coerceAtLeast(25))) },
        { set(cfg.copy(wallSpeed = (cfg.wallSpeed + 25).coerceAtMost(400), infillSpeed = (cfg.infillSpeed + 25).coerceAtMost(500))) })
    LcarsButton("Reset settings", color = s.frameAlt, height = 34.dp, enabled = enabled) { onReset() }
}

private class Filament(val name: String, val nozzle: Int, val bed: Int, val flow: Double, val fan: Int)
private val FILAMENTS = listOf(
    Filament("PLA", 220, 60, 21.0, 100),
    Filament("PETG", 250, 80, 12.0, 40),
    Filament("ABS", 260, 100, 18.0, 20),
    Filament("ASA", 260, 100, 18.0, 20),
    Filament("TPU", 230, 50, 3.6, 100),
)

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    LcarsLabel(text, color = LocalLcars.current.accent2, size = 14.sp, modifier = modifier.then(Modifier))
}

@Composable
private fun Stepper(label: String, value: String, enabled: Boolean, minus: () -> Unit, plus: () -> Unit) {
    val s = LocalLcars.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.weight(1f))
        LcarsButton("−", color = s.nav[1], height = 32.dp, enabled = enabled) { minus() }
        LcarsLabel(value, color = s.text, size = 16.sp, modifier = Modifier.widthIn(min = 70.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        LcarsButton("+", color = s.nav[1], height = 32.dp, enabled = enabled) { plus() }
    }
}
