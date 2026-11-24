package dev.vantara.replicatorops.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class QidiPackageTest {
    @Test fun packsLikeQidiStudio() {
        val dir = File(System.getProperty("java.io.tmpdir"), "qidipkg").apply { mkdirs() }
        val gcode = File(dir, "cube.gcode").apply { writeText(";FLAVOR:Marlin\n;Generated with Cura_SteamEngine 5.10.3\n;LAYER:0\nG1 X10 Y10 E1\n") }
        val job = QidiPackage.Job(
            gcode = gcode, name = "cube", seconds = 890.4, layers = 100, layerHeight = 0.2,
            nozzleTemp = 220, bedTemp = 60,
            filaments = listOf(
                QidiPackage.Filament(0, "PLA", 0xFFFF0000, 1000.0),
                QidiPackage.Filament(2, "PETG", null, 500.0, id = "QD_2_1_40"),
            ),
            picture = byteArrayOf(1, 2, 3),
        )
        val out = File(dir, "cube.gcode.3mf")
        QidiPackage.write(job, out)
        ZipFile(out).use { z ->
            val names = z.entries().asSequence().map { it.name }.toSet()
            for (n in listOf("Metadata/plate_1.gcode", "Metadata/plate_1.png", "Metadata/slice_info.config",
                    "Metadata/project_settings.config", "Metadata/plate_1.json", "Metadata/plate_1.gcode.md5", "[Content_Types].xml")) {
                assertTrue(n, n in names)
            }
            val info = z.getInputStream(z.getEntry("Metadata/slice_info.config")).reader().readText()
            assertTrue(info, info.contains("""key="prediction" value="890""""))
            assertTrue(info, info.contains("""<filament id="1" tray_info_idx="QD_2_1_7" type="PLA" color="#FF0000""""))
            assertTrue(info, info.contains("""<filament id="3" tray_info_idx="QD_2_1_40" type="PETG" color="#FFFFFF""""))
            assertEquals(gcode.readText(), z.getInputStream(z.getEntry("Metadata/plate_1.gcode")).reader().readText())
            val ps = z.getInputStream(z.getEntry("Metadata/project_settings.config")).reader().readText()
            assertTrue(ps, ps.contains("\"filament_colour\": [\"#FF0000\",\"#FFFFFF\",\"#FFFFFF\"]"))
            val md5 = z.getInputStream(z.getEntry("Metadata/plate_1.gcode.md5")).reader().readText()
            assertEquals(QidiPackage.md5(gcode), md5)
        }
        // 1 m of PLA ≈ 2.98 g.
        assertEquals(2.98, QidiPackage.grams("PLA", 1000.0), 0.01)
    }

    @Test fun layersReadFromInsideA3mf() {
        val dir = File(System.getProperty("java.io.tmpdir"), "qidipkg2").apply { mkdirs() }
        val gcode = File(dir, "a.gcode").apply { writeText("M83\nG1 Z0.2\n;LAYER:0\nG0 X0 Y0\nG1 X10 Y0 E1\n;LAYER:1\nG0 Z0.4\nG1 X10 Y10 E1\n") }
        val out = File(dir, "a.gcode.3mf")
        QidiPackage.write(QidiPackage.Job(gcode, "a", 10.0, 2, 0.2, 210, 60, listOf(QidiPackage.Filament(0, "PLA", null, 10.0))), out)
        val l = out.inputStream().use { GcodeLayers.read(it) }
        assertEquals(2, l.layers.size)
    }
}
