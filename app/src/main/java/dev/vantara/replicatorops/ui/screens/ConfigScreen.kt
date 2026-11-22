package dev.vantara.replicatorops.ui.screens

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
    var host by remember { mutableStateOf(vm.prefs.host) }
    var apiKey by remember { mutableStateOf(vm.prefs.apiKey) }
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

        LcarsSectionHeader("Subspace link", color = s.nav[1])
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { LcarsLabel("Printer address", color = s.accent2, size = 13.sp) },
            placeholder = { LcarsLabel("192.168.1.50, name.ts.net or IPv6", color = s.dim) },
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
            color = s.dim, size = 13.sp, maxLines = 4,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsButton("Engage", color = s.accent) { vm.connect(host, apiKey) }
            LcarsButton("Disconnect", color = s.frameAlt, enabled = p.connection != Connection.DISCONNECTED) { vm.disconnect() }
        }

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
        LcarsButton("Firmware restart", color = s.nav[3], enabled = p.online) { vm.firmwareRestart() }
        if (p.boxRaw.isNotEmpty()) {
            LcarsSectionHeader("QIDI Box raw data", color = s.nav[3])
            LcarsLabel("Send me a screenshot of this if the Box screen looks wrong.", color = s.dim, size = 12.sp, maxLines = 2)
            p.boxRaw.forEach { (k, v) ->
                Text("$k = $v", color = s.text, fontSize = 12.sp, fontFamily = LcarsFont)
            }
        }

        UplinkSection(vm)

        LcarsSectionHeader("About", color = s.dim)
        LcarsLabel(
            "Replicator Ops ${vm.appVersion} · open-source Moonraker client. Not affiliated with QIDI Tech or Paramount.",
            color = s.accent2, size = 13.sp, maxLines = 4,
        )
        Spacer(Modifier.height(24.dp))
    }
}
