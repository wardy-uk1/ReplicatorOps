package dev.vantara.replicatorops.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointTest {
    private fun base(input: String) = Endpoint.parse(input, null)?.baseUrl

    @Test fun ipv4AndNames() {
        assertEquals("http://192.168.1.50", base("192.168.1.50"))
        assertEquals("http://192.168.1.50:7125", base("192.168.1.50:7125/"))
        assertEquals("http://qidi-q2.tail985254.ts.net", base("qidi-q2.tail985254.ts.net"))
        assertEquals("https://printer.example.com", base("https://printer.example.com"))
    }

    @Test fun ipv6() {
        assertEquals("http://[fd7a:115c:a1e0::4646:226a]", base("fd7a:115c:a1e0::4646:226a"))
        assertEquals("http://[fd7a:115c:a1e0::4646:226a]:7125", base("[fd7a:115c:a1e0::4646:226a]:7125"))
        assertEquals("http://[2a00:23c7::5]", base("http://[2a00:23c7::5]/"))
        assertEquals("ws://[fd7a:115c:a1e0::1]:7125/websocket", Endpoint.parse("[fd7a:115c:a1e0::1]:7125", null)?.wsUrl)
    }

    @Test fun ipv6UrlsResolve() {
        val ep = Endpoint.parse("[fd7a:115c:a1e0::1]:7125", null)!!
        assertEquals("http://[fd7a:115c:a1e0::1]:7125/webcam/?action=snapshot", ep.resolve("/webcam/?action=snapshot"))
        assertEquals("http://[fd7a:115c:a1e0::1]:8080/?action=snapshot", ep.resolve("http://127.0.0.1:8080/?action=snapshot"))
    }

    @Test fun rejected() {
        assertNull(base(""))
        assertNull(base("fe80::1%wlan0"))
        assertNull(base("ftp://printer"))
        assertNotNull(Endpoint.problem("fe80::1%wlan0"))
        assertNull(Endpoint.problem("fd7a:115c:a1e0::1"))
    }
}
