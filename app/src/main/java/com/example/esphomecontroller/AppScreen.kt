package com.example.esphomecontroller

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
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
    LoadMdi()
    var page by remember { mutableStateOf(Page.DASHBOARD) }
    val location = store.location
    var selectedDevice by remember(location.id) { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<LocationStore.Device?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var removeId by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = page == Page.DEVICES && selectedDevice != null) { selectedDevice = null }
    var settingsPage by remember { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page == Page.SETTINGS && settingsPage != null && editing == null) { settingsPage = null }

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
    // OLED mode: black page and navigation bar, slightly darker cards that still stand out.
    val black = dark && store.pureBlack
    val colors = if (dark) darkColorScheme(primary = Color(0xFFCCBEFF), onPrimary = Color(0xFF302269), secondary = Color(0xFFCCC2DF), background = if (black) Color.Black else Color(0xFF15141B), surface = if (black) Color(0xFF1B1A21) else Color(0xFF24232C), surfaceVariant = Color(0xFF49454F))
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
                Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(PageGap)) {
                    val setup = editing
                    if (setup != null) DeviceSetupScreen(setup, discovered[setup.id], isNew, manager.secrets, store.locations.map { it.id to it.name }, defaultLocation = setup.locationId, onDismiss = { editing = null }, onSave = { updated ->
                        // A different interface yields different entity identities; drop the old cached ones.
                        if (setup.mode != updated.mode && !(setup.mode == null && updated.mode == "encrypted")) manager.forget(setup.id)
                        store.putDevice(updated); editing = null
                        manager.sync(store.devices, active); manager.reconnect(updated.id)
                        if (isNew) page = Page.DASHBOARD
                    }, onRemove = if (isNew) null else { { editing = null; removeId = setup.id } })
                    else when (page) {
                        Page.DASHBOARD -> DashboardScreen(store, dashboards, manager, onManageDashboards = { settingsPage = SettingsPage.DASHBOARDS; page = Page.SETTINGS }, onOpenDevice = { selectedDevice = it; page = Page.DEVICES })
                        Page.DISCOVER -> DiscoverScreen(store, discovered, scan, onSetup = { edit(it, true) })
                        Page.DEVICES -> {
                            val device = store.devices.firstOrNull { it.id == selectedDevice }
                            if (device == null) DeviceListScreen(store, manager, onOpen = { selectedDevice = it })
                            else DeviceDetailScreen(store, manager, device, onBack = { selectedDevice = null }, onEdit = { edit(device, false) })
                        }
                        Page.SETTINGS -> SettingsScreen(store, dashboards, manager, settingsPage, onOpen = { settingsPage = it })
                    }
                }
                // A slimmer bar than Material's 80 dp NavigationBar: 64 dp with labels, 40 dp with icons only (the 32 dp indicator plus the same small gap).
                if (editing == null) Surface(Modifier.fillMaxWidth(), color = if (black) Color.Black else MaterialTheme.colorScheme.surface, tonalElevation = if (black) 0.dp else 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().height(if (store.navLabels) 64.dp else 40.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                        listOf(Triple(Page.DASHBOARD, "Dashboard", Icons.Default.Home), Triple(Page.DEVICES, "Devices", Icons.Default.Devices), Triple(Page.DISCOVER, "Discover", Icons.AutoMirrored.Filled.List), Triple(Page.SETTINGS, "Settings", Icons.Default.Settings)).forEach { (target, label, icon) ->
                            NavigationBarItem(
                                selected = page == target, onClick = { if (target == Page.DEVICES) selectedDevice = null; if (target == Page.SETTINGS) settingsPage = null; page = target },
                                icon = { Icon(icon, contentDescription = if (store.navLabels) null else label) },
                                label = if (store.navLabels) { { Text(label, style = MaterialTheme.typography.labelSmall) } } else null,
                            )
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
          }
        }
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
