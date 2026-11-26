package com.example.esphomecontroller

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.collectLatest

private enum class Page { DASHBOARD, DEVICES, DISCOVER, SETTINGS }

@Composable
fun AppScreen(store: LocationStore, dashboards: DashboardStore, discovered: Map<String, DiscoveredDevice>, scan: ScanControls) {
    val activity = LocalContext.current as ComponentActivity
    val manager = remember(activity) { ViewModelProvider(activity)[ConnectionManager::class.java] }
    var page by remember { mutableStateOf(Page.DASHBOARD) }
    val location = store.location
    var selectedDevice by remember(location.id) { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<LocationStore.Device?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var removeId by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = page == Page.DEVICES && selectedDevice != null) { selectedDevice = null }

    // Sessions run only while the activity is visible.
    var active by remember { mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, _ -> active = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(store.devices, active) { manager.sync(store.devices, active) }
    // An unreachable device may have a new address; a short scan lets it follow.
    LaunchedEffect(manager.discoveryRequests) { if (manager.discoveryRequests > 0) scan.background() }

    val dark = when (store.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    SideEffect { WindowCompat.getInsetsController(activity.window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } }
    val colors = if (dark) darkColorScheme(primary = Color(0xFFCCBEFF), onPrimary = Color(0xFF302269), secondary = Color(0xFFCCC2DF), background = Color(0xFF15141B), surface = Color(0xFF24232C), surfaceVariant = Color(0xFF49454F))
        else lightColorScheme(primary = Color(0xFF5545A0), secondary = Color(0xFF65558F), background = Color(0xFFF2F1F7), surface = Color.White, surfaceVariant = Color(0xFFE9E7F0))

    fun edit(device: LocationStore.Device, new: Boolean) { editing = device; isNew = new }

    // Command failures appear once each as a snackbar with Retry.
    val snackbar = remember { SnackbarHostState() }
    val reported = remember { mutableSetOf<Long>() }
    // A dashboard can show errors under its sections instead of as snackbars.
    val inlineErrors by rememberUpdatedState(page == Page.DASHBOARD && editing == null && !dashboards.selected.settings.snackbarErrors)
    LaunchedEffect(manager) {
        snapshotFlow { manager.sessions.mapNotNull { (id, s) -> s.lastError?.let { id to it } } }.collectLatest { errors ->
            val (deviceId, error) = errors.filter { it.second.serial !in reported }.maxByOrNull { it.second.serial } ?: return@collectLatest
            reported += error.serial
            if (inlineErrors) return@collectLatest
            if (snackbar.showSnackbar(error.message, actionLabel = "Retry", withDismissAction = true) == SnackbarResult.ActionPerformed) manager.sessions[deviceId]?.retryFailed()
        }
    }

    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.height(6.dp))
                Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val setup = editing
                    if (setup != null) DeviceSetupScreen(setup, discovered[setup.id], isNew, manager.secrets, store.locations.map { it.id to it.name }, defaultLocation = setup.locationId ?: if (isNew) store.selected else null, onDismiss = { editing = null }, onSave = { updated ->
                        // A different interface yields different entity identities; drop the old cached ones.
                        if (setup.mode != updated.mode && !(setup.mode == null && updated.mode == "encrypted")) manager.forget(setup.id)
                        store.putDevice(updated); editing = null
                        manager.sync(store.devices, active); manager.reconnect(updated.id)
                        if (isNew) page = Page.DASHBOARD
                    }, onRemove = if (isNew) null else { { editing = null; removeId = setup.id } })
                    else when (page) {
                        Page.DASHBOARD -> DashboardScreen(store, dashboards, manager, onManageDashboards = { page = Page.SETTINGS }, onInfo = { details = it.id }, onSetup = { edit(it, false) })
                        Page.DISCOVER -> DiscoverScreen(store, discovered, scan, onSetup = { edit(it, true) })
                        Page.DEVICES -> {
                            val device = store.devices.firstOrNull { it.id == selectedDevice }
                            if (device == null) DeviceListScreen(store, manager, onOpen = { selectedDevice = it })
                            else DeviceDetailScreen(store, manager, device, onBack = { selectedDevice = null }, onEdit = { edit(device, false) })
                        }
                        Page.SETTINGS -> SettingsScreen(store, dashboards, manager)
                    }
                }
                if (editing == null) NavigationBar(modifier = Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(Triple(Page.DASHBOARD, "Dashboard", Icons.Default.Home), Triple(Page.DEVICES, "Devices", Icons.Default.Devices), Triple(Page.DISCOVER, "Discover", Icons.AutoMirrored.Filled.List), Triple(Page.SETTINGS, "Settings", Icons.Default.Settings)).forEach { (target, label, icon) ->
                        NavigationBarItem(selected = page == target, onClick = { if (target == Page.DEVICES) selectedDevice = null; page = target }, icon = { Icon(icon, contentDescription = null) }, label = { Text(label) })
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
          }
        }
        details?.let { id -> store.devices.find { it.id == id }?.let { d ->
            AlertDialog(
                onDismissRequest = { details = null }, title = { Text(d.name) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Device information", style = MaterialTheme.typography.titleSmall); DeviceInfoLines(d, manager.sessions[id], manager.setupErrors[id], manager.details(id)) } },
                confirmButton = { TextButton(onClick = { details = null; edit(d, false) }) { Text("Edit device") } },
                dismissButton = { TextButton(onClick = { details = null }) { Text("Close") } },
            )
        } }
        removeId?.let { id ->
            AlertDialog(
                onDismissRequest = { removeId = null }, title = { Text("Remove device?") }, text = { Text("The device and its saved keys will be removed from the app.") },
                confirmButton = { TextButton(onClick = {
                    store.removeDevice(id); dashboards.forgetDevice(id); if (selectedDevice == id) selectedDevice = null
                    manager.forget(id)
                    manager.secrets.remove(id); manager.secrets.remove("web:$id")
                    removeId = null
                }) { Text("Remove") } },
                dismissButton = { TextButton(onClick = { removeId = null }) { Text("Cancel") } },
            )
        }
    }
}
