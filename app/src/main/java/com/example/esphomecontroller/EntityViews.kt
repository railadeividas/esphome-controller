@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.esphomecontroller

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt
import com.example.esphomecontroller.api.*

private fun Platform.icon(): ImageVector = when (this) {
    is Platform.Light -> Icons.Default.Lightbulb
    Platform.Switch -> Icons.Default.ToggleOn
    Platform.BinarySensor -> Icons.Default.RadioButtonChecked
    Platform.TextSensor -> Icons.Default.TextFields
    Platform.Button -> Icons.Default.TouchApp
    is Platform.Number -> Icons.Default.Tune
    is Platform.Select -> Icons.AutoMirrored.Filled.List
    is Platform.Sensor -> Icons.Default.Sensors
    is Platform.Cover -> Icons.Default.Blinds
    is Platform.Valve -> Icons.Default.WaterDrop
    is Platform.Fan -> Icons.Default.Air
    is Platform.Lock -> Icons.Default.Lock
    is Platform.Climate -> Icons.Default.Thermostat
    is Platform.Unsupported -> Icons.AutoMirrored.Filled.HelpOutline
}

/** Icon from the advertised icon or device_class, falling back to the platform icon. */
fun entityIcon(entity: Entity): ImageVector {
    val mdi = entity.icon.removePrefix("mdi:").lowercase()
    val byIcon = when {
        mdi.isEmpty() -> null
        "thermometer" in mdi || "thermostat" in mdi -> Icons.Default.Thermostat
        "water-percent" in mdi || "humidity" in mdi || mdi == "water" -> Icons.Default.WaterDrop
        "wifi" in mdi || "signal" in mdi -> Icons.Default.Wifi
        "restart" in mdi || "reload" in mdi || "refresh" in mdi -> Icons.Default.RestartAlt
        "weather-sun" in mdi || "sun" in mdi -> Icons.Default.WbSunny
        "timer" in mdi || "clock" in mdi || "history" in mdi -> Icons.Default.Schedule
        "flash" in mdi || "lightning" in mdi || "power-plug" in mdi -> Icons.Default.Bolt
        "battery" in mdi -> Icons.Default.BatteryFull
        "door" in mdi -> Icons.Default.SensorDoor
        "window" in mdi -> Icons.Default.Window
        "motion" in mdi || "walk" in mdi || "run" in mdi -> Icons.Default.DirectionsWalk
        "gauge" in mdi || "speed" in mdi -> Icons.Default.Speed
        "lightbulb" in mdi || "lamp" in mdi -> Icons.Default.Lightbulb
        "fan" in mdi -> Icons.Default.Air
        else -> null
    }
    if (byIcon != null) return byIcon
    val byClass = when (entity.deviceClass.lowercase()) {
        "" -> null
        "temperature" -> Icons.Default.Thermostat
        "humidity", "moisture" -> Icons.Default.WaterDrop
        "signal_strength", "connectivity" -> Icons.Default.Wifi
        "power", "energy", "voltage", "current", "plug" -> Icons.Default.Bolt
        "battery", "battery_charging" -> Icons.Default.BatteryFull
        "illuminance", "light" -> Icons.Default.LightMode
        "pressure" -> Icons.Default.Speed
        "duration", "timestamp", "date" -> Icons.Default.Schedule
        "carbon_dioxide", "pm25", "pm10", "volatile_organic_compounds", "gas" -> Icons.Default.Air
        "door", "garage_door", "opening" -> Icons.Default.SensorDoor
        "window" -> Icons.Default.Window
        "motion", "occupancy", "presence", "moving" -> Icons.Default.DirectionsWalk
        "smoke", "heat" -> Icons.Default.LocalFireDepartment
        "problem", "safety", "tamper" -> Icons.Default.Warning
        "lock" -> Icons.Default.Lock
        "restart" -> Icons.Default.RestartAlt
        "update", "firmware" -> Icons.Default.SystemUpdate
        else -> null
    }
    return byClass ?: entity.platform.icon()
}

/**
 * An entity's icon: the exact MDI glyph when the device advertises a known "mdi:" icon, otherwise the closest
 * Material icon from [entityIcon]. [modifier] may add padding; the icon itself is [size].
 */
@Composable
fun EntityIcon(entity: Entity, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = entityTint(entity)) {
    val mdi = Mdi.find(entity.icon)
    if (mdi != null) MdiGlyph(mdi, size, modifier, tint) else Icon(entityIcon(entity), contentDescription = null, modifier = modifier.size(size), tint = tint)
}

/**
 * Icon colour that shows on/off at a glance, like Home Assistant: lights and switches amber when on, fans cyan,
 * grey when off or unknown. Entities without on/off use the theme's primary colour.
 */
@Composable
fun entityTint(entity: Entity): Color {
    val p = entity.platform
    if (!p.isToggle) return MaterialTheme.colorScheme.primary
    if (isOn(entity.state) != true) return MaterialTheme.colorScheme.onSurfaceVariant
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (p) {
        is Platform.Fan -> if (dark) Color(0xFF26C6DA) else Color(0xFF00ACC1)
        else -> if (dark) Color(0xFFFFC107) else Color(0xFFF9A825)
    }
}

/** The card look used across the app: theme surface (not Material's default grey), 16 dp corners, 1 dp elevation. */
val AppCardShape = RoundedCornerShape(16.dp)
@Composable fun appCardColors() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
@Composable fun appCardElevation() = CardDefaults.cardElevation(defaultElevation = 1.dp)

private val Platform.isToggle get() = this is Platform.Light || this == Platform.Switch || this is Platform.Fan
/** Platforms whose current value is shown as text next to the icon or name. */
private val Platform.showsValue get() = isReadOnly || this is Platform.Cover || this is Platform.Valve || this is Platform.Lock || this is Platform.Climate

/** Presentation options set per dashboard; screens without a dashboard use the defaults. */
/** [readOnly] disables every control (dashboard edit mode), so nothing can be sent to a device by accident. */
data class EntityStyle(val brightnessPercent: Boolean = true, val tapToToggle: Boolean = false, val pendingSpinner: Boolean = false, val readOnly: Boolean = false)
val LocalEntityStyle = staticCompositionLocalOf { EntityStyle() }

/** Wraps a send so that only the live (current-connection) entity is ever commanded. */
private class Sender(val session: ControlSession?, val live: Entity?, val ready: Boolean, val onSent: () -> Unit = {}) {
    operator fun invoke(command: Command) { if (ready && live != null) { session?.send(live, command); onSent() } }
}

/**
 * Dashboard tile. [entity] may be cached; [live] is the current connection's copy and gates commands.
 * [onOpen] (long-press or tapping the name) opens the full controls; [overlay] draws edit-mode tools.
 */
@Composable
fun EntityTile(entity: Entity, session: ControlSession?, live: Entity?, modifier: Modifier = Modifier, deviceLabel: String? = null, onOpen: (() -> Unit)? = null, overlay: (@Composable BoxScope.() -> Unit)? = null) {
    val style = LocalEntityStyle.current
    // Pending until the device reports a new state for this entity, or for at most five seconds.
    var pending by remember(entity.stableId) { mutableStateOf(false) }
    LaunchedEffect(live?.state) { pending = false }
    LaunchedEffect(pending) { if (pending) { kotlinx.coroutines.delay(5000); pending = false } }
    val send = Sender(session, live, isCommandReady(session.isConnected, live) && !LocalEntityStyle.current.readOnly) { if (style.pendingSpinner) pending = true }
    val p = entity.platform
    val tapToggle = style.tapToToggle && p.isToggle && overlay == null
    // Tap: toggle (section tap action Toggle, lights, switches and fans) or open all controls; long-press: all controls.
    // Inline controls (power button, sliders, buttons) handle their own touches first.
    val gestures = if (onOpen == null && !tapToggle) Modifier else Modifier.pointerInput(onOpen, tapToggle, send.ready, isOn(live?.state)) {
        detectTapGestures(
            onTap = { if (tapToggle) send(Command.SetOn(isOn(live?.state) != true)) else onOpen?.invoke() },
            onLongPress = if (onOpen != null) { _ -> onOpen() } else null,
        )
    }
    Card(modifier.heightIn(min = 142.dp).then(gestures), shape = AppCardShape, colors = appCardColors(), elevation = appCardElevation()) {
      Box {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EntityIcon(entity, size = TileIconSize)
                // In edit mode the tools replace the power button or value, so they never cover them.
                if (overlay != null) {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.layoutHeight(TileIconSize).offset(x = (48.dp - TileIconSize) / 2)) { overlay() }
                } else if (p.isToggle) {
                    Spacer(Modifier.weight(1f))
                    // Laid out at icon height and overhanging the row, so toggle tiles line up with tiles without a button;
                    // shifted right by the same overhang so the gap to the card's right edge matches the gap above it.
                    Box(Modifier.layoutHeight(TileIconSize).offset(x = (48.dp - TileIconSize) / 2)) { PowerButton(entity, live, send) }
                } else if (p.showsValue) {
                    Spacer(Modifier.width(8.dp))
                    Text(displayValue(entity) ?: "—", textAlign = TextAlign.End, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
            }
            Text(entity.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            deviceLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            when (p) {
                is Platform.Light -> if (p.supportsBrightness && (entity.state as? EntityState.Light)?.brightness != null) BrightnessSlider(entity, send) else Caption(onOffText(entity))
                Platform.Switch -> Caption(onOffText(entity))
                is Platform.Fan -> Caption(displayValue(entity) ?: onOffText(entity))
                is Platform.Select -> SelectControl(entity, live, send)
                is Platform.Number -> NumberControl(entity, send)
                Platform.Button -> PressButton(entity, send)
                is Platform.Cover -> PositionButtons(p.supportsStop, send)
                is Platform.Valve -> PositionButtons(p.supportsStop, send)
                is Platform.Lock -> LockButtons(entity, p, send, includeOpen = false)
                is Platform.Climate -> ClimateTarget(entity, p, send)
                is Platform.Unsupported -> UnsupportedCaption(p)
                else -> Unit
            }
        }
        if (pending) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(10.dp).size(16.dp), strokeWidth = 2.dp)
      }
    }
}

/**
 * Small dashboard tile: icon, name, device name and state (always the last line, like big tiles), no inline controls. A tap toggles lights, switches and fans when the
 * section's tap action is Toggle, and otherwise opens all controls ([onOpen]); a long-press always opens them.
 */
@Composable
fun CompactTile(entity: Entity, session: ControlSession?, live: Entity?, modifier: Modifier = Modifier, deviceLabel: String? = null, onOpen: (() -> Unit)? = null, overlay: (@Composable BoxScope.() -> Unit)? = null) {
    val style = LocalEntityStyle.current
    var pending by remember(entity.stableId) { mutableStateOf(false) }
    LaunchedEffect(live?.state) { pending = false }
    LaunchedEffect(pending) { if (pending) { kotlinx.coroutines.delay(5000); pending = false } }
    val send = Sender(session, live, isCommandReady(session.isConnected, live) && !LocalEntityStyle.current.readOnly) { if (style.pendingSpinner) pending = true }
    val p = entity.platform
    val tapToggle = style.tapToToggle && p.isToggle && overlay == null
    val on = isOn(entity.state) == true
    val gestures = if (overlay != null || (onOpen == null && !tapToggle)) Modifier else Modifier.pointerInput(onOpen, tapToggle, send.ready, isOn(live?.state)) {
        detectTapGestures(
            onTap = { if (tapToggle) send(Command.SetOn(isOn(live?.state) != true)) else onOpen?.invoke() },
            onLongPress = if (onOpen != null) { _ -> onOpen() } else null,
        )
    }
    val colors = if (p.isToggle && on) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else appCardColors()
    Card(modifier.heightIn(min = 64.dp).then(gestures), shape = AppCardShape, colors = colors, elevation = appCardElevation()) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                EntityIcon(entity, size = 24.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(entity.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    deviceLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Text(compactState(entity, style.brightnessPercent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (pending) CircularProgressIndicator(Modifier.padding(start = 6.dp).size(14.dp), strokeWidth = 2.dp)
                overlay?.let { Box { it() } }
            }
        }
    }
}

/** One-line state for a compact tile: on/off with brightness for lights, otherwise the formatted value. */
private fun compactState(entity: Entity, brightnessPercent: Boolean): String {
    val p = entity.platform
    if (p.isToggle) {
        val base = onOffText(entity)
        val b = (entity.state as? EntityState.Light)?.brightness
        return if (brightnessPercent && isOn(entity.state) == true && b != null && (p as? Platform.Light)?.supportsBrightness == true) "$base · ${(b * 100).roundToInt()}%" else base
    }
    return when (p) {
        is Platform.Unsupported -> "${p.platform} · not supported yet"
        Platform.Button -> "Button"
        else -> displayValue(entity) ?: "—"
    }
}

/** Compact row for read-only values (sensors, binary and text sensors) in the dashboard's list card. */
@Composable
fun ReadOnlyRow(entity: Entity, deviceLabel: String? = null, onOpen: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.combinedClickable(onLongClick = onOpen, onClick = onOpen) else Modifier).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        EntityIcon(entity, size = 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entity.name, style = MaterialTheme.typography.bodyMedium)
            deviceLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(displayValue(entity) ?: "—", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.padding(start = 12.dp))
        trailing?.invoke()
    }
}

/**
 * Compact dashboard list row for controllable entities: icon, name and one main control on the right
 * (switch, select, press button, or the current value). Secondary controls live in the controls sheet ([onOpen]).
 */
@Composable
fun CompactControlRow(entity: Entity, session: ControlSession?, live: Entity?, deviceLabel: String? = null, onOpen: (() -> Unit)? = null) {
    val style = LocalEntityStyle.current
    var pending by remember(entity.stableId) { mutableStateOf(false) }
    LaunchedEffect(live?.state) { pending = false }
    LaunchedEffect(pending) { if (pending) { kotlinx.coroutines.delay(5000); pending = false } }
    val send = Sender(session, live, isCommandReady(session.isConnected, live) && !LocalEntityStyle.current.readOnly) { if (style.pendingSpinner) pending = true }
    val p = entity.platform
    val tapToggle = style.tapToToggle && p.isToggle
    // One handler for tap and long-press, so a long-press opens the controls without also toggling.
    val click = when {
        tapToggle -> Modifier.combinedClickable(onLongClick = onOpen) { send(Command.SetOn(isOn(live?.state) != true)) }
        onOpen != null -> Modifier.combinedClickable(onLongClick = onOpen, onClick = onOpen)
        else -> Modifier
    }
    Row(Modifier.fillMaxWidth().then(click).heightIn(min = 44.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        EntityIcon(entity, size = 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entity.name, style = MaterialTheme.typography.bodyMedium)
            deviceLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (pending) CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(16.dp), strokeWidth = 2.dp)
        when (p) {
            is Platform.Light, Platform.Switch, is Platform.Fan -> {
                (entity.state as? EntityState.Light)?.brightness?.takeIf { isOn(entity.state) == true && (p as? Platform.Light)?.supportsBrightness == true }?.let {
                    Text("${(it * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                }
                Switch(checked = isOn(entity.state) == true, enabled = send.ready, onCheckedChange = { send(Command.SetOn(it)) })
            }
            is Platform.Select -> SelectControl(entity, live, send)
            Platform.Button -> PressButton(entity, send)
            is Platform.Unsupported -> UnsupportedCaption(p)
            else -> Text(displayValue(entity) ?: "—", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** Device page row: name on the left, value or main control on the right, secondary controls below. */
@Composable
fun EntityDetailRow(entity: Entity, session: ControlSession?, live: Entity?) {
    Card(Modifier.fillMaxWidth(), shape = AppCardShape, colors = appCardColors(), elevation = appCardElevation()) { EntityControls(entity, session, live, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
}

/**
 * All controls an entity advertises: used on the device page and in the dashboard's bottom sheet.
 * With [leadingIcon] the entity icon starts the row and secondary controls line up after it.
 */
@Composable
fun EntityControls(entity: Entity, session: ControlSession?, live: Entity?, modifier: Modifier = Modifier, leadingIcon: Boolean = false) {
    if (!leadingIcon) { EntityControlsColumn(entity, session, live, modifier); return }
    Row(Modifier.fillMaxWidth().then(modifier)) {
        EntityIcon(entity, Modifier.padding(top = 9.dp, end = 14.dp), size = 22.dp)
        EntityControlsColumn(entity, session, live, Modifier.weight(1f))
    }
}

@Composable
private fun EntityControlsColumn(entity: Entity, session: ControlSession?, live: Entity?, modifier: Modifier) {
    val send = Sender(session, live, isCommandReady(session.isConnected, live) && !LocalEntityStyle.current.readOnly)
    val p = entity.platform
    run {
        Column(Modifier.fillMaxWidth().then(modifier), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(entity.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                when (p) {
                    is Platform.Light, Platform.Switch, is Platform.Fan -> Switch(checked = isOn(entity.state) == true, enabled = send.ready, onCheckedChange = { send(Command.SetOn(it)) })
                    is Platform.Select -> SelectControl(entity, live, send)
                    Platform.Button -> PressButton(entity, send)
                    is Platform.Unsupported -> UnsupportedCaption(p)
                    is Platform.Number -> Unit
                    else -> Text(displayValue(entity) ?: "—", Modifier.weight(1f).padding(start = 12.dp), textAlign = TextAlign.End)
                }
            }
            when (p) {
                is Platform.Light -> LightControls(entity, p, send)
                is Platform.Number -> NumberControl(entity, send)
                is Platform.Cover -> {
                    PositionButtons(p.supportsStop, send)
                    val s = entity.state as? EntityState.Positioned
                    if (p.supportsPosition && s != null) FractionSlider("Position", s.position, send.ready) { send(Command.SetPosition(it)) }
                    if (p.supportsTilt && s?.tilt != null) FractionSlider("Tilt", s.tilt, send.ready) { send(Command.SetTilt(it)) }
                }
                is Platform.Valve -> {
                    PositionButtons(p.supportsStop, send)
                    val s = entity.state as? EntityState.Positioned
                    if (p.supportsPosition && s != null) FractionSlider("Position", s.position, send.ready) { send(Command.SetPosition(it)) }
                }
                is Platform.Fan -> FanControls(entity, p, send)
                is Platform.Lock -> LockButtons(entity, p, send, includeOpen = true)
                is Platform.Climate -> ClimateControls(entity, p, send)
                else -> Unit
            }
        }
    }
}

private fun onOffText(entity: Entity) = when (isOn(entity.state)) { true -> "On"; false -> "Off"; null -> if (entity.state == EntityState.Missing) "Unknown" else "Waiting for state" }

@Composable
private fun Caption(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun UnsupportedCaption(p: Platform.Unsupported) = Caption("${p.platform} · not supported yet")

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

private val TileIconSize = 30.dp

/** Reports [height] to the parent while drawing the content at its own size, centred (it may overhang). */
private fun Modifier.layoutHeight(height: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    val h = height.roundToPx()
    layout(placeable.width, h) { placeable.place(0, (h - placeable.height) / 2) }
}

@Composable
private fun PowerButton(entity: Entity, live: Entity?, send: Sender) {
    val on = isOn(entity.state) == true
    FilledTonalIconButton(
        enabled = send.ready,
        onClick = { send(Command.SetOn(isOn(live?.state) != true)) },
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        ),
    ) { Icon(Icons.Default.PowerSettingsNew, contentDescription = if (on) "Turn ${entity.name} off" else "Turn ${entity.name} on") }
}

/**
 * Brightness slider with its value on a caption line above it, like number sliders: "Off" or "On · 57%" on tiles,
 * "Brightness · 57%" in the controls sheet ([label]). The percentage follows the slider while it is dragged.
 */
@Composable
private fun BrightnessSlider(entity: Entity, send: Sender, label: String? = null) {
    val brightness = (entity.state as? EntityState.Light)?.brightness ?: return
    var slider by remember(entity.stableId, brightness) { mutableFloatStateOf(brightness.coerceIn(0f, 1f)) }
    val on = isOn(entity.state) == true
    val percent = if (LocalEntityStyle.current.brightnessPercent) "${Math.round(slider * 100)}%" else null
    Caption(when {
        label != null -> listOfNotNull(label, percent).joinToString(" · ")
        on -> listOfNotNull("On", percent).joinToString(" · ")
        else -> "Off"
    })
    Slider(value = slider, enabled = send.ready, onValueChange = { slider = it }, onValueChangeFinished = { send(Command.SetBrightness(slider)) }, modifier = Modifier.fillMaxWidth())
}

/** A slider that sends only when released, showing a percentage. */
@Composable
private fun FractionSlider(label: String, value: Float, enabled: Boolean, onSet: (Float) -> Unit) {
    var slider by remember(label, value) { mutableFloatStateOf(value.coerceIn(0f, 1f)) }
    Text("$label · ${Math.round(slider * 100)}%", style = MaterialTheme.typography.bodySmall)
    Slider(value = slider, enabled = enabled, onValueChange = { slider = it }, onValueChangeFinished = { onSet(slider) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun OptionDropdown(value: String?, options: List<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled && options.isNotEmpty(), onClick = { expanded = true }) { Text(value ?: "—", maxLines = 1) }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(option) }) }
        }
    }
}

@Composable
private fun SelectControl(entity: Entity, live: Entity?, send: Sender) =
    OptionDropdown(displayValue(entity), (live?.platform as? Platform.Select)?.options.orEmpty(), send.ready) { send(Command.SelectOption(it)) }

@Composable
private fun ConfirmDialog(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
    confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(action) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
)

@Composable
private fun PressButton(entity: Entity, send: Sender) {
    // Configuration and diagnostic buttons include restart and factory reset; confirm those.
    val confirm = entity.category != EntityCategory.NONE
    var asking by remember(entity.stableId) { mutableStateOf(false) }
    FilledTonalButton(enabled = send.ready, onClick = { if (confirm) asking = true else send(Command.Press) }) { Text("Press") }
    if (asking) ConfirmDialog("Press ${entity.name}?", "This runs the action on the device.", "Press", { send(Command.Press) }, { asking = false })
}

@Composable
private fun PositionButtons(supportsStop: Boolean, send: Sender) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalIconButton(enabled = send.ready, onClick = { send(Command.Open) }) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Open") }
        if (supportsStop) FilledTonalIconButton(enabled = send.ready, onClick = { send(Command.Stop) }) { Icon(Icons.Default.Stop, contentDescription = "Stop") }
        FilledTonalIconButton(enabled = send.ready, onClick = { send(Command.Close) }) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close") }
    }
}

@Composable
private fun LockButtons(entity: Entity, p: Platform.Lock, send: Sender, includeOpen: Boolean) {
    val status = (entity.state as? EntityState.Lock)?.status
    // Unlocking and opening reduce physical security; always confirm and ask for a code when the lock requires one.
    var pending by remember(entity.stableId) { mutableStateOf<LockAction?>(null) }
    fun request(action: LockAction) { if (action == LockAction.LOCK && !p.requiresCode) send(Command.Lock(action)) else pending = action }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status == LockStatus.LOCKED || status == LockStatus.LOCKING) FilledTonalButton(enabled = send.ready, onClick = { request(LockAction.UNLOCK) }) { Text("Unlock") }
        else FilledTonalButton(enabled = send.ready, onClick = { request(LockAction.LOCK) }) { Text("Lock") }
        if (includeOpen && p.supportsOpen) OutlinedButton(enabled = send.ready, onClick = { request(LockAction.OPEN) }) { Text("Open") }
    }
    pending?.let { action ->
        var code by remember { mutableStateOf("") }
        val verb = action.name.lowercase().replaceFirstChar { it.uppercase() }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("$verb ${entity.name}?") },
            text = {
                if (p.requiresCode) OutlinedTextField(code, { code = it }, label = { Text("Code") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (p.codeFormat.isEmpty() || p.codeFormat.all { it.isDigit() || it in "^$[]*+{}\\d" }) KeyboardType.NumberPassword else KeyboardType.Password))
                else Text("This changes the lock on the device.")
            },
            confirmButton = { TextButton(enabled = !p.requiresCode || code.isNotEmpty(), onClick = { pending = null; send(Command.Lock(action, code.ifEmpty { null })) }) { Text(verb) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LightControls(entity: Entity, p: Platform.Light, send: Sender) {
    val s = entity.state as? EntityState.Light
    if (p.supportsBrightness && s?.brightness != null) BrightnessSlider(entity, send, label = "Brightness")
    if (p.supportsRgb) {
        var hue by remember(entity.stableId, s?.rgb) { mutableFloatStateOf(s?.rgb?.let(::hueOf) ?: 0f) }
        val c = rgbForHue(hue)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption("Colour")
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(16.dp).background(Color(c.red, c.green, c.blue), CircleShape))
        }
        Slider(value = hue, valueRange = 0f..360f, enabled = send.ready, onValueChange = { hue = it }, onValueChangeFinished = { send(Command.SetRgb(rgbForHue(hue))) },
            colors = SliderDefaults.colors(thumbColor = Color(c.red, c.green, c.blue)), modifier = Modifier.fillMaxWidth())
    }
    if (p.supportsColorTemperature) {
        val min = p.minMireds!!; val max = p.maxMireds!!
        var mireds by remember(entity.stableId, s?.mireds) { mutableFloatStateOf((s?.mireds ?: (min + max) / 2).coerceIn(min, max)) }
        Caption("Colour temperature · ${miredsToKelvin(mireds)} K")
        Slider(value = mireds, valueRange = min..max, enabled = send.ready, onValueChange = { mireds = it }, onValueChangeFinished = { send(Command.SetColorTemperature(mireds)) }, modifier = Modifier.fillMaxWidth())
    }
    if (p.effects.isNotEmpty()) LabeledRow("Effect") { OptionDropdown(s?.effect ?: "None", listOf("None") + p.effects, send.ready) { send(Command.SetEffect(it)) } }
}

@Composable
private fun FanControls(entity: Entity, p: Platform.Fan, send: Sender) {
    val s = entity.state as? EntityState.Fan
    if (p.speedCount > 0) {
        var level by remember(entity.stableId, s?.speedLevel) { mutableFloatStateOf((s?.speedLevel ?: 1).coerceIn(1, p.speedCount).toFloat()) }
        Caption("Speed · ${level.toInt()} of ${p.speedCount}")
        if (p.speedCount > 1) Slider(value = level, valueRange = 1f..p.speedCount.toFloat(), steps = (p.speedCount - 2).coerceAtLeast(0), enabled = send.ready,
            onValueChange = { level = it }, onValueChangeFinished = { send(Command.SetFanSpeed(Math.round(level))) }, modifier = Modifier.fillMaxWidth())
    }
    if (p.supportsOscillation) LabeledRow("Oscillation") { Switch(checked = s?.oscillating == true, enabled = send.ready, onCheckedChange = { send(Command.SetOscillation(it)) }) }
    if (p.supportsDirection) LabeledRow("Reverse direction") { Switch(checked = s?.reverse == true, enabled = send.ready, onCheckedChange = { send(Command.SetDirection(it)) }) }
    if (p.presets.isNotEmpty()) LabeledRow("Preset") { OptionDropdown(s?.preset, p.presets, send.ready) { send(Command.SetPreset(it)) } }
}

@Composable
private fun TemperatureStepper(value: Float?, p: Platform.Climate, enabled: Boolean, onSet: (Float) -> Unit) {
    val decimals = decimalsForStep(p.step)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(enabled = enabled && value != null && value > p.minTemperature, onClick = { onSet(snapTemperature(value!! - p.step, p)) }) { Icon(Icons.Default.Remove, contentDescription = "Lower") }
        Text(value?.let { "${formatNumber(it, decimals)} ${p.unit}" } ?: "—", style = MaterialTheme.typography.titleMedium)
        IconButton(enabled = enabled && value != null && value < p.maxTemperature, onClick = { onSet(snapTemperature(value!! + p.step, p)) }) { Icon(Icons.Default.Add, contentDescription = "Raise") }
    }
}

/** Target temperature(s): one stepper, or low and high steppers for two-point devices. */
@Composable
private fun ClimateTarget(entity: Entity, p: Platform.Climate, send: Sender) {
    val s = entity.state as? EntityState.Climate
    if (!p.twoPoint) TemperatureStepper(s?.target, p, send.ready) { send(Command.SetTargetTemperature(it)) }
    else {
        TemperatureStepper(s?.targetLow, p, send.ready) { low -> s?.targetHigh?.let { send(Command.SetTargetRange(low, maxOf(low, it))) } }
        TemperatureStepper(s?.targetHigh, p, send.ready) { high -> s?.targetLow?.let { send(Command.SetTargetRange(minOf(it, high), high)) } }
    }
    s?.mode?.let { Caption(listOfNotNull(it, s.action?.takeIf { p.action }).joinToString(" · ")) }
}

@Composable
private fun ClimateControls(entity: Entity, p: Platform.Climate, send: Sender) {
    val s = entity.state as? EntityState.Climate
    if (p.modes.isNotEmpty()) LabeledRow("Mode") { OptionDropdown(s?.mode, p.modes, send.ready) { send(Command.SetClimateMode(it)) } }
    if (!p.twoPoint) LabeledRow("Target") { TemperatureStepper(s?.target, p, send.ready) { send(Command.SetTargetTemperature(it)) } }
    else {
        LabeledRow("Low") { TemperatureStepper(s?.targetLow, p, send.ready) { low -> s?.targetHigh?.let { send(Command.SetTargetRange(low, maxOf(low, it))) } } }
        LabeledRow("High") { TemperatureStepper(s?.targetHigh, p, send.ready) { high -> s?.targetLow?.let { send(Command.SetTargetRange(minOf(it, high), high)) } } }
    }
    if (p.fanModes.isNotEmpty()) LabeledRow("Fan") { OptionDropdown(s?.fanMode, p.fanModes, send.ready) { send(Command.SetFanMode(it)) } }
    if (p.swingModes.isNotEmpty()) LabeledRow("Swing") { OptionDropdown(s?.swingMode, p.swingModes, send.ready) { send(Command.SetSwingMode(it)) } }
    if (p.presets.isNotEmpty()) LabeledRow("Preset") { OptionDropdown(s?.preset, p.presets, send.ready) { send(Command.SetPreset(it)) } }
    if (p.action && s?.action != null) LabeledRow("Action") { Text(s.action) }
    if (p.currentHumidity && s?.currentHumidity != null) LabeledRow("Humidity") { Text("${formatNumber(s.currentHumidity, 0)} %") }
}

@Composable
private fun NumberControl(entity: Entity, send: Sender) {
    val p = entity.platform as? Platform.Number ?: return
    val current = (entity.state as? EntityState.Numeric)?.value
    val usable = send.ready && p.max > p.min
    val unit = if (p.unit.isBlank()) "" else " ${p.unit}"
    if (p.mode == NumberMode.BOX || !(p.max > p.min)) {
        var editing by remember(entity.stableId) { mutableStateOf(false) }
        OutlinedButton(enabled = usable, onClick = { editing = true }) { Text(displayValue(entity) ?: "—", maxLines = 1) }
        if (editing) NumberInputDialog(entity.name, p, current, onDismiss = { editing = false }) { value -> editing = false; send(Command.SetNumber(value)) }
    } else {
        var slider by remember(entity.stableId, current) { mutableFloatStateOf((current ?: p.min).coerceIn(p.min, p.max)) }
        val intervals = if (p.step > 0f) ((p.max - p.min) / p.step).toInt() else 0
        Text(if (current == null) "—" else formatNumber(snapToStep(slider, p), decimalsForStep(p.step)) + unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = slider, valueRange = p.min..p.max, steps = if (intervals in 2..100) intervals - 1 else 0, enabled = usable,
            onValueChange = { slider = it }, onValueChangeFinished = { send(Command.SetNumber(snapToStep(slider, p))) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NumberInputDialog(name: String, p: Platform.Number, current: Float?, onDismiss: () -> Unit, onSet: (Float) -> Unit) {
    var text by remember { mutableStateOf(current?.let { formatNumber(it, decimalsForStep(p.step), java.util.Locale.ROOT) } ?: "") }
    val value = text.replace(',', '.').toFloatOrNull()
    val valid = value != null && value in p.min..p.max
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, isError = text.isNotEmpty() && !valid,
                label = { Text("${formatNumber(p.min, decimalsForStep(p.step))} – ${formatNumber(p.max, decimalsForStep(p.step))} ${p.unit}".trim()) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSet(snapToStep(value!!, p)) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
