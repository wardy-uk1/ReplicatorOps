package dev.vantara.replicatorops.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsChip
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsReadout
import dev.vantara.replicatorops.ui.lcars.LcarsSectionHeader
import dev.vantara.replicatorops.ui.lcars.LocalLcars

fun notificationsAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun batteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

/** Config > Red Alert: background printer watch and notifications. */
@Composable
fun RedAlertSection(vm: AppViewModel) {
    val s = LocalLcars.current
    val context = LocalContext.current
    val on by vm.redAlert.collectAsState()
    // Re-read permission and battery state whenever we come back to the app.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val allowed = remember(tick) { notificationsAllowed(context) }
    val unrestricted = remember(tick) { batteryUnrestricted(context) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    LcarsSectionHeader("Red Alert", color = s.alert)
    LcarsLabel(
        "Keeps a link to the printer in the background. Alerts when a print finishes, fails, is cancelled or pauses (filament runout), and shows live progress.",
        color = s.hint, size = 13.sp, maxLines = 5,
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LcarsChip("Online", selected = on) {
            vm.setRedAlert(true)
            if (!notificationsAllowed(context) && Build.VERSION.SDK_INT >= 33) {
                askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        LcarsChip("Offline", selected = !on) { vm.setRedAlert(false) }
    }
    Spacer(Modifier.height(10.dp))
    LcarsLabel(
        "Alert levels: Yellow (you paused, a heater is slow, the Box is damp), Red (print failed or paused itself, printer error) " +
            "and Double red (Klipper shut down, e.g. a heater fault, or the printer lost mid-print). The whole app turns colour until you acknowledge.",
        color = s.hint, size = 12.sp, maxLines = 6,
    )
    val dnd by vm.alertThroughDnd.collectAsState()
    Spacer(Modifier.height(6.dp))
    LcarsLabel("Double red alert sounds through Do Not Disturb (alarm volume)", color = s.accent2, size = 13.sp, maxLines = 2)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LcarsChip("On", selected = dnd) { vm.setAlertThroughDnd(true) }
        LcarsChip("Off", selected = !dnd) { vm.setAlertThroughDnd(false) }
        LcarsButton("Test", color = s.alert, height = 32.dp) { dev.vantara.replicatorops.alert.AlertSound.play(context, times = 2, throughDnd = dnd) }
    }
    if (on) {
        val firstLayer by vm.firstLayer.collectAsState()
        Spacer(Modifier.height(10.dp))
        LcarsLabel("First-layer watch: a camera snapshot when layer 2 starts", color = s.accent2, size = 13.sp, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsChip("Online", selected = firstLayer) { vm.setFirstLayerWatch(true) }
            LcarsChip("Offline", selected = !firstLayer) { vm.setFirstLayerWatch(false) }
        }
        Spacer(Modifier.height(8.dp))
        LcarsReadout("Notifications", if (allowed) "Allowed" else "Blocked", valueColor = if (allowed) s.accent else s.alert)
        if (!allowed) {
            LcarsButton("Allow notifications", color = s.alert) {
                if (Build.VERSION.SDK_INT >= 33 && !vm.prefs.askedNotifications) {
                    vm.prefs.askedNotifications = true
                    askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            }
        }
        LcarsReadout("Battery", if (unrestricted) "Unrestricted" else "Optimised", valueColor = if (unrestricted) s.accent else s.nav[0])
        if (!unrestricted) {
            LcarsLabel(
                "Some phones (Xiaomi, Samsung) stop background links. Set battery to unrestricted so Red Alert stays on watch.",
                color = s.hint, size = 13.sp, maxLines = 4,
            )
            Spacer(Modifier.height(6.dp))
            LcarsButton("Unrestrict battery", color = s.nav[0]) { requestUnrestricted(context) }
        }
    }
}

@SuppressLint("BatteryLife")
private fun requestUnrestricted(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        )
    }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

/** On first launch with Red Alert on, ask once for the notification permission (Android 13+). */
@Composable
fun AskForNotificationsOnce(vm: AppViewModel) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && vm.prefs.redAlert && !vm.prefs.askedNotifications && !notificationsAllowed(context)) {
            vm.prefs.askedNotifications = true
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
