package dev.vantara.replicatorops.slicer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SliceSettingsTest {
    // The relevant part of AttachmentHolderV1_Print.3mf's project_settings.config (OrcaSlicer, Qidi Q2C).
    private val project = Json.parseToJsonElement(
        """{"printer_model":"Qidi Q2C","layer_height":"0.2","initial_layer_print_height":"0.2","wall_loops":"2",
           "top_shell_layers":"5","bottom_shell_layers":"3","sparse_infill_density":"15%","sparse_infill_pattern":"grid",
           "enable_support":"0","support_type":"tree(auto)","brim_type":"auto_brim","brim_width":"5",
           "nozzle_temperature":["210","210"],"nozzle_temperature_initial_layer":["210","210"],
           "hot_plate_temp":["60","60"],"filament_type":["PLA","PLA"],"outer_wall_speed":["200"],
           "filament_max_volumetric_speed":["24.5","24.5"],"retraction_length":["0.8"],"z_hop":["0.4"],
           "seam_position":"aligned","ironing_type":"no ironing"}"""
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
        assertEquals("sharpest_corner", s.seam)
        assertEquals(false, s.ironing)
        assertTrue(s.source.contains("Qidi Q2C"))
    }

    @Test fun curaProfileHasQidiStart() {
        val o = SliceSettings(nozzleTemp = 215, firstLayerNozzleTemp = 215, bedTemp = 55, firstLayerBedTemp = 55).curaOverrides()
        val start = o["machine_start_gcode"] as String
        assertTrue(start.contains("PRINT_START BED=55 HOTEND=215"))
        assertTrue(start.contains(TOTAL_LAYER_MARKER))
        assertTrue(start.contains(INITIAL_TOOL_MARKER))
        assertEquals(270.0, o["machine_width"])
        assertEquals(false, o["material_print_temp_prepend"])
        assertEquals(60.0, o["support_angle"])  // Orca 30° from horizontal = Cura 60° from vertical
        assertEquals("brim", SliceSettings(brim = 5.0).curaOverrides()["adhesion_type"])
        assertEquals("skirt", SliceSettings(brim = 0.0).curaOverrides()["adhesion_type"])
    }
}
