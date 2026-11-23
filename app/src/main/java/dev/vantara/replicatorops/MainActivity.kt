package dev.vantara.replicatorops

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.SIDEBAR_SCREENS
import dev.vantara.replicatorops.ui.Screen
import dev.vantara.replicatorops.ui.lcars.LcarsFrame
import dev.vantara.replicatorops.ui.lcars.LcarsTheme
import dev.vantara.replicatorops.ui.lcars.LocalLcars
import dev.vantara.replicatorops.ui.lcars.NavItem
import dev.vantara.replicatorops.ui.screens.CaptainsLogDialog
import dev.vantara.replicatorops.ui.screens.ConfigScreen
import dev.vantara.replicatorops.ui.screens.ControlScreen
import dev.vantara.replicatorops.ui.screens.FilesScreen
import dev.vantara.replicatorops.ui.screens.LoadPatternDialog
import dev.vantara.replicatorops.ui.screens.RetrieveDialog
import dev.vantara.replicatorops.ui.screens.LogScreen
import dev.vantara.replicatorops.ui.screens.StoresScreen
import dev.vantara.replicatorops.ui.screens.UplinkAlert
import dev.vantara.replicatorops.ui.screens.StatusScreen
import dev.vantara.replicatorops.ui.screens.stateLabel
import java.time.LocalDateTime
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            val style by vm.style.collectAsState()
            LcarsTheme(style) { App(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Share → Replicator Ops, or "Open with", hands us a file to Load pattern. */
    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        uri?.let { vm.receivePattern(it) }
    }
}

@Composable
private fun App(vm: AppViewModel) {
    val s = LocalLcars.current
    val screen by vm.screen.collectAsState()
    val p by vm.printer.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val screens = SIDEBAR_SCREENS
    val nav = screens.mapIndexed { i, sc -> NavItem(sc.label, s.nav[i % s.nav.size]) }

    BackHandler(enabled = screen != screen.parent) { vm.back() }

    // Ship's chronometer: ticks on each second of the device clock.
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }

    LcarsFrame(
        title = "Replicator Ops",
        nav = nav,
        selected = screens.indexOf(screen.parent),
        onSelect = { vm.select(screens[it]) },
        footer = { Chronometer(stateLabel(p), now) },
    ) {
        when (screen) {
            Screen.STATUS -> StatusScreen(vm)
            Screen.CONTROL -> ControlScreen(vm)
            Screen.FILES -> FilesScreen(vm)
            Screen.CONFIG -> ConfigScreen(vm)
            Screen.STORES -> StoresScreen(vm)
            Screen.LOG -> LogScreen(vm)
        }
    }

    CaptainsLogDialog(vm)
    UplinkAlert(vm)
    LoadPatternDialog(vm)
    RetrieveDialog(vm)
}

/** Stardate as year + day of month, then month: 22 Nov 2026 -> "202622.11". */
private fun stardate(t: LocalDateTime): String = "%d%02d.%02d".format(t.year, t.dayOfMonth, t.monthValue)

/** Starfleet-style time: 08:11 -> "0811". */
private fun computerTime(t: LocalDateTime): String = "%02d%02d".format(t.hour, t.minute)

/** Bottom bar: "STANDING BY · STARDATE 202623.11 ● 0811 HOURS", with the dot pulsing each second. */
@Composable
private fun Chronometer(state: String, now: LocalDateTime) {
    val s = LocalLcars.current
    val pulse by animateFloatAsState(
        targetValue = if (now.second % 2 == 0) 1f else 0.15f,
        animationSpec = tween(durationMillis = 350),
        label = "chronometer pulse",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        LcarsLabel("$state · stardate ${stardate(now)}", color = s.frame, size = 13.sp, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(7.dp))
        Box(Modifier.size(6.dp).alpha(pulse).clip(CircleShape).background(s.accent))
        Spacer(Modifier.width(7.dp))
        LcarsLabel("${computerTime(now)} hours", color = s.frame, size = 13.sp)
    }
}
