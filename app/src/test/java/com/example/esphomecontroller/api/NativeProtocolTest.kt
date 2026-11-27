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
        val withArea = decodeNativeFrame(frame(10, Api.DeviceInfoResponse.newBuilder().setSuggestedArea("Old area").setArea(Api.AreaInfo.newBuilder().setName("Hallway")).build())) as NativeEvent.Info
        assertEquals("Hallway", withArea.details.area)
        assertEquals("Bedroom", (decodeNativeFrame(frame(10, Api.DeviceInfoResponse.newBuilder().setSuggestedArea("Bedroom").build())) as NativeEvent.Info).details.area)
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
        val player = listed(decodeNativeFrame(frame(63, Api.ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setName("Speaker").setDeviceId(2).setEntityCategory(Api.EntityCategory.ENTITY_CATEGORY_CONFIG).build())))
        assertEquals(Entity(11, 2, "Speaker", Platform.Unsupported("Media player"), EntityCategory.CONFIG), player)
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
        assertNull(nativeCommand(Entity(6, 0, "Speaker", Platform.Unsupported("Media player")), Command.Press))
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

    @Test fun decodesLightColourCapabilitiesAndState() {
        val info = Api.ListEntitiesLightResponse.newBuilder().setKey(1).setName("Strip")
            .addSupportedColorModesValue(Api.ColorMode.COLOR_MODE_RGB_COLOR_TEMPERATURE_VALUE).setMinMireds(153f).setMaxMireds(500f).addEffects("Rainbow").build()
        assertEquals(Platform.Light(true, true, 153f, 500f, listOf("Rainbow")), listed(decodeNativeFrame(frame(15, info))).platform)
        val ctOnly = Api.ListEntitiesLightResponse.newBuilder().setKey(2).addSupportedColorModesValue(Api.ColorMode.COLOR_MODE_COLD_WARM_WHITE_VALUE).setMinMireds(153f).setMaxMireds(370f).build()
        assertEquals(Platform.Light(true, false, 153f, 370f), listed(decodeNativeFrame(frame(15, ctOnly))).platform)
        val state = Api.LightStateResponse.newBuilder().setKey(1).setState(true).setBrightness(0.5f).setColorModeValue(Api.ColorMode.COLOR_MODE_RGB_VALUE).setRed(1f).setGreen(0f).setBlue(0.5f).setColorTemperature(300f).setEffect("None").build()
        assertEquals(EntityState.Light(true, 0.5f, Rgb(1f, 0f, 0.5f), null, null), (decodeNativeFrame(frame(24, state)) as NativeEvent.StateChanged).state)
        val light = Entity(1, 0, "Strip", Platform.Light(true, true, 153f, 500f, listOf("Rainbow")))
        Api.LightCommandRequest.parseFrom(nativeCommand(light, Command.SetRgb(Rgb(0f, 1f, 0f)))!!.payload).let { assertTrue(it.hasRgb); assertEquals(1f, it.green, 0f) }
        Api.LightCommandRequest.parseFrom(nativeCommand(light, Command.SetColorTemperature(250f))!!.payload).let { assertTrue(it.hasColorTemperature); assertEquals(250f, it.colorTemperature, 0f) }
        assertNull(nativeCommand(light, Command.SetColorTemperature(600f)))
        assertEquals("None", Api.LightCommandRequest.parseFrom(nativeCommand(light, Command.SetEffect("None"))!!.payload).effect)
        assertNull(nativeCommand(light, Command.SetEffect("Unknown")))
        assertNull(nativeCommand(Entity(2, 0, "Plain", Platform.Light(true)), Command.SetRgb(Rgb(1f, 0f, 0f))))
    }

    @Test fun coversAndValvesUsePositionCommands() {
        val listing = Api.ListEntitiesCoverResponse.newBuilder().setKey(11).setName("Blinds").setSupportsPosition(true).setSupportsStop(true).build()
        val cover = listed(decodeNativeFrame(frame(13, listing)))
        assertEquals(Platform.Cover(supportsPosition = true, supportsTilt = false, supportsStop = true), cover.platform)
        assertEquals(EntityState.Positioned(0.4f, 0f, Operation.CLOSING), (decodeNativeFrame(frame(22, Api.CoverStateResponse.newBuilder().setKey(11).setPosition(0.4f).setCurrentOperation(Api.CoverOperation.COVER_OPERATION_IS_CLOSING).build())) as NativeEvent.StateChanged).state)
        Api.CoverCommandRequest.parseFrom(nativeCommand(cover, Command.Open)!!.payload).let { assertTrue(it.hasPosition); assertEquals(1f, it.position, 0f) }
        assertTrue(Api.CoverCommandRequest.parseFrom(nativeCommand(cover, Command.Stop)!!.payload).stop)
        assertEquals(0.25f, Api.CoverCommandRequest.parseFrom(nativeCommand(cover, Command.SetPosition(0.25f))!!.payload).position, 0f)
        assertNull(nativeCommand(cover, Command.SetTilt(0.5f)))
        val valve = Entity(12, 0, "Water", Platform.Valve(supportsPosition = false, supportsStop = false))
        assertEquals(30, nativeCommand(cover, Command.Close)!!.type); assertEquals(111, nativeCommand(valve, Command.Close)!!.type)
        assertNull(nativeCommand(valve, Command.Stop)); assertNull(nativeCommand(valve, Command.SetPosition(0.5f)))
    }

    @Test fun fansRespectAdvertisedFeatures() {
        val fan = listed(decodeNativeFrame(frame(14, Api.ListEntitiesFanResponse.newBuilder().setKey(3).setName("Fan").setSupportsSpeed(true).setSupportedSpeedCount(3).setSupportsOscillation(true).addSupportedPresetModes("Sleep").build())))
        assertEquals(Platform.Fan(supportsOscillation = true, supportsDirection = false, speedCount = 3, presets = listOf("Sleep")), fan.platform)
        assertEquals(EntityState.Fan(true, false, true, 2, null), (decodeNativeFrame(frame(23, Api.FanStateResponse.newBuilder().setKey(3).setState(true).setSpeedLevel(2).setDirection(Api.FanDirection.FAN_DIRECTION_REVERSE).build())) as NativeEvent.StateChanged).state)
        Api.FanCommandRequest.parseFrom(nativeCommand(fan, Command.SetFanSpeed(3))!!.payload).let { assertTrue(it.hasSpeedLevel); assertEquals(3, it.speedLevel) }
        assertNull(nativeCommand(fan, Command.SetFanSpeed(4))); assertNull(nativeCommand(fan, Command.SetDirection(true)))
        assertEquals("Sleep", Api.FanCommandRequest.parseFrom(nativeCommand(fan, Command.SetPreset("Sleep"))!!.payload).presetMode)
    }

    @Test fun locksRequireCodeWhenAdvertisedAndOpenOnlyWhenSupported() {
        val lock = listed(decodeNativeFrame(frame(58, Api.ListEntitiesLockResponse.newBuilder().setKey(4).setName("Door").setRequiresCode(true).build())))
        assertEquals(Platform.Lock(supportsOpen = false, requiresCode = true), lock.platform)
        assertEquals(EntityState.Lock(LockStatus.JAMMED), (decodeNativeFrame(frame(59, Api.LockStateResponse.newBuilder().setKey(4).setState(Api.LockState.LOCK_STATE_JAMMED).build())) as NativeEvent.StateChanged).state)
        assertNull(nativeCommand(lock, Command.Lock(LockAction.UNLOCK)))
        Api.LockCommandRequest.parseFrom(nativeCommand(lock, Command.Lock(LockAction.UNLOCK, "1234"))!!.payload).let { assertEquals(Api.LockCommand.LOCK_UNLOCK, it.command); assertTrue(it.hasCode); assertEquals("1234", it.code) }
        assertNull(nativeCommand(lock, Command.Lock(LockAction.OPEN, "1234")))
    }

    @Test fun climateFeaturesModesAndCommands() {
        val listing = Api.ListEntitiesClimateResponse.newBuilder().setKey(5).setName("Thermostat")
            .addSupportedModes(Api.ClimateMode.CLIMATE_MODE_OFF).addSupportedModes(Api.ClimateMode.CLIMATE_MODE_HEAT_COOL)
            .setVisualMinTemperature(10f).setVisualMaxTemperature(30f).setVisualTargetTemperatureStep(0.5f)
            .addSupportedFanModes(Api.ClimateFanMode.CLIMATE_FAN_AUTO).addSupportedCustomFanModes("Turbo")
            .addSupportedPresets(Api.ClimatePreset.CLIMATE_PRESET_ECO).setFeatureFlags(1 or 2 or 32).build()
        val climate = listed(decodeNativeFrame(frame(46, listing)))
        val p = climate.platform as Platform.Climate
        assertEquals(listOf("Off", "Heat cool"), p.modes); assertEquals(listOf("Auto", "Turbo"), p.fanModes); assertEquals(listOf("Eco"), p.presets)
        assertTrue(p.twoPoint && p.currentTemperature && p.action); assertFalse(p.currentHumidity)
        @Suppress("DEPRECATION") val legacy = listing.toBuilder().setFeatureFlags(0).setSupportsTwoPointTargetTemperature(false).setSupportsCurrentTemperature(true).build()
        assertEquals(1, climateFeatures(legacy))

        val state = decodeNativeFrame(frame(47, Api.ClimateStateResponse.newBuilder().setKey(5).setMode(Api.ClimateMode.CLIMATE_MODE_HEAT_COOL).setCurrentTemperature(21.3f).setTargetTemperatureLow(19f).setTargetTemperatureHigh(24f).setCustomFanMode("Turbo").setAction(Api.ClimateAction.CLIMATE_ACTION_HEATING).build()))
        val s = (state as NativeEvent.StateChanged).state as EntityState.Climate
        assertEquals("Heat cool", s.mode); assertEquals("Turbo", s.fanMode); assertEquals("Heating", s.action); assertEquals(19f, s.targetLow!!, 0f)
        assertEquals(NativeEvent.StateChanged(5, 0, EntityState.Missing), decodeNativeFrame(frame(47, Api.ClimateStateResponse.newBuilder().setKey(5).setMissingState(true).build())))

        assertEquals(Api.ClimateMode.CLIMATE_MODE_HEAT_COOL, Api.ClimateCommandRequest.parseFrom(nativeCommand(climate, Command.SetClimateMode("Heat cool"))!!.payload).mode)
        assertNull(nativeCommand(climate, Command.SetClimateMode("Cool")))
        Api.ClimateCommandRequest.parseFrom(nativeCommand(climate, Command.SetTargetRange(18f, 25f))!!.payload).let { assertTrue(it.hasTargetTemperatureLow && it.hasTargetTemperatureHigh) }
        assertNull(nativeCommand(climate, Command.SetTargetTemperature(20f)))
        assertNull(nativeCommand(climate, Command.SetTargetRange(5f, 25f)))
        Api.ClimateCommandRequest.parseFrom(nativeCommand(climate, Command.SetFanMode("Turbo"))!!.payload).let { assertTrue(it.hasCustomFanMode); assertFalse(it.hasFanMode) }
        Api.ClimateCommandRequest.parseFrom(nativeCommand(climate, Command.SetFanMode("Auto"))!!.payload).let { assertEquals(Api.ClimateFanMode.CLIMATE_FAN_AUTO, it.fanMode) }
        assertEquals(Api.ClimatePreset.CLIMATE_PRESET_ECO, Api.ClimateCommandRequest.parseFrom(nativeCommand(climate, Command.SetPreset("Eco"))!!.payload).preset)
        assertEquals("21.3 °C", displayValue(climate.copy(state = s), java.util.Locale.ROOT))
    }

    @Test fun logsAreDecodedWithoutColourCodesAndBounded() {
        val log = decodeNativeFrame(frame(29, Api.SubscribeLogsResponse.newBuilder().setLevelValue(3).setMessage(com.google.protobuf.ByteString.copyFromUtf8("\u001B[0;32m[I][app:100]: Running\u001B[0m")).build()))
        assertEquals(NativeEvent.Log(3, "[I][app:100]: Running"), log)
        subscribeLogsFrame().let { assertEquals(28, it.type); assertEquals(Api.LogLevel.LOG_LEVEL_VERY_VERBOSE, Api.SubscribeLogsRequest.parseFrom(it.payload).level) }
        val lines = (1..MAX_LOG_LINES + 5).fold(emptyList<String>()) { acc, i -> appendLog(acc, "line $i") }
        assertEquals(MAX_LOG_LINES, lines.size); assertEquals("line ${MAX_LOG_LINES + 5}", lines.last())
        subscribeLogsFrame(LogLevel.DEBUG.protoValue).let { assertEquals(Api.LogLevel.LOG_LEVEL_DEBUG, Api.SubscribeLogsRequest.parseFrom(it.payload).level) }
        stateLogLine(Entity(1, 0, "Sleep timer remaining", Platform.TextSensor, state = EntityState.Text("Disabled")), time = 0, seq = 1).let {
            assertTrue(it.isState); assertEquals("Sleep timer remaining", it.tag); assertEquals("Disabled", it.message)
            assertEquals("00:00:00.000 [state] Sleep timer remaining: Disabled", it.export(java.time.ZoneOffset.UTC))
        }
        assertEquals("unknown", stateLogLine(Entity(2, 0, "Temp", Platform.Sensor(), state = EntityState.Missing)).message)
    }
}
