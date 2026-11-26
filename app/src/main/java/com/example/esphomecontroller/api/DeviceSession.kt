package com.example.esphomecontroller.api

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.esphomecontroller.proto.Api
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A live connection to one device. All observable state is published on the main thread. */
interface ControlSession : AutoCloseable {
    val connection: ConnectionState
    val entities: List<Entity>
    val details: DeviceDetails?
    /** The last command failure; does not by itself mean the connection is down. */
    val lastError: CommandError?
    fun send(entity: Entity, command: Command)
    /** Re-sends the command behind [lastError] to the current copy of its entity, if it is still ready. */
    fun retryFailed()
    /** Recent device log lines, newest last; kept in memory only and only after [subscribeLogs]. */
    val logs: List<String>
    /** Starts receiving device logs on this connection. Logs may contain network details; never persist them. */
    fun subscribeLogs()
}

/** A failed command. [serial] is unique across sessions so each failure is reported once. */
data class CommandError(val message: String, val serial: Long = nextErrorSerial.incrementAndGet())
private val nextErrorSerial = java.util.concurrent.atomic.AtomicLong()

/** Remembers the last failed command so it can be retried against the live entity. */
internal class FailedCommand {
    @Volatile var entity: Entity? = null; @Volatile var command: Command? = null
    fun set(e: Entity, c: Command) { entity = e; command = c }
    fun retry(entities: List<Entity>, connected: Boolean, send: (Entity, Command) -> Unit) {
        val e = entity ?: return; val c = command ?: return
        val live = entities.firstOrNull { sameEntity(it, e) } ?: return
        if (isCommandReady(connected, live)) send(live, c)
    }
}

const val MAX_LOG_LINES = 300

fun appendLog(logs: List<String>, line: String): List<String> = (logs + line).takeLast(MAX_LOG_LINES)

const val STATE_LOG_PREFIX = "[state] "

/** A log-view line for a state update, like the web UI's live state stream; devices do not log these at DEBUG. */
fun stateLogLine(entity: Entity): String = "$STATE_LOG_PREFIX${entity.name}: ${displayValue(entity) ?: "unknown"}"

val ControlSession?.isConnected: Boolean get() = this?.connection is ConnectionState.Connected

/** Native API session over plaintext or Noise framing. Encryption is never downgraded. */
class DeviceSession(private val host: String, private val port: Int, private val key: ByteArray?) : ControlSession {
    override var connection by mutableStateOf<ConnectionState>(ConnectionState.Connecting); private set
    override var entities by mutableStateOf<List<Entity>>(emptyList()); private set
    override var details by mutableStateOf<DeviceDetails?>(null); private set
    override var lastError by mutableStateOf<CommandError?>(null); private set
    private val failed = FailedCommand()
    override var logs by mutableStateOf<List<String>>(emptyList()); private set
    @Volatile private var logsWanted = false
    private val ui = Handler(Looper.getMainLooper())
    /** Serialises writes (commands and keepalive pings) off the main thread. */
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "esphome-writer") }
    @Volatile private var socket: Socket? = null
    @Volatile private var frames: FrameConnection? = null
    @Volatile private var closed = false
    @Volatile private var lastReceived = 0L
    init { Thread({ run() }, "esphome-session").start() }

    private fun publish(block: () -> Unit) { ui.post { if (!closed) block() } }

    private fun run() {
        try {
            val s = Socket(); socket = s
            if (closed) { s.close(); return }
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            s.soTimeout = READ_TIMEOUT_MS
            val f = if (key != null) NoiseFrames(s.getInputStream(), s.getOutputStream(), key) else PlaintextConnection(s.getInputStream(), s.getOutputStream())
            frames = f; key?.fill(0)
            send(MessageType.HELLO_REQUEST, Api.HelloRequest.newBuilder().setClientInfo("ESPHome Controller").setApiVersionMajor(1).setApiVersionMinor(19).build().toByteArray())
            // Required by firmware before 2026.1; newer firmware ignores it.
            send(MessageType.AUTHENTICATION_REQUEST, Api.AuthenticationRequest.getDefaultInstance().toByteArray())
            while (!closed) {
                val frame = f.read()
                lastReceived = System.nanoTime()
                when (val event = decodeNativeFrame(frame)) {
                    is NativeEvent.Hello -> {
                        if (event.apiMajor != 1) throw IOException("Unsupported API version ${event.apiMajor}.${event.apiMinor}")
                        send(MessageType.DEVICE_INFO_REQUEST, byteArrayOf())
                        send(MessageType.LIST_ENTITIES_REQUEST, byteArrayOf())
                        startKeepalive()
                        publish { connection = ConnectionState.Connected(if (key == null) Security.UNENCRYPTED else Security.ENCRYPTED) }
                    }
                    NativeEvent.AuthenticationRejected -> throw IOException("Device requires legacy password authentication, which is not supported")
                    NativeEvent.DisconnectRequested -> { send(MessageType.DISCONNECT_RESPONSE, byteArrayOf()); throw IOException("Device closed the connection") }
                    NativeEvent.PingRequested -> send(MessageType.PING_RESPONSE, byteArrayOf())
                    is NativeEvent.Info -> publish { details = event.details }
                    is NativeEvent.EntityListed -> publish { entities = entities.filterNot { sameEntity(it, event.entity) } + event.entity }
                    // Subscribe only after the entity list so that no initial state arrives before its entity.
                    NativeEvent.EntitiesDone -> send(MessageType.SUBSCRIBE_STATES_REQUEST, byteArrayOf())
                    is NativeEvent.StateChanged -> publish {
                        entities = applyState(entities, event)
                        if (logsWanted) entities.firstOrNull { it.webId == null && it.key == event.key && it.deviceId == event.deviceId }?.let { logs = appendLog(logs, stateLogLine(it)) }
                    }
                    is NativeEvent.Log -> if (logsWanted) publish { logs = appendLog(logs, event.text) }
                    NativeEvent.Ignored -> Unit
                }
            }
        } catch (e: Exception) {
            val reason = if (e is java.net.SocketTimeoutException) "Device stopped responding" else e.message ?: e.javaClass.simpleName
            publish { connection = ConnectionState.Failed(reason, needsSetup = e is SetupException) }
        } finally {
            key?.fill(0)
            try { frames?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            frames = null
            worker.shutdownNow()
        }
    }

    /** Ping when idle so that dead links are detected well within the read timeout. */
    private fun startKeepalive() {
        worker.scheduleWithFixedDelay({
            if (System.nanoTime() - lastReceived > TimeUnit.MILLISECONDS.toNanos(PING_INTERVAL_MS)) {
                try { send(MessageType.PING_REQUEST, byteArrayOf()) } catch (_: Exception) { try { socket?.close() } catch (_: Exception) {} }
            }
        }, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun send(type: Int, payload: ByteArray) { (frames ?: throw IOException("Not connected")).write(ApiFrame(type, payload)) }

    override fun send(entity: Entity, command: Command) {
        val frame = nativeCommand(entity, command) ?: return
        try {
            worker.execute {
                try { send(frame.type, frame.payload); publish { lastError = null } }
                catch (e: Exception) { failed.set(entity, command); publish { lastError = CommandError("${entity.name}: command failed (${e.message ?: e.javaClass.simpleName})") } }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { failed.set(entity, command); lastError = CommandError("${entity.name}: not connected") }
    }

    override fun retryFailed() = failed.retry(entities, connection is ConnectionState.Connected, ::send)

    override fun subscribeLogs() {
        if (logsWanted) return
        logsWanted = true
        try {
            worker.execute { try { subscribeLogsFrame().let { send(it.type, it.payload) } } catch (e: Exception) { logsWanted = false; publish { lastError = CommandError("Could not subscribe to logs: ${e.message}") } } }
        } catch (_: java.util.concurrent.RejectedExecutionException) { logsWanted = false }
    }

    override fun close() {
        closed = true; key?.fill(0)
        try { socket?.close() } catch (_: Exception) {}
        worker.shutdownNow()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 60_000
        const val PING_INTERVAL_MS = 20_000L
    }
}

fun applyState(entities: List<Entity>, event: NativeEvent.StateChanged): List<Entity> =
    entities.map { if (it.webId == null && it.key == event.key && it.deviceId == event.deviceId) it.copy(state = event.state) else it }
