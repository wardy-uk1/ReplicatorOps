package dev.vantara.replicatorops.slicer

import java.io.File

/** CuraEngine's command-line output, and the finishing touches its G-code needs for the Q2C. */
object CuraGcode {
    const val TC_FROM = ";ROPS_TC_FROM "
    const val TC_TO = ";ROPS_TC_TO "
    private val BARE_TOOL = Regex("^T\\d+\\s*(;.*)?$")
    private val HEATER_TOOL = Regex("\\s+T\\d+")
    private val Z_WORD = Regex("\\sZ(-?[\\d.]+)")

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
     * CuraEngine's command line: the settings file, one "-e" per filament used (with the
     * markers that become QIDI's filament change), then each part's STL with its filament.
     * [extruders] is how many filaments the print uses; parts give CuraEngine's extruder number.
     */
    fun engineArgs(bin: String, settings: File, extruders: Int, parts: List<Pair<File, Int>>, out: File): List<String> = buildList {
        addAll(listOf(bin, "slice", "-j", settings.path))
        for (e in 0 until extruders) {
            addAll(listOf("-e$e", "-s", "extruder_nr=$e", "-s", "machine_extruder_start_code=$TC_TO$e", "-s", "machine_extruder_end_code=$TC_FROM$e"))
        }
        for ((stl, e) in parts) addAll(listOf("-l", stl.path, "-s", "extruder_nr=$e"))
        addAll(listOf("-o", out.path))
    }

    /** Bed coordinates (0…270) to Cura's, whose origin is the bed centre. */
    fun toCura(tris: FloatArray, bedW: Float, bedD: Float): FloatArray {
        val out = FloatArray(tris.size)
        var i = 0
        while (i < tris.size) { out[i] = tris[i] - bedW / 2; out[i + 1] = tris[i + 1] - bedD / 2; out[i + 2] = tris[i + 2]; i += 3 }
        return out
    }

    /**
     * Writes the finished G-code: the real header and thumbnails at the top (for the printer's
     * screen and Moonraker), the layer total in PRINT_START's stats, and the current layer
     * after every ";LAYER:n" so the Q2C's screen and Moonraker can follow along.
     */
    /** How filament changes are done on the Q2C with the QIDI Box (from QIDI Studio's profile). */
    data class ToolChange(
        /** CuraEngine extruder number -> tool (filament) number in the file, T0…T15. */
        val tools: List<Int> = listOf(0),
        /** Nozzle temperature for the new filament (°C). */
        val temp: Int = 220,
        /** Filament pushed through to flush the old colour out (mm). */
        val flush: Double = 70.0,
        /** Feed rate for loading/flushing (mm/min), from the filament's flow limit. */
        val feed: Int = 500,
    )

    /**
     * QIDI Studio's Q2C change_filament_gcode, worked out for one change: lift, cut the old
     * filament over the waste chute, unload it, load the new one, flush, wipe, and carry on.
     */
    fun toolChange(from: Int, to: Int, z: Double, tc: ToolChange): List<String> = buildList {
        add("; ---- filament change T$from -> T$to (QIDI Box) ----")
        add("M83")
        add("G1 Z%.3f F1200".format(java.util.Locale.ROOT, minOf(z + 3.0, 256.0)))
        add("TOOL_CHANGE_START F=$from T=$to")
        add("BUFFER_MONITORING ENABLE=0")
        add("DISABLE_ALL_SENSOR")
        add("M106 S255")
        add("MOVE_TO_TRASH")
        add("G1 E-10 F${tc.feed}")
        add("M400")
        add("CUT_FILAMENT T=$from")
        add("MOVE_TO_TRASH")
        add("M106 P2 S0")
        add("UNLOAD_T$from")
        add("T$to")
        add("M104 S${tc.temp}")
        add("; FLUSH_START")
        add("M106 S25")
        add("G1 E30 F300")
        add("; FLUSH_END")
        val f = tc.flush
        if (f > 1) {
            add("; FLUSH_START")
            if (f > 23.7) {
                val r = f - 23.7
                add("G1 E23.7 F${tc.feed}")
                repeat(4) { k ->
                    add("G1 E%.3f F50".format(java.util.Locale.ROOT, r * 0.02))
                    add("G1 E%.3f F${tc.feed}".format(java.util.Locale.ROOT, r * 0.23))
                }
            } else {
                add("G1 E%.3f F${tc.feed}".format(java.util.Locale.ROOT, f))
            }
            add("G1 E-2 F1800")
            add("; FLUSH_END")
        }
        add("M400")
        add("M106 S255")
        add("M104 S${tc.temp}")
        add("INIT_SYNC_BUFFER_STATE")
        add("BUFFER_MONITORING ENABLE=1")
        add("G1 E10 F25")
        add("M109 S${tc.temp}")
        add("G1 E-5 F1800")
        add("CLEAR_OOZE")
        add("TOOL_CHANGE_END")
        add("G1 Y270 F8000")
        add("M106 S0")
        add("G1 E2 F1800")
        add("ENABLE_ALL_SENSOR")
        add("; ---- end of filament change ----")
    }

    fun finish(
        raw: File, target: File, header: List<String>, thumbnails: List<Pair<Int, String>>,
        tc: ToolChange = ToolChange(),
    ): Info {
        val initialTool = raw.bufferedReader().useLines { lines ->
            lines.take(5000).firstOrNull { it.startsWith(TC_TO) }?.removePrefix(TC_TO)?.trim()?.toIntOrNull()
        }?.let { tc.tools.getOrElse(it) { it } } ?: tc.tools.firstOrNull() ?: 0
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
                var changing: Int? = null
                var lastZ = 0.0
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
                    // Filament changes: CuraEngine's switch (between our markers) becomes QIDI's Box routine.
                    if (changing != null) {
                        if (line.startsWith(TC_TO)) {
                            val toE = line.removePrefix(TC_TO).trim().toIntOrNull() ?: 0
                            val from = tc.tools.getOrElse(changing!!) { changing!! }
                            val to = tc.tools.getOrElse(toE) { toE }
                            if (from != to) toolChange(from, to, lastZ, tc).forEach { w.write(it); w.write("\n") }
                            changing = null
                        }
                        continue
                    }
                    when {
                        line.startsWith(TC_FROM) -> changing = line.removePrefix(TC_FROM).trim().toIntOrNull() ?: 0
                        line.startsWith(TC_TO) -> Unit  // the first filament: chosen in the start G-code
                        line == INITIAL_TOOL_MARKER -> w.write("T$initialTool\n")
                        BARE_TOOL.matches(line) -> Unit  // CuraEngine's own tool selects: the Box routine does that
                        line == TOTAL_LAYER_MARKER -> w.write("SET_PRINT_STATS_INFO TOTAL_LAYER=$layers\n")
                        line.startsWith(";LAYER:") -> {
                            w.write(line); w.write("\n")
                            line.substringAfter(':').trim().toIntOrNull()?.let { w.write("SET_PRINT_STATS_INFO CURRENT_LAYER=${it + 1}\n") }
                        }
                        else -> {
                            // Single heater: drop any "T<n>" CuraEngine adds to temperature commands.
                            val out = if (line.startsWith("M104") || line.startsWith("M109")) HEATER_TOOL.replace(line, "") else line
                            w.write(out); w.write("\n")
                            if (line.startsWith("G0") || line.startsWith("G1")) Z_WORD.find(line)?.let { lastZ = it.groupValues[1].toDouble() }
                        }
                    }
                }
            }
        }
        return Info(layers, seconds, filament)
    }
}
