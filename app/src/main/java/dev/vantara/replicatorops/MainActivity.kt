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
import java.time.LocalDate

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

    LcarsFrame(
        title = "Replicator Ops",
        nav = nav,
        selected = screens.indexOf(screen.parent),
        onSelect = { vm.select(screens[it]) },
        footer = "${stateLabel(p)} · stardate ${stardate()}",
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
private fun stardate(): String {
    val d = LocalDate.now()
    return "%d%02d.%02d".format(d.year, d.dayOfMonth, d.monthValue)
}
