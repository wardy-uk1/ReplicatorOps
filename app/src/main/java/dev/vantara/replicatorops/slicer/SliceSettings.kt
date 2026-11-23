package dev.vantara.replicatorops.slicer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
    /** Where the defaults came from, for the screen. */
    val source: String = "QIDI Q2C · PLA defaults",
) {
    companion object {
        val INFILL_PATTERNS = listOf("grid", "gyroid", "hex", "triangle", "cubic", "linear")

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
                "honeycomb", "3dhoneycomb" -> "hex"
                "triangles", "tri-hexagon" -> "triangle"
                "cubic", "adaptivecubic" -> "cubic"
                "line", "zig-zag", "monotonic", "monotonicline" -> "linear"
                null -> base.infillPattern
                else -> "grid"
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

    /** Kiri:Moto "process" settings. */
    fun kiriProcess(): JsonObject = buildJsonObject {
        put("processName", "Replicator Ops")
        put("sliceHeight", layerHeight)
        put("firstSliceHeight", firstLayerHeight)
        put("sliceShells", walls)
        put("sliceTopLayers", topLayers)
        put("sliceBottomLayers", bottomLayers)
        put("sliceFillSparse", infill / 100.0)
        put("sliceFillType", if (infill <= 0) "none" else infillPattern)
        put("sliceSupportType", if (supports) "automatic" else "disabled")
        put("sliceSupportTree", supports && treeSupports)
        put("sliceSupportAngle", supportAngle)
        put("sliceSupportDensity", 0.15)
        put("sliceSupportOffset", 0.4)
        put("firstLayerBrim", brim)
        put("outputTemp", nozzleTemp)
        put("firstLayerNozzleTemp", firstLayerNozzleTemp)
        put("outputBedTemp", bedTemp)
        put("firstLayerBedTemp", firstLayerBedTemp)
        put("outputFeedrate", infillSpeed)
        put("outputFinishrate", wallSpeed)
        put("firstLayerRate", firstLayerSpeed)
        put("firstLayerFillRate", firstLayerSpeed)
        put("outputSeekrate", travelSpeed)
        put("outputMaxFlowrate", maxFlow)
        put("outputRetractDist", retraction)
        put("outputRetractSpeed", retractionSpeed)
        put("zHopDistance", zHop)
        put("outputFanSpeed", (fan * 255 / 100).coerceIn(0, 255))
        put("outputFanLayer", fanFromLayer)
        put("outputMinLayerTime", minLayerTime)
        put("outputShellMult", 1.0)
        put("outputFillMult", 1.0)
        put("outputSparseMult", 1.0)
        put("sliceLineWidth", 0.42)
    }

    /**
     * Kiri:Moto "device" for the QIDI Q2C: bed, nozzle, and QIDI's own start/end
     * G-code (from QIDI Studio's Q2C profile), so the printer runs its usual
     * PRINT_START routine and takes filament from the QIDI Box (tool T0).
     */
    fun kiriDevice(): JsonObject = buildJsonObject {
        put("deviceName", "QIDI Q2C (Replicator Ops)")
        put("bedWidth", 270)
        put("bedDepth", 270)
        put("maxHeight", 256)
        put("originCenter", false)
        put("fwRetract", false)
        put("gcodeTime", 1)
        putJsonArray("extruders") {
            add(buildJsonObject {
                put("extFilament", 1.75); put("extNozzle", 0.4); put("extOffsetX", 0); put("extOffsetY", 0)
            })
        }
        putJsonArray("gcodePre") { Q2C_START(firstLayerHeight, maxFlow).forEach { add(JsonPrimitive(it)) } }
        putJsonArray("gcodePost") { Q2C_END.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("gcodeLayer") {
            add(JsonPrimitive(";LAYER_CHANGE"))
            add(JsonPrimitive("SET_PRINT_STATS_INFO CURRENT_LAYER={layer + 1}"))
        }
        putJsonArray("gcodeFan") { add(JsonPrimitive("M106 S{fan_speed}")) }
        putJsonArray("gcodeChange") { add(JsonPrimitive("T{tool}")) }
        putJsonObject("extras") { }
    }
}

/** QIDI Studio's Q2C start G-code, with its purge lines worked out for this filament's flow. */
private fun Q2C_START(firstLayer: Double, maxFlow: Double): List<String> {
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
        "; Sliced on the phone by Replicator Ops Beta (Kiri:Moto engine) for QIDI Q2C",
        "PRINT_START BED={bed_temp} HOTEND={temp} EXTRUDER=0",
        "SET_PRINT_STATS_INFO TOTAL_LAYER={layers}",
        "M83",
        "M140 S{bed_temp}",
        "M104 S{temp}",
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
