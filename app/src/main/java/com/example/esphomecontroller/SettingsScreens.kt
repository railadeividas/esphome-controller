package com.example.esphomecontroller

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The pages under Settings; null is the top-level list. */
enum class SettingsPage { DASHBOARDS, LOCATIONS, APPEARANCE, ABOUT }

// Fixed accent colours for the round category icons, like Android's system settings.
private val DashboardsColor = Color(0xFF3D7BF4)
private val LocationsColor = Color(0xFF2FA866)
private val AppearanceColor = Color(0xFF8B5CF6)
private val AboutColor = Color(0xFF7A7785)

@Composable
fun SettingsScreen(store: LocationStore, dashboards: DashboardStore, manager: ConnectionManager, page: SettingsPage?, onOpen: (SettingsPage?) -> Unit) {
    val back = { onOpen(null) }
    when (page) {
        null -> SettingsHome(store, dashboards, onOpen)
        SettingsPage.DASHBOARDS -> DashboardsSettings(store, dashboards, manager, back)
        SettingsPage.LOCATIONS -> LocationsSettings(store, back)
        SettingsPage.APPEARANCE -> AppearanceSettings(store, back)
        SettingsPage.ABOUT -> AboutSettings(back)
    }
}

@Composable
private fun SettingsHome(store: LocationStore, dashboards: DashboardStore, onOpen: (SettingsPage) -> Unit) {
    ScreenHeader("Settings")
    SettingsList {
        item {
            SettingsGroup {
                val default = dashboards.dashboards.firstOrNull { it.id == dashboards.defaultId }?.name
                SettingsRow(Icons.Default.Dashboard, DashboardsColor, "Dashboards", listOfNotNull(count(dashboards.dashboards.size, "dashboard"), default?.let { "default: $it" }).joinToString(" · "), onClick = { onOpen(SettingsPage.DASHBOARDS) }, chevron = true)
                SettingsDivider()
                SettingsRow(Icons.Default.Place, LocationsColor, "Locations", "${count(store.locations.size, "location")} · ${deviceCount(store.devices.size)}", onClick = { onOpen(SettingsPage.LOCATIONS) }, chevron = true)
            }
        }
        item {
            SettingsGroup {
                SettingsRow(Icons.Default.Palette, AppearanceColor, "Appearance", "${themeLabel(store.theme)} theme · navigation labels ${if (store.navLabels) "on" else "off"}", onClick = { onOpen(SettingsPage.APPEARANCE) }, chevron = true)
            }
        }
        item {
            SettingsGroup {
                SettingsRow(Icons.Default.Info, AboutColor, "About", "Version ${appVersion()}", onClick = { onOpen(SettingsPage.ABOUT) }, chevron = true)
            }
        }
    }
}

@Composable
private fun DashboardsSettings(store: LocationStore, dashboards: DashboardStore, manager: ConnectionManager, onBack: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Dashboard?>(null) }
    var deleting by remember { mutableStateOf<Dashboard?>(null) }
    ScreenHeader("Dashboards", subtitle = count(dashboards.dashboards.size, "dashboard"), navigation = HeaderNavigation.back("Back to settings", onBack))
    SettingsList {
        item { SettingsNote("The default dashboard opens on the Dashboard tab. Switch dashboards from its title.") }
        item {
            SettingsGroup {
                dashboards.dashboards.forEachIndexed { i, d ->
                    if (i > 0) SettingsDivider()
                    val isDefault = d.id == dashboards.defaultId
                    SettingsRow(
                        if (isDefault) Icons.Default.Star else Icons.Outlined.StarOutline, DashboardsColor, d.name,
                        listOfNotNull("Default".takeIf { isDefault }, count(d.sections.size, "section")).joinToString(" · "),
                        onClick = { renaming = d },
                    ) {
                        RowMenu { close ->
                            if (!isDefault) DropdownMenuItem(text = { Text("Make default") }, leadingIcon = { Icon(Icons.Default.Star, null) }, onClick = { close(); dashboards.setDefault(d.id) })
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { close(); renaming = d })
                            if (i > 0) DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) }, onClick = { close(); dashboards.moveDashboard(d.id, -1) })
                            if (i < dashboards.dashboards.lastIndex) DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) }, onClick = { close(); dashboards.moveDashboard(d.id, 1) })
                            if (dashboards.dashboards.size > 1) DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { close(); deleting = d })
                        }
                    }
                }
            }
        }
        item { SettingsGroup { SettingsRow(Icons.Default.Add, DashboardsColor, "New dashboard", "Start empty or from a template", onClick = { creating = true }) } }
    }
    if (creating) NewDashboardDialog(store, onDismiss = { creating = false }) { name, template ->
        creating = false; dashboards.create(name, template, store, { manager.entities(it) }, { manager.details(it)?.area })
    }
    renaming?.let { d -> RenameDialog("Rename dashboard", d.name, onDismiss = { renaming = null }) { dashboards.rename(d.id, it); renaming = null } }
    deleting?.let { d ->
        ConfirmDialog("Delete dashboard?", "\"${d.name}\" and its sections will be deleted. Devices are not affected.", "Delete", onDismiss = { deleting = null }) { dashboards.delete(d.id); deleting = null }
    }
}

@Composable
private fun LocationsSettings(store: LocationStore, onBack: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LocationStore.Location?>(null) }
    var deleting by remember { mutableStateOf<LocationStore.Location?>(null) }
    ScreenHeader("Locations", subtitle = count(store.locations.size, "location"), navigation = HeaderNavigation.back("Back to settings", onBack))
    SettingsList {
        item { SettingsNote("Locations organise devices. New devices are added to the checked location; tap a location to check it.") }
        item {
            SettingsGroup {
                store.locations.forEachIndexed { i, l ->
                    if (i > 0) SettingsDivider()
                    val selected = l.id == store.selected
                    SettingsRow(
                        if (selected) Icons.Default.CheckCircle else Icons.Default.Place, LocationsColor, l.name,
                        listOfNotNull(deviceCount(l.devices.size), "new devices go here".takeIf { selected }).joinToString(" · "),
                        onClick = { store.select(l.id) },
                    ) {
                        RowMenu { close ->
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { close(); renaming = l })
                            if (store.locations.size > 1) DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { close(); deleting = l })
                        }
                    }
                }
            }
        }
        item { SettingsGroup { SettingsRow(Icons.Default.Add, LocationsColor, "New location", null, onClick = { creating = true }) } }
    }
    if (creating) RenameDialog("New location", "", confirm = "Create", onDismiss = { creating = false }) { store.addLocation(it); creating = false }
    renaming?.let { l -> RenameDialog("Rename location", l.name, onDismiss = { renaming = null }) { store.renameLocation(l.id, it); renaming = null } }
    deleting?.let { l ->
        ConfirmDialog("Delete location?", "\"${l.name}\" will be deleted. Its ${deviceCount(l.devices.size)} stay in the app without a location.", "Delete", onDismiss = { deleting = null }) { store.deleteLocation(l.id); deleting = null }
    }
}

@Composable
private fun AppearanceSettings(store: LocationStore, onBack: () -> Unit) {
    ScreenHeader("Appearance", navigation = HeaderNavigation.back("Back to settings", onBack))
    SettingsList {
        item { PageSectionTitle("Theme", Modifier.padding(start = 4.dp)) }
        item {
            SettingsGroup {
                LocationStore.THEMES.forEachIndexed { i, value ->
                    if (i > 0) SettingsDivider()
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = store.theme == value, role = Role.RadioButton, onClick = { store.changeTheme(value) }).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (value == "system") "System default" else themeLabel(value), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        RadioButton(selected = store.theme == value, onClick = null)
                    }
                }
            }
        }
        item { PageSectionTitle("Navigation", Modifier.padding(start = 4.dp)) }
        item {
            SettingsGroup {
                SettingsRow(Icons.Default.Title, AppearanceColor, "Navigation labels", "Show text under the bottom navigation icons", onClick = { store.changeNavLabels(!store.navLabels) }) {
                    Switch(checked = store.navLabels, onCheckedChange = { store.changeNavLabels(it) })
                }
            }
        }
    }
}

@Composable
private fun AboutSettings(onBack: () -> Unit) {
    ScreenHeader("About", navigation = HeaderNavigation.back("Back to settings", onBack))
    SettingsList {
        item {
            SettingsGroup {
                SettingsRow(Icons.Default.Info, AboutColor, "Version", appVersion())
                SettingsDivider()
                SettingsRow(Icons.Default.Lock, AboutColor, "Encrypted connections", if (Build.VERSION.SDK_INT >= 33) "Supported on this device" else "Need Android 13 or newer")
            }
        }
        item {
            SettingsNote("ESPHome Controller talks directly to your ESPHome devices on the local network, through the Native API or the web server. No cloud account, Home Assistant or other server is used. Encryption uses the Noise protocol (torann library, which has not been independently audited).")
        }
    }
}

// Building blocks

@Composable
private fun SettingsList(content: LazyListScope.() -> Unit) =
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp), content = content)

/** A rounded card holding related rows, separated by inset dividers. */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) =
    Card(Modifier.fillMaxWidth(), shape = AppCardShape, colors = appCardColors(), elevation = appCardElevation()) { Column(content = content) }

/** Divider starting under the row text, past the icon. */
@Composable
private fun SettingsDivider() = HorizontalDivider(Modifier.padding(start = 70.dp, end = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)

@Composable
private fun SettingsRow(
    icon: ImageVector, color: Color, title: String, summary: String?,
    onClick: (() -> Unit)? = null, chevron: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
    }
}

@Composable
private fun RowMenu(items: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

@Composable
private fun SettingsNote(text: String) =
    Text(text, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun RenameDialog(title: String, initial: String, confirm: String = "Save", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(text) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
)

private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
private fun themeLabel(theme: String) = when (theme) { "light" -> "Light"; "dark" -> "Dark"; else -> "System" }

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown" }
}
