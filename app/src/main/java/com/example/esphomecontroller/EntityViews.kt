package com.example.esphomecontroller

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    is Platform.Unsupported -> Icons.AutoMirrored.Filled.HelpOutline
}

private val Platform.isToggle get() = this is Platform.Light || this == Platform.Switch
/** Platforms whose value is shown as text next to the icon or name. */
private val Platform.isReadOnly get() = this is Platform.Sensor || this == Platform.BinarySensor || this == Platform.TextSensor

/** Dashboard tile. [entity] may be cached; [live] is the current connection's copy and gates commands. */
@Composable
fun EntityTile(entity: Entity, session: ControlSession?, live: Entity?, modifier: Modifier = Modifier) {
    val ready = isCommandReady(session.isConnected, live)
    val p = entity.platform
    Card(modifier.heightIn(min = 142.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(p.icon(), contentDescription = null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                if (p.isToggle) {
                    Spacer(Modifier.weight(1f))
                    PowerButton(entity, session, live, ready)
                } else if (p.isReadOnly) {
                    Spacer(Modifier.width(8.dp))
                    Text(displayValue(entity) ?: "—", textAlign = TextAlign.End, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
            }
            Text(entity.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            when (p) {
                is Platform.Light -> if (p.supportsBrightness && (entity.state as? EntityState.Light)?.brightness != null) BrightnessSlider(entity, session, live, ready)
                    else OnOffCaption(entity)
                Platform.Switch -> OnOffCaption(entity)
                is Platform.Select -> SelectControl(entity, session, live, ready)
                is Platform.Number -> NumberControl(entity, session, live, ready)
                Platform.Button -> PressButton(entity, session, live, ready)
                is Platform.Unsupported -> UnsupportedCaption(p)
                else -> Unit
            }
        }
    }
}

/** Device page row: name on the left, value or control aligned on the right. */
@Composable
fun EntityDetailRow(entity: Entity, session: ControlSession?, live: Entity?) {
    val ready = isCommandReady(session.isConnected, live)
    val p = entity.platform
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(entity.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                when (p) {
                    is Platform.Light, Platform.Switch -> Switch(checked = isOn(entity.state) == true, enabled = ready, onCheckedChange = { if (live != null) session?.send(live, Command.SetOn(it)) })
                    is Platform.Select -> SelectControl(entity, session, live, ready)
                    Platform.Button -> PressButton(entity, session, live, ready)
                    is Platform.Unsupported -> UnsupportedCaption(p)
                    is Platform.Number -> Unit
                    else -> Text(displayValue(entity) ?: "—", Modifier.weight(1f).padding(start = 12.dp), textAlign = TextAlign.End)
                }
            }
            if (p is Platform.Light && p.supportsBrightness && (entity.state as? EntityState.Light)?.brightness != null) {
                Text("Brightness", style = MaterialTheme.typography.bodySmall)
                BrightnessSlider(entity, session, live, ready)
            }
            if (p is Platform.Number) NumberControl(entity, session, live, ready)
        }
    }
}

@Composable
private fun OnOffCaption(entity: Entity) {
    val text = when (isOn(entity.state)) { true -> "On"; false -> "Off"; null -> if (entity.state == EntityState.Missing) "Unknown" else "Waiting for state" }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun UnsupportedCaption(p: Platform.Unsupported) =
    Text("${p.platform} · not supported yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun PowerButton(entity: Entity, session: ControlSession?, live: Entity?, ready: Boolean) {
    val on = isOn(entity.state) == true
    FilledTonalIconButton(
        enabled = ready,
        onClick = { if (live != null) session?.send(live, Command.SetOn(isOn(live.state) != true)) },
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        ),
    ) { Icon(Icons.Default.PowerSettingsNew, contentDescription = if (on) "Turn ${entity.name} off" else "Turn ${entity.name} on") }
}

@Composable
private fun BrightnessSlider(entity: Entity, session: ControlSession?, live: Entity?, ready: Boolean) {
    val brightness = (entity.state as? EntityState.Light)?.brightness ?: return
    var slider by remember(entity.stableId, brightness) { mutableFloatStateOf(brightness.coerceIn(0f, 1f)) }
    Slider(value = slider, enabled = ready, onValueChange = { slider = it }, onValueChangeFinished = { if (live != null) session?.send(live, Command.SetBrightness(slider)) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SelectControl(entity: Entity, session: ControlSession?, live: Entity?, ready: Boolean) {
    var expanded by remember(entity.stableId) { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = ready, onClick = { expanded = true }) { Text(displayValue(entity) ?: "—", maxLines = 1) }
        DropdownMenu(expanded = expanded && ready, onDismissRequest = { expanded = false }) {
            (live?.platform as? Platform.Select)?.options.orEmpty().forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; if (live != null) session?.send(live, Command.SelectOption(option)) })
            }
        }
    }
}

@Composable
private fun PressButton(entity: Entity, session: ControlSession?, live: Entity?, ready: Boolean) {
    // Configuration and diagnostic buttons include restart and factory reset; confirm those.
    val confirm = entity.category != EntityCategory.NONE
    var asking by remember(entity.stableId) { mutableStateOf(false) }
    fun press() { if (live != null) session?.send(live, Command.Press) }
    FilledTonalButton(enabled = ready, onClick = { if (confirm) asking = true else press() }) { Text("Press") }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Press ${entity.name}?") },
        text = { Text("This runs the action on the device.") },
        confirmButton = { TextButton(onClick = { asking = false; press() }) { Text("Press") } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
    )
}

@Composable
private fun NumberControl(entity: Entity, session: ControlSession?, live: Entity?, ready: Boolean) {
    val p = entity.platform as? Platform.Number ?: return
    val current = (entity.state as? EntityState.Numeric)?.value
    val usable = ready && p.max > p.min
    val unit = if (p.unit.isBlank()) "" else " ${p.unit}"
    if (p.mode == NumberMode.BOX || !(p.max > p.min)) {
        var editing by remember(entity.stableId) { mutableStateOf(false) }
        OutlinedButton(enabled = usable, onClick = { editing = true }) { Text(displayValue(entity) ?: "—", maxLines = 1) }
        if (editing) NumberInputDialog(entity.name, p, current, onDismiss = { editing = false }) { value ->
            editing = false; if (live != null) session?.send(live, Command.SetNumber(value))
        }
    } else {
        var slider by remember(entity.stableId, current) { mutableFloatStateOf((current ?: p.min).coerceIn(p.min, p.max)) }
        val intervals = if (p.step > 0f) ((p.max - p.min) / p.step).toInt() else 0
        Text(if (current == null) "—" else formatNumber(snapToStep(slider, p), decimalsForStep(p.step)) + unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = slider, valueRange = p.min..p.max, steps = if (intervals in 2..100) intervals - 1 else 0, enabled = usable,
            onValueChange = { slider = it },
            onValueChangeFinished = { if (live != null) session?.send(live, Command.SetNumber(snapToStep(slider, p))) },
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
