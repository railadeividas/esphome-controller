package com.example.esphomecontroller.api

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

data class ApiFrame(val type: Int, val payload: ByteArray)

interface FrameConnection : AutoCloseable {
    fun read(): ApiFrame
    fun write(frame: ApiFrame)
}

class PlaintextConnection(private val input: InputStream, private val output: OutputStream) : FrameConnection {
    override fun read() = PlaintextFrames.read(input)
    @Synchronized override fun write(frame: ApiFrame) = PlaintextFrames.write(output, frame)
    override fun close() = Unit
}

/** ESPHome plaintext framing. This codec never falls back from encrypted transport. */
object PlaintextFrames {
    private const val MAX_PAYLOAD = 1024 * 1024
    fun write(out: OutputStream, frame: ApiFrame) {
        require(frame.type in 0..65535 && frame.payload.size <= MAX_PAYLOAD)
        out.write(0)
        writeVarint(out, frame.payload.size)
        writeVarint(out, frame.type)
        out.write(frame.payload)
        out.flush()
    }
    fun read(input: InputStream): ApiFrame {
        val marker = input.read()
        if (marker < 0) throw EOFException()
        // 0x01 is the Noise indicator: the device expects an encrypted connection.
        if (marker == 1) throw EncryptionRequiredException()
        if (marker != 0) throw IOException("Invalid plaintext frame marker")
        val length = readVarint(input)
        val type = readVarint(input)
        if (length > MAX_PAYLOAD || type > 65535) throw IOException("Frame exceeds supported limits")
        val data = ByteArray(length)
        var offset = 0
        while (offset < data.size) {
            val count = input.read(data, offset, data.size - offset)
            if (count < 0) throw EOFException("Truncated frame")
            if (count == 0) continue
            offset += count
        }
        return ApiFrame(type, data)
    }
    private fun writeVarint(out: OutputStream, value: Int) {
        var v = value
        while (v >= 128) { out.write((v and 127) or 128); v = v ushr 7 }
        out.write(v)
    }
    private fun readVarint(input: InputStream): Int {
        var result = 0L
        for (shift in 0..28 step 7) {
            val byte = input.read(); if (byte < 0) throw EOFException("Truncated varint")
            result = result or ((byte and 127).toLong() shl shift)
            if (byte and 128 == 0) {
                if (result > Int.MAX_VALUE) throw IOException("Varint overflow")
                return result.toInt()
            }
        }
        throw IOException("Invalid varint")
    }
}
