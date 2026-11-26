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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider

private enum class Page { HOME, DEVICES, DISCOVER, SETTINGS }

@Composable
fun HomeScreen(store: HomeStore, discovered: Map<String, DiscoveredDevice>, status: String, refresh: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val manager = remember(activity) { ViewModelProvider(activity)[ConnectionManager::class.java] }
    var page by remember { mutableStateOf(Page.HOME) }
    val home = store.home
    var selectedDevice by remember(home.id) { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<HomeStore.Device?>(null) }
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
    LaunchedEffect(home, active) { manager.sync(home, active) }

    val dark = when (store.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    SideEffect { WindowCompat.getInsetsController(activity.window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } }
    val colors = if (dark) darkColorScheme(primary = Color(0xFFCCBEFF), onPrimary = Color(0xFF302269), secondary = Color(0xFFCCC2DF), background = Color(0xFF15141B), surface = Color(0xFF24232C), surfaceVariant = Color(0xFF49454F))
        else lightColorScheme(primary = Color(0xFF5545A0), secondary = Color(0xFF65558F), background = Color(0xFFF2F1F7), surface = Color.White, surfaceVariant = Color(0xFFE9E7F0))

    fun edit(device: HomeStore.Device, new: Boolean) { editing = device; isNew = new }

    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.height(6.dp))
                Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val setup = editing
                    if (setup != null) DeviceSetupScreen(setup, discovered[setup.id], isNew, manager.secrets, onDismiss = { editing = null }, onSave = { updated ->
                        // A different interface yields different entity identities; drop the old cached ones.
                        if (setup.mode != updated.mode && !(setup.mode == null && updated.mode == "encrypted")) manager.forget(home.id, setup.id)
                        store.putDevice(updated); editing = null
                        manager.sync(store.home, active); manager.reconnect(updated.id)
                        if (isNew) page = Page.HOME
                    }, onRemove = if (isNew) null else { { editing = null; removeId = setup.id } })
                    else when (page) {
                        Page.HOME -> DashboardScreen(store, manager, onManageHomes = { page = Page.SETTINGS }, onAdd = { page = Page.DISCOVER }, onInfo = { details = it.id }, onSetup = { edit(it, false) })
                        Page.DISCOVER -> DiscoverScreen(store, discovered, status, refresh, onSetup = { edit(it, true) })
                        Page.DEVICES -> {
                            val device = home.devices.firstOrNull { it.id == selectedDevice }
                            if (device == null) DeviceListScreen(store, manager, onOpen = { selectedDevice = it })
                            else DeviceDetailScreen(store, manager, device, onBack = { selectedDevice = null }, onEdit = { edit(device, false) })
                        }
                        Page.SETTINGS -> SettingsScreen(store)
                    }
                }
                if (editing == null) NavigationBar(modifier = Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(Triple(Page.HOME, "Home", Icons.Default.Home), Triple(Page.DEVICES, "Devices", Icons.Default.Devices), Triple(Page.DISCOVER, "Discover", Icons.AutoMirrored.Filled.List), Triple(Page.SETTINGS, "Settings", Icons.Default.Settings)).forEach { (target, label, icon) ->
                        NavigationBarItem(selected = page == target, onClick = { if (target == Page.DEVICES && page == Page.DEVICES) selectedDevice = null; page = target }, icon = { Icon(icon, contentDescription = null) }, label = { Text(label) })
                    }
                }
            }
        }
        details?.let { id -> home.devices.find { it.id == id }?.let { d ->
            AlertDialog(
                onDismissRequest = { details = null }, title = { Text(d.name) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Device information", style = MaterialTheme.typography.titleSmall); DeviceInfoLines(d, manager.sessions[id], manager.setupErrors[id]) } },
                confirmButton = { TextButton(onClick = { details = null; edit(d, false) }) { Text("Edit device") } },
                dismissButton = { TextButton(onClick = { details = null }) { Text("Close") } },
            )
        } }
        removeId?.let { id ->
            AlertDialog(
                onDismissRequest = { removeId = null }, title = { Text("Remove device?") }, text = { Text("The device will be removed from ${home.name}.") },
                confirmButton = { TextButton(onClick = {
                    store.removeDevice(id); if (selectedDevice == id) selectedDevice = null
                    manager.forget(home.id, id)
                    if (!store.isDeviceInAnyHome(id)) { manager.secrets.remove(id); manager.secrets.remove("web:$id") }
                    removeId = null
                }) { Text("Remove") } },
                dismissButton = { TextButton(onClick = { removeId = null }) { Text("Cancel") } },
            )
        }
    }
}
