package dev.vantara.replicatorops.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.vantara.replicatorops.data.Connection
import dev.vantara.replicatorops.data.Endpoint
import dev.vantara.replicatorops.data.FileMeta
import dev.vantara.replicatorops.data.GcodeFile
import dev.vantara.replicatorops.data.HistoryJob
import dev.vantara.replicatorops.data.SlotInfo
import dev.vantara.replicatorops.data.Spool
import dev.vantara.replicatorops.data.StoresRepo
import dev.vantara.replicatorops.data.TempSample
import dev.vantara.replicatorops.data.StarbaseUplink
import dev.vantara.replicatorops.data.CaptainsLog
import dev.vantara.replicatorops.data.IncomingPattern
import dev.vantara.replicatorops.data.PatternKind
import dev.vantara.replicatorops.data.PatternLoader
import android.net.Uri
import dev.vantara.replicatorops.data.LogEntry
import dev.vantara.replicatorops.data.Upgrade
import dev.vantara.replicatorops.BuildConfig
import java.io.File
import dev.vantara.replicatorops.data.MoonrakerClient
import dev.vantara.replicatorops.data.Prefs
import dev.vantara.replicatorops.data.PrinterAlert
import dev.vantara.replicatorops.slicer.ModelReader
import dev.vantara.replicatorops.slicer.SliceSettings
import dev.vantara.replicatorops.slicer.SliceState
import dev.vantara.replicatorops.slicer.SlicerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.vantara.replicatorops.data.FilamentMapping
import dev.vantara.replicatorops.data.PrinterProfile
import dev.vantara.replicatorops.data.ReplicationQueue
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import dev.vantara.replicatorops.data.GcodeOutlines
import dev.vantara.replicatorops.data.FoundPrinter
import dev.vantara.replicatorops.data.SensorSweep
import dev.vantara.replicatorops.data.TimelapseFile
import dev.vantara.replicatorops.data.Webcam
import dev.vantara.replicatorops.widget.ReplicatorWidget
import dev.vantara.replicatorops.widget.WidgetData
import android.content.Intent
import androidx.core.content.FileProvider
import dev.vantara.replicatorops.data.Outline
import dev.vantara.replicatorops.alert.RedAlertService
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsStyle
import dev.vantara.replicatorops.ui.lcars.styleFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import coil.ImageLoader
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Sidebar screens, plus STORES and LOG which open from Control and Files. */
enum class Screen(val label: String) {
    STATUS("Status"),
    CONTROL("Control"),
    SYSTEMS("Systems"),
    FILES("Files"),
    TIMELAPSE("Timelapse"),
    SLICER("Slicer"),
    COMMANDS("Commands"),
    CONFIG("Config"),
    STORES("Stores"),
    LOG("Log");

    val parent: Screen
        get() = when (this) {
            STORES -> CONTROL
            LOG -> FILES
            else -> this
        }
}

val SIDEBAR_SCREENS: List<Screen> = buildList {
    addAll(listOf(Screen.STATUS, Screen.CONTROL, Screen.SYSTEMS, Screen.FILES))
    if (BuildConfig.BETA) add(Screen.SLICER) // on-phone slicer: Beta only
    addAll(listOf(Screen.TIMELAPSE, Screen.COMMANDS, Screen.CONFIG))
}

enum class LogPeriod(val label: String, val days: Int?) { DAY("Day", 1), WEEK("Week", 7), MONTH("Month", 30), YEAR("Year", 365), ALL("All", null); }

/** Starbase Uplink (in-app updates) state. */
sealed interface UplinkState {
    data object Idle : UplinkState
    data object Checking : UplinkState
    data object Current : UplinkState
    data class Available(val upgrade: Upgrade) : UplinkState
    data class Downloading(val upgrade: Upgrade, val progress: Float) : UplinkState
    data class Ready(val upgrade: Upgrade, val file: File) : UplinkState
    data class Failed(val message: String, val upgrade: Upgrade?) : UplinkState
}

/** Load pattern (upload) state. */
sealed interface LoadState {
    data object Idle : LoadState
    data object Receiving : LoadState
    data class Ready(val pattern: IncomingPattern) : LoadState
    data class NeedsSlicing(val pattern: IncomingPattern) : LoadState
    data class WrongPrinter(val pattern: IncomingPattern) : LoadState
    data class Unsupported(val name: String) : LoadState
    data class Uploading(val pattern: IncomingPattern, val progress: Float) : LoadState
    data class Failed(val message: String, val pattern: IncomingPattern?) : LoadState
}

/** Sensor sweep (printer discovery) state. */
data class SweepState(val running: Boolean = false, val found: List<FoundPrinter> = emptyList(), val ran: Boolean = false)

/** Timelapse save/share progress. */
sealed interface ReelState {
    data object Idle : ReelState
    data class Busy(val name: String, val verb: String, val progress: Float) : ReelState
}

/** Target lock outlines read from the print file. */
sealed interface OutlineState {
    data object Idle : OutlineState
    data class Loading(val file: String) : OutlineState
    data class Ready(val file: String, val shapes: Map<String, Outline>) : OutlineState
    data class Failed(val file: String, val message: String) : OutlineState
}

/** Retrieve pattern (download from printer) state. */
sealed interface RetrieveState {
    data object Idle : RetrieveState
    data class Retrieving(val name: String, val progress: Float) : RetrieveState
    data class Done(val name: String) : RetrieveState
    data class Failed(val name: String, val message: String) : RetrieveState
}

const val SHORTCUT_STATUS = "dev.vantara.replicatorops.STATUS_REPORT"
const val SHORTCUT_VOICE = "dev.vantara.replicatorops.VOICE"
const val SHORTCUT_PAUSE = "dev.vantara.replicatorops.PAUSE_REPLICATION"

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** Coil loader for thumbnails; adds the Moonraker API key when one is set. */
    val imageLoader: ImageLoader = ImageLoader.Builder(app).okHttpClient { thumbnailHttp() }.build()

    private fun thumbnailHttp(): OkHttpClient = http.newBuilder().addInterceptor(::addApiKey).build()

    private fun addApiKey(chain: Interceptor.Chain): Response {
        val key = prefs.apiKey.trim()
        if (key.isEmpty()) return chain.proceed(chain.request())
        return chain.proceed(chain.request().newBuilder().header("X-Api-Key", key).build())
    }

    val client = MoonrakerClient(viewModelScope, http)
    val printer = client.state

    private val _style = MutableStateFlow(styleFor(prefs.style))
    val style: StateFlow<LcarsStyle> = _style.asStateFlow()

    private val _chirps = MutableStateFlow(prefs.chirps)
    val chirps: StateFlow<Boolean> = _chirps.asStateFlow()

    /** Replication queue for the active printer. */
    val queueStore = ReplicationQueue.get(app)
    val queue: StateFlow<List<String>> = queueStore.items

    private val _fleet = MutableStateFlow(prefs.printers)
    /** Fleet registry: every saved printer. */
    val fleet: StateFlow<List<PrinterProfile>> = _fleet.asStateFlow()
    private val _activePrinter = MutableStateFlow(prefs.activePrinter)
    val activePrinter: StateFlow<PrinterProfile?> = _activePrinter.asStateFlow()

    private fun refreshFleet() {
        _fleet.value = prefs.printers
        _activePrinter.value = prefs.activePrinter
    }

    private val _redAlert = MutableStateFlow(prefs.redAlert)
    val redAlert: StateFlow<Boolean> = _redAlert.asStateFlow()

    private val _screen = MutableStateFlow(if (prefs.host.isBlank()) Screen.CONFIG else Screen.STATUS)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _files = MutableStateFlow<List<GcodeFile>>(emptyList())
    val files: StateFlow<List<GcodeFile>> = _files.asStateFlow()

    private val _filesLoading = MutableStateFlow(false)
    val filesLoading: StateFlow<Boolean> = _filesLoading.asStateFlow()

    private val _meta = MutableStateFlow<Map<String, FileMeta>>(emptyMap())
    val meta: StateFlow<Map<String, FileMeta>> = _meta.asStateFlow()
    private val metaRequested = HashSet<String>()

    private val _webcam = MutableStateFlow<Webcam?>(null)
    val webcam: StateFlow<Webcam?> = _webcam.asStateFlow()

    private val _favMacros = MutableStateFlow(prefs.favouriteMacros)
    /** Macros starred on the Ship systems screen. */
    val favMacros: StateFlow<Set<String>> = _favMacros.asStateFlow()
    private val _macroHelp = MutableStateFlow<Map<String, String>>(emptyMap())
    val macroHelp: StateFlow<Map<String, String>> = _macroHelp.asStateFlow()

    // ---- Thermal log, Replicator stores, Mission log -------------------------

    private val _temps = MutableStateFlow<List<TempSample>>(emptyList())
    /** About 30 minutes of nozzle/bed samples, one every 2 seconds. */
    val temps: StateFlow<List<TempSample>> = _temps.asStateFlow()

    val stores = StoresRepo(app)
    private val _spools = MutableStateFlow(stores.loadSpools())
    val spools: StateFlow<List<Spool>> = _spools.asStateFlow()
    private val _activeSpool = MutableStateFlow(stores.activeSpoolId)
    val activeSpool: StateFlow<Long?> = _activeSpool.asStateFlow()
    private val _slotInfo = MutableStateFlow(stores.loadSlots())
    /** User-set material/colour per Box slot (0-3). */
    val slotInfo: StateFlow<Map<Int, SlotInfo>> = _slotInfo.asStateFlow()

    private val _pricePerKg = MutableStateFlow(stores.defaultPricePerKg)
    /** Default filament price per kg for print costs. */
    val pricePerKg: StateFlow<Double> = _pricePerKg.asStateFlow()

    fun setPricePerKg(v: Double) {
        val clean = v.coerceIn(0.0, 10_000.0)
        stores.defaultPricePerKg = clean
        _pricePerKg.value = clean
    }

    private val _logPeriod = MutableStateFlow(LogPeriod.ALL)
    val logPeriod: StateFlow<LogPeriod> = _logPeriod.asStateFlow()
    private val _history = MutableStateFlow<List<HistoryJob>>(emptyList())
    val history: StateFlow<List<HistoryJob>> = _history.asStateFlow()
    private val _historyLoading = MutableStateFlow(false)
    val historyLoading: StateFlow<Boolean> = _historyLoading.asStateFlow()

    // ---- Starbase Uplink ----------------------------------------------------------

    val appVersion: String = BuildConfig.VERSION_NAME
    private val uplink = StarbaseUplink(app, http)
    private val _uplinkState = MutableStateFlow<UplinkState>(UplinkState.Idle)
    val uplinkState: StateFlow<UplinkState> = _uplinkState.asStateFlow()
    private val _uplinkAlert = MutableStateFlow(false)
    /** True when the launch check found an upgrade and the alert dialog should show. */
    val uplinkAlert: StateFlow<Boolean> = _uplinkAlert.asStateFlow()

    // ---- Captain's Log (what's new) -------------------------------------------------

    /** Every release note bundled with this build, newest first. */
    val captainsLog: List<LogEntry> = CaptainsLog.load(app)
    private val _logPopup = MutableStateFlow<List<LogEntry>>(emptyList())
    /** Entries to show in the Captain's Log pop-up; empty = hidden. */
    val logPopup: StateFlow<List<LogEntry>> = _logPopup.asStateFlow()

    // ---- Load pattern ---------------------------------------------------------------

    private val loader = PatternLoader(app, http)
    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()
    private val _openFile = MutableStateFlow<GcodeFile?>(null)
    /** A just-uploaded file whose details (with Replicate) the Files screen should open. */
    val openFile: StateFlow<GcodeFile?> = _openFile.asStateFlow()

    private val _retrieveState = MutableStateFlow<RetrieveState>(RetrieveState.Idle)
    val retrieveState: StateFlow<RetrieveState> = _retrieveState.asStateFlow()

    // ---- Sensor sweep ---------------------------------------------------------------

    private val sweeper = SensorSweep(app, http)
    private val _sweep = MutableStateFlow(SweepState())
    val sweep: StateFlow<SweepState> = _sweep.asStateFlow()

    // ---- Timelapse archive -----------------------------------------------------------

    private val _reels = MutableStateFlow<List<TimelapseFile>>(emptyList())
    val reels: StateFlow<List<TimelapseFile>> = _reels.asStateFlow()
    private val _reelsLoading = MutableStateFlow(false)
    val reelsLoading: StateFlow<Boolean> = _reelsLoading.asStateFlow()
    private val _reelsError = MutableStateFlow<String?>(null)
    val reelsError: StateFlow<String?> = _reelsError.asStateFlow()
    private val _reelState = MutableStateFlow<ReelState>(ReelState.Idle)
    val reelState: StateFlow<ReelState> = _reelState.asStateFlow()

    private val _printerAlert = MutableStateFlow<PrinterAlert?>(null)
    /** The latest pop-up from the printer, shown as an LCARS alert until acknowledged. */
    val printerAlert: StateFlow<PrinterAlert?> = _printerAlert.asStateFlow()
    fun dismissPrinterAlert() { _printerAlert.value = null }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    init {
        Chirp.enabled = prefs.chirps
        prefs.endpoint()?.let { client.connect(it) }
        RedAlertService.sync(app)
        // First launch with no printer set: look for one straight away.
        if (prefs.host.isBlank()) sensorSweep()
        viewModelScope.launch {
            // Keep the home screen widget current while the app is open.
            printer.map { WidgetData.of(it) }.distinctUntilChanged().collect { ReplicatorWidget.push(app, it) }
        }
        if (prefs.lastSeenVersion != appVersion) {
            val pending = CaptainsLog.since(captainsLog, prefs.lastSeenVersion)
                .ifEmpty { captainsLog.filter { it.version == appVersion } }
            if (pending.isEmpty()) prefs.lastSeenVersion = appVersion else _logPopup.value = pending
        }
        viewModelScope.launch {
            delay(1500)
            checkUplink(silent = true)
        }

        viewModelScope.launch {
            printer.map { it.connection == Connection.CONNECTED }.distinctUntilChanged().collect { online ->
                if (online) {
                    refreshFiles()
                    runCatching { client.temperatureStore() }.onSuccess { all ->
                        _temps.value = all.filterIndexed { i, _ -> i % 2 == 0 }.takeLast(TEMP_SAMPLES)
                    }
                    _webcam.value = runCatching { client.webcam() }.getOrNull()
                    _macroHelp.value = client.gcodeHelp()
                } else {
                    _webcam.value = null
                }
            }
        }
        viewModelScope.launch { client.fileListChanged.collect { refreshFiles() } }
        viewModelScope.launch {
            client.alerts.collect { a ->
                if (_printerAlert.value?.raw != a.raw) Chirp.alert()
                _printerAlert.value = a
            }
        }
        viewModelScope.launch {
            printer.map { it.filename }.distinctUntilChanged().collect { f -> if (!f.isNullOrBlank()) loadMeta(f, force = true) }
        }
        viewModelScope.launch {
            // Sample temperatures for the thermal log.
            while (true) {
                delay(2000)
                val p = printer.value
                if (p.ready) {
                    val noz = p.heaters.firstOrNull { it.objectName == "extruder" }?.temperature?.toFloat() ?: 0f
                    val bed = p.heaters.firstOrNull { it.objectName == "heater_bed" }?.temperature?.toFloat() ?: 0f
                    _temps.update { (it + TempSample(noz, bed)).takeLast(TEMP_SAMPLES) }
                }
            }
        }
        viewModelScope.launch {
            // Take used filament off the spool in use when a print ends.
            var lastUsed = 0.0
            var wasRunning = false
            printer.collect { p ->
                if (p.isBusy) {
                    wasRunning = true
                    lastUsed = p.filamentUsedMm
                } else if (wasRunning && p.ready && p.printState in setOf("complete", "cancelled", "error")) {
                    wasRunning = false
                    deductFromActiveSpool(lastUsed)
                    lastUsed = 0.0
                }
            }
        }
        viewModelScope.launch {
            // When the printer's own filament type for a slot changes (set on the printer's
            // screen, or a tag read), it replaces whatever was set in the app.
            var last: List<String?>? = null
            printer.map { p -> if (p.ready) p.box?.slots?.map { it.material } else null }
                .distinctUntilChanged()
                .collect { now ->
                    if (now == null) return@collect
                    val before = last
                    if (before != null) now.forEachIndexed { i, m -> if (m != before.getOrNull(i)) clearSlotOverride(i) }
                    last = now
                }
        }
        viewModelScope.launch {
            // LCARS alert chirp when a print finishes or errors.
            printer.map { it.printState }.distinctUntilChanged().collect { st ->
                when (st) {
                    "complete" -> Chirp.confirm()
                    "error" -> Chirp.alert()
                }
            }
        }
    }

    // ---- Navigation and settings -------------------------------------------

    fun select(screen: Screen) {
        _screen.value = screen
        if (screen == Screen.LOG) loadHistory()
        if (screen == Screen.TIMELAPSE) refreshReels()
    }

    fun back() { _screen.value = _screen.value.parent }

    fun setStyle(id: String) {
        prefs.style = id
        _style.value = styleFor(id)
    }

    fun setChirps(on: Boolean) {
        prefs.chirps = on
        Chirp.enabled = on
        _chirps.value = on
    }

    fun connect(host: String, apiKey: String) {
        val ep = Endpoint.parse(host, apiKey)
        if (ep == null) {
            Chirp.deny()
            say(Endpoint.problem(host) ?: "That doesn't look like a valid address")
            return
        }
        prefs.host = host.trim()
        prefs.apiKey = apiKey.trim()
        refreshFleet()
        resetPrinterData()
        client.connect(ep)
        RedAlertService.sync(getApplication())
    }

    // ---- Fleet registry ---------------------------------------------------------

    /** Forget everything that belonged to the previous printer. */
    private fun resetPrinterData() {
        metaRequested.clear()
        _meta.value = emptyMap()
        _files.value = emptyList()
        _history.value = emptyList()
        _reels.value = emptyList()
        _reelsError.value = null
        _webcam.value = null
        _temps.value = emptyList()
        _outlines.value = OutlineState.Idle
        queueStore.reload()
    }

    // ---- Replication queue --------------------------------------------------------

    fun addToQueue(path: String) {
        queueStore.add(path)
        Chirp.confirm()
        say("${path.substringAfterLast('/')} added to the replication queue (${queueStore.items.value.size})")
    }

    fun removeFromQueue(index: Int) = queueStore.removeAt(index)
    fun moveInQueue(index: Int, by: Int) = queueStore.move(index, by)
    fun clearQueue() = queueStore.clear()

    /** Starts the next queued file. Call once the build plate is clear. */
    fun startNextInQueue() {
        if (printer.value.isBusy) { Chirp.deny(); say("Replicator is busy"); return }
        val next = queueStore.pop() ?: run { say("The replication queue is empty"); return }
        viewModelScope.launch {
            runCatching {
                autoMap(next)
                client.call("printer.print.start", buildJsonObject { put("filename", next) })
            }
                .onSuccess { Chirp.confirm(); say("Replicating ${next.substringAfterLast('/')}") }
                .onFailure { e -> queueStore.pushFront(next); Chirp.deny(); say(e.message ?: "Couldn't start the next print") }
        }
    }

    /** Screenshot tests: sample files. */
    internal fun showFilesForTest(files: List<GcodeFile>) { _files.value = files }

    fun switchPrinter(id: Long) {
        val target = prefs.printers.firstOrNull { it.id == id } ?: return
        val ep = Endpoint.parse(target.host, target.apiKey)
        if (ep == null) { Chirp.deny(); say("${target.name} has an invalid address"); return }
        prefs.activeId = id
        refreshFleet()
        resetPrinterData()
        client.connect(ep)
        RedAlertService.sync(getApplication())
        Chirp.confirm()
        say("Now commanding ${target.name}")
    }

    /** Adds a printer to the fleet and switches to it (or switches if the address is already there). */
    fun addPrinter(name: String, host: String, apiKey: String) {
        val h = host.trim()
        if (Endpoint.parse(h, apiKey) == null) {
            Chirp.deny()
            say(Endpoint.problem(h) ?: "That doesn't look like a valid address")
            return
        }
        val existing = prefs.printers.firstOrNull { it.host.equals(h, ignoreCase = true) }
        val p = existing ?: prefs.savePrinter(PrinterProfile(System.currentTimeMillis(), name.trim().ifBlank { h }, h, apiKey.trim()))
        switchPrinter(p.id)
    }

    fun renamePrinter(id: Long, name: String) {
        val p = prefs.printers.firstOrNull { it.id == id } ?: return
        prefs.savePrinter(p.copy(name = name.trim().ifBlank { p.host }))
        refreshFleet()
    }

    fun removePrinter(id: Long) {
        val wasActive = prefs.activePrinter?.id == id
        prefs.removePrinter(id)
        refreshFleet()
        if (wasActive) {
            val next = prefs.activePrinter
            if (next != null) switchPrinter(next.id) else { client.disconnect(); resetPrinterData(); RedAlertService.sync(getApplication()) }
        }
    }

    // ---- Subroutines (macros) ------------------------------------------------------

    fun toggleFavouriteMacro(name: String) {
        val now = _favMacros.value.let { if (name in it) it - name else it + name }
        _favMacros.value = now
        prefs.favouriteMacros = now
    }

    // ---- "Computer," voice commands -------------------------------------------------

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private val _confirmAbort = MutableStateFlow(false)
    /** True when a voice "abort" is waiting for the on-screen confirmation. */
    val confirmAbort: StateFlow<Boolean> = _confirmAbort.asStateFlow()
    fun dismissAbort() { _confirmAbort.value = false }

    /** Speaks a reply (and shows it), in British English where available. */
    fun speak(text: String) {
        say(text)
        val engine = tts
        if (engine != null && ttsReady) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "replicator")
            return
        }
        pendingSpeech = text
        if (engine == null) {
            tts = TextToSpeech(getApplication()) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.UK
                    pendingSpeech?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "replicator") }
                }
                pendingSpeech = null
            }
        }
    }

    /** Handles what the speech recogniser heard. */
    fun onVoice(heard: String) = runCommand(Voice.parse(heard))

    /** Runs a command from voice, typing or the Commands tab, with a spoken reply. */
    fun runCommand(command: VoiceCommand) {
        val p = printer.value
        when (command) {
            VoiceCommand.STATUS -> speak(Voice.statusReport(p))
            VoiceCommand.TEMPERATURES -> speak(Voice.temperatures(p).ifEmpty { "Temperature sensors unavailable." })
            VoiceCommand.TIME_LEFT -> speak(Voice.timeLeft(p))
            VoiceCommand.PAUSE -> if (p.isPrinting) { pause(); speak("Pausing replication.") } else speak("No replication to pause.")
            VoiceCommand.RESUME -> if (p.isPaused) { resume(); speak("Resuming replication.") } else speak("Replication is not paused.")
            VoiceCommand.ABORT -> if (p.isBusy) { _confirmAbort.value = true; speak("Confirm abort on screen.") } else speak("No replication in progress.")
            VoiceCommand.LIGHTS_ON -> { setCaseLight(true); speak("Lights up.") }
            VoiceCommand.LIGHTS_OFF -> { setCaseLight(false); speak("Lights down.") }
            VoiceCommand.COOL_DOWN -> { coolDown(); speak("Heaters offline.") }
            VoiceCommand.HOME -> if (p.isBusy) speak("Unable to comply during replication.") else { home(); speak("Homing all axes.") }
            VoiceCommand.NEXT_IN_QUEUE -> if (queue.value.isEmpty()) speak("The replication queue is empty.")
                else speak("Check the build plate is clear, then tap Engage next on the Status screen.")
            VoiceCommand.UNKNOWN -> { Chirp.deny(); speak("Unable to comply. Try status report, pause or time remaining.") }
        }
    }

    private val _voiceRequest = MutableStateFlow(false)
    /** Set by the "Computer" shortcut: the UI should open the speech recogniser. */
    val voiceRequest: StateFlow<Boolean> = _voiceRequest.asStateFlow()
    fun requestVoice() { _voiceRequest.value = true }
    fun voiceRequestHandled() { _voiceRequest.value = false }

    private val _typingRequest = MutableStateFlow(false)
    /** Opens the typed "Computer" command box. */
    val typingRequest: StateFlow<Boolean> = _typingRequest.asStateFlow()
    fun requestTyping() { _typingRequest.value = true }
    fun typingRequestHandled() { _typingRequest.value = false }

    /** Launcher shortcuts arrive before the printer link is up; wait for it (up to 10 s). */
    fun shortcut(action: String) {
        viewModelScope.launch {
            when (action) {
                SHORTCUT_VOICE -> requestVoice()
                SHORTCUT_STATUS, SHORTCUT_PAUSE -> {
                    select(Screen.STATUS)
                    withTimeoutOrNull(10_000) { printer.first { it.ready } }
                    if (action == SHORTCUT_STATUS) speak(Voice.statusReport(printer.value))
                    else onVoice("pause")
                }
            }
        }
    }

    override fun onCleared() {
        tts?.shutdown()
        super.onCleared()
    }

    fun calibrateMesh() = gcode("G28\nBED_MESH_CALIBRATE", "Bed mesh calibration started")

    fun runMacro(name: String) = gcode(name, "Subroutine $name engaged")

    fun sensorSweep() {
        if (_sweep.value.running) return
        _sweep.value = SweepState(running = true)
        viewModelScope.launch {
            val all = runCatching {
                sweeper.sweep { p -> _sweep.update { st -> st.copy(found = (st.found + p).distinctBy { it.address }) } }
            }.getOrDefault(_sweep.value.found)
            _sweep.value = SweepState(running = false, found = all, ran = true)
            if (all.isEmpty()) Chirp.deny() else Chirp.confirm()
        }
    }

    private val _firstLayer = MutableStateFlow(prefs.firstLayerWatch)
    val firstLayer: StateFlow<Boolean> = _firstLayer.asStateFlow()

    fun setFirstLayerWatch(on: Boolean) {
        prefs.firstLayerWatch = on
        _firstLayer.value = on
    }

    fun setRedAlert(on: Boolean) {
        prefs.redAlert = on
        _redAlert.value = on
        RedAlertService.sync(getApplication())
    }

    // ---- Target lock (exclude object) -----------------------------------------

    /** Cancel several objects at once. */
    fun excludeObjects(names: Collection<String>) {
        if (names.isEmpty()) return
        val text = if (names.size == 1) "Target ${prettyObjectName(names.first())} neutralised" else "${names.size} targets neutralised"
        gcode(names.joinToString("\n") { "EXCLUDE_OBJECT NAME=$it" }, text)
    }

    private val _outlines = MutableStateFlow<OutlineState>(OutlineState.Idle)
    /** First-layer shapes of the objects in the current print, for the Target lock map. */
    val outlines: StateFlow<OutlineState> = _outlines.asStateFlow()

    fun loadOutlines() {
        val file = printer.value.filename ?: return
        val ep = client.endpoint ?: return
        val now = _outlines.value
        if ((now is OutlineState.Ready && now.file == file) || (now is OutlineState.Loading && now.file == file)) return
        _outlines.value = OutlineState.Loading(file)
        viewModelScope.launch {
            _outlines.value = runCatching { GcodeOutlines.fetch(http, ep, file) }
                .fold({ OutlineState.Ready(file, it) }, { OutlineState.Failed(file, it.message ?: "Couldn't read the print file") })
        }
    }

    // ---- Ship systems ------------------------------------------------------------

    fun setCaseLight(on: Boolean) =
        gcode("SET_PIN PIN=caselight VALUE=${if (on) 1 else 0}", if (on) "Lights up" else "Lights down")

    private fun saveFlag(name: String, on: Boolean, okText: String) {
        val bools = printer.value.systems.boolVars
        val value = if (name in bools) (if (on) "True" else "False") else (if (on) "1" else "0")
        gcode("SAVE_VARIABLE VARIABLE=$name VALUE=$value", okText)
    }

    fun setNoodleDetection(on: Boolean) =
        saveFlag("enable_noodle_detection", on, if (on) "Spaghetti detection online" else "Spaghetti detection offline")

    fun setPrePrintCheck(on: Boolean) =
        saveFlag("enable_pre_print_model_check", on, if (on) "Pre-flight scan online" else "Pre-flight scan offline")

    fun setNoodleSensitivity(level: String) =
        gcode("SAVE_VARIABLE VARIABLE=noodle_sensitivity_level VALUE=\"'$level'\"", "Sensitivity set to $level")

    fun disconnect() = client.disconnect()

    private fun say(text: String) { _messages.tryEmit(text) }

    /** Run a printer action, reporting failures as a message. */
    private fun action(okText: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { okText?.let(::say) }
                .onFailure { e ->
                    Chirp.deny()
                    say(e.message ?: "Command failed")
                }
        }
    }

    // ---- Files ---------------------------------------------------------------

    fun refreshFiles() {
        viewModelScope.launch {
            _filesLoading.value = true
            runCatching { client.listFiles() }
                .onSuccess { _files.value = it }
                .onFailure { say("Couldn't list files: ${it.message}") }
            _filesLoading.value = false
        }
    }

    fun loadMeta(path: String, force: Boolean = false) {
        if (!force && !metaRequested.add(path)) return
        metaRequested.add(path)
        viewModelScope.launch {
            runCatching { client.metadata(path) }.onSuccess { m -> _meta.update { it + (path to m) } }
        }
    }

    fun startPrint(path: String) = action("Print started") {
        client.call("printer.print.start", buildJsonObject { put("filename", path) })
    }

    /** Queued prints: pick Box slots automatically (same type, closest colour). */
    private suspend fun autoMap(path: String) {
        val box = printer.value.box ?: return
        val needs = FilamentMapping.needs(runCatching { client.metadata(path) }.getOrNull())
        val map = FilamentMapping.suggest(needs, box.slots, box.toolMap)
        if (map.isNotEmpty()) client.gcode(FilamentMapping.gcode(map))
    }

    /** Sets which Box slot feeds each filament, then starts the print. */
    fun startPrintMapped(path: String, map: Map<Int, Int>) = action("Print started") {
        if (map.isNotEmpty()) client.gcode(FilamentMapping.gcode(map))
        client.call("printer.print.start", buildJsonObject { put("filename", path) })
    }

    fun deleteFile(path: String) = action("File deleted") {
        client.call("server.files.delete_file", buildJsonObject { put("path", "gcodes/$path") })
    }

    // ---- Print control -------------------------------------------------------

    fun pause() = action { client.call("printer.print.pause") }
    fun resume() = action { client.call("printer.print.resume") }
    fun cancel() = action("Print cancelled") { client.call("printer.print.cancel") }
    fun emergencyStop() = action("Emergency stop sent") { client.call("printer.emergency_stop") }
    fun firmwareRestart() = action("Firmware restart sent") { client.call("printer.firmware_restart") }
    /** Klipper (host) restart: reloads printer.cfg without resetting the MCUs. */
    fun klipperRestart() = action("Klipper restart sent") { client.call("printer.restart") }

    fun gcode(script: String, okText: String? = null) = action(okText) { client.gcode(script) }

    fun setTemperature(heaterName: String, target: Int) =
        gcode("SET_HEATER_TEMPERATURE HEATER=$heaterName TARGET=$target")

    fun preheat(nozzle: Int, bed: Int) {
        val heaters = printer.value.heaters.mapNotNull { it.heaterName }
        val lines = buildList {
            if ("extruder" in heaters) add("SET_HEATER_TEMPERATURE HEATER=extruder TARGET=$nozzle")
            if ("heater_bed" in heaters) add("SET_HEATER_TEMPERATURE HEATER=heater_bed TARGET=$bed")
        }
        if (lines.isNotEmpty()) gcode(lines.joinToString("\n"), "Preheating")
    }

    fun coolDown() {
        val lines = printer.value.heaters.mapNotNull { it.heaterName }
            .map { "SET_HEATER_TEMPERATURE HEATER=$it TARGET=0" }
        if (lines.isNotEmpty()) gcode(lines.joinToString("\n"), "Heaters off")
    }

    /** QIDI printers drive the part fan as fan_generic cooling_fan; others use the standard [fan]. */
    fun setPartFan(percent: Int) {
        val pct = percent.coerceIn(0, 100)
        if (printer.value.fans.any { it.objectName == "fan_generic cooling_fan" }) {
            gcode("SET_FAN_SPEED FAN=cooling_fan SPEED=%.2f".format(pct / 100.0))
        } else {
            gcode("M106 S${pct * 255 / 100}")
        }
    }
    fun setSpeedFactor(percent: Int) = gcode("M220 S${percent.coerceIn(10, 300)}")
    fun setFlowFactor(percent: Int) = gcode("M221 S${percent.coerceIn(50, 150)}")

    fun home(axes: String = "") = gcode(if (axes.isBlank()) "G28" else "G28 $axes")
    fun motorsOff() = gcode("M84", "Steppers unlocked")

    /** Bed up/down by [mm] (QIDI's Bed buttons). The Q2C bed moves on Z: up = towards the nozzle. */
    fun moveBed(mm: Double) {
        if ('z' !in printer.value.homedAxes.lowercase()) {
            Chirp.deny(); say("Home Z first, then the bed can move"); return
        }
        gcode("G91\nG1 Z${-mm} F600\nG90")
    }

    /** Any fan_generic fan (Aux, Chamber, Exhaust…): 0–100 %. */
    fun setFan(objectName: String, percent: Int) {
        val name = objectName.removePrefix("fan_generic ").trim()
        gcode("SET_FAN_SPEED FAN=$name SPEED=%.2f".format(percent.coerceIn(0, 100) / 100.0))
    }

    fun jog(axis: Char, distance: Double) {
        val feed = if (axis == 'Z') 600 else 6000
        gcode("G91\nG1 $axis$distance F$feed\nG90")
    }

    // ---- QIDI Box ------------------------------------------------------------

    // Q2C Box firmware (box_stepper / box_extras) names slots "slot0".."slot3" and
    // expects the nozzle hot before it moves filament, as its own load routine does.
    private fun boxTemp(): Int {
        val target = printer.value.heaters.firstOrNull { it.objectName == "extruder" }?.target ?: 0.0
        return if (target >= 190) target.toInt() else 220
    }

    fun boxLoad(slot: Int) = gcode("M109 S${boxTemp()}\nEXTRUDER_LOAD SLOT=slot$slot", "Loading slot ${slot + 1}")
    /**
     * Unload: the printer's own M603 routine (same as its touchscreen). It heats the nozzle and
     * pulls the filament out of the toolhead, leaving the spool in its Box slot.
     */
    fun boxUnload() = gcode("M603 S${boxTemp()}", "Unloading filament from the nozzle")

    /** Eject: runs the filament for this slot all the way out of the Box (EXTRUDER_UNLOAD). */
    fun boxEject(slot: Int) = gcode("M109 S${boxTemp()}\nEXTRUDER_UNLOAD SLOT=slot$slot", "Ejecting slot ${slot + 1}")
    fun boxReadRfid(slot: Int) {
        // Don't send a tag read to an empty slot; the printer would just report nothing.
        val reported = printer.value.box?.slots?.getOrNull(slot)
        if (reported != null && !reported.loaded) {
            Chirp.deny()
            say("No filament in slot ${slot + 1}. Insert a spool to read its tag.")
            return
        }
        action("Reading tag in slot ${slot + 1}") {
            client.gcode("SLOT_RFID_READ SLOT=slot$slot")
            // The printer's reading is now the truth: drop any type/colour set in the app.
            clearSlotOverride(slot)
        }
    }

    /** Forget the app's own type/colour for a slot so the printer's report shows. */
    private fun clearSlotOverride(slot: Int) {
        if (slot !in _slotInfo.value) return
        _slotInfo.update { it - slot }
        stores.saveSlots(_slotInfo.value)
    }

    /** Write a slot colour where the Box firmware reads it: color_slotN = ['RRGGBB'] in save_variables. */
    private fun writeSlotColour(slot: Int, argb: Long) {
        if (!printer.value.ready || printer.value.isPrinting) return
        val hex = "%06X".format(argb and 0xFFFFFF)
        gcode("SAVE_VARIABLE VARIABLE=color_slot$slot VALUE=\"['$hex']\"")
    }

    /** Set (or clear, with null) what's in a Box slot. Linking a spool also tags the spool with the slot. */
    fun setSlot(slot: Int, info: SlotInfo?) {
        _slotInfo.update { if (info == null) it - slot else it + (slot to info) }
        info?.let { writeSlotColour(slot, it.color) }
        stores.saveSlots(_slotInfo.value)
        updateSpools { list ->
            list.map { sp ->
                when {
                    info?.spoolId == sp.id -> sp.copy(slot = slot)
                    sp.slot == slot -> sp.copy(slot = null)
                    else -> sp
                }
            }
        }
        Chirp.confirm()
    }

    // ---- Replicator stores -----------------------------------------------------

    private fun updateSpools(change: (List<Spool>) -> List<Spool>) {
        _spools.update(change)
        stores.saveSpools(_spools.value)
    }

    fun saveSpool(spool: Spool) {
        updateSpools { list ->
            val others = list.map { if (spool.slot != null && it.id != spool.id && it.slot == spool.slot) it.copy(slot = null) else it }
            if (others.any { it.id == spool.id }) others.map { if (it.id == spool.id) spool else it } else others + spool
        }
        // Keep Box slot assignments in step with the spool.
        _slotInfo.update { map ->
            val cleared = map.filterNot { (slot, info) -> info.spoolId == spool.id && slot != spool.slot }
            spool.slot?.let { cleared + (it to SlotInfo(spool.material, spool.color, spool.id)) } ?: cleared
        }
        stores.saveSlots(_slotInfo.value)
    }

    fun deleteSpool(id: Long) {
        updateSpools { list -> list.filterNot { it.id == id } }
        if (_activeSpool.value == id) setActiveSpool(null)
        _slotInfo.update { m -> m.mapValues { (_, v) -> if (v.spoolId == id) v.copy(spoolId = null) else v } }
        stores.saveSlots(_slotInfo.value)
    }

    fun setActiveSpool(id: Long?) {
        stores.activeSpoolId = id
        _activeSpool.value = id
    }

    /** The spool a print uses: the one in the Box's active slot, else the one marked "in use". */
    private fun spoolInUse(): Spool? {
        val slot = printer.value.box?.activeSlot
        val bySlot = slot?.let { s -> _slotInfo.value[s]?.spoolId ?: _spools.value.firstOrNull { it.slot == s }?.id }
        val id = bySlot ?: _activeSpool.value ?: return null
        return _spools.value.firstOrNull { it.id == id }
    }

    private fun deductFromActiveSpool(mm: Double) {
        if (mm <= 0) return
        val spool = spoolInUse() ?: return
        val grams = StoresRepo.gramsFor(mm, spool.material)
        saveSpool(spool.copy(remainingGrams = (spool.remainingGrams - grams).coerceAtLeast(0.0)))
        say("%.0f g taken off %s".format(grams, spool.colourName.ifBlank { spool.material }))
    }

    // ---- Mission log -------------------------------------------------------------

    fun setLogPeriod(period: LogPeriod) {
        _logPeriod.value = period
        loadHistory()
    }

    fun loadHistory() {
        viewModelScope.launch {
            _historyLoading.value = true
            val since = _logPeriod.value.days?.let { System.currentTimeMillis() / 1000.0 - it * 86_400.0 }
            runCatching { client.history(since) }
                .onSuccess { _history.value = it }
                .onFailure { say("Couldn't load history: ${it.message}") }
            _historyLoading.value = false
        }
    }

    // ---- Starbase Uplink ----------------------------------------------------------

    /** Ask GitHub Releases for a newer build. Silent checks only speak up when one is found. */
    fun checkUplink(silent: Boolean = false) {
        if (_uplinkState.value is UplinkState.Checking || _uplinkState.value is UplinkState.Downloading) return
        viewModelScope.launch {
            if (!silent) _uplinkState.value = UplinkState.Checking
            runCatching { uplink.check(appVersion) }
                .onSuccess { up ->
                    if (up != null) {
                        _uplinkState.value = UplinkState.Available(up)
                        if (silent) {
                            _uplinkAlert.value = true
                            Chirp.alert()
                        } else {
                            Chirp.confirm()
                        }
                    } else {
                        _uplinkState.value = if (silent) UplinkState.Idle else UplinkState.Current
                        if (!silent) Chirp.tap()
                    }
                }
                .onFailure { e ->
                    if (!silent) {
                        _uplinkState.value = UplinkState.Failed("Uplink failed: ${e.message ?: "no signal"}", null)
                        Chirp.deny()
                    }
                }
        }
    }

    fun dismissUplinkAlert() { _uplinkAlert.value = false }

    /** Close the Captain's Log and remember this version has been seen. */
    fun dismissLog() {
        prefs.lastSeenVersion = appVersion
        _logPopup.value = emptyList()
    }

    /** Show the whole Captain's Log (from Config). */
    fun openLog() {
        _logPopup.value = captainsLog
    }

    /** Download (if needed) and hand the upgrade to Android's installer. */
    fun installUpgrade() {
        _uplinkAlert.value = false
        val st = _uplinkState.value
        val upgrade = when (st) {
            is UplinkState.Available -> st.upgrade
            is UplinkState.Ready -> st.upgrade
            is UplinkState.Failed -> st.upgrade
            else -> null
        } ?: return
        if (!uplink.canInstall()) {
            say("Allow installs from Replicator Ops, then tap Install again")
            uplink.openInstallPermission()
            return
        }
        if (st is UplinkState.Ready && st.file.exists()) {
            uplink.install(st.file)
            return
        }
        viewModelScope.launch {
            _uplinkState.value = UplinkState.Downloading(upgrade, 0f)
            runCatching { uplink.download(upgrade) { p -> _uplinkState.value = UplinkState.Downloading(upgrade, p) } }
                .onSuccess { file ->
                    _uplinkState.value = UplinkState.Ready(upgrade, file)
                    Chirp.confirm()
                    uplink.install(file)
                }
                .onFailure { e ->
                    _uplinkState.value = UplinkState.Failed("Transmission lost: ${e.message ?: "download failed"}", upgrade)
                    Chirp.deny()
                }
        }
    }

    fun openReleaseNotes() {
        val page = when (val st = _uplinkState.value) {
            is UplinkState.Available -> st.upgrade.pageUrl
            is UplinkState.Ready -> st.upgrade.pageUrl
            else -> StarbaseUplink.RELEASES_PAGE
        }
        uplink.openReleasePage(page)
    }

    // ---- Load pattern ---------------------------------------------------------------

    /** A file arrived from the picker or Android's share menu. */
    fun receivePattern(uri: Uri) {
        viewModelScope.launch {
            _loadState.value = LoadState.Receiving
            runCatching { loader.receive(uri) }
                .onSuccess { p ->
                    _loadState.value = when (p.kind) {
                        PatternKind.SLICED -> LoadState.Ready(p)
                        PatternKind.NEEDS_SLICING -> LoadState.NeedsSlicing(p).also { Chirp.deny() }
                        PatternKind.WRONG_PRINTER -> LoadState.WrongPrinter(p).also { Chirp.alert() }
                        PatternKind.UNSUPPORTED -> LoadState.Unsupported(p.name).also { Chirp.deny() }
                    }
                }
                .onFailure { e ->
                    _loadState.value = LoadState.Failed(e.message ?: "Couldn't read that file", null)
                    Chirp.deny()
                }
        }
    }

    /** Beam to printer: upload, then open the file's details so it can be replicated. */
    fun beamPattern() {
        val p = when (val st = _loadState.value) {
            is LoadState.Ready -> st.pattern
            is LoadState.Failed -> st.pattern
            else -> null
        } ?: return
        val ep = client.endpoint
        if (ep == null || !printer.value.online) {
            _loadState.value = LoadState.Failed("Printer offline. Connect in Config, then try again.", p)
            Chirp.deny()
            return
        }
        viewModelScope.launch {
            _loadState.value = LoadState.Uploading(p, 0f)
            runCatching { loader.upload(ep, p) { pr -> _loadState.value = LoadState.Uploading(p, pr) } }
                .onSuccess { path ->
                    _loadState.value = LoadState.Idle
                    p.file.delete()
                    Chirp.confirm()
                    say("Pattern stored: $path")
                    refreshFiles()
                    select(Screen.FILES)
                    _openFile.value = GcodeFile(path, System.currentTimeMillis() / 1000.0, p.size)
                    // Moonraker reads the file's metadata just after upload; fetch it, then again shortly after.
                    loadMeta(path, force = true)
                    delay(2500)
                    loadMeta(path, force = true)
                }
                .onFailure { e ->
                    _loadState.value = LoadState.Failed("Transport failed: ${e.message ?: "upload error"}", p)
                    Chirp.deny()
                }
        }
    }

    // ---- On-phone slicer (Beta) ---------------------------------------------------------

    /** Kiri:Moto in a WebView; created the first time the Slicer is used (Beta only). */
    val slicer: SlicerEngine? by lazy { if (BuildConfig.BETA) SlicerEngine(getApplication()) else null }
    private val _sliceSettings = MutableStateFlow(SliceSettings())
    val sliceSettings: StateFlow<SliceSettings> = _sliceSettings.asStateFlow()
    fun setSliceSettings(s: SliceSettings) { _sliceSettings.value = s }

    /** "Slice it here" from the Load pattern pop-up. */
    fun sliceIncoming() {
        val p = when (val st = _loadState.value) {
            is LoadState.NeedsSlicing -> st.pattern
            is LoadState.WrongPrinter -> st.pattern
            else -> null
        } ?: return
        _loadState.value = LoadState.Idle
        openForSlicing(p.file, p.name)
    }

    /** Opens an STL/3MF in the Slicer tab. */
    fun openForSlicing(file: File, name: String) {
        val engine = slicer ?: return
        select(Screen.SLICER)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { ModelReader.read(file, name) } }
                .onSuccess { model ->
                    _sliceSettings.value = SliceSettings.fromProject(model.projectSettings)
                    engine.open(model, 0)
                }
                .onFailure { e -> Chirp.deny(); say(e.message ?: "Couldn't read that model") }
        }
    }

    fun choosePlate(index: Int) {
        val engine = slicer ?: return
        val model = when (val st = engine.state.value) {
            is SliceState.Ready -> st.model
            is SliceState.Done -> st.model
            is SliceState.Failed -> st.model
            else -> null
        } ?: return
        engine.open(model, index)
    }

    /** Back to the 3MF's own settings (or the Q2C defaults for an STL). */
    fun resetSliceSettings() {
        val model = when (val st = slicer?.state?.value) {
            is SliceState.Ready -> st.model
            is SliceState.Done -> st.model
            is SliceState.Failed -> st.model
            else -> null
        }
        _sliceSettings.value = SliceSettings.fromProject(model?.projectSettings)
    }

    /** "Load model" on the Slicer tab. */
    fun receiveForSlicing(uri: Uri) {
        viewModelScope.launch {
            runCatching { loader.receive(uri) }
                .onSuccess { p ->
                    when (p.kind) {
                        PatternKind.SLICED -> _loadState.value = LoadState.Ready(p)
                        PatternKind.UNSUPPORTED -> { Chirp.deny(); say("The slicer takes STL and 3MF files") }
                        else -> openForSlicing(p.file, p.name)
                    }
                }
                .onFailure { e -> Chirp.deny(); say(e.message ?: "Couldn't read that file") }
        }
    }

    fun startSlice() = slicer?.slice(_sliceSettings.value)
    fun cancelSlice() = slicer?.cancel()

    /** Sends the sliced G-code to the printer (the usual Load pattern upload). */
    fun sendSliced() {
        val done = slicer?.state?.value as? SliceState.Done ?: return
        val copy = File(getApplication<Application>().cacheDir, done.gcode.name).also { done.gcode.copyTo(it, overwrite = true) }
        _loadState.value = LoadState.Ready(IncomingPattern(copy.name, copy, PatternKind.SLICED))
        beamPattern()
    }

    fun dismissLoad() {
        (_loadState.value as? LoadState.Ready)?.pattern?.file?.delete()
        _loadState.value = LoadState.Idle
    }

    fun openFileHandled() { _openFile.value = null }

    // ---- Retrieve pattern ------------------------------------------------------------

    /** Download a printer file to the location the user picked in Android's save screen. */
    fun retrievePattern(file: GcodeFile, dest: Uri) {
        val ep = client.endpoint
        if (ep == null || !printer.value.online) {
            _retrieveState.value = RetrieveState.Failed(file.name, "Printer offline. Connect in Config, then try again.")
            Chirp.deny()
            return
        }
        viewModelScope.launch {
            _retrieveState.value = RetrieveState.Retrieving(file.name, 0f)
            runCatching { loader.download(ep, file.path, dest) { pr -> _retrieveState.value = RetrieveState.Retrieving(file.name, pr) } }
                .onSuccess {
                    _retrieveState.value = RetrieveState.Done(file.name)
                    Chirp.confirm()
                }
                .onFailure { e ->
                    _retrieveState.value = RetrieveState.Failed(file.name, e.message ?: "Download failed")
                    Chirp.deny()
                }
        }
    }

    // ---- Timelapse archive -------------------------------------------------------------

    fun refreshReels() {
        if (!printer.value.online) return
        viewModelScope.launch {
            _reelsLoading.value = true
            runCatching { client.listTimelapses() }
                .onSuccess { _reels.value = it; _reelsError.value = null }
                .onFailure { e ->
                    _reels.value = emptyList()
                    _reelsError.value = if ((e.message ?: "").contains("timelapse", true) || (e.message ?: "").contains("root", true))
                        "No timelapse archive on this printer. Turn timelapse on in QIDI Studio or Fluidd, and moonraker-timelapse saves videos here."
                    else e.message ?: "Couldn't read the timelapse archive"
                }
            _reelsLoading.value = false
        }
    }

    fun reelUrl(f: TimelapseFile): String? = client.endpoint?.fileUrl("timelapse", f.path)
    fun reelThumbUrl(f: TimelapseFile): String? = f.thumbnailPath?.let { client.endpoint?.fileUrl("timelapse", it) }

    fun saveReel(f: TimelapseFile, dest: Uri) {
        val ep = client.endpoint ?: return
        viewModelScope.launch {
            _reelState.value = ReelState.Busy(f.name, "Saving", 0f)
            runCatching {
                loader.downloadUrl(ep, ep.fileUrl("timelapse", f.path), dest) { pr -> _reelState.value = ReelState.Busy(f.name, "Saving", pr) }
            }.onSuccess { Chirp.confirm(); say("${f.name} saved to your device") }
                .onFailure { Chirp.deny(); say(it.message ?: "Save failed") }
            _reelState.value = ReelState.Idle
        }
    }

    /** Copies the video to the phone, then opens Android's share sheet. */
    fun shareReel(f: TimelapseFile, launch: (Intent) -> Unit) {
        val ep = client.endpoint ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            _reelState.value = ReelState.Busy(f.name, "Preparing", 0f)
            runCatching {
                val dir = File(app.cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, PatternLoader.safeName(f.name))
                loader.downloadUrl(ep, ep.fileUrl("timelapse", f.path), Uri.fromFile(out)) { pr -> _reelState.value = ReelState.Busy(f.name, "Preparing", pr) }
                FileProvider.getUriForFile(app, "${app.packageName}.updates", out)
            }.onSuccess { uri ->
                val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                launch(Intent.createChooser(send, "Share timelapse"))
            }.onFailure { Chirp.deny(); say(it.message ?: "Couldn't prepare the video") }
            _reelState.value = ReelState.Idle
        }
    }

    fun deleteReel(f: TimelapseFile) = action("Timelapse deleted") {
        client.deleteTimelapse(f.path)
        f.thumbnailPath?.let { runCatching { client.deleteTimelapse(it) } }
        _reels.update { list -> list.filterNot { it.path == f.path } }
    }

    fun dismissRetrieve() { _retrieveState.value = RetrieveState.Idle }

    companion object {
        const val TEMP_SAMPLES = 900
    }
}

/** "Cube_id_0_copy_1" -> "Cube #2"; model file extensions and underscores are dropped. */
fun prettyObjectName(name: String): String {
    val m = Regex("^(.*?)_id_\\d+_copy_(\\d+)$", RegexOption.IGNORE_CASE).find(name)
    val base = m?.groupValues?.get(1) ?: name
    val clean = base.replace(Regex("\\.(stl|3mf|obj|step|stp)(?=$|[_ ])", RegexOption.IGNORE_CASE), "").replace('_', ' ').trim()
    return if (m != null) "$clean #${m.groupValues[2].toInt() + 1}" else clean
}
