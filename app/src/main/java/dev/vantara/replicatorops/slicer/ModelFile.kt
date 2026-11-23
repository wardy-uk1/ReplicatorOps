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

/** One build plate's worth of geometry, ready to slice. */
class Plate(
    val number: Int,
    val name: String,
    val objectNames: List<String>,
    /** Triangles as x1,y1,z1,x2,y2,z2,x3,y3,z3 … in mm, each object dropped onto the bed. */
    val triangles: FloatArray,
    /** Plate preview from the 3MF (PNG), if it has one. */
    val thumbnail: ByteArray?,
) {
    val triangleCount: Int get() = triangles.size / 9

    /** Size of the plate's contents in mm (x, y, z). */
    val size: Triple<Float, Float, Float>
        get() {
            if (triangles.isEmpty()) return Triple(0f, 0f, 0f)
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            var i = 0
            while (i < triangles.size) {
                val x = triangles[i]; val y = triangles[i + 1]; val z = triangles[i + 2]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                i += 3
            }
            return Triple(maxX - minX, maxY - minY, maxZ - minZ)
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
 * with several plates and objects split into 3D/Objects/*.model).
 */
object ModelReader {

    fun read(file: File, displayName: String = file.name): ModelFile {
        val lower = displayName.lowercase()
        return if (lower.endsWith(".stl")) {
            val tris = Stl.read(file.inputStream().buffered())
            ModelFile(displayName, listOf(Plate(1, "", listOf(displayName.substringBeforeLast('.')), dropToBed(tris), null)), null)
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
            return concat(parts)
        }

        // Plates from Bambu/Orca/QIDI model_settings.config; otherwise one plate.
        val names = HashMap<String, String>()
        val plateMap = bytes("Metadata/model_settings.config")?.let { parsePlates(it, names) }.orEmpty()
        val printable = root.items.filter { it.printable }
        val plates = if (plateMap.isEmpty()) {
            listOf(1 to printable.map { it.objectId })
        } else {
            plateMap.entries.sortedBy { it.key }.map { (n, ids) -> n to ids }
        }
        val result = plates.mapNotNull { (n, ids) ->
            val items = printable.filter { it.objectId in ids }
            if (items.isEmpty()) return@mapNotNull null
            val meshes = items.map { dropToBed(flatten(rootPath, it.objectId, it.transform)) }
            val objNames = items.map { names[it.objectId] ?: root.objects[it.objectId]?.name ?: "Object ${it.objectId}" }
            Plate(n, "", objNames, concat(meshes), bytes("Metadata/plate_$n.png"))
        }
        if (result.isEmpty()) throw IllegalArgumentException("No printable objects in this 3MF")
        val settings = bytes("Metadata/project_settings.config")?.let {
            runCatching { Json.parseToJsonElement(String(it)).jsonObject }.getOrNull()
        }
        ModelFile(displayName, result, settings)
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

    /** plater_id -> object ids, and object names, from model_settings.config. */
    private fun parsePlates(data: ByteArray, names: MutableMap<String, String>): Map<Int, List<String>> {
        val plates = LinkedHashMap<Int, MutableList<String>>()
        val handler = object : DefaultHandler() {
            var inPlate = false
            var inInstance = false
            var plate = 0
            var objectId: String? = null
            var objectDepth = 0
            override fun startElement(uri: String?, local: String?, qName: String, a: Attributes) {
                when (qName) {
                    "object" -> { objectId = a.getValue("id"); objectDepth = 0 }
                    "part" -> objectDepth++
                    "plate" -> { inPlate = true; plate = 0 }
                    "model_instance" -> inInstance = true
                    "metadata" -> {
                        val key = a.getValue("key"); val value = a.getValue("value") ?: ""
                        when {
                            inPlate && !inInstance && key == "plater_id" -> plate = value.toIntOrNull() ?: 0
                            inPlate && inInstance && key == "object_id" -> plates.getOrPut(plate) { mutableListOf() } += value
                            !inPlate && objectId != null && objectDepth == 0 && key == "name" -> names[objectId!!] = value
                        }
                    }
                }
            }
            override fun endElement(uri: String?, local: String?, qName: String) {
                when (qName) {
                    "plate" -> inPlate = false
                    "model_instance" -> inInstance = false
                    "part" -> objectDepth--
                    "object" -> objectId = null
                }
            }
        }
        newParser().parse(ByteArrayInputStream(data), handler)
        return plates
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
