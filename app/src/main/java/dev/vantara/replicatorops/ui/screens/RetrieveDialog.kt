package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.RetrieveState
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsProgress
import dev.vantara.replicatorops.ui.lcars.LocalLcars

/** Progress and result for Retrieve pattern (download from the printer). */
@Composable
fun RetrieveDialog(vm: AppViewModel) {
    val st by vm.retrieveState.collectAsState()
    if (st is RetrieveState.Idle) return
    val s = LocalLcars.current
    val colour = if (st is RetrieveState.Failed) s.alert else s.nav[1]

    AlertDialog(
        onDismissRequest = { if (st !is RetrieveState.Retrieving) vm.dismissRetrieve() },
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, colour, RoundedCornerShape(24.dp)),
        title = {
            LcarsLabel(
                when (st) {
                    is RetrieveState.Done -> "Pattern retrieved"
                    is RetrieveState.Failed -> "Retrieval failed"
                    else -> "Retrieve pattern"
                },
                color = colour, size = 22.sp, bold = true,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (val state = st) {
                    is RetrieveState.Retrieving -> {
                        LcarsLabel(state.name, color = s.text, size = 17.sp, maxLines = 3)
                        Spacer(Modifier.height(8.dp))
                        LcarsLabel("Transporting… ${(state.progress * 100).toInt()}%", color = s.accent2, size = 15.sp)
                        Spacer(Modifier.height(6.dp))
                        LcarsProgress(state.progress.toDouble())
                    }
                    is RetrieveState.Done -> {
                        LcarsLabel(state.name, color = s.text, size = 17.sp, maxLines = 3)
                        Spacer(Modifier.height(6.dp))
                        LcarsLabel("Saved to your device", color = s.accent, size = 15.sp)
                    }
                    is RetrieveState.Failed -> {
                        LcarsLabel(state.name, color = s.text, size = 17.sp, maxLines = 3)
                        Spacer(Modifier.height(6.dp))
                        LcarsLabel(state.message, color = s.alert, size = 15.sp, maxLines = 4)
                    }
                    RetrieveState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            if (st !is RetrieveState.Retrieving) {
                LcarsButton("Acknowledged", color = s.frameAlt) { vm.dismissRetrieve() }
            }
        },
    )
}
