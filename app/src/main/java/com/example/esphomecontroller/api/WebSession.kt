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
    override var status by mutableStateOf("Connecting…"); private set
    override var entities by mutableStateOf<List<DeviceSession.Entity>>(emptyList()); private set
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val base = HttpUrl.Builder().scheme("http").host(host).port(port).build()
    private val client = createWebClient(username, password)
    @Volatile private var stream: Call? = null
    @Volatile private var closed = false
    private var nextKey = 0
    private val hiddenIds = mutableSetOf<String>()
    private val pendingDetails = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val optionsKnown = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    init { Thread({ listen() }, "esphome-web-events").start() }
    private fun publish(block: () -> Unit) { main.post { if (!closed) block() } }
    private fun listen() {
        try {
            val call = client.newCall(Request.Builder().url(base.newBuilder().addPathSegment("events").build()).header("Accept", "text/event-stream").build())
            stream = call
            if (closed) { call.cancel(); return }
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException(if (response.code == 401) "Web server rejected credentials" else "Web server returned HTTP ${response.code}")
                if (!(response.header("Content-Type") ?: "").startsWith("text/event-stream", ignoreCase = true)) throw IOException("Web server did not provide events")
                val source = response.body?.source() ?: throw IOException("Empty web server response")
                publish { status = "Connected via web server" }
                consumeWebEvents(source, { closed }, ::handle)
                if (!closed) throw IOException("Web server disconnected")
            }
        } catch (e: Exception) { publish { status = "Connection failed: ${e.message ?: "web server unavailable"}" } }
    }
    private fun handle(text: String) {
        val raw = try { JSONObject(text) } catch (_: Exception) { return }
        val id = raw.optString("name_id").ifBlank { raw.optString("id") }
        if (raw.optBoolean("is_disabled_by_default", false)) { hiddenIds.add(id); publish { entities = entities.filterNot { it.webId == id } }; return }
        if (id in hiddenIds) return
        val decoded = decodeWebEvent(text) ?: return
        val (_, name, kind, bool, value, brightness, options) = decoded
        if (options != null) optionsKnown.add(id)
        publish {
            val old = entities.firstOrNull { it.webId == id }
            val entity = DeviceSession.Entity(old?.key ?: ++nextKey, 0, name, kind, state = bool, value = value, brightness = brightness, supportsBrightness = brightness != null, webId = id, receivedState = true, options = options ?: old?.options.orEmpty())
            entities = if (old == null) entities + entity else entities.map { if (it.webId == id) entity else it }
        }
        if (kind == "select" && options == null && id !in optionsKnown && pendingDetails.add(id)) fetchSelectOptions(id)
    }
    private fun fetchSelectOptions(id: String) {
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
                    val options = decodeWebEvent(source.readUtf8())?.options ?: return@use
                    optionsKnown.add(id)
                    publish { entities = entities.map { if (it.webId == id) it.copy(options = options) else it } }
                }
            } catch (_: Exception) { /* The next state event may retry metadata retrieval. */ }
            finally { pendingDetails.remove(id) }
        }
    }
    override fun toggle(e: DeviceSession.Entity, value: Boolean) = command(e, if (value) "turn_on" else "turn_off", null)
    override fun brightness(e: DeviceSession.Entity, value: Float) = command(e, "turn_on", (value.coerceIn(0f, 1f) * 255).toInt())
    override fun select(e: DeviceSession.Entity, option: String) {
        if (e.kind != "select" || option !in e.options) return
        val request = e.webId?.let { createWebSelectCommand(base, it, option) } ?: return
        executeCommand(request)
    }
    private fun command(e: DeviceSession.Entity, action: String, brightness: Int?) {
        val request = e.webId?.let { createWebCommand(base, it, action, brightness) } ?: return
        executeCommand(request)
    }
    private fun executeCommand(request: Request) {
        executor.execute {
            try {
                client.newCall(request).execute().use { if (!it.isSuccessful) throw IOException("HTTP ${it.code}") }
            } catch (e: Exception) { publish { status = "Connection failed: ${e.message ?: "network error"}" } }
        }
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

data class WebSnapshot(val id: String, val name: String, val kind: String, val state: Boolean?, val value: String?, val brightness: Float?, val options: List<String>?)

fun decodeWebEvent(text: String): WebSnapshot? {
    val obj = try { JSONObject(text) } catch (_: Exception) { return null }
    if (obj.optBoolean("is_disabled_by_default", false)) return null
    val id = obj.optString("name_id").ifBlank { obj.optString("id") }
    val parsed = parseWebId(id) ?: return null
    val kind = when (parsed.first()) { "light" -> "light"; "switch" -> "switch"; "sensor" -> "sensor"; "binary_sensor" -> "binary"; "text_sensor" -> "text"; "select" -> "select"; else -> return null }
    val name = obj.optString("name").ifBlank { parsed.last() }
    val state = if (kind == "light" || kind == "switch" || kind == "binary") when (obj.optString("state", obj.optString("value")).uppercase()) { "ON", "TRUE" -> true; "OFF", "FALSE" -> false; else -> null } else null
    val value = if (kind == "sensor" || kind == "text" || kind == "select") obj.optString("state").takeIf { it.isNotBlank() } else null
    val brightness = if (kind == "light" && obj.has("brightness")) (obj.optDouble("brightness", Double.NaN) / 255.0).toFloat().takeIf { it.isFinite() && it in 0f..1f } else null
    val options = obj.optJSONArray("option")?.let { array -> (0 until array.length()).mapNotNull { i -> array.opt(i) as? String } }
    return WebSnapshot(id, name, kind, state, value, brightness, options)
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
fun consumeWebEvents(source: BufferedSource, isClosed: () -> Boolean, onEvent: (String) -> Unit) {
    var event = ""; val data = StringBuilder()
    while (!isClosed()) {
        val line = source.readUtf8LineStrict(65536)
        when {
            line.isEmpty() -> { if ((event == "state" || event == "all") && data.isNotEmpty()) onEvent(data.toString()); event = ""; data.clear() }
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
