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
    /** True once the device has listed all of its entities on this connection, so missing ones were removed. */
    val entitiesComplete: Boolean
    val details: DeviceDetails?
    /** The last command failure; does not by itself mean the connection is down. */
    val lastError: CommandError?
    fun send(entity: Entity, command: Command)
    /** Re-sends the command behind [lastError] to the current copy of its entity, if it is still ready. */
    fun retryFailed()
    /** Recent device log lines, newest last; kept in memory only and only after [subscribeLogs]. */
    val logs: List<LogLine>
    /**
     * Starts receiving device logs on this connection, up to [level] where the interface supports choosing it (the Native API does; the web
     * server's event stream always carries what the device logs). Calling it again with another level changes the level. Logs may contain
     * network details; never persist them.
     */
    fun subscribeLogs(level: LogLevel = LogLevel.VERY_VERBOSE)
    /** Empties [logs]; receiving continues. */
    fun clearLogs()
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

const val MAX_LOG_LINES = 1000

fun <T> appendLog(logs: List<T>, line: T): List<T> = if (logs.size < MAX_LOG_LINES) logs + line else logs.subList(logs.size - MAX_LOG_LINES + 1, logs.size) + line

val ControlSession?.isConnected: Boolean get() = this?.connection is ConnectionState.Connected

/** Native API session over plaintext or Noise framing. Encryption is never downgraded. */
class DeviceSession(private val host: String, private val port: Int, private val key: ByteArray?) : ControlSession {
    override var connection by mutableStateOf<ConnectionState>(ConnectionState.Connecting); private set
    override var entities by mutableStateOf<List<Entity>>(emptyList()); private set
    override var details by mutableStateOf<DeviceDetails?>(null); private set
    override var entitiesComplete by mutableStateOf(false); private set
    override var lastError by mutableStateOf<CommandError?>(null); private set
    private val failed = FailedCommand()
    override var logs by mutableStateOf<List<LogLine>>(emptyList()); private set
    /** The level logs were requested at; null until [subscribeLogs]. */
    @Volatile private var logLevel: LogLevel? = null
    private val logsWanted get() = logLevel != null
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
                    NativeEvent.EntitiesDone -> { send(MessageType.SUBSCRIBE_STATES_REQUEST, byteArrayOf()); publish { entitiesComplete = true } }
                    is NativeEvent.StateChanged -> publish {
                        entities = applyState(entities, event)
                        if (logsWanted) entities.firstOrNull { it.webId == null && it.key == event.key && it.deviceId == event.deviceId }?.let { logs = appendLog(logs, stateLogLine(it)) }
                    }
                    is NativeEvent.Log -> if (logsWanted) { val line = parseLogLine(event.text, levelHint = event.level); publish { logs = appendLog(logs, line) } }
                    NativeEvent.Ignored -> Unit
                }
            }
        } catch (e: Exception) {
            val reason = failureReason(e)
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

    override fun subscribeLogs(level: LogLevel) {
        if (logLevel == level) return
        logLevel = level
        try {
            worker.execute { try { subscribeLogsFrame(level.protoValue).let { send(it.type, it.payload) } } catch (e: Exception) { logLevel = null; publish { lastError = CommandError("Could not subscribe to logs: ${e.message}") } } }
        } catch (_: java.util.concurrent.RejectedExecutionException) { logLevel = null }
    }

    override fun clearLogs() { logs = emptyList() }

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

/** A short, user-facing reason for a connection failure, without addresses or stack details. */
fun failureReason(e: Exception): String = when (e) {
    is SetupException -> e.message ?: "Check the connection settings"
    is java.net.SocketTimeoutException -> "Device stopped responding"
    is java.net.NoRouteToHostException -> "Device is not reachable from this network"
    is java.net.UnknownHostException -> "The device address could not be found"
    is java.net.ConnectException -> if (e.message.orEmpty().contains("ECONNREFUSED") || e.message.orEmpty().contains("refused", ignoreCase = true)) "The device refused the connection" else "Could not connect to the device"
    is java.io.EOFException -> "The device closed the connection"
    is java.net.SocketException -> "The connection was interrupted"
    is com.google.protobuf.InvalidProtocolBufferException -> "The device sent an unreadable message"
    else -> e.message?.takeIf { it.length <= 80 && !Regex("""\d{1,3}(\.\d{1,3}){3}""").containsMatchIn(it) } ?: "Connection failed"
}

fun applyState(entities: List<Entity>, event: NativeEvent.StateChanged): List<Entity> =
    entities.map { if (it.webId == null && it.key == event.key && it.deviceId == event.deviceId) it.copy(state = event.state) else it }
