package com.example.esphomecontroller

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.esphomecontroller.api.Entity
import com.example.esphomecontroller.api.EntityCategory
import com.example.esphomecontroller.api.Platform
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** An entity on a dashboard: a device id and the entity's stable id on that device. */
data class ItemRef(val deviceId: String, val stableId: String)

enum class SectionType(val label: String, val description: String) {
    TILES("Tiles", "Chosen entities as tiles"),
    LIST("List", "Chosen entities as compact rows"),
    COMPACT("Compact tiles", "Chosen entities as small tiles with icon, name and state"),
    DEVICE("Device", "All entities of one device, kept up to date automatically"),
    HEADING("Heading", "A title or note between sections"),
}

/** Section title sizes, largest first. */
enum class TitleSize(val label: String) { H1("H1"), H2("H2"), H3("H3"), H4("H4") }

enum class TapAction(val label: String, val description: String) {
    CONTROLS("Open controls", "Long-press a tile, tap its name or tap a row for all controls"),
    TOGGLE("Toggle", "Tap a light, switch or fan to toggle it; long-press for all controls"),
    NONE("Nothing", "Only the controls on the tile or row"),
}

data class Section(
    val id: String = UUID.randomUUID().toString(),
    val type: SectionType,
    val title: String = "",
    /** Entities shown by sections with [hasChosenItems], in order. */
    val items: List<ItemRef> = emptyList(),
    /** The device followed by a [SectionType.DEVICE] section. */
    val deviceId: String? = null,
    /** Optional text under the title, for every section type. */
    val text: String = "",
    val collapsed: Boolean = false,
    /** The header has a collapse arrow (not for headings). */
    val collapsible: Boolean = true,
    /** Tile columns for tile and device sections; 0 = automatic (2 on phones, 4 on wide screens). */
    val columns: Int = 0,
    /** In tile and device sections, sensors, binary and text sensors go to a compact list under the tiles. */
    val readingsAsList: Boolean = true,
    /** Label entities of tile and list sections with their device name. */
    val showDeviceNames: Boolean = true,
    /** Include configuration entities in a device section. */
    val showConfig: Boolean = true,
    /** Include diagnostic entities in a device section. */
    val showDiagnostic: Boolean = true,
    /** Title size; null uses the type's default ([titleSizeOrDefault]). */
    val titleSize: TitleSize? = null,
    /** What tapping a tile or row does. */
    val tapAction: TapAction = TapAction.CONTROLS,
    /** An all on / all off button in the header when the section has lights, switches or fans. */
    val allOnOff: Boolean = true,
) {
    val hasEntities: Boolean get() = type != SectionType.HEADING
    val titleSizeOrDefault: TitleSize get() = titleSize ?: if (type == SectionType.HEADING) TitleSize.H3 else TitleSize.H4
    val hasTiles: Boolean get() = type == SectionType.TILES || type == SectionType.COMPACT || type == SectionType.DEVICE
    /** Tiles, list and compact tiles hold the same chosen entities, so the style can change at any time. */
    val hasChosenItems: Boolean get() = type in CHOSEN_ITEM_TYPES

    companion object { val CHOSEN_ITEM_TYPES = listOf(SectionType.TILES, SectionType.LIST, SectionType.COMPACT) }
}

/** Options for the whole dashboard; layout and content options belong to each [Section]. */
data class DashboardSettings(
    val brightnessPercent: Boolean = true,
    /** Dim entities of offline devices and show when they were last seen. */
    val dimOffline: Boolean = true,
    /** Show a spinner on a tile until the device reports the commanded state. */
    val pendingSpinner: Boolean = true,
    /** Command failures as a snackbar with Retry; otherwise as text under the section. */
    val snackbarErrors: Boolean = true,
    /** A summary line (lights on, open doors, offline devices) under the dashboard name. */
    val summaryLine: Boolean = false,
    /** Pull down to reconnect all devices on this dashboard immediately. */
    val pullToRefresh: Boolean = true,
)

data class Dashboard(val id: String, val name: String, val sections: List<Section> = emptyList(), val settings: DashboardSettings = DashboardSettings()) {
    /** Devices referenced by any section. */
    val deviceIds: Set<String> get() = sections.flatMap { s -> s.items.map { it.deviceId } + listOfNotNull(s.deviceId) }.toSet()
}

/** Ways to start a new dashboard. */
sealed interface DashboardTemplate {
    data object Empty : DashboardTemplate
    /** One device section per device in a location (or all devices when [locationId] is null). */
    data class Devices(val locationId: String?) : DashboardTemplate
    /** A heading per area with that area's device sections. */
    data object ByArea : DashboardTemplate
    /** Tile and list sections grouped by entity type across all devices. */
    data object ByType : DashboardTemplate
}

/** User-created dashboards, persisted as JSON in the app preferences. One dashboard is the default. */
class DashboardStore(private val prefs: SharedPreferences) {
    var dashboards by mutableStateOf<List<Dashboard>>(emptyList()); private set
    var defaultId by mutableStateOf(""); private set
    /** The dashboard on screen; the default one after each start. */
    var selectedId by mutableStateOf(""); private set

    init {
        dashboards = load()
        if (dashboards.isEmpty()) dashboards = listOf(Dashboard(UUID.randomUUID().toString(), "My dashboard"))
        defaultId = prefs.getString("defaultDashboard", null)?.takeIf { id -> dashboards.any { it.id == id } } ?: dashboards.first().id
        selectedId = defaultId
    }

    val selected: Dashboard get() = dashboards.firstOrNull { it.id == selectedId } ?: dashboards.first()

    fun select(id: String) { if (dashboards.any { it.id == id }) selectedId = id }
    fun setDefault(id: String) { if (dashboards.any { it.id == id }) { defaultId = id; save() } }

    fun create(name: String, template: DashboardTemplate, locations: LocationStore, entities: (String) -> List<Entity>, area: (String) -> String?): Dashboard {
        val d = Dashboard(UUID.randomUUID().toString(), name.trim().ifBlank { "Dashboard" }, buildSections(template, locations.devices, entities, area))
        dashboards = dashboards + d; selectedId = d.id; save()
        return d
    }
    fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim().ifBlank { it.name }) }
    fun delete(id: String) {
        if (dashboards.size <= 1) return
        dashboards = dashboards.filterNot { it.id == id }
        if (defaultId == id) defaultId = dashboards.first().id
        if (selectedId == id) selectedId = defaultId
        save()
    }
    fun moveDashboard(id: String, delta: Int) { dashboards = moved(dashboards, dashboards.indexOfFirst { it.id == id }, delta); save() }
    fun updateSettings(id: String, settings: DashboardSettings) = update(id) { it.copy(settings = settings) }

    fun addSection(id: String, section: Section) = update(id) { it.copy(sections = it.sections + section) }
    fun updateSection(id: String, section: Section) = update(id) { d -> d.copy(sections = d.sections.map { if (it.id == section.id) section else it }) }
    fun deleteSection(id: String, sectionId: String) = update(id) { d -> d.copy(sections = d.sections.filterNot { it.id == sectionId }) }
    fun moveSection(id: String, sectionId: String, delta: Int) = update(id) { d -> d.copy(sections = moved(d.sections, d.sections.indexOfFirst { it.id == sectionId }, delta)) }
    fun toggleCollapsed(id: String, sectionId: String) = update(id) { d -> d.copy(sections = d.sections.map { if (it.id == sectionId) it.copy(collapsed = !it.collapsed) else it }) }
    fun addItems(id: String, sectionId: String, refs: List<ItemRef>) = editSection(id, sectionId) { s -> s.copy(items = s.items + refs.filterNot { it in s.items }) }
    fun setItems(id: String, sectionId: String, refs: List<ItemRef>) = editSection(id, sectionId) { it.copy(items = refs.distinct()) }
    fun removeItem(id: String, sectionId: String, ref: ItemRef) = editSection(id, sectionId) { s -> s.copy(items = s.items - ref) }
    fun moveItem(id: String, sectionId: String, ref: ItemRef, delta: Int) = editSection(id, sectionId) { s -> s.copy(items = moved(s.items, s.items.indexOf(ref), delta)) }

    /** Drops every reference to a device that was removed from the app. */
    fun forgetDevice(deviceId: String) {
        dashboards = dashboards.map { d ->
            d.copy(sections = d.sections.filterNot { it.type == SectionType.DEVICE && it.deviceId == deviceId }
                .map { s -> s.copy(items = s.items.filterNot { it.deviceId == deviceId }) })
        }
        save()
    }

    private fun editSection(id: String, sectionId: String, change: (Section) -> Section) =
        update(id) { d -> d.copy(sections = d.sections.map { if (it.id == sectionId) change(it) else it }) }
    private fun update(id: String, change: (Dashboard) -> Dashboard) { dashboards = dashboards.map { if (it.id == id) change(it) else it }; save() }

    private fun load(): List<Dashboard> = try {
        val arr = JSONArray(prefs.getString("dashboards", "[]"))
        (0 until arr.length()).mapNotNull { runCatching { DashboardCodec.decode(arr.getJSONObject(it)) }.getOrNull() }
    } catch (_: Exception) { emptyList() }

    private fun save() {
        val arr = JSONArray().apply { dashboards.forEach { put(DashboardCodec.encode(it)) } }
        prefs.edit().putString("dashboards", arr.toString()).putString("defaultDashboard", defaultId).apply()
    }

    companion object {
        fun <T> moved(list: List<T>, index: Int, delta: Int): List<T> {
            val target = index + delta
            if (index < 0 || target !in list.indices) return list
            return list.toMutableList().apply { add(target, removeAt(index)) }
        }

        /** Groups for the "by type" template, in display order; read-only entities go to a final list. */
        private val TYPE_GROUPS: List<Pair<String, (Platform) -> Boolean>> = listOf(
            "Lights" to { p -> p is Platform.Light },
            "Switches" to { p -> p == Platform.Switch },
            "Fans" to { p -> p is Platform.Fan },
            "Covers and valves" to { p -> p is Platform.Cover || p is Platform.Valve },
            "Climate" to { p -> p is Platform.Climate },
            "Locks" to { p -> p is Platform.Lock },
            "Settings" to { p -> p is Platform.Select || p is Platform.Number },
            "Buttons" to { p -> p == Platform.Button },
        )

        fun buildSections(template: DashboardTemplate, devices: List<LocationStore.Device>, entities: (String) -> List<Entity>, area: (String) -> String?): List<Section> {
            return when (template) {
                DashboardTemplate.Empty -> emptyList()
                is DashboardTemplate.Devices -> devices.filter { template.locationId == null || it.locationId == template.locationId }
                    .map { Section(type = SectionType.DEVICE, title = it.name, deviceId = it.id) }
                DashboardTemplate.ByArea -> devices.groupBy { area(it.id)?.takeIf { a -> a.isNotBlank() } ?: "No area" }.toSortedMap(compareBy<String> { it == "No area" }.thenBy { it })
                    .flatMap { (areaName, inArea) -> listOf(Section(type = SectionType.HEADING, title = areaName)) + inArea.map { Section(type = SectionType.DEVICE, title = it.name, deviceId = it.id) } }
                DashboardTemplate.ByType -> {
                    val all = devices.flatMap { d -> entities(d.id).filter { it.category == EntityCategory.NONE }.map { d.id to it } }
                    TYPE_GROUPS.mapNotNull { (title, matches) ->
                        all.filter { matches(it.second.platform) }.takeIf { it.isNotEmpty() }
                            ?.let { group -> Section(type = SectionType.TILES, title = title, items = group.map { (id, e) -> ItemRef(id, e.stableId) }) }
                    } + listOfNotNull(all.filter { it.second.platform.isReadOnlyPlatform() }.takeIf { it.isNotEmpty() }
                        ?.let { group -> Section(type = SectionType.LIST, title = "Readings", items = group.map { (id, e) -> ItemRef(id, e.stableId) }) })
                }
            }
        }

        private fun Platform.isReadOnlyPlatform() = this is Platform.Sensor || this == Platform.BinarySensor || this == Platform.TextSensor
    }
}

/** JSON form of a dashboard; unknown fields fall back to defaults so older saves keep loading. */
object DashboardCodec {
    fun encode(d: Dashboard): JSONObject = JSONObject().put("id", d.id).put("name", d.name)
        .put("sections", JSONArray().apply { d.sections.forEach { put(encodeSection(it)) } })
        .put("settings", encodeSettings(d.settings))

    fun decode(o: JSONObject): Dashboard {
        // Saves from before section options kept them in the dashboard settings; those become each section's starting values.
        val legacy = o.optJSONObject("settings")
        val sections = o.optJSONArray("sections")?.let { a -> (0 until a.length()).mapNotNull { i -> runCatching { decodeSection(a.getJSONObject(i), legacy) }.getOrNull() } }.orEmpty()
        return Dashboard(o.getString("id"), o.optString("name", "Dashboard"), sections, decodeSettings(o.optJSONObject("settings")))
    }

    private fun encodeSection(s: Section) = JSONObject().put("id", s.id).put("type", s.type.name).put("title", s.title).put("text", s.text)
        .put("collapsed", s.collapsed).put("deviceId", s.deviceId ?: JSONObject.NULL)
        .put("collapsible", s.collapsible).put("columns", s.columns).put("readingsAsList", s.readingsAsList)
        .put("showDeviceNames", s.showDeviceNames).put("showConfig", s.showConfig).put("showDiagnostic", s.showDiagnostic)
        .put("tapAction", s.tapAction.name).put("titleSize", s.titleSize?.name ?: JSONObject.NULL).put("allOnOff", s.allOnOff)
        .put("items", JSONArray().apply { s.items.forEach { put(JSONObject().put("deviceId", it.deviceId).put("stableId", it.stableId)) } })

    private fun decodeSection(o: JSONObject, legacy: JSONObject? = null): Section {
        val items = o.optJSONArray("items")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { ItemRef(it.getString("deviceId"), it.getString("stableId")) } } }.orEmpty()
        val d = Section(id = "", type = SectionType.HEADING)
        fun bool(key: String, legacyKey: String, default: Boolean) = o.optBoolean(key, legacy?.optBoolean(legacyKey, default) ?: default)
        return Section(o.getString("id"), SectionType.valueOf(o.getString("type")), o.optString("title"), items,
            if (o.has("deviceId") && !o.isNull("deviceId")) o.getString("deviceId") else null, o.optString("text"), o.optBoolean("collapsed"),
            collapsible = bool("collapsible", "collapsibleSections", d.collapsible),
            columns = o.optInt("columns", legacy?.optInt("columns", d.columns) ?: d.columns),
            readingsAsList = bool("readingsAsList", "readingsAsList", d.readingsAsList),
            showDeviceNames = bool("showDeviceNames", "showDeviceNames", d.showDeviceNames),
            showConfig = bool("showConfig", "showConfigDiagnostic", d.showConfig),
            showDiagnostic = bool("showDiagnostic", "showConfigDiagnostic", d.showDiagnostic),
            tapAction = o.optString("tapAction").let { name -> TapAction.entries.firstOrNull { it.name == name } } ?: when {
                legacy?.optBoolean("tapToToggle") == true -> TapAction.TOGGLE
                legacy?.optBoolean("controlsSheet", true) == false -> TapAction.NONE
                else -> d.tapAction
            },
            allOnOff = o.optBoolean("allOnOff", d.allOnOff),
            titleSize = if (o.isNull("titleSize")) null else TitleSize.entries.firstOrNull { it.name == o.optString("titleSize") },
        )
    }

    private fun encodeSettings(s: DashboardSettings) = JSONObject()
        .put("brightnessPercent", s.brightnessPercent).put("dimOffline", s.dimOffline)
        .put("pendingSpinner", s.pendingSpinner).put("snackbarErrors", s.snackbarErrors).put("summaryLine", s.summaryLine).put("pullToRefresh", s.pullToRefresh)

    private fun decodeSettings(o: JSONObject?): DashboardSettings {
        val d = DashboardSettings()
        if (o == null) return d
        return DashboardSettings(
            brightnessPercent = o.optBoolean("brightnessPercent", d.brightnessPercent),
            dimOffline = o.optBoolean("dimOffline", d.dimOffline),
            pendingSpinner = o.optBoolean("pendingSpinner", d.pendingSpinner), snackbarErrors = o.optBoolean("snackbarErrors", d.snackbarErrors),
            summaryLine = o.optBoolean("summaryLine", d.summaryLine), pullToRefresh = o.optBoolean("pullToRefresh", d.pullToRefresh),
        )
    }
}
