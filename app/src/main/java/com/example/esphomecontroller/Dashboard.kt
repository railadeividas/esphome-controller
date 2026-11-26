package com.example.esphomecontroller

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.example.esphomecontroller.api.*
import kotlinx.coroutines.delay

/** One entity on the dashboard together with the device it belongs to. */
private data class Item(val device: HomeStore.Device, val entity: Entity) {
    val key get() = HomeStore.entityKey(device.id, entity.stableId)
}

/**
 * The Home tab: optional Favourites, then one collapsible section per device in the home's order.
 * Controls are tiles; read-only values are listed compactly. Long-press or tap a name for all controls.
 */
@Composable
fun DashboardScreen(store: HomeStore, manager: ConnectionManager, onManageHomes: () -> Unit, onAdd: () -> Unit, onInfo: (HomeStore.Device) -> Unit, onSetup: (HomeStore.Device) -> Unit) {
    val home = store.home
    val layout = home.layout
    var homesExpanded by remember { mutableStateOf(false) }
    var editing by remember(home.id) { mutableStateOf(false) }
    var sheet by remember(home.id) { mutableStateOf<Pair<String, String>?>(null) }
    // Ticks so that "last seen" times stay current.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    fun entitiesOf(d: HomeStore.Device) = manager.entities(d.id)
        .filter { store.showAllEntities || it.category == EntityCategory.NONE }
        .map { Item(d, it) }
        .filter { editing || it.key !in layout.hidden }
    fun live(item: Item) = manager.sessions[item.device.id]?.entities?.firstOrNull { sameEntity(it, item.entity) }
    val open: (Item) -> Unit = { sheet = it.device.id to it.entity.stableId }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { homesExpanded = true }) { Text("${home.name} ▾", style = MaterialTheme.typography.titleMedium) }
            DropdownMenu(expanded = homesExpanded, onDismissRequest = { homesExpanded = false }) {
                store.homes.forEach { h -> DropdownMenuItem(text = { Text(h.name) }, onClick = { store.select(h.id); homesExpanded = false }) }
                DropdownMenuItem(text = { Text("Manage homes") }, onClick = { homesExpanded = false; onManageHomes() })
            }
        }
        if (!editing) {
            Text("${home.devices.size} devices", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (home.devices.isNotEmpty()) IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, contentDescription = "Edit dashboard") }
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add device") }
        } else Button(onClick = { editing = false }) { Text("Done") }
    }
    if (editing) Text("Reorder sections with the arrows, ★ pins to Favourites, the eye hides an entity from Home.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (home.devices.isEmpty()) Text("Your home is ready. Add a device to get started.")

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        // Favourites: pinned entities from any device, labelled with their device.
        val favourites = layout.pinned.mapNotNull { key ->
            home.devices.firstNotNullOfOrNull { d -> entitiesOf(d).firstOrNull { it.key == key } }
        }
        if (favourites.isNotEmpty()) item(key = HomeStore.FAVOURITES) {
            Section(
                title = "Favourites", subtitle = null, dot = null, collapsed = HomeStore.FAVOURITES in layout.collapsed,
                onToggleCollapse = { store.toggleCollapsed(HomeStore.FAVOURITES) },
            ) { EntityBlock(favourites, store, manager, ::live, editing, open, showDevice = true) }
        }
        items(home.devices, key = { it.id }) { d ->
            val session = manager.sessions[d.id]
            val setupError = manager.setupErrors[d.id]
            val offline = session.isOffline && setupError == null
            val items = entitiesOf(d)
            val index = home.devices.indexOf(d)
            Section(
                title = d.name,
                subtitle = connectionSummary(session, setupError, manager.lastSeen(d.id), now),
                dot = session.isConnected,
                collapsed = d.id in layout.collapsed,
                onToggleCollapse = { store.toggleCollapsed(d.id) },
                actions = {
                    if (editing) {
                        IconButton(enabled = index > 0, onClick = { store.moveDevice(d.id, -1) }) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up") }
                        IconButton(enabled = index < home.devices.lastIndex, onClick = { store.moveDevice(d.id, 1) }) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down") }
                    } else IconButton(onClick = { onInfo(d) }) { Icon(Icons.Default.Info, contentDescription = "Device information") }
                },
            ) {
                // Offline devices keep their last values visible, dimmed, with controls disabled.
                Column(Modifier.alpha(if (offline) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    EntityBlock(items, store, manager, ::live, editing, open, showDevice = false)
                    if (items.isEmpty()) Text(if (session.isConnected) "Waiting for device states…" else "—", style = MaterialTheme.typography.bodySmall)
                }
                if (setupError != null) TextButton(onClick = { onSetup(d) }) { Text("Fix connection settings") }
            }
        }
    }

    sheet?.let { (deviceId, stableId) ->
        val device = home.devices.firstOrNull { it.id == deviceId }
        val entity = device?.let { d -> manager.entities(d.id).firstOrNull { it.stableId == stableId } }
        if (device != null && entity != null) EntitySheet(Item(device, entity), manager.sessions[device.id], live(Item(device, entity))) { sheet = null }
    }
}

/** A dashboard section with a collapsible header. */
@Composable
private fun Section(
    title: String, subtitle: String?, dot: Boolean?, collapsed: Boolean, onToggleCollapse: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) { StatusDot(dot); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            actions()
            IconButton(onClick = onToggleCollapse) {
                Icon(Icons.Default.ExpandMore, contentDescription = if (collapsed) "Expand" else "Collapse", modifier = Modifier.rotate(if (collapsed) -90f else 0f))
            }
        }
        if (!collapsed) content()
    }
}

/** Control tiles in two columns, then read-only values in one compact list card. */
@Composable
private fun EntityBlock(items: List<Item>, store: HomeStore, manager: ConnectionManager, live: (Item) -> Entity?, editing: Boolean, open: (Item) -> Unit, showDevice: Boolean) {
    val layout = store.home.layout
    val controls = items.filterNot { it.entity.platform.isReadOnly }
    val readOnly = items.filter { it.entity.platform.isReadOnly }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        controls.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { item ->
                    val hidden = item.key in layout.hidden
                    EntityTile(
                        item.entity, manager.sessions[item.device.id], live(item),
                        Modifier.weight(1f).fillMaxHeight().alpha(if (hidden) 0.4f else 1f),
                        deviceLabel = if (showDevice) item.device.name else null,
                        onOpen = if (editing) null else { { open(item) } },
                        overlay = if (!editing) null else { { EditTools(item, store, Modifier.align(Alignment.TopEnd)) } },
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (readOnly.isNotEmpty()) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                readOnly.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Box(Modifier.alpha(if (item.key in layout.hidden) 0.4f else 1f)) {
                        ReadOnlyRow(
                            item.entity, deviceLabel = if (showDevice) item.device.name else null,
                            onOpen = if (editing) null else { { open(item) } },
                            trailing = if (!editing) null else { { EditTools(item, store) } },
                        )
                    }
                }
            }
        }
    }
}

/** Pin and hide buttons shown in edit mode. */
@Composable
private fun EditTools(item: Item, store: HomeStore, modifier: Modifier = Modifier) {
    val layout = store.home.layout
    val pinned = item.key in layout.pinned
    val hidden = item.key in layout.hidden
    Row(modifier) {
        IconButton(onClick = { store.togglePinned(item.key) }) {
            Icon(if (pinned) Icons.Default.Star else Icons.Outlined.StarOutline, contentDescription = if (pinned) "Unpin" else "Pin to Favourites", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = { store.toggleHidden(item.key) }) {
            Icon(if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (hidden) "Show on Home" else "Hide from Home")
        }
    }
}

/** Full controls for one entity without leaving Home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntitySheet(item: Item, session: ControlSession?, live: Entity?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.device.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EntityControls(item.entity, session, live)
            if (!session.isConnected) Text("Controls are available when the device is connected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
