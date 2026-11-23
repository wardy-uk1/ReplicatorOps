package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsConfirm
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LocalLcars

/** Replication queue: reorder, remove, clear, and start the next file. */
@Composable
fun QueueDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val s = LocalLcars.current
    val queue by vm.queue.collectAsState()
    val p by vm.printer.collectAsState()
    var confirmNext by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, s.nav[2], RoundedCornerShape(24.dp)),
        title = { LcarsLabel("Replication queue", color = s.nav[2], size = 20.sp, bold = true) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (queue.isEmpty()) {
                    LcarsLabel("Queue empty. Open a pattern and tap Add to queue.", color = s.hint, size = 14.sp, maxLines = 3)
                } else {
                    LcarsLabel(
                        "When a print finishes, clear the build plate, then tap Engage next here or Next on the notification.",
                        color = s.hint, size = 12.sp, maxLines = 3,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                queue.forEachIndexed { i, path ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        LcarsLabel("${i + 1}", color = if (i == 0) s.accent else s.hint, size = 16.sp, modifier = Modifier.width(22.dp))
                        LcarsLabel(path.substringAfterLast('/').substringBeforeLast('.'), color = if (i == 0) s.accent else s.text, size = 15.sp, modifier = Modifier.weight(1f))
                        LcarsButton("▲", color = s.nav[1], height = 30.dp, enabled = i > 0) { vm.moveInQueue(i, -1) }
                        Spacer(Modifier.width(4.dp))
                        LcarsButton("▼", color = s.nav[1], height = 30.dp, enabled = i < queue.size - 1) { vm.moveInQueue(i, 1) }
                        Spacer(Modifier.width(4.dp))
                        LcarsButton("✕", color = s.alert, height = 30.dp) { vm.removeFromQueue(i) }
                    }
                }
                if (queue.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LcarsButton("Clear queue", color = s.alert, height = 36.dp) { vm.clearQueue() }
                    }
                    if (p.isBusy) LcarsLabel("Next starts after the current print and a plate check.", color = s.hint, size = 12.sp, maxLines = 2)
                }
            }
        },
        confirmButton = {
            LcarsButton("Engage next", color = s.accent, enabled = queue.isNotEmpty() && p.ready && !p.isBusy) { confirmNext = true }
        },
        dismissButton = { LcarsButton("Close", color = s.frameAlt) { onDismiss() } },
    )

    if (confirmNext) {
        LcarsConfirm(
            title = "Build plate clear?",
            text = "Start ${queue.firstOrNull()?.substringAfterLast('/') ?: "the next file"} now. Make sure the plate is empty.",
            confirmLabel = "Engage",
            color = s.accent,
            onConfirm = { confirmNext = false; vm.startNextInQueue() },
            onDismiss = { confirmNext = false },
        )
    }
}
