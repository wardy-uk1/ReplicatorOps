package dev.vantara.replicatorops.slicer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The settings shown on the Slicer screen. Defaults are QIDI's Q2C PLA profile;
 * a 3MF saved by OrcaSlicer / QIDI Studio / Bambu Studio fills them from its
 * project_settings.config.
 */
data class SliceSettings(
    val layerHeight: Double = 0.2,
    val firstLayerHeight: Double = 0.2,
    val walls: Int = 2,
    val topLayers: Int = 5,
    val bottomLayers: Int = 3,
    /** 0–100 %. */
    val infill: Int = 15,
    val infillPattern: String = "grid",
    val supports: Boolean = false,
    val treeSupports: Boolean = true,
    /** Overhang angle (from horizontal) below which support is added. */
    val supportAngle: Int = 30,
    /** mm; 0 = none. */
    val brim: Double = 0.0,
    val nozzleTemp: Int = 220,
    val firstLayerNozzleTemp: Int = 220,
    val bedTemp: Int = 60,
    val firstLayerBedTemp: Int = 60,
    val filament: String = "PLA",
    /** mm/s. */
    val wallSpeed: Int = 200,
    val infillSpeed: Int = 270,
    val firstLayerSpeed: Int = 50,
    val travelSpeed: Int = 300,
    /** mm³/s the hotend can melt. */
    val maxFlow: Double = 21.0,
    val retraction: Double = 0.8,
    val retractionSpeed: Int = 30,
    val zHop: Double = 0.4,
    /** 0–100 %. */
    val fan: Int = 100,
    val fanFromLayer: Int = 1,
    val minLayerTime: Int = 8,
    /** Smooth the top surfaces with a second, flat pass of the hot nozzle. */
    val ironing: Boolean = false,
    /** Cura z_seam_type: back, shortest, random, sharpest_corner. */
    val seam: String = "sharpest_corner",
    /** Grows every outline by this much (mm), holes kept the same size: thickens thin parts. */
    val xyOffset: Double = 0.0,
    /** Fine random texture on the outside walls. */
    val fuzzySkin: Boolean = false,
    /** Thinner layers on curves and slopes, thicker on straight walls. */
    val adaptiveLayers: Boolean = false,
    /** Where the defaults came from, for the screen. */
    val source: String = "QIDI Q2C · PLA defaults",
) {
    companion object {
        /** CuraEngine infill patterns, with the names shown on screen. */
        val INFILL_PATTERNS = listOf(
            "grid" to "Grid", "gyroid" to "Gyroid", "cubic" to "Cubic", "triangles" to "Triangles",
            "trihexagon" to "Tri-hex", "lines" to "Lines", "lightning" to "Lightning",
        )
        val SEAMS = listOf("sharpest_corner" to "Corner", "back" to "Back", "shortest" to "Nearest", "random" to "Random")

        /** Fills settings from an Orca/QIDI/Bambu project_settings.config. */
        fun fromProject(p: JsonObject?, base: SliceSettings = SliceSettings()): SliceSettings {
            p ?: return base
            fun str(key: String): String? = when (val v = p[key]) {
                is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> v.contentOrNull
                else -> null
            }?.takeIf { it.isNotBlank() && it != "nil" }
            fun num(key: String): Double? = str(key)?.removeSuffix("%")?.toDoubleOrNull()
            fun int(key: String): Int? = num(key)?.roundToInt()
            val pattern = when (str("sparse_infill_pattern")) {
                "gyroid" -> "gyroid"
                "honeycomb", "3dhoneycomb", "tri-hexagon" -> "trihexagon"
                "triangles" -> "triangles"
                "cubic", "adaptivecubic", "supportcubic" -> "cubic"
                "lightning" -> "lightning"
                "line", "zig-zag", "monotonic", "monotonicline" -> "lines"
                null -> base.infillPattern
                else -> "grid"
            }
            val seam = when (str("seam_position")) {
                "back" -> "back"
                "nearest" -> "shortest"
                "random" -> "random"
                "aligned" -> "sharpest_corner"
                else -> base.seam
            }
            val printer = str("printer_model") ?: str("printer_settings_id")
            return base.copy(
                layerHeight = num("layer_height") ?: base.layerHeight,
                firstLayerHeight = num("initial_layer_print_height") ?: base.firstLayerHeight,
                walls = int("wall_loops") ?: base.walls,
                topLayers = int("top_shell_layers") ?: base.topLayers,
                bottomLayers = int("bottom_shell_layers") ?: base.bottomLayers,
                infill = int("sparse_infill_density") ?: base.infill,
                infillPattern = pattern,
                seam = seam,
                ironing = str("ironing_type")?.let { it != "no ironing" && it != "none" } ?: base.ironing,
                supports = str("enable_support")?.let { it == "1" || it == "true" } ?: base.supports,
                treeSupports = str("support_type")?.contains("tree") ?: base.treeSupports,
                supportAngle = int("support_threshold_angle")?.takeIf { it > 0 } ?: base.supportAngle,
                brim = if (str("brim_type") == "no_brim") 0.0 else num("brim_width") ?: base.brim,
                nozzleTemp = int("nozzle_temperature") ?: base.nozzleTemp,
                firstLayerNozzleTemp = int("nozzle_temperature_initial_layer") ?: int("nozzle_temperature") ?: base.firstLayerNozzleTemp,
                bedTemp = int("hot_plate_temp") ?: int("textured_plate_temp") ?: base.bedTemp,
                firstLayerBedTemp = int("hot_plate_temp_initial_layer") ?: int("hot_plate_temp") ?: base.firstLayerBedTemp,
                filament = str("filament_type") ?: base.filament,
                wallSpeed = int("outer_wall_speed") ?: base.wallSpeed,
                infillSpeed = int("sparse_infill_speed") ?: base.infillSpeed,
                firstLayerSpeed = int("initial_layer_speed") ?: base.firstLayerSpeed,
                travelSpeed = int("travel_speed")?.let { min(it, 500) } ?: base.travelSpeed,
                maxFlow = num("filament_max_volumetric_speed") ?: base.maxFlow,
                retraction = num("retraction_length") ?: base.retraction,
                retractionSpeed = int("retraction_speed") ?: base.retractionSpeed,
                zHop = num("z_hop") ?: base.zHop,
                fan = int("fan_max_speed") ?: base.fan,
                fanFromLayer = int("close_fan_the_first_x_layers") ?: base.fanFromLayer,
                minLayerTime = int("slow_down_layer_time") ?: base.minLayerTime,
                source = "Settings from the 3MF" + (printer?.let { " ($it)" } ?: ""),
            )
        }
    }

    /**
     * The CuraEngine settings this screen controls, plus the QIDI Q2C machine itself.
     * Everything else is worked out from these by Cura's own formulas (see [CuraSettings]).
     */
    fun curaOverrides(): Map<String, Any?> = buildMap {
        // Machine: QIDI Q2C, Klipper (Marlin-style G-code), relative extrusion like QIDI Studio.
        put("machine_name", "QIDI Q2C")
        put("machine_width", 270.0); put("machine_depth", 270.0); put("machine_height", 256.0)
        put("machine_center_is_zero", false)
        put("machine_heated_bed", true)
        put("machine_gcode_flavor", "RepRap (Marlin/Sprinter)")
        put("machine_extruder_count", 1L)
        put("machine_nozzle_size", 0.4)
        put("material_diameter", 1.75)
        put("relative_extrusion", true)
        put("machine_acceleration", 10000.0)
        put("machine_max_acceleration_x", 20000.0); put("machine_max_acceleration_y", 20000.0)
        put("machine_max_feedrate_x", 500.0); put("machine_max_feedrate_y", 500.0)
        // PRINT_START heats everything itself, so Cura mustn't add its own M104/M190 first.
        put("material_print_temp_prepend", false)
        put("material_bed_temp_prepend", false)
        put("machine_start_gcode", q2cStart().joinToString("\n"))
        put("machine_end_gcode", Q2C_END.joinToString("\n"))
        // Quality & strength
        put("layer_height", layerHeight)
        put("layer_height_0", firstLayerHeight)
        put("wall_line_count", walls.toLong())
        put("top_layers", topLayers.toLong())
        put("bottom_layers", bottomLayers.toLong())
        put("infill_sparse_density", infill.toDouble())
        put("infill_pattern", infillPattern)
        put("z_seam_type", seam)
        put("ironing_enabled", ironing)
        put("magic_fuzzy_skin_enabled", fuzzySkin)
        put("adaptive_layer_height_enabled", adaptiveLayers)
        put("xy_offset", xyOffset)
        put("xy_offset_layer_0", xyOffset)
        put("hole_xy_offset", xyOffset)
        // Supports & adhesion
        put("support_enable", supports)
        put("support_structure", if (treeSupports) "tree" else "normal")
        // QIDI/Orca measure the threshold from horizontal, Cura from vertical.
        put("support_angle", (90 - supportAngle).coerceIn(0, 90).toDouble())
        if (brim > 0) {
            put("adhesion_type", "brim"); put("brim_width", brim)
        } else {
            put("adhesion_type", "skirt")
        }
        // Filament & temperatures
        put("material_print_temperature", nozzleTemp.toDouble())
        put("material_print_temperature_layer_0", firstLayerNozzleTemp.toDouble())
        put("material_initial_print_temperature", nozzleTemp.toDouble())
        put("material_final_print_temperature", nozzleTemp.toDouble())
        put("material_bed_temperature", bedTemp.toDouble())
        put("material_bed_temperature_layer_0", firstLayerBedTemp.toDouble())
        // Speed
        put("speed_print", infillSpeed.toDouble())
        put("speed_infill", infillSpeed.toDouble())
        put("speed_wall_0", wallSpeed.toDouble())
        put("speed_wall_x", wallSpeed.toDouble())
        put("speed_layer_0", firstLayerSpeed.toDouble())
        put("speed_travel", travelSpeed.toDouble())
        put("retraction_enable", retraction > 0)
        put("retraction_amount", retraction)
        put("retraction_speed", retractionSpeed.toDouble())
        put("retraction_hop_enabled", zHop > 0)
        put("retraction_hop", zHop)
        // Cooling
        put("cool_fan_enabled", fan > 0)
        put("cool_fan_speed", fan.toDouble())
        put("cool_fan_speed_max", fan.toDouble())
        put("cool_fan_speed_0", 0.0)
        put("cool_fan_full_layer", (fanFromLayer + 1).toLong())
        put("cool_min_layer_time", minLayerTime.toDouble())
    }

    private fun q2cStart() = Q2C_START(firstLayerHeight, maxFlow, firstLayerNozzleTemp, firstLayerBedTemp)
}

/** Replaced with the real layer count once CuraEngine has sliced (see SlicerEngine). */
const val TOTAL_LAYER_MARKER = "SET_PRINT_STATS_INFO TOTAL_LAYER={layers}"

/** QIDI Studio's Q2C start G-code, with its purge lines worked out for this filament's flow. */
private fun Q2C_START(firstLayer: Double, maxFlow: Double, temp: Int, bedTemp: Int): List<String> {
    val slow = (maxFlow / 1.2 * 60).roundToInt()
    val wipeSlow = (maxFlow / 0.15 / 4 * 60).roundToInt()
    val wipeFast = (maxFlow / 0.15 * 60).roundToInt()
    fun purge(y: String, e: Int) = listOf(
        "G1 X108.000 Y$y F30000",
        "G0 Z$firstLayer F600",
        "M83",
        "G0 X128 E$e F$slow",
        "G0 X133 E.3742 F$wipeSlow",
        "G0 X138 E.3742 F$wipeFast",
        "G0 X143 E.3742 F$wipeSlow",
        "G0 X148 E.3742 F$wipeFast",
        "G0 X153 E.3742 F$wipeSlow",
        "G91",
        "G1 X1 Z-0.300",
        "G1 X4",
        "G1 Z1 F1200",
        "G90",
        "M400",
    )
    return listOf(
        "; Sliced on the phone by Replicator Ops (CuraEngine) for QIDI Q2C",
        "PRINT_START BED=$bedTemp HOTEND=$temp EXTRUDER=0",
        TOTAL_LAYER_MARKER,
        "M83",
        "M140 S$bedTemp",
        "M104 S$temp",
        "G4 P3000",
        "T0",
        "G90",
    ) + purge("1", 8) + purge("2.5", 10) + listOf("G1 Z1 F600", "G92 E0")
}

private val Q2C_END = listOf(
    "DISABLE_BOX_HEATER",
    "M140 S0",
    "BUFFER_MONITORING ENABLE=0",
    "DISABLE_ALL_SENSOR",
    "G1 E-3 F1800",
    "G91",
    "G0 Z3 F600",
    "G90",
    "UNLOAD_FILAMENT T=0",
    "G0 Y270 F12000",
    "G0 X90 Y270 F12000",
    "M104 S0",
)
