package com.example.esphomecontroller.api

import com.example.esphomecontroller.proto.Api
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** What a successful test connection learned about the device. */
data class ProbeInfo(val name: String, val friendlyName: String, val model: String, val esphomeVersion: String, val entityCount: Int, val webServerPort: Int) {
    val displayName: String get() = friendlyName.ifBlank { name }
}

enum class ProbeFailure { UNREACHABLE, KEY_REJECTED, ENCRYPTION_REQUIRED, ENCRYPTION_NOT_ENABLED, TIMEOUT, OTHER }

sealed interface ProbeResult {
    data class Success(val info: ProbeInfo, val encrypted: Boolean) : ProbeResult
    data class Failure(val kind: ProbeFailure, val message: String) : ProbeResult
}

/** User-facing explanation and suggested fix for a failed test connection. */
fun probeMessage(kind: ProbeFailure, detail: String = ""): String = when (kind) {
    ProbeFailure.UNREACHABLE -> "Could not reach the device. Check the address, port and that the phone is on the same network."
    ProbeFailure.KEY_REJECTED -> "The device rejected the encryption key. Copy the key from the device's api: encryption: key setting."
    ProbeFailure.ENCRYPTION_REQUIRED -> "This device uses encryption. Choose Encrypted and enter its key."
    ProbeFailure.ENCRYPTION_NOT_ENABLED -> "This device does not have an encryption key. Choose Unencrypted, or add an encryption key to the device configuration."
    ProbeFailure.TIMEOUT -> "The device did not answer in time."
    ProbeFailure.OTHER -> "Connection failed${if (detail.isBlank()) "." else ": $detail"}"
}

/** Maps a connection exception to a [ProbeResult.Failure]; shared by probes and tests. */
fun probeFailure(e: Exception): ProbeResult.Failure {
    val kind = when (e) {
        is KeyRejectedException -> ProbeFailure.KEY_REJECTED
        is EncryptionRequiredException -> ProbeFailure.ENCRYPTION_REQUIRED
        is EncryptionNotEnabledException -> ProbeFailure.ENCRYPTION_NOT_ENABLED
        is SocketTimeoutException -> ProbeFailure.TIMEOUT
        else -> ProbeFailure.OTHER
    }
    return ProbeResult.Failure(kind, probeMessage(kind, e.message ?: ""))
}

/**
 * Connects once with the given settings, reads device info and counts entities, then disconnects.
 * Blocking; call off the main thread. Never changes anything on the device and never falls back
 * to a different security mode. [key] is zeroed afterwards.
 */
fun probeNative(host: String, port: Int, key: ByteArray?, timeoutMs: Int = 8000): ProbeResult {
    val socket = Socket()
    try {
        try { socket.connect(InetSocketAddress(host, port), 5000) }
        catch (e: IOException) { return ProbeResult.Failure(ProbeFailure.UNREACHABLE, probeMessage(ProbeFailure.UNREACHABLE)) }
        socket.soTimeout = timeoutMs
        val frames = if (key != null) NoiseFrames(socket.getInputStream(), socket.getOutputStream(), key) else PlaintextConnection(socket.getInputStream(), socket.getOutputStream())
        frames.use { f ->
            f.write(ApiFrame(MessageType.HELLO_REQUEST, Api.HelloRequest.newBuilder().setClientInfo("ESPHome Controller").setApiVersionMajor(1).setApiVersionMinor(19).build().toByteArray()))
            f.write(ApiFrame(MessageType.AUTHENTICATION_REQUEST, Api.AuthenticationRequest.getDefaultInstance().toByteArray()))
            var info: Api.DeviceInfoResponse? = null
            var entities = 0
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                val frame = f.read()
                when (frame.type) {
                    MessageType.HELLO_RESPONSE -> {
                        val hello = Api.HelloResponse.parseFrom(frame.payload)
                        if (hello.apiVersionMajor != 1) return ProbeResult.Failure(ProbeFailure.OTHER, probeMessage(ProbeFailure.OTHER, "unsupported API version ${hello.apiVersionMajor}"))
                        f.write(ApiFrame(MessageType.DEVICE_INFO_REQUEST, byteArrayOf()))
                        f.write(ApiFrame(MessageType.LIST_ENTITIES_REQUEST, byteArrayOf()))
                    }
                    MessageType.DEVICE_INFO_RESPONSE -> info = Api.DeviceInfoResponse.parseFrom(frame.payload)
                    MessageType.PING_REQUEST -> f.write(ApiFrame(MessageType.PING_RESPONSE, byteArrayOf()))
                    MessageType.DISCONNECT_REQUEST -> throw IOException("the device closed the connection")
                    MessageType.LIST_ENTITIES_DONE -> {
                        try { f.write(ApiFrame(MessageType.DISCONNECT_REQUEST, byteArrayOf())) } catch (_: IOException) {}
                        val i = info ?: Api.DeviceInfoResponse.getDefaultInstance()
                        return ProbeResult.Success(ProbeInfo(i.name, i.friendlyName, i.model, i.esphomeVersion, entities, i.webserverPort), key != null)
                    }
                    else -> if (decodeNativeFrame(frame) is NativeEvent.EntityListed) entities++
                }
            }
            return ProbeResult.Failure(ProbeFailure.TIMEOUT, probeMessage(ProbeFailure.TIMEOUT))
        }
    } catch (e: Exception) {
        return probeFailure(e)
    } finally {
        key?.fill(0)
        try { socket.close() } catch (_: Exception) {}
    }
}

/** Decodes an ESPHome API encryption key (32 bytes, standard Base64). Returns null when invalid. */
fun decodeNoiseKey(text: String): ByteArray? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return try { java.util.Base64.getDecoder().decode(trimmed).takeIf { it.size == 32 } } catch (_: IllegalArgumentException) { null }
}
