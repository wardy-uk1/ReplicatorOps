package dev.vantara.replicatorops.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CostTest {
    private fun spool(material: String, price: Double?, grams: Int = 1000) =
        Spool(1, material, "Black", "", 0xFF000000, grams, grams.toDouble(), price = price)

    @Test fun usesDefaultWithoutPricedSpools() =
        assertEquals(0.02, StoresRepo.costPerGram("PLA", emptyList(), 20.0), 1e-9)

    @Test fun usesMatchingSpoolPrice() =
        assertEquals(0.03, StoresRepo.costPerGram("PLA Silk", listOf(spool("Silk PLA", 30.0), spool("PETG", 10.0)), 20.0), 1e-9)

    @Test fun plaPlusIsNotPla() =
        assertEquals(0.02, StoresRepo.costPerGram("PLA", listOf(spool("PLA+", 50.0)), 20.0), 1e-9)

    @Test fun printCost() {
        // 1 m of PLA ≈ 2.98 g; at £20/kg ≈ £0.06.
        assertEquals(0.0596, StoresRepo.printCost(1000.0, "PLA", emptyList(), 20.0), 0.001)
    }
}
