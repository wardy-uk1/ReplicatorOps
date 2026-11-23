package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.CommandInfo
import dev.vantara.replicatorops.ui.Voice
import dev.vantara.replicatorops.ui.VoiceCommand
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsConfirm
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsSectionHeader
import dev.vantara.replicatorops.ui.lcars.LocalLcars

/** Commands: every "Computer," command (tap to run) plus the printer's macros. */
@Composable
fun CommandsScreen(vm: AppViewModel) {
    val s = LocalLcars.current
    val p by vm.printer.collectAsState()
    var confirm by remember { mutableStateOf<CommandInfo?>(null) }

    fun run(c: CommandInfo) {
        Chirp.tap()
        if (c.confirm) confirm = c else vm.runCommand(c.command)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LcarsSectionHeader("Computer", color = s.accent)
        LcarsLabel(
            "Tap a command to run it, or tap the combadge and say it. You can start with \"Computer\".",
            color = s.hint, size = 13.sp, maxLines = 3,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsButton("Speak", color = s.accent, height = 38.dp) { vm.requestVoice() }
            LcarsButton("Type", color = s.nav[1], height = 38.dp) { vm.requestTyping() }
        }
        if (!p.ready) {
            Spacer(Modifier.height(6.dp))
            LcarsLabel("Printer offline: commands will report that they can't comply.", color = s.alert, size = 13.sp, maxLines = 2)
        }

        LcarsSectionHeader("Voice commands", color = s.nav[1])
        Voice.catalogue.forEach { c -> CommandRow(c) { run(c) } }

        Spacer(Modifier.height(6.dp))
        Subroutines(vm)
        Spacer(Modifier.height(24.dp))
    }

    confirm?.let { c ->
        LcarsConfirm(
            title = "${c.title}?",
            text = if (c.command == VoiceCommand.ABORT) "This cancels the current print. It can't be resumed." else c.description,
            confirmLabel = "Engage",
            onConfirm = {
                confirm = null
                if (c.command == VoiceCommand.ABORT) {
                    if (p.isBusy) { vm.cancel(); vm.speak("Replication aborted.") } else vm.speak("No replication in progress.")
                } else vm.runCommand(c.command)
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun CommandRow(c: CommandInfo, onRun: () -> Unit) {
    val s = LocalLcars.current
    val colour = if (c.confirm) s.alert else s.accent
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(2.dp, s.dim, RoundedCornerShape(14.dp))
            .clickable(onClick = onRun)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).height(40.dp).clip(RoundedCornerShape(3.dp)).background(colour))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            LcarsLabel(c.title, color = colour, size = 17.sp)
            LcarsLabel("Say: " + c.say.joinToString(" · ") { "\"$it\"" }, color = s.accent2, size = 12.sp)
            LcarsLabel(c.description, color = s.hint, size = 12.sp, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        LcarsButton("Run", color = colour, height = 34.dp) { onRun() }
    }
}
