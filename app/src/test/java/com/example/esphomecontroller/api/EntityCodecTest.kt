package com.example.esphomecontroller.api

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class EntityCodecTest {
    private val all = listOf(
        Entity(1, 0, "Relay", Platform.Switch, deviceClass = "outlet", icon = "mdi:power"),
        Entity(2, 0, "Door", Platform.BinarySensor, EntityCategory.NONE, deviceClass = "door"),
        Entity(3, 0, "Status", Platform.TextSensor, EntityCategory.DIAGNOSTIC),
        Entity(4, 0, "Restart", Platform.Button, EntityCategory.CONFIG, deviceClass = "restart"),
        Entity(5, 2, "Strip", Platform.Light(true, true, 153f, 500f, listOf("Rainbow"))),
        Entity(6, 0, "Plain light", Platform.Light(false)),
        Entity(7, 0, "Temperature", Platform.Sensor("°C", 1)),
        Entity(8, 0, "Raw", Platform.Sensor()),
        Entity(9, 0, "Timer", Platform.Select(listOf("Off", "15 min"))),
        Entity(10, 0, "Delay", Platform.Number(0f, 60f, 0.5f, "s", NumberMode.BOX)),
        Entity(11, 0, "Blinds", Platform.Cover(true, true, false)),
        Entity(12, 0, "Water", Platform.Valve(true, false)),
        Entity(13, 0, "Fan", Platform.Fan(true, false, 3, listOf("Sleep"))),
        Entity(14, 0, "Lock", Platform.Lock(true, true, "^\\d{4}$")),
        Entity(15, 0, "Heater", Platform.Climate(listOf("Off", "Heat"), 10f, 30f, 0.5f, true, true, false, true, listOf("Auto", "Turbo"), listOf("Off"), listOf("Eco"), "°F", listOf("Turbo"), emptyList())),
        Entity(16, 0, "Speaker", Platform.Unsupported("Media player")),
        Entity(17, 0, "Web relay", Platform.Switch, webId = "switch/Web relay"),
    )

    @Test fun roundTripsEveryPlatformWithoutStates() {
        val withStates = all.map { it.copy(state = EntityState.OnOff(true)) }
        val json = EntityCodec.encodeEntities(withStates).toString()
        assertFalse("states must never be written", json.contains("\"state\""))
        assertEquals(all, EntityCodec.decodeEntities(JSONArray(json)))
    }

    @Test fun skipsMalformedAndUnknownEntries() {
        val json = JSONArray(EntityCodec.encodeEntities(all.take(2)).toString())
        json.put(org.json.JSONObject().put("key", 1))
        json.put(org.json.JSONObject().put("key", 1).put("deviceId", 0).put("name", "x").put("platform", org.json.JSONObject().put("type", "future_platform")))
        json.put("not an object")
        assertEquals(all.take(2), EntityCodec.decodeEntities(json))
        assertTrue(EntityCodec.decodeEntities(null).isEmpty())
    }

    @Test fun deviceDetailsRoundTrip() {
        val d = DeviceDetails("esp32dev", "Espressif", "2026.11.0", "acme.lamp 1.0", "Hallway")
        assertEquals(d, EntityCodec.decodeDetails(EntityCodec.encodeDetails(d)))
        assertNull(EntityCodec.decodeDetails(null))
    }
}
