package dev.vantara.replicatorops

import dev.vantara.replicatorops.data.Connection
import dev.vantara.replicatorops.data.PrinterState
import dev.vantara.replicatorops.ui.Voice
import dev.vantara.replicatorops.ui.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTest {
    @Test fun commands() {
        assertEquals(VoiceCommand.STATUS, Voice.parse("Computer, status report"))
        assertEquals(VoiceCommand.PAUSE, Voice.parse("computer pause replication"))
        assertEquals(VoiceCommand.RESUME, Voice.parse("Resume replication"))
        assertEquals(VoiceCommand.ABORT, Voice.parse("Computer, abort replication"))
        assertEquals(VoiceCommand.TIME_LEFT, Voice.parse("how long is left"))
        assertEquals(VoiceCommand.TEMPERATURES, Voice.parse("what are the temperatures"))
        assertEquals(VoiceCommand.LIGHTS_ON, Voice.parse("Computer, lights on"))
        assertEquals(VoiceCommand.LIGHTS_OFF, Voice.parse("lights off"))
        assertEquals(VoiceCommand.UNKNOWN, Voice.parse("tea, Earl Grey, hot"))
    }

    @Test fun report() {
        val p = PrinterState(connection = Connection.CONNECTED, klippy = "ready", printState = "printing", progress = 0.5, printDuration = 3600.0)
        val r = Voice.statusReport(p)
        assertTrue(r, r.startsWith("Replicating, 50 percent complete"))
        assertTrue(r, "1 hour remaining" in r)
    }
}
