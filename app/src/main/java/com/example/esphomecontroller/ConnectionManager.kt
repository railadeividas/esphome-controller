package com.example.esphomecontroller

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.esphomecontroller.api.ConnectionState
import com.example.esphomecontroller.api.ControlSession
import com.example.esphomecontroller.api.DeviceSession
import com.example.esphomecontroller.api.Entity
import com.example.esphomecontroller.api.SecretStore
import com.example.esphomecontroller.api.WebSession
import com.example.esphomecontroller.api.mergeCachedEntities
import com.example.esphomecontroller.api.DeviceDetails
import com.example.esphomecontroller.api.sameEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.random.Random

/**
 * Owns device sessions for all of the app's devices. Each device connects, fails and retries
 * independently, so a change to one device never interrupts the others. Lives in a
 * ViewModel so sessions survive activity recreation; entity state is kept in memory only.
 */
class ConnectionManager(app: Application) : AndroidViewModel(app) {
    val secrets = SecretStore(app)
    val sessions = mutableStateMapOf<String, ControlSession>()
    /** Problems that need the user (missing key, bad address); not retried until settings change. */
    val setupErrors = mutableStateMapOf<String, String>()
    /** Last known entities per device id: metadata survives restarts (see [ControlCache]); states stay in memory. */
    private val cache = mutableStateMapOf<String, List<Entity>>()
    private val cachedDetails = mutableStateMapOf<String, DeviceDetails>()
    private val disk = ControlCache(java.io.File(app.noBackupFilesDir, "control-cache.json"))
    /** What was last written, without states, to avoid rewriting the file on every state update. */
    private var saved: Map<String, ControlCache.Entry> = emptyMap()
    private var saveJob: Job? = null
    private var pendingSave: Map<String, ControlCache.Entry>? = null
    /** Incremented when a device that follows discovery cannot be reached, so the UI can scan for its new address. */
    var discoveryRequests by mutableIntStateOf(0); private set
    private var lastDiscoveryRequest = 0L
    /** Wall-clock time each device was last seen connected, in memory only. */
    private val lastSeen = mutableMapOf<String, Long>()
    fun lastSeen(deviceId: String): Long? = lastSeen[deviceId]

    private data class Target(val device: LocationStore.Device)
    private val targets = mutableMapOf<String, Target>()
    private val attempts = mutableMapOf<String, Int>()
    private val nextAttempt = mutableMapOf<String, Long>()

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private var currentNetwork: Network? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { viewModelScope.launch { onNetworkAvailable(network) } }
    }

    init {
        try { connectivity?.registerDefaultNetworkCallback(networkCallback) } catch (_: Exception) { /* Retries still recover without callbacks. */ }
        viewModelScope.launch { retryLoop() }
        viewModelScope.launch {
            saved = withContext(Dispatchers.IO) { disk.load() }
            saved.forEach { (key, entry) ->
                if (key !in cache && entry.entities.isNotEmpty()) cache[key] = entry.entities
                entry.details?.let { if (key !in cachedDetails) cachedDetails[key] = it }
            }
            snapshotFlow { sessions.mapValues { Triple(it.value.entities, it.value.entitiesComplete, it.value.details) } }.collect { snapshots ->
                snapshots.forEach { (id, snapshot) ->
                    val (entities, complete, details) = snapshot
                    val target = targets[id] ?: return@forEach
                    val key = id
                    val cached = cache[key].orEmpty()
                    // A complete listing replaces the saved controls so removed entities disappear; otherwise only add.
                    if (entities.isNotEmpty()) cache[key] = mergeCachedEntities(if (complete) cached.filter { c -> entities.any { sameEntity(c, it) } } else cached, entities)
                    details?.let { cachedDetails[key] = it }
                }
                scheduleSave()
            }
        }
    }


    /** Last known entities (for display) merged with the live ones. */
    fun entities(deviceId: String): List<Entity> =
        mergeCachedEntities(cache[deviceId].orEmpty(), sessions[deviceId]?.entities.orEmpty())

    /** Device details from the live session, or the saved copy while offline. */
    fun details(deviceId: String): DeviceDetails? = sessions[deviceId]?.details ?: cachedDetails[deviceId]

    fun forget(deviceId: String) {
        cache.remove(deviceId); cachedDetails.remove(deviceId)
        scheduleSave()
    }

    /** Writes changed metadata (never states) shortly after it changes. */
    private fun scheduleSave() {
        val entries = (cache.keys + cachedDetails.keys).associateWith { key ->
            ControlCache.Entry(cache[key].orEmpty().map { it.copy(state = null) }, cachedDetails[key])
        }
        // State updates arrive every second on some devices; only metadata changes restart the delay.
        if (entries == saved || entries == pendingSave) return
        pendingSave = entries
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1000)
            withContext(Dispatchers.IO) { try { disk.save(entries) } catch (_: Exception) { /* The cache is only a convenience. */ } }
            saved = entries; pendingSave = null
        }
    }

    /** Reconciles sessions with the app's devices. Only devices whose connection settings changed are restarted. */
    fun sync(devices: List<LocationStore.Device>, active: Boolean) {
        val wanted = if (active) devices.associateBy { it.id } else emptyMap()
        for ((id, target) in targets.toList()) {
            val device = wanted[id]
            if (device == null || !sameConnection(device, target.device)) stop(id)
            else targets[id] = target.copy(device = device)
        }
        for ((id, device) in wanted) if (id !in targets) { targets[id] = Target(device); setupErrors.remove(id) }
        connectDue()
    }

    /** Forces a fresh connection, for example after a key or password change. */
    fun reconnect(deviceId: String) {
        val target = targets[deviceId] ?: return
        stop(deviceId); targets[deviceId] = target; setupErrors.remove(deviceId)
        connectDue()
    }

    private fun stop(id: String) {
        targets.remove(id); sessions.remove(id)?.close(); setupErrors.remove(id)
        attempts.remove(id); nextAttempt.remove(id)
    }

    private fun sameConnection(a: LocationStore.Device, b: LocationStore.Device) = a.address == b.address && a.mode == b.mode

    private fun onNetworkAvailable(network: Network) {
        val changed = currentNetwork != null && currentNetwork != network
        currentNetwork = network
        for (id in targets.keys) {
            // Sockets opened on a previous network are unusable; failed ones may succeed now.
            if (changed || sessions[id]?.connection is ConnectionState.Failed) { sessions.remove(id)?.close(); attempts.remove(id); nextAttempt.remove(id) }
        }
        connectDue()
    }

    private suspend fun retryLoop() {
        while (true) { connectDue(); delay(1000) }
    }

    private fun connectDue() {
        val now = SystemClock.elapsedRealtime()
        for ((id, target) in targets) {
            val session = sessions[id]
            (session?.connection as? ConnectionState.Failed)?.takeIf { it.needsSetup }?.let { failed ->
                // A wrong key or encryption mismatch will not fix itself; stop retrying until the settings change.
                setupErrors[id] = failed.reason; session.close(); sessions.remove(id); return@let
            }
            if (id in setupErrors) continue
            when (session?.connection) {
                is ConnectionState.Connected -> { attempts.remove(id); lastSeen[id] = System.currentTimeMillis(); continue }
                ConnectionState.Connecting -> continue
                else -> Unit
            }
            if (id in setupErrors || now < (nextAttempt[id] ?: 0)) continue
            if (session?.connection is ConnectionState.Failed && target.device.followDiscovery && now - lastDiscoveryRequest > 60_000L) {
                lastDiscoveryRequest = now; discoveryRequests++
            }
            session?.close(); sessions.remove(id)
            val replacement = when (val result = createSession(target.device)) {
                is SessionResult.Ready -> result.session
                is SessionResult.NeedsSetup -> { setupErrors[id] = result.message; continue }
            }
            sessions[id] = replacement
            val attempt = (attempts[id] ?: 0).coerceAtMost(5)
            attempts[id] = attempt + 1
            nextAttempt[id] = now + (5000L shl attempt).coerceAtMost(120_000L) + Random.nextLong(0, 2000)
        }
    }

    private sealed interface SessionResult {
        data class Ready(val session: ControlSession) : SessionResult
        data class NeedsSetup(val message: String) : SessionResult
    }

    private fun createSession(device: LocationStore.Device): SessionResult {
        val (host, port) = splitAddress(device.address)
        val endpoint = parseEndpoint(host, port) ?: return SessionResult.NeedsSetup("Check the device address in settings")
        return try {
            when (device.mode) {
                "web" -> SessionResult.Ready(WebSession(endpoint.host, endpoint.port, null, null))
                "web_auth" -> {
                    val bytes = secrets.load("web:${device.id}") ?: return SessionResult.NeedsSetup("Finish setup to connect")
                    val credentials = try { JSONObject(String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) }
                    SessionResult.Ready(WebSession(endpoint.host, endpoint.port, credentials.getString("username"), credentials.getString("password")))
                }
                "plain" -> SessionResult.Ready(DeviceSession(endpoint.host, endpoint.port, null))
                else -> {
                    // Legacy devices without a mode must not silently connect without encryption.
                    val raw = secrets.load(device.id) ?: return SessionResult.NeedsSetup("Finish setup to connect")
                    if (Build.VERSION.SDK_INT < 33) { raw.fill(0); return SessionResult.NeedsSetup("Encrypted connections need Android 13 or newer") }
                    SessionResult.Ready(DeviceSession(endpoint.host, endpoint.port, raw))
                }
            }
        } catch (_: Exception) { SessionResult.NeedsSetup("Check the device connection settings") }
    }

    override fun onCleared() {
        try { connectivity?.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        sessions.values.forEach { it.close() }; sessions.clear(); targets.clear()
    }
}
