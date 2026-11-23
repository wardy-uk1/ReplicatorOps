package dev.vantara.replicatorops.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrinterAlertsTest {
    @Test fun qidiError() {
        val a = PrinterAlerts.parse("!! Code:QDE_004_005; Message:Please load the filament to 1D first.")!!
        assertEquals("QDE_004_005", a.code)
        assertEquals("Please load the filament to Box 1 slot 4 first.", a.message)
    }

    @Test fun slotNames() = assertEquals("Please reload slot 3", PrinterAlerts.plain("Please reload slot2"))

    @Test fun ordinaryLinesIgnored() {
        assertNull(PrinterAlerts.parse("// Klipper state: Ready"))
        assertNull(PrinterAlerts.parse("ok"))
    }
}
