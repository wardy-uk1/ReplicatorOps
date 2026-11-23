package dev.vantara.replicatorops.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilamentMappingTest {
    // The user's Box: slot 1 red PLA, slot 2 yellow PLA Silk, slot 3 grey PLA, slot 4 empty.
    private val slots = listOf(
        BoxSlot(0, true, "PLA", 0xFFD7263D),
        BoxSlot(1, true, "PLA Silk", 0xFFFFD23F),
        BoxSlot(2, true, "PLA", 0xFF8A8F98),
        BoxSlot(3, false, null, null),
    )
    // As in saved_variables.cfg: value_t0 = 'slot2', value_t1 = 'slot3' (empty: "load the filament to 1D").
    private val current = mapOf(0 to 2, 1 to 3)

    @Test fun keepsAGoodCurrentChoice() {
        val needs = listOf(ToolNeed(0, "PLA", 0xFF808080))
        assertEquals(mapOf(0 to 2), FilamentMapping.suggest(needs, slots, current))
    }

    @Test fun movesOffAnEmptySlotToTheClosestColour() {
        val needs = listOf(ToolNeed(1, "PLA", 0xFFE02030))
        assertEquals(mapOf(1 to 0), FilamentMapping.suggest(needs, slots, current))
    }

    @Test fun emptySlotIsAProblem() {
        val needs = listOf(ToolNeed(1, "PLA", null))
        assertTrue(FilamentMapping.problems(needs, slots, mapOf(1 to 3)).first().contains("empty"))
    }

    @Test fun differentTypeIsOnlyANote() {
        val needs = listOf(ToolNeed(0, "PETG", null))
        assertTrue(FilamentMapping.problems(needs, slots, mapOf(0 to 0)).isEmpty())
        assertTrue(FilamentMapping.notes(needs, slots, mapOf(0 to 0)).first().contains("PETG"))
    }

    @Test fun silkCountsAsPla() = assertTrue(FilamentMapping.sameType("PLA Silk", "PLA"))

    @Test fun gcodeMatchesSavedVariables() =
        assertEquals("SAVE_VARIABLE VARIABLE=value_t1 VALUE=\"'slot0'\"", FilamentMapping.gcode(mapOf(1 to 0)))

    @Test fun singleFilamentFileDefaultsToT0() =
        assertEquals(listOf(0), FilamentMapping.needs(FileMeta(thumbnailUrl = null, estimatedSeconds = null, filamentMm = null, filamentGrams = null, layerHeight = null, objectHeight = null, slicer = null)).map { it.tool })
}
