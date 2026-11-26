package com.example.esphomecontroller

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.random.Random

/**
 * Owns device sessions for the selected home. Each device connects, fails and retries
 * independently, so a change to one device never interrupts the others. Lives in a
 * ViewModel so sessions survive activity recreation; entity state is kept in memory only.
 */
class ConnectionManager(app: Application) : AndroidViewModel(app) {
    val secrets = SecretStore(app)
    val sessions = mutableStateMapOf<String, ControlSession>()
    /** Problems that need the user (missing key, bad address); not retried until settings change. */
    val setupErrors = mutableStateMapOf<String, String>()
    private val cache = mutableStateMapOf<String, List<Entity>>()

    private data class Target(val homeId: String, val device: HomeStore.Device)
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
            snapshotFlow { sessions.mapValues { it.value.entities } }.collect { snapshots ->
                snapshots.forEach { (id, entities) ->
                    val target = targets[id] ?: return@forEach
                    if (entities.isNotEmpty()) cache[cacheKey(target.homeId, id)] = mergeCachedEntities(cache[cacheKey(target.homeId, id)].orEmpty(), entities)
                }
            }
        }
    }

    private fun cacheKey(homeId: String, deviceId: String) = "$homeId/$deviceId"

    /** Last known entities (for display) merged with the live ones. */
    fun entities(homeId: String, deviceId: String): List<Entity> =
        mergeCachedEntities(cache[cacheKey(homeId, deviceId)].orEmpty(), sessions[deviceId]?.entities.orEmpty())

    fun forget(homeId: String, deviceId: String) { cache.remove(cacheKey(homeId, deviceId)) }

    /** Reconciles sessions with the selected home. Only devices whose connection settings changed are restarted. */
    fun sync(home: HomeStore.Home, active: Boolean) {
        val wanted = if (active) home.devices.associateBy { it.id } else emptyMap()
        for ((id, target) in targets.toList()) {
            val device = wanted[id]
            if (device == null || target.homeId != home.id || !sameConnection(device, target.device)) stop(id)
            else targets[id] = target.copy(device = device)
        }
        for ((id, device) in wanted) if (id !in targets) { targets[id] = Target(home.id, device); setupErrors.remove(id) }
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

    private fun sameConnection(a: HomeStore.Device, b: HomeStore.Device) = a.address == b.address && a.mode == b.mode

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
            when (session?.connection) {
                is ConnectionState.Connected -> { attempts.remove(id); continue }
                ConnectionState.Connecting -> continue
                else -> Unit
            }
            if (id in setupErrors || now < (nextAttempt[id] ?: 0)) continue
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

    private fun createSession(device: HomeStore.Device): SessionResult {
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
