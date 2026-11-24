package io.github.foliba.phorca.core

import io.github.foliba.phorca.core.config.HostType
import io.github.foliba.phorca.core.config.PrinterConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrinterWebUrlTest {
    private fun web(url: String, type: HostType = HostType.MOONRAKER) = PrinterConnection("i", "P", type, url).webUrl()

    @Test
    fun webInterfaceAddress() {
        assertEquals("http://soron.local/", web("http://soron.local"))
        assertEquals("http://192.168.1.20/", web("192.168.1.20"))
        // Moonraker's API port: Mainsail/Fluidd run on the normal web port
        assertEquals("http://192.168.1.20/", web("http://192.168.1.20:7125"))
        assertEquals("http://192.168.1.20:8080/", web("http://192.168.1.20:8080"))
        assertEquals("https://printer.example.org/", web("https://printer.example.org/"))
        assertEquals("http://octopi.local:5000/", web("http://octopi.local:5000", HostType.OCTOPRINT))
        // only Moonraker's port is special
        assertEquals("http://host:7125/", web("http://host:7125", HostType.OCTOPRINT))
        // a front-end under a path stays, API paths open the start page
        assertEquals("http://host/mainsail/", web("http://host/mainsail/"))
        assertEquals("http://host/", web("http://host:7125/printer/info"))
        assertNull(web("http://"))
        assertNull(web(""))
    }
}
