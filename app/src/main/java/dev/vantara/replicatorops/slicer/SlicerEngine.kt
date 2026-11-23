package dev.vantara.replicatorops.slicer

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Where the on-phone slicer is up to. */
sealed interface SliceState {
    data object Starting : SliceState
    data object Empty : SliceState
    data class Loading(val name: String) : SliceState
    data class Ready(val model: ModelFile, val plate: Plate) : SliceState
    data class Slicing(val model: ModelFile, val plate: Plate, val stage: String, val percent: Int) : SliceState
    data class Done(
        val model: ModelFile, val plate: Plate, val gcode: File,
        val layers: Int, val seconds: Double, val filamentMm: Double,
        /** 300 px PNG of the model, for the Files screen until the printer has read the file. */
        val thumbnail: File? = null,
    ) : SliceState
    data class Failed(val message: String, val model: ModelFile?, val plate: Plate?) : SliceState
}

/**
 * Runs CuraEngine (bundled as libcuraengine.so, built from Ultimaker's source
 * in CI) as a separate process. The app works out every Cura setting itself ([CuraSettings]),
 * hands CuraEngine one JSON file of values plus the plate as an STL, then finishes the G-code
 * for the Q2C ([CuraGcode]). One instance lives as long as the app, so slicing carries on
 * when you change tabs.
 */
class SlicerEngine(context: Context) {
    private val app = context.applicationContext
    private val work = File(app.cacheDir, "slicer").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<SliceState>(SliceState.Empty)
    val state: StateFlow<SliceState> = _state.asStateFlow()

    private val table: CuraSettings by lazy {
        CuraSettings.load(app.assets.open("cura/settings.json").bufferedReader().use { it.readText() })
    }
    private val binary: File get() = File(app.applicationInfo.nativeLibraryDir, "libcuraengine.so")

    private var job: Job? = null
    @Volatile private var process: Process? = null

    /** Opens a model file and shows [plateIndex] (0-based) ready to slice. */
    fun open(model: ModelFile, plateIndex: Int = 0) {
        val plate = model.plates.getOrElse(plateIndex) { model.plates.first() }
        cancelRunning()
        _state.value = SliceState.Ready(model, plate)
    }

    fun slice(settings: SliceSettings) {
        val (model, plate) = currentModel() ?: return
        cancelRunning()
        _state.value = SliceState.Slicing(model, plate, "Preparing", 0)
        job = scope.launch {
            val result = runCatching { run(model, plate, settings) }
            if (!isActive) return@launch
            result.onSuccess { _state.value = it }
                .onFailure { e -> _state.value = SliceState.Failed(e.message ?: "Slicing failed", model, plate) }
        }
    }

    private fun run(model: ModelFile, plate: Plate, settings: SliceSettings): SliceState {
        val bin = binary
        if (!bin.exists()) error("The slicing engine isn't available on this phone (it needs a 64-bit ARM phone).")
        work.listFiles()?.forEach { if (it.name.endsWith(".gcode") || it.name.endsWith(".png")) it.delete() }

        // Settings: every Cura value worked out from the screen's settings.
        progress(model, plate, "Preparing", 2)
        val values = table.resolve(settings.curaOverrides())
        val json = File(work, "settings.json").apply { writeText(CuraSettings.toEngineJson(values, table).toString()) }

        // The plate, placed where the 3MF put it (or centred), in Cura's centred bed coordinates.
        val stl = File(work, "plate.stl")
        stl.outputStream().buffered().use { Stl.writeBinary(CuraGcode.placeOnBed(plate.triangles, 270f, 270f), it) }

        val base = safeName(model.name, plate)
        val raw = File(work, "$base.raw.gcode")
        val proc = ProcessBuilder(bin.path, "slice", "-j", json.path, "-l", stl.path, "-o", raw.path)
            .directory(work)
            .redirectErrorStream(true)
            .start()
        process = proc
        val log = CuraGcode.EngineLog()
        proc.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                log.add(line)?.let { pct -> progress(model, plate, "Slicing", (5 + pct * 0.9).toInt().coerceAtMost(95)) }
            }
        }
        val exit = proc.waitFor()
        process = null
        if (exit != 0 || !raw.exists() || raw.length() == 0L) {
            error(log.lastError ?: "The slicer stopped (code $exit)")
        }

        progress(model, plate, "Writing G-code", 96)
        val mesh = runCatching { MeshRenderer(plate.triangles) }.getOrNull()
        val thumb = runCatching { mesh?.thumbnailBase64(300) }.getOrNull()
        val small = runCatching { mesh?.thumbnailBase64(96) }.getOrNull()
        val target = File(work, "$base.gcode")
        val info = CuraGcode.finish(raw, target, log.header, listOfNotNull(small?.let { 96 to it }, thumb?.let { 300 to it }))
        raw.delete()
        val png = thumb?.let { b64 ->
            runCatching { File(work, "$base-thumb.png").also { it.writeBytes(Base64.decode(b64, Base64.DEFAULT)) } }.getOrNull()
        }
        return SliceState.Done(
            model, plate, target, thumbnail = png,
            layers = info.layers,
            seconds = info.seconds ?: log.seconds ?: 0.0,
            filamentMm = info.filamentMm ?: 0.0,
        )
    }

    private fun progress(model: ModelFile, plate: Plate, stage: String, percent: Int) {
        if (_state.value is SliceState.Slicing) _state.value = SliceState.Slicing(model, plate, stage, percent)
    }

    private fun cancelRunning() {
        job?.cancel()
        job = null
        process?.destroyForcibly()
        process = null
    }

    fun cancel() {
        cancelRunning()
        val (model, plate) = currentModel() ?: return
        _state.value = SliceState.Ready(model, plate)
    }

    fun clear() {
        cancelRunning()
        _state.value = SliceState.Empty
    }

    /** Nothing to tidy up any more (the slicer used to live in a WebView). */
    fun detach() = Unit

    private fun currentModel(): Pair<ModelFile, Plate>? = when (val s = _state.value) {
        is SliceState.Ready -> s.model to s.plate
        is SliceState.Slicing -> s.model to s.plate
        is SliceState.Done -> s.model to s.plate
        is SliceState.Failed -> if (s.model != null && s.plate != null) s.model to s.plate else null
        else -> null
    }

    private fun safeName(name: String, plate: Plate): String {
        val base = name.substringBeforeLast('.').removeSuffix(".gcode").replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "pattern" }
        return if (plate.number > 1) "${base}_plate${plate.number}" else base
    }
}
