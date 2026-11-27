package com.example.esphomecontroller.api

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class LogLineTest {
    @Test fun parsesEsphomePrefix() {
        parseLogLine("[W][wifi:123]: Connection lost", time = 0, seq = 1).let {
            assertEquals(LogLevel.WARN, it.level); assertEquals("wifi", it.tag); assertEquals("Connection lost", it.message); assertFalse(it.isState)
        }
        parseLogLine("[VV][api.connection:042]: Frame", seq = 2).let { assertEquals(LogLevel.VERY_VERBOSE, it.level); assertEquals("api.connection", it.tag); assertEquals("Frame", it.message) }
        parseLogLine("[C][logger]: Logger:", seq = 3).let { assertEquals(LogLevel.CONFIG, it.level); assertEquals("logger", it.tag); assertEquals("Logger:", it.message) }
        // Multi-line messages keep their continuation lines.
        assertEquals("Sensor 'x'\n  Unit: °C", parseLogLine("[C][sensor:010]: Sensor 'x'\n  Unit: °C", seq = 4).message)
    }

    @Test fun linesWithoutPrefixUseTheLevelHint() {
        parseLogLine("plain text", levelHint = 1, seq = 1).let { assertEquals(LogLevel.ERROR, it.level); assertNull(it.tag); assertEquals("plain text", it.message) }
        parseLogLine("plain text", seq = 2).let { assertNull(it.level); assertNull(it.tag) }
        // The prefix wins over the hint, and unknown hints are ignored.
        assertEquals(LogLevel.DEBUG, parseLogLine("[D][x:1]: y", levelHint = 3, seq = 3).level)
        assertNull(parseLogLine("z", levelHint = 42, seq = 4).level)
    }

    @Test fun exportsWithReceiveTime() {
        val t = 15L * 3600_000 + 42 * 60_000 + 7_000 + 5
        assertEquals("15:42:07.005 [W][wifi]: Connection lost", parseLogLine("[W][wifi:123]: Connection lost", time = t, seq = 1).export(ZoneOffset.UTC))
        assertEquals("15:42:07.005 plain", parseLogLine("plain", time = t, seq = 2).export(ZoneOffset.UTC))
    }

    @Test fun levelsMatchProto() {
        LogLevel.entries.forEach { assertEquals(it, LogLevel.ofProto(it.protoValue)); assertEquals(it, LogLevel.ofCode(it.code)) }
        assertEquals(com.example.esphomecontroller.proto.Api.LogLevel.LOG_LEVEL_VERY_VERBOSE_VALUE, LogLevel.VERY_VERBOSE.protoValue)
        assertEquals(com.example.esphomecontroller.proto.Api.LogLevel.LOG_LEVEL_ERROR_VALUE, LogLevel.ERROR.protoValue)
    }

    @Test fun redactsAddressesButNotTimes() {
        assertEquals("IP Address: <ip>", redactAddresses("IP Address: 192.168.1.20"))
        assertEquals("BSSID: <mac>", redactAddresses("BSSID: AA:BB:CC:DD:EE:0F"))
        assertEquals("addr <ipv6> and <ipv6>", redactAddresses("addr fe80::1a2b:3c4d and 2001:db8:0:0:0:0:0:1"))
        assertEquals("Uptime 12:34:56, version 2025.11.0", redactAddresses("Uptime 12:34:56, version 2025.11.0"))
    }

    @Test fun appendKeepsTheNewestLines() {
        var lines = emptyList<Int>()
        repeat(MAX_LOG_LINES + 3) { lines = appendLog(lines, it) }
        assertEquals(MAX_LOG_LINES, lines.size); assertEquals(3, lines.first()); assertEquals(MAX_LOG_LINES + 2, lines.last())
    }
}
