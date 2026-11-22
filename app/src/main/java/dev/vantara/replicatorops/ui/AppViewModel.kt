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
import dev.vantara.replicatorops.data.Upgrade
import dev.vantara.replicatorops.BuildConfig
import java.io.File
import dev.vantara.replicatorops.data.MoonrakerClient
import dev.vantara.replicatorops.data.Prefs
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
    FILES("Files"),
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

val SIDEBAR_SCREENS = listOf(Screen.STATUS, Screen.CONTROL, Screen.FILES, Screen.CONFIG)

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

    private val _screen = MutableStateFlow(if (prefs.host.isBlank()) Screen.CONFIG else Screen.STATUS)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _files = MutableStateFlow<List<GcodeFile>>(emptyList())
    val files: StateFlow<List<GcodeFile>> = _files.asStateFlow()

    private val _filesLoading = MutableStateFlow(false)
    val filesLoading: StateFlow<Boolean> = _filesLoading.asStateFlow()

    private val _meta = MutableStateFlow<Map<String, FileMeta>>(emptyMap())
    val meta: StateFlow<Map<String, FileMeta>> = _meta.asStateFlow()
    private val metaRequested = HashSet<String>()

    private val _webcamUrl = MutableStateFlow<String?>(null)
    val webcamUrl: StateFlow<String?> = _webcamUrl.asStateFlow()

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

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    init {
        Chirp.enabled = prefs.chirps
        prefs.endpoint()?.let { client.connect(it) }
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
                    _webcamUrl.value = runCatching { client.webcamSnapshotUrl() }.getOrNull()
                } else {
                    _webcamUrl.value = null
                }
            }
        }
        viewModelScope.launch { client.fileListChanged.collect { refreshFiles() } }
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
            say("Enter the printer's address")
            return
        }
        prefs.host = host.trim()
        prefs.apiKey = apiKey.trim()
        metaRequested.clear()
        _meta.value = emptyMap()
        client.connect(ep)
    }

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

    fun deleteFile(path: String) = action("File deleted") {
        client.call("server.files.delete_file", buildJsonObject { put("path", "gcodes/$path") })
    }

    // ---- Print control -------------------------------------------------------

    fun pause() = action { client.call("printer.print.pause") }
    fun resume() = action { client.call("printer.print.resume") }
    fun cancel() = action("Print cancelled") { client.call("printer.print.cancel") }
    fun emergencyStop() = action("Emergency stop sent") { client.call("printer.emergency_stop") }
    fun firmwareRestart() = action { client.call("printer.firmware_restart") }

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
    fun motorsOff() = gcode("M84", "Motors off")

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
        gcode("SLOT_RFID_READ SLOT=slot$slot", "Reading tag in slot ${slot + 1}")
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

    companion object {
        const val TEMP_SAMPLES = 900
    }
}
