package dev.vantara.replicatorops.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Top-down shape of one object on the plate: its first-layer extrusion lines
 * (x1, y1, x2, y2, …) and the convex hull around them (for tapping and shading).
 */
class Outline(val segments: FloatArray, val hull: List<Pair<Double, Double>>)

/**
 * Target lock outlines. Klipper's exclude_object often only knows each object's
 * centre, so we read the print file's first layers and draw what each object
 * actually lays down, labelled by EXCLUDE_OBJECT_START / _END.
 */
object GcodeOutlines {

    /** Layers above the object's first one to include (mm). */
    private const val DEPTH_MM = 0.6
    private const val MAX_SEGMENTS = 8000
    private const val MAX_BYTES = 120L * 1024 * 1024

    suspend fun fetch(http: OkHttpClient, ep: Endpoint, path: String): Map<String, Outline> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(ep.gcodeFileUrl(path)).apply {
            ep.apiKey?.let { header("X-Api-Key", it) }
        }.build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Couldn't read the print file (${r.code})")
            val body = r.body ?: throw IOException("Empty print file")
            parse(openGcode(body.byteStream()))
        }
    }

    /** A ".gcode.3mf" may be a zip with the G-code inside, or plain G-code. */
    private fun openGcode(raw: InputStream): InputStream {
        val input = BufferedInputStream(raw, 64 * 1024)
        input.mark(4)
        val pk = input.read() == 'P'.code && input.read() == 'K'.code
        input.reset()
        if (!pk) return input
        val zip = ZipInputStream(input)
        while (true) {
            val e = zip.nextEntry ?: throw IOException("No G-code inside this 3MF")
            if (!e.isDirectory && e.name.lowercase().endsWith(".gcode")) return zip
        }
    }

    fun parse(input: InputStream): Map<String, Outline> {
        val segs = HashMap<String, FloatArrayBuilder>()
        val firstZ = HashMap<String, Double>()
        val done = HashSet<String>()
        val defined = LinkedHashSet<String>()

        var x = 0.0; var y = 0.0; var z = 0.0; var e = 0.0
        var absXY = true; var absE = true
        var current: String? = null
        var bytes = 0L

        input.bufferedReader().use { reader ->
            while (true) {
                val raw = reader.readLine() ?: break
                bytes += raw.length + 1
                if (bytes > MAX_BYTES) break
                val line = raw.substringBefore(';').trim()
                if (line.isEmpty()) continue
                val upper = line.uppercase()
                when {
                    upper.startsWith("EXCLUDE_OBJECT_DEFINE") -> nameOf(line)?.let { defined += it }
                    upper.startsWith("EXCLUDE_OBJECT_START") -> current = nameOf(line)
                    upper.startsWith("EXCLUDE_OBJECT_END") -> {
                        current = null
                        // Stop once every object has its first layers.
                        if (defined.isNotEmpty() && done.containsAll(defined)) break
                    }
                    upper == "G90" -> absXY = true
                    upper == "G91" -> absXY = false
                    upper == "M82" -> absE = true
                    upper == "M83" -> absE = false
                    upper.startsWith("G92") -> word(line, 'E')?.let { e = it }
                    upper.startsWith("G0") || upper.startsWith("G1") || upper.startsWith("G2") || upper.startsWith("G3") -> {
                        val code = upper.substringBefore(' ')
                        if (code !in MOVES) continue
                        val nx = word(line, 'X')?.let { if (absXY) it else x + it } ?: x
                        val ny = word(line, 'Y')?.let { if (absXY) it else y + it } ?: y
                        val nz = word(line, 'Z')?.let { if (absXY) it else z + it } ?: z
                        val ev = word(line, 'E')
                        val extruding = ev != null && (if (absE) ev > e else ev > 0)
                        if (ev != null) e = if (absE) ev else e + ev
                        val obj = current
                        if (obj != null && extruding && (nx != x || ny != y) && obj !in done) {
                            val fz = firstZ.getOrPut(obj) { nz }
                            if (nz > fz + DEPTH_MM) {
                                done += obj
                            } else {
                                val b = segs.getOrPut(obj) { FloatArrayBuilder() }
                                if (b.size < MAX_SEGMENTS * 4) b.add(x.toFloat(), y.toFloat(), nx.toFloat(), ny.toFloat())
                            }
                        }
                        x = nx; y = ny; z = nz
                    }
                }
            }
        }
        return segs.mapValues { (_, b) ->
            val arr = b.toArray()
            Outline(arr, hull(arr))
        }
    }

    private val MOVES = setOf("G0", "G1", "G2", "G3", "G00", "G01", "G02", "G03")

    private fun nameOf(line: String): String? =
        Regex("""NAME=("[^"]*"|'[^']*'|\S+)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.trim('"', '\'')

    private fun word(line: String, letter: Char): Double? {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if ((c == letter || c == letter.lowercaseChar()) && (i == 0 || line[i - 1] == ' ')) {
                var j = i + 1
                while (j < line.length && line[j] != ' ') j++
                return line.substring(i + 1, j).toDoubleOrNull()
            }
            i++
        }
        return null
    }

    /** Convex hull (Andrew's monotone chain) of segment end points. */
    fun hull(segments: FloatArray): List<Pair<Double, Double>> {
        val pts = ArrayList<Pair<Double, Double>>(segments.size / 2)
        var i = 0
        while (i + 1 < segments.size) { pts += segments[i].toDouble() to segments[i + 1].toDouble(); i += 2 }
        val sorted = pts.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (sorted.size < 3) return sorted
        fun cross(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val lower = ArrayList<Pair<Double, Double>>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1)
            lower += p
        }
        val upper = ArrayList<Pair<Double, Double>>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1)
            upper += p
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(256)
        var size = 0
            private set

        fun add(a: Float, b: Float, c: Float, d: Float) {
            if (size + 4 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = a; data[size++] = b; data[size++] = c; data[size++] = d
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
