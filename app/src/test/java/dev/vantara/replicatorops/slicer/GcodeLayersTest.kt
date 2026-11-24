package dev.vantara.replicatorops.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeLayersTest {
    @Test fun readsCuraLayersAndTypes() {
        val g = """
            ;FLAVOR:Marlin
            M83
            G1 Z0.2 F600
            ;LAYER:0
            ;TYPE:SKIRT
            G0 X10 Y10
            G1 X20 Y10 E1
            ;TYPE:WALL-OUTER
            G1 X20 Y20 E1
            ;LAYER:1
            G0 Z0.4
            ;TYPE:FILL
            G1 X10 Y20 E1
            G1 X10.1 Y20 E0.01
            G1 X10.2 Y20 E0.01
            G1 X10.5 Y20 E0.01
            T2
            G1 X10.5 Y30 E1
        """.trimIndent()
        val l = GcodeLayers.read(g.byteInputStream())
        assertEquals(2, l.layers.size)
        assertEquals(2, l.layers[0].count)
        assertEquals(GcodeLayers.Kind.SKIRT.ordinal.toByte(), l.layers[0].kinds[0])
        assertEquals(GcodeLayers.Kind.OUTER_WALL.ordinal.toByte(), l.layers[0].kinds[1])
        // Three tiny moves are joined into one line.
        assertEquals(3, l.layers[1].count)
        assertEquals(2.toByte(), l.layers[1].tools[2])
        assertEquals(0.4f, l.layers[1].z, 1e-4f)
        // Following a print: the second layer starts after the first layer's lines.
        val pos = l.layers[1].start.toLong()
        assertEquals(1, l.layerAt(pos + 1))
        assertEquals(2, l.layers[0].doneAt(pos))
    }

    @Test fun splitsLayersByHeightWithoutComments() {
        val g = "G90\nM82\nG1 Z0.2\nG1 X10 Y10 E1\nG1 Z0.6\nG1 X10 Y20\nG1 Z0.2\nG1 X20 Y20 E2\nG1 Z0.4\nG1 X30 Y20 E3\n"
        val l = GcodeLayers.read(g.byteInputStream())
        assertEquals(2, l.layers.size)
    }
}
