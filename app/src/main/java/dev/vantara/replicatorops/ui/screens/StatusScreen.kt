package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import dev.vantara.replicatorops.ui.lcars.Chirp
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.vantara.replicatorops.data.PrinterState
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsBracketPanel
import dev.vantara.replicatorops.ui.lcars.LcarsConfirm
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsProgress
import dev.vantara.replicatorops.ui.lcars.LcarsSectionHeader
import dev.vantara.replicatorops.ui.lcars.LocalLcars
import dev.vantara.replicatorops.ui.lcars.formatDuration

@Composable
fun StatusScreen(vm: AppViewModel) {
    val s = LocalLcars.current
    val p by vm.printer.collectAsState()
    val meta by vm.meta.collectAsState()
    val temps by vm.temps.collectAsState()
    var confirmCancel by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var showThumb by remember { mutableStateOf(false) }
    var showLive by remember { mutableStateOf(false) }
    var showTargets by remember { mutableStateOf(false) }
    var confirmNext by remember { mutableStateOf(false) }

    val nozzle = p.heaters.firstOrNull { it.objectName == "extruder" }
    val bed = p.heaters.firstOrNull { it.objectName == "heater_bed" }
    val chamber = p.heaters.firstOrNull { "chamber" in it.objectName }
        ?: p.heaters.firstOrNull { it.objectName != "extruder" && it.objectName != "heater_bed" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LcarsLabel("Replicator", color = s.accent2, size = 15.sp)
            Spacer(Modifier.width(10.dp))
            LcarsLabel(stateLabel(p), color = stateColor(p), size = 22.sp, bold = true)
        }
        p.klippyMessage?.takeIf { !p.ready }?.let {
            LcarsLabel(it, color = s.alert, size = 14.sp, maxLines = 4)
        }

        // ---- Printer drawing with temperatures ---------------------------------
        LcarsSectionHeader("Q2C · Engineering", color = s.frame)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrinterDrawing()
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TempTile("Nozzle", nozzle?.temperature, nozzle?.target, s.accent)
                TempTile("Bed", bed?.temperature, bed?.target, s.nav[2])
                TempTile(chamber?.label ?: "Chamber", chamber?.temperature, chamber?.target, s.accent2)
            }
        }
        if (p.fans.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.fans.take(3).forEach { f ->
                    Row(
                        Modifier.weight(1f).height(30.dp)
                            .border(2.dp, s.nav[1], RoundedCornerShape(percent = 50)),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LcarsLabel(f.label, color = s.accent2, size = 13.sp)
                        Spacer(Modifier.width(5.dp))
                        LcarsLabel("${(f.speed * 100).toInt()}%", color = s.text, size = 13.sp)
                    }
                }
            }
        }

        // ---- Current print ----------------------------------------------------
        val file = p.filename
        if (file != null) {
            LcarsSectionHeader("Replication", color = s.nav[0])
            val m = meta[file]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(64.dp).background(Color(0xFF111111))
                        .clickable(enabled = m?.thumbnailUrl != null) { Chirp.tap(); showThumb = true },
                    contentAlignment = Alignment.Center,
                ) {
                    if (m?.thumbnailUrl != null) {
                        AsyncImage(
                            model = m.thumbnailUrl,
                            imageLoader = vm.imageLoader,
                            contentDescription = "Model thumbnail",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    LcarsLabel(file.substringAfterLast('/').removeSuffix(".3mf").removeSuffix(".gcode"), color = s.text, size = 16.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        LcarsLabel("%.1f%%".format(p.progress * 100), color = s.accent, size = 28.sp, bold = true)
                        Spacer(Modifier.width(8.dp))
                        val layer = if ((p.totalLayers ?: 0) > 0) "Layer ${p.currentLayer ?: 0}/${p.totalLayers} · " else ""
                        val left = formatDuration(p.remainingSeconds ?: m?.estimatedSeconds?.minus(p.printDuration))
                        LcarsLabel("$layer$left left", color = s.accent2, size = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            LcarsProgress(p.progress)
            Spacer(Modifier.height(8.dp))
            LivePrintView(vm, p, toolColours(m)) { showLive = true }
        } else {
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.isBusy) {
                if (p.isPaused) {
                    LcarsButton("Resume", color = s.nav[0], height = 38.dp) { vm.resume() }
                } else {
                    LcarsButton("Pause", color = s.nav[0], height = 38.dp) { vm.pause() }
                }
                LcarsButton("Cancel", color = s.alert, height = 38.dp) { confirmCancel = true }
            }
            LcarsButton("Camera", color = s.nav[1], height = 38.dp, enabled = p.online) { showCamera = true }
        }
        val queue by vm.queue.collectAsState()
        if (!p.isBusy && p.ready && queue.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LcarsBracketPanel(color = s.nav[2]) {
                LcarsLabel("Next in queue · ${queue.size} waiting", color = s.nav[2], size = 13.sp)
                LcarsLabel(queue.first().substringAfterLast('/').substringBeforeLast('.'), color = s.accent, size = 16.sp)
                Spacer(Modifier.height(4.dp))
                LcarsButton("Plate clear · engage next", color = s.accent, height = 36.dp) { confirmNext = true }
            }
        }
        if (p.isBusy && p.objects.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LcarsButton("Target lock", color = s.nav[3], height = 38.dp) { showTargets = true }
        }

        // ---- Thermal log ------------------------------------------------------
        LcarsSectionHeader("Thermal log", color = s.nav[2])
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LcarsLabel("— Nozzle", color = s.accent, size = 13.sp)
            LcarsLabel("— Bed", color = s.nav[2], size = 13.sp)
        }
        TempChart(temps)
        Spacer(Modifier.height(24.dp))
    }

    if (showCamera) CameraDialog(vm) { showCamera = false }

    if (showLive) {
        val st by vm.printLayers.collectAsState()
        val ready = st as? AppViewModel.LayersState.Ready
        val live = ready?.layers?.let { l -> val i = l.layerAt(p.filePosition); i to (l.layers.getOrNull(i)?.doneAt(p.filePosition) ?: 0) }
        LayerPreviewDialog(
            title = p.filename.orEmpty().substringAfterLast('/').removeSuffix(".3mf").removeSuffix(".gcode"),
            layers = ready?.layers,
            loading = (st as? AppViewModel.LayersState.Loading)?.what ?: (st as? AppViewModel.LayersState.Failed)?.message,
            onDismiss = { showLive = false },
            startLayer = live?.first ?: 0,
            toolColours = toolColours(p.filename?.let { meta[it] }),
            live = live,
            nozzle = p.position.takeIf { it.size >= 2 }?.let { androidx.compose.ui.geometry.Offset(it[0].toFloat(), it[1].toFloat()) },
        )
    }

    // Tap the print's picture to see it large (pinch to zoom).
    val thumbUrl = p.filename?.let { meta[it]?.thumbnailUrl }
    if (showThumb && thumbUrl != null) {
        AlertDialog(
            onDismissRequest = { showThumb = false },
            containerColor = Color.Black,
            modifier = Modifier.border(2.dp, s.nav[0], RoundedCornerShape(24.dp)),
            title = { LcarsLabel(p.filename.orEmpty().substringAfterLast('/').removeSuffix(".3mf").removeSuffix(".gcode"), color = s.nav[0], size = 18.sp, bold = true) },
            text = {
                ZoomableBox(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF111111))) {
                    AsyncImage(
                        model = thumbUrl,
                        imageLoader = vm.imageLoader,
                        contentDescription = "Model picture",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
            confirmButton = { LcarsButton("Close", color = s.frameAlt) { showThumb = false } },
        )
    }

    if (showTargets) TargetLockDialog(vm) { showTargets = false }
    if (confirmNext) {
        LcarsConfirm(
            title = "Build plate clear?",
            text = "Start the next file in the replication queue now. Make sure the plate is empty.",
            confirmLabel = "Engage",
            color = s.accent,
            onConfirm = { confirmNext = false; vm.startNextInQueue() },
            onDismiss = { confirmNext = false },
        )
    }

    if (confirmCancel) {
        LcarsConfirm(
            title = "Abort replication?",
            text = "This cancels the current print. It can't be resumed.",
            confirmLabel = "Abort",
            onConfirm = { confirmCancel = false; vm.cancel() },
            onDismiss = { confirmCancel = false },
        )
    }
}

fun stateLabel(p: PrinterState): String = when {
    !p.online -> "Offline"
    p.klippy != "ready" -> "Klipper ${p.klippy}"
    else -> when (p.printState) {
        "printing" -> "Replicating"
        "paused" -> "Paused"
        "complete" -> "Complete"
        "cancelled" -> "Cancelled"
        "error" -> "Error"
        else -> "Standing by"
    }
}

@Composable
fun stateColor(p: PrinterState): Color {
    val s = LocalLcars.current
    return when {
        !p.online || p.klippy != "ready" || p.printState == "error" -> s.alert
        p.isPrinting -> s.accent
        p.isPaused -> s.nav[0]
        else -> s.accent2
    }
}


/** Colours of the file's filaments (by tool number), for "By filament" in the layer views. */
private fun toolColours(m: dev.vantara.replicatorops.data.FileMeta?): List<Color> =
    m?.filamentColors?.map { c -> c?.let { Color(it) } ?: Color(0xFFFFCC66) }.orEmpty()

/**
 * Live print view: the layer being printed seen from above, filling in as the printer
 * reads the file, with the nozzle marked. Tap to look through every layer.
 */
@Composable
private fun LivePrintView(vm: AppViewModel, p: dev.vantara.replicatorops.data.PrinterState, colours: List<Color>, onOpen: () -> Unit) {
    val s = LocalLcars.current
    val st by vm.printLayers.collectAsState()
    when (val x = st) {
        is AppViewModel.LayersState.Ready -> {
            val l = x.layers
            val i = l.layerAt(p.filePosition)
            val done = l.layers.getOrNull(i)?.doneAt(p.filePosition) ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                LayerCanvas(
                    l, i, Modifier.size(150.dp).clickable { Chirp.tap(); onOpen() },
                    done = done,
                    colours = if (colours.size > 1) LayerColours.FILAMENT else LayerColours.TYPE,
                    toolColours = colours,
                    nozzle = p.position.takeIf { it.size >= 2 }?.let { androidx.compose.ui.geometry.Offset(it[0].toFloat(), it[1].toFloat()) },
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    LcarsLabel("Live path", color = s.nav[1], size = 14.sp, bold = true)
                    LcarsLabel("Layer ${i + 1} / ${l.layers.size}", color = s.text, size = 15.sp)
                    l.layers.getOrNull(i)?.let { LcarsLabel("%.2f mm · %d%% of layer".format(it.z, if (it.count > 0) done * 100 / it.count else 0), color = s.accent2, size = 12.sp) }
                    LcarsLabel("Tap to look through the layers", color = s.hint, size = 11.sp, maxLines = 2)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        is AppViewModel.LayersState.Loading -> { LcarsLabel(x.what, color = s.hint, size = 12.sp); Spacer(Modifier.height(6.dp)) }
        else -> Unit
    }
}
