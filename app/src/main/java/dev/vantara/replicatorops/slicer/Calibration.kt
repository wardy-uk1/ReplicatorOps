package dev.vantara.replicatorops.slicer

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Calibration prints, written straight to G-code (no slicing needed): flow, pressure
 * advance and retraction. Each uses the Slicer's current filament settings and QIDI's
 * Q2C start and end routine, so the result applies to that filament.
 */
object Calibration {

    enum class Test(val label: String, val file: String) {
        FLOW("Flow", "calibrate_flow"),
        PRESSURE_ADVANCE("Pressure advance", "calibrate_pressure_advance"),
        RETRACTION("Retraction", "calibrate_retraction"),
    }

    /** The values each test tries, in print order (left to right / front to back / bottom to top). */
    val FLOW_STEPS = listOf(90, 94, 97, 100, 103, 106, 110)
    val PA_STEPS = (0..16).map { it * 0.005 }
    val RETRACTION_STEPS = (1..10).map { it * 0.2 }

    private const val LINE = 0.45
    private const val LAYER = 0.2
    private const val FILAMENT_AREA = PI * 0.875 * 0.875

    /** Writes the G-code for [test] with [s]'s temperatures and speeds. */
    fun gcode(test: Test, s: SliceSettings): String {
        val g = Writer(s)
        g.start(test.label)
        when (test) {
            Test.FLOW -> flow(g)
            Test.PRESSURE_ADVANCE -> pressureAdvance(g)
            Test.RETRACTION -> retraction(g, s)
        }
        g.end()
        return g.toString()
    }

    /**
     * Flow: a row of squares, three solid layers each, from 90 % (left) to 110 % (right).
     * Square n has n small tabs along its front edge so you can tell them apart.
     */
    private fun flow(g: Writer) {
        val size = 22.0; val gap = 8.0
        val x0 = 135 - (FLOW_STEPS.size * size + (FLOW_STEPS.size - 1) * gap) / 2
        val y0 = 115.0
        for (layer in 0 until 3) {
            g.layer(layer, LAYER * (layer + 1))
            FLOW_STEPS.forEachIndexed { i, pct ->
                val x = x0 + i * (size + gap)
                g.flow = pct / 100.0
                g.comment("flow $pct%")
                g.rect(x, y0, size, size, g.wallSpeed)
                g.rect(x + LINE, y0 + LINE, size - 2 * LINE, size - 2 * LINE, g.wallSpeed)
                // Solid fill, alternating direction each layer.
                g.fill(x + 2 * LINE, y0 + 2 * LINE, size - 4 * LINE, size - 4 * LINE, horizontal = layer % 2 == 0)
                // Tabs to number the squares (only on the first layer, in front).
                if (layer == 0) repeat(i + 1) { t -> g.rect(x + 1.5 + t * 3.0, y0 - 3.0, 1.5, 2.0, g.firstSpeed) }
            }
        }
        g.flow = 1.0
    }

    /**
     * Pressure advance: lines printed slow – fast – slow, one per value, from 0.000 (front)
     * to 0.080 (back). The best line is evenly thick with no blob or gap where the speed changes.
     */
    private fun pressureAdvance(g: Writer) {
        val x0 = 75.0; val slow = 20.0; val fast = 120.0
        val y0 = 80.0; val pitch = 6.0
        g.layer(0, LAYER)
        // A frame so the first lines go down on a primed nozzle.
        g.rect(x0 - 6, y0 - 6, 132.0, PA_STEPS.size * pitch + 6, g.firstSpeed)
        PA_STEPS.forEachIndexed { i, pa ->
            val y = y0 + i * pitch
            g.raw("SET_PRESSURE_ADVANCE ADVANCE=%.3f".format(java.util.Locale.ROOT, pa))
            g.travel(x0, y)
            g.line(x0 + 20, y, slow)
            g.line(x0 + 80, y, fast)
            g.line(x0 + 100, y, slow)
            // Every fifth line gets a tick on the right so you can count.
            if (i % 5 == 0) { g.travel(x0 + 104, y); g.line(x0 + 112, y, slow) }
        }
        g.raw("SET_PRESSURE_ADVANCE ADVANCE=0")
    }

    /**
     * Retraction: two thin towers 40 mm apart, 3 mm per setting from 0.2 mm (bottom) to
     * 2.0 mm (top). The best band has no strings between the towers.
     */
    private fun retraction(g: Writer, s: SliceSettings) {
        val size = 8.0; val bandLayers = 15
        val ax = 110.0; val bx = 152.0; val y = 131.0
        val total = RETRACTION_STEPS.size * bandLayers
        for (layer in 0 until total) {
            val r = RETRACTION_STEPS[layer / bandLayers]
            g.layer(layer, LAYER * (layer + 1))
            if (layer % bandLayers == 0) g.comment("retraction %.1f mm".format(java.util.Locale.ROOT, r))
            for (x in listOf(ax, bx)) {
                g.retract = r
                g.travel(x, y)
                g.rect(x, y, size, size, if (layer == 0) g.firstSpeed else g.wallSpeed)
                g.rect(x + LINE, y + LINE, size - 2 * LINE, size - 2 * LINE, if (layer == 0) g.firstSpeed else g.wallSpeed)
                if (layer < 3) g.fill(x + 2 * LINE, y + 2 * LINE, size - 4 * LINE, size - 4 * LINE, horizontal = layer % 2 == 0)
            }
        }
        g.retract = s.retraction
    }

    /** Small G-code writer: relative extrusion, retract on travel, Q2C start/end. */
    private class Writer(val s: SliceSettings) {
        private val out = StringBuilder()
        var x = 0.0; var y = 0.0; var z = 0.0
        var flow = 1.0
        var retract = s.retraction
        var layerIndex = 0
        val wallSpeed = minOf(s.wallSpeed, 120).toDouble()
        val firstSpeed = s.firstLayerSpeed.toDouble()
        private var retracted = false

        fun raw(line: String) { out.append(line).append('\n') }
        fun comment(c: String) = raw("; $c")

        fun start(title: String) {
            raw("; Calibration print: $title · Replicator Ops")
            raw("; Generated with Replicator Ops calibration")
            s.curaOverrides()["machine_start_gcode"].toString().lines()
                .map { if (it == INITIAL_TOOL_MARKER) "T0" else it }
                .filterNot { it.startsWith("SET_PRINT_STATS_INFO TOTAL_LAYER") }
                .forEach(::raw)
            raw("M83")
            raw("M106 S0")
        }

        fun end() {
            raw("M107")
            s.curaOverrides()["machine_end_gcode"].toString().lines().forEach(::raw)
        }

        fun layer(i: Int, height: Double) {
            layerIndex = i
            raw(";LAYER:$i")
            raw("SET_PRINT_STATS_INFO CURRENT_LAYER=${i + 1}")
            if (i == 1) raw("M106 S${(s.fan * 255 / 100).coerceIn(0, 255)}")
            z = height
            raw("G1 Z%.3f F600".format(java.util.Locale.ROOT, z))
        }

        fun travel(tx: Double, ty: Double) {
            val d = Math.hypot(tx - x, ty - y)
            if (d < 0.01) return
            if (d > 2 && retract > 0 && !retracted) { raw("G1 E-%.3f F%d".format(java.util.Locale.ROOT, retract, s.retractionSpeed * 60)); retracted = true }
            raw("G0 X%.3f Y%.3f F%d".format(java.util.Locale.ROOT, tx, ty, s.travelSpeed * 60))
            x = tx; y = ty
        }

        fun line(tx: Double, ty: Double, speed: Double) {
            if (retracted) { raw("G1 E%.3f F%d".format(java.util.Locale.ROOT, retract, s.retractionSpeed * 60)); retracted = false }
            val len = Math.hypot(tx - x, ty - y)
            val h = if (layerIndex == 0) s.firstLayerHeight.coerceAtLeast(LAYER) else LAYER
            val e = len * LINE * h / FILAMENT_AREA * flow
            raw("G1 X%.3f Y%.3f E%.5f F%d".format(java.util.Locale.ROOT, tx, ty, e, (speed * 60).roundToInt()))
            x = tx; y = ty
        }

        fun rect(rx: Double, ry: Double, w: Double, d: Double, speed: Double) {
            travel(rx, ry)
            line(rx + w, ry, speed); line(rx + w, ry + d, speed); line(rx, ry + d, speed); line(rx, ry, speed)
        }

        fun fill(fx: Double, fy: Double, w: Double, d: Double, horizontal: Boolean) {
            val sp = if (layerIndex == 0) firstSpeed else minOf(s.infillSpeed, 100).toDouble()
            if (horizontal) {
                var yy = fy; var dir = 0
                travel(fx, yy)
                while (yy <= fy + d + 1e-6) {
                    val tx = if (dir == 0) fx + w else fx
                    line(tx, yy, sp)
                    yy += LINE; dir = 1 - dir
                    if (yy <= fy + d + 1e-6) line(tx, yy, sp)
                }
            } else {
                var xx = fx; var dir = 0
                travel(xx, fy)
                while (xx <= fx + w + 1e-6) {
                    val ty = if (dir == 0) fy + d else fy
                    line(xx, ty, sp)
                    xx += LINE; dir = 1 - dir
                    if (xx <= fx + w + 1e-6) line(xx, ty, sp)
                }
            }
        }

        override fun toString() = out.toString()
    }

}
