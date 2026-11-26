package com.example.esphomecontroller.api

import com.example.esphomecontroller.proto.Api
import com.example.esphomecontroller.relativeTime
import org.junit.Assert.*
import org.junit.Test

class DashboardModelTest {
    @Test fun binarySensorTextFollowsDeviceClass() {
        fun text(dc: String, on: Boolean) = displayValue(Entity(1, 0, "x", Platform.BinarySensor, state = EntityState.OnOff(on), deviceClass = dc))
        assertEquals("Open", text("door", true)); assertEquals("Closed", text("window", false))
        assertEquals("Detected", text("motion", true)); assertEquals("Clear", text("occupancy", false))
        assertEquals("Wet", text("moisture", true)); assertEquals("Unlocked", text("lock", true))
        assertEquals("On", text("", true)); assertEquals("Off", text("something_new", false))
        // Switches keep On/Off even with a device class.
        assertEquals("On", displayValue(Entity(2, 0, "s", Platform.Switch, state = EntityState.OnOff(true), deviceClass = "outlet")))
    }

    @Test fun decoderKeepsDeviceClassAndIcon() {
        val door = (decodeNativeFrame(ApiFrame(12, Api.ListEntitiesBinarySensorResponse.newBuilder().setKey(1).setName("Door").setDeviceClass("door").setIcon("mdi:door").build().toByteArray())) as NativeEvent.EntityListed).entity
        assertEquals("door", door.deviceClass); assertEquals("mdi:door", door.icon)
        val light = (decodeNativeFrame(ApiFrame(15, Api.ListEntitiesLightResponse.newBuilder().setKey(2).setName("L").setIcon("mdi:lamp").build().toByteArray())) as NativeEvent.EntityListed).entity
        assertEquals("mdi:lamp", light.icon); assertEquals("", light.deviceClass)
        val player = (decodeNativeFrame(ApiFrame(63, Api.ListEntitiesMediaPlayerResponse.newBuilder().setKey(3).setName("P").setIcon("mdi:speaker").build().toByteArray())) as NativeEvent.EntityListed).entity
        assertEquals("mdi:speaker", player.icon)
        assertTrue(Platform.Sensor().isReadOnly && Platform.BinarySensor.isReadOnly && Platform.TextSensor.isReadOnly)
        assertFalse(Platform.Switch.isReadOnly || Platform.Button.isReadOnly)
    }

    @Test fun retryUsesTheLiveEntityOnlyWhenReady() {
        val failed = FailedCommand()
        val stale = Entity(1, 0, "Relay", Platform.Switch, state = EntityState.OnOff(false))
        failed.set(stale, Command.SetOn(true))
        val sent = mutableListOf<Pair<Entity, Command>>()
        failed.retry(emptyList(), connected = true) { e, c -> sent += e to c }
        assertTrue(sent.isEmpty())
        failed.retry(listOf(stale.copy(state = null)), connected = true) { e, c -> sent += e to c }
        assertTrue("no fresh state yet", sent.isEmpty())
        val live = stale.copy(state = EntityState.OnOff(false), name = "Relay (renamed)")
        failed.retry(listOf(live), connected = false) { e, c -> sent += e to c }
        assertTrue("disconnected", sent.isEmpty())
        failed.retry(listOf(live), connected = true) { e, c -> sent += e to c }
        assertEquals(listOf(live to Command.SetOn(true)), sent)
        assertNotEquals(CommandError("a").serial, CommandError("a").serial)
    }

    @Test fun relativeTimesAndLayoutKeys() {
        val now = 10_000_000L
        assertEquals("just now", relativeTime(now - 30_000, now)); assertEquals("5 min ago", relativeTime(now - 5 * 60_000, now))
        assertEquals("3 h ago", relativeTime(now - 3 * 3_600_000, now)); assertEquals("2 d ago", relativeTime(now - 2 * 86_400_000L, now - 0))
        assertEquals("just now", relativeTime(now + 5000, now))
    }

    @Test fun failureReasonsHideAddresses() {
        assertEquals("The device refused the connection", failureReason(java.net.ConnectException("failed to connect to /192.0.2.21 (port 6053) after 8000ms: isConnected failed: ECONNREFUSED (Connection refused)")))
        assertEquals("Device stopped responding", failureReason(java.net.SocketTimeoutException("Read timed out")))
        assertEquals("Connection failed", failureReason(java.io.IOException("cannot reach 192.0.2.5")))
        assertEquals("The device rejected the encryption key", failureReason(KeyRejectedException()))
        assertEquals("Unsupported API version 2.0", failureReason(java.io.IOException("Unsupported API version 2.0")))
    }

    @Test fun placeLineJoinsHomeAndArea() {
        assertEquals("My location · Hallway", com.example.esphomecontroller.placeLine("My location", "Hallway"))
        assertEquals("My location", com.example.esphomecontroller.placeLine("My location", ""))
        assertEquals("Hallway", com.example.esphomecontroller.placeLine(null, "Hallway"))
        assertNull(com.example.esphomecontroller.placeLine(null, null))
    }
}
