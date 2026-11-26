package com.example.esphomecontroller.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON encoding of entity metadata (capabilities, names, icons) and device details for the
 * on-disk control cache. Entity states are deliberately never encoded.
 */
object EntityCodec {
    fun encodeEntities(entities: List<Entity>): JSONArray = JSONArray().apply { entities.forEach { put(encode(it)) } }

    /** Decodes what it understands; malformed or unknown entries are skipped. */
    fun decodeEntities(array: JSONArray?): List<Entity> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { runCatching { decode(it) }.getOrNull() } }

    fun encodeDetails(d: DeviceDetails): JSONObject =
        JSONObject().put("model", d.model).put("manufacturer", d.manufacturer).put("esphomeVersion", d.esphomeVersion).put("project", d.project).put("area", d.area)

    fun decodeDetails(o: JSONObject?): DeviceDetails? =
        o?.let { DeviceDetails(it.optString("model"), it.optString("manufacturer"), it.optString("esphomeVersion"), it.optString("project"), it.optString("area")) }

    private fun strings(list: List<String>) = JSONArray(list)
    private fun JSONObject.strings(key: String) = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    private fun JSONObject.float(key: String) = getDouble(key).toFloat()
    private fun JSONObject.floatOrNull(key: String) = if (has(key) && !isNull(key)) getDouble(key).toFloat() else null

    private fun encode(e: Entity): JSONObject {
        val o = JSONObject().put("key", e.key).put("deviceId", e.deviceId).put("name", e.name).put("category", e.category.name)
            .put("deviceClass", e.deviceClass).put("icon", e.icon)
        e.webId?.let { o.put("webId", it) }
        val p = JSONObject().put("type", e.platform.id.substringBefore(':'))
        when (val pl = e.platform) {
            is Platform.Light -> { p.put("brightness", pl.supportsBrightness).put("rgb", pl.supportsRgb).put("effects", strings(pl.effects)); pl.minMireds?.let { p.put("minMireds", it.toDouble()) }; pl.maxMireds?.let { p.put("maxMireds", it.toDouble()) } }
            is Platform.Sensor -> { p.put("unit", pl.unit); pl.decimals?.let { p.put("decimals", it) } }
            is Platform.Select -> p.put("options", strings(pl.options))
            is Platform.Number -> p.put("min", pl.min.toDouble()).put("max", pl.max.toDouble()).put("step", pl.step.toDouble()).put("unit", pl.unit).put("mode", pl.mode.name)
            is Platform.Cover -> p.put("position", pl.supportsPosition).put("tilt", pl.supportsTilt).put("stop", pl.supportsStop)
            is Platform.Valve -> p.put("position", pl.supportsPosition).put("stop", pl.supportsStop)
            is Platform.Fan -> p.put("oscillation", pl.supportsOscillation).put("direction", pl.supportsDirection).put("speedCount", pl.speedCount).put("presets", strings(pl.presets))
            is Platform.Lock -> p.put("open", pl.supportsOpen).put("requiresCode", pl.requiresCode).put("codeFormat", pl.codeFormat)
            is Platform.Climate -> p.put("modes", strings(pl.modes)).put("min", pl.minTemperature.toDouble()).put("max", pl.maxTemperature.toDouble()).put("step", pl.step.toDouble())
                .put("twoPoint", pl.twoPoint).put("currentTemperature", pl.currentTemperature).put("currentHumidity", pl.currentHumidity).put("action", pl.action)
                .put("fanModes", strings(pl.fanModes)).put("swingModes", strings(pl.swingModes)).put("presets", strings(pl.presets)).put("unit", pl.unit)
                .put("customFanModes", strings(pl.customFanModes)).put("customPresets", strings(pl.customPresets))
            is Platform.Unsupported -> p.put("platform", pl.platform)
            Platform.Switch, Platform.BinarySensor, Platform.TextSensor, Platform.Button -> Unit
        }
        return o.put("platform", p)
    }

    private fun decode(o: JSONObject): Entity? {
        val p = o.getJSONObject("platform")
        val platform: Platform = when (p.getString("type")) {
            "switch" -> Platform.Switch
            "binary_sensor" -> Platform.BinarySensor
            "text_sensor" -> Platform.TextSensor
            "button" -> Platform.Button
            "light" -> Platform.Light(p.optBoolean("brightness"), p.optBoolean("rgb"), p.floatOrNull("minMireds"), p.floatOrNull("maxMireds"), p.strings("effects"))
            "sensor" -> Platform.Sensor(p.optString("unit"), if (p.has("decimals")) p.getInt("decimals") else null)
            "select" -> Platform.Select(p.strings("options"))
            "number" -> Platform.Number(p.float("min"), p.float("max"), p.float("step"), p.optString("unit"), NumberMode.entries.firstOrNull { it.name == p.optString("mode") } ?: NumberMode.AUTO)
            "cover" -> Platform.Cover(p.optBoolean("position"), p.optBoolean("tilt"), p.optBoolean("stop"))
            "valve" -> Platform.Valve(p.optBoolean("position"), p.optBoolean("stop"))
            "fan" -> Platform.Fan(p.optBoolean("oscillation"), p.optBoolean("direction"), p.optInt("speedCount"), p.strings("presets"))
            "lock" -> Platform.Lock(p.optBoolean("open"), p.optBoolean("requiresCode"), p.optString("codeFormat"))
            "climate" -> Platform.Climate(
                p.strings("modes"), p.float("min"), p.float("max"), p.float("step"), p.optBoolean("twoPoint"), p.optBoolean("currentTemperature"),
                p.optBoolean("currentHumidity"), p.optBoolean("action"), p.strings("fanModes"), p.strings("swingModes"), p.strings("presets"),
                p.optString("unit", "°C"), p.strings("customFanModes"), p.strings("customPresets"))
            "unsupported" -> Platform.Unsupported(p.getString("platform"))
            else -> return null
        }
        return Entity(
            o.getInt("key"), o.getInt("deviceId"), o.getString("name"), platform,
            EntityCategory.entries.firstOrNull { it.name == o.optString("category") } ?: EntityCategory.NONE,
            webId = if (o.has("webId")) o.getString("webId") else null,
            deviceClass = o.optString("deviceClass"), icon = o.optString("icon"),
        )
    }
}
