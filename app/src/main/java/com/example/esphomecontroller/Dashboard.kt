package com.example.esphomecontroller

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.example.esphomecontroller.api.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A dashboard item resolved against the app's devices and the live/cached entities. */
private data class Resolved(val ref: ItemRef, val device: LocationStore.Device?, val entity: Entity?, val session: ControlSession?, val live: Entity?) {
    val available get() = device != null && entity != null
}

/**
 * The Dashboard tab: user-created dashboards made of sections (tiles, list, device, heading). Every
 * presentation feature comes from the dashboard's own [DashboardSettings]; editing happens in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(store: LocationStore, dashboards: DashboardStore, manager: ConnectionManager, onManageDashboards: () -> Unit) {
    val board = dashboards.selected
    val settings = board.settings
    var menuOpen by remember { mutableStateOf(false) }
    var editing by remember(board.id) { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var addingSection by remember(board.id) { mutableStateOf(false) }
    var editingSection by remember(board.id) { mutableStateOf<Section?>(null) }
    var picking by remember(board.id) { mutableStateOf<Section?>(null) }
    var sheet by remember(board.id) { mutableStateOf<ItemRef?>(null) }
    // Sections waiting for a device's entities (device templates, old Device sections) fill as soon as they are known.
    val knownEntities = store.devices.map { it.id to manager.entities(it.id).size }
    LaunchedEffect(knownEntities) { dashboards.fillPending({ id -> store.devices.any { it.id == id } }, { manager.entities(it) }) }
    val autoColumns = if (LocalConfiguration.current.screenWidthDp >= 600) 4 else 2

    fun resolve(ref: ItemRef): Resolved {
        val device = store.devices.firstOrNull { it.id == ref.deviceId }
        val session = manager.sessions[ref.deviceId]
        val entity = device?.let { manager.entities(it.id).firstOrNull { e -> e.stableId == ref.stableId } }
        return Resolved(ref, device, entity, session, entity?.let { e -> session?.entities?.firstOrNull { sameEntity(it, e) } })
    }
    val open: (ItemRef) -> Unit = { sheet = it }

    CompositionLocalProvider(LocalEntityStyle provides EntityStyle(settings.brightnessPercent, tapToToggle = false, pendingSpinner = settings.pendingSpinner, readOnly = editing)) {
        // Top bar: dashboard switcher, edit and settings.
        ScreenHeader(
            title = board.name,
            subtitle = if (settings.summaryLine && !editing) summaryText(board, store, manager) else null,
            onTitleClick = { menuOpen = true },
            titleMenu = {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    dashboards.dashboards.forEach { d ->
                        DropdownMenuItem(
                            text = { Text(d.name) },
                            leadingIcon = { if (d.id == dashboards.defaultId) Icon(Icons.Default.Star, contentDescription = "Default") },
                            onClick = { dashboards.select(d.id); menuOpen = false },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("New dashboard") }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { menuOpen = false; creating = true })
                    DropdownMenuItem(text = { Text("Manage dashboards") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { menuOpen = false; onManageDashboards() })
                }
            },
        ) {
            if (editing) {
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Tune, contentDescription = "Dashboard settings") }
                TextButton(onClick = { editing = false }) { Text("Done") }
            } else IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, contentDescription = "Edit dashboard") }
        }

        val list: @Composable () -> Unit = {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                if (board.sections.isEmpty()) item {
                    Text(if (editing) "Add a section to start building this dashboard." else "This dashboard is empty. Tap the pencil to add sections.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(board.sections, key = { it.id }) { section ->
                    SectionView(
                        section, board, store, dashboards, manager, settings, section.columns.takeIf { it > 0 } ?: autoColumns, editing, ::resolve, open,
                        onEdit = { editingSection = section }, onPick = { picking = section },
                    )
                }
                if (editing) item {
                    OutlinedButton(onClick = { addingSection = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add section") }
                }
            }
        }
        if (settings.pullToRefresh && !editing) {
            val scope = rememberCoroutineScope()
            var refreshing by remember { mutableStateOf(false) }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = {
                refreshing = true; manager.reconnectNow(board.deviceIds)
                scope.launch { delay(1200); refreshing = false }
            }) { list() }
        } else list()

        sheet?.let { ref ->
            val r = resolve(ref)
            if (r.available) EntitySheet(r.device!!, r.entity!!, r.session, r.live) { sheet = null }
        }
    }

    if (creating) NewDashboardDialog(store, onDismiss = { creating = false }) { name, template ->
        creating = false
        dashboards.create(name, template, store, { manager.entities(it) }, { manager.details(it)?.area })
        editing = template == DashboardTemplate.Empty
    }
    if (showSettings) DashboardSettingsSheet(board, dashboards, onDismiss = { showSettings = false })
    if (addingSection) SectionDialog(null, store, onDismiss = { addingSection = false }) { section ->
        addingSection = false
        dashboards.addSection(board.id, section)
        if (section.hasChosenItems) picking = section
    }
    editingSection?.let { s -> SectionDialog(s, store, onDismiss = { editingSection = null }) { dashboards.updateSection(board.id, it); editingSection = null } }
    picking?.let { s ->
        val current = board.sections.firstOrNull { it.id == s.id } ?: s
        EntityPicker(store, manager, current.items, onDismiss = { picking = null }) { refs -> dashboards.setItems(board.id, s.id, refs); picking = null }
    }
}

fun TitleSize.iconSize() = when (this) { TitleSize.H1 -> 32.dp; TitleSize.H2 -> 28.dp; TitleSize.H3 -> 24.dp; TitleSize.H4 -> 20.dp }

@Composable
fun TitleSize.textStyle() = when (this) {
    TitleSize.H1 -> MaterialTheme.typography.headlineMedium
    TitleSize.H2 -> MaterialTheme.typography.headlineSmall
    TitleSize.H3 -> MaterialTheme.typography.titleLarge
    TitleSize.H4 -> MaterialTheme.typography.titleMedium
}

/** Lights on, open doors and offline devices among this dashboard's devices. */
private fun summaryText(board: Dashboard, store: LocationStore, manager: ConnectionManager): String {
    val ids = board.deviceIds.filter { id -> store.devices.any { it.id == id } }
    val entities = ids.flatMap { manager.entities(it) }
    val lights = entities.count { it.platform is Platform.Light && isOn(it.state) == true }
    val open = entities.count { it.platform == Platform.BinarySensor && it.deviceClass in setOf("door", "window", "garage_door", "opening") && isOn(it.state) == true }
    val offline = ids.count { manager.sessions[it].isOffline }
    val parts = listOfNotNull("$lights ${if (lights == 1) "light" else "lights"} on", if (open > 0) "$open open" else null, if (offline > 0) "$offline offline" else null)
    return parts.joinToString(" · ")
}

@Composable
private fun SectionView(
    section: Section, board: Dashboard, store: LocationStore, dashboards: DashboardStore, manager: ConnectionManager, settings: DashboardSettings,
    columns: Int, editing: Boolean, resolve: (ItemRef) -> Resolved, open: (ItemRef) -> Unit, onEdit: () -> Unit, onPick: () -> Unit,
) {
    val pendingDevice = section.pendingDeviceId?.let { id -> store.devices.firstOrNull { it.id == id } }
    val collapsed = section.collapsible && section.collapsed && !editing
    val onOpen: ((ItemRef) -> Unit)? = if (editing || section.tapAction == TapAction.NONE) null else open
    val items: List<Resolved> = when (section.type) {
        SectionType.TILES, SectionType.LIST, SectionType.COMPACT -> section.items.map(resolve)
        SectionType.HEADING -> emptyList()
    }
    // Lights, switches and fans that can be commanded right now, for the all on / all off button.
    val toggles = items.filter { r -> r.live != null && r.entity!!.platform.let { it is Platform.Light || it == Platform.Switch || it is Platform.Fan } && isCommandReady(r.session.isConnected, r.live) }
    val index = board.sections.indexOf(section)
    val tools: @Composable (Resolved) -> Unit = { r ->
        ItemTools(
            onUp = { dashboards.moveItem(board.id, section.id, r.ref, -1) },
            onDown = { dashboards.moveItem(board.id, section.id, r.ref, 1) },
            onRemove = { dashboards.removeItem(board.id, section.id, r.ref) },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val title = section.title
                val icon = sectionIcon(section.icon)
                if (title.isNotBlank() || icon != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    val color = section.titleColorValue()
                    icon?.let { MdiGlyph(it, section.titleSizeOrDefault.iconSize(), Modifier.padding(end = 8.dp), tint = color) }
                    if (title.isNotBlank()) Text(title, style = section.titleSizeOrDefault.textStyle(), color = color)
                }
                if (section.text.isNotBlank()) Text(section.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (section.type) {
                    SectionType.HEADING -> Unit
                    else -> if (editing) Text("${section.type.label} · ${section.items.size} entities", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (editing) {
                IconButton(enabled = index > 0, onClick = { dashboards.moveSection(board.id, section.id, -1) }) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                IconButton(enabled = index < board.sections.lastIndex, onClick = { dashboards.moveSection(board.id, section.id, 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit section") }
                IconButton(onClick = { dashboards.deleteSection(board.id, section.id) }) { Icon(Icons.Default.Delete, "Delete section") }
            } else {
                if (section.allOnOff && toggles.isNotEmpty() && (!collapsed || section.allOnOffWhenCollapsed)) {
                    val anyOn = toggles.any { isOn(it.live!!.state) == true }
                    IconButton(onClick = { toggles.forEach { r -> r.session?.send(r.live!!, Command.SetOn(!anyOn)) } }) {
                        Icon(Icons.Default.PowerSettingsNew, if (anyOn) "Turn all off" else "Turn all on", tint = if (anyOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (section.collapsible && section.hasEntities) IconButton(onClick = { dashboards.toggleCollapsed(board.id, section.id) }) {
                    Icon(Icons.Default.ExpandMore, if (collapsed) "Expand" else "Collapse", Modifier.rotate(if (collapsed) -90f else 0f))
                }
            }
        }
        if (!collapsed) CompositionLocalProvider(LocalEntityStyle provides LocalEntityStyle.current.copy(tapToToggle = section.tapAction == TapAction.TOGGLE && !editing)) {
            when (section.type) {
                SectionType.HEADING -> Unit
                SectionType.TILES, SectionType.LIST, SectionType.COMPACT -> {
                    if (items.isEmpty()) Text(when {
                        pendingDevice != null -> "Waiting for ${pendingDevice.name} to report its entities…"
                        editing -> "No entities yet."
                        else -> "—"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (section.type != SectionType.LIST) TilesAndReadings(items, settings, columns, section.readingsAsList, editing, section.showDeviceNames, onOpen, tools, compact = section.type == SectionType.COMPACT)
                    else ListCard(items, settings, editing, section.showDeviceNames, onOpen, tools)
                    if (editing) TextButton(onClick = onPick) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add or remove entities") }
                }
            }
        }
        // Without snackbars, command failures are shown under the section they happened in.
        if (!settings.snackbarErrors) {
            val ids = section.items.map { it.deviceId }.distinct()
            ids.mapNotNull { manager.sessions[it]?.lastError?.message }.distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Controls as tiles in [columns]; read-only values as a list card (or as tiles, per settings). */
@Composable
private fun TilesAndReadings(items: List<Resolved>, settings: DashboardSettings, columns: Int, asList: Boolean, editing: Boolean, showDevice: Boolean, open: ((ItemRef) -> Unit)?, itemTools: (@Composable (Resolved) -> Unit)?, compact: Boolean = false) {
    val tiles = items.filter { !asList || it.entity == null || !it.entity.platform.isReadOnly }
    val readings = if (asList) items.filter { it.entity?.platform?.isReadOnly == true } else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { r ->
                    val m = Modifier.weight(1f).fillMaxHeight().alpha(if (settings.dimOffline && r.session.isOffline) 0.5f else 1f)
                    val toolsFor: (@Composable () -> Unit)? = if (editing && itemTools != null) { { itemTools(r) } } else null
                    if (!r.available) UnavailableTile(r, m, toolsFor, compact)
                    else if (compact) CompactTile(r.entity!!, r.session, r.live, m,
                        deviceLabel = if (showDevice) r.device!!.name else null,
                        onOpen = open?.let { o -> { o(r.ref) } },
                        overlay = toolsFor?.let { t -> { Box(Modifier.align(Alignment.CenterEnd)) { t() } } })
                    else EntityTile(r.entity!!, r.session, r.live, m,
                        deviceLabel = if (showDevice) r.device!!.name else null,
                        onOpen = open?.let { o -> { o(r.ref) } },
                        overlay = toolsFor?.let { t -> { Box(Modifier.align(Alignment.TopEnd)) { t() } } })
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (readings.isNotEmpty()) ListCard(readings, settings, editing, showDevice, open, itemTools)
    }
}

/** One card of compact rows: read-only values or the main control on the right (a plain row while editing). */
@Composable
private fun ListCard(items: List<Resolved>, settings: DashboardSettings, editing: Boolean, showDevice: Boolean, open: ((ItemRef) -> Unit)?, itemTools: (@Composable (Resolved) -> Unit)?) {
    if (items.isEmpty()) return
    Card(Modifier.fillMaxWidth(), shape = AppCardShape, colors = appCardColors(), elevation = appCardElevation()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
            items.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Box(Modifier.alpha(if (settings.dimOffline && r.session.isOffline) 0.5f else 1f)) {
                    val label = if (showDevice) r.device?.name else null
                    val entity = r.entity
                    when {
                        entity == null || r.device == null -> Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (r.device == null) "Device removed" else "Entity not available yet", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (editing) itemTools?.invoke(r)
                        }
                        entity.platform.isReadOnly || editing -> ReadOnlyRow(
                            entity, deviceLabel = label,
                            onOpen = open?.let { o -> { o(r.ref) } },
                            trailing = if (editing && itemTools != null) { { itemTools(r) } } else null,
                        )
                        else -> CompactControlRow(
                            entity, r.session, r.live, deviceLabel = label,
                            onOpen = open?.let { o -> { o(r.ref) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnavailableTile(r: Resolved, modifier: Modifier, tools: (@Composable () -> Unit)?, compact: Boolean = false) {
    Card(modifier.heightIn(min = if (compact) 64.dp else 142.dp), shape = AppCardShape, colors = appCardColors(), elevation = appCardElevation()) {
        Box(Modifier.fillMaxSize().padding(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (r.device == null) "Device removed" else "Entity not available yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tools != null) Box(Modifier.align(Alignment.TopEnd)) { tools() }
        }
    }
}

/** Move and remove buttons for an item in edit mode. */
@Composable
private fun ItemTools(onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit) {
    Row {
        IconButton(onClick = onUp, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Move earlier") }
        IconButton(onClick = onDown, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Move later") }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, "Remove from section") }
    }
}

/** Full controls for one entity without leaving the dashboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntitySheet(device: LocationStore.Device, entity: Entity, session: ControlSession?, live: Entity?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(device.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EntityControls(entity, session, live)
            if (!session.isConnected) Text("Controls are available when the device is connected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
