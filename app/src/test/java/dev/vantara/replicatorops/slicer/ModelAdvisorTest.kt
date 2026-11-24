package dev.vantara.replicatorops.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ModelAdvisorTest {
    private fun plateOf(t: FloatArray) = Plate(1, "", listOf(Geometry.place(1, "o", listOf(t to 0))), null)

    private fun boxes(vararg b: FloatArray): FloatArray {
        val tri = ArrayList<Float>()
        for (q in b) {
            val (x0, y0, z0) = Triple(q[0], q[1], q[2]); val (x1, y1, z1) = Triple(q[3], q[4], q[5])
            val p = arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
                floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
            fun quad(a: Int, b2: Int, c: Int, d: Int) = listOf(a, b2, c, a, c, d).forEach { i -> p[i].forEach { tri += it } }
            quad(0, 3, 2, 1); quad(4, 5, 6, 7); quad(0, 1, 5, 4); quad(1, 2, 6, 5); quad(2, 3, 7, 6); quad(3, 0, 4, 7)
        }
        return tri.toFloatArray()
    }

    /** Coarse cylinder (12 sides): curved surface drawn with big facets. */
    private fun cylinder(sides: Int = 12, r: Float = 20f, h: Float = 30f): FloatArray {
        val t = ArrayList<Float>()
        fun v(a: Int, z: Float) = floatArrayOf(100 + r * cos(2 * PI * a / sides).toFloat(), 100 + r * sin(2 * PI * a / sides).toFloat(), z)
        val c0 = floatArrayOf(100f, 100f, 0f); val c1 = floatArrayOf(100f, 100f, h)
        for (i in 0 until sides) {
            val a0 = v(i, 0f); val a1 = v(i + 1, 0f); val b0 = v(i, h); val b1 = v(i + 1, h)
            listOf(c0, a1, a0, c1, b0, b1, a0, a1, b1, a0, b1, b0).forEach { t += it.toList() }
        }
        return t.toFloatArray()
    }

    @Test fun overhangGetsSupportsOrTurned() {
        // A "T": thin post with a wide bar on top.
        val t = boxes(floatArrayOf(130f, 130f, 0f, 140f, 140f, 30f), floatArrayOf(100f, 130f, 30f, 170f, 140f, 36f))
        val a = ModelAdvisor.analyse(t)
        assertTrue("overhang ${a.overhangArea}", a.overhangArea > 500)
        val ids = ModelAdvisor.suggest(a, SliceSettings(), ModelAdvisor.Purpose.EVERYDAY).map { it.id }
        assertTrue(ids.toString(), "orient" in ids || "supports" in ids)
        assertTrue(ids.toString(), "brim" in ids)  // 100 mm² post on the bed
    }

    @Test fun thinPlateGetsThickened() {
        val t = boxes(floatArrayOf(100f, 100f, 0f, 160f, 100.8f, 30f))  // 0.8 mm wall standing up
        val a = ModelAdvisor.analyse(t)
        assertTrue("thin ${a.thinWall}", a.thinWall != null && abs(a.thinWall!! - 0.8) < 0.15)
        val ids = ModelAdvisor.suggest(a, SliceSettings(), ModelAdvisor.Purpose.STRONG).map { it.id }
        assertTrue(ids.toString(), "thicken" in ids && "strong-walls" in ids && "strong-infill" in ids)
        assertTrue("walls" in ModelAdvisor.suggest(a, SliceSettings(), ModelAdvisor.Purpose.EVERYDAY).map { it.id })
    }

    @Test fun cubeNeedsNothingForEveryday() {
        val a = ModelAdvisor.analyse(boxes(floatArrayOf(120f, 120f, 0f, 140f, 140f, 20f)))
        assertEquals(20.0, a.thinWall!!, 0.5)
        assertEquals(400.0, a.bedContact, 1.0)
        assertEquals(null, a.betterOrientation)
        assertEquals(emptyList<String>(), ModelAdvisor.suggest(a, SliceSettings(), ModelAdvisor.Purpose.EVERYDAY).map { it.id })
    }

    @Test fun coarseCurvesGetSmoothed() {
        val t = cylinder()
        val a = ModelAdvisor.analyse(t)
        assertTrue("faceted ${a.faceted}", a.faceted > 0.3)
        assertTrue("smooth" in ModelAdvisor.suggest(a, SliceSettings(), ModelAdvisor.Purpose.DISPLAY).map { it.id })
        val sm = ModelAdvisor.smooth(t)
        assertEquals(t.size * 4, sm.size)
        // Sharp rims stay put: still 40 mm across and 30 mm tall, on the bed.
        val p = plateOf(sm)
        assertEquals(30f, p.size.third, 0.01f)
        assertTrue(p.size.first in 38f..40.01f)
        var minZ = Float.MAX_VALUE; var i = 2; while (i < sm.size) { minZ = minOf(minZ, sm[i]); i += 3 }
        assertEquals(0f, minZ, 1e-4f)
    }

    @Test fun rotateKeepsItOnTheBed() {
        val t = boxes(floatArrayOf(100f, 100f, 0f, 150f, 110f, 5f))
        val r = ModelAdvisor.rotate(t, ModelAdvisor.Orientation.X_90)
        val p = plateOf(r)
        assertEquals(50f, p.size.first, 0.01f); assertEquals(5f, p.size.second, 0.01f); assertEquals(10f, p.size.third, 0.01f)
    }
}
