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
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** What kind of file arrived. */
enum class PatternKind { SLICED, NEEDS_SLICING, WRONG_PRINTER, UNSUPPORTED }

/** Which program made a model file, so the "needs slicing" advice can be specific. */
enum class PatternOrigin { PRUSA, BAMBU, ORCA_QIDI, CURA, PLAIN_MODEL, UNKNOWN }

/** A file received for Load pattern, copied into the app's cache. */
data class IncomingPattern(
    val name: String,
    val file: File,
    val kind: PatternKind,
    val origin: PatternOrigin = PatternOrigin.UNKNOWN,
) {
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
        val kind = classify(out)
        IncomingPattern(out.name, out, kind, if (kind == PatternKind.NEEDS_SLICING) origin(out) else PatternOrigin.UNKNOWN)
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

    /** Retrieve pattern: streams a file from the printer's gcodes folder into a file the user chose. */
    suspend fun download(ep: Endpoint, path: String, dest: Uri, onProgress: (Float) -> Unit) =
        downloadUrl(ep, ep.gcodeFileUrl(path), dest, onProgress)

    /** Streams any printer file URL into a content Uri (save screen) or a file Uri. */
    suspend fun downloadUrl(ep: Endpoint, url: String, dest: Uri, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply {
            ep.apiKey?.let { header("X-Api-Key", it) }
        }.build()
        uploadHttp.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Printer refused the download (${r.code})")
            val body = r.body ?: throw IOException("Empty file")
            val total = body.contentLength()
            val out = context.contentResolver.openOutputStream(dest, "w") ?: throw IOException("Can't write to that location")
            body.byteStream().use { input ->
                out.use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
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
                SLICED_EXT.any { n.endsWith(it) } ->
                    if (isForeign(gcodeHeader(file.inputStream()))) PatternKind.WRONG_PRINTER else PatternKind.SLICED
                n.endsWith(".3mf") -> {
                    val header = threeMfGcodeHeader(file)
                    when {
                        header == null -> PatternKind.NEEDS_SLICING
                        isForeign(header) -> PatternKind.WRONG_PRINTER
                        else -> PatternKind.SLICED
                    }
                }
                MODEL_EXT.any { n.endsWith(it) } -> PatternKind.NEEDS_SLICING
                else -> PatternKind.UNSUPPORTED
            }
        }

        /**
         * G-code from Bambu Studio is made for Bambu printers (different start routine, bed and
         * temperatures) and must not run on a QIDI. QIDI Studio says "QIDIStudio" here instead.
         */
        fun isForeign(header: String): Boolean =
            Regex("""generated by\s+BambuStudio""", RegexOption.IGNORE_CASE).containsMatchIn(header)

        /** First few KB of G-code, where slicers write "; generated by …". */
        private fun gcodeHeader(input: java.io.InputStream): String = input.use { src ->
            val buf = ByteArray(8192)
            var len = 0
            while (len < buf.size) {
                val r = src.read(buf, len, buf.size - len)
                if (r < 0) break
                len += r
            }
            String(buf, 0, len, Charsets.UTF_8)
        }

        /**
         * Header of the G-code in a ".3mf", or null if it has none (not sliced).
         * A real 3MF is a zip with Metadata/plate_N.gcode inside. But files that have been on
         * a QIDI printer can be plain G-code that just keeps the ".gcode.3mf" name, so check that too.
         */
        fun threeMfGcodeHeader(file: File): String? {
            if (!isZip(file)) {
                val head = gcodeHeader(file.inputStream())
                return head.takeIf { looksLikeGcode(it) }
            }
            // ZipFile reads the central directory, so it copes with entries ZipInputStream can't.
            return runCatching {
                ZipFile(file).use { zip ->
                    val entry = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.lowercase().endsWith(".gcode") }
                        .sortedBy { it.name }
                        .firstOrNull() ?: return@use null
                    gcodeHeader(zip.getInputStream(entry))
                }
            }.getOrNull()
        }

        private fun isZip(file: File): Boolean = runCatching {
            file.inputStream().use { val b = ByteArray(2); it.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }
        }.getOrDefault(false)

        /** Slicer comments or G/M commands near the top of a text file. */
        fun looksLikeGcode(head: String): Boolean =
            Regex("""(?m)^\s*;\s*(generated by|HEADER_BLOCK_START|THUMBNAIL_BLOCK_START|thumbnail begin)""", RegexOption.IGNORE_CASE).containsMatchIn(head) ||
                Regex("""(?m)^\s*[GM]\d+\b""").findAll(head).count() >= 3

        /** Reads a 3MF's metadata file names to tell which slicer saved it. */
        fun origin(file: File): PatternOrigin {
            if (!file.name.lowercase().endsWith(".3mf")) return PatternOrigin.PLAIN_MODEL
            val names = runCatching {
                ZipInputStream(file.inputStream().buffered()).use { zip ->
                    generateSequence { zip.nextEntry }.map { it.name.lowercase() }.toList()
                }
            }.getOrDefault(emptyList())
            return when {
                names.any { "slic3r_pe" in it || "prusa" in it } -> PatternOrigin.PRUSA
                names.any { "cura" in it } -> PatternOrigin.CURA
                names.any { it.endsWith("project_settings.config") || it.endsWith("model_settings.config") } ->
                    if (names.any { "qidi" in it || "orca" in it }) PatternOrigin.ORCA_QIDI else PatternOrigin.BAMBU
                else -> PatternOrigin.UNKNOWN
            }
        }

        fun threeMfHasGcode(file: File): Boolean = runCatching {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                generateSequence { zip.nextEntry }.any { it.name.lowercase().endsWith(".gcode") }
            }
        }.getOrDefault(false)
    }
}
