package dev.vantara.replicatorops

import dev.vantara.replicatorops.widget.WidgetData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetDataTest {
    @Test fun printing() {
        val d = WidgetData.of("printing", true, 0.5, 3600.0, "parts/Mood_Ghost.gcode")
        assertEquals("Replicating", d.state)
        assertEquals(50, d.percent)
        assertEquals("Mood_Ghost", d.file)
        assertTrue(d.left!!.endsWith("left"))
    }

    @Test fun standby() {
        val d = WidgetData.of("standby", true, 0.0, 0.0, null)
        assertEquals("Standing by", d.state)
        assertNull(d.percent)
    }

    @Test fun offline() = assertEquals("Offline", WidgetData.of(null, false, 0.0, 0.0, null).state)
}
