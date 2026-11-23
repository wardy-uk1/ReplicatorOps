package dev.vantara.replicatorops

import dev.vantara.replicatorops.alert.RedAlerts
import dev.vantara.replicatorops.data.Connection
import dev.vantara.replicatorops.data.PrinterState
import dev.vantara.replicatorops.ui.prettyObjectName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedAlertsTest {
    private fun st(state: String, progress: Double = 0.5) = PrinterState(
        connection = Connection.CONNECTED, klippy = "ready", printState = state,
        filename = "Mood_Ghost.gcode", progress = progress, printDuration = 3600.0,
    )

    @Test fun noAlertOnFirstState() = assertNull(RedAlerts.transition(null, st("complete")))

    @Test fun completeAlerts() {
        val a = RedAlerts.transition(st("printing"), st("complete"))
        assertNotNull(a)
        assertEquals("Replication complete", a!!.title)
        assertTrue("Mood_Ghost" in a.text)
    }

    @Test fun pauseIsSevere() = assertTrue(RedAlerts.transition(st("printing"), st("paused"))!!.severe)

    @Test fun errorIsSevere() = assertTrue(RedAlerts.transition(st("printing"), st("error"))!!.severe)

    @Test fun cancelAlerts() = assertNotNull(RedAlerts.transition(st("paused"), st("cancelled")))

    @Test fun resumeDoesNotAlert() = assertNull(RedAlerts.transition(st("paused"), st("printing")))

    @Test fun standbyToCompleteDoesNotAlert() = assertNull(RedAlerts.transition(st("standby"), st("complete")))

    @Test fun watchShowsProgress() {
        val w = RedAlerts.watch(st("printing", 0.42), "qidi")
        assertEquals(42, w.percent)
        assertTrue(w.title.startsWith("Replicating 42%"))
        assertTrue(w.printing)
    }

    @Test fun watchOffline() = assertEquals("Subspace link lost", RedAlerts.watch(PrinterState(), "qidi").title)

    @Test fun prettyNames() {
        assertEquals("Cube #2", prettyObjectName("Cube_id_0_copy_1"))
        assertEquals("Mood Ghost #1", prettyObjectName("Mood_Ghost.stl_id_3_copy_0"))
        assertEquals("part a", prettyObjectName("part_a"))
    }
}
