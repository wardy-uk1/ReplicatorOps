package dev.vantara.replicatorops.ui.screens

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
