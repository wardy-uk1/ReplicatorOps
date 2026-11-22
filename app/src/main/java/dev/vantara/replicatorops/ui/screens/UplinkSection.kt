package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.UplinkState
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsFont
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsProgress
import dev.vantara.replicatorops.ui.lcars.LcarsReadout
import dev.vantara.replicatorops.ui.lcars.LcarsSectionHeader
import dev.vantara.replicatorops.ui.lcars.LocalLcars

/** Starbase Uplink section on the Config screen: check for, download and install updates. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UplinkSection(vm: AppViewModel) {
    val s = LocalLcars.current
    val st by vm.uplinkState.collectAsState()

    LcarsSectionHeader("Starbase Uplink", color = s.nav[1])
    LcarsReadout("Software version", vm.appVersion)

    when (val state = st) {
        UplinkState.Idle -> LcarsLabel("Standing by for contact with Starbase", color = s.accent2, size = 14.sp, maxLines = 2)
        UplinkState.Checking -> LcarsLabel("Establishing uplink…", color = s.accent2, size = 14.sp)
        UplinkState.Current -> LcarsLabel("All systems current. No upgrades available.", color = s.accent, size = 14.sp, maxLines = 2)
        is UplinkState.Available -> {
            LcarsLabel("Software upgrade ${state.upgrade.version} available", color = s.accent, size = 17.sp, bold = true, maxLines = 2)
            ReleaseNotes(state.upgrade.notes)
        }
        is UplinkState.Downloading -> {
            LcarsLabel("Receiving transmission… ${(state.progress * 100).toInt()}%", color = s.accent2, size = 14.sp)
            Spacer(Modifier.height(6.dp))
            LcarsProgress(state.progress.toDouble())
        }
        is UplinkState.Ready -> LcarsLabel("Transmission complete. Upgrade ${state.upgrade.version} ready to install.", color = s.accent, size = 14.sp, maxLines = 2)
        is UplinkState.Failed -> LcarsLabel(state.message, color = s.alert, size = 14.sp, maxLines = 3)
    }

    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val state = st) {
            is UplinkState.Available -> {
                LcarsButton("Download & install", color = s.accent, sound = Chirp::confirm) { vm.installUpgrade() }
                LcarsButton("Release notes", color = s.frameAlt) { vm.openReleaseNotes() }
            }
            is UplinkState.Ready -> {
                LcarsButton("Install upgrade", color = s.accent, sound = Chirp::confirm) { vm.installUpgrade() }
            }
            is UplinkState.Failed -> {
                if (state.upgrade != null) {
                    LcarsButton("Retry download", color = s.accent) { vm.installUpgrade() }
                } else {
                    LcarsButton("Retry uplink", color = s.nav[1]) { vm.checkUplink() }
                }
            }
            is UplinkState.Downloading -> Unit
            else -> {
                LcarsButton(
                    "Uplink to Starbase",
                    color = s.nav[1],
                    enabled = state !is UplinkState.Checking,
                ) { vm.checkUplink() }
            }
        }
    }
}

@Composable
private fun ReleaseNotes(notes: String) {
    val s = LocalLcars.current
    val text = notes.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("**Full Changelog") }
        .take(6)
        .joinToString("\n")
    if (text.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(text, color = s.text, fontFamily = LcarsFont, fontSize = 14.sp)
    }
}

/** Shown once at launch when the background check finds a newer build. */
@Composable
fun UplinkAlert(vm: AppViewModel) {
    val s = LocalLcars.current
    val show by vm.uplinkAlert.collectAsState()
    val st by vm.uplinkState.collectAsState()
    val upgrade = (st as? UplinkState.Available)?.upgrade
    if (!show || upgrade == null) return

    AlertDialog(
        onDismissRequest = { vm.dismissUplinkAlert() },
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, s.nav[1], RoundedCornerShape(24.dp)),
        title = { LcarsLabel("Starbase Uplink", color = s.nav[1], size = 22.sp, bold = true) },
        text = {
            Column {
                LcarsLabel("Incoming transmission", color = s.accent2, size = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Software upgrade ${upgrade.version} is available from Starbase. You're running ${vm.appVersion}.",
                    color = s.text, fontFamily = LcarsFont, fontSize = 17.sp,
                )
                ReleaseNotes(upgrade.notes)
            }
        },
        confirmButton = {
            LcarsButton("Install", color = s.accent, sound = Chirp::confirm) { vm.installUpgrade() }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LcarsButton("Later", color = s.frameAlt) { vm.dismissUplinkAlert() }
            }
        },
    )
}
