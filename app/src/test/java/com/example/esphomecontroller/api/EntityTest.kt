package com.example.esphomecontroller.api

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class EntityTest {
    private val light = Entity(7, 0, "Lamp", Platform.Light(true), state = EntityState.Light(true, 0.7f))

    @Test fun reconnectMetadataKeepsLastStateUntilNewStateArrives() {
        val metadata = light.copy(state = null)
        val cached = mergeCachedEntities(listOf(light), listOf(metadata)).single()
        assertEquals(EntityState.Light(true, 0.7f), cached.state)
        val updated = metadata.copy(state = EntityState.Light(false, 0.2f))
        assertEquals(false, isOn(mergeCachedEntities(listOf(cached), listOf(updated)).single().state))
    }

    @Test fun missingStateReplacesOldValueAndNewEntitiesDoNotEraseOthers() {
        val sensor = Entity(8, 0, "Temperature", Platform.Sensor("°C", 1), state = EntityState.Numeric(21f))
        val merged = mergeCachedEntities(listOf(light, sensor), listOf(sensor.copy(state = EntityState.Missing)))
        assertEquals(2, merged.size); assertEquals(EntityState.Missing, merged[1].state); assertNull(displayValue(merged[1]))
        assertEquals(true, isOn(merged[0].state))
    }

    @Test fun commandsRequireFreshKnownStateOnCurrentConnection() {
        assertFalse(isCommandReady(false, light))
        assertFalse(isCommandReady(true, null))
        assertFalse(isCommandReady(true, light.copy(state = null)))
        assertTrue(isCommandReady(true, light))
        val select = Entity(42, 3, "Timer", Platform.Select(listOf("Disabled", "15 min")), state = EntityState.Text("Disabled"))
        assertTrue(isCommandReady(true, select))
        assertFalse(isCommandReady(true, select.copy(platform = Platform.Select(emptyList()))))
        assertFalse(isCommandReady(true, select.copy(state = EntityState.Missing)))
        assertTrue(isCommandReady(true, Entity(1, 0, "Restart", Platform.Button)))
        assertFalse(isCommandReady(true, Entity(1, 0, "Blinds", Platform.Unsupported("Cover"))))
        assertFalse(isCommandReady(true, Entity(1, 0, "Temp", Platform.Sensor(), state = EntityState.Numeric(1f))))
    }

    @Test fun platformsWithSameKeyAreDistinctAndWebIdentitySurvivesKeyChanges() {
        assertFalse(sameEntity(light, light.copy(platform = Platform.Switch)))
        assertTrue(sameEntity(light, light.copy(platform = Platform.Light(false))))
        val web = light.copy(key = 1, webId = "light/Lamp")
        assertTrue(sameEntity(web, web.copy(key = 9)))
        assertFalse(sameEntity(web, web.copy(webId = "light/Other")))
    }

    @Test fun sensorValuesUseAdvertisedAccuracy() {
        val sensor = Entity(1, 0, "Temp", Platform.Sensor("°C", 1), state = EntityState.Numeric(21.4567f))
        assertEquals("21.5 °C", displayValue(sensor, Locale.ROOT))
        assertEquals("21", displayValue(sensor.copy(platform = Platform.Sensor("", 0)), Locale.ROOT))
        assertEquals("21,5 °C", displayValue(sensor, Locale.GERMANY))
        assertEquals("On", displayValue(Entity(2, 0, "Door", Platform.BinarySensor, state = EntityState.OnOff(true))))
    }

    @Test fun numberValuesSnapToAdvertisedStepAndRange() {
        val p = Platform.Number(0f, 10f, 0.5f, "s")
        assertEquals(1, decimalsForStep(0.5f)); assertEquals(0, decimalsForStep(1f)); assertEquals(2, decimalsForStep(0.05f))
        assertEquals(2.5f, snapToStep(2.6f, p), 0f)
        assertEquals(10f, snapToStep(42f, p), 0f)
        assertEquals(0f, snapToStep(-1f, p), 0f)
        assertEquals(0.3f, snapToStep(0.31f, Platform.Number(0f, 1f, 0.1f)), 0f)
        assertEquals("2.5 s", displayValue(Entity(1, 0, "Delay", p, state = EntityState.Numeric(2.5f)), Locale.ROOT))
    }
}
