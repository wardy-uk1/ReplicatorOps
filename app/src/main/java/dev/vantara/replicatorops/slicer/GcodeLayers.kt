package dev.vantara.replicatorops.slicer

import java.io.InputStream

/**
 * A G-code file read into layers of printed lines, for the layer preview and the live
 * print view. Works with CuraEngine (";LAYER:n", ";TYPE:"), QIDI Studio / Orca / Bambu
 * (";LAYER_CHANGE", "; FEATURE:") and anything else (a new layer when Z goes up).
 * Very small moves are joined up so even big files stay light on the phone.
 */
class GcodeLayers private constructor(val layers: List<Layer>) {

    enum class Kind { OUTER_WALL, INNER_WALL, SKIN, INFILL, SUPPORT, SKIRT, IRONING, OTHER }

    class Layer(
        val z: Float,
        /** Lines as x0, y0, x1, y1, … (mm, bed coordinates). */
        val lines: FloatArray,
        /** Kind of each line. */
        val kinds: ByteArray,
        /** Tool (filament) of each line. */
        val tools: ByteArray,
        /** Byte offset in the file where each line ends (for following a print). */
        val ends: IntArray,
        /** Byte offset where the layer starts. */
        val start: Int,
    ) {
        val count: Int get() = kinds.size

        /** How many of this layer's lines are done when the printer has read up to [filePosition]. */
        fun doneAt(filePosition: Long): Int {
            var lo = 0; var hi = count
            while (lo < hi) { val m = (lo + hi) / 2; if (ends[m] <= filePosition) lo = m + 1 else hi = m }
            return lo
        }
    }

    val segmentCount: Int get() = layers.sumOf { it.count }

    /** The layer the printer is on when it has read up to [filePosition]. */
    fun layerAt(filePosition: Long): Int {
        var lo = 0; var hi = layers.size - 1; var ans = 0
        while (lo <= hi) { val m = (lo + hi) / 2; if (layers[m].start <= filePosition) { ans = m; lo = m + 1 } else hi = m - 1 }
        return ans
    }

    companion object {
        /** Lines shorter than this are joined to the next (mm). */
        private const val MIN_LEN = 0.35f

        fun read(input: InputStream, maxSegments: Int = 3_000_000): GcodeLayers {
            val layers = ArrayList<Layer>()
            var xs = Seg()
            var x = 0f; var y = 0f; var z = 0f
            var relXY = false; var relE = false
            var kind = Kind.OTHER
            var tool = 0
            var layerZ = 0f
            var layerStart = 0
            var explicitLayers = false
            // Start of the line being built (for joining short moves).
            var sx = 0f; var sy = 0f; var pending = false
            var total = 0
            var offset = 0
            fun flushPending(end: Int) {
                if (pending && (x != sx || y != sy)) xs.add(sx, sy, x, y, kind, tool, end)
                pending = false
            }
            fun newLayer(at: Int) {
                flushPending(at)
                if (xs.size > 0) { layers += xs.build(layerZ, layerStart); total += xs.size }
                xs = Seg(); layerStart = at; layerZ = z
            }
            val reader = input.bufferedReader(Charsets.ISO_8859_1)
            while (true) {
                val raw = reader.readLine() ?: break
                val lineStart = offset
                offset += raw.length + 1
                if (raw.isEmpty()) continue
                val c0 = raw[0]
                if (c0 == ';') {
                    when {
                        raw.startsWith(";LAYER:") || raw.startsWith(";LAYER_CHANGE") -> {
                            if (!explicitLayers) {
                                // Anything before the first layer (purge lines in the start G-code) isn't a layer.
                                pending = false; xs = Seg(); layerStart = lineStart; layerZ = z
                                explicitLayers = true
                            } else newLayer(lineStart)
                        }
                        raw.startsWith(";TYPE:") -> { flushPending(lineStart); kind = kindOf(raw.substring(6)) }
                        raw.startsWith("; FEATURE:") -> { flushPending(lineStart); kind = kindOf(raw.substring(10)) }
                        raw.startsWith("; Z_HEIGHT:") || raw.startsWith(";Z:") -> raw.substringAfter(':').trim().toFloatOrNull()?.let { if (xs.size == 0) layerZ = it }
                    }
                    continue
                }
                if (total > maxSegments) break
                val code = raw.substringBefore(';').trim()
                if (code.isEmpty()) continue
                when {
                    code.startsWith("G0") || code.startsWith("G1") -> {
                        if (code.length > 2 && code[2].isDigit()) continue  // G10, G11…
                        var nx = x; var ny = y; var nz = z; var e = 0f; var hasE = false
                        for (w in code.split(' ')) {
                            if (w.length < 2) continue
                            val v = w.substring(1).toFloatOrNull() ?: continue
                            when (w[0]) {
                                'X' -> nx = if (relXY) x + v else v
                                'Y' -> ny = if (relXY) y + v else v
                                'Z' -> nz = if (relXY) z + v else v
                                'E' -> { e = v; hasE = true }
                            }
                        }
                        val extruding = hasE && (if (relE) e > 0f else true) && (nx != x || ny != y)
                        z = nz
                        // No layer comments: printing higher than the layer so far starts a new one.
                        if (extruding && !explicitLayers && xs.size > 0 && z > layerZ + 0.01f) newLayer(lineStart)
                        if (xs.size == 0 && extruding) layerZ = z
                        if (extruding) {
                            if (!pending) { sx = x; sy = y; pending = true }
                            x = nx; y = ny
                            val dx = x - sx; val dy = y - sy
                            if (dx * dx + dy * dy >= MIN_LEN * MIN_LEN) { xs.add(sx, sy, x, y, kind, tool, offset); pending = false }
                        } else {
                            flushPending(lineStart)
                            x = nx; y = ny
                        }
                    }
                    code == "G90" -> relXY = false
                    code == "G91" -> relXY = true
                    code.startsWith("M82") -> relE = false
                    code.startsWith("M83") -> relE = true
                    code.startsWith("G92") -> Unit
                    code[0] == 'T' && code.length <= 3 -> code.substring(1).toIntOrNull()?.let { flushPending(lineStart); tool = it }
                }
            }
            newLayer(offset)
            return GcodeLayers(layers)
        }

        private fun kindOf(t: String): Kind {
            val s = t.trim().lowercase()
            return when {
                s.contains("outer") || s == "wall-outer" || s.contains("external perimeter") -> Kind.OUTER_WALL
                s.contains("wall") || s.contains("perimeter") -> Kind.INNER_WALL
                s.contains("ironing") -> Kind.IRONING
                s.contains("skin") || s.contains("top") || s.contains("bottom") || s.contains("solid") || s.contains("bridge") -> Kind.SKIN
                s.contains("fill") -> Kind.INFILL
                s.contains("support") -> Kind.SUPPORT
                s.contains("skirt") || s.contains("brim") -> Kind.SKIRT
                else -> Kind.OTHER
            }
        }
    }

    /** Growable arrays for one layer. */
    private class Seg {
        var lines = FloatArray(1024); var kinds = ByteArray(256); var tools = ByteArray(256); var ends = IntArray(256)
        var size = 0
        fun add(x0: Float, y0: Float, x1: Float, y1: Float, k: Kind, tool: Int, end: Int) {
            if (size == kinds.size) {
                kinds = kinds.copyOf(size * 2); tools = tools.copyOf(size * 2); ends = ends.copyOf(size * 2); lines = lines.copyOf(size * 8)
            }
            val b = size * 4
            lines[b] = x0; lines[b + 1] = y0; lines[b + 2] = x1; lines[b + 3] = y1
            kinds[size] = k.ordinal.toByte(); tools[size] = tool.coerceIn(0, 127).toByte(); ends[size] = end
            size++
        }
        fun build(z: Float, start: Int) = Layer(z, lines.copyOf(size * 4), kinds.copyOf(size), tools.copyOf(size), ends.copyOf(size), start)
    }
}
