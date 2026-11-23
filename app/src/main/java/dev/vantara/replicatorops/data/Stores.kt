package dev.vantara.replicatorops.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.PI

/** A spool in Replicator stores (kept on the phone). */
@Serializable
data class Spool(
    val id: Long,
    val material: String,
    val colourName: String,
    val brand: String,
    /** ARGB colour. */
    val color: Long,
    val totalGrams: Int,
    val remainingGrams: Double,
    /** QIDI Box slot 0-3 this spool is loaded in, or null. */
    val slot: Int? = null,
    /** What the spool cost, in the phone's currency, or null if not entered. */
    val price: Double? = null,
) {
    val percent: Int get() = if (totalGrams <= 0) 0 else ((remainingGrams / totalGrams) * 100).toInt().coerceIn(0, 100)
}

/** What the user says is in a Box slot (overrides what the printer reports). */
@Serializable
data class SlotInfo(
    val material: String,
    val color: Long,
    val spoolId: Long? = null,
)

/** Phone-side storage for spools and Box slot assignments. */
class StoresRepo(context: Context) {
    private val sp = context.getSharedPreferences("replicator_stores", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun loadSpools(): List<Spool> =
        sp.getString("spools", null)?.let { runCatching { json.decodeFromString<List<Spool>>(it) }.getOrNull() } ?: emptyList()

    fun saveSpools(list: List<Spool>) = sp.edit().putString("spools", json.encodeToString(list)).apply()

    fun loadSlots(): Map<Int, SlotInfo> =
        sp.getString("slots", null)?.let { runCatching { json.decodeFromString<Map<Int, SlotInfo>>(it) }.getOrNull() } ?: emptyMap()

    fun saveSlots(map: Map<Int, SlotInfo>) = sp.edit().putString("slots", json.encodeToString(map)).apply()

    /** Filament price used for print costs when no spool of that type has a price. */
    var defaultPricePerKg: Double
        get() = sp.getString("price_per_kg", null)?.toDoubleOrNull() ?: 20.0
        set(v) = sp.edit().putString("price_per_kg", v.toString()).apply()

    var activeSpoolId: Long?
        get() = sp.getLong("active_spool", -1L).takeIf { it >= 0 }
        set(v) = sp.edit().putLong("active_spool", v ?: -1L).apply()

    companion object {
        val MATERIALS = listOf("PLA", "Silk PLA", "PLA+", "PETG", "ABS", "ASA", "TPU", "PA", "PC", "Other")

        /** Quick colour picks: name to ARGB. */
        val COLOURS = listOf(
            "Black" to 0xFF1E1E22, "White" to 0xFFF2F2F2, "Grey" to 0xFF8A8F98, "Red" to 0xFFD7263D,
            "Orange" to 0xFFFF8C1A, "Yellow" to 0xFFFFD23F, "Green" to 0xFF2BA84A, "Teal" to 0xFF2EC4B6,
            "Blue" to 0xFF3A86FF, "Purple" to 0xFF8E44AD, "Pink" to 0xFFFF6FB5, "Clear" to 0xFFCFE8EF,
        )

        private fun density(material: String): Double = when {
            material.contains("PETG", true) -> 1.27
            material.contains("ABS", true) -> 1.04
            material.contains("ASA", true) -> 1.07
            material.contains("TPU", true) -> 1.21
            material.contains("PC", true) -> 1.20
            material.contains("PA", true) -> 1.14
            else -> 1.24 // PLA
        }

        /**
         * Cost of one gram of [material]: the average price per gram of priced spools of
         * that type, otherwise [defaultPerKg] / 1000.
         */
        fun costPerGram(material: String?, spools: List<Spool>, defaultPerKg: Double): Double {
            val m = material?.trim().orEmpty()
            val priced = spools.filter { it.price != null && it.price > 0 && it.totalGrams > 0 }
            val same = if (m.isEmpty()) emptyList() else priced.filter { sameMaterial(it.material, m) }
            return if (same.isNotEmpty()) same.map { it.price!! / it.totalGrams }.average() else defaultPerKg / 1000.0
        }

        /** "Silk PLA" matches "PLA Silk"; "PLA" doesn't match "PLA+". */
        private fun sameMaterial(a: String, b: String): Boolean {
            fun words(x: String) = x.uppercase().split(' ', '-', '_').filter { it.isNotBlank() }.toSet()
            return words(a) == words(b)
        }

        /** Filament cost of a print. */
        fun printCost(filamentMm: Double, material: String?, spools: List<Spool>, defaultPerKg: Double): Double =
            gramsFor(filamentMm, material ?: "PLA") * costPerGram(material, spools, defaultPerKg)

        /** Grams for a length of 1.75 mm filament. */
        fun gramsFor(mm: Double, material: String): Double {
            val areaMm2 = PI * 0.875 * 0.875
            return mm * areaMm2 / 1000.0 * density(material)
        }
    }
}
