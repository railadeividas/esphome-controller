package com.example.esphomecontroller.api

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.burgstaller.okhttp.DispatchingAuthenticator
import com.burgstaller.okhttp.basic.BasicAuthenticator
import com.burgstaller.okhttp.digest.Credentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** HTTP web-server interface, opt-in only; it never falls back from the native API. */
class WebSession(host: String, port: Int, username: String?, password: String?) : ControlSession {
    override var connection by mutableStateOf<ConnectionState>(ConnectionState.Connecting); private set
    override var entities by mutableStateOf<List<Entity>>(emptyList()); private set
    override val details: DeviceDetails? = null
    override var lastError by mutableStateOf<String?>(null); private set
    override var logs by mutableStateOf<List<String>>(emptyList()); private set
    /** The event stream always carries logs; they are only kept once requested. */
    @Volatile private var logsWanted = false
    override fun subscribeLogs() { logsWanted = true }
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val base = HttpUrl.Builder().scheme("http").host(host).port(port).build()
    private val client = createWebClient(username, password)
    @Volatile private var stream: Call? = null
    @Volatile private var closed = false
    private var nextKey = 0
    private val pendingDetails = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val detailsKnown = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    init { Thread({ listen() }, "esphome-web-events").start() }
    private fun publish(block: () -> Unit) { main.post { if (!closed) block() } }
    private fun listen() {
        try {
            val call = client.newCall(Request.Builder().url(base.newBuilder().addPathSegment("events").build()).header("Accept", "text/event-stream").build())
            stream = call
            if (closed) { call.cancel(); return }
            call.execute().use { response ->
                if (response.code == 401) throw CredentialsRejectedException()
                if (!response.isSuccessful) throw IOException("Web server returned HTTP ${response.code}")
                if (!(response.header("Content-Type") ?: "").startsWith("text/event-stream", ignoreCase = true)) throw IOException("Web server did not provide events")
                val source = response.body?.source() ?: throw IOException("Empty web server response")
                publish { connection = ConnectionState.Connected(Security.WEB) }
                consumeWebEvents(source, { closed }, onLog = { line -> if (logsWanted) publish { logs = appendLog(logs, stripAnsi(line)) } }, onEvent = ::handle)
                if (!closed) throw IOException("Web server disconnected")
            }
        } catch (e: Exception) { publish { connection = ConnectionState.Failed(e.message ?: "Web server unavailable", needsSetup = e is SetupException) } }
    }
    private fun handle(text: String) {
        val decoded = decodeWebEvent(text) ?: return
        val id = decoded.id
        if (decoded.hidden) { publish { entities = entities.filterNot { it.webId == id } }; return }
        if (!decoded.needsDetail) detailsKnown.add(id)
        publish {
            val old = entities.firstOrNull { it.webId == id }
            // Ranges and options arrive only with detail=all; keep them across later state-only events.
            val platform = if (decoded.needsDetail) old?.platform?.takeIf { it::class == decoded.platform::class } ?: decoded.platform else decoded.platform
            val entity = Entity(old?.key ?: ++nextKey, 0, decoded.name, platform, webId = id, state = decoded.state)
            entities = if (old == null) entities + entity else entities.map { if (it.webId == id) entity else it }
            if (logsWanted && entity.state != null) logs = appendLog(logs, stateLogLine(entity))
        }
        if (decoded.needsDetail && id !in detailsKnown && pendingDetails.add(id)) fetchDetail(id)
    }
    private fun fetchDetail(id: String) {
        val parts = parseWebId(id) ?: return
        executor.execute {
            try {
                val builder = base.newBuilder(); parts.forEach { builder.addPathSegment(it) }; builder.addQueryParameter("detail", "all")
                val call = client.newCall(Request.Builder().url(builder.build()).get().build())
                call.timeout().timeout(8, TimeUnit.SECONDS)
                call.execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val source = response.body?.source() ?: return@use
                    source.request(65537)
                    if (source.buffer.size > 65536) return@use
                    val detail = decodeWebEvent(source.readUtf8())?.takeIf { !it.needsDetail } ?: return@use
                    detailsKnown.add(id)
                    publish { entities = entities.map { if (it.webId == id && it.platform::class == detail.platform::class) it.copy(platform = detail.platform) else it } }
                }
            } catch (_: Exception) { /* The next state event may retry metadata retrieval. */ }
            finally { pendingDetails.remove(id) }
        }
    }
    override fun send(entity: Entity, command: Command) {
        val request = createWebRequest(base, entity, command) ?: return
        try {
            executor.execute {
                try {
                    client.newCall(request).execute().use { if (!it.isSuccessful) throw IOException("HTTP ${it.code}") }
                    publish { lastError = null }
                } catch (e: Exception) { publish { lastError = "Command failed: ${e.message ?: "network error"}" } }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { lastError = "Command failed: not connected" }
    }
    override fun close() { closed = true; stream?.cancel(); client.dispatcher.cancelAll(); executor.shutdownNow() }
}

/** Modern IDs use slashes; older firmware uses domain-object_id. */
fun parseWebId(id: String): List<String>? {
    val parts = if ('/' in id) id.split('/') else id.indexOf('-').let { i -> if (i > 0) listOf(id.substring(0, i), id.substring(i + 1)) else emptyList() }
    return parts.takeIf { it.size in 2..3 && it.all(String::isNotBlank) && it.none { part -> part == "." || part == ".." } }
}

/** Credentials are only provided after a server challenge; redirects stay disabled. */
fun createWebClient(username: String?, password: String?): OkHttpClient =
    OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).apply {
            if (username != null && password != null) {
                val credentials = Credentials(username, password)
                authenticator(DispatchingAuthenticator.Builder().with("basic", BasicAuthenticator(credentials)).with("digest", DigestAuthenticator(credentials)).build())
            }
        }.build()

data class WebSnapshot(val id: String, val name: String, val platform: Platform, val state: EntityState?, val options: List<String>?, val hidden: Boolean = false, val needsDetail: Boolean = false)

private fun JSONObject.float(key: String): Float? = if (!has(key)) null else (opt(key) as? Number)?.toFloat() ?: optString(key).trim().toFloatOrNull()

private fun operation(obj: JSONObject) = when (obj.optString("current_operation").uppercase()) { "OPENING" -> Operation.OPENING; "CLOSING" -> Operation.CLOSING; else -> Operation.IDLE }

fun decodeWebEvent(text: String): WebSnapshot? {
    val obj = try { JSONObject(text) } catch (_: Exception) { return null }
    val id = obj.optString("name_id").ifBlank { obj.optString("id") }
    val parsed = parseWebId(id) ?: return null
    val domain = parsed.first()
    val name = obj.optString("name").ifBlank { parsed.last() }
    val options = obj.optJSONArray("option")?.let { array -> (0 until array.length()).mapNotNull { i -> array.opt(i) as? String } }
    if (obj.optBoolean("is_disabled_by_default", false)) return WebSnapshot(id, name, Platform.Unsupported(domain), null, null, hidden = true)
    fun onOff(): Boolean? = when (val v = obj.opt("value")) { is Boolean -> v; else -> when (obj.optString("state", obj.optString("value")).uppercase()) { "ON", "TRUE" -> true; "OFF", "FALSE" -> false; else -> null } }
    // Sensor states are already formatted by the device, including the unit.
    val text = obj.optString("state").takeIf { it.isNotBlank() }
    var needsDetail = false
    val platform: Platform; val state: EntityState?
    when (domain) {
        "light" -> {
            val brightness = if (obj.has("brightness")) (obj.optDouble("brightness", Double.NaN) / 255.0).toFloat().takeIf { it.isFinite() && it in 0f..1f } else null
            platform = Platform.Light(brightness != null); state = onOff()?.let { EntityState.Light(it, brightness) } ?: EntityState.Missing
        }
        "switch" -> { platform = Platform.Switch; state = onOff()?.let { EntityState.OnOff(it) } ?: EntityState.Missing }
        "binary_sensor" -> { platform = Platform.BinarySensor; state = onOff()?.let { EntityState.OnOff(it) } ?: EntityState.Missing }
        "sensor" -> { platform = Platform.Sensor(); state = text?.let { EntityState.Text(it) } ?: EntityState.Missing }
        "text_sensor" -> { platform = Platform.TextSensor; state = text?.let { EntityState.Text(it) } ?: EntityState.Missing }
        "select" -> { platform = Platform.Select(options.orEmpty()); state = text?.let { EntityState.Text(it) } ?: EntityState.Missing; needsDetail = options == null }
        "number" -> {
            val min = obj.float("min_value"); val max = obj.float("max_value"); val step = obj.float("step")
            needsDetail = min == null || max == null
            val mode = when (obj.optInt("mode", 0)) { 1 -> NumberMode.BOX; 2 -> NumberMode.SLIDER; else -> NumberMode.AUTO }
            platform = Platform.Number(min ?: 0f, max ?: 0f, step ?: 0f, obj.optString("uom"), mode)
            state = obj.float("value")?.takeIf { it.isFinite() }?.let { EntityState.Numeric(it) } ?: EntityState.Missing
        }
        "button" -> { platform = Platform.Button; state = null }
        "cover", "valve" -> {
            val position = obj.float("position") ?: if (obj.optString("state").uppercase() == "CLOSED") 0f else 1f
            val tilt = obj.float("tilt")
            platform = if (domain == "cover") Platform.Cover(obj.has("position"), tilt != null, true) else Platform.Valve(obj.has("position"), true)
            state = EntityState.Positioned(position.coerceIn(0f, 1f), tilt?.coerceIn(0f, 1f), operation(obj))
        }
        "fan" -> {
            platform = Platform.Fan(obj.has("oscillation"), false, if (obj.has("speed_level")) obj.optInt("speed_count", 0).coerceAtLeast(0) else 0)
            state = onOff()?.let { EntityState.Fan(it, obj.optBoolean("oscillation", false), false, obj.optInt("speed_level", 0), null) } ?: EntityState.Missing
        }
        "lock" -> {
            platform = Platform.Lock(supportsOpen = false, requiresCode = false)
            val byName = LockStatus.entries.firstOrNull { it.name == obj.optString("state").uppercase() }
            val byValue = (obj.opt("value") as? Number)?.toInt()?.let { LockStatus.entries.getOrNull(it) }
            state = EntityState.Lock(byName ?: byValue ?: LockStatus.UNKNOWN)
        }
        else -> { platform = Platform.Unsupported(domain.replace('_', ' ').replaceFirstChar { it.uppercase() }); state = null }
    }
    return WebSnapshot(id, name, platform, state, options, needsDetail = needsDetail)
}

private val webActions = mapOf(
    "light" to setOf("turn_on", "turn_off"), "switch" to setOf("turn_on", "turn_off"), "fan" to setOf("turn_on", "turn_off"),
    "select" to setOf("set"), "number" to setOf("set"), "button" to setOf("press"),
    "cover" to setOf("open", "close", "stop", "set"), "valve" to setOf("open", "close", "stop", "set"), "lock" to setOf("lock", "unlock", "open"),
)

/** Builds a POST for an action the web server implements for this domain; null otherwise. */
fun createWebAction(base: HttpUrl, id: String, action: String, params: Map<String, String> = emptyMap()): Request? {
    val path = parseWebId(id) ?: return null
    if (action !in webActions[path.first()].orEmpty()) return null
    val builder = base.newBuilder(); path.forEach { builder.addPathSegment(it) }; builder.addPathSegment(action)
    params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
    return Request.Builder().url(builder.build()).post(ByteArray(0).toRequestBody()).build()
}

private fun fraction(v: Float) = String.format(java.util.Locale.ROOT, "%.3f", v)

/** Maps a command to the web server's REST action, respecting the entity's advertised capabilities. */
fun createWebRequest(base: HttpUrl, entity: Entity, command: Command): Request? {
    val id = entity.webId ?: return null
    val p = entity.platform
    return when {
        command is Command.SetOn && (p is Platform.Light || p == Platform.Switch || p is Platform.Fan) -> createWebAction(base, id, if (command.on) "turn_on" else "turn_off")
        command is Command.SetBrightness && p is Platform.Light -> createWebCommand(base, id, "turn_on", (command.brightness.coerceIn(0f, 1f) * 255).toInt())
        command is Command.SelectOption && p is Platform.Select && command.option in p.options -> createWebSelectCommand(base, id, command.option)
        command is Command.SetNumber && p is Platform.Number && p.max > p.min && command.value in p.min..p.max -> createWebAction(base, id, "set", mapOf("value" to command.value.toBigDecimal().stripTrailingZeros().toPlainString()))
        command == Command.Press && p == Platform.Button -> createWebAction(base, id, "press")
        p is Platform.Cover || p is Platform.Valve -> {
            val position = (p as? Platform.Cover)?.supportsPosition ?: (p as Platform.Valve).supportsPosition
            when {
                command == Command.Open -> createWebAction(base, id, "open")
                command == Command.Close -> createWebAction(base, id, "close")
                command == Command.Stop -> createWebAction(base, id, "stop")
                command is Command.SetPosition && position && command.position in 0f..1f -> createWebAction(base, id, "set", mapOf("position" to fraction(command.position)))
                command is Command.SetTilt && (p as? Platform.Cover)?.supportsTilt == true && command.tilt in 0f..1f -> createWebAction(base, id, "set", mapOf("tilt" to fraction(command.tilt)))
                else -> null
            }
        }
        p is Platform.Fan && command is Command.SetFanSpeed && command.level in 1..p.speedCount -> createWebAction(base, id, "turn_on", mapOf("speed_level" to command.level.toString()))
        // The handler accepts oscillation on both turn_on and turn_off; keep the current power state.
        p is Platform.Fan && command is Command.SetOscillation && p.supportsOscillation ->
            createWebAction(base, id, if (isOn(entity.state) == true) "turn_on" else "turn_off", mapOf("oscillation" to if (command.on) "on" else "off"))
        p is Platform.Lock && command is Command.Lock && command.code.isNullOrEmpty() && (command.action != LockAction.OPEN || p.supportsOpen) ->
            createWebAction(base, id, command.action.name.lowercase())
        else -> null
    }
}

fun createWebCommand(base: HttpUrl, id: String, action: String, brightness: Int?): Request? {
    val path = parseWebId(id) ?: return null
    if (path.first() !in listOf("light", "switch") || action !in listOf("turn_on", "turn_off")) return null
    if (brightness != null && (path.first() != "light" || action != "turn_on" || brightness !in 0..255)) return null
    val builder = base.newBuilder(); path.forEach { builder.addPathSegment(it) }; builder.addPathSegment(action)
    if (brightness != null) builder.addQueryParameter("brightness", brightness.toString())
    return Request.Builder().url(builder.build()).post(ByteArray(0).toRequestBody()).build()
}

/** Parse SSE records incrementally; logs and pings are deliberately ignored. */
fun consumeWebEvents(source: BufferedSource, isClosed: () -> Boolean, onLog: (String) -> Unit = {}, onEvent: (String) -> Unit) {
    var event = ""; val data = StringBuilder()
    while (!isClosed()) {
        val line = source.readUtf8LineStrict(65536)
        when {
            line.isEmpty() -> {
                if ((event == "state" || event == "all") && data.isNotEmpty()) onEvent(data.toString())
                else if (event == "log" && data.isNotEmpty()) onLog(data.toString())
                event = ""; data.clear()
            }
            line.startsWith("event:") -> event = line.substringAfter(':').trim()
            line.startsWith("data:") -> {
                if (data.length + line.length > 65536) throw IOException("Web server event too large")
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substringAfter(':').trimStart())
            }
        }
    }
}

fun createWebSelectCommand(base: HttpUrl, id: String, option: String): Request? {
    val path = parseWebId(id) ?: return null
    if (path.first() != "select") return null
    val builder = base.newBuilder(); path.forEach { builder.addPathSegment(it) }
    builder.addPathSegment("set").addQueryParameter("option", option)
    return Request.Builder().url(builder.build()).post(ByteArray(0).toRequestBody()).build()
}
