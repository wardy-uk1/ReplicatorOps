package dev.vantara.replicatorops.slicer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.Writer

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
    ) : SliceState
    data class Failed(val message: String, val model: ModelFile?, val plate: Plate?) : SliceState
}

/**
 * Runs Kiri:Moto (bundled in the Beta's assets) in a WebView. The page is served from
 * https://appassets.androidplatform.net/ so its module workers and WebAssembly load
 * normally, and the model to slice is served from the app cache under /local/.
 * One instance lives as long as the app, so slicing carries on when you change tabs.
 */
class SlicerEngine(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val work = File(app.cacheDir, "slicer").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<SliceState>(SliceState.Starting)
    val state: StateFlow<SliceState> = _state.asStateFlow()

    private var ready = false
    private var pendingLoad: Pair<ModelFile, Plate>? = null
    private var out: Writer? = null
    private var outFile: File? = null

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(app).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/local/", WebViewAssetLoader.InternalStoragePathHandler(app, work))
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(app))
            .build()
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        addJavascriptInterface(Bridge(), "Android")
        loadUrl("https://appassets.androidplatform.net/slicer/index.html")
    }

    /** Detaches the WebView from whatever screen showed it last, so it can be shown again. */
    fun detach() { (webView.parent as? ViewGroup)?.removeView(webView) }

    /** Opens a model file and shows [plateIndex] (0-based) ready to slice. */
    fun open(model: ModelFile, plateIndex: Int = 0) {
        val plate = model.plates.getOrElse(plateIndex) { model.plates.first() }
        _state.value = SliceState.Loading(model.name)
        // Binary STL of this plate, served to the page as /local/plate.stl.
        File(work, "plate.stl").outputStream().buffered().use { Stl.writeBinary(plate.triangles, it) }
        if (!ready) { pendingLoad = model to plate; return }
        loadIntoPage(model, plate)
    }

    private fun loadIntoPage(model: ModelFile, plate: Plate) {
        pendingLoad = model to plate
        js("slicer.loadModel('/local/plate.stl?t=${System.currentTimeMillis()}', 'ffcc66')")
    }

    fun slice(settings: SliceSettings) {
        val (model, plate) = currentModel() ?: return
        outFile?.delete()
        val file = File(work, safeName(model.name, plate) + ".gcode")
        outFile = file
        out = file.bufferedWriter()
        _state.value = SliceState.Slicing(model, plate, "Preparing", 0)
        val cfg = buildJsonObject {
            put("device", settings.kiriDevice())
            put("process", settings.kiriProcess())
        }.toString()
        js("slicer.slice(${JsonPrimitive(cfg)})")
    }

    fun cancel() {
        js("slicer.cancel()")
        closeOut()
        val (model, plate) = currentModel() ?: return
        _state.value = SliceState.Ready(model, plate)
    }

    fun clear() {
        pendingLoad = null
        closeOut()
        js("slicer.clearModel()")
        _state.value = SliceState.Empty
    }

    private fun currentModel(): Pair<ModelFile, Plate>? = when (val s = _state.value) {
        is SliceState.Ready -> s.model to s.plate
        is SliceState.Slicing -> s.model to s.plate
        is SliceState.Done -> s.model to s.plate
        is SliceState.Failed -> if (s.model != null && s.plate != null) s.model to s.plate else null
        else -> pendingLoad
    }

    private fun closeOut() {
        runCatching { out?.close() }
        out = null
    }

    private fun js(code: String) = main.post { webView.evaluateJavascript(code, null) }

    private fun safeName(name: String, plate: Plate): String {
        val base = name.substringBeforeLast('.').removeSuffix(".gcode").replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "pattern" }
        return if (plate.number > 1) "${base}_plate${plate.number}" else base
    }

    /** Calls from slicer.js (on a WebView background thread). */
    private inner class Bridge {
        @JavascriptInterface fun onReady() {
            ready = true
            val p = pendingLoad
            if (p != null) main.post { loadIntoPage(p.first, p.second) }
            else if (_state.value == SliceState.Starting) _state.value = SliceState.Empty
        }

        @JavascriptInterface fun onModel(info: String) {
            val p = pendingLoad ?: return
            _state.value = SliceState.Ready(p.first, p.second)
        }

        @JavascriptInterface fun onProgress(stage: String, percent: Int) {
            val (model, plate) = currentModel() ?: return
            if (_state.value is SliceState.Slicing) _state.value = SliceState.Slicing(model, plate, stage, percent.coerceIn(0, 100))
        }

        @JavascriptInterface fun onChunk(text: String) {
            out?.write(text)
        }

        @JavascriptInterface fun onDone(infoJson: String) {
            val (model, plate) = currentModel() ?: return
            closeOut()
            val raw = outFile ?: return
            val info = runCatching { json.parseToJsonElement(infoJson).jsonObject }.getOrNull()
            // Thumbnails drawn here (the page's WebGL view isn't available on every phone).
            val mesh = runCatching { MeshRenderer(plate.triangles) }.getOrNull()
            val thumb = runCatching { mesh?.thumbnailBase64(300) }.getOrNull()
                ?: (info?.get("thumbnail") as? JsonPrimitive)?.contentOrNull
            val small = runCatching { mesh?.thumbnailBase64(96) }.getOrNull()
                ?: (info?.get("thumbSmall") as? JsonPrimitive)?.contentOrNull
            // Final file: thumbnails (for the printer's screen and Moonraker) + G-code.
            val final = File(work, raw.nameWithoutExtension + "_rops.gcode")
            final.bufferedWriter().use { w ->
                raw.bufferedReader().use { r ->
                    // Keep Kiri's "; Generated by" line first so Moonraker recognises the slicer.
                    val first = r.readLine()
                    if (first != null) w.write(first + "\n")
                    small?.let { writeThumbnail(w, 96, it) }
                    thumb?.let { writeThumbnail(w, 300, it) }
                    r.copyTo(w)
                }
            }
            raw.delete()
            val target = File(work, raw.name)
            final.renameTo(target)
            _state.value = SliceState.Done(
                model, plate, target,
                layers = (info?.get("layers") as? JsonPrimitive)?.intOrNull ?: 0,
                seconds = (info?.get("seconds") as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                filamentMm = (info?.get("filamentMm") as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            )
        }

        @JavascriptInterface fun onError(message: String) {
            closeOut()
            val cur = currentModel()
            _state.value = SliceState.Failed(message.ifBlank { "Slicing failed" }, cur?.first, cur?.second)
        }
    }

    private fun writeThumbnail(w: Writer, px: Int, base64: String) {
        w.write(";\n; thumbnail begin ${px}x$px ${base64.length}\n")
        base64.chunked(78).forEach { w.write("; $it\n") }
        w.write("; thumbnail end\n;\n")
    }
}
