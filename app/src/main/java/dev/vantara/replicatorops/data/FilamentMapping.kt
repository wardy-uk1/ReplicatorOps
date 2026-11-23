package dev.vantara.replicatorops.data

/** One filament the file needs, and the Box slot chosen for it. */
data class ToolNeed(val tool: Int, val type: String?, val color: Long?)

/**
 * Filament mapping before a print, like QIDI Studio's: each tool (filament) in the
 * file is fed from a Box slot. The printer keeps this in save_variables as
 * value_tN = 'slotM'; a tool pointing at an empty slot stops the print with
 * "Please load the filament to 1D first".
 */
object FilamentMapping {

    /** Which tools the file uses, with their type and colour. */
    fun needs(meta: FileMeta?): List<ToolNeed> {
        meta ?: return emptyList()
        val tools = meta.tools.ifEmpty {
            val n = maxOf(meta.filamentTypes.size, meta.filamentColors.size)
            if (n <= 1) listOf(0) else (0 until n).toList()
        }.distinct().sorted()
        return tools.map { t -> ToolNeed(t, meta.filamentTypes.getOrNull(t), meta.filamentColors.getOrNull(t)) }
    }

    /**
     * Suggested slot for each tool: keep the printer's current choice if that slot is
     * loaded with the same type; otherwise the loaded slot of the same type with the
     * closest colour; otherwise the closest colour of any loaded slot.
     */
    fun suggest(needs: List<ToolNeed>, slots: List<BoxSlot>, current: Map<Int, Int>): Map<Int, Int> {
        val loaded = slots.filter { it.loaded }
        if (loaded.isEmpty()) return emptyMap()
        return needs.associate { n ->
            val cur = current[n.tool]?.let { c -> loaded.firstOrNull { it.index == c } }
            val pick = cur?.takeIf { sameType(it.material, n.type) }
                ?: loaded.filter { sameType(it.material, n.type) }.minByOrNull { distance(it.color, n.color) }
                ?: loaded.minByOrNull { distance(it.color, n.color) }!!
            n.tool to pick.index
        }
    }

    /** Problems to show before starting, or empty if the mapping looks fine. */
    fun problems(needs: List<ToolNeed>, slots: List<BoxSlot>, map: Map<Int, Int>): List<String> = needs.mapNotNull { n ->
        val slot = map[n.tool]?.let { s -> slots.firstOrNull { it.index == s } }
        when {
            slot == null -> "Filament ${n.tool + 1} has no slot chosen"
            !slot.loaded -> "Slot ${slot.index + 1} is empty"
            !sameType(slot.material, n.type) -> "Filament ${n.tool + 1} is ${n.type}, but slot ${slot.index + 1} has ${slot.material ?: "unknown filament"}"
            else -> null
        }
    }

    /** G-code that saves the choice where the Box firmware reads it. */
    fun gcode(map: Map<Int, Int>): String =
        map.entries.sortedBy { it.key }.joinToString("\n") { (t, s) -> "SAVE_VARIABLE VARIABLE=value_t$t VALUE=\"'slot$s'\"" }

    /** Unknown types match anything; "PLA Silk" matches "Silk PLA" and "PLA". */
    fun sameType(slot: String?, file: String?): Boolean {
        if (slot.isNullOrBlank() || file.isNullOrBlank()) return true
        val finishes = setOf("SILK", "MATTE", "BASIC", "HF", "PLUS", "PRO", "LITE")
        fun family(x: String): String {
            val words = x.uppercase().replace("+", " PLUS").split(' ', '-', '_').filter { it.isNotBlank() }
            return words.firstOrNull { it !in finishes } ?: words.firstOrNull().orEmpty()
        }
        return family(slot) == family(file)
    }

    private fun distance(a: Long?, b: Long?): Int {
        if (a == null || b == null) return 1_000
        fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF).toInt()
        val dr = ch(a, 16) - ch(b, 16); val dg = ch(a, 8) - ch(b, 8); val db = ch(a, 0) - ch(b, 0)
        return dr * dr + dg * dg + db * db
    }
}
