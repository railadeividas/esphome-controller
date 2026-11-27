package com.example.esphomecontroller

import com.example.esphomecontroller.api.Entity
import com.example.esphomecontroller.api.EntityCategory
import com.example.esphomecontroller.api.Platform
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DashboardStoreTest {
    private val devices = listOf(
        LocationStore.Device("stairs", "Stairs", "192.0.2.1:6053", "plain", locationId = "home"),
        LocationStore.Device("lamp", "Lamp", "192.0.2.2:6053", "plain", locationId = "office"),
        LocationStore.Device("hub", "Hub", "192.0.2.3:6053", "plain"),
    )
    private val entities = mapOf(
        "stairs" to listOf(Entity(1, 0, "Stair light", Platform.Light(true)), Entity(2, 0, "Restart", Platform.Button, EntityCategory.CONFIG)),
        "lamp" to listOf(Entity(1, 0, "Lamp", Platform.Light(false)), Entity(3, 0, "Plug", Platform.Switch)),
        "hub" to listOf(Entity(4, 0, "Temperature", Platform.Sensor("°C", 1)), Entity(5, 0, "Heater", Platform.Climate(listOf("Off", "Heat"), 10f, 30f, 0.5f, false, true, false, true))),
    )
    private val areas = mapOf("stairs" to "Hallway", "lamp" to "Office")
    private fun build(t: DashboardTemplate) = DashboardStore.buildSections(t, devices, { entities[it].orEmpty() }, { areas[it] })

    @Test fun deviceTemplatesBuildTileSectionsOfMainEntities() {
        val all = build(DashboardTemplate.Devices(null))
        assertEquals(listOf("Stairs", "Lamp", "Hub"), all.map { it.title })
        assertTrue(all.all { it.type == SectionType.TILES && it.pendingDeviceId == null && !it.showDeviceNames })
        // Configuration entities (the stairs Restart button) are left out.
        assertEquals(listOf(ItemRef("stairs", "light:0:1")), all[0].items)
        assertEquals(listOf("Lamp"), build(DashboardTemplate.Devices("office")).map { it.title })
        assertTrue(build(DashboardTemplate.Empty).isEmpty())
        // A device whose entities are not known yet gets a section that waits for them.
        val unknown = DashboardStore.buildSections(DashboardTemplate.Devices(null), devices.take(1), { emptyList() }, { null }).single()
        assertEquals("stairs", unknown.pendingDeviceId); assertTrue(unknown.items.isEmpty())
    }

    @Test fun areaTemplateAddsHeadingsWithNoAreaLast() {
        val sections = build(DashboardTemplate.ByArea)
        assertEquals(listOf("Hallway", "Stairs", "Office", "Lamp", "No area", "Hub"), sections.map { it.title })
        assertEquals(listOf(SectionType.HEADING, SectionType.TILES, SectionType.HEADING, SectionType.TILES, SectionType.HEADING, SectionType.TILES), sections.map { it.type })
    }

    @Test fun typeTemplateGroupsEntitiesAcrossDevicesAndSkipsConfig() {
        val sections = build(DashboardTemplate.ByType)
        assertEquals(listOf("Lights", "Switches", "Climate", "Readings"), sections.map { it.title })
        assertEquals(listOf(ItemRef("stairs", "light:0:1"), ItemRef("lamp", "light:0:1")), sections[0].items)
        assertEquals(SectionType.LIST, sections.last().type)
        assertTrue(sections.none { s -> s.items.any { it.stableId.startsWith("button") } })
    }

    @Test fun dashboardsRoundTripThroughJson() {
        val d = Dashboard("id1", "Evening", listOf(
            Section("s1", SectionType.TILES, "Lights", listOf(ItemRef("stairs", "light:0:1")), columns = 3, readingsAsList = true, tapAction = TapAction.TOGGLE, allOnOff = true),
            Section("s2", SectionType.TILES, "", pendingDeviceId = "hub", collapsed = true, collapsible = false),
            Section("s3", SectionType.HEADING, "Upstairs", text = "Bedrooms", titleSize = TitleSize.H1),
            Section("s4", SectionType.LIST, "Readings", listOf(ItemRef("hub", "sensor:0:4"))),
            Section("s5", SectionType.LIST, "Plain", showDeviceNames = true, tapAction = TapAction.NONE, deviceLink = true, titleColor = TitleColor.AMBER, icon = "sofa"),
            Section("s7", SectionType.TILES, "Tuned", titleColor = TitleColor.CUSTOM, customColor = 0xFF7FA8F0.toInt()),
            Section("s6", SectionType.COMPACT, "Small", listOf(ItemRef("stairs", "switch:0:2")), text = "Hall", columns = 4, titleSize = TitleSize.H2),
        ), DashboardSettings(summaryLine = true, pullToRefresh = false, brightnessPercent = false, showDescription = false), description = "Living room and kitchen")
        assertEquals(d, DashboardCodec.decode(JSONObject(DashboardCodec.encode(d).toString())))
        // Missing settings fall back to defaults; unknown section types are skipped.
        val partial = JSONObject("""{"id":"x","name":"Old","sections":[{"id":"a","type":"FUTURE"},{"id":"b","type":"HEADING","title":"Hi"}]}""")
        val decoded = DashboardCodec.decode(partial)
        assertEquals(DashboardSettings(), decoded.settings); assertEquals(listOf("b"), decoded.sections.map { it.id })
    }

    @Test fun oldDashboardSettingsBecomeSectionOptions() {
        val old = JSONObject("""{"id":"x","name":"Old","settings":{"columns":3,"readingsAsList":false,"collapsibleSections":false,"showDeviceNames":false,
            "showConfigDiagnostic":false,"tapToToggle":true,"summaryLine":true},"sections":[{"id":"a","type":"TILES"},{"id":"b","type":"TILES"}]}""")
        val d = DashboardCodec.decode(old)
        assertTrue(d.settings.summaryLine)
        d.sections.forEach { s ->
            assertEquals(3, s.columns); assertFalse(s.readingsAsList); assertFalse(s.collapsible); assertFalse(s.showDeviceNames)
            assertEquals(TapAction.TOGGLE, s.tapAction)
        }
        val noSheet = DashboardCodec.decode(JSONObject("""{"id":"y","name":"N","settings":{"controlsSheet":false},"sections":[{"id":"a","type":"LIST"}]}"""))
        assertEquals(TapAction.NONE, noSheet.sections.single().tapAction)
    }

    @Test fun oldDeviceSectionsBecomeTileSectionsWaitingForTheirDevice() {
        val old = JSONObject("""{"id":"x","name":"Old","sections":[{"id":"b","type":"DEVICE","title":"Hall","deviceId":"hub","collapsed":true}]}""")
        val s = DashboardCodec.decode(old).sections.single()
        assertEquals(SectionType.TILES, s.type); assertEquals("hub", s.pendingDeviceId); assertEquals("Hall", s.title)
        assertTrue(s.collapsed); assertFalse(s.showDeviceNames)
    }

    @Test fun moveKeepsBoundsAndDeviceIdsCollectEverySection() {
        assertEquals(listOf(2, 1, 3), DashboardStore.moved(listOf(1, 2, 3), 1, -1))
        assertEquals(listOf(1, 2, 3), DashboardStore.moved(listOf(1, 2, 3), 0, -1))
        assertEquals(listOf(1, 2, 3), DashboardStore.moved(listOf(1, 2, 3), 5, 1))
        val d = Dashboard("d", "D", listOf(Section(type = SectionType.TILES, items = listOf(ItemRef("a", "x"))), Section(type = SectionType.TILES, pendingDeviceId = "b")))
        assertEquals(setOf("a", "b"), d.deviceIds)
    }
}
