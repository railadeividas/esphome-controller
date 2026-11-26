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
    data class Light(val supportsBrightness: Boolean) : Platform { override val id get() = "light" }
    data class Sensor(val unit: String = "", val decimals: Int? = null) : Platform { override val id get() = "sensor" }
    data class Select(val options: List<String>) : Platform { override val id get() = "select" }
    data class Number(val min: Float, val max: Float, val step: Float, val unit: String = "", val mode: NumberMode = NumberMode.AUTO) : Platform { override val id get() = "number" }
    /** An entity the app cannot control yet; shown honestly instead of being dropped. */
    data class Unsupported(val platform: String) : Platform { override val id get() = "unsupported:$platform" }
}

enum class NumberMode { AUTO, BOX, SLIDER }

sealed interface EntityState {
    /** The device reported that it has no valid state yet. */
    data object Missing : EntityState
    data class OnOff(val on: Boolean) : EntityState
    data class Light(val on: Boolean, val brightness: Float?) : EntityState
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
) {
    val stableId: String get() = webId ?: "${platform.id}:$deviceId:$key"
}

sealed interface Command {
    data class SetOn(val on: Boolean) : Command
    data class SetBrightness(val brightness: Float) : Command
    data class SelectOption(val option: String) : Command
    data class SetNumber(val value: Float) : Command
    data object Press : Command
}

enum class Security { ENCRYPTED, UNENCRYPTED, WEB }

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data class Connected(val security: Security) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
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
        else -> false
    }
}

fun isOn(state: EntityState?): Boolean? = when (state) {
    is EntityState.OnOff -> state.on
    is EntityState.Light -> state.on
    else -> null
}

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
        is EntityState.OnOff -> if (state.on) "On" else "Off"
        is EntityState.Light -> if (state.on) "On" else "Off"
        is EntityState.Text -> state.value
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
