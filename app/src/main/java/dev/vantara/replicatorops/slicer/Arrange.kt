package dev.vantara.replicatorops.slicer

/** Laying objects out on the bed: auto-arrange, finding room for a copy, keeping things on the bed. */
object Arrange {
    const val GAP = 6f

    /**
     * Packs the objects in rows (tallest footprint first) and centres the group on the bed.
     * Objects keep their turn and size; only their position changes.
     */
    fun auto(objects: List<PlacedObject>, bedW: Float = 270f, bedD: Float = 270f, gap: Float = GAP): List<PlacedObject> {
        if (objects.isEmpty()) return objects
        data class Box(val o: PlacedObject, val w: Float, val d: Float, val cx: Float, val cy: Float)
        val boxes = objects.map { o ->
            val b = o.bounds()
            // Offset of the object's centre within its footprint (hulls aren't symmetric).
            Box(o, b[2] - b[0], b[3] - b[1], o.x - (b[0] + b[2]) / 2, o.y - (b[1] + b[3]) / 2)
        }.sortedByDescending { it.d }
        val placed = ArrayList<Pair<Box, Pair<Float, Float>>>()  // box -> footprint centre
        var x = 0f; var y = 0f; var rowD = 0f
        for (b in boxes) {
            if (x > 0f && x + b.w > bedW) { x = 0f; y += rowD + gap; rowD = 0f }
            placed += b to ((x + b.w / 2) to (y + b.d / 2))
            x += b.w + gap
            rowD = maxOf(rowD, b.d)
        }
        val usedW = placed.maxOf { (b, c) -> c.first + b.w / 2 }
        val usedD = placed.maxOf { (b, c) -> c.second + b.d / 2 }
        val ox = (bedW - usedW) / 2; val oy = (bedD - usedD) / 2
        val moved = placed.associate { (b, c) -> b.o.id to b.o.copy(x = c.first + ox + b.cx, y = c.second + oy + b.cy) }
        return objects.map { moved[it.id] ?: it }
    }

    /** A copy of [o] placed where it doesn't overlap anything, or null if there's no room. */
    fun findRoom(objects: List<PlacedObject>, o: PlacedObject, bedW: Float = 270f, bedD: Float = 270f, gap: Float = GAP): PlacedObject? {
        val b = o.bounds()
        val w = b[2] - b[0]; val d = b[3] - b[1]
        val cx = o.x - (b[0] + b[2]) / 2; val cy = o.y - (b[1] + b[3]) / 2
        val taken = objects.map { it.bounds() }
        var bestDist = Float.MAX_VALUE
        var best: Pair<Float, Float>? = null
        var y = 0f
        while (y + d <= bedD) {
            var x = 0f
            while (x + w <= bedW) {
                val free = taken.none { t -> x < t[2] + gap && x + w + gap > t[0] && y < t[3] + gap && y + d + gap > t[1] }
                if (free) {
                    val dist = (x + w / 2 - o.x) * (x + w / 2 - o.x) + (y + d / 2 - o.y) * (y + d / 2 - o.y)
                    if (dist < bestDist) { bestDist = dist; best = (x + w / 2) to (y + d / 2) }
                }
                x += 2f
            }
            y += 2f
        }
        return best?.let { (fx, fy) -> o.copy(x = fx + cx, y = fy + cy) }
    }

    /** Moves [o] (by whole millimetres) so its footprint stays on the bed. */
    fun keepOnBed(o: PlacedObject, bedW: Float = 270f, bedD: Float = 270f): PlacedObject {
        val b = o.bounds()
        var dx = 0f; var dy = 0f
        if (b[2] - b[0] <= bedW) { if (b[0] < 0) dx = -b[0] else if (b[2] > bedW) dx = bedW - b[2] }
        if (b[3] - b[1] <= bedD) { if (b[1] < 0) dy = -b[1] else if (b[3] > bedD) dy = bedD - b[3] }
        return if (dx == 0f && dy == 0f) o else o.copy(x = o.x + dx, y = o.y + dy)
    }

    /** Centres the whole group on the bed without changing the layout. */
    fun centre(objects: List<PlacedObject>, bedW: Float = 270f, bedD: Float = 270f): List<PlacedObject> {
        if (objects.isEmpty()) return objects
        val b = objects.map { it.bounds() }
        val dx = bedW / 2 - (b.minOf { it[0] } + b.maxOf { it[2] }) / 2
        val dy = bedD / 2 - (b.minOf { it[1] } + b.maxOf { it[3] }) / 2
        return objects.map { it.copy(x = it.x + dx, y = it.y + dy) }
    }
}
