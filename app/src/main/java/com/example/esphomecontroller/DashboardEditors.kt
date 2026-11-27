package com.example.esphomecontroller

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.esphomecontroller.api.Platform
import com.example.esphomecontroller.api.entityLabel

@Composable
private fun RadioRow(selected: Boolean, label: String, description: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Name and starting point for a new dashboard. */
@Composable
fun NewDashboardDialog(store: LocationStore, onDismiss: () -> Unit, onCreate: (String, DashboardTemplate) -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf<DashboardTemplate>(DashboardTemplate.Empty) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New dashboard") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Start from", style = MaterialTheme.typography.titleSmall)
                RadioRow(template == DashboardTemplate.Empty, "Empty", "Build it section by section") { template = DashboardTemplate.Empty }
                RadioRow(template == DashboardTemplate.Devices(null), "All devices", "One section per device") { template = DashboardTemplate.Devices(null) }
                store.locations.forEach { l ->
                    RadioRow(template == DashboardTemplate.Devices(l.id), "Devices in ${l.name}", "${l.devices.size} devices, one section each") { template = DashboardTemplate.Devices(l.id) }
                }
                RadioRow(template == DashboardTemplate.ByArea, "Grouped by area", "A heading per ESPHome area with its devices") { template = DashboardTemplate.ByArea }
                RadioRow(template == DashboardTemplate.ByType, "Grouped by type", "Lights, switches, climate… across all devices, plus readings") { template = DashboardTemplate.ByType }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, template) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Adds a section (type, title size, title and text) or edits one, including its style and options. */
@Composable
fun SectionDialog(existing: Section?, store: LocationStore, onDismiss: () -> Unit, onSave: (Section) -> Unit) {
    var type by remember { mutableStateOf(existing?.type ?: SectionType.TILES) }
    var o by remember { mutableStateOf(existing ?: Section(type = SectionType.TILES)) }
    var titleSize by remember { mutableStateOf(existing?.titleSize) }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var text by remember { mutableStateOf(existing?.text ?: "") }
    val valid = type != SectionType.HEADING || title.isNotBlank() || text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add section" else "Edit section") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (existing == null) {
                    Text("Type", style = MaterialTheme.typography.titleSmall)
                    SectionType.entries.forEach { t -> RadioRow(type == t, t.label, t.description) { type = t } }
                    Spacer(Modifier.height(8.dp))
                } else if (type in Section.CHOSEN_ITEM_TYPES) {
                    Text("Style", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Section.CHOSEN_ITEM_TYPES.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) }) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
                Text("Title size", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val effective = titleSize ?: Section(type = type).titleSizeOrDefault
                    TitleSize.entries.forEach { size -> FilterChip(selected = effective == size, onClick = { titleSize = size }, label = { Text(size.label, style = size.textStyle().copy(fontSize = MaterialTheme.typography.labelLarge.fontSize)) }) }
                }
                // Title colour, icon and options are set when editing, so adding a section stays short.
                if (existing != null) {
                    TitleLook(o, title, titleSize ?: Section(type = type).titleSizeOrDefault) { o = it }
                    if (type != SectionType.HEADING) SectionOptions(o.copy(type = type)) { o = it }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(o.copy(type = type, titleSize = titleSize, title = title.trim(), text = text.trim()))
            }) { Text(if (existing == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Full-screen picker of entities from all registered devices; keeps the order of already chosen ones. */
@Composable
fun EntityPicker(store: LocationStore, manager: ConnectionManager, selected: List<ItemRef>, onDismiss: () -> Unit, onDone: (List<ItemRef>) -> Unit) {
    var chosen by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Choose entities", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onDone(chosen) }) { Text("Done (${chosen.size})") }
                }
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
                if (store.devices.isEmpty()) Text("Add devices first; entities appear once a device has connected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f)) {
                    store.devices.forEach { d ->
                        val entities = manager.entities(d.id).filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || d.name.contains(query, ignoreCase = true) }
                        if (entities.isEmpty()) return@forEach
                        item(key = "h:${d.id}") { Text(d.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                        items(entities, key = { "${d.id}|${it.stableId}" }) { e ->
                            val ref = ItemRef(d.id, e.stableId)
                            val on = ref in chosen
                            Row(Modifier.fillMaxWidth().clickable { chosen = if (on) chosen - ref else chosen + ref }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = on, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                EntityIcon(e, size = 22.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.name)
                                    Text(entityLabel(e.platform), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Layout, content and behaviour of one section; only options that apply to [s]'s type are shown. */
@Composable
private fun SectionOptions(s: Section, onChange: (Section) -> Unit) {
    Spacer(Modifier.height(12.dp))
    Text("Options", style = MaterialTheme.typography.titleSmall)
    if (s.hasTiles) {
        Text("Tile columns", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(0 to "Auto", 1 to "1", 2 to "2", 3 to "3", 4 to "4").forEach { (value, label) ->
                FilterChip(selected = s.columns == value, onClick = { onChange(s.copy(columns = value)) }, label = { Text(label) })
            }
        }
        SwitchRow("Readings as a list", "Sensors and text values in a compact list under the tiles", s.readingsAsList) { onChange(s.copy(readingsAsList = it)) }
    }
    if (s.hasChosenItems) SwitchRow("Device names", "Under each entity name", s.showDeviceNames) { onChange(s.copy(showDeviceNames = it)) }
    SwitchRow("All on / all off button", "When the section has lights, switches or fans", s.allOnOff) { onChange(s.copy(allOnOff = it)) }
    SwitchRow("Collapsible", null, s.collapsible) { onChange(s.copy(collapsible = it)) }
    if (s.allOnOff && s.collapsible) SwitchRow("Keep all on / all off when collapsed", "The button stays in the header of a collapsed section", s.allOnOffWhenCollapsed) { onChange(s.copy(allOnOffWhenCollapsed = it)) }
    Text("Tap action", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
    TapAction.entries.forEach { a -> RadioRow(s.tapAction == a, a.label, a.description) { onChange(s.copy(tapAction = a)) } }
}

@Composable
private fun SwitchRow(label: String, description: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Options for the whole dashboard; layout and behaviour of sections are set on each section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardSettingsSheet(board: Dashboard, dashboards: DashboardStore, onDismiss: () -> Unit) {
    var name by remember(board.id) { mutableStateOf(board.name) }
    var description by remember(board.id) { mutableStateOf(board.description) }
    val s = board.settings
    fun set(change: DashboardSettings) = dashboards.updateSettings(board.id, change)
    ModalBottomSheet(onDismissRequest = { dashboards.rename(board.id, name); dashboards.setDescription(board.id, description); onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Dashboard settings", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("Description") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SwitchRow("Default dashboard", "Opens when the app starts", board.id == dashboards.defaultId) { if (it) dashboards.setDefault(board.id) }

            Text("Section layout, device names, tap action and all on/off are set on each section (pencil on the section).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            SwitchRow("Show description", "Under the dashboard name", s.showDescription) { set(s.copy(showDescription = it)) }
            SwitchRow("Summary line", "Lights on, open doors and offline devices", s.summaryLine) { set(s.copy(summaryLine = it)) }

            Text("Controls", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            SwitchRow("Brightness percentage", null, s.brightnessPercent) { set(s.copy(brightnessPercent = it)) }
            SwitchRow("Spinner until confirmed", "While a command waits for the device", s.pendingSpinner) { set(s.copy(pendingSpinner = it)) }

            Text("Status", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            SwitchRow("Dim offline devices", "With when they were last seen", s.dimOffline) { set(s.copy(dimOffline = it)) }
            SwitchRow("Errors as snackbars", "Otherwise shown under the section", s.snackbarErrors) { set(s.copy(snackbarErrors = it)) }
            SwitchRow("Pull to refresh", "Pull down to reconnect this dashboard's devices", s.pullToRefresh) { set(s.copy(pullToRefresh = it)) }
        }
    }
}
