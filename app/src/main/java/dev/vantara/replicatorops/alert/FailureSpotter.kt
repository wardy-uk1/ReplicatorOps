package dev.vantara.replicatorops.alert

import kotlin.math.abs

/**
 * Failure spotter: watches camera snapshots during a print, on the phone (no internet,
 * no AI model). A failed print — "spaghetti" or a part knocked loose and dragged around —
 * fills the bed area with fine, tangled edges. The spotter learns how busy the picture
 * normally looks for this print and raises the alarm when it jumps well above that for
 * two snapshots in a row. It catches the obvious failures; expect the odd false alarm.
 *
 * Frames are small greyscale pictures (0–255 per pixel, row by row).
 */
class FailureSpotter(var sensitivity: Sensitivity = Sensitivity.MEDIUM) {

    enum class Sensitivity(val label: String, val ratio: Double) { LOW("Low", 2.2), MEDIUM("Medium", 1.7), HIGH("High", 1.4) }

    /** Result for one snapshot: how busy it looks against normal, and whether to alarm. */
    data class Verdict(val score: Double, val baseline: Double?, val alarm: Boolean)

    private var baseline: Double? = null
    private var learned = 0
    private var strikes = 0

    fun reset() { baseline = null; learned = 0; strikes = 0 }

    /** Looks at one frame; [w] × [h] pixels. The top third (gantry, frame) is ignored. */
    fun look(lum: IntArray, w: Int, h: Int): Verdict {
        val score = edgeDensity(lum, w, h, top = h / 3)
        val base = baseline
        if (base == null || learned < 3) {
            // Learn what this print normally looks like from the first few snapshots.
            baseline = if (base == null) score else (base * learned + score) / (learned + 1)
            learned++
            return Verdict(score, baseline, false)
        }
        val high = score > base * sensitivity.ratio && score - base > 0.02
        strikes = if (high) strikes + 1 else 0
        if (!high) baseline = base * 0.9 + score * 0.1   // the part grows: keep up slowly
        val alarm = strikes == 2
        return Verdict(score, base, alarm)
    }

    companion object {
        /** Share of pixels on a strong edge (Sobel), below row [top]. */
        fun edgeDensity(lum: IntArray, w: Int, h: Int, top: Int = 0, threshold: Int = 60): Double {
            var edges = 0; var total = 0
            for (y in maxOf(1, top) until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    val gx = -lum[i - w - 1] - 2 * lum[i - 1] - lum[i + w - 1] + lum[i - w + 1] + 2 * lum[i + 1] + lum[i + w + 1]
                    val gy = -lum[i - w - 1] - 2 * lum[i - w] - lum[i - w + 1] + lum[i + w - 1] + 2 * lum[i + w] + lum[i + w + 1]
                    if (abs(gx) + abs(gy) > threshold * 4) edges++
                    total++
                }
            }
            return if (total == 0) 0.0 else edges.toDouble() / total
        }
    }
}
