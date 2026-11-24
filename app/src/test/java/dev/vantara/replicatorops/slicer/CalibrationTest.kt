package dev.vantara.replicatorops.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {
    private fun check(test: Calibration.Test, layers: Int) {
        val g = Calibration.gcode(test, SliceSettings())
        assertTrue(g.contains("PRINT_START BED=60 HOTEND=220"))
        assertTrue(g.contains("UNLOAD_FILAMENT T=0"))
        assertTrue(!g.contains("ROPS_") && !g.contains("{"))
        assertTrue("decimal commas", !Regex("[XYZE]-?\\d+,\\d").containsMatchIn(g))
        val l = GcodeLayers.read(g.byteInputStream())
        assertEquals(test.name, layers, l.layers.size)
        for (layer in l.layers) for (i in 0 until layer.lines.size) {
            assertTrue(layer.lines[i] in 0f..270f)
        }
    }

    @Test fun flow() = check(Calibration.Test.FLOW, 3)
    @Test fun pressureAdvance() {
        check(Calibration.Test.PRESSURE_ADVANCE, 1)
        val g = Calibration.gcode(Calibration.Test.PRESSURE_ADVANCE, SliceSettings())
        assertEquals(Calibration.PA_STEPS.size + 1, Regex("SET_PRESSURE_ADVANCE").findAll(g).count())
    }
    @Test fun retraction() = check(Calibration.Test.RETRACTION, Calibration.RETRACTION_STEPS.size * 15)

    @Test fun pressureAdvanceGoesIntoStart() {
        val start = SliceSettings(pressureAdvance = 0.035).curaOverrides()["machine_start_gcode"] as String
        assertTrue(start.contains("SET_PRESSURE_ADVANCE ADVANCE=0.0350"))
        assertEquals(97.0, SliceSettings(flow = 97).curaOverrides()["material_flow"])
    }
}
