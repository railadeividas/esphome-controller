package com.example.esphomecontroller.api

import com.example.esphomecontroller.proto.Api
import com.example.esphomecontroller.proto.ApiOptions
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import com.google.protobuf.InvalidProtocolBufferException

/** Message type numbers, from the `(id)` options in api.proto. */
object MessageType {
    const val HELLO_REQUEST = 1
    const val HELLO_RESPONSE = 2
    const val AUTHENTICATION_REQUEST = 3
    const val AUTHENTICATION_RESPONSE = 4
    const val DISCONNECT_REQUEST = 5
    const val DISCONNECT_RESPONSE = 6
    const val PING_REQUEST = 7
    const val PING_RESPONSE = 8
    const val DEVICE_INFO_REQUEST = 9
    const val DEVICE_INFO_RESPONSE = 10
    const val LIST_ENTITIES_REQUEST = 11
    const val LIST_ENTITIES_DONE = 19
    const val SUBSCRIBE_STATES_REQUEST = 20
    const val LIGHT_COMMAND = 32
    const val SWITCH_COMMAND = 33
    const val NUMBER_COMMAND = 51
    const val SELECT_COMMAND = 54
    const val BUTTON_COMMAND = 62
    const val SUBSCRIBE_LOGS_REQUEST = 28
    const val SUBSCRIBE_LOGS_RESPONSE = 29
    const val COVER_COMMAND = 30
    const val FAN_COMMAND = 31
    const val CLIMATE_COMMAND = 48
    const val LOCK_COMMAND = 60
    const val VALVE_COMMAND = 111
}

sealed interface NativeEvent {
    data class Hello(val apiMajor: Int, val apiMinor: Int, val keepaliveSeconds: Int) : NativeEvent
    data object AuthenticationRejected : NativeEvent
    data object DisconnectRequested : NativeEvent
    data object PingRequested : NativeEvent
    data class Info(val details: DeviceDetails) : NativeEvent
    data class EntityListed(val entity: Entity) : NativeEvent
    data object EntitiesDone : NativeEvent
    data class StateChanged(val key: Int, val deviceId: Int, val state: EntityState) : NativeEvent
    data class Log(val level: Int, val text: String) : NativeEvent
    /** Unknown, unsupported or deliberately skipped (for example disabled-by-default) messages. */
    data object Ignored : NativeEvent
}

private fun name(name: String, objectId: String) = name.ifBlank { objectId }
private fun category(c: Api.EntityCategory) = EntityCategory.fromNumber(c.number)

/**
 * Decodes one Native API frame without side effects. Malformed payloads throw
 * [InvalidProtocolBufferException]; unknown message types are ignored.
 */
fun decodeNativeFrame(frame: ApiFrame): NativeEvent {
    val payload = frame.payload
    return when (frame.type) {
        MessageType.HELLO_RESPONSE -> Api.HelloResponse.parseFrom(payload).let { NativeEvent.Hello(it.apiVersionMajor, it.apiVersionMinor, it.keepaliveInterval) }
        MessageType.AUTHENTICATION_RESPONSE -> if (Api.AuthenticationResponse.parseFrom(payload).invalidPassword) NativeEvent.AuthenticationRejected else NativeEvent.Ignored
        MessageType.DISCONNECT_REQUEST -> NativeEvent.DisconnectRequested
        MessageType.PING_REQUEST -> NativeEvent.PingRequested
        MessageType.DEVICE_INFO_RESPONSE -> Api.DeviceInfoResponse.parseFrom(payload).let { p ->
            val project = listOf(p.projectName, p.projectVersion).filter { it.isNotBlank() }.joinToString(" ")
            NativeEvent.Info(DeviceDetails(p.model, p.manufacturer, p.esphomeVersion, project))
        }
        MessageType.LIST_ENTITIES_DONE -> NativeEvent.EntitiesDone
        12 -> Api.ListEntitiesBinarySensorResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.BinarySensor, category(p.entityCategory))) }
        15 -> Api.ListEntitiesLightResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), lightPlatform(p), category(p.entityCategory))) }
        16 -> Api.ListEntitiesSensorResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.Sensor(p.unitOfMeasurement, p.accuracyDecimals.takeIf { it >= 0 }), category(p.entityCategory))) }
        17 -> Api.ListEntitiesSwitchResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.Switch, category(p.entityCategory))) }
        18 -> Api.ListEntitiesTextSensorResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.TextSensor, category(p.entityCategory))) }
        49 -> Api.ListEntitiesNumberResponse.parseFrom(payload).let { p ->
            val mode = when (p.mode) { Api.NumberMode.NUMBER_MODE_BOX -> NumberMode.BOX; Api.NumberMode.NUMBER_MODE_SLIDER -> NumberMode.SLIDER; else -> NumberMode.AUTO }
            listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.Number(p.minValue, p.maxValue, p.step, p.unitOfMeasurement, mode), category(p.entityCategory)))
        }
        52 -> Api.ListEntitiesSelectResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.Select(p.optionsList.toList()), category(p.entityCategory))) }
        61 -> Api.ListEntitiesButtonResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.Button, category(p.entityCategory))) }
        13 -> Api.ListEntitiesCoverResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.Cover(p.supportsPosition, p.supportsTilt, p.supportsStop), category(p.entityCategory))) }
        14 -> Api.ListEntitiesFanResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.Fan(p.supportsOscillation, p.supportsDirection, if (p.supportsSpeed) p.supportedSpeedCount.coerceAtLeast(0) else 0, p.supportedPresetModesList.toList()), category(p.entityCategory))) }
        46 -> Api.ListEntitiesClimateResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), climatePlatform(p), category(p.entityCategory))) }
        58 -> Api.ListEntitiesLockResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.Lock(p.supportsOpen, p.requiresCode, p.codeFormat), category(p.entityCategory))) }
        109 -> Api.ListEntitiesValveResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.Valve(p.supportsPosition, p.supportsStop), category(p.entityCategory))) }
        22 -> Api.CoverStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, EntityState.Positioned(p.position.unit(), p.tilt.unit(), coverOperation(p.currentOperationValue))) }
        23 -> Api.FanStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, EntityState.Fan(p.state, p.oscillating, p.direction == Api.FanDirection.FAN_DIRECTION_REVERSE, p.speedLevel, p.presetMode.ifBlank { null })) }
        47 -> Api.ClimateStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, if (p.missingState) EntityState.Missing else climateState(p)) }
        59 -> Api.LockStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, EntityState.Lock(LockStatus.entries.getOrElse(p.stateValue) { LockStatus.UNKNOWN })) }
        110 -> Api.ValveStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, EntityState.Positioned(p.position.unit(), null, coverOperation(p.currentOperationValue))) }
        MessageType.SUBSCRIBE_LOGS_RESPONSE -> Api.SubscribeLogsResponse.parseFrom(payload).let { NativeEvent.Log(it.levelValue, stripAnsi(it.message.toStringUtf8())) }
        21 -> Api.BinarySensorStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, if (p.missingState) EntityState.Missing else EntityState.OnOff(p.state)) }
        24 -> Api.LightStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, lightState(p)) }
        25 -> Api.SensorStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, if (p.missingState || !p.state.isFinite()) EntityState.Missing else EntityState.Numeric(p.state)) }
        26 -> Api.SwitchStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, if (p.missingState) EntityState.Missing else EntityState.OnOff(p.state)) }
        27 -> Api.TextSensorStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, if (p.missingState) EntityState.Missing else EntityState.Text(p.state)) }
        50 -> Api.NumberStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, if (p.missingState || !p.state.isFinite()) EntityState.Missing else EntityState.Numeric(p.state)) }
        53 -> Api.SelectStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, if (p.missingState) EntityState.Missing else EntityState.Text(p.state)) }
        else -> unsupportedListing(frame) ?: NativeEvent.Ignored
    }
}

// ColorMode values are bit sets of capabilities (aioesphomeapi LightColorCapability).
private const val CAP_BRIGHTNESS = 1 shl 1
private const val CAP_COLOR_TEMPERATURE = 1 shl 3
private const val CAP_COLD_WARM_WHITE = 1 shl 4
private const val CAP_RGB = 1 shl 5

private fun Float.unit(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
private fun Float.finite(): Float? = takeIf { it.isFinite() }

@Suppress("DEPRECATION")
private fun lightPlatform(p: Api.ListEntitiesLightResponse): Platform.Light {
    val modes = p.supportedColorModesValueList
    val legacy = modes.isEmpty()
    val brightness = if (legacy) p.legacySupportsBrightness else modes.any { it and CAP_BRIGHTNESS != 0 }
    val rgb = if (legacy) p.legacySupportsRgb else modes.any { it and CAP_RGB != 0 }
    val ct = (if (legacy) p.legacySupportsColorTemperature else modes.any { it and (CAP_COLOR_TEMPERATURE or CAP_COLD_WARM_WHITE) != 0 }) && p.minMireds > 0f && p.maxMireds > p.minMireds
    return Platform.Light(brightness, rgb, if (ct) p.minMireds else null, if (ct) p.maxMireds else null, p.effectsList.filter { it.isNotBlank() })
}

private fun lightState(p: Api.LightStateResponse): EntityState.Light {
    val mode = p.colorModeValue
    val rgb = if (mode and CAP_RGB != 0) Rgb(p.red.unit(), p.green.unit(), p.blue.unit()) else null
    val mireds = if (mode and (CAP_COLOR_TEMPERATURE or CAP_COLD_WARM_WHITE) != 0) p.colorTemperature.finite()?.takeIf { it > 0f } else null
    val effect = p.effect.takeIf { it.isNotBlank() && !it.equals("None", ignoreCase = true) }
    return EntityState.Light(p.state, p.brightness.finite()?.coerceIn(0f, 1f), rgb, mireds, effect)
}

private fun coverOperation(value: Int) = when (value) { 1 -> Operation.OPENING; 2 -> Operation.CLOSING; else -> Operation.IDLE }

/** "CLIMATE_MODE_HEAT_COOL" -> "Heat cool". */
internal fun enumLabel(name: String, prefix: String) = name.removePrefix(prefix).lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

private inline fun <reified E : Enum<E>> labelOf(value: E, prefix: String) = enumLabel(value.name, prefix)
private inline fun <reified E : Enum<E>> enumFor(label: String, prefix: String): E? =
    enumValues<E>().firstOrNull { it.name != "UNRECOGNIZED" && enumLabel(it.name, prefix) == label }

private const val MODE = "CLIMATE_MODE_"
private const val FAN = "CLIMATE_FAN_"
private const val SWING = "CLIMATE_SWING_"
private const val PRESET = "CLIMATE_PRESET_"
private const val ACTION = "CLIMATE_ACTION_"

// ClimateFeature bits (esphome climate_mode.h / aioesphomeapi ClimateFeature).
private const val SUPPORTS_CURRENT_TEMPERATURE = 1 shl 0
private const val SUPPORTS_TWO_POINT = 1 shl 1
private const val REQUIRES_TWO_POINT = 1 shl 2
private const val SUPPORTS_CURRENT_HUMIDITY = 1 shl 3
private const val SUPPORTS_ACTION = 1 shl 5

@Suppress("DEPRECATION")
internal fun climateFeatures(p: Api.ListEntitiesClimateResponse): Int {
    if (p.featureFlags != 0) return p.featureFlags
    // Firmware before API 1.13 only sends the deprecated booleans; mirror aioesphomeapi's compatibility mapping.
    var flags = 0
    if (p.supportsCurrentTemperature) flags = flags or SUPPORTS_CURRENT_TEMPERATURE
    if (p.supportsTwoPointTargetTemperature) flags = flags or REQUIRES_TWO_POINT
    if (p.supportsCurrentHumidity) flags = flags or SUPPORTS_CURRENT_HUMIDITY
    if (p.supportsAction) flags = flags or SUPPORTS_ACTION
    return flags
}

private fun climatePlatform(p: Api.ListEntitiesClimateResponse): Platform.Climate {
    val flags = climateFeatures(p)
    val unit = when (p.temperatureUnit) { Api.TemperatureUnit.TEMPERATURE_UNIT_FAHRENHEIT -> "°F"; Api.TemperatureUnit.TEMPERATURE_UNIT_KELVIN -> "K"; else -> "°C" }
    val customFan = p.supportedCustomFanModesList.filter { it.isNotBlank() }
    val customPresets = p.supportedCustomPresetsList.filter { it.isNotBlank() }
    return Platform.Climate(
        modes = p.supportedModesList.filter { it != Api.ClimateMode.UNRECOGNIZED }.map { labelOf(it, MODE) },
        minTemperature = p.visualMinTemperature, maxTemperature = p.visualMaxTemperature,
        step = p.visualTargetTemperatureStep.takeIf { it.isFinite() && it > 0f } ?: 0.5f,
        twoPoint = flags and (SUPPORTS_TWO_POINT or REQUIRES_TWO_POINT) != 0,
        currentTemperature = flags and SUPPORTS_CURRENT_TEMPERATURE != 0,
        currentHumidity = flags and SUPPORTS_CURRENT_HUMIDITY != 0,
        action = flags and SUPPORTS_ACTION != 0,
        fanModes = p.supportedFanModesList.filter { it != Api.ClimateFanMode.UNRECOGNIZED }.map { labelOf(it, FAN) } + customFan,
        swingModes = p.supportedSwingModesList.filter { it != Api.ClimateSwingMode.UNRECOGNIZED }.map { labelOf(it, SWING) },
        presets = p.supportedPresetsList.filter { it != Api.ClimatePreset.UNRECOGNIZED }.map { labelOf(it, PRESET) } + customPresets,
        unit = unit, customFanModes = customFan, customPresets = customPresets,
    )
}

private fun climateState(p: Api.ClimateStateResponse) = EntityState.Climate(
    mode = labelOf(p.mode, MODE),
    action = labelOf(p.action, ACTION),
    current = p.currentTemperature.finite(),
    target = p.targetTemperature.finite(),
    targetLow = p.targetTemperatureLow.finite(),
    targetHigh = p.targetTemperatureHigh.finite(),
    fanMode = p.customFanMode.ifBlank { null } ?: labelOf(p.fanMode, FAN),
    swingMode = labelOf(p.swingMode, SWING),
    preset = p.customPreset.ifBlank { null } ?: labelOf(p.preset, PRESET),
    currentHumidity = p.currentHumidity.finite(),
)

private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
internal fun stripAnsi(text: String) = text.replace(ansi, "")

private fun listed(disabledByDefault: Boolean, entity: Entity) = if (disabledByDefault) NativeEvent.Ignored else NativeEvent.EntityListed(entity)

/** Every `ListEntities*Response` in api.proto that describes an entity, keyed by message type. */
internal val entityListings: Map<Int, Descriptors.Descriptor> by lazy {
    Api.getDescriptor().messageTypes
        .filter { it.name.startsWith("ListEntities") && it.name.endsWith("Response") && it.name != "ListEntitiesDoneResponse" && it.findFieldByName("key") != null && it.findFieldByName("object_id") != null }
        .associateBy { it.options.getExtension(ApiOptions.id) }
        .filterKeys { it > 0 }
}

/** "ListEntitiesAlarmControlPanelResponse" -> "Alarm control panel". */
internal fun platformLabel(messageName: String): String =
    messageName.removePrefix("ListEntities").removeSuffix("Response")
        .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase().replaceFirstChar { it.uppercase() }

private fun unsupportedListing(frame: ApiFrame): NativeEvent? {
    val descriptor = entityListings[frame.type] ?: return null
    val message = DynamicMessage.parseFrom(descriptor, frame.payload)
    fun field(name: String): Any? = descriptor.findFieldByName(name)?.let { message.getField(it) }
    if (field("disabled_by_default") == true) return NativeEvent.Ignored
    val key = (field("key") as? Int) ?: return null
    val deviceId = (field("device_id") as? Int) ?: 0
    val name = name(field("name") as? String ?: "", field("object_id") as? String ?: "")
    val category = (field("entity_category") as? Descriptors.EnumValueDescriptor)?.number?.let(EntityCategory::fromNumber) ?: EntityCategory.NONE
    return NativeEvent.EntityListed(Entity(key, deviceId, name, Platform.Unsupported(platformLabel(descriptor.name)), category))
}

/** Builds the platform-specific command; returns null for commands the entity does not advertise. */
fun nativeCommand(entity: Entity, command: Command): ApiFrame? {
    val p = entity.platform
    return when {
        command is Command.SetOn && p is Platform.Light ->
            ApiFrame(MessageType.LIGHT_COMMAND, Api.LightCommandRequest.newBuilder().setKey(entity.key).setDeviceId(entity.deviceId).setHasState(true).setState(command.on).build().toByteArray())
        command is Command.SetBrightness && p is Platform.Light && p.supportsBrightness && command.brightness in 0f..1f ->
            ApiFrame(MessageType.LIGHT_COMMAND, Api.LightCommandRequest.newBuilder().setKey(entity.key).setDeviceId(entity.deviceId).setHasState(true).setState(true).setHasBrightness(true).setBrightness(command.brightness).build().toByteArray())
        command is Command.SetOn && p == Platform.Switch ->
            ApiFrame(MessageType.SWITCH_COMMAND, Api.SwitchCommandRequest.newBuilder().setKey(entity.key).setDeviceId(entity.deviceId).setState(command.on).build().toByteArray())
        command is Command.SelectOption && p is Platform.Select && command.option in p.options ->
            ApiFrame(MessageType.SELECT_COMMAND, Api.SelectCommandRequest.newBuilder().setKey(entity.key).setDeviceId(entity.deviceId).setState(command.option).build().toByteArray())
        command is Command.SetNumber && p is Platform.Number && command.value.isFinite() && command.value in p.min..p.max ->
            ApiFrame(MessageType.NUMBER_COMMAND, Api.NumberCommandRequest.newBuilder().setKey(entity.key).setDeviceId(entity.deviceId).setState(command.value).build().toByteArray())
        p is Platform.Light -> lightCommand(entity, p, command)
        p is Platform.Cover -> coverCommand(entity, p, command)
        p is Platform.Valve -> valveCommand(entity, p, command)
        p is Platform.Fan -> fanCommand(entity, p, command)
        p is Platform.Lock && command is Command.Lock && (command.action != LockAction.OPEN || p.supportsOpen) && (!p.requiresCode || !command.code.isNullOrEmpty()) ->
            Api.LockCommandRequest.newBuilder().setKey(entity.key).setDeviceId(entity.deviceId).setCommand(when (command.action) { LockAction.LOCK -> Api.LockCommand.LOCK_LOCK; LockAction.UNLOCK -> Api.LockCommand.LOCK_UNLOCK; LockAction.OPEN -> Api.LockCommand.LOCK_OPEN })
                .apply { if (!command.code.isNullOrEmpty()) setHasCode(true).setCode(command.code) }.build().let { ApiFrame(MessageType.LOCK_COMMAND, it.toByteArray()) }
        p is Platform.Climate -> climateCommand(entity, p, command)
        command == Command.Press && p == Platform.Button ->
            ApiFrame(MessageType.BUTTON_COMMAND, Api.ButtonCommandRequest.newBuilder().setKey(entity.key).setDeviceId(entity.deviceId).build().toByteArray())
        else -> null
    }
}

private fun lightCommand(entity: Entity, p: Platform.Light, command: Command): ApiFrame? {
    val b = Api.LightCommandRequest.newBuilder().setKey(entity.key).setDeviceId(entity.deviceId)
    when {
        command is Command.SetRgb && p.supportsRgb && listOf(command.rgb.red, command.rgb.green, command.rgb.blue).all { it in 0f..1f } ->
            b.setHasState(true).setState(true).setHasRgb(true).setRed(command.rgb.red).setGreen(command.rgb.green).setBlue(command.rgb.blue)
        command is Command.SetColorTemperature && p.supportsColorTemperature && command.mireds in p.minMireds!!..p.maxMireds!! ->
            b.setHasState(true).setState(true).setHasColorTemperature(true).setColorTemperature(command.mireds)
        command is Command.SetEffect && (command.effect in p.effects || command.effect == "None") ->
            b.setHasState(true).setState(true).setHasEffect(true).setEffect(command.effect)
        else -> return null
    }
    return ApiFrame(MessageType.LIGHT_COMMAND, b.build().toByteArray())
}

private fun coverCommand(entity: Entity, p: Platform.Cover, command: Command): ApiFrame? {
    val b = Api.CoverCommandRequest.newBuilder().setKey(entity.key).setDeviceId(entity.deviceId)
    when {
        command == Command.Open -> b.setHasPosition(true).setPosition(1f)
        command == Command.Close -> b.setHasPosition(true).setPosition(0f)
        command == Command.Stop && p.supportsStop -> b.setStop(true)
        command is Command.SetPosition && p.supportsPosition && command.position in 0f..1f -> b.setHasPosition(true).setPosition(command.position)
        command is Command.SetTilt && p.supportsTilt && command.tilt in 0f..1f -> b.setHasTilt(true).setTilt(command.tilt)
        else -> return null
    }
    return ApiFrame(MessageType.COVER_COMMAND, b.build().toByteArray())
}

private fun valveCommand(entity: Entity, p: Platform.Valve, command: Command): ApiFrame? {
    val b = Api.ValveCommandRequest.newBuilder().setKey(entity.key).setDeviceId(entity.deviceId)
    when {
        command == Command.Open -> b.setHasPosition(true).setPosition(1f)
        command == Command.Close -> b.setHasPosition(true).setPosition(0f)
        command == Command.Stop && p.supportsStop -> b.setStop(true)
        command is Command.SetPosition && p.supportsPosition && command.position in 0f..1f -> b.setHasPosition(true).setPosition(command.position)
        else -> return null
    }
    return ApiFrame(MessageType.VALVE_COMMAND, b.build().toByteArray())
}

private fun fanCommand(entity: Entity, p: Platform.Fan, command: Command): ApiFrame? {
    val b = Api.FanCommandRequest.newBuilder().setKey(entity.key).setDeviceId(entity.deviceId)
    when {
        command is Command.SetOn -> b.setHasState(true).setState(command.on)
        command is Command.SetFanSpeed && p.speedCount > 0 && command.level in 1..p.speedCount -> b.setHasState(true).setState(true).setHasSpeedLevel(true).setSpeedLevel(command.level)
        command is Command.SetOscillation && p.supportsOscillation -> b.setHasOscillating(true).setOscillating(command.on)
        command is Command.SetDirection && p.supportsDirection -> b.setHasDirection(true).setDirection(if (command.reverse) Api.FanDirection.FAN_DIRECTION_REVERSE else Api.FanDirection.FAN_DIRECTION_FORWARD)
        command is Command.SetPreset && command.preset in p.presets -> b.setHasPresetMode(true).setPresetMode(command.preset)
        else -> return null
    }
    return ApiFrame(MessageType.FAN_COMMAND, b.build().toByteArray())
}

private fun climateCommand(entity: Entity, p: Platform.Climate, command: Command): ApiFrame? {
    val b = Api.ClimateCommandRequest.newBuilder().setKey(entity.key).setDeviceId(entity.deviceId)
    val range = p.minTemperature..p.maxTemperature
    when {
        command is Command.SetClimateMode && command.mode in p.modes -> b.setHasMode(true).setMode(enumFor<Api.ClimateMode>(command.mode, MODE) ?: return null)
        command is Command.SetTargetTemperature && !p.twoPoint && command.target in range -> b.setHasTargetTemperature(true).setTargetTemperature(command.target)
        command is Command.SetTargetRange && p.twoPoint && command.low in range && command.high in range && command.low <= command.high ->
            b.setHasTargetTemperatureLow(true).setTargetTemperatureLow(command.low).setHasTargetTemperatureHigh(true).setTargetTemperatureHigh(command.high)
        command is Command.SetFanMode && command.mode in p.customFanModes -> b.setHasCustomFanMode(true).setCustomFanMode(command.mode)
        command is Command.SetFanMode && command.mode in p.fanModes -> b.setHasFanMode(true).setFanMode(enumFor<Api.ClimateFanMode>(command.mode, FAN) ?: return null)
        command is Command.SetSwingMode && command.mode in p.swingModes -> b.setHasSwingMode(true).setSwingMode(enumFor<Api.ClimateSwingMode>(command.mode, SWING) ?: return null)
        command is Command.SetPreset && command.preset in p.customPresets -> b.setHasCustomPreset(true).setCustomPreset(command.preset)
        command is Command.SetPreset && command.preset in p.presets -> b.setHasPreset(true).setPreset(enumFor<Api.ClimatePreset>(command.preset, PRESET) ?: return null)
        else -> return null
    }
    return ApiFrame(MessageType.CLIMATE_COMMAND, b.build().toByteArray())
}

fun subscribeLogsFrame(level: Int = 5): ApiFrame =
    ApiFrame(MessageType.SUBSCRIBE_LOGS_REQUEST, Api.SubscribeLogsRequest.newBuilder().setLevelValue(level).build().toByteArray())
