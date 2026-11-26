package com.example.esphomecontroller.api

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PlaintextFramesTest {
    @Test fun upstreamWireFormatAndMultiByteSize() {
        val output = ByteArrayOutputStream()
        val data = ByteArray(128) { it.toByte() }
        PlaintextFrames.write(output, ApiFrame(300, data))
        assertArrayEquals(byteArrayOf(0, 0x80.toByte(), 1, 0xac.toByte(), 2), output.toByteArray().copyOf(5))
        val decoded = PlaintextFrames.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals(300, decoded.type); assertArrayEquals(data, decoded.payload)
    }
    @Test fun rejectsWrongMarkerAndTruncatedPayload() {
        assertThrows(IOException::class.java) { PlaintextFrames.read(ByteArrayInputStream(byteArrayOf(1))) }
        assertThrows(EOFException::class.java) { PlaintextFrames.read(ByteArrayInputStream(byteArrayOf(0, 2, 1, 4))) }
    }
}
