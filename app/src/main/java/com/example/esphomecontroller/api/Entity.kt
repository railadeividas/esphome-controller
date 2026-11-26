package com.example.esphomecontroller.api

import java.math.BigDecimal
import java.util.Locale

enum class EntityCategory { NONE, CONFIG, DIAGNOSTIC;
    companion object { fun fromNumber(n: Int) = entries.getOrElse(n) { NONE } }
}

/** Capabilities advertised by the device for one entity. Controls are derived only from these. */
sealed interface Platform {
    val id: String
    data object Switch : Platform { override val id = "switch" }
    data object BinarySensor : Platform { override val id = "binary_sensor" }
    data object TextSensor : Platform { override val id = "text_sensor" }
    data object Button : Platform { override val id = "button" }
    data class Light(
        val supportsBrightness: Boolean,
        val supportsRgb: Boolean = false,
        /** Advertised colour temperature range in mireds, when supported. */
        val minMireds: Float? = null,
        val maxMireds: Float? = null,
        val effects: List<String> = emptyList(),
    ) : Platform {
        override val id get() = "light"
        val supportsColorTemperature get() = minMireds != null && maxMireds != null && maxMireds > minMireds
    }
    data class Sensor(val unit: String = "", val decimals: Int? = null) : Platform { override val id get() = "sensor" }
    data class Select(val options: List<String>) : Platform { override val id get() = "select" }
    data class Number(val min: Float, val max: Float, val step: Float, val unit: String = "", val mode: NumberMode = NumberMode.AUTO) : Platform { override val id get() = "number" }
    data class Cover(val supportsPosition: Boolean, val supportsTilt: Boolean, val supportsStop: Boolean) : Platform { override val id get() = "cover" }
    data class Valve(val supportsPosition: Boolean, val supportsStop: Boolean) : Platform { override val id get() = "valve" }
    data class Fan(val supportsOscillation: Boolean, val supportsDirection: Boolean, val speedCount: Int, val presets: List<String> = emptyList()) : Platform { override val id get() = "fan" }
    data class Lock(val supportsOpen: Boolean, val requiresCode: Boolean, val codeFormat: String = "") : Platform { override val id get() = "lock" }
    data class Climate(
        val modes: List<String>,
        val minTemperature: Float,
        val maxTemperature: Float,
        val step: Float,
        val twoPoint: Boolean,
        val currentTemperature: Boolean,
        val currentHumidity: Boolean,
        val action: Boolean,
        val fanModes: List<String> = emptyList(),
        val swingModes: List<String> = emptyList(),
        val presets: List<String> = emptyList(),
        val unit: String = "°C",
        /** Names in [fanModes] / [presets] that are firmware-defined custom values rather than standard ones. */
        val customFanModes: List<String> = emptyList(),
        val customPresets: List<String> = emptyList(),
    ) : Platform { override val id get() = "climate" }
    /** An entity the app cannot control yet; shown honestly instead of being dropped. */
    data class Unsupported(val platform: String) : Platform { override val id get() = "unsupported:$platform" }
}

enum class NumberMode { AUTO, BOX, SLIDER }
enum class Operation { IDLE, OPENING, CLOSING }
enum class LockStatus { UNKNOWN, LOCKED, UNLOCKED, JAMMED, LOCKING, UNLOCKING, OPENING, OPEN }
enum class LockAction { LOCK, UNLOCK, OPEN }

/** Colour channels from 0 to 1. */
data class Rgb(val red: Float, val green: Float, val blue: Float)

sealed interface EntityState {
    /** The device reported that it has no valid state yet. */
    data object Missing : EntityState
    data class OnOff(val on: Boolean) : EntityState
    data class Light(val on: Boolean, val brightness: Float?, val rgb: Rgb? = null, val mireds: Float? = null, val effect: String? = null) : EntityState
    /** Covers and valves: position and tilt are 0 (closed) to 1 (open). */
    data class Positioned(val position: Float, val tilt: Float?, val operation: Operation) : EntityState
    data class Fan(val on: Boolean, val oscillating: Boolean, val reverse: Boolean, val speedLevel: Int, val preset: String?) : EntityState
    data class Lock(val status: LockStatus) : EntityState
    data class Climate(
        val mode: String, val action: String?, val current: Float?, val target: Float?, val targetLow: Float?, val targetHigh: Float?,
        val fanMode: String?, val swingMode: String?, val preset: String?, val currentHumidity: Float?,
    ) : EntityState
    data class Numeric(val value: Float) : EntityState
    data class Text(val value: String) : EntityState
}

data class Entity(
    val key: Int,
    val deviceId: Int,
    val name: String,
    val platform: Platform,
    val category: EntityCategory = EntityCategory.NONE,
    /** Web-server identifier; null for Native API entities. */
    val webId: String? = null,
    /** null until the current connection reports a state. */
    val state: EntityState? = null,
    /** Advertised device_class (e.g. "door", "temperature"); empty when unknown. */
    val deviceClass: String = "",
    /** Advertised icon, usually "mdi:<name>"; empty when unknown. */
    val icon: String = "",
) {
    val stableId: String get() = webId ?: "${platform.id}:$deviceId:$key"
}

sealed interface Command {
    data class SetOn(val on: Boolean) : Command
    data class SetBrightness(val brightness: Float) : Command
    data class SelectOption(val option: String) : Command
    data class SetNumber(val value: Float) : Command
    data object Press : Command
    data class SetRgb(val rgb: Rgb) : Command
    data class SetColorTemperature(val mireds: Float) : Command
    data class SetEffect(val effect: String) : Command
    data object Open : Command
    data object Close : Command
    data object Stop : Command
    data class SetPosition(val position: Float) : Command
    data class SetTilt(val tilt: Float) : Command
    data class SetFanSpeed(val level: Int) : Command
    data class SetOscillation(val on: Boolean) : Command
    data class SetDirection(val reverse: Boolean) : Command
    /** Fan or climate preset, by advertised name. */
    data class SetPreset(val preset: String) : Command
    data class Lock(val action: LockAction, val code: String? = null) : Command
    data class SetClimateMode(val mode: String) : Command
    data class SetTargetTemperature(val target: Float) : Command
    data class SetTargetRange(val low: Float, val high: Float) : Command
    data class SetFanMode(val mode: String) : Command
    data class SetSwingMode(val mode: String) : Command
}

enum class Security { ENCRYPTED, UNENCRYPTED, WEB }

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data class Connected(val security: Security) : ConnectionState
    /** [needsSetup]: retrying cannot help (wrong key, encryption mismatch, rejected credentials). */
    data class Failed(val reason: String, val needsSetup: Boolean = false) : ConnectionState
}

/** Optional device metadata reported by the Native API. Identifiers such as MAC addresses are deliberately not kept. */
data class DeviceDetails(val model: String, val manufacturer: String, val esphomeVersion: String, val project: String)

fun sameEntity(a: Entity, b: Entity): Boolean =
    if (a.webId != null || b.webId != null) a.webId != null && a.webId == b.webId
    else a.key == b.key && a.deviceId == b.deviceId && a.platform.id == b.platform.id

/** Metadata arriving during a reconnect must not erase the last reported state. */
fun mergeCachedEntities(cached: List<Entity>, fresh: List<Entity>): List<Entity> {
    val merged = cached.toMutableList()
    for (entity in fresh) {
        val index = merged.indexOfFirst { sameEntity(it, entity) }
        if (index < 0) merged.add(entity)
        else merged[index] = if (entity.state != null) entity else entity.copy(state = merged[index].state)
    }
    return merged
}

/** Cached values are for display only; commands require state from the current connection. */
fun isCommandReady(connected: Boolean, live: Entity?): Boolean {
    if (!connected || live == null) return false
    return when (val p = live.platform) {
        Platform.Button -> true
        Platform.Switch -> live.state is EntityState.OnOff
        is Platform.Light -> live.state is EntityState.Light
        is Platform.Select -> live.state is EntityState.Text && p.options.isNotEmpty()
        is Platform.Number -> live.state is EntityState.Numeric && p.max > p.min
        is Platform.Cover, is Platform.Valve -> live.state is EntityState.Positioned
        is Platform.Fan -> live.state is EntityState.Fan
        is Platform.Lock -> live.state is EntityState.Lock
        is Platform.Climate -> live.state is EntityState.Climate
        else -> false
    }
}

fun isOn(state: EntityState?): Boolean? = when (state) {
    is EntityState.OnOff -> state.on
    is EntityState.Light -> state.on
    is EntityState.Fan -> state.on
    else -> null
}

private fun percent(value: Float) = "${Math.round(value.coerceIn(0f, 1f) * 100)}%"

fun decimalsForStep(step: Float): Int =
    if (!step.isFinite() || step <= 0f) 2 else BigDecimal(step.toString()).stripTrailingZeros().scale().coerceIn(0, 6)

fun formatNumber(value: Float, decimals: Int?, locale: Locale = Locale.getDefault()): String =
    if (decimals == null || decimals < 0) value.toString().removeSuffix(".0") else String.format(locale, "%.${decimals.coerceAtMost(6)}f", value)

/** Text shown for read-only values; null when unknown. */
fun displayValue(entity: Entity, locale: Locale = Locale.getDefault()): String? {
    val state = entity.state ?: return null
    val p = entity.platform
    val text = when (state) {
        EntityState.Missing -> return null
        is EntityState.OnOff -> if (p == Platform.BinarySensor) binaryStateText(state.on, entity.deviceClass) else if (state.on) "On" else "Off"
        is EntityState.Light -> if (state.on) "On" else "Off"
        is EntityState.Text -> state.value
        is EntityState.Fan -> if (!state.on) "Off" else if (p is Platform.Fan && p.speedCount > 0) "On · ${state.speedLevel}/${p.speedCount}" else "On"
        is EntityState.Lock -> state.status.name.lowercase().replaceFirstChar { it.uppercase() }
        is EntityState.Positioned -> when (state.operation) {
            Operation.OPENING -> "Opening"; Operation.CLOSING -> "Closing"
            Operation.IDLE -> if (p is Platform.Cover && p.supportsPosition || p is Platform.Valve && p.supportsPosition) (if (state.position <= 0f) "Closed" else "Open · ${percent(state.position)}") else if (state.position <= 0f) "Closed" else "Open"
        }
        is EntityState.Climate -> {
            val unit = (p as? Platform.Climate)?.unit.orEmpty()
            val decimals = decimalsForStep((p as? Platform.Climate)?.step ?: 0.5f).coerceAtLeast(1)
            state.current?.let { "${formatNumber(it, decimals, locale)} $unit".trim() } ?: state.mode
        }
        is EntityState.Numeric -> when (p) {
            is Platform.Sensor -> formatNumber(state.value, p.decimals, locale)
            is Platform.Number -> formatNumber(state.value, decimalsForStep(p.step), locale)
            else -> formatNumber(state.value, null, locale)
        }
    }
    val unit = when (p) { is Platform.Sensor -> p.unit; is Platform.Number -> p.unit; else -> "" }
    return if (state is EntityState.Numeric && unit.isNotBlank()) "$text $unit" else text
}

/** Rounds a slider value to the advertised step, within the advertised range. */
fun snapToStep(value: Float, platform: Platform.Number): Float {
    val clamped = value.coerceIn(platform.min, platform.max)
    if (!platform.step.isFinite() || platform.step <= 0f) return clamped
    val steps = Math.round((clamped - platform.min) / platform.step)
    val decimals = decimalsForStep(platform.step)
    return BigDecimal((platform.min + steps * platform.step).toDouble()).setScale(decimals, java.math.RoundingMode.HALF_UP).toFloat().coerceIn(platform.min, platform.max)
}

/** Hue in degrees (0–360) of a colour; 0 for greys. */
fun hueOf(rgb: Rgb): Float {
    val max = maxOf(rgb.red, rgb.green, rgb.blue); val min = minOf(rgb.red, rgb.green, rgb.blue)
    val d = max - min
    if (d <= 0f) return 0f
    val h = when (max) {
        rgb.red -> ((rgb.green - rgb.blue) / d).mod(6f)
        rgb.green -> (rgb.blue - rgb.red) / d + 2f
        else -> (rgb.red - rgb.green) / d + 4f
    } * 60f
    return h.mod(360f)
}

/** Fully saturated colour for a hue; brightness is controlled separately. */
fun rgbForHue(hue: Float): Rgb {
    val h = hue.mod(360f) / 60f
    val x = 1f - kotlin.math.abs(h.mod(2f) - 1f)
    return when (h.toInt()) {
        0 -> Rgb(1f, x, 0f); 1 -> Rgb(x, 1f, 0f); 2 -> Rgb(0f, 1f, x)
        3 -> Rgb(0f, x, 1f); 4 -> Rgb(x, 0f, 1f); else -> Rgb(1f, 0f, x)
    }
}

/** Kelvin for display; ESPHome uses mireds. */
fun miredsToKelvin(mireds: Float): Int = if (mireds > 0f) Math.round(1_000_000f / mireds) else 0

/** Rounds a target temperature to the advertised step within the visual range. */
fun snapTemperature(value: Float, p: Platform.Climate): Float =
    snapToStep(value, Platform.Number(p.minTemperature, p.maxTemperature, if (p.step > 0f) p.step else 0.5f))

/** State words for a binary sensor's device_class (Home Assistant conventions); On/Off when unknown. */
fun binaryStateText(on: Boolean, deviceClass: String): String {
    val (onText, offText) = when (deviceClass.lowercase()) {
        "door", "garage_door", "opening", "window" -> "Open" to "Closed"
        "motion", "occupancy", "presence", "sound", "vibration", "smoke", "gas", "carbon_monoxide", "tamper" -> "Detected" to "Clear"
        "moisture" -> "Wet" to "Dry"
        "lock" -> "Unlocked" to "Locked"
        "battery" -> "Low" to "Normal"
        "battery_charging" -> "Charging" to "Not charging"
        "cold" -> "Cold" to "Normal"
        "heat" -> "Hot" to "Normal"
        "connectivity" -> "Connected" to "Disconnected"
        "light" -> "Light" to "Dark"
        "moving" -> "Moving" to "Stopped"
        "plug" -> "Plugged in" to "Unplugged"
        "power" -> "Power" to "No power"
        "problem" -> "Problem" to "OK"
        "running" -> "Running" to "Not running"
        "safety" -> "Unsafe" to "Safe"
        "update" -> "Update available" to "Up to date"
        else -> "On" to "Off"
    }
    return if (on) onText else offText
}

/** Read-only platforms are listed compactly instead of as control tiles. */
val Platform.isReadOnly: Boolean get() = this is Platform.Sensor || this == Platform.BinarySensor || this == Platform.TextSensor
