package com.example.esphomecontroller.api

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.esphomecontroller.proto.Api
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

interface ControlSession : AutoCloseable {
    val status: String
    val entities: List<DeviceSession.Entity>
    fun toggle(e: DeviceSession.Entity, value: Boolean)
    fun brightness(e: DeviceSession.Entity, value: Float)
    fun select(e: DeviceSession.Entity, option: String)
}

/** Native API session; all UI state is published on the main thread. */
class DeviceSession(private val host: String, private val port: Int, private val key: ByteArray?) : ControlSession {
    data class Entity(val key: Int, val deviceId: Int, val name: String, val kind: String, val unit: String = "", val state: Boolean? = null, val value: String? = null, val brightness: Float? = null, val supportsBrightness: Boolean = false, val webId: String? = null, val receivedState: Boolean = false, val options: List<String> = emptyList())
    override var status by mutableStateOf("Connecting…"); private set
    override var entities by mutableStateOf<List<Entity>>(emptyList()); private set
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var socket: Socket? = null
    @Volatile private var frames: FrameConnection? = null
    @Volatile private var closed = false
    init { Thread({ run() }, "esphome-session").start() }
    private fun publish(block: () -> Unit) { ui.post { if (!closed) block() } }
    private fun run() {
        try {
            val s = Socket(); socket = s; if (closed) { s.close(); return }; s.connect(InetSocketAddress(host, port), 8000); s.soTimeout = 90000
            val f = if (key != null) NoiseFrames(s.getInputStream(), s.getOutputStream(), key) else PlaintextConnection(s.getInputStream(), s.getOutputStream()); frames = f; key?.fill(0)
            send(1, Api.HelloRequest.newBuilder().setClientInfo("ESPHome Controller").setApiVersionMajor(1).setApiVersionMinor(19).build().toByteArray())
            send(3, Api.AuthenticationRequest.getDefaultInstance().toByteArray())
            while (!closed) {
                val frame = f.read()
                when (frame.type) {
                    2 -> { val hello = Api.HelloResponse.parseFrom(frame.payload); if (hello.apiVersionMajor > 2) throw IllegalStateException("Unsupported API version"); send(11, byteArrayOf()); send(20, byteArrayOf()); publish { status = if (key == null) "Connected without encryption" else "Connected securely" } }
                    4 -> if (Api.AuthenticationResponse.parseFrom(frame.payload).invalidPassword) throw IllegalStateException("Device requires legacy password authentication")
                    5 -> { send(6, byteArrayOf()); throw IllegalStateException("Device closed connection") }
                    7 -> send(8, byteArrayOf())
                    12 -> Api.ListEntitiesBinarySensorResponse.parseFrom(frame.payload).let { p -> if (!p.disabledByDefault) add(Entity(p.key, p.deviceId, p.name.ifBlank { p.objectId }, "binary")) }
                    15 -> Api.ListEntitiesLightResponse.parseFrom(frame.payload).let { p -> if (!p.disabledByDefault) add(Entity(p.key, p.deviceId, p.name.ifBlank { p.objectId }, "light", supportsBrightness = p.legacySupportsBrightness || p.supportedColorModesValueList.any { (it and 2) != 0 })) }
                    16 -> Api.ListEntitiesSensorResponse.parseFrom(frame.payload).let { p -> if (!p.disabledByDefault) add(Entity(p.key, p.deviceId, p.name.ifBlank { p.objectId }, "sensor", p.unitOfMeasurement)) }
                    17 -> Api.ListEntitiesSwitchResponse.parseFrom(frame.payload).let { p -> if (!p.disabledByDefault) add(Entity(p.key, p.deviceId, p.name.ifBlank { p.objectId }, "switch")) }
                    18 -> Api.ListEntitiesTextSensorResponse.parseFrom(frame.payload).let { p -> if (!p.disabledByDefault) add(Entity(p.key, p.deviceId, p.name.ifBlank { p.objectId }, "text")) }
                    52 -> Api.ListEntitiesSelectResponse.parseFrom(frame.payload).let { p -> if (!p.disabledByDefault) add(Entity(p.key, p.deviceId, p.name.ifBlank { p.objectId }, "select", options = p.optionsList.toList())) }
                    53 -> Api.SelectStateResponse.parseFrom(frame.payload).let { p -> update(p.key, p.deviceId) { it.copy(value = if (p.missingState) null else p.state, receivedState = true) } }
                    21 -> Api.BinarySensorStateResponse.parseFrom(frame.payload).let { p -> update(p.key, p.deviceId) { it.copy(state = if (p.missingState) null else p.state, receivedState = true) } }
                    24 -> Api.LightStateResponse.parseFrom(frame.payload).let { p -> update(p.key, p.deviceId) { it.copy(state = p.state, brightness = p.brightness, receivedState = true) } }
                    25 -> Api.SensorStateResponse.parseFrom(frame.payload).let { p -> update(p.key, p.deviceId) { it.copy(value = if (p.missingState) null else p.state.toString(), receivedState = true) } }
                    26 -> Api.SwitchStateResponse.parseFrom(frame.payload).let { p -> update(p.key, p.deviceId) { it.copy(state = if (p.missingState) null else p.state, receivedState = true) } }
                    27 -> Api.TextSensorStateResponse.parseFrom(frame.payload).let { p -> update(p.key, p.deviceId) { it.copy(value = if (p.missingState) null else p.state, receivedState = true) } }
                }
            }
        } catch (e: Exception) { publish { status = "Connection failed: ${e.message ?: e.javaClass.simpleName}" } }
        finally { key?.fill(0); try { frames?.close() } catch (_: Exception) {}; try { socket?.close() } catch (_: Exception) {}; frames = null }
    }
    private fun add(entity: Entity) = publish { entities = entities.filterNot { it.key == entity.key && it.deviceId == entity.deviceId } + entity }
    private fun update(key: Int, device: Int, change: (Entity) -> Entity) = publish { entities = entities.map { if (it.key == key && it.deviceId == device) change(it) else it } }
    private fun send(type: Int, payload: ByteArray) { (frames ?: throw IllegalStateException("Not connected")).write(ApiFrame(type, payload)) }
    override fun toggle(e: Entity, value: Boolean) { worker.execute { try {
        when (e.kind) {
            "light" -> send(32, Api.LightCommandRequest.newBuilder().setKey(e.key).setDeviceId(e.deviceId).setHasState(true).setState(value).build().toByteArray())
            "switch" -> send(33, Api.SwitchCommandRequest.newBuilder().setKey(e.key).setDeviceId(e.deviceId).setState(value).build().toByteArray())
        }
    } catch (ex: Exception) { publish { status = "Command failed: ${ex.message}" } } } }
    override fun brightness(e: Entity, value: Float) { worker.execute { try {
        send(32, Api.LightCommandRequest.newBuilder().setKey(e.key).setDeviceId(e.deviceId).setHasBrightness(true).setBrightness(value).build().toByteArray())
    } catch (ex: Exception) { publish { status = "Command failed: ${ex.message}" } } } }
    override fun select(e: Entity, option: String) {
        val frame = createNativeSelectCommand(e, option) ?: return
        worker.execute { try {
            send(frame.type, frame.payload)
        } catch (ex: Exception) { publish { status = "Command failed: ${ex.message}" } } }
    }
    override fun close() { closed = true; key?.fill(0); try { socket?.close() } catch (_: Exception) {}; worker.shutdownNow() }
}

fun createNativeSelectCommand(entity: DeviceSession.Entity, option: String): ApiFrame? {
    if (entity.kind != "select" || option !in entity.options) return null
    val payload = Api.SelectCommandRequest.newBuilder().setKey(entity.key).setDeviceId(entity.deviceId).setState(option).build().toByteArray()
    return ApiFrame(54, payload)
}
