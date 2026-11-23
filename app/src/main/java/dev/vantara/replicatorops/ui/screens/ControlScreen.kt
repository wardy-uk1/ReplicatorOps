package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import dev.vantara.replicatorops.data.Heater
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.Screen
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsChip
import dev.vantara.replicatorops.ui.lcars.LcarsConfirm
import dev.vantara.replicatorops.ui.lcars.LcarsFont
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsSectionHeader
import dev.vantara.replicatorops.ui.lcars.LocalLcars
import dev.vantara.replicatorops.ui.lcars.formatTemp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlScreen(vm: AppViewModel) {
    val s = LocalLcars.current
    val p by vm.printer.collectAsState()
    var editHeater by remember { mutableStateOf<Heater?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf(10.0) }
    var console by remember { mutableStateOf("") }
    val canAct = p.ready
    val canMove = p.ready && !p.isBusy

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (!p.ready) {
            LcarsLabel("Printer not ready: ${stateLabel(p)}", color = s.alert, size = 15.sp, maxLines = 3)
        }

        // ---- Print ---------------------------------------------------------
        LcarsSectionHeader("Replication", color = s.frame)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.isPaused) {
                LcarsButton("Resume", color = s.nav[0], enabled = canAct) { vm.resume() }
            } else {
                LcarsButton("Pause", color = s.nav[0], enabled = canAct && p.isPrinting) { vm.pause() }
            }
            LcarsButton("Cancel", color = s.alert, enabled = canAct && p.isBusy) { confirm = "cancel" }
        }

        // ---- QIDI Box and spools --------------------------------------------
        BoxSection(vm, canAct && !p.isPrinting)
        Spacer(Modifier.height(8.dp))
        LcarsButton("Replicator stores", color = s.nav[3], modifier = Modifier.fillMaxWidth()) { vm.select(Screen.STORES) }

        // ---- Thermal -------------------------------------------------------
        LcarsSectionHeader("Thermal", color = s.nav[2])
        p.heaters.forEach { h ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clickable(enabled = canAct && h.canSet) { Chirp.tap(); editHeater = h },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LcarsLabel(h.label, color = s.accent2, size = 16.sp, modifier = Modifier.weight(1f))
                LcarsLabel(formatTemp(h.temperature), color = s.text, size = 20.sp)
                if (h.canSet) {
                    Spacer(Modifier.width(8.dp))
                    LcarsLabel(
                        "→ ${formatTemp(h.target ?: 0.0)}",
                        color = if ((h.target ?: 0.0) > 0) s.accent else s.dim,
                        size = 20.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsButton("PLA", color = s.nav[0], enabled = canAct) { vm.preheat(215, 60) }
            LcarsButton("PETG", color = s.nav[3], enabled = canAct) { vm.preheat(245, 75) }
            LcarsButton("ABS", color = s.nav[1], enabled = canAct) { vm.preheat(260, 100) }
            LcarsButton("Cool down", color = s.accent2, enabled = canAct) { vm.coolDown() }
        }
        LcarsLabel("Tap a heater to set its target", color = s.hint, size = 13.sp, modifier = Modifier.padding(top = 4.dp))

        // ---- Fan, speed, flow ----------------------------------------------
        LcarsSectionHeader("Atmospherics · air system", color = s.nav[1])
        p.partFan?.let { fan ->
            var fanPct by remember { mutableFloatStateOf((fan * 100).toFloat()) }
            LaunchedEffect(fan) { fanPct = (fan * 100).toFloat() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LcarsLabel("Part fan", color = s.accent2, size = 16.sp, modifier = Modifier.weight(1f))
                LcarsLabel("${fanPct.toInt()}%", color = s.text, size = 20.sp)
            }
            Slider(
                value = fanPct,
                onValueChange = { fanPct = it },
                onValueChangeFinished = { vm.setPartFan(fanPct.toInt()) },
                valueRange = 0f..100f,
                enabled = canAct,
                colors = SliderDefaults.colors(thumbColor = s.accent, activeTrackColor = s.accent, inactiveTrackColor = s.dim),
            )
        }
        // Air system: every other fan the printer reports (aux, chamber circulation, exhaust…).
        p.fans.filter { it.label != "Part" && it.objectName.startsWith("fan_generic ") }.forEach { f ->
            var pct by remember(f.objectName) { mutableFloatStateOf((f.speed * 100).toFloat()) }
            LaunchedEffect(f.speed) { pct = (f.speed * 100).toFloat() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LcarsLabel("${f.label} fan", color = s.accent2, size = 16.sp, modifier = Modifier.weight(1f))
                LcarsLabel("${pct.toInt()}%", color = s.text, size = 20.sp)
            }
            Slider(
                value = pct,
                onValueChange = { pct = it },
                onValueChangeFinished = { vm.setFan(f.objectName, pct.toInt()) },
                valueRange = 0f..100f,
                enabled = canAct,
                colors = SliderDefaults.colors(thumbColor = s.nav[1], activeTrackColor = s.nav[1], inactiveTrackColor = s.dim),
            )
        }
        FactorRow("Speed", (p.speedFactor * 100).toInt(), canAct) { vm.setSpeedFactor(it) }
        FactorRow("Flow", (p.extrudeFactor * 100).toInt(), canAct) { vm.setFlowFactor(it) }

        // ---- Motion --------------------------------------------------------
        LcarsSectionHeader("Helm", color = s.nav[3])
        if (p.position.size >= 3) {
            LcarsLabel(
                "X %.1f  Y %.1f  Z %.2f   homed: %s".format(p.position[0], p.position[1], p.position[2], p.homedAxes.ifBlank { "none" }),
                color = s.text, size = 14.sp,
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.1, 1.0, 10.0, 50.0).forEach { v ->
                LcarsChip(if (v < 1) "0.1" else v.toInt().toString(), selected = step == v) { step = v }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LcarsButton("Y+", color = s.nav[0], enabled = canMove) { vm.jog('Y', step) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LcarsButton("X-", color = s.nav[0], enabled = canMove) { vm.jog('X', -step) }
                    LcarsButton("X+", color = s.nav[0], enabled = canMove) { vm.jog('X', step) }
                }
                LcarsButton("Y-", color = s.nav[0], enabled = canMove) { vm.jog('Y', -step) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LcarsButton("Z+", color = s.nav[1], enabled = canMove) { vm.jog('Z', step.coerceAtMost(10.0)) }
                LcarsButton("Z-", color = s.nav[1], enabled = canMove) { vm.jog('Z', -step.coerceAtMost(10.0)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        // Bed up/down, like the printer's own Bed buttons.
        LcarsLabel("Bed", color = s.accent2, size = 15.sp)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsButton("▲ 10", color = s.nav[2], enabled = canMove) { vm.moveBed(10.0) }
            LcarsButton("▲ 1", color = s.nav[2], enabled = canMove) { vm.moveBed(1.0) }
            LcarsButton("▼ 1", color = s.nav[2], enabled = canMove) { vm.moveBed(-1.0) }
            LcarsButton("▼ 10", color = s.nav[2], enabled = canMove) { vm.moveBed(-10.0) }
        }
        LcarsLabel("▲ raises the bed towards the nozzle (mm). Home Z first.", color = s.hint, size = 12.sp, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsButton("Home all", color = s.accent, enabled = canMove) { vm.home() }
            LcarsButton("Unlock steppers", color = s.frameAlt, enabled = canMove) { vm.motorsOff() }
        }

        // ---- Console -------------------------------------------------------
        LcarsSectionHeader("Console", color = s.accent2)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = console,
                onValueChange = { console = it },
                singleLine = true,
                placeholder = { LcarsLabel("G-code", color = s.hint) },
                textStyle = TextStyle(fontFamily = LcarsFont, fontSize = 17.sp, color = s.text),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            LcarsButton("Send", enabled = canAct && console.isNotBlank()) {
                vm.gcode(console.trim(), "Sent")
                console = ""
            }
        }

        LcarsSectionHeader("Emergency", color = s.alert)
        LcarsButton("Emergency stop", color = s.alert, enabled = p.online, sound = Chirp::alert, modifier = Modifier.fillMaxWidth()) {
            confirm = "estop"
        }
        LcarsLabel("Halts Klipper at once. You'll need a firmware restart afterwards.", color = s.hint, size = 14.sp, maxLines = 3)
        Spacer(Modifier.height(24.dp))
    }

    editHeater?.let { h ->
        HeaterDialog(h, onSet = { vm.setTemperature(h.heaterName!!, it); editHeater = null }, onDismiss = { editHeater = null })
    }

    when (confirm) {
        "cancel" -> LcarsConfirm(
            title = "Abort replication?",
            text = "This cancels the current print. It can't be resumed.",
            confirmLabel = "Abort",
            onConfirm = { confirm = null; vm.cancel() },
            onDismiss = { confirm = null },
        )
        "estop" -> LcarsConfirm(
            title = "Emergency stop?",
            text = "Klipper will shut down immediately and any print will be lost.",
            confirmLabel = "Stop",
            onConfirm = { confirm = null; vm.emergencyStop() },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun FactorRow(label: String, percent: Int, enabled: Boolean, onSet: (Int) -> Unit) {
    val s = LocalLcars.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LcarsLabel(label, color = s.accent2, size = 16.sp, modifier = Modifier.weight(1f))
        LcarsButton("-5", color = s.nav[2], enabled = enabled, height = 36.dp) { onSet(percent - 5) }
        Spacer(Modifier.width(8.dp))
        LcarsLabel("$percent%", color = s.text, size = 20.sp)
        Spacer(Modifier.width(8.dp))
        LcarsButton("+5", color = s.nav[2], enabled = enabled, height = 36.dp) { onSet(percent + 5) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaterDialog(h: Heater, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    val s = LocalLcars.current
    var text by remember { mutableStateOf(((h.target ?: 0.0).toInt()).toString()) }
    val presets = when (h.heaterName) {
        "extruder" -> listOf(0, 190, 215, 245, 260, 280)
        "heater_bed" -> listOf(0, 55, 60, 75, 90, 100)
        else -> listOf(0, 40, 50, 60)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, s.nav[2], RoundedCornerShape(24.dp)),
        title = { LcarsLabel("${h.label} target", color = s.nav[2], size = 22.sp, bold = true) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(3) },
                    singleLine = true,
                    suffix = { LcarsLabel("°C", color = s.text) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = TextStyle(fontFamily = LcarsFont, fontSize = 22.sp, color = s.text),
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { t ->
                        LcarsChip(if (t == 0) "Off" else "$t", selected = text == t.toString()) { text = t.toString() }
                    }
                }
            }
        },
        confirmButton = {
            LcarsButton("Set", color = s.nav[2], sound = Chirp::confirm) { onSet(text.toIntOrNull() ?: 0) }
        },
        dismissButton = { LcarsButton("Cancel", color = s.frameAlt) { onDismiss() } },
    )
}
