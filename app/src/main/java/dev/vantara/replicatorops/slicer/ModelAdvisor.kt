package dev.vantara.replicatorops.slicer

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The ship's computer for the Slicer: looks over a plate's mesh on the phone (no internet)
 * and suggests changes to make the print stronger or more detailed. Each suggestion says
 * why, and changes either the slice settings or the mesh itself.
 */
object ModelAdvisor {

    enum class Purpose(val label: String) { DISPLAY("Display"), EVERYDAY("Everyday"), STRONG("Strong") }

    /** What the computer found out about the mesh. */
    data class Analysis(
        val sizeX: Float, val sizeY: Float, val sizeZ: Float,
        val triangles: Int,
        /** Downward-facing area steeper than 45° that isn't on the bed (mm²). */
        val overhangArea: Double,
        /** Area touching the bed (mm²). */
        val bedContact: Double,
        /** Area of the bounding box on the bed (mm²). */
        val footprint: Double,
        /** Thinnest typical wall (5th percentile of inward thickness), mm. Null if it couldn't be measured. */
        val thinWall: Double?,
        /** Flat, upward-facing area above the bed (mm²): tops that show. */
        val flatTop: Double,
        /** Share of the surface that is gently curved but drawn with coarse facets (0–1). */
        val faceted: Double,
        /** Best way up, if it's clearly better than the current one. */
        val betterOrientation: Orientation?,
    )

    enum class Orientation(val label: String) {
        X_90("Tip forward"), X_270("Tip backward"), Y_90("Roll right"), Y_270("Roll left"), X_180("Upside down")
    }

    sealed interface Change {
        data class Settings(val apply: (SliceSettings) -> SliceSettings) : Change
        data class Rotate(val orientation: Orientation) : Change
        data object Smooth : Change
    }

    data class Suggestion(val id: String, val title: String, val why: String, val change: Change)

    // ---- Analysis ---------------------------------------------------------------------

    fun analyse(tris: FloatArray): Analysis {
        val n = tris.size / 9
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until n * 3) {
            val x = tris[i * 3]; val y = tris[i * 3 + 1]; val z = tris[i * 3 + 2]
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y); minZ = min(minZ, z); maxZ = max(maxZ, z)
        }
        val s = surfaceStats(tris, minZ)
        val current = s.overhang
        // Try the other ways up: suggest one only if it clearly cuts overhangs and keeps a good base.
        var best: Orientation? = null
        var bestScore = score(s.overhang, s.contact)
        if (n in 1..400_000) for (o in Orientation.entries) {
            val r = rotate(tris, o)
            val rs = surfaceStats(r, r.minZ())
            val sc = score(rs.overhang, rs.contact)
            if (sc < bestScore && rs.overhang < current * 0.6 - 50 && rs.contact >= min(s.contact, 100.0) * 0.8) {
                best = o; bestScore = sc
            }
        }
        return Analysis(
            sizeX = maxX - minX, sizeY = maxY - minY, sizeZ = maxZ - minZ,
            triangles = n,
            overhangArea = s.overhang, bedContact = s.contact,
            footprint = ((maxX - minX) * (maxY - minY)).toDouble(),
            thinWall = thickness(tris),
            flatTop = s.flatTop,
            faceted = faceted(tris),
            betterOrientation = best,
        )
    }

    private fun score(overhang: Double, contact: Double) = overhang - 0.5 * contact

    private class Surface(val overhang: Double, val contact: Double, val flatTop: Double)

    private fun surfaceStats(t: FloatArray, minZ: Float): Surface {
        var overhang = 0.0; var contact = 0.0; var flatTop = 0.0
        val steep = cos(Math.toRadians(45.0))
        for (i in 0 until t.size / 9) {
            val b = i * 9
            val (nx, ny, nz, area) = normal(t, b)
            if (area <= 0) continue
            val zMax = max(t[b + 2], max(t[b + 5], t[b + 8]))
            val zMin = min(t[b + 2], min(t[b + 5], t[b + 8]))
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            val cz = nz / len
            if (cz < -0.99 && zMax - minZ < 0.05) contact += area
            else if (cz < -steep && zMin - minZ > 0.3) overhang += area
            if (cz > 0.99 && zMin - minZ > 0.5) flatTop += area
        }
        return Surface(overhang, contact, flatTop)
    }

    /** Normal (not unit) and area of triangle starting at [b]. */
    private fun normal(t: FloatArray, b: Int): DoubleArray {
        val ux = (t[b + 3] - t[b]).toDouble(); val uy = (t[b + 4] - t[b + 1]).toDouble(); val uz = (t[b + 5] - t[b + 2]).toDouble()
        val vx = (t[b + 6] - t[b]).toDouble(); val vy = (t[b + 7] - t[b + 1]).toDouble(); val vz = (t[b + 8] - t[b + 2]).toDouble()
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        return doubleArrayOf(nx, ny, nz, sqrt(nx * nx + ny * ny + nz * nz) / 2)
    }


    private fun FloatArray.minZ(): Float { var m = Float.MAX_VALUE; var i = 2; while (i < size) { m = min(m, this[i]); i += 3 }; return m }

    /**
     * Wall thickness: from points on the surface, shoot a ray inwards and see how far it goes
     * before leaving the part. A uniform grid keeps it quick on a phone.
     */
    fun thickness(t: FloatArray, samples: Int = 1500): Double? {
        val n = t.size / 9
        if (n < 4) return null
        val grid = Grid(t)
        val areas = DoubleArray(n) { normal(t, it * 9)[3] }
        val total = areas.sum().takeIf { it > 0 } ?: return null
        val rnd = java.util.Random(42)
        val hits = ArrayList<Double>()
        for (i in 0 until n) {
            // Points spread over the surface by area (at least one per triangle on small meshes).
            var k = (areas[i] / total * samples).roundToInt()
            if (k == 0 && n < samples && areas[i] > 1e-6) k = 1
            if (k == 0) continue
            val b = i * 9
            val nv = normal(t, b)
            val len = nv[3] * 2
            val dx = -nv[0] / len; val dy = -nv[1] / len; val dz = -nv[2] / len
            repeat(min(k, 50)) {
                var u = rnd.nextDouble(); var v = rnd.nextDouble()
                if (u + v > 1) { u = 1 - u; v = 1 - v }
                val w = 1 - u - v
                val ox = w * t[b] + u * t[b + 3] + v * t[b + 6] + dx * 1e-3
                val oy = w * t[b + 1] + u * t[b + 4] + v * t[b + 7] + dy * 1e-3
                val oz = w * t[b + 2] + u * t[b + 5] + v * t[b + 8] + dz * 1e-3
                grid.nearestHit(ox, oy, oz, dx, dy, dz, 20.0, i)?.let { hits += it }
            }
        }
        if (hits.size < 10) return null
        hits.sort()
        return hits[(hits.size * 0.05).toInt()]
    }

    /** Share of the surface where neighbouring facets meet at 8–35°: curves drawn coarsely. */
    fun faceted(t: FloatArray): Double {
        val n = t.size / 9
        if (n < 8 || n > 200_000) return 0.0
        val mesh = Indexed(t)
        var coarse = 0.0; var total = 0.0
        for ((_, faces) in mesh.edgeFaces) {
            if (faces.size != 2) continue
            val a = mesh.faceNormal(faces[0]); val b = mesh.faceNormal(faces[1])
            val ang = Math.toDegrees(Math.acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)))
            if (ang < 1.0) continue  // flat: the diagonal of a quad, not a real edge
            total += 1
            if (ang in 8.0..35.0) coarse += 1
        }
        return if (total == 0.0) 0.0 else coarse / total
    }

    // ---- Suggestions --------------------------------------------------------------------

    fun suggest(a: Analysis, s: SliceSettings, purpose: Purpose): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        val tall = a.sizeZ > 2.5 * sqrt(max(a.footprint, 1.0)) / 2

        a.betterOrientation?.let { o ->
            out += Suggestion("orient", "Turn it: ${o.label.lowercase()}",
                "Fewer overhangs (about ${a.overhangArea.roundToInt()} mm² now): less support, a cleaner surface, and layers that take the load better.",
                Change.Rotate(o))
        }
        if (a.overhangArea > 150 && !s.supports && a.betterOrientation == null) {
            out += Suggestion("supports", "Add organic tree supports",
                "About ${a.overhangArea.roundToInt()} mm² hangs out over thin air at more than 45° and would sag without support.",
                Change.Settings { it.copy(supports = true, treeSupports = true) })
        }
        if ((a.bedContact < 150 || (tall && a.bedContact < a.footprint * 0.25)) && s.brim < 3) {
            out += Suggestion("brim", "Add a 5 mm brim",
                "Only ${a.bedContact.roundToInt()} mm² touches the bed" + (if (tall) " and it's tall for its base" else "") + ", so it could lift or get knocked over.",
                Change.Settings { it.copy(brim = 5.0) })
        }
        a.thinWall?.let { w ->
            if (w < 1.0 && s.xyOffset < 0.05) {
                out += Suggestion("thicken", "Thicken thin parts",
                    "Some walls are only about %.1f mm thick. Grows the outline by 0.1 mm each side while keeping holes the same size.".format(w),
                    Change.Settings { it.copy(xyOffset = 0.1) })
            }
            if (w < 2.0 && s.walls < 3 && purpose == Purpose.EVERYDAY) {
                out += Suggestion("walls", "Use 3 walls",
                    "Thin sections (about %.1f mm) are almost all wall, so an extra wall makes them solid and much stronger.".format(w),
                    Change.Settings { it.copy(walls = 3) })
            }
        }
        if (purpose == Purpose.STRONG) {
            if (s.walls < 4) out += Suggestion("strong-walls", "4 walls, 6 top and bottom layers",
                "Most of a part's strength comes from its walls and skins, not its infill.",
                Change.Settings { it.copy(walls = 4, topLayers = max(it.topLayers, 6), bottomLayers = max(it.bottomLayers, 5)) })
            if (s.infill < 35 || s.infillPattern !in listOf("gyroid", "cubic")) out += Suggestion("strong-infill", "35% gyroid infill",
                "Gyroid is equally strong in every direction and doesn't crack along one line.",
                Change.Settings { it.copy(infill = max(it.infill, 35), infillPattern = "gyroid") })
            if (s.layerHeight > 0.2) out += Suggestion("strong-layers", "0.20 mm layers",
                "Thinner layers bond better than thick ones.",
                Change.Settings { it.copy(layerHeight = 0.2) })
        } else if (purpose == Purpose.EVERYDAY) {
            if (s.infill < 15) out += Suggestion("everyday-infill", "15% infill",
                "Below 15% the tops start to sag and the part feels hollow.",
                Change.Settings { it.copy(infill = 15) })
        }
        if (purpose == Purpose.DISPLAY) {
            if (s.layerHeight > 0.12) out += Suggestion("detail-layers", "Finer layers where it counts",
                "Adaptive layers go as fine as about 0.1 mm on curves and slopes and stay thicker on straight walls, so detail improves without doubling the print time.",
                Change.Settings { it.copy(adaptiveLayers = true) })
            if (s.seam != "back") out += Suggestion("seam", "Hide the seam at the back",
                "Every layer starts and ends somewhere. Putting that point at the back keeps the front clean.",
                Change.Settings { it.copy(seam = "back") })
            if (s.infill > 15) out += Suggestion("display-infill", "10% lightning infill",
                "A display piece doesn't need strength inside. Lightning only holds up the top, so it's quicker and uses less filament.",
                Change.Settings { it.copy(infill = 10, infillPattern = "lightning") })
        }
        if (a.flatTop > 400 && !s.ironing && purpose == Purpose.DISPLAY) {
            out += Suggestion("ironing", "Iron the flat tops",
                "About ${a.flatTop.roundToInt()} mm² of flat top shows. Ironing runs the hot nozzle over it again for a smooth, glossy finish.",
                Change.Settings { it.copy(ironing = true) })
        }
        if (purpose == Purpose.DISPLAY && a.faceted > 0.3 && a.triangles < 150_000) {
            out += Suggestion("smooth", "Smooth the curves",
                "Curved surfaces are drawn with coarse flat facets (about ${(a.faceted * 100).roundToInt()}% of edges), which print as visible flat bands. " +
                    "Smoothing rounds them off and keeps sharp edges sharp.",
                Change.Smooth)
        }
        if (purpose == Purpose.DISPLAY && !s.fuzzySkin && a.faceted <= 0.3) {
            out += Suggestion("fuzzy", "Textured finish (fuzzy skin)",
                "Gives the outside a fine, matte texture that hides layer lines. Leave it off for parts that have to fit something.",
                Change.Settings { it.copy(fuzzySkin = true) })
        }
        return out
    }

    // ---- Mesh changes ---------------------------------------------------------------------

    /** Rotates the mesh and puts it back on the bed in the same place. */
    fun rotate(t: FloatArray, o: Orientation): FloatArray {
        val out = FloatArray(t.size)
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        val count = t.size / 3
        for (i in 0 until count) { cx += t[i * 3]; cy += t[i * 3 + 1]; cz += t[i * 3 + 2] }
        cx /= count; cy /= count; cz /= count
        for (i in 0 until count) {
            val x = t[i * 3] - cx; val y = t[i * 3 + 1] - cy; val z = t[i * 3 + 2] - cz
            val (nx, ny, nz) = when (o) {
                Orientation.X_90 -> Triple(x, -z, y)
                Orientation.X_270 -> Triple(x, z, -y)
                Orientation.X_180 -> Triple(x, -y, -z)
                Orientation.Y_90 -> Triple(z, y, -x)
                Orientation.Y_270 -> Triple(-z, y, x)
            }
            out[i * 3] = (nx + cx).toFloat(); out[i * 3 + 1] = (ny + cy).toFloat(); out[i * 3 + 2] = nz.toFloat()
        }
        val mz = out.minZ()
        var i = 2
        while (i < out.size) { out[i] -= mz; i += 3 }
        return out
    }

    /**
     * One step of Loop subdivision with sharp creases: each triangle becomes four, curved
     * areas are smoothed, and edges sharper than [creaseDeg] stay sharp. Stays on the bed.
     */
    fun smooth(t: FloatArray, creaseDeg: Double = 35.0, dropToBed: Boolean = true): FloatArray {
        val m = Indexed(t)
        val vCount = m.verts.size / 3
        val creaseCos = cos(Math.toRadians(creaseDeg))
        // Classify edges.
        val crease = HashMap<Long, Boolean>()
        for ((key, faces) in m.edgeFaces) {
            crease[key] = if (faces.size != 2) true else {
                val a = m.faceNormal(faces[0]); val b = m.faceNormal(faces[1])
                (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) < creaseCos
            }
        }
        // Neighbours of each vertex, and crease neighbours.
        val nbrs = Array(vCount) { HashSet<Int>() }
        val creaseNbrs = Array(vCount) { HashSet<Int>() }
        for ((key, isCrease) in crease) {
            val a = (key shr 32).toInt(); val b = (key and 0xffffffffL).toInt()
            nbrs[a] += b; nbrs[b] += a
            if (isCrease) { creaseNbrs[a] += b; creaseNbrs[b] += a }
        }
        // New positions for the old vertices.
        val newV = DoubleArray(vCount * 3)
        for (v in 0 until vCount) {
            val c = creaseNbrs[v]
            when {
                c.size > 2 -> for (k in 0..2) newV[v * 3 + k] = m.verts[v * 3 + k]
                c.size == 2 -> {
                    val (a, b) = c.toList()
                    for (k in 0..2) newV[v * 3 + k] = 0.75 * m.verts[v * 3 + k] + 0.125 * (m.verts[a * 3 + k] + m.verts[b * 3 + k])
                }
                else -> {
                    val nb = nbrs[v]; val k0 = nb.size
                    if (k0 < 3) { for (k in 0..2) newV[v * 3 + k] = m.verts[v * 3 + k]; continue }
                    val beta = if (k0 == 3) 3.0 / 16 else 3.0 / (8.0 * k0)
                    for (k in 0..2) {
                        var sum = 0.0
                        for (u in nb) sum += m.verts[u * 3 + k]
                        newV[v * 3 + k] = (1 - k0 * beta) * m.verts[v * 3 + k] + beta * sum
                    }
                }
            }
        }
        // Edge points.
        val edgePoint = HashMap<Long, DoubleArray>()
        for ((key, faces) in m.edgeFaces) {
            val a = (key shr 32).toInt(); val b = (key and 0xffffffffL).toInt()
            val p = DoubleArray(3)
            if (crease[key] == true) {
                for (k in 0..2) p[k] = 0.5 * (m.verts[a * 3 + k] + m.verts[b * 3 + k])
            } else {
                val c = m.opposite(faces[0], a, b); val d = m.opposite(faces[1], a, b)
                for (k in 0..2) p[k] = 0.375 * (m.verts[a * 3 + k] + m.verts[b * 3 + k]) + 0.125 * (m.verts[c * 3 + k] + m.verts[d * 3 + k])
            }
            edgePoint[key] = p
        }
        val out = FloatArray(m.faces.size / 3 * 4 * 9)
        var o = 0
        fun put(p: DoubleArray, i: Int = 0) { out[o++] = p[i].toFloat(); out[o++] = p[i + 1].toFloat(); out[o++] = p[i + 2].toFloat() }
        fun vert(v: Int) { put(newV, v * 3) }
        for (f in 0 until m.faces.size / 3) {
            val a = m.faces[f * 3]; val b = m.faces[f * 3 + 1]; val c = m.faces[f * 3 + 2]
            val ab = edgePoint[Indexed.key(a, b)]!!; val bc = edgePoint[Indexed.key(b, c)]!!; val ca = edgePoint[Indexed.key(c, a)]!!
            vert(a); put(ab); put(ca)
            vert(b); put(bc); put(ab)
            vert(c); put(ca); put(bc)
            put(ab); put(bc); put(ca)
        }
        if (!dropToBed) return out
        val mz = out.minZ()
        var i = 2
        while (i < out.size) { out[i] -= mz; i += 3 }
        return out
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /** Triangle soup welded into shared vertices, with the faces around each edge. */
    private class Indexed(t: FloatArray) {
        val verts: DoubleArray
        val faces: IntArray
        val edgeFaces = HashMap<Long, MutableList<Int>>()

        init {
            val index = HashMap<Triple<Long, Long, Long>, Int>()
            val vs = ArrayList<Double>()
            val n = t.size / 9
            faces = IntArray(n * 3)
            fun q(x: Float) = Math.round(x * 1e4)
            for (i in 0 until n * 3) {
                val x = t[i * 3]; val y = t[i * 3 + 1]; val z = t[i * 3 + 2]
                faces[i] = index.getOrPut(Triple(q(x), q(y), q(z))) {
                    vs += x.toDouble(); vs += y.toDouble(); vs += z.toDouble(); vs.size / 3 - 1
                }
            }
            verts = vs.toDoubleArray()
            for (f in 0 until n) {
                val a = faces[f * 3]; val b = faces[f * 3 + 1]; val c = faces[f * 3 + 2]
                for ((p, r) in listOf(a to b, b to c, c to a)) edgeFaces.getOrPut(key(p, r)) { ArrayList(2) } += f
            }
        }

        fun faceNormal(f: Int): DoubleArray {
            val a = faces[f * 3]; val b = faces[f * 3 + 1]; val c = faces[f * 3 + 2]
            val ux = verts[b * 3] - verts[a * 3]; val uy = verts[b * 3 + 1] - verts[a * 3 + 1]; val uz = verts[b * 3 + 2] - verts[a * 3 + 2]
            val vx = verts[c * 3] - verts[a * 3]; val vy = verts[c * 3 + 1] - verts[a * 3 + 1]; val vz = verts[c * 3 + 2] - verts[a * 3 + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0 } ?: 1.0
            return doubleArrayOf(nx / l, ny / l, nz / l)
        }

        fun opposite(f: Int, a: Int, b: Int): Int {
            for (k in 0..2) { val v = faces[f * 3 + k]; if (v != a && v != b) return v }
            return a
        }

        companion object {
            fun key(a: Int, b: Int): Long = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
        }
    }

    /** Uniform grid of triangles for quick ray casts. */
    private class Grid(private val t: FloatArray) {
        private val n = t.size / 9
        private val minX: Float; private val minY: Float; private val minZ: Float
        private val cell: Float
        private val nx: Int; private val ny: Int; private val nz: Int
        private val cells: Array<IntArray?>

        init {
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
            for (i in 0 until n * 3) {
                x0 = min(x0, t[i * 3]); x1 = max(x1, t[i * 3]); y0 = min(y0, t[i * 3 + 1]); y1 = max(y1, t[i * 3 + 1]); z0 = min(z0, t[i * 3 + 2]); z1 = max(z1, t[i * 3 + 2])
            }
            minX = x0; minY = y0; minZ = z0
            val vol = max((x1 - x0) * (y1 - y0) * (z1 - z0), 1f)
            cell = max(Math.cbrt(vol / max(n, 1).toDouble() * 2).toFloat(), 0.5f)
            nx = ((x1 - x0) / cell).toInt() + 1; ny = ((y1 - y0) / cell).toInt() + 1; nz = ((z1 - z0) / cell).toInt() + 1
            val lists = HashMap<Int, ArrayList<Int>>()
            for (f in 0 until n) {
                val b = f * 9
                val cx0 = ((min(t[b], min(t[b + 3], t[b + 6])) - minX) / cell).toInt()
                val cx1 = ((max(t[b], max(t[b + 3], t[b + 6])) - minX) / cell).toInt()
                val cy0 = ((min(t[b + 1], min(t[b + 4], t[b + 7])) - minY) / cell).toInt()
                val cy1 = ((max(t[b + 1], max(t[b + 4], t[b + 7])) - minY) / cell).toInt()
                val cz0 = ((min(t[b + 2], min(t[b + 5], t[b + 8])) - minZ) / cell).toInt()
                val cz1 = ((max(t[b + 2], max(t[b + 5], t[b + 8])) - minZ) / cell).toInt()
                for (x in cx0..cx1) for (y in cy0..cy1) for (z in cz0..cz1) lists.getOrPut(idx(x, y, z)) { ArrayList() } += f
            }
            cells = arrayOfNulls(nx * ny * nz)
            for ((k, v) in lists) cells[k] = v.toIntArray()
        }

        private fun idx(x: Int, y: Int, z: Int) = (z * ny + y) * nx + x

        /** Distance to the first triangle hit along the ray (not [skip]), within [maxDist]. */
        fun nearestHit(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double, maxDist: Double, skip: Int): Double? {
            var best = maxDist
            var found = false
            val seen = HashSet<Int>()
            val stepLen = cell * 0.5
            var d = 0.0
            while (d <= best + cell) {
                val px = ox + dx * d; val py = oy + dy * d; val pz = oz + dz * d
                val cx = ((px - minX) / cell).toInt(); val cy = ((py - minY) / cell).toInt(); val cz = ((pz - minZ) / cell).toInt()
                if (cx < 0 || cy < 0 || cz < 0 || cx >= nx || cy >= ny || cz >= nz) break
                cells[idx(cx, cy, cz)]?.forEach { f ->
                    if (f != skip && seen.add(f)) {
                        val h = hit(f, ox, oy, oz, dx, dy, dz)
                        if (h != null && h > 1e-3 && h < best) { best = h; found = true }
                    }
                }
                d += stepLen
            }
            return if (found) best else null
        }

        /** Möller–Trumbore. */
        private fun hit(f: Int, ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Double? {
            val b = f * 9
            val e1x = (t[b + 3] - t[b]).toDouble(); val e1y = (t[b + 4] - t[b + 1]).toDouble(); val e1z = (t[b + 5] - t[b + 2]).toDouble()
            val e2x = (t[b + 6] - t[b]).toDouble(); val e2y = (t[b + 7] - t[b + 1]).toDouble(); val e2z = (t[b + 8] - t[b + 2]).toDouble()
            val px = dy * e2z - dz * e2y; val py = dz * e2x - dx * e2z; val pz = dx * e2y - dy * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (abs(det) < 1e-12) return null
            val inv = 1 / det
            val sx = ox - t[b]; val sy = oy - t[b + 1]; val sz = oz - t[b + 2]
            val u = (sx * px + sy * py + sz * pz) * inv
            if (u < 0 || u > 1) return null
            val qx = sy * e1z - sz * e1y; val qy = sz * e1x - sx * e1z; val qz = sx * e1y - sy * e1x
            val v = (dx * qx + dy * qy + dz * qz) * inv
            if (v < 0 || u + v > 1) return null
            return (e2x * qx + e2y * qy + e2z * qz) * inv
        }
    }
}
