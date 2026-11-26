package com.example.esphomecontroller

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Homes, device assignments and preferences. Secrets live in [com.example.esphomecontroller.api.SecretStore]. */
class HomeStore(private val prefs: SharedPreferences) {
    /** [mode] null is a legacy device whose connection type has not been chosen yet. */
    data class Device(val id: String, val name: String, val address: String, val mode: String? = null, val followDiscovery: Boolean = !id.startsWith("manual:")) {
        val isWeb get() = mode == "web" || mode == "web_auth"
    }
    data class Home(val id: String, val name: String, val devices: List<Device> = emptyList())

    var theme by mutableStateOf(prefs.getString("theme", "system")?.takeIf { it in THEMES } ?: "system"); private set
    /** Show configuration and diagnostic entities on the dashboard as well as on the device page. */
    var showAllEntities by mutableStateOf(prefs.getBoolean("showAllEntities", false)); private set
    var homes by mutableStateOf(load()); private set
    var selected by mutableStateOf(prefs.getString("selected", null) ?: homes.first().id); private set
    init { if (homes.none { it.id == selected }) selected = homes.first().id }

    val home: Home get() = homes.first { it.id == selected }

    fun changeTheme(value: String) { if (value in THEMES) { theme = value; prefs.edit().putString("theme", value).apply() } }
    fun changeShowAllEntities(value: Boolean) { showAllEntities = value; prefs.edit().putBoolean("showAllEntities", value).apply() }

    private fun load(): List<Home> = try {
        val arr = JSONArray(prefs.getString("homes", "[]"))
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i); val ds = obj.getJSONArray("devices")
            Home(obj.getString("id"), obj.getString("name"), (0 until ds.length()).map { j ->
                ds.getJSONObject(j).let { Device(it.getString("id"), it.getString("name"), it.getString("address"), it.optString("mode").takeIf { mode -> mode in MODES }, it.optBoolean("followDiscovery", !it.getString("id").startsWith("manual:"))) }
            })
        }.ifEmpty { listOf(Home(UUID.randomUUID().toString(), "My home")) }
    } catch (_: Exception) { listOf(Home(UUID.randomUUID().toString(), "My home")) }

    private fun save() {
        val arr = JSONArray()
        homes.forEach { h ->
            val ds = JSONArray()
            h.devices.forEach { d -> ds.put(JSONObject().put("id", d.id).put("name", d.name).put("address", d.address).put("mode", d.mode).put("followDiscovery", d.followDiscovery)) }
            arr.put(JSONObject().put("id", h.id).put("name", h.name).put("devices", ds))
        }
        prefs.edit().putString("homes", arr.toString()).putString("selected", selected).apply()
    }

    fun select(id: String) { if (homes.any { it.id == id }) { selected = id; save() } }
    fun addHome(name: String) { if (name.isNotBlank()) { val h = Home(UUID.randomUUID().toString(), name.trim()); homes = homes + h; selected = h.id; save() } }
    fun deleteHome(id: String) { if (homes.size > 1) { homes = homes.filterNot { it.id == id }; if (selected == id) selected = homes.first().id; save() } }
    fun putDevice(device: Device) {
        homes = homes.map { h -> if (h.id != selected) h else h.copy(devices = if (h.devices.any { it.id == device.id }) h.devices.map { if (it.id == device.id) device else it } else h.devices + device) }
        save()
    }
    fun removeDevice(id: String) { homes = homes.map { if (it.id == selected) it.copy(devices = it.devices.filterNot { d -> d.id == id }) else it }; save() }
    fun isDeviceInAnyHome(id: String) = homes.any { h -> h.devices.any { it.id == id } }

    /**
     * Records a newly resolved address for devices that follow discovery, in every home.
     * Web-server devices keep their configured port. Persisting it means a device that
     * is temporarily missing from discovery keeps using its last known address.
     */
    fun applyDiscovered(serviceName: String, resolved: String) {
        val (host, _) = splitAddress(resolved)
        var changed = false
        val updated = homes.map { h ->
            h.copy(devices = h.devices.map { d ->
                if (d.id != serviceName || !d.followDiscovery) return@map d
                val address = if (!d.isWeb) resolved else splitAddress(d.address).second.toIntOrNull()?.let { joinAddress(host, it) } ?: return@map d
                if (address == d.address) d else { changed = true; d.copy(address = address) }
            })
        }
        if (changed) { homes = updated; save() }
    }

    companion object {
        val THEMES = listOf("system", "light", "dark")
        val MODES = listOf("encrypted", "plain", "web", "web_auth")
    }
}
