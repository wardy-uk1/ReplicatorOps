package dev.vantara.replicatorops.slicer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/** One mesh of an object, printed with one filament (0-based index into the plate's filaments). */
class Part(
    /** Triangles in the object's own frame: centred on x/y = 0, lowest point at z = 0. */
    val triangles: FloatArray,
    val filament: Int = 0,
)

/** A filament the file asks for. */
data class Filament(val type: String, val color: Long?)

/**
 * An object on the plate: its parts, where its centre sits on the bed (mm), and how it's
 * turned (degrees about Z) and scaled. Immutable: edits make a new copy.
 */
data class PlacedObject(
    val id: Int,
    val name: String,
    val parts: List<Part>,
    val x: Float,
    val y: Float,
    val rotation: Float = 0f,
    val scale: Float = 1f,
) {
    /** Outline seen from above (convex hull of the object's own points, before turning/scaling). */
    val hull: FloatArray by lazy { Geometry.hull(parts) }

    /** Turns, scales and moves one part into bed coordinates. */
    fun world(part: Part): FloatArray {
        val t = part.triangles
        val out = FloatArray(t.size)
        val r = Math.toRadians(rotation.toDouble())
        val c = Math.cos(r).toFloat() * scale; val sn = Math.sin(r).toFloat() * scale
        var i = 0
        while (i < t.size) {
            val px = t[i]; val py = t[i + 1]
            out[i] = px * c - py * sn + x
            out[i + 1] = px * sn + py * c + y
            out[i + 2] = t[i + 2] * scale
            i += 3
        }
        return out
    }

    /** The outline in bed coordinates (x0, y0, x1, y1, …). */
    fun worldHull(): FloatArray {
        val h = hull
        val out = FloatArray(h.size)
        val r = Math.toRadians(rotation.toDouble())
        val c = Math.cos(r).toFloat() * scale; val sn = Math.sin(r).toFloat() * scale
        for (i in 0 until h.size / 2) {
            out[i * 2] = h[i * 2] * c - h[i * 2 + 1] * sn + x
            out[i * 2 + 1] = h[i * 2] * sn + h[i * 2 + 1] * c + y
        }
        return out
    }

    /** Footprint (min x, min y, max x, max y) on the bed. */
    fun bounds(): FloatArray {
        val h = worldHull()
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until h.size / 2) {
            x0 = minOf(x0, h[i * 2]); x1 = maxOf(x1, h[i * 2]); y0 = minOf(y0, h[i * 2 + 1]); y1 = maxOf(y1, h[i * 2 + 1])
        }
        return floatArrayOf(x0, y0, x1, y1)
    }

    val height: Float get() {
        var m = 0f
        for (p in parts) { var i = 2; while (i < p.triangles.size) { m = maxOf(m, p.triangles[i]); i += 3 } }
        return m * scale
    }
}

/** One build plate: the objects on it and the filaments they use. */
class Plate(
    val number: Int,
    val name: String,
    val objects: List<PlacedObject>,
    /** Plate preview from the 3MF (PNG), if it has one. */
    val thumbnail: ByteArray?,
    /** Filaments the file defines (at least one). */
    val filaments: List<Filament> = listOf(Filament("PLA", null)),
) {
    val objectNames: List<String> get() = objects.map { it.name }

    /** Every triangle in bed coordinates, as x1,y1,z1,x2,y2,z2,x3,y3,z3 … (mm). */
    val triangles: FloatArray by lazy { Geometry.concat(objects.flatMap { o -> o.parts.map { o.world(it) } }) }

    val triangleCount: Int get() = objects.sumOf { o -> o.parts.sumOf { it.triangles.size / 9 } }

    /** Filaments actually used by the objects, in order. */
    val usedFilaments: List<Int> get() = objects.flatMap { o -> o.parts.map { it.filament } }.distinct().sorted()

    fun copy(objects: List<PlacedObject> = this.objects) = Plate(number, name, objects, thumbnail, filaments)

    /** Size of the plate's contents in mm (x, y, z). */
    val size: Triple<Float, Float, Float>
        get() {
            if (objects.isEmpty()) return Triple(0f, 0f, 0f)
            val b = objects.map { it.bounds() }
            return Triple(b.maxOf { it[2] } - b.minOf { it[0] }, b.maxOf { it[3] } - b.minOf { it[1] }, objects.maxOf { it.height })
        }

    /** True if everything sits inside a bed of [w] × [d] × [h] mm. */
    fun fits(w: Float = 270f, d: Float = 270f, h: Float = 256f): Boolean = objects.all { o ->
        val b = o.bounds(); b[0] >= -0.01f && b[1] >= -0.01f && b[2] <= w + 0.01f && b[3] <= d + 0.01f && o.height <= h
    }
}

/** Mesh helpers shared by the reader, the arranger and the ship's computer. */
object Geometry {
    fun concat(parts: List<FloatArray>): FloatArray {
        if (parts.size == 1) return parts[0]
        val out = FloatArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) { System.arraycopy(p, 0, out, pos, p.size); pos += p.size }
        return out
    }

    /** Builds an object from world-space parts: centres it on x/y, drops it to z = 0, remembers where it was. */
    fun place(id: Int, name: String, world: List<Pair<FloatArray, Int>>): PlacedObject {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for ((t, _) in world) {
            var i = 0
            while (i < t.size) {
                x0 = minOf(x0, t[i]); x1 = maxOf(x1, t[i]); y0 = minOf(y0, t[i + 1]); y1 = maxOf(y1, t[i + 1]); z0 = minOf(z0, t[i + 2])
                i += 3
            }
        }
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
        val parts = world.map { (t, f) ->
            val o = FloatArray(t.size)
            var i = 0
            while (i < t.size) { o[i] = t[i] - cx; o[i + 1] = t[i + 1] - cy; o[i + 2] = t[i + 2] - z0; i += 3 }
            Part(o, f)
        }
        return PlacedObject(id, name, parts, cx, cy)
    }

    /** Convex hull of the parts' x/y points (Andrew's monotone chain), as x0, y0, x1, y1 … */
    fun hull(parts: List<Part>): FloatArray {
        val total = parts.sumOf { it.triangles.size / 3 }
        if (total == 0) return FloatArray(0)
        val step = maxOf(1, total / 40_000)
        val pts = ArrayList<Long>(minOf(total, 40_000) + 8)
        var k = 0
        for (p in parts) {
            var i = 0
            while (i < p.triangles.size) {
                if (k++ % step == 0) {
                    val x = Math.round(p.triangles[i] * 100); val y = Math.round(p.triangles[i + 1] * 100)
                    pts.add((x.toLong() shl 32) or (y.toLong() and 0xffffffffL))
                }
                i += 3
            }
        }
        val sorted = pts.distinct().map { (it shr 32).toInt() to it.toInt() }.sortedWith(compareBy({ it.first }, { it.second }))
        if (sorted.size < 3) return FloatArray(sorted.size * 2) { j -> (if (j % 2 == 0) sorted[j / 2].first else sorted[j / 2].second) / 100f }
        fun cross(o: Pair<Int, Int>, a: Pair<Int, Int>, b: Pair<Int, Int>) =
            (a.first - o.first).toLong() * (b.second - o.second) - (a.second - o.second).toLong() * (b.first - o.first)
        val lower = ArrayList<Pair<Int, Int>>()
        for (p in sorted) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1); lower += p }
        val upper = ArrayList<Pair<Int, Int>>()
        for (p in sorted.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1); upper += p }
        val h = lower.dropLast(1) + upper.dropLast(1)
        return FloatArray(h.size * 2) { j -> (if (j % 2 == 0) h[j / 2].first else h[j / 2].second) / 100f }
    }

    /** True if (x, y) is inside the convex polygon [poly] (x0, y0, x1, y1 …). */
    fun inside(poly: FloatArray, x: Float, y: Float): Boolean {
        val n = poly.size / 2
        if (n < 3) return false
        var sign = 0
        for (i in 0 until n) {
            val ax = poly[i * 2]; val ay = poly[i * 2 + 1]
            val bx = poly[((i + 1) % n) * 2]; val by = poly[((i + 1) % n) * 2 + 1]
            val c = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            val sg = if (c > 0) 1 else if (c < 0) -1 else 0
            if (sg != 0) { if (sign == 0) sign = sg else if (sg != sign) return false }
        }
        return true
    }
}

/** A model file opened for slicing: its plates, and the slicer settings saved with it (if any). */
class ModelFile(
    val name: String,
    val plates: List<Plate>,
    /** project_settings.config from an Orca/QIDI/Bambu 3MF, or null for STL and plain 3MF. */
    val projectSettings: JsonObject?,
)

/**
 * Reads STL and 3MF files (including Bambu Studio / OrcaSlicer / QIDI Studio projects
 * with several plates and objects split into separate model files under 3D/Objects).
 */
object ModelReader {

    fun read(file: File, displayName: String = file.name): ModelFile {
        val lower = displayName.lowercase()
        return if (lower.endsWith(".stl")) {
            val tris = Stl.read(file.inputStream().buffered())
            val name = displayName.substringBeforeLast('.')
            val obj = Geometry.place(1, name, listOf(tris to 0))
            ModelFile(displayName, listOf(Plate(1, "", listOf(obj.copy(x = 135f, y = 135f)), null)), null)
        } else {
            readThreeMf(file, displayName)
        }
    }

    // ---- 3MF ----------------------------------------------------------------

    private class Obj(val id: String, var name: String?) {
        var mesh: FloatArray? = null
        val components = mutableListOf<Comp>()
    }
    private class Comp(val path: String?, val objectId: String, val transform: FloatArray?)
    private class Item(val objectId: String, val transform: FloatArray?, val printable: Boolean)

    private class ModelDoc(val objects: Map<String, Obj>, val items: List<Item>)

    private fun readThreeMf(file: File, displayName: String): ModelFile = ZipFile(file).use { zip ->
        fun bytes(path: String): ByteArray? =
            zip.getEntry(path.removePrefix("/"))?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

        val rootPath = zip.entries().asSequence().map { it.name }
            .firstOrNull { it.equals("3D/3dmodel.model", ignoreCase = true) }
            ?: zip.entries().asSequence().map { it.name }.firstOrNull { it.lowercase().endsWith(".model") }
            ?: throw IllegalArgumentException("No 3D model inside this 3MF")
        val docs = HashMap<String, ModelDoc>()
        fun doc(path: String): ModelDoc? {
            val key = path.removePrefix("/")
            return docs.getOrPut(key) { bytes(key)?.let { parseModel(it) } ?: return null }
        }
        val root = doc(rootPath) ?: throw IllegalArgumentException("Couldn't read the 3D model")

        // Flatten an object (mesh or components) into world triangles.
        fun flatten(docPath: String, objectId: String, transform: FloatArray?, depth: Int = 0): FloatArray {
            if (depth > 8) return FloatArray(0)
            val d = doc(docPath) ?: return FloatArray(0)
            val o = d.objects[objectId] ?: return FloatArray(0)
            val parts = ArrayList<FloatArray>()
            o.mesh?.let { parts += transformed(it, transform) }
            for (c in o.components) {
                val sub = flatten(c.path ?: docPath, c.objectId, c.transform, depth + 1)
                parts += transformed(sub, transform)
            }
            return Geometry.concat(parts)
        }

        // Bambu/Orca/QIDI model_settings.config: plates, names, and which filament each part uses.
        val info = bytes("Metadata/model_settings.config")?.let { parseSettings(it) } ?: SettingsInfo()
        // PrusaSlicer: volumes (triangle ranges) with their own filament.
        val prusa = bytes("Metadata/Slic3r_PE_model.config")?.let { parsePrusaVolumes(it) }.orEmpty()

        // The parts of one build item, each with its filament (0-based).
        fun partsOf(item: Item): List<Pair<FloatArray, Int>> {
            val o = root.objects[item.objectId] ?: return emptyList()
            val objFil = (info.objectExtruder[item.objectId] ?: 1).coerceAtLeast(1) - 1
            val out = ArrayList<Pair<FloatArray, Int>>()
            o.mesh?.let { mesh ->
                val vols = prusa[item.objectId]
                if (vols.isNullOrEmpty()) out += transformed(mesh, item.transform) to objFil
                else for (v in vols) {
                    if (!v.printable) continue
                    val from = v.first * 9; val to = minOf((v.last + 1) * 9, mesh.size)
                    if (from >= to) continue
                    out += transformed(mesh.copyOfRange(from, to), item.transform) to (if (v.extruder > 0) v.extruder - 1 else objFil)
                }
            }
            for (c in o.components) {
                val key = item.objectId + "/" + c.objectId
                if (info.partSubtype[key]?.let { it != "normal_part" } == true) continue  // modifiers, negative parts
                val fil = info.partExtruder[key]?.takeIf { it > 0 }?.minus(1) ?: objFil
                val sub = flatten(c.path ?: rootPath, c.objectId, c.transform, 1)
                out += transformed(sub, item.transform) to fil
            }
            return out.filter { it.first.isNotEmpty() }
        }

        val printable = root.items.filter { it.printable }
        val plates = if (info.plates.isEmpty()) {
            listOf(1 to printable.map { it.objectId })
        } else {
            info.plates.entries.sortedBy { it.key }.map { (n, ids) -> n to ids }
        }
        val settings = bytes("Metadata/project_settings.config")?.let {
            runCatching { Json.parseToJsonElement(String(it)).jsonObject }.getOrNull()
        }
        val filaments = filamentsFrom(settings, bytes("Metadata/Slic3r_PE.config")?.let { String(it) })
        var nextId = 1
        val result = plates.mapNotNull { (n, ids) ->
            val items = printable.filter { it.objectId in ids }
            if (items.isEmpty()) return@mapNotNull null
            val objects = items.mapNotNull { item ->
                val parts = partsOf(item)
                if (parts.isEmpty()) null
                else Geometry.place(nextId++, info.names[item.objectId] ?: root.objects[item.objectId]?.name ?: "Object ${item.objectId}", parts)
            }
            if (objects.isEmpty()) null else Plate(n, "", onBed(objects), bytes("Metadata/plate_$n.png"), filaments)
        }
        if (result.isEmpty()) throw IllegalArgumentException("No printable objects in this 3MF")
        ModelFile(displayName, result, settings)
    }

    /**
     * Keeps the 3MF's layout when it fits the Q2C's bed. Otherwise (another printer's bed, or
     * Bambu's second plate further along) the group is centred, or re-arranged if it's too big.
     */
    private fun onBed(objects: List<PlacedObject>): List<PlacedObject> {
        val plate = Plate(0, "", objects, null)
        if (plate.fits()) return objects
        val centred = Arrange.centre(objects)
        return if (Plate(0, "", centred, null).fits()) centred else Arrange.auto(objects)
    }

    /** Filament types and colours from an Orca/Bambu project, or PrusaSlicer's config. */
    private fun filamentsFrom(settings: JsonObject?, prusaConfig: String?): List<Filament> {
        fun list(key: String): List<String> = when (val v = settings?.get(key)) {
            is kotlinx.serialization.json.JsonArray -> v.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            is kotlinx.serialization.json.JsonPrimitive -> v.content.split(';')
            else -> emptyList()
        }
        var types = list("filament_type")
        var colours = list("filament_colour")
        if (types.isEmpty() && prusaConfig != null) {
            fun line(key: String) = Regex("^; $key = (.*)$", RegexOption.MULTILINE).find(prusaConfig)?.groupValues?.get(1)?.split(';').orEmpty()
            types = line("filament_type")
            colours = line("extruder_colour").takeIf { c -> c.any { it.startsWith("#") && it.length >= 7 } } ?: line("filament_colour")
        }
        val n = maxOf(types.size, colours.size, 1)
        return (0 until n).map { i ->
            Filament(types.getOrNull(i)?.trim()?.ifEmpty { null } ?: types.firstOrNull()?.trim() ?: "PLA", colours.getOrNull(i)?.let(::parseHex))
        }
    }

    private fun parseHex(s: String): Long? {
        val h = s.trim().removePrefix("#").take(6)
        return h.toLongOrNull(16)?.takeIf { h.length == 6 }?.let { 0xFF000000L or it }
    }

    private class PrusaVolume(val first: Int, val last: Int, val extruder: Int, val printable: Boolean)

    /** Slic3r_PE_model.config: object id -> volumes (triangle ranges) with extruder (0 = the object's). */
    private fun parsePrusaVolumes(data: ByteArray): Map<String, List<PrusaVolume>> {
        val out = HashMap<String, MutableList<PrusaVolume>>()
        val handler = object : DefaultHandler() {
            var obj: String? = null
            var first = 0; var last = -1; var extruder = 0; var type = "ModelPart"; var inVolume = false
            var objExtruder = 0
            override fun startElement(uri: String?, local: String?, qName: String, a: Attributes) {
                when (qName) {
                    "object" -> { obj = a.getValue("id"); objExtruder = 0 }
                    "volume" -> {
                        inVolume = true; first = a.getValue("firstid")?.toIntOrNull() ?: 0; last = a.getValue("lastid")?.toIntOrNull() ?: -1
                        extruder = 0; type = "ModelPart"
                    }
                    "metadata" -> {
                        val key = a.getValue("key"); val value = a.getValue("value") ?: ""
                        if (inVolume && key == "extruder") extruder = value.toIntOrNull() ?: 0
                        if (inVolume && key == "volume_type") type = value
                        if (!inVolume && key == "extruder") objExtruder = value.toIntOrNull() ?: 0
                    }
                }
            }
            override fun endElement(uri: String?, local: String?, qName: String) {
                if (qName == "volume") {
                    inVolume = false
                    obj?.let { out.getOrPut(it) { mutableListOf() } += PrusaVolume(first, last, if (extruder > 0) extruder else objExtruder, type == "ModelPart") }
                }
            }
        }
        newParser().parse(ByteArrayInputStream(data), handler)
        return out
    }

    private fun parseModel(data: ByteArray): ModelDoc {
        val objects = LinkedHashMap<String, Obj>()
        val items = ArrayList<Item>()
        val handler = object : DefaultHandler() {
            var current: Obj? = null
            var verts = FloatArrayList()
            var tris = FloatArrayList()
            override fun startElement(uri: String?, local: String?, qName: String, a: Attributes) {
                when (qName.substringAfter(':')) {
                    "object" -> {
                        current = Obj(a.getValue("id") ?: "", a.getValue("name")).also { objects[it.id] = it }
                    }
                    "mesh" -> { verts = FloatArrayList(); tris = FloatArrayList() }
                    "vertex" -> {
                        verts.add(a.getValue("x").toFloat()); verts.add(a.getValue("y").toFloat()); verts.add(a.getValue("z").toFloat())
                    }
                    "triangle" -> {
                        val v = verts.data
                        for (k in arrayOf("v1", "v2", "v3")) {
                            val idx = a.getValue(k).toInt() * 3
                            tris.add(v[idx]); tris.add(v[idx + 1]); tris.add(v[idx + 2])
                        }
                    }
                    "component" -> current?.components?.add(
                        Comp(attr(a, "path"), a.getValue("objectid") ?: "", a.getValue("transform")?.let(::matrix))
                    )
                    "item" -> items += Item(
                        a.getValue("objectid") ?: "",
                        a.getValue("transform")?.let(::matrix),
                        a.getValue("printable") != "0",
                    )
                }
            }
            override fun endElement(uri: String?, local: String?, qName: String) {
                when (qName.substringAfter(':')) {
                    "mesh" -> current?.mesh = tris.toArray()
                    "object" -> current = null
                }
            }
        }
        newParser().parse(ByteArrayInputStream(data), handler)
        return ModelDoc(objects, items)
    }

    private class SettingsInfo(
        val plates: Map<Int, List<String>> = emptyMap(),
        val names: Map<String, String> = emptyMap(),
        /** Object id -> filament number (1-based). */
        val objectExtruder: Map<String, Int> = emptyMap(),
        /** "objectId/partId" -> filament number (1-based, 0 = the object's). */
        val partExtruder: Map<String, Int> = emptyMap(),
        /** "objectId/partId" -> normal_part, modifier_part, negative_part … */
        val partSubtype: Map<String, String> = emptyMap(),
    )

    /** model_settings.config: plates (plater_id -> object ids), names, and filaments per object and part. */
    private fun parseSettings(data: ByteArray): SettingsInfo {
        val plates = LinkedHashMap<Int, MutableList<String>>()
        val names = HashMap<String, String>()
        val objExt = HashMap<String, Int>()
        val partExt = HashMap<String, Int>()
        val partSub = HashMap<String, String>()
        val handler = object : DefaultHandler() {
            var inPlate = false
            var inInstance = false
            var plate = 0
            var objectId: String? = null
            var partId: String? = null
            var objectDepth = 0
            override fun startElement(uri: String?, local: String?, qName: String, a: Attributes) {
                when (qName) {
                    "object" -> { objectId = a.getValue("id"); objectDepth = 0 }
                    "part" -> {
                        objectDepth++
                        partId = a.getValue("id")
                        val o = objectId; val pid = partId
                        if (o != null && pid != null) a.getValue("subtype")?.let { partSub["$o/$pid"] = it }
                    }
                    "plate" -> { inPlate = true; plate = 0 }
                    "model_instance" -> inInstance = true
                    "metadata" -> {
                        val key = a.getValue("key"); val value = a.getValue("value") ?: ""
                        val o = objectId
                        when {
                            inPlate && !inInstance && key == "plater_id" -> plate = value.toIntOrNull() ?: 0
                            inPlate && inInstance && key == "object_id" -> plates.getOrPut(plate) { mutableListOf() } += value
                            !inPlate && o != null && objectDepth == 0 && key == "name" -> names[o] = value
                            !inPlate && o != null && objectDepth == 0 && key == "extruder" -> value.toIntOrNull()?.let { objExt[o] = it }
                            !inPlate && o != null && objectDepth > 0 && key == "extruder" -> value.toIntOrNull()?.let { partExt["$o/$partId"] = it }
                        }
                    }
                }
            }
            override fun endElement(uri: String?, local: String?, qName: String) {
                when (qName) {
                    "plate" -> inPlate = false
                    "model_instance" -> inInstance = false
                    "part" -> { objectDepth--; partId = null }
                    "object" -> objectId = null
                }
            }
        }
        newParser().parse(ByteArrayInputStream(data), handler)
        return SettingsInfo(plates, names, objExt, partExt, partSub)
    }

    private fun newParser() = SAXParserFactory.newInstance().apply { isNamespaceAware = false }.newSAXParser()

    /** Attribute by local name, whatever its namespace prefix (p:path). */
    private fun attr(a: Attributes, local: String): String? {
        for (i in 0 until a.length) if (a.getQName(i).substringAfter(':') == local) return a.getValue(i)
        return null
    }

    /** 3MF transform "m00 m01 m02 m10 m11 m12 m20 m21 m22 m30 m31 m32". */
    private fun matrix(text: String): FloatArray? =
        text.trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 12 }?.toFloatArray()

    fun transformed(tris: FloatArray, m: FloatArray?): FloatArray {
        if (m == null) return tris
        val out = FloatArray(tris.size)
        var i = 0
        while (i < tris.size) {
            val x = tris[i]; val y = tris[i + 1]; val z = tris[i + 2]
            out[i] = x * m[0] + y * m[3] + z * m[6] + m[9]
            out[i + 1] = x * m[1] + y * m[4] + z * m[7] + m[10]
            out[i + 2] = x * m[2] + y * m[5] + z * m[8] + m[11]
            i += 3
        }
        return out
    }

    /** Moves a mesh so its lowest point sits on the bed (z = 0). */
    fun dropToBed(tris: FloatArray): FloatArray {
        if (tris.isEmpty()) return tris
        var minZ = Float.MAX_VALUE
        var i = 2
        while (i < tris.size) { if (tris[i] < minZ) minZ = tris[i]; i += 3 }
        if (minZ == 0f) return tris
        val out = tris.copyOf()
        i = 2
        while (i < out.size) { out[i] -= minZ; i += 3 }
        return out
    }

    private fun concat(parts: List<FloatArray>): FloatArray {
        if (parts.size == 1) return parts[0]
        val out = FloatArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) { System.arraycopy(p, 0, out, pos, p.size); pos += p.size }
        return out
    }
}

/** Growable float list without boxing. */
internal class FloatArrayList {
    var data = FloatArray(1024)
    var size = 0
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    fun toArray(): FloatArray = data.copyOf(size)
}

/** Binary/ASCII STL in, binary STL out. */
object Stl {
    fun read(input: InputStream): FloatArray {
        val bytes = input.use { it.readBytes() }
        if (bytes.size >= 84) {
            val count = ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (84 + count * 50 == bytes.size.toLong()) return readBinary(bytes, count.toInt())
        }
        return readAscii(String(bytes))
    }

    private fun readBinary(bytes: ByteArray, count: Int): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(count * 9)
        var p = 84
        for (t in 0 until count) {
            p += 12 // normal
            for (k in 0 until 9) { out[t * 9 + k] = buf.getFloat(p); p += 4 }
            p += 2
        }
        return out
    }

    private fun readAscii(text: String): FloatArray {
        val list = FloatArrayList()
        Regex("""vertex\s+(\S+)\s+(\S+)\s+(\S+)""").findAll(text).forEach { m ->
            list.add(m.groupValues[1].toFloat()); list.add(m.groupValues[2].toFloat()); list.add(m.groupValues[3].toFloat())
        }
        return list.toArray()
    }

    fun writeBinary(tris: FloatArray, out: OutputStream) {
        val count = tris.size / 9
        val header = ByteArray(80).also { "Replicator Ops".toByteArray().copyInto(it) }
        out.write(header)
        val buf = ByteBuffer.allocate(50 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(count).array())
        for (t in 0 until count) {
            if (buf.remaining() < 50) { out.write(buf.array(), 0, buf.position()); buf.clear() }
            val b = t * 9
            // Facet normal from the winding.
            val ux = tris[b + 3] - tris[b]; val uy = tris[b + 4] - tris[b + 1]; val uz = tris[b + 5] - tris[b + 2]
            val vx = tris[b + 6] - tris[b]; val vy = tris[b + 7] - tris[b + 1]; val vz = tris[b + 8] - tris[b + 2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val len = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
            if (len > 0f) { nx /= len; ny /= len; nz /= len }
            buf.putFloat(nx); buf.putFloat(ny); buf.putFloat(nz)
            for (k in 0 until 9) buf.putFloat(tris[b + k])
            buf.putShort(0)
        }
        out.write(buf.array(), 0, buf.position())
    }
}
