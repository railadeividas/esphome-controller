package com.example.esphomecontroller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    /** Found devices live in a ViewModel so they survive rotation and last until the app is closed. */
    class Found : androidx.lifecycle.ViewModel() { val devices = mutableStateMapOf<String, DiscoveredDevice>() }
    private val devices get() = found_.devices
    private val found_: Found by viewModels()
    /** Whether the running scan was started by the user; background scans stay silent in Discover. */
    private var manualScan by mutableStateOf(false)
    /** Discovery runs in bounded scans, like a Bluetooth scan, rather than continuously. */
    private var scanning by mutableStateOf(false)
    private var scanError by mutableStateOf<String?>(null)
    private var scanned by mutableStateOf(false)
    private val handler = Handler(Looper.getMainLooper())
    private var extensions = 0
    private val endScan = object : Runnable {
        override fun run() {
            // Let resolutions already in progress finish, briefly.
            if ((resolving || pending.isNotEmpty()) && extensions++ < 3) { handler.postDelayed(this, 2000); return }
            stopScan()
        }
    }
    private lateinit var nsd: NsdManager
    private var listener: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private var generation = 0
    private val pending = ArrayDeque<NsdServiceInfo>()
    /** Live announcements per service name; the same device may be announced on several interfaces. */
    private val found = mutableMapOf<String, Int>()
    private var resolving = false
    private lateinit var store: HomeStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The system's three-button contrast scrim would otherwise obscure our themed background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        store = HomeStore(getSharedPreferences("homes", Context.MODE_PRIVATE))
        setContent {
            HomeScreen(store, devices, ScanControls(scanning && manualScan, scanned, scanError, start = { startScan(MANUAL_SCAN_MS, manual = true) }, stop = ::stopScan, background = ::backgroundScan))
        }
    }

    // A short scan on start refreshes the addresses of saved devices.
    override fun onStart() { super.onStart(); startScan(BACKGROUND_SCAN_MS) }
    override fun onStop() { stopScan(); super.onStop() }

    /** Found devices are kept until the app closes; a new scan only adds to or updates them. */
    private fun startScan(durationMs: Long, manual: Boolean = false) {
        stopDiscovery()
        manualScan = manual
        handler.removeCallbacks(endScan); extensions = 0
        scanError = null; scanning = true
        startDiscovery()
        handler.postDelayed(endScan, durationMs)
    }

    private fun stopScan() {
        handler.removeCallbacks(endScan)
        if (scanning && manualScan) scanned = true
        scanning = false; manualScan = false
        stopDiscovery()
    }

    /** Requested when a device that follows discovery cannot be reached; never interrupts a running scan. */
    private fun backgroundScan() { if (!scanning) startScan(BACKGROUND_SCAN_MS) }

    private fun stopDiscovery() {
        generation++
        pending.clear()
        found.clear()
        resolving = false
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: IllegalArgumentException) {} }
        listener = null
        lock?.let { if (it.isHeld) it.release() }; lock = null
    }
    @Suppress("DEPRECATION")
    private fun startDiscovery() {
        val current = ++generation
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("esphome-discovery").apply { setReferenceCounted(false); acquire() }
            val discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, error: Int) { runOnUiThread { if (generation == current) { val manual = manualScan; stopScan(); if (manual) scanError = "Discovery failed ($error). Check Wi-Fi and try again." } } }
                override fun onStopDiscoveryFailed(type: String, error: Int) {}
                override fun onServiceLost(service: NsdServiceInfo) { runOnUiThread {
                    if (generation == current) {
                        val remaining = (found[service.serviceName] ?: 1) - 1
                        if (remaining > 0) found[service.serviceName] = remaining
                        else {
                            // Stop resolving it, but keep it listed: the user may still want to add it.
                            found.remove(service.serviceName)
                            pending.removeAll { it.serviceName == service.serviceName }
                        }
                    }
                } }
                override fun onServiceFound(service: NsdServiceInfo) { runOnUiThread {
                    if (generation == current) {
                        val count = (found[service.serviceName] ?: 0) + 1
                        found[service.serviceName] = count
                        // Resolve once per name; duplicate announcements from other interfaces only add a reference.
                        if (count == 1) { pending.addLast(service); resolveNext(current) }
                    }
                } }

            }
            listener = discovery
            nsd.discoverServices("_esphomelib._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) { val manual = manualScan; stopScan(); if (manual) scanError = "Discovery unavailable: ${e.localizedMessage}" }
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
                        if (manualScan) scanError = "Could not resolve ${info.serviceName} ($error)."
                        resolveNext(current)
                    }
                }
                override fun onServiceResolved(info: NsdServiceInfo) = runOnUiThread {
                    if (generation == current) {
                        resolving = false
                        val host = info.host?.hostAddress
                        if (found.containsKey(info.serviceName) && host != null) {
                            val address = joinAddress(host, info.port)
                            devices[info.serviceName] = parseEsphomeTxt(info.serviceName, address, info.attributes.orEmpty())
                            store.applyDiscovered(info.serviceName, address)
                        }
                        resolveNext(current)
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
            if (manualScan) scanError = "Could not resolve ${service.serviceName}: ${e.localizedMessage}"
            resolveNext(current)
        }
    }

    companion object {
        const val MANUAL_SCAN_MS = 30_000L
        const val BACKGROUND_SCAN_MS = 15_000L
    }
}
