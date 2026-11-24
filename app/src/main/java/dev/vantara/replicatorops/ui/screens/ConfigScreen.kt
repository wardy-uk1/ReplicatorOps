package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import dev.vantara.replicatorops.ui.lcars.Chirp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import dev.vantara.replicatorops.data.NotificationIcon
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.graphics.Color
import dev.vantara.replicatorops.data.PrinterProfile
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.data.Connection
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.lcars.Century24
import dev.vantara.replicatorops.ui.lcars.Century25
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsChip
import dev.vantara.replicatorops.ui.lcars.LcarsBracketPanel
import dev.vantara.replicatorops.ui.lcars.LcarsConfirm
import dev.vantara.replicatorops.ui.lcars.LcarsFont
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsReadout
import dev.vantara.replicatorops.ui.lcars.LcarsSectionHeader
import dev.vantara.replicatorops.ui.lcars.LocalLcars

@Composable
fun ConfigScreen(vm: AppViewModel) {
    val s = LocalLcars.current
    val p by vm.printer.collectAsState()
    val chirps by vm.chirps.collectAsState()
    val active by vm.activePrinter.collectAsState()
    val fleet by vm.fleet.collectAsState()
    var host by remember(active?.id) { mutableStateOf(active?.host.orEmpty()) }
    var apiKey by remember(active?.id) { mutableStateOf(active?.apiKey.orEmpty()) }
    var addingPrinter by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PrinterProfile?>(null) }
    var removing by remember { mutableStateOf<PrinterProfile?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf<String?>(null) }
    val field = TextStyle(fontFamily = LcarsFont, fontSize = 18.sp, color = s.text)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LcarsSectionHeader("LCARS System", color = s.frame)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsChip(Century24.name, selected = s.id == "24") { vm.setStyle("24") }
            LcarsChip(Century25.name, selected = s.id == "25") { vm.setStyle("25") }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsChip("Chirps on", selected = chirps) { vm.setChirps(true) }
            LcarsChip("Chirps off", selected = !chirps) { vm.setChirps(false) }
        }
        Spacer(Modifier.height(10.dp))
        LcarsLabel("Notification symbol", color = s.accent2, size = 13.sp)
        val notifIcon by vm.notificationIcon.collectAsState()
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NotificationIcon.entries.forEach { icon ->
                val on = icon == notifIcon
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { Chirp.tap(); vm.setNotificationIcon(icon) }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(if (on) s.accent else s.dim)
                            .border(if (on) 2.dp else 0.dp, s.text, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(icon.res), contentDescription = icon.label, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    LcarsLabel(icon.label, color = if (on) s.accent else s.hint, size = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        ComputerVoiceSection(vm)

        LcarsSectionHeader("Sensor sweep", color = s.nav[0])
        val sweep by vm.sweep.collectAsState()
        LcarsLabel(
            "Finds QIDI and other Klipper printers on your Wi-Fi.",
            color = s.hint, size = 13.sp, maxLines = 2,
        )
        Spacer(Modifier.height(6.dp))
        LcarsButton(if (sweep.running) "Sweeping…" else "Sensor sweep", color = s.nav[0], enabled = !sweep.running) { vm.sensorSweep() }
        if (sweep.running) {
            LcarsLabel("Scanning local network… ${sweep.found.size} found", color = s.accent2, size = 13.sp)
        } else if (sweep.ran && sweep.found.isEmpty()) {
            LcarsLabel(
                "No printers detected. Check the phone is on the same Wi-Fi as the printer, or type the address below.",
                color = s.alert, size = 13.sp, maxLines = 3,
            )
        }
        sweep.found.forEach { f ->
            Spacer(Modifier.height(6.dp))
            LcarsBracketPanel(color = s.nav[0]) {
                LcarsReadout(f.name, f.address, valueColor = s.accent)
                if (f.needsKey) LcarsLabel("Needs an API key", color = s.hint, size = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LcarsButton("Lock on", color = s.accent, height = 36.dp) {
                        vm.addPrinter(f.name, f.address, "")
                    }
                }
            }
        }

        LcarsSectionHeader("Fleet registry", color = s.nav[2])
        if (fleet.isEmpty()) {
            LcarsLabel("No printers yet. Use Sensor sweep, or type an address below and Engage.", color = s.hint, size = 13.sp, maxLines = 3)
        }
        fleet.forEach { pr ->
            val isActive = pr.id == active?.id
            LcarsBracketPanel(color = if (isActive) s.accent else s.nav[2]) {
                LcarsReadout(pr.name, if (isActive) "Active" else "", valueColor = s.accent)
                LcarsLabel(pr.host, color = s.accent2, size = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!isActive) LcarsButton("Switch", color = s.accent, height = 34.dp) { vm.switchPrinter(pr.id) }
                    LcarsButton("Rename", color = s.nav[2], height = 34.dp) { renaming = pr }
                    LcarsButton("Remove", color = s.alert, height = 34.dp) { removing = pr }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        LcarsButton("Add printer", color = s.nav[2]) { addingPrinter = true }
        LcarsLabel("Tip: tap the title bar to switch printers from any screen.", color = s.hint, size = 12.sp, maxLines = 2)

        LcarsSectionHeader("Subspace link${active?.let { " · " + it.name } ?: ""}", color = s.nav[1])
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { LcarsLabel("Printer address", color = s.accent2, size = 13.sp) },
            placeholder = { LcarsLabel("192.168.1.50, name.ts.net or IPv6", color = s.hint) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            textStyle = field,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { LcarsLabel("API key (optional)", color = s.accent2, size = 13.sp) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            textStyle = field,
            modifier = Modifier.fillMaxWidth(),
        )
        LcarsLabel(
            "No port = port 80 (QIDI's web UI). Add :7125 to talk to Moonraker directly. IPv6 works as typed, or as [address]:7125 with a port.",
            color = s.hint, size = 13.sp, maxLines = 4,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsButton("Engage", color = s.accent) { vm.connect(host, apiKey) }
            LcarsButton("Disconnect", color = s.frameAlt, enabled = p.connection != Connection.DISCONNECTED) { vm.disconnect() }
        }

        RedAlertSection(vm)

        LcarsSectionHeader("Diagnostics", color = s.nav[2])
        LcarsReadout(
            "Link",
            when (p.connection) {
                Connection.CONNECTED -> "Established"
                Connection.CONNECTING -> "Connecting"
                Connection.DISCONNECTED -> "Offline"
            },
            valueColor = if (p.online) s.accent else s.alert,
        )
        p.connectionError?.let { LcarsLabel(it, color = s.alert, size = 14.sp, maxLines = 4) }
        LcarsReadout("Klipper", p.klippy)
        p.klippyMessage?.let { LcarsLabel(it, color = s.text, size = 14.sp, maxLines = 6) }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsButton("Klipper restart", color = s.nav[0], enabled = p.online) {
                if (p.isBusy) confirmRestart = "klipper" else vm.klipperRestart()
            }
            LcarsButton("Firmware restart", color = s.nav[3], enabled = p.online) {
                if (p.isBusy) confirmRestart = "firmware" else vm.firmwareRestart()
            }
        }
        LcarsLabel(
            "Klipper restart reloads printer.cfg. Firmware restart also resets the printer's boards.",
            color = s.hint, size = 12.sp, maxLines = 3,
        )
        if (p.boxRaw.isNotEmpty()) {
            LcarsSectionHeader("QIDI Box raw data", color = s.nav[3])
            LcarsLabel("Send me a screenshot of this if the Box screen looks wrong.", color = s.hint, size = 12.sp, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            LcarsButton(
                if (showRaw) "Hide raw data ▲" else "Show raw data (${p.boxRaw.size}) ▼",
                color = s.nav[3],
                height = 38.dp,
            ) { showRaw = !showRaw }
            if (showRaw) {
                Spacer(Modifier.height(6.dp))
                p.boxRaw.forEach { (k, v) ->
                    Text("$k = $v", color = s.text, fontSize = 12.sp, fontFamily = LcarsFont)
                }
            }
        }

        UplinkSection(vm)

        LcarsSectionHeader("About", color = s.hint)
        LcarsLabel(
            "Replicator Ops ${vm.appVersion} · Moonraker client for QIDI printers. Not affiliated with QIDI Tech or Paramount.",
            color = s.accent2, size = 13.sp, maxLines = 4,
        )
        Spacer(Modifier.height(24.dp))
    }

    if (addingPrinter) PrinterDialog(null, onSave = { n, h, k -> vm.addPrinter(n, h, k); addingPrinter = false }) { addingPrinter = false }
    renaming?.let { pr -> PrinterDialog(pr, onSave = { n, _, _ -> vm.renamePrinter(pr.id, n); renaming = null }) { renaming = null } }
    removing?.let { pr ->
        LcarsConfirm(
            title = "Remove ${pr.name}?",
            text = "${pr.host} will be removed from the fleet registry. Nothing on the printer changes.",
            confirmLabel = "Remove",
            onConfirm = { vm.removePrinter(pr.id); removing = null },
            onDismiss = { removing = null },
        )
    }

    confirmRestart?.let { kind ->
        LcarsConfirm(
            title = "Restart during a print?",
            text = "A print is running. Restarting ${if (kind == "klipper") "Klipper" else "the firmware"} will stop it and it can't be resumed.",
            confirmLabel = "Restart",
            onConfirm = {
                confirmRestart = null
                if (kind == "klipper") vm.klipperRestart() else vm.firmwareRestart()
            },
            onDismiss = { confirmRestart = null },
        )
    }
}

/** Add a printer (name, address, key) or rename one ([existing] set: only the name is editable). */
@Composable
private fun PrinterDialog(existing: PrinterProfile?, onSave: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    val s = LocalLcars.current
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var key by remember { mutableStateOf("") }
    val field = TextStyle(fontFamily = LcarsFont, fontSize = 18.sp, color = s.text)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, s.nav[2], RoundedCornerShape(24.dp)),
        title = { LcarsLabel(if (existing == null) "Register printer" else "Rename printer", color = s.nav[2], size = 20.sp, bold = true) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, textStyle = field,
                    label = { LcarsLabel("Name (e.g. Q2C workshop)", color = s.accent2, size = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (existing == null) {
                    OutlinedTextField(
                        value = host, onValueChange = { host = it }, singleLine = true, textStyle = field,
                        label = { LcarsLabel("Address", color = s.accent2, size = 12.sp) },
                        placeholder = { LcarsLabel("192.168.1.50, name.ts.net or IPv6", color = s.hint) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = key, onValueChange = { key = it }, singleLine = true, textStyle = field,
                        label = { LcarsLabel("API key (optional)", color = s.accent2, size = 12.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            LcarsButton("Save", color = s.nav[2], enabled = existing != null || host.isNotBlank()) { onSave(name, host, key) }
        },
        dismissButton = { LcarsButton("Cancel", color = s.frameAlt) { onDismiss() } },
    )
}


/** Computer voice: which of the phone's voices speaks replies, its pitch and pace, and the chirp. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ComputerVoiceSection(vm: AppViewModel) {
    val s = LocalLcars.current
    val v by vm.voiceSettings.collectAsState()
    val voices by vm.voices.collectAsState()
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadVoices() }
    LcarsSectionHeader("Computer voice", color = s.nav[1])
    LcarsLabel("How the computer speaks replies to voice and typed commands.", color = s.hint, size = 12.sp, maxLines = 2)
    val current = voices.firstOrNull { it.name == v.name }?.label ?: "Phone's default (British English)"
    LcarsReadout("Voice", current)
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LcarsButton("Choose voice", color = s.nav[1], height = 34.dp) { picking = true }
        LcarsButton("Hear it", color = s.accent, height = 34.dp) { vm.previewVoice() }
    }
    LcarsLabel("Pitch", color = s.accent2, size = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0.82f to "Lower", 0.92f to "Computer", 1.0f to "Natural").forEach { (p, l) ->
            LcarsChip(l, selected = kotlin.math.abs(v.pitch - p) < 0.01f) { vm.setVoicePitch(p) }
        }
    }
    LcarsLabel("Pace", color = s.accent2, size = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0.82f to "Measured", 0.9f to "Computer", 1.0f to "Natural").forEach { (r, l) ->
            LcarsChip(l, selected = kotlin.math.abs(v.rate - r) < 0.01f) { vm.setVoiceRate(r) }
        }
    }
    LcarsLabel("Chirp before replies", color = s.accent2, size = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LcarsChip("On", selected = v.chirp) { vm.setVoiceChirp(true) }
        LcarsChip("Off", selected = !v.chirp) { vm.setVoiceChirp(false) }
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            containerColor = Color.Black,
            modifier = Modifier.border(2.dp, s.nav[1], RoundedCornerShape(24.dp)),
            title = { LcarsLabel("Choose voice", color = s.nav[1], size = 20.sp, bold = true) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (voices.isEmpty()) LcarsLabel("No English voices found. Install Speech Services by Google from the Play Store for more voices.",
                        color = s.hint, size = 13.sp, maxLines = 4)
                    LcarsLabel("Tap to hear and choose. Download more voices in Android Settings → Text-to-speech.", color = s.hint, size = 12.sp, maxLines = 3)
                    Spacer(Modifier.height(6.dp))
                    LcarsChip("Phone's default", selected = v.name == null) { vm.setVoice(null); vm.previewVoice() }
                    voices.forEach { o ->
                        Spacer(Modifier.height(6.dp))
                        LcarsChip(o.label, selected = v.name == o.name) { vm.setVoice(o.name); vm.previewVoice() }
                    }
                }
            },
            confirmButton = { LcarsButton("Done", color = s.nav[1]) { picking = false } },
        )
    }
}
