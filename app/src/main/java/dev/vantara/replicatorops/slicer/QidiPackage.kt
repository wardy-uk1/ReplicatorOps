package dev.vantara.replicatorops.slicer

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI

/**
 * Packs phone-sliced G-code the way QIDI Studio sends it to a Q2C: a ".gcode.3mf" holding
 * Metadata/plate_1.gcode plus the picture, time, weight and filament list. QIDI's Moonraker and
 * the printer's own screen read those files (not the G-code), so without them the screen shows
 * no picture, time or weight, and "external filament" instead of matching the Box.
 */
object QidiPackage {

    data class Filament(
        /** Tool number in the G-code (T0 = filament 1). */
        val tool: Int,
        val type: String,
        /** ARGB, or null if unknown. */
        val color: Long?,
        val usedMm: Double,
        /** QIDI's filament id (e.g. QD_2_1_7) from the Box slot it will come from, if known. */
        val id: String? = null,
    ) {
        val grams: Double get() = grams(type, usedMm)
        val hex: String get() = hex(color)
    }

    data class Job(
        val gcode: File,
        val name: String,
        val seconds: Double,
        val layers: Int,
        val layerHeight: Double,
        val nozzleTemp: Int,
        val bedTemp: Int,
        val filaments: List<Filament>,
        /** 512 px PNG of the plate (shown on the printer and in Fluidd). */
        val picture: ByteArray? = null,
        /** Small PNG for lists. */
        val small: ByteArray? = null,
        val supports: Boolean = false,
        val nozzle: Double = 0.4,
    ) {
        val grams: Double get() = filaments.sumOf { it.grams }
    }

    /** Typical densities (g/cm³) by filament family. */
    fun density(type: String): Double {
        val t = type.uppercase(Locale.ROOT)
        return when {
            t.startsWith("PETG") || t.startsWith("PET") -> 1.27
            t.startsWith("PLA") -> 1.24
            t.startsWith("ABS") -> 1.04
            t.startsWith("ASA") -> 1.07
            t.startsWith("TPU") || t.startsWith("TPE") -> 1.21
            t.startsWith("PC") -> 1.20
            t.startsWith("PA") || t.contains("NYLON") -> 1.14
            t.startsWith("PVA") -> 1.23
            t.startsWith("HIPS") -> 1.04
            else -> 1.24
        }
    }

    /** Weight of [mm] of 1.75 mm filament. */
    fun grams(type: String, mm: Double): Double = mm * PI * 0.875 * 0.875 / 1000.0 * density(type)

    /**
     * QIDI's id for a filament: QD_<box>_<vendor>_<filament list number> (box 2 = Q2C Box,
     * vendor 1 = QIDI). Used when the Box slot's own id isn't known; numbers from QIDI's
     * officiall_filas_list.cfg.
     */
    fun defaultId(type: String): String {
        val t = type.uppercase(Locale.ROOT).replace(' ', '-')
        val n = when {
            t.startsWith("PLA-CF") -> 5
            t.startsWith("PLA") -> 7
            t.startsWith("PETG-CF") -> 42
            t.startsWith("PETG-GF") -> 43
            t.startsWith("PETG") -> 39
            t.startsWith("PET-CF") -> 37
            t.startsWith("ABS-GF") -> 12
            t.startsWith("ABS") -> 11
            t.startsWith("ASA-CF") -> 20
            t.startsWith("ASA") -> 18
            t.startsWith("TPU") -> 50
            t.startsWith("PC") -> 23
            t.startsWith("PA") -> 24
            t.startsWith("PVA") -> 47
            else -> 7
        }
        return "QD_2_1_$n"
    }

    fun hex(color: Long?): String =
        if (color == null) "#FFFFFF" else String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

    fun md5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { String.format(Locale.ROOT, "%02X", it) }
    }

    private fun f2(v: Double) = String.format(Locale.ROOT, "%.2f", v)
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun jstr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun jlist(items: List<String>) = items.joinToString(",", "[", "]") { jstr(it) }

    fun sliceInfo(job: Job): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("<config>")
        appendLine("  <header>")
        appendLine("""    <header_item key="X-QDT-Client-Type" value="slicer"/>""")
        appendLine("""    <header_item key="X-QDT-Client-Version" value="02.07.02.60"/>""")
        appendLine("  </header>")
        appendLine("  <plate>")
        fun meta(k: String, v: String) = appendLine("""    <metadata key="$k" value="${xml(v)}"/>""")
        meta("index", "1")
        meta("extruder_type", "0")
        meta("nozzle_volume_type", "0")
        meta("printer_model_id", "Q2C")
        meta("nozzle_diameters", job.nozzle.toString())
        meta("timelapse_type", "0")
        meta("prediction", job.seconds.toLong().toString())
        meta("weight", f2(job.grams))
        meta("outside", "false")
        meta("support_used", job.supports.toString())
        meta("label_object_enabled", "false")
        meta("enable_filament_dynamic_map", "false")
        meta("has_filament_switcher", "false")
        meta("filament_maps", job.filaments.joinToString(" ") { "1" })
        meta("limit_filament_maps", job.filaments.joinToString(" ") { "0" })
        appendLine("""    <object identify_id="1" name="${xml(job.name)}" skipped="false" />""")
        for (f in job.filaments) {
            appendLine(
                """    <filament id="${f.tool + 1}" tray_info_idx="${xml(f.id ?: defaultId(f.type))}" type="${xml(f.type)}" color="${f.hex}" """ +
                    """used_m="${f2(f.usedMm / 1000)}" used_g="${f2(f.grams)}" group_id="0" nozzle_diameter="${String.format(Locale.ROOT, "%.2f", job.nozzle)}" """ +
                    """volume_type="Standard" used_for_object="true" used_for_support="${job.supports}"/>"""
            )
        }
        appendLine("""    <nozzle id="0" extruder_id="1" nozzle_diameter="${job.nozzle}" volume_type="Standard"/>""")
        appendLine("  </plate>")
        appendLine("</config>")
    }

    /** All filaments T0…Tmax, with unused ones filled in so list positions match tool numbers. */
    private fun byTool(job: Job): List<Filament?> {
        val max = job.filaments.maxOfOrNull { it.tool } ?: 0
        return (0..max).map { t -> job.filaments.firstOrNull { it.tool == t } }
    }

    fun projectSettings(job: Job): String {
        val all = byTool(job)
        val first = job.filaments.firstOrNull()
        fun each(f: (Filament?) -> String) = jlist(all.map(f))
        return buildString {
            append("{\n")
            append("  \"name\": \"project_settings\",\n")
            append("  \"from\": \"project\",\n")
            append("  \"printer_model\": \"Q2C\",\n")
            append("  \"printer_settings_id\": \"Q2C 0.4 nozzle\",\n")
            append("  \"nozzle_diameter\": [\"${job.nozzle}\"],\n")
            append("  \"layer_height\": \"${job.layerHeight}\",\n")
            append("  \"filament_type\": ${each { it?.type ?: first?.type ?: "PLA" }},\n")
            append("  \"filament_colour\": ${each { it?.hex ?: "#FFFFFF" }},\n")
            append("  \"filament_ids\": ${each { it?.let { f -> f.id ?: defaultId(f.type) } ?: defaultId(first?.type ?: "PLA") }},\n")
            append("  \"filament_settings_id\": ${each { f -> "Generic ${f?.type ?: first?.type ?: "PLA"} (Replicator Ops)" }},\n")
            append("  \"filament_vendor\": ${each { "Generic" }},\n")
            append("  \"filament_density\": ${each { String.format(Locale.ROOT, "%.2f", density(it?.type ?: first?.type ?: "PLA")) }},\n")
            append("  \"filament_diameter\": ${each { "1.75" }},\n")
            append("  \"nozzle_temperature\": ${each { job.nozzleTemp.toString() }},\n")
            append("  \"nozzle_temperature_initial_layer\": ${each { job.nozzleTemp.toString() }},\n")
            append("  \"hot_plate_temp\": ${each { job.bedTemp.toString() }},\n")
            append("  \"hot_plate_temp_initial_layer\": ${each { job.bedTemp.toString() }},\n")
            append("  \"thumbnail_size\": \"50x50\",\n")
            append("  \"thumbnails_formats\": \"PNG\"\n")
            append("}\n")
        }
    }

    fun plateJson(job: Job): String {
        val all = byTool(job)
        return "{\"bed_type\":\"textured_plate\",\"filament_colors\":${jlist(all.map { it?.hex ?: "#FFFFFF" })}," +
            "\"filament_ids\":${job.filaments.map { it.tool }.joinToString(",", "[", "]")}," +
            "\"first_extruder\":${job.filaments.firstOrNull()?.tool ?: 0},\"is_seq_print\":false," +
            "\"nozzle_diameter\":${job.nozzle},\"version\":2}"
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
 <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
 <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
 <Default Extension="png" ContentType="image/png"/>
 <Default Extension="gcode" ContentType="text/x.gcode"/>
</Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Target="/3D/3dmodel.model" Id="rel-1" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
 <Relationship Target="/Metadata/plate_1.png" Id="rel-2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail"/>
 <Relationship Target="/Metadata/plate_1.png" Id="rel-4" Type="http://schemas.qiditech.com/package/2021/cover-thumbnail-middle"/>
 <Relationship Target="/Metadata/plate_1_small.png" Id="rel-5" Type="http://schemas.qiditech.com/package/2021/cover-thumbnail-small"/>
</Relationships>"""

    private const val GCODE_RELS = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Target="/Metadata/plate_1.gcode" Id="rel-1" Type="http://schemas.qiditech.com/package/2021/gcode"/>
</Relationships>"""

    private const val MODEL_SETTINGS = """<?xml version="1.0" encoding="UTF-8"?>
<config>
  <plate>
    <metadata key="plater_id" value="1"/>
    <metadata key="plater_name" value=""/>
    <metadata key="locked" value="false"/>
    <metadata key="gcode_file" value="Metadata/plate_1.gcode"/>
    <metadata key="thumbnail_file" value="Metadata/plate_1.png"/>
    <metadata key="pattern_bbox_file" value="Metadata/plate_1.json"/>
  </plate>
</config>"""

    private fun model(job: Job) = """<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:QIDIStudio="http://schemas.qiditech.com/package/2021">
 <metadata name="Application">Replicator Ops</metadata>
 <metadata name="QIDIStudio:3mfVersion">1</metadata>
 <metadata name="Title">${xml(job.name)}</metadata>
 <resources>
 </resources>
 <build/>
</model>"""

    /** Writes [job] as a ".gcode.3mf" to [out]. */
    fun write(job: Job, out: File) {
        val md5 = md5(job.gcode)
        ZipOutputStream(out.outputStream().buffered(256 * 1024)).use { zip ->
            fun text(name: String, s: String) { zip.putNextEntry(ZipEntry(name)); zip.write(s.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            fun bytes(name: String, b: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(b); zip.closeEntry() }
            text("[Content_Types].xml", CONTENT_TYPES)
            text("_rels/.rels", RELS)
            text("3D/3dmodel.model", model(job))
            text("Metadata/_rels/model_settings.config.rels", GCODE_RELS)
            text("Metadata/model_settings.config", MODEL_SETTINGS)
            text("Metadata/slice_info.config", sliceInfo(job))
            text("Metadata/project_settings.config", projectSettings(job))
            text("Metadata/plate_1.json", plateJson(job))
            job.picture?.let { bytes("Metadata/plate_1.png", it) }
            (job.small ?: job.picture)?.let { bytes("Metadata/plate_1_small.png", it) }
            text("Metadata/plate_1.gcode.md5", md5)
            zip.putNextEntry(ZipEntry("Metadata/plate_1.gcode"))
            job.gcode.inputStream().use { it.copyTo(zip, 256 * 1024) }
            zip.closeEntry()
        }
    }
}
