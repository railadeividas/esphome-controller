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
        15 -> Api.ListEntitiesLightResponse.parseFrom(payload).let { p ->
            // ColorMode bit 2 is COLOR_CAP_BRIGHTNESS.
            val brightness = p.legacySupportsBrightness || p.supportedColorModesValueList.any { (it and 2) != 0 }
            listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.Light(brightness), category(p.entityCategory)))
        }
        16 -> Api.ListEntitiesSensorResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.Sensor(p.unitOfMeasurement, p.accuracyDecimals.takeIf { it >= 0 }), category(p.entityCategory))) }
        17 -> Api.ListEntitiesSwitchResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.Switch, category(p.entityCategory))) }
        18 -> Api.ListEntitiesTextSensorResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.TextSensor, category(p.entityCategory))) }
        49 -> Api.ListEntitiesNumberResponse.parseFrom(payload).let { p ->
            val mode = when (p.mode) { Api.NumberMode.NUMBER_MODE_BOX -> NumberMode.BOX; Api.NumberMode.NUMBER_MODE_SLIDER -> NumberMode.SLIDER; else -> NumberMode.AUTO }
            listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.Number(p.minValue, p.maxValue, p.step, p.unitOfMeasurement, mode), category(p.entityCategory)))
        }
        52 -> Api.ListEntitiesSelectResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.Select(p.optionsList.toList()), category(p.entityCategory))) }
        61 -> Api.ListEntitiesButtonResponse.parseFrom(payload).let { p -> listed(p.disabledByDefault, Entity(p.key, p.deviceId, name(p.name, p.objectId), Platform.Button, category(p.entityCategory))) }
        21 -> Api.BinarySensorStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, if (p.missingState) EntityState.Missing else EntityState.OnOff(p.state)) }
        24 -> Api.LightStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, EntityState.Light(p.state, p.brightness.takeIf { it.isFinite() }?.coerceIn(0f, 1f))) }
        25 -> Api.SensorStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, if (p.missingState || !p.state.isFinite()) EntityState.Missing else EntityState.Numeric(p.state)) }
        26 -> Api.SwitchStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, if (p.missingState) EntityState.Missing else EntityState.OnOff(p.state)) }
        27 -> Api.TextSensorStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, if (p.missingState) EntityState.Missing else EntityState.Text(p.state)) }
        50 -> Api.NumberStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, if (p.missingState || !p.state.isFinite()) EntityState.Missing else EntityState.Numeric(p.state)) }
        53 -> Api.SelectStateResponse.parseFrom(payload).let { p -> NativeEvent.StateChanged(p.key, p.deviceId, if (p.missingState) EntityState.Missing else EntityState.Text(p.state)) }
        else -> unsupportedListing(frame) ?: NativeEvent.Ignored
    }
}

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
        command == Command.Press && p == Platform.Button ->
            ApiFrame(MessageType.BUTTON_COMMAND, Api.ButtonCommandRequest.newBuilder().setKey(entity.key).setDeviceId(entity.deviceId).build().toByteArray())
        else -> null
    }
}
