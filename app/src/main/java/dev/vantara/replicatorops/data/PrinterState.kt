package dev.vantara.replicatorops.data

enum class Connection { DISCONNECTED, CONNECTING, CONNECTED }

data class Heater(
    /** Klipper object name, e.g. "extruder", "heater_bed", "heater_generic chamber". */
    val objectName: String,
    /** Name used in SET_HEATER_TEMPERATURE, or null for read-only sensors. */
    val heaterName: String?,
    val label: String,
    val temperature: Double,
    val target: Double?,
) {
    val canSet: Boolean get() = heaterName != null
}

data class Fan(val objectName: String, val label: String, val speed: Double)

/** One QIDI Box slot as reported by the printer (index is 0-based, shown as 1-4). */
data class BoxSlot(
    val index: Int,
    val loaded: Boolean,
    val material: String?,
    /** ARGB colour, or null if unknown. */
    val color: Long?,
    /** QIDI's filament id for this slot (QD_2_<vendor>_<filament>), as QIDI Studio builds it. */
    val filamentId: String? = null,
)

data class BoxState(
    val slots: List<BoxSlot>,
    val temperature: Double?,
    val humidity: Double?,
    val target: Double?,
    val activeSlot: Int?,
    /** QIDI's tool → slot map (save_variables value_tN = 'slotM'): which Box slot feeds each tool. */
    val toolMap: Map<Int, Int> = emptyMap(),
)

/** One object on the build plate, from Klipper's exclude_object (Target lock). */
data class PrintObject(
    val name: String,
    val center: Pair<Double, Double>?,
    val polygon: List<Pair<Double, Double>>,
)

/** Printer settings shown on the Ship systems screen; null = not reported by this printer. */
data class ShipSystems(
    /** output_pin caselight value 0..1. */
    val caseLight: Double? = null,
    val noodleDetection: Boolean? = null,
    /** Stored as text, e.g. "low", "medium", "high". */
    val noodleSensitivity: String? = null,
    val prePrintCheck: Boolean? = null,
    /** save_variables entries stored as Python booleans (written back as True/False, else 1/0). */
    val boolVars: Set<String> = emptySet(),
)

data class PrinterState(
    val connection: Connection = Connection.DISCONNECTED,
    val connectionError: String? = null,
    /** Klippy state from Moonraker: ready, startup, shutdown, error, disconnected. */
    val klippy: String = "unknown",
    val klippyMessage: String? = null,
    /** print_stats.state: standby, printing, paused, complete, cancelled, error. */
    val printState: String = "standby",
    val filename: String? = null,
    val progress: Double = 0.0,
    /** How far through the file the printer has read (bytes), for the live print view. */
    val filePosition: Long = 0L,
    val printDuration: Double = 0.0,
    val totalDuration: Double = 0.0,
    val filamentUsedMm: Double = 0.0,
    val currentLayer: Int? = null,
    val totalLayers: Int? = null,
    val message: String? = null,
    val heaters: List<Heater> = emptyList(),
    /** Part cooling fan 0..1, or null if the printer has no [fan] object. */
    val partFan: Double? = null,
    val speedFactor: Double = 1.0,
    val extrudeFactor: Double = 1.0,
    val position: List<Double> = emptyList(),
    val homedAxes: String = "",
    val fans: List<Fan> = emptyList(),
    /** QIDI Box, or null when no Box objects are reported. */
    val box: BoxState? = null,
    /** Raw Box-related fields, shown in Config > Diagnostics to help map new firmware. */
    val boxRaw: Map<String, String> = emptyMap(),
    /** Objects on the plate (exclude_object); empty when the file has no object labels. */
    val objects: List<PrintObject> = emptyList(),
    val excludedObjects: Set<String> = emptySet(),
    val currentObject: String? = null,
    /** Bed limits (x, y) from toolhead.axis_minimum/maximum. */
    val bedMin: Pair<Double, Double>? = null,
    val bedMax: Pair<Double, Double>? = null,
    val systems: ShipSystems = ShipSystems(),
    /** Active bed mesh, or null if none is loaded. */
    val bedMesh: BedMesh? = null,
    /** Bed meshes saved on the printer (printer.cfg), by profile name. */
    val meshProfiles: Map<String, BedMesh> = emptyMap(),
    /** Klipper gcode_macro names, minus hidden ones starting with "_". */
    val macros: List<String> = emptyList(),
) {
    val online: Boolean get() = connection == Connection.CONNECTED
    val ready: Boolean get() = online && klippy == "ready"
    val isPrinting: Boolean get() = printState == "printing"
    val isPaused: Boolean get() = printState == "paused"
    val isBusy: Boolean get() = isPrinting || isPaused

    /** Remaining seconds, estimated from elapsed print time and file progress. */
    val remainingSeconds: Double?
        get() = if (progress > 0.01 && printDuration > 0) printDuration / progress - printDuration else null
}

data class GcodeFile(
    val path: String,
    val modified: Double,
    val size: Long,
) {
    val name: String get() = path.substringAfterLast('/')
}

data class FileMeta(
    val thumbnailUrl: String?,
    /** Filaments the slicer set up, by tool number (T0, T1, …). */
    val filamentTypes: List<String> = emptyList(),
    /** ARGB per tool, or null where unknown. */
    val filamentColors: List<Long?> = emptyList(),
    /** Tool numbers the G-code actually uses (Moonraker's referenced_tools). */
    val tools: List<Int> = emptyList(),
    val estimatedSeconds: Double?,
    val filamentMm: Double?,
    val filamentGrams: Double?,
    val layerHeight: Double?,
    val objectHeight: Double?,
    val slicer: String?,
)

data class HistoryJob(
    val filename: String,
    val status: String,
    val startTime: Double,
    val printDuration: Double,
    val filamentUsedMm: Double,
    val filamentType: String?,
)

/** One temperature sample for the thermal log graph. */
data class TempSample(val nozzle: Float, val bed: Float)

/** A timelapse video in Moonraker's "timelapse" root, with its preview image if there is one. */
data class TimelapseFile(
    val path: String,
    val modified: Double,
    val size: Long,
    val thumbnailPath: String?,
) {
    val name: String get() = path.substringAfterLast('/')
}

/** Webcam addresses: a still image and the live MJPEG stream. */
data class Webcam(val snapshot: String, val stream: String)

/** Klipper bed_mesh: heights (mm) in rows from front (min Y) to back, columns left to right. */
data class BedMesh(
    val profile: String,
    val min: Pair<Double, Double>,
    val max: Pair<Double, Double>,
    val heights: List<List<Double>>,
) {
    val lowest: Double get() = heights.flatten().minOrNull() ?: 0.0
    val highest: Double get() = heights.flatten().maxOrNull() ?: 0.0
    val range: Double get() = highest - lowest
}
