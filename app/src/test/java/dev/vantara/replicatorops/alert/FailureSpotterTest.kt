package dev.vantara.replicatorops.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FailureSpotterTest {
    private val w = 96; private val h = 72

    /** A tidy scene: dark bed with a grey block (the part) of [size] px. */
    private fun scene(size: Int, mess: Int = 0, seed: Long = 1): IntArray {
        val f = IntArray(w * h) { 40 }
        for (y in 40 until 40 + size) for (x in 40 until 40 + size) f[y * w + x] = 180
        // Spaghetti: random bright strands across the bed.
        val r = Random(seed)
        repeat(mess) {
            var x = r.nextInt(w); var y = h / 3 + r.nextInt(h - h / 3)
            repeat(30) {
                if (x in 0 until w && y in 0 until h) f[y * w + x] = 230
                x += r.nextInt(3) - 1; y += r.nextInt(3) - 1
            }
        }
        return f
    }

    @Test fun growingPartIsFine() {
        val s = FailureSpotter()
        for (i in 0 until 20) assertTrue("frame $i", !s.look(scene(6 + i / 2), w, h).alarm)
    }

    @Test fun spaghettiRaisesTheAlarmOnce() {
        val s = FailureSpotter()
        repeat(5) { s.look(scene(10), w, h) }
        assertTrue(!s.look(scene(10, mess = 60, seed = 2), w, h).alarm)  // one frame isn't enough
        assertTrue(s.look(scene(10, mess = 70, seed = 3), w, h).alarm)
        assertTrue(!s.look(scene(10, mess = 80, seed = 4), w, h).alarm)  // already said so
    }

    @Test fun edgeDensityOfFlatImageIsZero() = assertEquals(0.0, FailureSpotter.edgeDensity(IntArray(w * h) { 100 }, w, h), 1e-9)
}
