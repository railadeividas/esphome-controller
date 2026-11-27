package com.example.esphomecontroller

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/** The shade of a preset [TitleColor] that reads well on the current theme's background. */
@Composable
fun TitleColor.color(): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    fun pick(onDark: Long, onLight: Long) = Color(if (dark) onDark else onLight)
    return when (this) {
        TitleColor.DEFAULT, TitleColor.CUSTOM -> Color.Unspecified
        TitleColor.ACCENT -> MaterialTheme.colorScheme.primary
        TitleColor.AMBER -> pick(0xFFFFCA28, 0xFFB26A00)
        TitleColor.GREEN -> pick(0xFF81C995, 0xFF1E7D3A)
        TitleColor.BLUE -> pick(0xFF8AB4F8, 0xFF1A5FB4)
        TitleColor.RED -> pick(0xFFF28B82, 0xFFB3261E)
        TitleColor.PINK -> pick(0xFFF8A1C4, 0xFFA8326E)
    }
}

/** This section's title colour; [Color.Unspecified] for the theme's default. */
@Composable
fun Section.titleColorValue(): Color =
    if (titleColor == TitleColor.CUSTOM) customColor?.let { Color(it) } ?: Color.Unspecified else titleColor.color()

private fun Section.titleColorName(): String = when (titleColor) {
    TitleColor.CUSTOM -> customColor?.let { "Custom #%06X".format(it and 0xFFFFFF) } ?: "Custom"
    else -> titleColor.label
}

/** Groups of MDI icons shown in the icon picker before searching; every name exists in the bundled MDI set. */
val SuggestedSectionIcons: List<Pair<String, List<String>>> = listOf(
    "Rooms" to "home sofa bed-king bed fridge stove silverware-fork-knife bathtub shower toilet briefcase desk stairs garage garage-variant flower tree door window-closed-variant balcony baby-face-outline washing-machine",
    "Lighting" to "lightbulb ceiling-light floor-lamp lamp desk-lamp led-strip-variant track-light wall-sconce string-lights coach-lamp lightbulb-group",
    "Devices" to "power-plug power-socket-eu television speaker cctv dishwasher robot-vacuum coffee-maker router-wireless wifi motion-sensor blinds curtains ceiling-fan fan air-purifier printer-3d",
    "Climate & energy" to "thermometer thermostat snowflake fire radiator air-conditioner weather-windy water-percent water weather-sunny weather-night solar-power flash battery leaf chart-bar gauge",
    "Security" to "shield-home lock door-closed-lock bell alarm-light smoke-detector key",
    "Other" to "star heart paw car car-electric clock-outline calendar sprout pool bike gamepad-variant music",
).map { (group, names) -> group to names.split(' ') }

/** The MDI icon a section shows, or null for none (or while the MDI index is still loading). */
fun sectionIcon(name: String?): MdiIcon? = Mdi.find(name)

/**
 * Title colour and title icon rows of the section dialog. Each row has a button that opens its picker dialog,
 * the current choice's name, and Reset/Remove when something is set.
 */
@Composable
fun TitleLook(s: Section, title: String, size: TitleSize, onChange: (Section) -> Unit) {
    var colourOpen by remember { mutableStateOf(false) }
    var iconOpen by remember { mutableStateOf(false) }
    val colour = s.titleColorValue()
    LookRow("Title colour", s.titleColorName(), onOpen = { colourOpen = true }, contentDescription = "Choose title colour",
        reset = if (s.titleColor != TitleColor.DEFAULT) ("Reset" to { onChange(s.copy(titleColor = TitleColor.DEFAULT, customColor = null)) }) else null) {
        ColourDot(colour, 18.dp)
    }
    val icon = sectionIcon(s.icon)
    LookRow("Title icon", icon?.label ?: s.icon ?: "No icon", onOpen = { iconOpen = true }, contentDescription = "Choose title icon",
        reset = if (s.icon != null) ("Remove" to { onChange(s.copy(icon = null)) }) else null) {
        if (icon != null) MdiGlyph(icon, 20.dp, tint = colour)
        else Icon(Icons.Default.Block, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
    if (colourOpen) TitleColourDialog(s, title, size, onChange, onDismiss = { colourOpen = false })
    if (iconOpen) TitleIconDialog(s.icon, colour, onPick = { onChange(s.copy(icon = it)); iconOpen = false }, onDismiss = { iconOpen = false })
}

@Composable
private fun LookRow(label: String, value: String, onOpen: () -> Unit, contentDescription: String, reset: Pair<String, () -> Unit>?, button: @Composable () -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(onClick = onOpen, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) { button() }
        }
        Spacer(Modifier.width(12.dp))
        Text(value, Modifier.weight(1f).clickable(onClickLabel = contentDescription, onClick = onOpen))
        reset?.let { (text, action) -> TextButton(onClick = action) { Text(text) } }
    }
}

/** A filled colour circle, or for the default colour an outlined circle crossed by a diagonal line. */
@Composable
private fun ColourDot(color: Color, size: Dp, modifier: Modifier = Modifier) {
    if (color.isSpecified) Box(modifier.size(size).clip(CircleShape).background(color))
    else {
        val outline = MaterialTheme.colorScheme.outline
        Box(modifier.size(size).border(2.dp, outline, CircleShape).drawBehind {
            val inset = this.size.width * 0.18f
            drawLine(outline, Offset(this.size.width - inset, inset), Offset(inset, this.size.height - inset), strokeWidth = 2.dp.toPx())
        })
    }
}

private val Color.isSpecified get() = this != Color.Unspecified

private fun hslOf(color: Color): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }
private fun colourOf(hsl: FloatArray) = Color(ColorUtils.HSLToColor(hsl))

/**
 * Title colour picker: one row of Default, six presets and a Custom swatch, with hue, saturation and lightness
 * sliders always shown. A preset loads into the sliders; moving a slider turns it into the custom colour, so presets
 * can be adjusted slightly. Changes apply immediately; Done closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitleColourDialog(s: Section, title: String, size: TitleSize, onChange: (Section) -> Unit, onDismiss: () -> Unit) {
    val presets = TitleColor.PRESETS.map { it to it.color() }
    val current = s.titleColorValue()
    val start = current.takeOrElse { presets.first().second }
    var hsl by remember { mutableStateOf(hslOf(start).also { it[2] = it[2].coerceIn(0.15f, 0.9f) }) }
    var adjustedFrom by remember { mutableStateOf<TitleColor?>(null) }
    fun setCustom(next: FloatArray) { hsl = next; onChange(s.copy(titleColor = TitleColor.CUSTOM, customColor = colourOf(next).toArgb())) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Title colour") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Live preview of the section title.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    sectionIcon(s.icon)?.let { MdiGlyph(it, size.iconSize(), Modifier.padding(end = 8.dp), tint = current) }
                    Text(title.ifBlank { "Section title" }, style = size.textStyle(), color = current, maxLines = 1)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val swatch = Modifier.weight(1f).aspectRatio(1f)
                    Swatch(Color.Unspecified, s.titleColor == TitleColor.DEFAULT, "Default", swatch) {
                        adjustedFrom = null; onChange(s.copy(titleColor = TitleColor.DEFAULT, customColor = null))
                    }
                    presets.forEach { (preset, color) ->
                        Swatch(color, s.titleColor == preset, preset.label, swatch) {
                            hsl = hslOf(color); adjustedFrom = preset; onChange(s.copy(titleColor = preset, customColor = null))
                        }
                    }
                    Swatch(colourOf(hsl), s.titleColor == TitleColor.CUSTOM, "Custom", swatch, dashed = true) { setCustom(hsl) }
                }
                Text(
                    s.titleColorName() + if (s.titleColor == TitleColor.CUSTOM) adjustedFrom?.let { " (adjusted ${it.label})" }.orEmpty() else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val (h, sat, light) = Triple(hsl[0], hsl[1], hsl[2])
                GradientSlider("H", h, 0f..360f, "°", 1f, Brush.horizontalGradient((0..6).map { Color.hsl(it * 60f, sat, light) })) { setCustom(floatArrayOf(it, sat, light)) }
                GradientSlider("S", sat, 0f..1f, "%", 100f, Brush.horizontalGradient(listOf(Color.hsl(h, 0f, light), Color.hsl(h, 1f, light)))) { setCustom(floatArrayOf(h, it, light)) }
                GradientSlider("L", light, 0.15f..0.9f, "%", 100f, Brush.horizontalGradient(listOf(Color.hsl(h, sat, 0.15f), Color.hsl(h, sat, 0.5f), Color.hsl(h, sat, 0.9f)))) { setCustom(floatArrayOf(h, sat, it)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { onChange(s.copy(titleColor = TitleColor.DEFAULT, customColor = null)); onDismiss() }) { Text("Default") } },
    )
}

@Composable
private fun Swatch(color: Color, selected: Boolean, label: String, modifier: Modifier, dashed: Boolean = false, onClick: () -> Unit) {
    val ring = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier
            .then(if (selected) Modifier.border(2.dp, ring, CircleShape).padding(3.dp) else Modifier.padding(3.dp))
            .clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ColourDot(color, 40.dp, Modifier.fillMaxSize())
        if (dashed && !selected) Box(Modifier.fillMaxSize().drawBehind {
            drawCircle(outline, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
        })
        if (selected) Icon(Icons.Default.Check, contentDescription = "$label selected", modifier = Modifier.size(16.dp),
            tint = if (color.isSpecified && color.luminance() > 0.45f) Color.Black else if (color.isSpecified) Color.White else ring)
    }
}

/** A slider whose track is [brush]; [scale] converts the value for display (fractions shown as percent). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradientSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, scale: Float, brush: Brush, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(18.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f),
            thumb = { Box(Modifier.size(20.dp).border(2.dp, Color(0xFF333333), CircleShape).padding(2.dp).background(Color.White, CircleShape)) },
            track = { Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush)) },
        )
        Text("${(value * scale).roundToInt()}$unit", Modifier.width(42.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
}

/**
 * Title icon picker over every MDI icon: suggested groups and then all icons, or ranked search results while typing
 * (names, aliases and MDI tags, e.g. "couch" finds sofa).
 */
@Composable
private fun TitleIconDialog(selected: String?, tint: Color, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = Mdi.icons
    val results = remember(query, all) { if (query.isBlank()) emptyList() else Mdi.search(query, all) }
    val suggested = remember(all) { SuggestedSectionIcons.map { (group, names) -> group to names.mapNotNull { Mdi.find(it) } } }
    val everything = remember(all) { all.filterNot { it.deprecated } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose icon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search ${if (all.isEmpty()) "" else "${everything.size} "}icons") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth())
                sectionIcon(selected)?.let { Text("Selected: ${it.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                LazyVerticalGrid(GridCells.Adaptive(48.dp), Modifier.heightIn(max = 380.dp)) {
                    fun header(text: String) = item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    }
                    fun cells(prefix: String, icons: List<MdiIcon>) = items(icons, key = { prefix + it.name }, contentType = { "icon" }) { icon ->
                        IconCell(icon, icon.name == selected, tint) { onPick(icon.name) }
                    }
                    when {
                        all.isEmpty() -> header("Loading icons…")
                        query.isNotBlank() && results.isEmpty() -> header("No icons match \u201c${query.trim()}\u201d.")
                        query.isNotBlank() -> { header("${results.size} icons"); cells("r:", results) }
                        else -> {
                            suggested.forEach { (group, icons) -> header(group); cells("$group:", icons) }
                            header("All icons"); cells("a:", everything)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        dismissButton = { TextButton(onClick = { onPick(null) }) { Text("No icon") } },
    )
}

@Composable
private fun IconCell(icon: MdiIcon, selected: Boolean, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClickLabel = icon.label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { MdiGlyph(icon, 24.dp, tint = if (selected) tint else Color.Unspecified, contentDescription = icon.label) }
}
