package dev.vantara.replicatorops.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** What kind of file arrived. */
enum class PatternKind { SLICED, NEEDS_SLICING, UNSUPPORTED }

/** A file received for Load pattern, copied into the app's cache. */
data class IncomingPattern(val name: String, val file: File, val kind: PatternKind) {
    val size: Long get() = file.length()
}

/**
 * Load pattern: receives a file (picker or Android share), checks it is sliced,
 * and uploads it to Moonraker's gcodes folder.
 */
class PatternLoader(private val context: Context, http: OkHttpClient) {

    // Uploads can be large and slow over Tailscale: no write timeout, generous read timeout.
    private val uploadHttp = http.newBuilder()
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Copies the shared/picked file into the cache and works out whether it can be printed. */
    suspend fun receive(uri: Uri): IncomingPattern = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "pattern"
        val dir = File(context.cacheDir, "incoming").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, safeName(name))
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't read that file")
        input.use { src -> out.outputStream().use { src.copyTo(it) } }
        IncomingPattern(out.name, out, classify(out))
    }

    /** Uploads to Moonraker; returns the path of the new file in the gcodes root. */
    suspend fun upload(ep: Endpoint, p: IncomingPattern, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        val total = p.file.length()
        val fileBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = total
            override fun writeTo(sink: BufferedSink) {
                p.file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        if (total > 0) onProgress((sent.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("root", "gcodes")
            .addFormDataPart("file", p.name, fileBody)
            .build()
        val req = Request.Builder().url(ep.resolve("/server/files/upload")).post(body).apply {
            ep.apiKey?.let { header("X-Api-Key", it) }
        }.build()
        uploadHttp.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching {
                    (json.parseToJsonElement(text).jsonObject["error"] as? JsonObject)?.get("message")?.let { (it as JsonPrimitive).contentOrNull }
                }.getOrNull()
                throw IOException(msg ?: "Printer refused the upload (${r.code})")
            }
            val item = runCatching { json.parseToJsonElement(text).jsonObject["item"] as? JsonObject }.getOrNull()
            (item?.get("path") as? JsonPrimitive)?.contentOrNull ?: p.name
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        private val SLICED_EXT = listOf(".gcode", ".gco", ".g")
        private val MODEL_EXT = listOf(".stl", ".obj", ".step", ".stp", ".amf")

        /** Keeps letters, digits and a few safe symbols; Moonraker paths can't contain slashes. */
        fun safeName(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "pattern.gcode" }

        /**
         * .gcode = sliced. A .3mf is sliced only if it carries G-code inside
         * (QIDI Studio / Orca "*.gcode.3mf" have Metadata/plate_N.gcode). STL/OBJ/STEP need slicing.
         */
        fun classify(file: File): PatternKind {
            val n = file.name.lowercase()
            return when {
                SLICED_EXT.any { n.endsWith(it) } -> PatternKind.SLICED
                n.endsWith(".3mf") -> if (threeMfHasGcode(file)) PatternKind.SLICED else PatternKind.NEEDS_SLICING
                MODEL_EXT.any { n.endsWith(it) } -> PatternKind.NEEDS_SLICING
                else -> PatternKind.UNSUPPORTED
            }
        }

        fun threeMfHasGcode(file: File): Boolean = runCatching {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                generateSequence { zip.nextEntry }.any { it.name.lowercase().endsWith(".gcode") }
            }
        }.getOrDefault(false)
    }
}
