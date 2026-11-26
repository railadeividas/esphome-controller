package com.example.esphomecontroller

import org.junit.Assert.*
import org.junit.Test

class EndpointTest {
    @Test fun acceptsValidEndpoint() {
        assertEquals(Endpoint("device.local", 6053), parseEndpoint(" device.local ", "6053"))
    }
    @Test fun rejectsInvalidValues() {
        for ((host, port) in listOf("" to "6053", "a b" to "6053", "host" to "0", "host" to "65536", "host" to "abc")) {
            assertNull(parseEndpoint(host, port))
        }
    }
}
