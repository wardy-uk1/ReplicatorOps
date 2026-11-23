package dev.vantara.replicatorops.slicer

import java.io.File

/** CuraEngine's command-line output, and the finishing touches its G-code needs for the Q2C. */
object CuraGcode {

    /** Reads CuraEngine's log as it runs. */
    class EngineLog {
        /** The real header ("Gcode header after slicing"); the file itself only has placeholders. */
        val header = mutableListOf<String>()
        var seconds: Double? = null; private set
        var lastError: String? = null; private set
        private var inHeader = false

        /** Returns a progress percentage (0–100) when the line is one. */
        fun add(line: String): Int? {
            if (line.startsWith("#PROGRESS ")) return line.removePrefix("#PROGRESS ").trim().toIntOrNull()
            if (inHeader) {
                if (line.isBlank() || line.startsWith("[")) inHeader = false else { header += line; return null }
            }
            val marker = "Gcode header after slicing:"
            val i = line.indexOf(marker)
            if (i >= 0) {
                header.clear()
                line.substring(i + marker.length).trim().takeIf { it.isNotEmpty() }?.let { header += it }
                inHeader = true
                return null
            }
            Regex("Print time \\(s\\): (\\d+)").find(line)?.let { seconds = it.groupValues[1].toDouble() }
            if (line.contains("[error]")) lastError = line.substringAfter("[error]").trim()
            return null
        }
    }

    data class Info(val layers: Int, val seconds: Double?, val filamentMm: Double?)

    /**
     * Shifts the plate into Cura's coordinates (origin at the bed centre). A plate that sits
     * on the bed keeps its place from the 3MF; anything else is centred.
     */
    fun placeOnBed(tris: FloatArray, bedW: Float, bedD: Float): FloatArray {
        if (tris.isEmpty()) return tris
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var i = 0
        while (i < tris.size) {
            val x = tris[i]; val y = tris[i + 1]; val z = tris[i + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z
            i += 3
        }
        val onBed = minX >= 0f && maxX <= bedW && minY >= 0f && maxY <= bedD
        val dx = if (onBed) -bedW / 2 else -(minX + maxX) / 2
        val dy = if (onBed) -bedD / 2 else -(minY + maxY) / 2
        val out = FloatArray(tris.size)
        i = 0
        while (i < tris.size) {
            out[i] = tris[i] + dx; out[i + 1] = tris[i + 1] + dy; out[i + 2] = tris[i + 2] - minZ
            i += 3
        }
        return out
    }

    /**
     * Writes the finished G-code: the real header and thumbnails at the top (for the printer's
     * screen and Moonraker), the layer total in PRINT_START's stats, and the current layer
     * after every ";LAYER:n" so the Q2C's screen and Moonraker can follow along.
     */
    fun finish(raw: File, target: File, header: List<String>, thumbnails: List<Pair<Int, String>>): Info {
        val layers = raw.bufferedReader().useLines { lines ->
            lines.take(5000).firstOrNull { it.startsWith(";LAYER_COUNT:") }?.substringAfter(':')?.trim()?.toIntOrNull()
        } ?: 0
        var seconds: Double? = null
        var filament: Double? = null
        fun scan(line: String) {
            if (line.startsWith(";TIME:")) line.substringAfter(':').trim().toDoubleOrNull()?.let { seconds = it }
            if (line.startsWith(";Filament used:")) {
                line.substringAfter(':').trim().removeSuffix("m").split(',').firstOrNull()?.trim()?.toDoubleOrNull()?.let { filament = it * 1000 }
            }
        }
        header.forEach(::scan)
        target.bufferedWriter().use { w ->
            raw.bufferedReader().useLines { lines ->
                var top = true
                fun endTop(generated: String?) {
                    top = false
                    header.forEach { w.write(it); w.write("\n") }
                    generated?.let { w.write(it); w.write("\n") }
                    for ((px, b64) in thumbnails) {
                        w.write(";\n; thumbnail begin ${px}x$px ${b64.length}\n")
                        b64.chunked(78).forEach { w.write("; $it\n") }
                        w.write("; thumbnail end\n;\n")
                    }
                }
                for (line in lines) {
                    if (top) {
                        when {
                            line.startsWith(";Generated with") -> { endTop(line); continue }
                            line.isBlank() || line.startsWith(";") -> {
                                // CuraEngine's placeholder header: replaced by the real one.
                                if (header.isEmpty()) { scan(line); w.write(line); w.write("\n") }
                                continue
                            }
                            else -> endTop(null)
                        }
                    }
                    when {
                        line == TOTAL_LAYER_MARKER -> w.write("SET_PRINT_STATS_INFO TOTAL_LAYER=$layers\n")
                        line.startsWith(";LAYER:") -> {
                            w.write(line); w.write("\n")
                            line.substringAfter(':').trim().toIntOrNull()?.let { w.write("SET_PRINT_STATS_INFO CURRENT_LAYER=${it + 1}\n") }
                        }
                        else -> { w.write(line); w.write("\n") }
                    }
                }
            }
        }
        return Info(layers, seconds, filament)
    }
}
