package com.example.esphomecontroller.api

import org.junit.Assert.*
import org.junit.Test
import com.example.esphomecontroller.proto.Api

class EntityCacheTest {
    private val light = DeviceSession.Entity(7, 0, "Lamp", "light", state = true, brightness = 0.7f, supportsBrightness = true, receivedState = true)
    @Test fun reconnectMetadataKeepsLastStateUntilNewStateArrives() {
        val metadata = light.copy(state = null, brightness = null, receivedState = false)
        val cached = mergeCachedEntities(listOf(light), listOf(metadata)).single()
        assertEquals(true, cached.state); assertEquals(0.7f, cached.brightness!!, 0.001f)
        val updated = metadata.copy(state = false, brightness = 0.2f, receivedState = true)
        assertEquals(false, mergeCachedEntities(listOf(cached), listOf(updated)).single().state)
    }
    @Test fun missingStateClearsOldValueAndNewEntitiesDoNotEraseOthers() {
        val sensor = DeviceSession.Entity(8, 0, "Temperature", "sensor", value = "21", receivedState = true)
        val missing = sensor.copy(value = null, receivedState = true)
        val merged = mergeCachedEntities(listOf(light, sensor), listOf(missing))
        assertEquals(2, merged.size); assertNull(merged[1].value); assertEquals(true, merged[0].state)
    }
    @Test fun controlsRequireFreshKnownStateOnCurrentConnection() {
        assertFalse(isControlReady(false, light))
        assertFalse(isControlReady(true, null))
        assertFalse(isControlReady(true, light.copy(receivedState = false)))
        assertFalse(isControlReady(true, light.copy(state = null)))
        assertTrue(isControlReady(true, light))
    }
    @Test fun selectRequiresFreshStateAndAdvertisedOptions() {
        val select = DeviceSession.Entity(42, 3, "Timer", "select", value = "Disabled", options = listOf("Disabled", "15 min"), receivedState = true)
        assertFalse(isSelectReady(false, select)); assertFalse(isSelectReady(true, select.copy(options = emptyList())))
        assertFalse(isSelectReady(true, select.copy(value = null))); assertTrue(isSelectReady(true, select))
        val frame = createNativeSelectCommand(select, "15 min")!!
        assertEquals(54, frame.type)
        val message = Api.SelectCommandRequest.parseFrom(frame.payload)
        assertEquals(42, message.key); assertEquals(3, message.deviceId); assertEquals("15 min", message.state)
        assertNull(createNativeSelectCommand(select, "not advertised"))
    }
    @Test fun webIdentitySurvivesSessionKeyChanges() {
        val web = light.copy(key = 1, webId = "light/Lamp")
        assertTrue(sameEntity(web, web.copy(key = 9)))
        assertFalse(sameEntity(web, web.copy(webId = "light/Other")))
    }
}
