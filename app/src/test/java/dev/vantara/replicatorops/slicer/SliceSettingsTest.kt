package dev.vantara.replicatorops.slicer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SliceSettingsTest {
    // The relevant part of AttachmentHolderV1_Print.3mf's project_settings.config (OrcaSlicer, Qidi Q2C).
    private val project = Json.parseToJsonElement(
        """{"printer_model":"Qidi Q2C","layer_height":"0.2","initial_layer_print_height":"0.2","wall_loops":"2",
           "top_shell_layers":"5","bottom_shell_layers":"3","sparse_infill_density":"15%","sparse_infill_pattern":"grid",
           "enable_support":"0","support_type":"tree(auto)","brim_type":"auto_brim","brim_width":"5",
           "nozzle_temperature":["210","210"],"nozzle_temperature_initial_layer":["210","210"],
           "hot_plate_temp":["60","60"],"filament_type":["PLA","PLA"],"outer_wall_speed":["200"],
           "filament_max_volumetric_speed":["24.5","24.5"],"retraction_length":["0.8"],"z_hop":["0.4"]}"""
    ).jsonObject

    @Test fun readsOrcaProject() {
        val s = SliceSettings.fromProject(project)
        assertEquals(0.2, s.layerHeight, 1e-9)
        assertEquals(2, s.walls)
        assertEquals(15, s.infill)
        assertEquals(210, s.nozzleTemp)
        assertEquals(60, s.bedTemp)
        assertEquals(false, s.supports)
        assertEquals(true, s.treeSupports)
        assertEquals(5.0, s.brim, 1e-9)
        assertTrue(s.source.contains("Qidi Q2C"))
    }

    @Test fun kiriProfileHasQidiStart() {
        val d = SliceSettings().kiriDevice()
        val pre = d["gcodePre"].toString()
        assertTrue(pre.contains("PRINT_START BED={bed_temp} HOTEND={temp}"))
        assertTrue(pre.contains("T0"))
        assertEquals(JsonPrimitive(270), d["bedWidth"])
        val p = SliceSettings(infill = 0).kiriProcess()
        assertEquals(JsonPrimitive("none"), p["sliceFillType"])
    }

    /** Writes the config the CI self-test slices with (same as the app sends). */
    @Test fun writeSelfTestConfig() {
        val s = SliceSettings()
        val cfg = buildJsonObject {
            put("device", s.kiriDevice())
            put("process", s.kiriProcess())
        }
        File("build").mkdirs()
        File("build/selftest.json").writeText(cfg.toString())
        assertTrue(File("build/selftest.json").length() > 500)
    }

}
