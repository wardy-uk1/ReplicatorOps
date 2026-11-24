package dev.vantara.replicatorops.slicer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Checks the Cura pipeline the app runs on the phone. Needs CI's build outputs:
 *  - CURA_SETTINGS: settings.json from compile_defs.py (the app asset)
 *  - CURA_ENGINE:   a CuraEngine built for this machine (for the slicing test)
 * Writes build/cura-check/ for CI to compare against Cura's own Python formulas.
 */
class CuraSlicingTest {
    private val settingsFile = System.getenv("CURA_SETTINGS")?.let(::File)
    private val engine = System.getenv("CURA_ENGINE")?.let(::File)
    private val out = File("build/cura-check").apply { mkdirs() }

    private val variants = mapOf(
        "defaults" to SliceSettings(),
        "tree" to SliceSettings(supports = true, treeSupports = true, brim = 5.0, layerHeight = 0.24, walls = 3, infill = 20, xyOffset = 0.1),
        "ironing" to SliceSettings(ironing = true, infillPattern = "gyroid", seam = "back", infill = 0, topLayers = 4, fuzzySkin = true, adaptiveLayers = true),
        "solid" to SliceSettings(infill = 100, fan = 0, zHop = 0.0, retraction = 0.0, layerHeight = 0.12, firstLayerHeight = 0.16,
            supports = true, treeSupports = false, supportAngle = 45, infillPattern = "lightning"),
    )

    private fun table(): CuraSettings {
        assumeTrue("CURA_SETTINGS not set", settingsFile?.exists() == true)
        return CuraSettings.load(settingsFile!!.readText())
    }

    private fun js(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { js(it) })
        else -> JsonPrimitive(v.toString())
    }

    @Test fun resolvesEverySetting() {
        val t = table()
        for ((name, s) in variants) {
            val over = s.curaOverrides()
            val values = t.resolve(over)
            assertEquals(t.keys.size, values.size)
            File(out, "$name.overrides.json").writeText(JsonObject(over.mapValues { js(it.value) }).toString())
            File(out, "$name.kotlin.json").writeText(JsonObject(values.mapValues { js(it.value) }).toString())
            // Formulas that matter for this screen's settings.
            assertEquals(s.layerHeight, values["layer_height"] as Double, 1e-9)
            assertEquals(s.walls.toLong(), values["wall_line_count"])
            assertTrue((values["infill_line_distance"] as Double) >= 0.0)
            if (s.infill in 1..99) assertTrue((values["infill_line_distance"] as Double) > 0.0)
        }
    }

    @Test fun slicesWithCuraEngine() {
        val t = table()
        assumeTrue("CURA_ENGINE not set", engine?.canExecute() == true)
        val models = mapOf("cube" to box(listOf(floatArrayOf(125f, 125f, 0f, 145f, 145f, 20f))),
            "overhang" to box(listOf(floatArrayOf(130f, 130f, 0f, 140f, 140f, 30f), floatArrayOf(105f, 130f, 30f, 165f, 140f, 36f))))
        for ((vName, s) in variants) for ((mName, tris) in models) {
            val tag = "$vName-$mName"
            val json = File(out, "$tag.engine.json").apply { writeText(CuraSettings.toEngineJson(t.resolve(s.curaOverrides()), t).toString()) }
            val stl = File(out, "$tag.stl").apply { outputStream().use { Stl.writeBinary(CuraGcode.placeOnBed(tris, 270f, 270f), it) } }
            val raw = File(out, "$tag.raw.gcode")
            val proc = ProcessBuilder(engine!!.path, "slice", "-j", json.path, "-l", stl.path, "-o", raw.path).redirectErrorStream(true).start()
            val log = CuraGcode.EngineLog()
            var lastProgress = 0
            proc.inputStream.bufferedReader().useLines { lines -> lines.forEach { l -> log.add(l)?.let { lastProgress = it } } }
            val exit = proc.waitFor()
            assertEquals("$tag: ${log.lastError}", 0, exit)
            val gcode = File(out, "$tag.gcode")
            val info = CuraGcode.finish(raw, gcode, log.header, listOf(96 to "QUJD"))
            val text = gcode.readText()
            println("$tag: layers=${info.layers} seconds=${info.seconds} filament=${info.filamentMm} progress=$lastProgress bytes=${text.length}")
            assertTrue(tag, info.layers > 10)
            assertTrue(tag, (info.seconds ?: 0.0) > 0)
            assertTrue(tag, (info.filamentMm ?: 0.0) > 0)
            assertTrue(tag, text.startsWith(";FLAVOR:Marlin"))
            assertTrue(tag, text.contains(";Generated with Cura_SteamEngine"))
            assertTrue(tag, text.contains("; thumbnail begin 96x96 4"))
            assertTrue(tag, text.contains("PRINT_START BED=60 HOTEND=220"))
            assertTrue(tag, text.contains("SET_PRINT_STATS_INFO TOTAL_LAYER=${info.layers}"))
            assertTrue(tag, text.contains("SET_PRINT_STATS_INFO CURRENT_LAYER=1\n"))
            assertTrue(tag, !text.contains("{layers}") && !text.contains(";TIME:6666"))
            assertTrue(tag, text.contains("UNLOAD_FILAMENT T=0"))
            assertTrue(tag, text.contains("M83"))
            // Nothing outside the Q2C's bed.
            Regex("G[01] .*X([-\\d.]+) Y([-\\d.]+)").findAll(text).forEach { m ->
                val x = m.groupValues[1].toDouble(); val y = m.groupValues[2].toDouble()
                assertTrue("$tag: $x,$y", x in -1.0..271.0 && y in -1.0..271.0)
            }
        }
    }

    private fun box(boxes: List<FloatArray>): FloatArray {
        val tri = ArrayList<Float>()
        for ((x0, y0, z0, x1, y1, z1) in boxes.map { listOf(it[0], it[1], it[2], it[3], it[4], it[5]) }) {
            val p = arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
                floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
            fun quad(a: Int, b: Int, c: Int, d: Int) = listOf(a, b, c, a, c, d).forEach { i -> p[i].forEach { tri += it } }
            quad(0, 3, 2, 1); quad(4, 5, 6, 7); quad(0, 1, 5, 4); quad(1, 2, 6, 5); quad(2, 3, 7, 6); quad(3, 0, 4, 7)
        }
        return tri.toFloatArray()
    }

    private operator fun <T> List<T>.component6() = this[5]
}
