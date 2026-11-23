package dev.vantara.replicatorops.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cubeObject = """
        <object id="1" type="model"><mesh><vertices>
          <vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/><vertex x="10" y="10" z="0"/><vertex x="0" y="0" z="10"/>
        </vertices><triangles>
          <triangle v1="0" v2="1" v3="2"/><triangle v1="0" v2="1" v3="3"/>
        </triangles></mesh></object>"""

    /** Bambu/Orca layout: meshes in 3D/Objects, components + build items in the root, two plates. */
    private fun bambuStyle(): java.io.File {
        val f = tmp.newFile("project.3mf")
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("3D/Objects/part.model", """<model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources>$cubeObject</resources></model>""")
            put("3D/3dmodel.model", """<model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06">
                <resources>
                  <object id="3" type="model"><components><component p:path="/3D/Objects/part.model" objectid="1" transform="1 0 0 0 1 0 0 0 1 0 0 0"/></components></object>
                  <object id="5" type="model"><components><component p:path="/3D/Objects/part.model" objectid="1" transform="2 0 0 0 2 0 0 0 2 0 0 0"/></components></object>
                </resources>
                <build>
                  <item objectid="3" transform="1 0 0 0 1 0 0 0 1 100 100 5" printable="1"/>
                  <item objectid="5" transform="1 0 0 0 1 0 0 0 1 400 100 7" printable="1"/>
                </build></model>""")
            put("Metadata/model_settings.config", """<config>
                <object id="3"><metadata key="name" value="Rail"/><part id="1"><metadata key="name" value="inner"/></part></object>
                <object id="5"><metadata key="name" value="Stopper"/></object>
                <plate><metadata key="plater_id" value="1"/><model_instance><metadata key="object_id" value="3"/></model_instance></plate>
                <plate><metadata key="plater_id" value="2"/><model_instance><metadata key="object_id" value="5"/></model_instance></plate>
                </config>""")
            put("Metadata/project_settings.config", """{"layer_height":"0.16","wall_loops":"3"}""")
        }
        return f
    }

    @Test fun readsPlatesObjectsAndSettings() {
        val m = ModelReader.read(bambuStyle())
        assertEquals(2, m.plates.size)
        assertEquals(listOf("Rail"), m.plates[0].objectNames)
        assertEquals(listOf("Stopper"), m.plates[1].objectNames)
        assertEquals(2, m.plates[0].triangleCount)
        // Plate 2's object is scaled ×2 and dropped onto the bed.
        val (x, _, z) = m.plates[1].size
        assertEquals(20f, x, 0.001f)
        assertEquals(20f, z, 0.001f)
        assertEquals(0.16, SliceSettings.fromProject(m.projectSettings).layerHeight, 1e-9)
    }

    @Test fun stlRoundTrip() {
        val tris = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val out = ByteArrayOutputStream()
        Stl.writeBinary(tris, out)
        assertEquals(84 + 50, out.size())
        val back = Stl.read(ByteArrayInputStream(out.toByteArray()))
        assertTrue(back.contentEquals(tris))
    }

    @Test fun asciiStl() {
        val text = "solid t\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid t\n"
        assertEquals(9, Stl.read(ByteArrayInputStream(text.toByteArray())).size)
    }
}
