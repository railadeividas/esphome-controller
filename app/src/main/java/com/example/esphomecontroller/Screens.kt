package com.example.esphomecontroller

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.esphomecontroller.api.*
import java.util.UUID

fun connectionSummary(session: ControlSession?, setupError: String?): String = setupError ?: when (session?.connection) {
    null, ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Failed -> "Connection unavailable · retrying"
}

private fun connectionDetail(session: ControlSession?, setupError: String?): String = setupError ?: when (val c = session?.connection) {
    null -> "Not connected"
    ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Connected -> when (c.security) { Security.ENCRYPTED -> "Connected securely"; Security.UNENCRYPTED -> "Connected without encryption"; Security.WEB -> "Connected via web server" }
    is ConnectionState.Failed -> "Unavailable: ${c.reason} · retrying"
}

private fun securityLabel(device: HomeStore.Device): String = when (device.mode) {
    "plain" -> "Unencrypted"; "encrypted" -> "Encrypted"; "web" -> "HTTP, no login"; "web_auth" -> "HTTP with login"; else -> "Not configured"
}

@Composable
private fun StatusDot(connected: Boolean) =
    Box(Modifier.size(10.dp).background(if (connected) Color(0xFF36A76B) else Color(0xFFAAA7B2), CircleShape))

/** Connection and firmware information shared by the info dialog and the device page. */
@Composable
fun DeviceInfoLines(device: HomeStore.Device, session: ControlSession?, setupError: String?) {
    Text("Address: ${device.address}")
    Text("Connection: ${connectionDetail(session, setupError)}")
    Text("Interface: ${if (device.isWeb) "Web server" else "Native API"}")
    Text("Security: ${securityLabel(device)}")
    session?.details?.let { d ->
        listOf("Model" to d.model, "Manufacturer" to d.manufacturer, "ESPHome" to d.esphomeVersion, "Project" to d.project)
            .filter { it.second.isNotBlank() }.forEach { (label, value) -> Text("$label: $value") }
    }
    session?.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
fun DashboardScreen(store: HomeStore, manager: ConnectionManager, onManageHomes: () -> Unit, onAdd: () -> Unit, onInfo: (HomeStore.Device) -> Unit, onSetup: (HomeStore.Device) -> Unit) {
    val home = store.home
    var homesExpanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { homesExpanded = true }) { Text("${home.name} ▾", style = MaterialTheme.typography.titleMedium) }
            DropdownMenu(expanded = homesExpanded, onDismissRequest = { homesExpanded = false }) {
                store.homes.forEach { h -> DropdownMenuItem(text = { Text(h.name) }, onClick = { store.select(h.id); homesExpanded = false }) }
                DropdownMenuItem(text = { Text("Manage homes") }, onClick = { homesExpanded = false; onManageHomes() })
            }
        }
        Text("${home.devices.size} devices", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add device") }
    }
    if (home.devices.isEmpty()) Text("Your home is ready. Add a device to get started.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        items(home.devices, key = { it.id }) { d ->
            val session = manager.sessions[d.id]
            val setupError = manager.setupErrors[d.id]
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(session.isConnected)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        Text(connectionSummary(session, setupError), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onInfo(d) }) { Icon(Icons.Default.Info, contentDescription = "Device information") }
                }
                val live = session?.entities.orEmpty()
                val entities = manager.entities(home.id, d.id).filter { store.showAllEntities || it.category == EntityCategory.NONE }
                entities.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { entity -> EntityTile(entity, session, live.firstOrNull { sameEntity(it, entity) }, Modifier.weight(1f).fillMaxHeight()) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (entities.isEmpty()) Text(if (session.isConnected) "Waiting for device states…" else "—", style = MaterialTheme.typography.bodySmall)
                session?.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (setupError != null) TextButton(onClick = { onSetup(d) }) { Text("Finish setup") }
            }
        }
    }
}

@Composable
fun DiscoverScreen(home: HomeStore.Home, discovered: Map<String, String>, status: String, refresh: () -> Unit, onSetup: (HomeStore.Device) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Add a device", style = MaterialTheme.typography.titleLarge); Text("Choose a device for ${home.name}", style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = refresh) { Text("Refresh") }
    }
    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val available = discovered.toList().filterNot { (name, _) -> home.devices.any { it.id == name } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        if (available.isEmpty()) item {
            Text("No new devices found yet. Discovery only works on the same network segment; use manual setup for devices on another VLAN or subnet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(available, key = { it.first }) { (name, address) ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { onSetup(HomeStore.Device(name, name, address)) }) { Text("Set up") }
                }
            }
        }
        item { OutlinedButton(onClick = { onSetup(HomeStore.Device("manual:${UUID.randomUUID()}", "", ":6053")) }) { Text("Add a device manually") } }
    }
}

@Composable
fun DeviceListScreen(store: HomeStore, manager: ConnectionManager, onOpen: (String) -> Unit) {
    val home = store.home
    Text("Devices", style = MaterialTheme.typography.titleLarge)
    Text("${home.name} · ${home.devices.size} devices", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (home.devices.isEmpty()) Text("No devices in this home yet.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        items(home.devices, key = { it.id }) { d ->
            val session = manager.sessions[d.id]
            Card(onClick = { onOpen(d.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(session.isConnected)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        Text(connectionSummary(session, manager.setupErrors[d.id]), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("View", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun DeviceDetailScreen(store: HomeStore, manager: ConnectionManager, device: HomeStore.Device, onBack: () -> Unit, onEdit: () -> Unit) {
    val session = manager.sessions[device.id]
    var showLogs by remember(device.id) { mutableStateOf(false) }
    if (showLogs) { DeviceLogs(device, session, onBack = { showLogs = false }); return }
    val live = session?.entities.orEmpty()
    val entities = manager.entities(store.home.id, device.id)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Devices") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showLogs = true }) { Text("Logs") }
                TextButton(onClick = onEdit) { Text("Edit") }
            }
            Text(device.name, style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Device information", style = MaterialTheme.typography.titleMedium)
                    DeviceInfoLines(device, session, manager.setupErrors[device.id])
                }
            }
        }
        if (entities.isEmpty()) item { Text(if (session.isConnected) "Waiting for device states…" else "—") }
        entitySection("Controls and sensors", entities.filter { it.category == EntityCategory.NONE }, session, live)
        entitySection("Configuration", entities.filter { it.category == EntityCategory.CONFIG }, session, live)
        entitySection("Diagnostic", entities.filter { it.category == EntityCategory.DIAGNOSTIC }, session, live)
    }
}

/** Live device logs for the current connection; kept in memory only, never saved or shared. */
@Composable
private fun DeviceLogs(device: HomeStore.Device, session: ControlSession?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // Subscribing is per connection, so subscribe again after a reconnect.
    LaunchedEffect(session, session.isConnected) { if (session.isConnected) session?.subscribeLogs() }
    var showStates by rememberSaveable { mutableStateOf(true) }
    val logs = session?.logs.orEmpty().let { all -> if (showStates) all else all.filterNot { it.startsWith(STATE_LOG_PREFIX) } }
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ ${device.name}") }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Device logs", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        Text("State updates", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        Switch(checked = showStates, onCheckedChange = { showStates = it })
    }
    Text("Shown while this page is open and kept only in memory. Logs can include network details; take care when sharing screenshots.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!session.isConnected) Text(connectionSummary(session, null), style = MaterialTheme.typography.bodySmall)
    else if (logs.isEmpty()) Text("Waiting for log messages…", style = MaterialTheme.typography.bodySmall)
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        items(logs.size) { i -> Text(logs[i], style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}

private fun LazyListScope.entitySection(title: String, entities: List<Entity>, session: ControlSession?, live: List<Entity>) {
    if (entities.isEmpty()) return
    item(key = "header:$title") { Text(title, style = MaterialTheme.typography.titleMedium) }
    items(entities, key = { it.stableId }) { entity -> EntityDetailRow(entity, session, live.firstOrNull { sameEntity(it, entity) }) }
}

@Composable
fun SettingsScreen(store: HomeStore) {
    var newHome by remember { mutableStateOf("") }
    Text("Homes", style = MaterialTheme.typography.titleLarge)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(store.homes, key = { it.id }) { h ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { store.select(h.id) }) { Text(if (h.id == store.selected) "✓ ${h.name}" else h.name) }
                if (store.homes.size > 1) TextButton(onClick = { store.deleteHome(h.id) }) { Text("Delete") }
            }
        }
        item {
            OutlinedTextField(newHome, { newHome = it }, label = { Text("New home name") }, singleLine = true)
            Button(enabled = newHome.isNotBlank(), onClick = { store.addHome(newHome); newHome = "" }) { Text("Create home") }
        }
        item {
            Spacer(Modifier.height(20.dp))
            Text("Dashboard", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show configuration and diagnostic entities", Modifier.weight(1f))
                Switch(checked = store.showAllEntities, onCheckedChange = store::changeShowAllEntities)
            }
            Text("They are always listed on each device's page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Spacer(Modifier.height(20.dp))
            Text("Appearance", style = MaterialTheme.typography.titleLarge)
            listOf("system" to "System default", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = store.theme == value, onClick = { store.changeTheme(value) }); Text(label) }
            }
        }
    }
}
