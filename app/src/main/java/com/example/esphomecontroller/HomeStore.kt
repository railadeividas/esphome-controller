package com.example.esphomecontroller

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Devices, homes and preferences. Devices belong to the app and are each assigned to at most one
 * home ([Device.homeId]); homes only group devices and hold their dashboard layout.
 * Secrets live in [com.example.esphomecontroller.api.SecretStore].
 */
class HomeStore(private val prefs: SharedPreferences) {
    /** [mode] null is a legacy device whose connection type has not been chosen yet; [homeId] null means "not in a home". */
    data class Device(val id: String, val name: String, val address: String, val mode: String? = null, val followDiscovery: Boolean = !id.startsWith("manual:"), val homeId: String? = null) {
        val isWeb get() = mode == "web" || mode == "web_auth"
    }
    /** Dashboard layout for one home. Entity keys come from [entityKey]; section keys are device ids or [FAVOURITES]. */
    data class Layout(val pinned: List<String> = emptyList(), val hidden: Set<String> = emptySet(), val collapsed: Set<String> = emptySet())
    /** [devices] is derived: the app's devices assigned to this home, in dashboard order. */
    data class Home(val id: String, val name: String, val layout: Layout = Layout(), val devices: List<Device> = emptyList())

    var theme by mutableStateOf(prefs.getString("theme", "system")?.takeIf { it in THEMES } ?: "system"); private set
    /** Show configuration and diagnostic entities on the dashboard as well as on the device page. */
    var showAllEntities by mutableStateOf(prefs.getBoolean("showAllEntities", true)); private set

    private var rawHomes by mutableStateOf<List<Home>>(emptyList())
    /** Every device in the app, in order; a home's dashboard order is this order filtered by home. */
    var devices by mutableStateOf<List<Device>>(emptyList()); private set
    val homes: List<Home> get() = rawHomes.map { h -> h.copy(devices = devices.filter { it.homeId == h.id }) }
    var selected by mutableStateOf(""); private set

    init {
        load()
        selected = prefs.getString("selected", null)?.takeIf { id -> rawHomes.any { it.id == id } } ?: rawHomes.first().id
    }

    val home: Home get() = homes.first { it.id == selected }

    fun changeTheme(value: String) { if (value in THEMES) { theme = value; prefs.edit().putString("theme", value).apply() } }
    fun changeShowAllEntities(value: Boolean) { showAllEntities = value; prefs.edit().putBoolean("showAllEntities", value).apply() }

    private fun parseDevice(o: JSONObject, homeId: String?) = Device(
        o.getString("id"), o.getString("name"), o.getString("address"), o.optString("mode").takeIf { it in MODES },
        o.optBoolean("followDiscovery", !o.getString("id").startsWith("manual:")),
        if (o.has("homeId") && !o.isNull("homeId")) o.getString("homeId") else homeId,
    )

    private fun load() {
        val homesJson = try { JSONArray(prefs.getString("homes", "[]")) } catch (_: Exception) { JSONArray() }
        val homes = mutableListOf<Home>()
        val legacy = mutableListOf<Device>()
        for (i in 0 until homesJson.length()) {
            val obj = homesJson.optJSONObject(i) ?: continue
            fun strings(key: String) = obj.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val id = obj.optString("id")
            if (id.isBlank()) continue
            homes += Home(id, obj.optString("name", "Home"), Layout(strings("pinned"), strings("hidden").toSet(), strings("collapsed").toSet()))
            // Older versions stored a copy of each device inside every home it was added to.
            obj.optJSONArray("devices")?.let { ds -> for (j in 0 until ds.length()) runCatching { legacy += parseDevice(ds.getJSONObject(j), id) } }
        }
        if (homes.isEmpty()) homes += Home(UUID.randomUUID().toString(), "My home")
        rawHomes = homes
        val stored = prefs.getString("devices", null)
        devices = if (stored != null) {
            val arr = try { JSONArray(stored) } catch (_: Exception) { JSONArray() }
            (0 until arr.length()).mapNotNull { runCatching { parseDevice(arr.getJSONObject(it), null) }.getOrNull() }
        } else migrate(legacy, prefs.getString("selected", null))
        if (stored == null && legacy.isNotEmpty()) save()
    }

    private fun save() {
        val homesJson = JSONArray()
        rawHomes.forEach { h ->
            homesJson.put(JSONObject().put("id", h.id).put("name", h.name)
                .put("pinned", JSONArray(h.layout.pinned)).put("hidden", JSONArray(h.layout.hidden.toList())).put("collapsed", JSONArray(h.layout.collapsed.toList())))
        }
        val devicesJson = JSONArray()
        devices.forEach { d ->
            devicesJson.put(JSONObject().put("id", d.id).put("name", d.name).put("address", d.address).put("mode", d.mode)
                .put("followDiscovery", d.followDiscovery).put("homeId", d.homeId ?: JSONObject.NULL))
        }
        prefs.edit().putString("homes", homesJson.toString()).putString("devices", devicesJson.toString()).putString("selected", selected).apply()
    }

    fun select(id: String) { if (rawHomes.any { it.id == id }) { selected = id; save() } }
    fun addHome(name: String) { if (name.isNotBlank()) { val h = Home(UUID.randomUUID().toString(), name.trim()); rawHomes = rawHomes + h; selected = h.id; save() } }
    /** Deleting a home keeps its devices in the app, no longer assigned to a home. */
    fun deleteHome(id: String) {
        if (rawHomes.size <= 1) return
        rawHomes = rawHomes.filterNot { it.id == id }
        devices = devices.map { if (it.homeId == id) it.copy(homeId = null) else it }
        if (selected == id) selected = rawHomes.first().id
        save()
    }
    fun homeName(id: String?): String? = rawHomes.firstOrNull { it.id == id }?.name
    fun contains(deviceId: String) = devices.any { it.id == deviceId }

    /** Adds or updates a device; a device moved to another home leaves its old home's layout. */
    fun putDevice(device: Device) {
        val old = devices.firstOrNull { it.id == device.id }
        devices = if (old == null) devices + device else devices.map { if (it.id == device.id) device else it }
        if (old != null && old.homeId != device.homeId) old.homeId?.let { forgetLayout(it, device.id) }
        save()
    }
    fun removeDevice(id: String) {
        devices.firstOrNull { it.id == id }?.homeId?.let { forgetLayout(it, id) }
        devices = devices.filterNot { it.id == id }
        save()
    }
    private fun forgetLayout(homeId: String, deviceId: String) {
        val prefix = "$deviceId|"
        rawHomes = rawHomes.map { h ->
            if (h.id != homeId) h else h.copy(layout = h.layout.copy(
                pinned = h.layout.pinned.filterNot { it.startsWith(prefix) }, hidden = h.layout.hidden.filterNot { it.startsWith(prefix) }.toSet(), collapsed = h.layout.collapsed - deviceId))
        }
    }

    private fun updateLayout(change: (Layout) -> Layout) { rawHomes = rawHomes.map { if (it.id == selected) it.copy(layout = change(it.layout)) else it }; save() }
    fun togglePinned(key: String) = updateLayout { l -> l.copy(pinned = if (key in l.pinned) l.pinned - key else l.pinned + key) }
    fun toggleHidden(key: String) = updateLayout { l -> l.copy(hidden = if (key in l.hidden) l.hidden - key else l.hidden + key) }
    fun toggleCollapsed(section: String) = updateLayout { l -> l.copy(collapsed = if (section in l.collapsed) l.collapsed - section else l.collapsed + section) }

    /** Moves a device section up (-1) or down (+1) among the selected home's devices. */
    fun moveDevice(id: String, delta: Int) {
        val inHome = devices.filter { it.homeId == selected }
        val i = inHome.indexOfFirst { it.id == id }; val j = i + delta
        if (i < 0 || j !in inHome.indices) return
        val a = devices.indexOf(inHome[i]); val b = devices.indexOf(inHome[j])
        devices = devices.toMutableList().apply { this[a] = inHome[j]; this[b] = inHome[i] }
        save()
    }

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
        const val FAVOURITES = "favourites"
        /** Stable layout key for an entity of a device. */
        fun entityKey(deviceId: String, stableId: String) = "$deviceId|$stableId"
        val THEMES = listOf("system", "light", "dark")
        val MODES = listOf("encrypted", "plain", "web", "web_auth")

        /**
         * Merges per-home device copies into one device each, assigned to the selected home if it had
         * the device, otherwise to the first home that did. The first copy's settings win.
         */
        fun migrate(legacy: List<Device>, selectedHome: String?): List<Device> =
            legacy.groupBy { it.id }.values.map { copies ->
                val home = copies.firstOrNull { it.homeId == selectedHome }?.homeId ?: copies.first().homeId
                (copies.firstOrNull { it.homeId == home } ?: copies.first()).copy(homeId = home)
            }
    }
}
