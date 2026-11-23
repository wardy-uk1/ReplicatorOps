package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.data.PatternOrigin
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.LoadState
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsFont
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsProgress
import dev.vantara.replicatorops.ui.lcars.LcarsReadout
import dev.vantara.replicatorops.ui.lcars.LocalLcars
import dev.vantara.replicatorops.ui.lcars.formatSize

/** Load pattern: confirm and upload a shared or picked file, or explain why it can't be printed. */
@Composable
fun LoadPatternDialog(vm: AppViewModel) {
    val st by vm.loadState.collectAsState()
    if (st is LoadState.Idle) return
    val s = LocalLcars.current
    val accent = when (st) {
        is LoadState.NeedsSlicing, is LoadState.WrongPrinter, is LoadState.Unsupported, is LoadState.Failed -> s.alert
        else -> s.nav[1]
    }
    val busy = st is LoadState.Uploading || st is LoadState.Receiving

    AlertDialog(
        onDismissRequest = { if (!busy) vm.dismissLoad() },
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, accent, RoundedCornerShape(24.dp)),
        title = {
            LcarsLabel(
                when (st) {
                    is LoadState.NeedsSlicing -> "Pattern requires slicing"
                    is LoadState.WrongPrinter -> "Pattern incompatible"
                    is LoadState.Unsupported -> "Unknown pattern format"
                    is LoadState.Failed -> "Load pattern failed"
                    else -> "Load pattern"
                },
                color = accent, size = 22.sp, bold = true,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (val state = st) {
                    LoadState.Receiving -> LcarsLabel("Scanning pattern…", color = s.accent2, size = 15.sp)
                    is LoadState.Ready -> {
                        FileName(state.pattern.name)
                        Spacer(Modifier.height(6.dp))
                        LcarsReadout("Size", formatSize(state.pattern.size))
                        LcarsReadout("Status", "Sliced · ready", valueColor = s.accent)
                        Spacer(Modifier.height(6.dp))
                        Body("Beam it to the printer's pattern buffer. You'll be asked before it prints.")
                    }
                    is LoadState.Uploading -> {
                        FileName(state.pattern.name)
                        Spacer(Modifier.height(8.dp))
                        LcarsLabel("Energising… ${(state.progress * 100).toInt()}%", color = s.accent2, size = 15.sp)
                        Spacer(Modifier.height(6.dp))
                        LcarsProgress(state.progress.toDouble())
                    }
                    is LoadState.NeedsSlicing -> {
                        FileName(state.pattern.name)
                        Spacer(Modifier.height(8.dp))
                        val source = when (state.pattern.origin) {
                            PatternOrigin.PRUSA -> "This is a PrusaSlicer project (typical of Printables): the 3D model and Prusa's settings, but no print instructions."
                            PatternOrigin.BAMBU -> "This is a Bambu Studio project (typical of MakerWorld): the 3D model and Bambu printer settings, but no print instructions for your printer."
                            PatternOrigin.ORCA_QIDI -> "This is a QIDI Studio / OrcaSlicer project that hasn't been sliced yet."
                            PatternOrigin.CURA -> "This is a Cura project: the 3D model, but no print instructions."
                            PatternOrigin.PLAIN_MODEL, PatternOrigin.UNKNOWN -> "This is a 3D model, not a sliced print file."
                        }
                        Body(
                            "$source The printer can't make it yet. Open it in QIDI Studio or OrcaSlicer, slice it for your " +
                                "printer, then share the .gcode or .gcode.3mf to Replicator Ops."
                        )
                    }
                    is LoadState.WrongPrinter -> {
                        FileName(state.pattern.name)
                        Spacer(Modifier.height(8.dp))
                        Body(
                            "This file was sliced in Bambu Studio for a Bambu printer. Its start routine, bed size and " +
                                "temperatures don't match your printer, so it hasn't been sent. Open the 3MF in QIDI Studio " +
                                "or OrcaSlicer, slice it for your printer, then share the result to Replicator Ops."
                        )
                    }
                    is LoadState.Unsupported -> {
                        FileName(state.name)
                        Spacer(Modifier.height(8.dp))
                        Body("Replicator Ops can load sliced .gcode and .gcode.3mf files.")
                    }
                    is LoadState.Failed -> Body(state.message)
                    LoadState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when (st) {
                is LoadState.Ready -> LcarsButton("Beam to printer", color = s.accent, sound = Chirp::confirm) { vm.beamPattern() }
                is LoadState.Failed -> if ((st as LoadState.Failed).pattern != null) {
                    LcarsButton("Retry", color = s.accent) { vm.beamPattern() }
                } else {
                    LcarsButton("Acknowledged", color = s.frameAlt) { vm.dismissLoad() }
                }
                is LoadState.NeedsSlicing, is LoadState.WrongPrinter, is LoadState.Unsupported ->
                    LcarsButton("Acknowledged", color = s.frameAlt) { vm.dismissLoad() }
                else -> Unit
            }
        },
        dismissButton = {
            if (st is LoadState.Ready || (st is LoadState.Failed && (st as LoadState.Failed).pattern != null)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LcarsButton("Cancel", color = s.frameAlt) { vm.dismissLoad() }
                }
            }
        },
    )
}

/** File names wrap onto up to three lines here rather than sliding. */
@Composable
private fun FileName(name: String) {
    val s = LocalLcars.current
    LcarsLabel(name, color = s.text, size = 17.sp, maxLines = 3)
}

@Composable
private fun Body(text: String) {
    val s = LocalLcars.current
    Text(text, color = s.text, fontFamily = LcarsFont, fontSize = 16.sp)
}
