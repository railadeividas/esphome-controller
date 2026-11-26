package com.example.esphomecontroller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val devices = mutableStateMapOf<String, String>()
    private var status by mutableStateOf("Discovery stopped")
    private lateinit var nsd: NsdManager
    private var listener: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private var generation = 0
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val found = mutableSetOf<String>()
    private var resolving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The system's three-button contrast scrim would otherwise obscure our themed background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val store = HomeStore(getSharedPreferences("homes", Context.MODE_PRIVATE))
        setContent { HomeScreen(store, devices, status, ::restart) }
    }

    override fun onStart() { super.onStart(); startDiscovery() }
    override fun onStop() { stopDiscovery(); super.onStop() }
    private fun restart() { stopDiscovery(); devices.keys.filterNot { it.startsWith("Manual: ") }.forEach { devices.remove(it) }; startDiscovery() }
    private fun stopDiscovery() {
        generation++
        pending.clear()
        found.clear()
        resolving = false
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: IllegalArgumentException) {} }
        listener = null
        lock?.let { if (it.isHeld) it.release() }; lock = null
        status = "Discovery stopped"
    }
    @Suppress("DEPRECATION")
    private fun startDiscovery() {
        val current = ++generation
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("esphome-discovery").apply { setReferenceCounted(false); acquire() }
            val discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) { runOnUiThread { if (generation == current) status = "Searching for devices…" } }
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, error: Int) { runOnUiThread { if (generation == current) status = "Discovery failed ($error). Check Wi-Fi and refresh." } }
                override fun onStopDiscoveryFailed(type: String, error: Int) {}
                override fun onServiceLost(service: NsdServiceInfo) { runOnUiThread {
                    if (generation == current) {
                        found.remove(service.serviceName)
                        pending.removeAll { it.serviceName == service.serviceName }
                        devices.remove(service.serviceName)
                    }
                } }
                override fun onServiceFound(service: NsdServiceInfo) { runOnUiThread {
                    if (generation == current && found.add(service.serviceName)) {
                        pending.addLast(service)
                        resolveNext(current)
                    }
                } }

            }
            listener = discovery
            nsd.discoverServices("_esphomelib._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) { status = "Discovery unavailable: ${e.localizedMessage}"; stopDiscovery(); status = "Discovery unavailable: ${e.localizedMessage}" }
    }
    @Suppress("DEPRECATION")
    private fun resolveNext(current: Int) {
        if (generation != current || resolving || pending.isEmpty()) return
        val service = pending.removeFirst()
        resolving = true
        try {
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) = runOnUiThread {
                    if (generation == current) {
                        resolving = false
                        status = "Could not resolve ${info.serviceName} ($error); refresh to retry."
                        resolveNext(current)
                    }
                }
                override fun onServiceResolved(info: NsdServiceInfo) = runOnUiThread {
                    if (generation == current) {
                        resolving = false
                        if (found.contains(info.serviceName)) devices[info.serviceName] = "${info.host?.hostAddress ?: "unknown"}:${info.port}"
                        resolveNext(current)
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
            status = "Could not resolve ${service.serviceName}: ${e.localizedMessage}"
            resolveNext(current)
        }
    }

}
