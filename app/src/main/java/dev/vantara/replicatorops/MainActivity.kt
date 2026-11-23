package dev.vantara.replicatorops

import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.vantara.replicatorops.ui.SHORTCUT_PAUSE
import dev.vantara.replicatorops.ui.SHORTCUT_STATUS
import dev.vantara.replicatorops.ui.SHORTCUT_VOICE
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsConfirm
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
import dev.vantara.replicatorops.ui.lcars.ScaledToScreen
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
import dev.vantara.replicatorops.ui.screens.SystemsScreen
import dev.vantara.replicatorops.ui.screens.TimelapseScreen
import dev.vantara.replicatorops.ui.screens.AskForNotificationsOnce
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
            LcarsTheme(style) { ScaledToScreen { App(vm) } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Share → Replicator Ops, or "Open with", hands us a file to Load pattern. */
    private fun handleIncoming(intent: Intent?) {
        when (val a = intent?.action) {
            SHORTCUT_STATUS, SHORTCUT_VOICE, SHORTCUT_PAUSE -> { vm.shortcut(a); return }
        }
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
    val nav = screens.mapIndexed { i, sc -> NavItem(sc.label, s.sidebar[i % s.sidebar.size]) }

    BackHandler(enabled = screen != screen.parent) { vm.back() }

    // Ship's chronometer: ticks on each second of the device clock.
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }

    val fleet by vm.fleet.collectAsState()
    val active by vm.activePrinter.collectAsState()
    var picking by remember { mutableStateOf(false) }

    // "Computer," voice commands: Android's speech recogniser, then the command parser.
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (heard != null) vm.onVoice(heard)
    }
    fun startListening() {
        Chirp.confirm()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Computer: awaiting command")
        runCatching { listen.launch(intent) }.onFailure {
            Toast.makeText(context, "No speech recogniser on this phone", Toast.LENGTH_SHORT).show()
        }
    }
    val voiceRequest by vm.voiceRequest.collectAsState()
    LaunchedEffect(voiceRequest) { if (voiceRequest) { vm.voiceRequestHandled(); startListening() } }

    LcarsFrame(
        title = if (fleet.size > 1) active?.name ?: "Replicator Ops" else "Replicator Ops",
        nav = nav,
        selected = screens.indexOf(screen.parent),
        onSelect = { vm.select(screens[it]) },
        footer = { Chronometer(stateLabel(p), now) },
        onTitleClick = { picking = true },
        titleAction = { Combadge { startListening() } },
    ) {
        when (screen) {
            Screen.STATUS -> StatusScreen(vm)
            Screen.CONTROL -> ControlScreen(vm)
            Screen.SYSTEMS -> SystemsScreen(vm)
            Screen.FILES -> FilesScreen(vm)
            Screen.TIMELAPSE -> TimelapseScreen(vm)
            Screen.CONFIG -> ConfigScreen(vm)
            Screen.STORES -> StoresScreen(vm)
            Screen.LOG -> LogScreen(vm)
        }
    }

    CaptainsLogDialog(vm)
    if (picking) FleetPicker(vm) { picking = false }
    val confirmAbort by vm.confirmAbort.collectAsState()
    if (confirmAbort) {
        LcarsConfirm(
            title = "Abort replication?",
            text = "Voice command received. This cancels the current print and it can't be resumed.",
            confirmLabel = "Abort",
            onConfirm = { vm.dismissAbort(); vm.cancel() },
            onDismiss = { vm.dismissAbort() },
        )
    }
    AskForNotificationsOnce(vm)
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

/** Combadge button in the title bar: tap, then speak a command. */
@Composable
private fun Combadge(onClick: () -> Unit) {
    val s = LocalLcars.current
    Canvas(
        Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClick)
    ) {
        val w = size.width
        val h = size.height
        // Oval backing plate and a delta, in the frame colours.
        drawOval(s.frame, topLeft = Offset(0f, h * 0.3f), size = Size(w, h * 0.45f))
        val delta = Path().apply {
            moveTo(w * 0.5f, h * 0.05f)
            lineTo(w * 0.78f, h * 0.95f)
            lineTo(w * 0.5f, h * 0.72f)
            lineTo(w * 0.22f, h * 0.95f)
            close()
        }
        drawPath(delta, s.accent)
        drawPath(delta, Color.Black, style = Stroke(width = 2f))
    }
}

/** Fleet registry picker (tap the title bar). */
@Composable
private fun FleetPicker(vm: AppViewModel, onDismiss: () -> Unit) {
    val s = LocalLcars.current
    val fleet by vm.fleet.collectAsState()
    val active by vm.activePrinter.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, s.nav[2], RoundedCornerShape(24.dp)),
        title = { LcarsLabel("Fleet registry", color = s.nav[2], size = 20.sp, bold = true) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fleet.isEmpty()) LcarsLabel("No printers registered yet. Add one in Config.", color = s.hint, size = 14.sp, maxLines = 2)
                fleet.forEach { pr ->
                    val isActive = pr.id == active?.id
                    LcarsButton(
                        if (isActive) "${pr.name} · active" else pr.name,
                        color = if (isActive) s.accent else s.nav[2],
                        modifier = Modifier.fillMaxWidth(),
                    ) { if (!isActive) vm.switchPrinter(pr.id); onDismiss() }
                }
            }
        },
        confirmButton = {
            LcarsButton("Manage", color = s.nav[2]) { vm.select(Screen.CONFIG); onDismiss() }
        },
        dismissButton = { LcarsButton("Close", color = s.frameAlt) { onDismiss() } },
    )
}
