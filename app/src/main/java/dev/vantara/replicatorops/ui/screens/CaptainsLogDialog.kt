package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.data.LogEntry
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsFont
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LocalLcars

/** "What's new" after an upgrade, in the same rounded LCARS panel as the camera view. Also opened from Config. */
@Composable
fun CaptainsLogDialog(vm: AppViewModel) {
    val entries by vm.logPopup.collectAsState()
    if (entries.isEmpty()) return
    val s = LocalLcars.current
    val upgraded = entries.firstOrNull()?.version == vm.appVersion && entries.size < vm.captainsLog.size

    LaunchedEffect(entries) { Chirp.confirm() }

    AlertDialog(
        onDismissRequest = { vm.dismissLog() },
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, s.nav[1], RoundedCornerShape(24.dp)),
        title = { LcarsLabel("Captain's Log", color = s.nav[1], size = 22.sp, bold = true) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                LcarsLabel(
                    if (upgraded) "Upgrade complete · now running ${vm.appVersion}" else "Software history",
                    color = s.accent2, size = 14.sp,
                )
                entries.forEachIndexed { i, e -> Entry(e, s.nav[i % s.nav.size]) }
            }
        },
        confirmButton = {
            LcarsButton("Acknowledged", color = s.frameAlt, sound = Chirp::confirm) { vm.dismissLog() }
        },
    )
}

@Composable
private fun Entry(e: LogEntry, marker: Color) {
    val s = LocalLcars.current
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(14.dp).height(18.dp).clip(RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)).background(marker))
        Spacer(Modifier.width(6.dp))
        LcarsLabel("Upgrade ${e.version}", color = marker, size = 18.sp, bold = true)
    }
    e.stardate?.let {
        LcarsLabel("Captain's log, stardate $it", color = s.accent2, size = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 2.dp))
    }
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        e.items.forEach { item ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.padding(top = 6.dp).width(10.dp).height(6.dp)
                        .clip(RoundedCornerShape(percent = 50)).background(marker)
                )
                Spacer(Modifier.width(8.dp))
                Text(item, color = s.text, fontFamily = LcarsFont, fontSize = 16.sp)
            }
        }
    }
}
