package com.example.esphomecontroller.api

import com.stonygrey.torann.NoiseProtocol
import com.stonygrey.torann.Transport
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** ESPHome Noise_NNpsk0 framing. Never switches to plaintext. */
class NoiseFrames(private val input: InputStream, private val output: OutputStream, key: ByteArray) : FrameConnection {
    private val transport: Transport
    init {
        require(key.size == 32) { "Encryption key must be 32 bytes" }
        val handshake = NoiseProtocol.parse("Noise_NNpsk0_25519_ChaChaPoly_SHA256")
            .createHandshake(initiator = true, prologue = "NoiseAPIInit\u0000\u0000".toByteArray(), psks = listOf(key))
        try {
            writeRaw(byteArrayOf())
            writeRaw(byteArrayOf(0) + handshake.writeMessage().message)
            val hello = try { readRaw() } catch (_: EOFException) { throw EncryptionNotEnabledException("it closed the connection") }
            if (hello.isEmpty() || hello[0].toInt() != 1) throw EncryptionNotEnabledException()
            val reply = try { readRaw() } catch (_: EOFException) { throw EncryptionNotEnabledException("it closed the connection") }
            if (reply.isEmpty()) throw IOException("Empty encryption handshake reply")
            if (reply[0].toInt() != 0) {
                // The device explains the failure in text after the status byte.
                val reason = String(reply, 1, reply.size - 1, Charsets.UTF_8)
                if (reason.contains("indicator", ignoreCase = true)) throw EncryptionNotEnabledException()
                throw KeyRejectedException()
            }
            transport = handshake.readMessage(reply.copyOfRange(1, reply.size)).transport ?: throw IOException("Incomplete encryption handshake")
        } catch (_: java.net.SocketException) {
            // A plaintext-only device drops the connection when it sees the Noise indicator byte.
            throw EncryptionNotEnabledException("it closed the connection")
        } finally { handshake.close() }
    }
    @Synchronized override fun write(frame: ApiFrame) {
        require(frame.type in 0..65535 && frame.payload.size <= 65515)
        val size = frame.payload.size
        val bytes = byteArrayOf((frame.type shr 8).toByte(), frame.type.toByte(), (size shr 8).toByte(), size.toByte()) + frame.payload
        writeRaw(transport.writeMessage(bytes))
    }
    override fun read(): ApiFrame {
        val plain = transport.readMessage(readRaw())
        if (plain.size < 4) throw IOException("Invalid encrypted message")
        val type = ((plain[0].toInt() and 255) shl 8) or (plain[1].toInt() and 255)
        val size = ((plain[2].toInt() and 255) shl 8) or (plain[3].toInt() and 255)
        if (size != plain.size - 4) throw IOException("Invalid encrypted message length")
        return ApiFrame(type, plain.copyOfRange(4, plain.size))
    }
    private fun writeRaw(payload: ByteArray) {
        if (payload.size > 65535) throw IOException("Frame too large")
        output.write(byteArrayOf(1, (payload.size shr 8).toByte(), payload.size.toByte())); output.write(payload); output.flush()
    }
    private fun readRaw(): ByteArray {
        val marker = input.read(); val hi = input.read(); val lo = input.read()
        if (marker < 0 || hi < 0 || lo < 0) throw EOFException()
        // A plaintext (0x00) frame means the device is not configured for encryption.
        if (marker == 0) throw EncryptionNotEnabledException()
        if (marker != 1) throw IOException("Invalid Noise frame marker")
        val payload = ByteArray((hi shl 8) or lo); var offset = 0
        while (offset < payload.size) { val n = input.read(payload, offset, payload.size - offset); if (n < 0) throw EOFException(); if (n > 0) offset += n }
        return payload
    }
    override fun close() = transport.close()
}
