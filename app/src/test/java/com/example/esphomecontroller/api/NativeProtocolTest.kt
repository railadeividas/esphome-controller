package com.example.esphomecontroller.api

import com.example.esphomecontroller.proto.Api
import com.google.protobuf.InvalidProtocolBufferException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class NativeProtocolTest {
    private fun frame(type: Int, message: com.google.protobuf.MessageLite) = ApiFrame(type, message.toByteArray())
    private fun listed(event: NativeEvent) = (event as NativeEvent.EntityListed).entity

    @Test fun messageTypeConstantsMatchProtoOptions() {
        val ids = Api.getDescriptor().messageTypes.associate { it.name to it.options.getExtension(com.example.esphomecontroller.proto.ApiOptions.id) }
        assertEquals(MessageType.HELLO_REQUEST, ids["HelloRequest"]); assertEquals(MessageType.LIST_ENTITIES_DONE, ids["ListEntitiesDoneResponse"])
        assertEquals(MessageType.SUBSCRIBE_STATES_REQUEST, ids["SubscribeStatesRequest"]); assertEquals(MessageType.DEVICE_INFO_RESPONSE, ids["DeviceInfoResponse"])
        assertEquals(MessageType.LIGHT_COMMAND, ids["LightCommandRequest"]); assertEquals(MessageType.SWITCH_COMMAND, ids["SwitchCommandRequest"])
        assertEquals(MessageType.NUMBER_COMMAND, ids["NumberCommandRequest"]); assertEquals(MessageType.SELECT_COMMAND, ids["SelectCommandRequest"])
        assertEquals(MessageType.BUTTON_COMMAND, ids["ButtonCommandRequest"]); assertEquals(MessageType.PING_REQUEST, ids["PingRequest"])
    }

    @Test fun decodesHelloKeepaliveAndDeviceInfoWithoutIdentifiers() {
        val hello = decodeNativeFrame(frame(2, Api.HelloResponse.newBuilder().setApiVersionMajor(1).setApiVersionMinor(12).setKeepaliveInterval(30).build()))
        assertEquals(NativeEvent.Hello(1, 12, 30), hello)
        val info = decodeNativeFrame(frame(10, Api.DeviceInfoResponse.newBuilder().setModel("esp32dev").setEsphomeVersion("2026.11.0").setMacAddress("00:00:00:00:00:00").setProjectName("acme.plug").setProjectVersion("1.2").build()))
        assertEquals(NativeEvent.Info(DeviceDetails("esp32dev", "", "2026.11.0", "acme.plug 1.2")), info)
    }

    @Test fun decodesEntityMetadataAndCategories() {
        val sensor = listed(decodeNativeFrame(frame(16, Api.ListEntitiesSensorResponse.newBuilder().setKey(5).setObjectId("temp").setUnitOfMeasurement("°C").setAccuracyDecimals(1).setEntityCategory(Api.EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC).build())))
        assertEquals("temp", sensor.name); assertEquals(Platform.Sensor("°C", 1), sensor.platform); assertEquals(EntityCategory.DIAGNOSTIC, sensor.category)
        val light = listed(decodeNativeFrame(frame(15, Api.ListEntitiesLightResponse.newBuilder().setKey(1).setName("Lamp").addSupportedColorModesValue(Api.ColorMode.COLOR_MODE_BRIGHTNESS_VALUE).build())))
        assertEquals(Platform.Light(true), light.platform)
        val onOff = listed(decodeNativeFrame(frame(15, Api.ListEntitiesLightResponse.newBuilder().setKey(2).setName("Relay").addSupportedColorModesValue(Api.ColorMode.COLOR_MODE_ON_OFF_VALUE).build())))
        assertEquals(Platform.Light(false), onOff.platform)
        val number = listed(decodeNativeFrame(frame(49, Api.ListEntitiesNumberResponse.newBuilder().setKey(3).setName("Delay").setMinValue(0f).setMaxValue(60f).setStep(0.5f).setUnitOfMeasurement("s").setMode(Api.NumberMode.NUMBER_MODE_BOX).setEntityCategory(Api.EntityCategory.ENTITY_CATEGORY_CONFIG).build())))
        assertEquals(Platform.Number(0f, 60f, 0.5f, "s", NumberMode.BOX), number.platform); assertEquals(EntityCategory.CONFIG, number.category)
        val button = listed(decodeNativeFrame(frame(61, Api.ListEntitiesButtonResponse.newBuilder().setKey(4).setName("Restart").setDeviceId(9).build())))
        assertEquals(Platform.Button, button.platform); assertEquals(9, button.deviceId)
        assertEquals(NativeEvent.Ignored, decodeNativeFrame(frame(17, Api.ListEntitiesSwitchResponse.newBuilder().setKey(6).setName("Hidden").setDisabledByDefault(true).build())))
    }

    @Test fun unsupportedPlatformsAreListedGenericallyAndRespectDisabledFlag() {
        val cover = listed(decodeNativeFrame(frame(13, Api.ListEntitiesCoverResponse.newBuilder().setKey(11).setName("Blinds").setDeviceId(2).setEntityCategory(Api.EntityCategory.ENTITY_CATEGORY_CONFIG).build())))
        assertEquals(Entity(11, 2, "Blinds", Platform.Unsupported("Cover"), EntityCategory.CONFIG), cover)
        val alarm = listed(decodeNativeFrame(frame(94, Api.ListEntitiesAlarmControlPanelResponse.newBuilder().setKey(12).setObjectId("alarm").build())))
        assertEquals(Platform.Unsupported("Alarm control panel"), alarm.platform)
        assertEquals(NativeEvent.Ignored, decodeNativeFrame(frame(14, Api.ListEntitiesFanResponse.newBuilder().setKey(13).setName("Fan").setDisabledByDefault(true).build())))
        assertTrue(entityListings.values.none { it.name == "ListEntitiesServicesResponse" || it.name == "ListEntitiesDoneResponse" })
        assertEquals(NativeEvent.Ignored, decodeNativeFrame(ApiFrame(60000, byteArrayOf(1, 2, 3))))
    }

    @Test fun decodesStatesIncludingMissingAndInvalidValues() {
        assertEquals(NativeEvent.StateChanged(5, 0, EntityState.Numeric(21.5f)), decodeNativeFrame(frame(25, Api.SensorStateResponse.newBuilder().setKey(5).setState(21.5f).build())))
        assertEquals(NativeEvent.StateChanged(5, 0, EntityState.Missing), decodeNativeFrame(frame(25, Api.SensorStateResponse.newBuilder().setKey(5).setState(Float.NaN).build())))
        assertEquals(NativeEvent.StateChanged(5, 0, EntityState.Missing), decodeNativeFrame(frame(25, Api.SensorStateResponse.newBuilder().setKey(5).setMissingState(true).build())))
        assertEquals(NativeEvent.StateChanged(6, 1, EntityState.Missing), decodeNativeFrame(frame(26, Api.SwitchStateResponse.newBuilder().setKey(6).setDeviceId(1).setMissingState(true).build())))
        assertEquals(NativeEvent.StateChanged(1, 0, EntityState.Light(true, 1f)), decodeNativeFrame(frame(24, Api.LightStateResponse.newBuilder().setKey(1).setState(true).setBrightness(1.5f).build())))
        assertEquals(NativeEvent.StateChanged(3, 0, EntityState.Numeric(2.5f)), decodeNativeFrame(frame(50, Api.NumberStateResponse.newBuilder().setKey(3).setState(2.5f).build())))
        assertEquals(NativeEvent.StateChanged(7, 0, EntityState.Text("15 min")), decodeNativeFrame(frame(53, Api.SelectStateResponse.newBuilder().setKey(7).setState("15 min").build())))
    }

    @Test fun stateUpdatesMatchKeyAndSubDevice() {
        val a = Entity(1, 0, "A", Platform.Switch); val b = Entity(1, 2, "B", Platform.Switch)
        val updated = applyState(listOf(a, b), NativeEvent.StateChanged(1, 2, EntityState.OnOff(true)))
        assertNull(updated[0].state); assertEquals(EntityState.OnOff(true), updated[1].state)
    }

    @Test fun malformedPayloadsAreRejected() {
        val truncated = ApiFrame(25, byteArrayOf(0x0d, 1, 2))  // fixed32 key cut short
        assertThrows(InvalidProtocolBufferException::class.java) { decodeNativeFrame(truncated) }
        assertThrows(InvalidProtocolBufferException::class.java) { decodeNativeFrame(ApiFrame(13, byteArrayOf(0x15, 1))) }
    }

    @Test fun commandsMatchAdvertisedCapabilities() {
        val select = Entity(42, 3, "Timer", Platform.Select(listOf("Disabled", "15 min")))
        val frame = nativeCommand(select, Command.SelectOption("15 min"))!!
        assertEquals(54, frame.type)
        Api.SelectCommandRequest.parseFrom(frame.payload).let { assertEquals(42, it.key); assertEquals(3, it.deviceId); assertEquals("15 min", it.state) }
        assertNull(nativeCommand(select, Command.SelectOption("not advertised")))

        val light = Entity(1, 0, "Lamp", Platform.Light(true))
        Api.LightCommandRequest.parseFrom(nativeCommand(light, Command.SetBrightness(0.4f))!!.payload).let { assertTrue(it.hasBrightness); assertEquals(0.4f, it.brightness, 0f); assertTrue(it.hasState && it.state) }
        assertNull(nativeCommand(light.copy(platform = Platform.Light(false)), Command.SetBrightness(0.4f)))
        assertEquals(33, nativeCommand(Entity(2, 0, "Relay", Platform.Switch), Command.SetOn(false))!!.type)

        val number = Entity(3, 1, "Delay", Platform.Number(0f, 60f, 0.5f))
        nativeCommand(number, Command.SetNumber(12.5f))!!.let { assertEquals(51, it.type); assertEquals(12.5f, Api.NumberCommandRequest.parseFrom(it.payload).state, 0f) }
        assertNull(nativeCommand(number, Command.SetNumber(61f))); assertNull(nativeCommand(number, Command.SetNumber(Float.NaN)))

        nativeCommand(Entity(4, 9, "Restart", Platform.Button), Command.Press)!!.let { assertEquals(62, it.type); assertEquals(9, Api.ButtonCommandRequest.parseFrom(it.payload).deviceId) }
        assertNull(nativeCommand(Entity(5, 0, "Temp", Platform.Sensor()), Command.SetOn(true)))
        assertNull(nativeCommand(Entity(6, 0, "Blinds", Platform.Unsupported("Cover")), Command.Press))
    }

    /** A recorded-style session: frames survive the plaintext codec and decode in order. */
    @Test fun plaintextStreamOfListAndStateFramesDecodes() {
        val out = ByteArrayOutputStream()
        PlaintextFrames.write(out, frame(17, Api.ListEntitiesSwitchResponse.newBuilder().setKey(6).setName("Relay").build()))
        PlaintextFrames.write(out, ApiFrame(19, byteArrayOf()))
        PlaintextFrames.write(out, frame(26, Api.SwitchStateResponse.newBuilder().setKey(6).setState(true).build()))
        val input = ByteArrayInputStream(out.toByteArray())
        var entities = emptyList<Entity>()
        val events = List(3) { decodeNativeFrame(PlaintextFrames.read(input)) }
        events.forEach { e -> when (e) { is NativeEvent.EntityListed -> entities = entities + e.entity; is NativeEvent.StateChanged -> entities = applyState(entities, e); else -> Unit } }
        assertEquals(NativeEvent.EntitiesDone, events[1])
        assertEquals(EntityState.OnOff(true), entities.single().state)
    }
}
