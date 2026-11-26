package com.example.esphomecontroller.api

import com.stonygrey.torann.NoiseProtocol
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NoiseFramesTest {
    @Test fun performsHandshakeAndExchangesAuthenticatedFrames() {
        val psk = ByteArray(32) { it.toByte() }
        val listener = ServerSocket(0)
        val pool = Executors.newSingleThreadExecutor()
        val server = pool.submit {
            listener.accept().use { peer ->
                peer.soTimeout = 5000
                val input = peer.getInputStream(); val output = peer.getOutputStream()
                fun read(): ByteArray { assertEquals(1, input.read()); val n = (input.read() shl 8) or input.read(); return input.readNBytes(n) }
                fun write(data: ByteArray) { output.write(byteArrayOf(1, (data.size shr 8).toByte(), data.size.toByte())); output.write(data); output.flush() }
                assertEquals(0, read().size)
                val first = read(); assertEquals(0, first[0].toInt())
                val handshake = NoiseProtocol.parse("Noise_NNpsk0_25519_ChaChaPoly_SHA256").createHandshake(false, "NoiseAPIInit\u0000\u0000".toByteArray(), psks = listOf(psk))
                handshake.readMessage(first.copyOfRange(1, first.size))
                write(byteArrayOf(1))
                val response = handshake.writeMessage(); write(byteArrayOf(0) + response.message)
                val transport = response.transport!!
                val request = transport.readMessage(read())
                assertArrayEquals(byteArrayOf(0, 7, 0, 2, 42, 43), request)
                write(transport.writeMessage(byteArrayOf(0, 8, 0, 1, 99)))
                transport.close(); handshake.close()
            }
        }
        Socket("127.0.0.1", listener.localPort).use { socket ->
            socket.soTimeout = 5000
            NoiseFrames(socket.getInputStream(), socket.getOutputStream(), psk).use { frames ->
                frames.write(ApiFrame(7, byteArrayOf(42, 43)))
                val response = frames.read(); assertEquals(8, response.type); assertArrayEquals(byteArrayOf(99), response.payload)
            }
        }
        server.get(10, TimeUnit.SECONDS); listener.close(); pool.shutdownNow()
    }
}
