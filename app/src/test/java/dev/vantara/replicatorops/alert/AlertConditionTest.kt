package dev.vantara.replicatorops.alert

import dev.vantara.replicatorops.data.Connection
import dev.vantara.replicatorops.data.Heater
import dev.vantara.replicatorops.data.PrinterState
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertConditionTest {
    private val ok = PrinterState(connection = Connection.CONNECTED, klippy = "ready", printState = "printing", filename = "cube.gcode")

    @Test fun greenWhenPrinting() = assertEquals(Condition.GREEN, AlertMonitor().update(ok, 0).condition)

    @Test fun klipperShutdownIsDoubleRed() {
        val s = AlertMonitor().update(ok.copy(klippy = "shutdown", klippyMessage = "Heater extruder not heating at expected rate\nmore"), 0)
        assertEquals(Condition.DOUBLE_RED, s.condition)
        assertEquals("Heater extruder not heating at expected rate", s.reason)
    }

    @Test fun lostMidPrintIsDoubleRedAfter30s() {
        val m = AlertMonitor()
        m.update(ok, 0)
        val lost = ok.copy(connection = Connection.DISCONNECTED)
        assertEquals(Condition.GREEN, m.update(lost, 10_000).condition)
        assertEquals(Condition.DOUBLE_RED, m.update(lost, 41_000).condition)
        assertEquals(Condition.GREEN, m.update(ok, 50_000).condition)
    }

    @Test fun pauseIsRedUnlessYouDidIt() {
        val m = AlertMonitor()
        assertEquals(Condition.RED, m.update(ok.copy(printState = "paused"), 0).condition)
        m.userPaused = true
        assertEquals(Condition.YELLOW, m.update(ok.copy(printState = "paused"), 1).condition)
        m.update(ok, 2)
        assertEquals(Condition.RED, m.update(ok.copy(printState = "paused"), 3).condition)
    }

    @Test fun slowHeaterIsYellow() {
        val m = AlertMonitor()
        val cold = ok.copy(heaters = listOf(Heater("extruder", "extruder", "Nozzle", 150.0, 220.0)))
        assertEquals(Condition.GREEN, m.update(cold, 0).condition)
        assertEquals(Condition.YELLOW, m.update(cold, 9 * 60_000L).condition)
        assertEquals(Condition.GREEN, m.update(cold.copy(heaters = listOf(Heater("extruder", "extruder", "Nozzle", 215.0, 220.0))), 9 * 60_000L + 1).condition)
    }

    @Test fun printerErrorLatchesRed() {
        val m = AlertMonitor()
        m.latch(Condition.RED, "Load filament to 1D", 0)
        assertEquals(Condition.RED, m.update(ok, 1).condition)
        m.clearLatched()
        assertEquals(Condition.GREEN, m.update(ok, 2).condition)
    }
}
