package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import dev.vantara.replicatorops.data.StoresRepo
import dev.vantara.replicatorops.ui.lcars.LcarsFont
import java.text.NumberFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.data.HistoryJob
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.LogPeriod
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsChip
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsSectionHeader
import dev.vantara.replicatorops.ui.lcars.LocalLcars
import dev.vantara.replicatorops.ui.lcars.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Mission log: print history and totals from Moonraker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogScreen(vm: AppViewModel) {
    val s = LocalLcars.current
    val jobs by vm.history.collectAsState()
    val period by vm.logPeriod.collectAsState()
    val loading by vm.historyLoading.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    val spools by vm.spools.collectAsState()
    val perKg by vm.pricePerKg.collectAsState()
    var editPrice by remember { mutableStateOf(false) }
    fun cost(j: HistoryJob) = StoresRepo.printCost(j.filamentUsedMm, j.filamentType, spools, perKg)
    val totalCost = jobs.sumOf { cost(it) }

    val finished = jobs.filter { it.status in setOf("completed", "cancelled", "error", "klippy_shutdown", "klippy_disconnect") }
    val completed = finished.count { it.status == "completed" }
    val successRate = if (finished.isEmpty()) null else completed * 100 / finished.size
    val printTime = jobs.sumOf { it.printDuration }
    val filamentM = jobs.sumOf { it.filamentUsedMm } / 1000
    val byType = jobs.groupBy { it.filamentType ?: "Unknown" }
        .mapValues { (_, js) -> js.sumOf { it.filamentUsedMm } / 1000 }
        .filterValues { it > 0 }
        .toList().sortedByDescending { it.second }.take(5)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackLink("Files") { vm.back() }
        LcarsSectionHeader("Mission log", color = s.nav[1])
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            LogPeriod.entries.forEach { p ->
                LcarsChip(p.label, selected = p == period) { vm.setLogPeriod(p) }
            }
        }
        if (loading) LcarsLabel("Accessing records…", color = s.accent2, size = 13.sp, modifier = Modifier.padding(top = 6.dp))

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Total prints", jobs.size.toString(), Modifier.weight(1f))
            Stat("Success rate", successRate?.let { "$it%" } ?: "--", Modifier.weight(1f), s.accent)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Print time", formatHours(printTime), Modifier.weight(1f))
            Stat("Filament used", "%.1f m".format(filamentM), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Filament cost", money(totalCost), Modifier.weight(1f), s.accent)
            Stat("Per print", if (jobs.isEmpty()) "--" else money(totalCost / jobs.size), Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            LcarsLabel(
                "Costs use your spool prices, or ${money(perKg)}/kg",
                color = s.hint, size = 12.sp, modifier = Modifier.weight(1f),
            )
            LcarsButton("Set price", color = s.nav[1], height = 32.dp) { editPrice = true }
        }

        if (byType.isNotEmpty()) {
            LcarsSectionHeader("Filament by type", color = s.nav[2])
            val max = byType.first().second
            val colours = listOf(s.accent, s.nav[1], s.nav[2], s.nav[3], s.accent2)
            byType.forEachIndexed { i, (type, metres) ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LcarsLabel(type, color = s.accent2, size = 13.sp, modifier = Modifier.width(62.dp))
                    Box(Modifier.weight(1f).height(12.dp).background(s.dim)) {
                        Box(Modifier.fillMaxWidth((metres / max).toFloat()).fillMaxHeight().background(colours[i % colours.size]))
                    }
                    Spacer(Modifier.width(8.dp))
                    LcarsLabel("%.1f m".format(metres), color = s.text, size = 14.sp)
                }
            }
        }

        LcarsSectionHeader("Recent", color = s.frame)
        if (jobs.isEmpty() && !loading) LcarsLabel("No prints in this period", color = s.hint, size = 14.sp)
        (if (showAll) jobs else jobs.take(8)).forEach { JobRow(it, cost(it)) }
        if (jobs.size > 8 && !showAll) {
            Spacer(Modifier.height(10.dp))
            LcarsButton("Full history (${jobs.size})", color = s.nav[1], modifier = Modifier.fillMaxWidth()) { showAll = true }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (editPrice) PriceDialog(perKg, onSave = { vm.setPricePerKg(it); editPrice = false }) { editPrice = false }
}

/** Money in the phone's currency, e.g. "£1.23". */
fun money(v: Double): String = NumberFormat.getCurrencyInstance().format(v)

@Composable
private fun PriceDialog(current: Double, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    val s = LocalLcars.current
    var text by remember { mutableStateOf("%.2f".format(current)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, s.nav[1], RoundedCornerShape(24.dp)),
        title = { LcarsLabel("Filament price", color = s.nav[1], size = 20.sp, bold = true) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LcarsLabel("Price per kg, used for any type without a priced spool in Replicator stores.", color = s.hint, size = 13.sp, maxLines = 3)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' || it == ',' }.take(8) },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = LcarsFont, fontSize = 18.sp, color = s.text),
                    label = { LcarsLabel("Price per kg", color = s.accent2, size = 12.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            LcarsButton("Save", color = s.nav[1]) { text.replace(',', '.').toDoubleOrNull()?.let(onSave) ?: onDismiss() }
        },
        dismissButton = { LcarsButton("Cancel", color = s.frameAlt) { onDismiss() } },
    )
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: Color = LocalLcars.current.text) {
    val s = LocalLcars.current
    Row(modifier) {
        Box(Modifier.width(6.dp).height(48.dp).background(s.nav[1]))
        Spacer(Modifier.width(8.dp))
        Column {
            LcarsLabel(label, color = s.accent2, size = 12.sp)
            LcarsLabel(value, color = color, size = 26.sp, bold = true)
        }
    }
}

@Composable
private fun JobRow(j: HistoryJob, cost: Double) {
    val s = LocalLcars.current
    val ok = j.status == "completed"
    val status = when (j.status) {
        "completed" -> "Complete"
        "cancelled" -> "Cancelled"
        "in_progress" -> "In progress"
        else -> "Failed"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok || j.status == "in_progress") s.accent else s.alert))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            LcarsLabel(j.filename.substringAfterLast('/').removeSuffix(".gcode"), color = s.text, size = 14.sp)
            LcarsLabel(
                "$status · ${formatDuration(j.printDuration)} · ${money(cost)} · ${SimpleDateFormat("d MMM", Locale.UK).format(Date((j.startTime * 1000).toLong()))}",
                color = s.accent2, size = 12.sp,
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(s.dim))
}

private fun formatHours(seconds: Double): String {
    val total = seconds.toLong()
    return "%dh %02dm".format(total / 3600, (total % 3600) / 60)
}
