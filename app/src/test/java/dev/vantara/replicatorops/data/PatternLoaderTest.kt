package dev.vantara.replicatorops.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PatternLoaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(name: String, vararg entries: String): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { e -> z.putNextEntry(ZipEntry(e)); z.write("x".toByteArray()); z.closeEntry() }
        }
        return f
    }

    @Test fun gcodeIsSliced() {
        assertEquals(PatternKind.SLICED, PatternLoader.classify(tmp.newFile("benchy.gcode")))
    }

    @Test fun slicedThreeMf() {
        val f = zip("gargoyles.gcode.3mf", "3D/3dmodel.model", "Metadata/plate_1.gcode", "Metadata/plate_1.png")
        assertEquals(PatternKind.SLICED, PatternLoader.classify(f))
    }

    @Test fun unslicedThreeMfAndStlNeedSlicing() {
        val f = zip("model.3mf", "3D/3dmodel.model", "Metadata/model_settings.config")
        assertEquals(PatternKind.NEEDS_SLICING, PatternLoader.classify(f))
        assertEquals(PatternKind.NEEDS_SLICING, PatternLoader.classify(tmp.newFile("part.STL")))
    }

    @Test fun otherFilesUnsupported() {
        assertEquals(PatternKind.UNSUPPORTED, PatternLoader.classify(tmp.newFile("photo.jpg")))
    }

    @Test fun safeNames() {
        assertEquals("a_b_c.gcode", PatternLoader.safeName("a/b:c.gcode"))
    }
}
