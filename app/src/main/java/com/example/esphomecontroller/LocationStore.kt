package com.example.esphomecontroller

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Devices, locations and preferences. Devices belong to the app and are each assigned to at most one
 * location ([Device.locationId]); locations only organise devices. Dashboards are separate ([DashboardStore]).
 * Secrets live in [com.example.esphomecontroller.api.SecretStore].
 */
class LocationStore(private val prefs: SharedPreferences) {
    /** [mode] null means the connection type has not been chosen yet; [locationId] null means "no location". */
    data class Device(val id: String, val name: String, val address: String, val mode: String? = null, val followDiscovery: Boolean = !id.startsWith("manual:"), val locationId: String? = null) {
        val isWeb get() = mode == "web" || mode == "web_auth"
    }
    /** [devices] is derived: the app's devices assigned to this location, in dashboard order. */
    data class Location(val id: String, val name: String, val devices: List<Device> = emptyList())

    var theme by mutableStateOf(prefs.getString("theme", "system")?.takeIf { it in THEMES } ?: "system"); private set

    private var rawLocations by mutableStateOf<List<Location>>(emptyList())
    /** Every device in the app, in order; a location's dashboard order is this order filtered by location. */
    var devices by mutableStateOf<List<Device>>(emptyList()); private set
    val locations: List<Location> get() = rawLocations.map { h -> h.copy(devices = devices.filter { it.locationId == h.id }) }
    var selected by mutableStateOf(""); private set

    init {
        load()
        selected = prefs.getString("selected", null)?.takeIf { id -> rawLocations.any { it.id == id } } ?: rawLocations.first().id
    }

    val location: Location get() = locations.first { it.id == selected }

    /** Text labels under the bottom navigation icons (icons only by default). */
    var navLabels by mutableStateOf(prefs.getBoolean("navLabels", false)); private set
    fun changeNavLabels(value: Boolean) { navLabels = value; prefs.edit().putBoolean("navLabels", value).apply() }
    fun changeTheme(value: String) { if (value in THEMES) { theme = value; prefs.edit().putString("theme", value).apply() } }

    private fun parseDevice(o: JSONObject) = Device(
        o.getString("id"), o.getString("name"), o.getString("address"), o.optString("mode").takeIf { it in MODES },
        o.optBoolean("followDiscovery", !o.getString("id").startsWith("manual:")),
        if (o.has("locationId") && !o.isNull("locationId")) o.getString("locationId") else null,
    )

    private fun load() {
        val locationsJson = try { JSONArray(prefs.getString("locations", "[]")) } catch (_: Exception) { JSONArray() }
        val locations = mutableListOf<Location>()
        for (i in 0 until locationsJson.length()) {
            val obj = locationsJson.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            locations += Location(id, obj.optString("name", "Location"))
        }
        if (locations.isEmpty()) locations += Location(UUID.randomUUID().toString(), "My location")
        rawLocations = locations
        val arr = try { JSONArray(prefs.getString("devices", "[]")) } catch (_: Exception) { JSONArray() }
        devices = (0 until arr.length()).mapNotNull { runCatching { parseDevice(arr.getJSONObject(it)) }.getOrNull() }
    }

    private fun save() {
        val locationsJson = JSONArray()
        rawLocations.forEach { h ->
            locationsJson.put(JSONObject().put("id", h.id).put("name", h.name))
        }
        val devicesJson = JSONArray()
        devices.forEach { d ->
            devicesJson.put(JSONObject().put("id", d.id).put("name", d.name).put("address", d.address).put("mode", d.mode)
                .put("followDiscovery", d.followDiscovery).put("locationId", d.locationId ?: JSONObject.NULL))
        }
        prefs.edit().putString("locations", locationsJson.toString()).putString("devices", devicesJson.toString()).putString("selected", selected).apply()
    }

    fun select(id: String) { if (rawLocations.any { it.id == id }) { selected = id; save() } }
    fun addLocation(name: String) { if (name.isNotBlank()) { val h = Location(UUID.randomUUID().toString(), name.trim()); rawLocations = rawLocations + h; selected = h.id; save() } }
    /** Deleting a location keeps its devices in the app, no longer assigned to a location. */
    fun deleteLocation(id: String) {
        if (rawLocations.size <= 1) return
        rawLocations = rawLocations.filterNot { it.id == id }
        devices = devices.map { if (it.locationId == id) it.copy(locationId = null) else it }
        if (selected == id) selected = rawLocations.first().id
        save()
    }
    fun locationName(id: String?): String? = rawLocations.firstOrNull { it.id == id }?.name
    fun contains(deviceId: String) = devices.any { it.id == deviceId }

    fun putDevice(device: Device) {
        devices = if (devices.none { it.id == device.id }) devices + device else devices.map { if (it.id == device.id) device else it }
        save()
    }
    fun removeDevice(id: String) { devices = devices.filterNot { it.id == id }; save() }
    /**
     * Records a newly resolved address for a device that follows discovery. Web-server devices keep
     * their configured port. Persisting it means a device briefly missing from discovery keeps its last address.
     */
    fun applyDiscovered(serviceName: String, resolved: String) {
        val (host, _) = splitAddress(resolved)
        var changed = false
        val updated = devices.map { d ->
            if (d.id != serviceName || !d.followDiscovery) return@map d
            val address = if (!d.isWeb) resolved else splitAddress(d.address).second.toIntOrNull()?.let { joinAddress(host, it) } ?: return@map d
            if (address == d.address) d else { changed = true; d.copy(address = address) }
        }
        if (changed) { devices = updated; save() }
    }

    companion object {
        val THEMES = listOf("system", "light", "dark")
        val MODES = listOf("encrypted", "plain", "web", "web_auth")
    }
}
