package com.example.esphomecontroller

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Memory
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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

fun connectionSummary(session: ControlSession?, setupError: String?, lastSeen: Long? = null, now: Long = System.currentTimeMillis()): String = setupError ?: when (session?.connection) {
    null, ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Failed -> "Offline" + (lastSeen?.let { " · last seen ${relativeTime(it, now)}" } ?: "")
}

/** "Home · Area", leaving out whichever is unknown; null when neither is. */
fun placeLine(homeName: String?, area: String?): String? =
    listOfNotNull(homeName?.takeIf { it.isNotBlank() }, area?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null }

fun deviceCount(n: Int) = if (n == 1) "1 device" else "$n devices"

/** "just now", "5 min ago", "3 h ago", "2 d ago". */
fun relativeTime(then: Long, now: Long): String {
    val seconds = ((now - then) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}

/** A device whose connection dropped and is being retried; setup problems are shown separately. */
val ControlSession?.isOffline: Boolean get() = (this?.connection as? ConnectionState.Failed)?.needsSetup == false

private fun connectionDetail(session: ControlSession?, setupError: String?, lastSeen: Long? = null): String = setupError ?: when (val c = session?.connection) {
    null -> "Not connected"
    ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Connected -> when (c.security) { Security.ENCRYPTED -> "Connected securely"; Security.UNENCRYPTED -> "Connected without encryption"; Security.WEB -> "Connected via web server" }
    is ConnectionState.Failed -> "Offline" + (lastSeen?.let { " · last seen ${relativeTime(it, System.currentTimeMillis())}" } ?: "") + " · ${c.reason}"
}

private fun securityLabel(device: HomeStore.Device): String = when (device.mode) {
    "plain" -> "Unencrypted"; "encrypted" -> "Encrypted"; "web" -> "HTTP, no login"; "web_auth" -> "HTTP with login"; else -> "Not configured"
}

@Composable
fun StatusDot(connected: Boolean) =
    Box(Modifier.size(10.dp).background(if (connected) Color(0xFF36A76B) else Color(0xFFAAA7B2), CircleShape))

/** Connection and firmware information shared by the info dialog and the device page. */
@Composable
fun DeviceInfoLines(device: HomeStore.Device, session: ControlSession?, setupError: String?, details: DeviceDetails? = session?.details) {
    Text("Address: ${device.address}")
    Text("Connection: ${connectionDetail(session, setupError)}")
    Text("Interface: ${if (device.isWeb) "Web server" else "Native API"}")
    Text("Security: ${securityLabel(device)}")
    details?.let { d ->
        listOf("Area" to d.area, "Model" to d.model, "Manufacturer" to d.manufacturer, "ESPHome" to d.esphomeVersion, "Project" to d.project)
            .filter { it.second.isNotBlank() }.forEach { (label, value) -> Text("$label: $value") }
    }
    session?.lastError?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
}

@Composable
fun DiscoverScreen(store: HomeStore, discovered: Map<String, DiscoveredDevice>, scan: ScanControls, onSetup: (HomeStore.Device) -> Unit) {
    val home = store.home
    val available = discovered.values.filterNot { d -> store.contains(d.serviceName) }.sortedBy { it.displayName.lowercase() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Add a device", style = MaterialTheme.typography.titleLarge); Text("New devices are added to ${home.name}; you can change this during setup.", style = MaterialTheme.typography.bodySmall) }
        if (scan.scanning) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            TextButton(onClick = scan.stop) { Text("Stop") }
        } else TextButton(onClick = scan.start) { Text("Refresh") }
    }
    // The spinner and the list show progress and results; only explain failures.
    if (!scan.scanning) scan.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        if (available.isEmpty() && !scan.scanning && !scan.finished) item {
            Text("Tap Refresh to search for devices on this network.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (available.isEmpty() && !scan.scanning && scan.finished) item {
            Card(Modifier.fillMaxWidth(), shape = AppCardShape, colors = appCardColors(), elevation = appCardElevation()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("No new devices found", style = MaterialTheme.typography.titleSmall)
                    Text("• The phone must be on the same Wi-Fi network as the device.", style = MaterialTheme.typography.bodySmall)
                    Text("• Discovery (mDNS) does not cross VLANs or subnets unless the network repeats it.", style = MaterialTheme.typography.bodySmall)
                    Text("• The device needs the api: component; devices already added to the app are hidden.", style = MaterialTheme.typography.bodySmall)
                    Text("You can still add a device by its address.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(available, key = { it.serviceName }) { d ->
            Card(onClick = { onSetup(HomeStore.Device(d.serviceName, d.displayName, d.address)) }, modifier = Modifier.fillMaxWidth(), shape = AppCardShape, colors = appCardColors(), elevation = appCardElevation()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(d.displayName, style = MaterialTheme.typography.titleMedium)
                        if (d.summary.isNotBlank()) Text(d.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(encryptionText(d.encryption), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    EncryptionIcon(d.encryption)
                    Spacer(Modifier.width(12.dp))
                    Text("Set up", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { OutlinedButton(onClick = { onSetup(HomeStore.Device("manual:${UUID.randomUUID()}", "", "")) }) { Text("Add a device by address") } }
    }
}

/** A device's icon: a bulb when it has lights, otherwise a generic board. */
fun deviceIcon(entities: List<Entity>): ImageVector = if (entities.any { it.platform is Platform.Light }) Icons.Default.Lightbulb else Icons.Default.Memory

/** Rounded device icon in a primary-tinted square, with a connection badge on its corner. */
@Composable
private fun DeviceAvatar(icon: ImageVector, connected: Boolean, size: Dp = 42.dp) {
    Box(Modifier.size(size)) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(size * 0.3f)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.56f))
        }
        Box(Modifier.align(Alignment.BottomEnd).offset(2.dp, 2.dp).size(13.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp)
            .background(if (connected) Color(0xFF36A76B) else Color(0xFFAAA7B2), CircleShape))
    }
}

@Composable
fun DeviceListScreen(store: HomeStore, manager: ConnectionManager, onOpen: (String) -> Unit) {
    val home = store.home
    Text("Devices", style = MaterialTheme.typography.titleLarge)
    Text(deviceCount(store.devices.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (store.devices.isEmpty()) Text("No devices yet. Add one from Discover.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        items(store.devices, key = { it.id }) { d ->
            val session = manager.sessions[d.id]
            Card(onClick = { onOpen(d.id) }, modifier = Modifier.fillMaxWidth(), shape = AppCardShape, colors = appCardColors(), elevation = appCardElevation()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    DeviceAvatar(deviceIcon(manager.entities(d.id)), session.isConnected)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        placeLine(store.homeName(d.homeId), manager.details(d.id)?.area)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(connectionSummary(session, manager.setupErrors[d.id], manager.lastSeen(d.id)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun DeviceDetailScreen(store: HomeStore, manager: ConnectionManager, device: HomeStore.Device, onBack: () -> Unit, onEdit: () -> Unit) {
    val session = manager.sessions[device.id]
    var showLogs by remember(device.id) { mutableStateOf(false) }
    var infoOpen by rememberSaveable(device.id) { mutableStateOf(false) }
    if (showLogs) { DeviceLogs(device, session, onBack = { showLogs = false }); return }
    val live = session?.entities.orEmpty()
    val entities = manager.entities(device.id)
    val details = manager.details(device.id)
    val setupError = manager.setupErrors[device.id]
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to devices") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showLogs = true }) { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "Device logs") }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit device") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = AppCardShape, colors = appCardColors(), elevation = appCardElevation()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    DeviceAvatar(deviceIcon(entities), session.isConnected, 54.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleLarge)
                        placeLine(store.homeName(device.homeId), details?.area)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(connectionSummary(session, setupError, manager.lastSeen(device.id)), style = MaterialTheme.typography.bodyMedium,
                            color = if (setupError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = AppCardShape, colors = appCardColors(), elevation = appCardElevation()) {
                Row(Modifier.fillMaxWidth().clickable { infoOpen = !infoOpen }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Device information", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ExpandMore, contentDescription = if (infoOpen) "Hide" else "Show", modifier = Modifier.rotate(if (infoOpen) 180f else 0f))
                }
                if (infoOpen) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    deviceFacts(device, details).forEach { (label, value) ->
                        Row {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
                            Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(0.6f))
                        }
                    }
                }
            }
        }
        if (entities.isEmpty()) item { Text(if (session.isConnected) "Waiting for device states…" else "—") }
        entitySection("Controls and sensors", entities.filter { it.category == EntityCategory.NONE }, session, live)
        entitySection("Configuration", entities.filter { it.category == EntityCategory.CONFIG }, session, live)
        entitySection("Diagnostic", entities.filter { it.category == EntityCategory.DIAGNOSTIC }, session, live)
    }
}

/** Static device information: connection settings and what the firmware reports, no live status. */
private fun deviceFacts(device: HomeStore.Device, details: DeviceDetails?): List<Pair<String, String>> =
    listOf(
        "Address" to device.address,
        "Interface" to if (device.isWeb) "Web server" else "Native API",
        "Security" to securityLabel(device),
    ) + listOf("Area" to details?.area, "Model" to details?.model, "Manufacturer" to details?.manufacturer, "ESPHome" to details?.esphomeVersion, "Project" to details?.project)
        .mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }

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
    item(key = "header:$title") {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
    }
    // One card per section, rows separated by dividers.
    item(key = "group:$title") {
        Card(Modifier.fillMaxWidth(), shape = AppCardShape, colors = appCardColors(), elevation = appCardElevation()) {
            entities.forEachIndexed { i, entity ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                EntityControls(entity, session, live.firstOrNull { sameEntity(it, entity) }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), leadingIcon = true)
            }
        }
    }
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
