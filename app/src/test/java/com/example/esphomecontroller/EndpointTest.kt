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
    @Test fun splitsAndJoinsIpv4AndIpv6Addresses() {
        assertEquals("192.0.2.10" to "6053", splitAddress("192.0.2.10:6053"))
        assertEquals("device.local" to "6053", splitAddress("device.local"))
        assertEquals("[2001:db8::1]:80", joinAddress("2001:db8::1", 80))
        assertEquals("2001:db8::1" to "80", splitAddress("[2001:db8::1]:80"))
        assertEquals("fe80::1%wlan0" to "6053", splitAddress(joinAddress("fe80::1%wlan0", 6053)))
    }
}
