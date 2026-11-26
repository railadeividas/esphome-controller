package com.example.esphomecontroller.api

import com.example.esphomecontroller.proto.Api
import com.stonygrey.torann.NoiseProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProbeTest {
    /** Runs [device] against one accepted connection and probes it. */
    private fun probeAgainst(key: ByteArray?, device: (InputStream, OutputStream) -> Unit): ProbeResult {
        val listener = ServerSocket(0)
        val pool = Executors.newSingleThreadExecutor()
        val server = pool.submit { listener.accept().use { peer -> peer.soTimeout = 5000; try { device(peer.getInputStream(), peer.getOutputStream()) } catch (_: Exception) {} } }
        try { return probeNative("127.0.0.1", listener.localPort, key, timeoutMs = 4000) }
        finally { server.get(10, TimeUnit.SECONDS); listener.close(); pool.shutdownNow() }
    }

    private fun noiseRead(input: InputStream): ByteArray { assertEquals(1, input.read()); val n = (input.read() shl 8) or input.read(); return input.readNBytes(n) }
    private fun noiseWrite(output: OutputStream, data: ByteArray) { output.write(byteArrayOf(1, (data.size shr 8).toByte(), data.size.toByte())); output.write(data); output.flush() }

    /** Answers the probe's requests like a minimal ESPHome device with one switch. */
    private fun serveDevice(read: () -> ApiFrame, write: (ApiFrame) -> Unit) {
        while (true) {
            val frame = read()
            when (frame.type) {
                MessageType.HELLO_REQUEST -> write(ApiFrame(2, Api.HelloResponse.newBuilder().setApiVersionMajor(1).setApiVersionMinor(12).build().toByteArray()))
                MessageType.DEVICE_INFO_REQUEST -> write(ApiFrame(10, Api.DeviceInfoResponse.newBuilder().setName("lamp").setFriendlyName("Lamp").setModel("esp32dev").setEsphomeVersion("2026.11.0").build().toByteArray()))
                MessageType.LIST_ENTITIES_REQUEST -> {
                    write(ApiFrame(17, Api.ListEntitiesSwitchResponse.newBuilder().setKey(1).setName("Relay").build().toByteArray()))
                    write(ApiFrame(19, byteArrayOf()))
                }
                MessageType.DISCONNECT_REQUEST -> return
            }
        }
    }

    @Test fun plaintextDeviceReportsInfoAndEntityCount() {
        val result = probeAgainst(null) { input, output -> serveDevice({ PlaintextFrames.read(input) }, { PlaintextFrames.write(output, it) }) }
        val success = result as ProbeResult.Success
        assertFalse(success.encrypted); assertEquals("Lamp", success.info.displayName); assertEquals(1, success.info.entityCount); assertEquals("esp32dev", success.info.model)
    }

    @Test fun encryptedDeviceWithCorrectKey() {
        val psk = ByteArray(32) { (it * 7).toByte() }
        val result = probeAgainst(psk.copyOf()) { input, output ->
            assertEquals(0, noiseRead(input).size)
            val first = noiseRead(input)
            val handshake = NoiseProtocol.parse("Noise_NNpsk0_25519_ChaChaPoly_SHA256").createHandshake(false, "NoiseAPIInit\u0000\u0000".toByteArray(), psks = listOf(psk))
            handshake.readMessage(first.copyOfRange(1, first.size))
            noiseWrite(output, byteArrayOf(1))
            val response = handshake.writeMessage(); noiseWrite(output, byteArrayOf(0) + response.message)
            val t = response.transport!!
            serveDevice({
                val plain = t.readMessage(noiseRead(input))
                ApiFrame(((plain[0].toInt() and 255) shl 8) or (plain[1].toInt() and 255), plain.copyOfRange(4, plain.size))
            }, { f -> noiseWrite(output, t.writeMessage(byteArrayOf((f.type shr 8).toByte(), f.type.toByte(), (f.payload.size shr 8).toByte(), f.payload.size.toByte()) + f.payload)) })
        }
        assertTrue((result as ProbeResult.Success).encrypted)
    }

    @Test fun wrongKeyIsReportedAsRejected() {
        val result = probeAgainst(ByteArray(32) { 1 }) { input, output ->
            noiseRead(input); noiseRead(input)
            noiseWrite(output, byteArrayOf(1))
            noiseWrite(output, byteArrayOf(1) + "Handshake MAC failure".toByteArray())
        }
        assertEquals(ProbeFailure.KEY_REJECTED, (result as ProbeResult.Failure).kind)
    }

    @Test fun plaintextAgainstEncryptedDeviceAsksForKey() {
        val result = probeAgainst(null) { input, output ->
            PlaintextFrames.read(input)
            noiseWrite(output, byteArrayOf(1) + "Bad indicator byte".toByteArray())
        }
        assertEquals(ProbeFailure.ENCRYPTION_REQUIRED, (result as ProbeResult.Failure).kind)
    }

    @Test fun keyAgainstPlaintextDeviceSaysEncryptionNotEnabled() {
        val closes = probeAgainst(ByteArray(32)) { input, _ -> input.read() }
        assertEquals(ProbeFailure.ENCRYPTION_NOT_ENABLED, (closes as ProbeResult.Failure).kind)
        val plainReply = probeAgainst(ByteArray(32)) { input, output -> input.read(); PlaintextFrames.write(output, ApiFrame(2, byteArrayOf())) }
        assertEquals(ProbeFailure.ENCRYPTION_NOT_ENABLED, (plainReply as ProbeResult.Failure).kind)
    }

    @Test fun unreachableAndTimeout() {
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(ProbeFailure.UNREACHABLE, (probeNative("127.0.0.1", port, null, 2000) as ProbeResult.Failure).kind)
        val silent = probeAgainst(null) { input, _ -> PlaintextFrames.read(input); Thread.sleep(2500) }
        assertTrue((silent as ProbeResult.Failure).kind in setOf(ProbeFailure.TIMEOUT, ProbeFailure.OTHER))
    }

    @Test fun validatesEncryptionKeys() {
        assertEquals(32, decodeNoiseKey(java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))!!.size)
        assertEquals(32, decodeNoiseKey("  " + java.util.Base64.getEncoder().encodeToString(ByteArray(32)) + "\n")!!.size)
        assertNull(decodeNoiseKey("")); assertNull(decodeNoiseKey("not base64!")); assertNull(decodeNoiseKey(java.util.Base64.getEncoder().encodeToString(ByteArray(16))))
    }
}
