package com.example.esphomecontroller

import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import com.example.esphomecontroller.api.DashboardCache
import com.example.esphomecontroller.api.mergeCachedEntities
import com.example.esphomecontroller.api.sameEntity
import com.example.esphomecontroller.api.isControlReady
import com.example.esphomecontroller.api.isSelectReady
import com.example.esphomecontroller.api.DeviceSession
import com.example.esphomecontroller.api.ControlSession
import com.example.esphomecontroller.api.WebSession
import com.example.esphomecontroller.api.SecretStore
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

class HomeStore(private val prefs: SharedPreferences) {
    // null is a legacy device whose connection mode has not yet been selected.
    data class Device(val id: String, val name: String, val address: String, val mode: String? = null, val followDiscovery: Boolean = !id.startsWith("manual:"))
    data class Home(val id: String, val name: String, val devices: List<Device> = emptyList())
    var theme by mutableStateOf(prefs.getString("theme", "system")?.takeIf { it in listOf("system", "light", "dark") } ?: "system"); private set
    fun changeTheme(value: String) { if (value in listOf("system", "light", "dark")) { theme = value; prefs.edit().putString("theme", value).apply() } }
    var homes by mutableStateOf(load()); private set
    var selected by mutableStateOf(prefs.getString("selected", null) ?: homes.first().id); private set
    init { if (homes.none { it.id == selected }) selected = homes.first().id }
    private fun load(): List<Home> = try {
        val arr = JSONArray(prefs.getString("homes", "[]"))
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i); val ds = obj.getJSONArray("devices")
            Home(obj.getString("id"), obj.getString("name"), (0 until ds.length()).map { j -> ds.getJSONObject(j).let { Device(it.getString("id"), it.getString("name"), it.getString("address"), it.optString("mode").takeIf { mode -> mode in listOf("encrypted", "plain", "web", "web_auth") }, it.optBoolean("followDiscovery", !it.getString("id").startsWith("manual:"))) } })
        }.ifEmpty { listOf(Home(UUID.randomUUID().toString(), "My home")) }
    } catch (_: Exception) { listOf(Home(UUID.randomUUID().toString(), "My home")) }
    private fun save() {
        val arr = JSONArray()
        homes.forEach { h -> val ds = JSONArray(); h.devices.forEach { d -> ds.put(JSONObject().put("id", d.id).put("name", d.name).put("address", d.address).put("mode", d.mode).put("followDiscovery", d.followDiscovery)) }; arr.put(JSONObject().put("id", h.id).put("name", h.name).put("devices", ds)) }
        prefs.edit().putString("homes", arr.toString()).putString("selected", selected).apply()
    }
    fun select(id: String) { selected = id; save() }
    fun addHome(name: String) { if (name.isNotBlank()) { val h = Home(UUID.randomUUID().toString(), name.trim()); homes = homes + h; selected = h.id; save() } }
    fun deleteHome(id: String) { if (homes.size > 1) { homes = homes.filterNot { it.id == id }; if (selected == id) selected = homes.first().id; save() } }
    fun putDevice(device: Device) { homes = homes.map { h -> if (h.id == selected) h.copy(devices = if (h.devices.any { it.id == device.id }) h.devices.map { if (it.id == device.id) device else it } else h.devices + device) else h }; save() }
    fun removeDevice(id: String) { homes = homes.map { if (it.id == selected) it.copy(devices = it.devices.filterNot { d -> d.id == id }) else it }; save() }
}

private fun endpointParts(address: String): Pair<String, String> {
    val index = address.lastIndexOf(':')
    return if (index > 0) address.substring(0, index).removePrefix("[").removeSuffix("]") to address.substring(index + 1) else address to "6053"
}

@Composable
fun HomeScreen(store: HomeStore, discovered: Map<String, String>, status: String, refresh: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    var newHome by remember { mutableStateOf("") }
    val home = store.homes.first { it.id == store.selected }
    val context = LocalContext.current
    val secrets = remember { SecretStore(context) }
    val cache = remember(context) { ViewModelProvider(context as ComponentActivity)[DashboardCache::class.java] }
    fun cacheKey(id: String) = "${home.id}/$id"
    val sessions = remember { mutableStateMapOf<String, ControlSession>() }
    val errors = remember { mutableStateMapOf<String, String>() }
    var revision by remember { mutableIntStateOf(0) }
    var details by remember { mutableStateOf<String?>(null) }
    var selectedDevice by remember(home.id) { mutableStateOf<String?>(null) }
    BackHandler(enabled = page == 3 && selectedDevice != null) { selectedDevice = null }
    var editing by remember { mutableStateOf<HomeStore.Device?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var removeId by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf((context as ComponentActivity).lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(context) {
        val lifecycle = (context as ComponentActivity).lifecycle
        val observer = LifecycleEventObserver { _, _ -> active = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); sessions.values.forEach { it.close() }; sessions.clear() }
    }
    // Restart on changed settings or discovered endpoints, and retry failures with bounded jittered backoff.
    val endpoints = home.devices.associate { d ->
        val found = if (d.followDiscovery) discovered[d.id] else null
        val address = if (found == null) d.address else if (d.mode == "web" || d.mode == "web_auth") "${endpointParts(found).first}:${endpointParts(d.address).second}" else found
        d.id to address
    }
    LaunchedEffect(home.id, home.devices, endpoints, active, revision) {
        sessions.values.forEach { it.close() }; sessions.clear(); errors.clear()
        if (!active) return@LaunchedEffect
        val attempts = mutableMapOf<String, Int>(); val next = mutableMapOf<String, Long>()
        while (true) {
            val now = android.os.SystemClock.elapsedRealtime()
            for (device in home.devices) {
                val current = sessions[device.id]
                if (current != null && !current.status.startsWith("Connection failed")) {
                    if (current.status.startsWith("Connected")) attempts.remove(device.id)
                    continue
                }
                if (now < (next[device.id] ?: 0)) continue
                val (host, port) = endpointParts(endpoints[device.id] ?: device.address)
                val endpoint = parseEndpoint(host, port)
                if (endpoint == null) { errors[device.id] = "Check the device address in settings"; continue }
                current?.close(); sessions.remove(device.id)
                val replacement = try {
                    when (device.mode) {
                        "web" -> WebSession(endpoint.host, endpoint.port, null, null)
                        "web_auth" -> {
                            val bytes = secrets.load("web:${device.id}")
                            if (bytes == null) { errors[device.id] = "Finish setup to connect"; continue }
                            val credentials = try { JSONObject(String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) }
                            WebSession(endpoint.host, endpoint.port, credentials.getString("username"), credentials.getString("password"))
                        }
                        else -> {
                            val raw = if (device.mode == "plain") null else secrets.load(device.id)
                            if (device.mode != "plain" && raw == null) { errors[device.id] = "Finish setup to connect"; continue }
                            if (raw != null && Build.VERSION.SDK_INT < 33) { raw.fill(0); errors[device.id] = "Requires Android 13 or newer"; continue }
                            DeviceSession(endpoint.host, endpoint.port, raw)
                        }
                    }
                } catch (_: Exception) { errors[device.id] = "Check the device connection settings."; continue }
                errors.remove(device.id)
                sessions[device.id] = replacement
                val attempt = (attempts[device.id] ?: 0).coerceAtMost(5)
                attempts[device.id] = attempt + 1
                next[device.id] = now + (5000L shl attempt).coerceAtMost(120000L) + Random.nextLong(0, 2000)
            }
            delay(2000)
        }
    }
    val dark = when (store.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    SideEffect { WindowCompat.getInsetsController((context as ComponentActivity).window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } }
    val colors = if (dark) darkColorScheme(primary = Color(0xFFCCBEFF), onPrimary = Color(0xFF302269), secondary = Color(0xFFCCC2DF), background = Color(0xFF15141B), surface = Color(0xFF24232C), surfaceVariant = Color(0xFF49454F)) else lightColorScheme(primary = Color(0xFF5545A0), secondary = Color(0xFF65558F), background = Color(0xFFF2F1F7), surface = Color.White, surfaceVariant = Color(0xFFE9E7F0))
    LaunchedEffect(home.id) {
        snapshotFlow { sessions.mapValues { it.value.entities } }.collect { snapshots ->
            snapshots.forEach { (id, entities) ->
                if (store.homes.any { h -> h.id == home.id && h.devices.any { it.id == id } }) cache.remember(cacheKey(id), entities)
            }
        }
    }
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.height(6.dp))
                Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (page) {
                    0 -> {
                        var homesExpanded by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                TextButton(onClick = { homesExpanded = true }) { Text("${home.name} ▾", style = MaterialTheme.typography.titleMedium) }
                                DropdownMenu(expanded = homesExpanded, onDismissRequest = { homesExpanded = false }) {
                                    store.homes.forEach { h -> DropdownMenuItem(text = { Text(h.name) }, onClick = { store.select(h.id); homesExpanded = false }) }
                                    DropdownMenuItem(text = { Text("Manage homes") }, onClick = { page = 2; homesExpanded = false })
                                }
                            }
                            Text("${home.devices.size} devices", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = { page = 1 }) { Icon(Icons.Default.Add, contentDescription = "Add device") }
                        }
                        if (home.devices.isEmpty()) Text("Your home is ready. Add a device to get started.")
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                            items(home.devices, key = { it.id }) { d ->
                                val session = sessions[d.id]
                                val connected = session?.status?.startsWith("Connected") == true
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(10.dp).background(if (connected) Color(0xFF36A76B) else Color(0xFFAAA7B2), CircleShape))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(d.name, style = MaterialTheme.typography.titleMedium)
                                            val label = errors[d.id] ?: when {
                                                session == null -> "Connecting…"
                                                connected -> "Connected"
                                                session.status.startsWith("Connection failed") -> "Connection unavailable · retrying"
                                                else -> session.status
                                            }
                                            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton(onClick = { details = d.id }) { Icon(Icons.Default.Info, contentDescription = "Device information") }
                                    }
                                    val liveEntities = session?.entities.orEmpty()
                                    val entities = mergeCachedEntities(cache.devices[cacheKey(d.id)].orEmpty(), liveEntities)
                                    entities.chunked(2).forEach { pair ->
                                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            pair.forEach { entity -> EntityTile(entity, session, liveEntities.firstOrNull { sameEntity(it, entity) }, Modifier.weight(1f).fillMaxHeight()) }
                                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                                        }
                                    }
                                    if (entities.isEmpty()) Text(if (connected) "Waiting for device states…" else "—", style = MaterialTheme.typography.bodySmall)
                                    if (errors[d.id] != null) TextButton(onClick = { editing = d; isNew = false }) { Text("Finish setup") }
                                }
                            }
                        }
                    }
                    1 -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Add a device", style = MaterialTheme.typography.titleLarge); Text("Choose a device for ${home.name}", style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = refresh) { Text("Refresh") } }
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val availableDevices = discovered.toList().filterNot { (name, _) -> home.devices.any { it.id == name } }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                            if (availableDevices.isEmpty()) item { Text("No new devices found yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(availableDevices, key = { it.first }) { (name, address) ->
                                Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    TextButton(onClick = { editing = HomeStore.Device(name, name, address); isNew = true }) { Text("Set up") }
                                } }
                            }
                            item { OutlinedButton(onClick = { editing = HomeStore.Device("manual:${UUID.randomUUID()}", "", ":6053"); isNew = true }) { Text("Add a device manually") } }
                        }
                    }
                    3 -> {
                        val device = home.devices.firstOrNull { it.id == selectedDevice }
                        if (device == null) {
                            Text("Devices", style = MaterialTheme.typography.titleLarge)
                            Text("${home.name} · ${home.devices.size} devices", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (home.devices.isEmpty()) Text("No devices in this home yet.")
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                                items(home.devices, key = { it.id }) { d ->
                                    val session = sessions[d.id]
                                    val connected = session?.status?.startsWith("Connected") == true
                                    Card(onClick = { selectedDevice = d.id }, modifier = Modifier.fillMaxWidth()) {
                                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(10.dp).background(if (connected) Color(0xFF36A76B) else Color(0xFFAAA7B2), CircleShape))
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(d.name, style = MaterialTheme.typography.titleMedium)
                                                Text(errors[d.id] ?: if (connected) "Connected" else if (session?.status?.startsWith("Connection failed") == true) "Connection unavailable · retrying" else "Connecting…", style = MaterialTheme.typography.bodySmall)
                                            }
                                            Text("View", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        } else {
                            val session = sessions[device.id]
                            val live = session?.entities.orEmpty()
                            val entities = mergeCachedEntities(cache.devices[cacheKey(device.id)].orEmpty(), live)
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                                item {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { selectedDevice = null }) { Text("‹ Devices") }
                                        Spacer(Modifier.weight(1f))
                                        TextButton(onClick = { editing = device.copy(address = endpoints[device.id] ?: device.address); isNew = false }) { Text("Edit") }
                                    }
                                    Text(device.name, style = MaterialTheme.typography.headlineSmall)
                                    Text(errors[device.id] ?: session?.status ?: "Not connected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                item {
                                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Device information", style = MaterialTheme.typography.titleMedium)
                                        Text("Address: ${endpoints[device.id] ?: device.address}")
                                        Text("Interface: ${if (device.mode == "web" || device.mode == "web_auth") "Web server" else "Native API"}")
                                        Text("Security: ${when (device.mode) { "plain" -> "Unencrypted"; "encrypted" -> "Encrypted"; "web" -> "HTTP, no login"; "web_auth" -> "HTTP with login"; else -> if (session?.status == "Connected securely") "Encrypted" else "Not configured" }}")
                                    } }
                                }
                                item { Text("Controls and sensors", style = MaterialTheme.typography.titleMedium) }
                                if (entities.isEmpty()) item { Text(if (session?.status?.startsWith("Connected") == true) "Waiting for device states…" else "—") }
                                items(entities, key = { e -> e.webId ?: "${e.kind}:${e.deviceId}:${e.key}" }) { entity ->
                                    EntityDetailRow(entity, session, live.firstOrNull { sameEntity(it, entity) })
                                }
                            }
                        }
                    }
                    else -> {
                        Text("Homes", style = MaterialTheme.typography.titleLarge)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(store.homes, key = { it.id }) { h -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { store.select(h.id) }) { Text(if (h.id == store.selected) "✓ ${h.name}" else h.name) }
                                if (store.homes.size > 1) TextButton(onClick = { store.deleteHome(h.id) }) { Text("Delete") }
                            } }
                            item { OutlinedTextField(newHome, { newHome = it }, label = { Text("New home name") }, singleLine = true); Button(enabled = newHome.isNotBlank(), onClick = { store.addHome(newHome); newHome = "" }) { Text("Create home") } }
                            item {
                                Spacer(Modifier.height(20.dp))
                                Text("Appearance", style = MaterialTheme.typography.titleLarge)
                                listOf("system" to "System default", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = store.theme == value, onClick = { store.changeTheme(value) }); Text(label) }
                                }
                            }
                        }
                    }
                }
                }
                NavigationBar(modifier = Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(0 to "Home", 3 to "Devices", 1 to "Discover", 2 to "Settings").forEach { (target, label) ->
                        val icon = when (target) { 0 -> Icons.Default.Home; 3 -> Icons.Default.Devices; 1 -> Icons.AutoMirrored.Filled.List; else -> Icons.Default.Settings }
                        NavigationBarItem(selected = page == target, onClick = { if (target == 3 && page == 3) selectedDevice = null; page = target }, icon = { Icon(icon, contentDescription = null) }, label = { Text(label) })
                    }
                }
            }
        }
        details?.let { id -> home.devices.find { it.id == id }?.let { d ->
            val s = sessions[id]
            AlertDialog(onDismissRequest = { details = null }, title = { Text(d.name) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Device information", style = MaterialTheme.typography.titleSmall)
                Text("Address: ${endpoints[id] ?: d.address}")
                Text("Connection: ${s?.status ?: errors[id] ?: "Not connected"}")
                Text("Interface: ${if (d.mode == "web" || d.mode == "web_auth") "Web server" else "Native API"}")
                Text("Security: ${when (d.mode) { "plain" -> "Unencrypted"; "encrypted" -> "Encrypted"; "web" -> "HTTP, no login"; "web_auth" -> "HTTP with login"; else -> if (s?.status == "Connected securely") "Encrypted" else "Not configured" }}")
            } }, confirmButton = { TextButton(onClick = { details = null; editing = d.copy(address = endpoints[id] ?: d.address); isNew = false }) { Text("Edit device") } }, dismissButton = { TextButton(onClick = { details = null }) { Text("Close") } })
        } }
        editing?.let { device -> DeviceSetupDialog(device, isNew, secrets, onDismiss = { editing = null }, onSave = { updated -> if (device.mode != updated.mode && !(device.mode == null && updated.mode == "encrypted")) { sessions.remove(device.id)?.close(); cache.remove(cacheKey(device.id)) }; store.putDevice(updated); editing = null; revision++; if (isNew) page = 0 }, onRemove = if (isNew) null else { { editing = null; removeId = device.id } }) }
        removeId?.let { id -> AlertDialog(onDismissRequest = { removeId = null }, title = { Text("Remove device?") }, text = { Text("The device will be removed from ${home.name}.") }, confirmButton = { TextButton(onClick = { store.removeDevice(id); if (selectedDevice == id) selectedDevice = null; cache.remove(cacheKey(id)); if (store.homes.none { h -> h.devices.any { it.id == id } }) { secrets.remove(id); secrets.remove("web:$id") }; removeId = null }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removeId = null }) { Text("Cancel") } }) }
    }
}

@Composable
private fun SelectControl(entity: DeviceSession.Entity, session: ControlSession?, live: DeviceSession.Entity?) {
    var expanded by remember(entity.key, entity.webId) { mutableStateOf(false) }
    val ready = isSelectReady(session?.status?.startsWith("Connected") == true, live)
    Box {
        OutlinedButton(enabled = ready, onClick = { expanded = true }) { Text(entity.value ?: "—", maxLines = 1) }
        DropdownMenu(expanded = expanded && ready, onDismissRequest = { expanded = false }) {
            live?.options.orEmpty().forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; if (live != null) session?.select(live, option) })
            }
        }
    }
}

@Composable
private fun EntityDetailRow(entity: DeviceSession.Entity, session: ControlSession?, live: DeviceSession.Entity?) {
    val connected = session?.status?.startsWith("Connected") == true
    val ready = isControlReady(connected, live)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(entity.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (entity.kind == "light" || entity.kind == "switch") {
                    Switch(checked = entity.state == true, enabled = ready, onCheckedChange = { if (live != null) session?.toggle(live, it) })
                } else if (entity.kind == "select") {
                    SelectControl(entity, session, live)
                } else {
                    val value = when (entity.kind) {
                        "binary" -> entity.state?.let { if (it) "ON" else "OFF" }
                        else -> entity.value?.let { listOfNotNull(it, entity.unit.takeIf { unit -> unit.isNotBlank() }).joinToString(" ") }
                    } ?: "—"
                    Text(value, Modifier.weight(1f).padding(start = 12.dp), textAlign = TextAlign.End)
                }
            }
            if (entity.kind == "light" && entity.supportsBrightness && entity.brightness != null) {
                var slider by remember(entity.key, entity.webId, entity.brightness) { mutableFloatStateOf(entity.brightness.coerceIn(0f, 1f)) }
                Text("Brightness", style = MaterialTheme.typography.bodySmall)
                Slider(value = slider, enabled = ready, onValueChange = { slider = it }, onValueChangeFinished = { if (live != null) session?.brightness(live, slider) })
            }
        }
    }
}

@Composable
private fun EntityTile(entity: DeviceSession.Entity, session: ControlSession?, live: DeviceSession.Entity?, modifier: Modifier = Modifier) {
    val connected = session?.status?.startsWith("Connected") == true
    val ready = isControlReady(connected, live)
    val isControl = entity.kind == "light" || entity.kind == "switch"
    val icon = when (entity.kind) {
        "light" -> Icons.Default.Lightbulb
        "switch" -> Icons.Default.ToggleOn
        "text" -> Icons.Default.TextFields
        else -> Icons.Default.Sensors
    }
    Card(modifier.heightIn(min = 142.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                if (isControl) {
                    Spacer(Modifier.weight(1f))
                    FilledTonalIconButton(enabled = ready, onClick = { if (live != null) session?.toggle(live, live.state != true) }, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = if (entity.state == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (entity.state == true) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = if (entity.state == true) "Turn ${entity.name} off" else "Turn ${entity.name} on")
                    }
                } else if (entity.kind != "select") {
                    Spacer(Modifier.width(8.dp))
                    val value = when (entity.kind) { "binary" -> entity.state?.let { if (it) "ON" else "OFF" }; else -> entity.value?.let { listOfNotNull(it, entity.unit.takeIf { u -> u.isNotBlank() }).joinToString(" ") } } ?: "—"
                    Text(value, textAlign = TextAlign.End, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
            }
            Text(entity.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            if (entity.kind == "select") SelectControl(entity, session, live)
            if (entity.kind == "light" && entity.supportsBrightness && entity.brightness != null) {
                var slider by remember(entity.key, entity.brightness) { mutableFloatStateOf(entity.brightness.coerceIn(0f, 1f)) }
                Slider(value = slider, enabled = ready, onValueChange = { slider = it }, onValueChangeFinished = { if (live != null) session?.brightness(live, slider) }, modifier = Modifier.fillMaxWidth())
            } else if (isControl) Text(if (entity.state == null) "Waiting for state" else if (entity.state) "On" else "Off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceSetupDialog(device: HomeStore.Device, isNew: Boolean, secrets: SecretStore, onDismiss: () -> Unit, onSave: (HomeStore.Device) -> Unit, onRemove: (() -> Unit)?) {
    var name by remember(device.id) { mutableStateOf(device.name) }
    val initial = remember(device.id) { endpointParts(device.address) }
    var host by remember(device.id) { mutableStateOf(initial.first) }
    var port by remember(device.id) { mutableStateOf(initial.second) }
    val hasKey = remember(device.id) { try { secrets.load(device.id)?.let { it.fill(0); true } ?: false } catch (_: Exception) { false } }
    var mode by remember(device.id) { mutableStateOf(device.mode ?: if (hasKey) "encrypted" else "") }
    var followDiscovery by remember(device.id) { mutableStateOf(device.followDiscovery) }
    var key by remember(device.id) { mutableStateOf("") }
    val webCredentials = remember(device.id) { try { secrets.load("web:${device.id}")?.let { bytes -> try { JSONObject(String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) } } } catch (_: Exception) { null } }
    var username by remember(device.id) { mutableStateOf(webCredentials?.optString("username") ?: "") }
    var password by remember(device.id) { mutableStateOf("") }
    var error by remember(device.id) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (isNew) "Set up device" else "Edit device") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Device name") }, singleLine = true)
            if (!device.id.startsWith("manual:")) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = followDiscovery, onCheckedChange = { followDiscovery = it })
                Text("Update address automatically")
            }
            OutlinedTextField(host, { host = it }, label = { Text("Address") }, singleLine = true, enabled = !followDiscovery)
            OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true, enabled = !followDiscovery || mode == "web" || mode == "web_auth")
            Text("Connection type", style = MaterialTheme.typography.titleSmall)
            listOf("encrypted" to "Native API · Encrypted", "plain" to "Native API · Unencrypted", "web" to "Web server · No login", "web_auth" to "Web server · Username and password").forEach { (option, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == option, onClick = {
                        val wasWeb = mode == "web" || mode == "web_auth"
                        val isWeb = option == "web" || option == "web_auth"
                        if (wasWeb != isWeb && (port == "6053" || port == "80")) port = if (isWeb) "80" else "6053"
                        mode = option
                    }); Text(label)
                }
            }
            if (mode == "plain" || mode == "web" || mode == "web_auth") Text("This connection is unencrypted. Device controls and states may be visible on your network.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (mode == "encrypted") OutlinedTextField(key, { key = it }, label = { Text(if (hasKey) "New encryption key (optional)" else "Encryption key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (mode == "web_auth") {
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text(if (webCredentials != null) "New password (optional)" else "Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove from home") }
        }
    }, confirmButton = { TextButton(onClick = {
        val endpoint = parseEndpoint(host, port)
        when {
            name.isBlank() -> error = "Enter a device name."
            endpoint == null -> error = "Enter a valid address and port."
            mode.isBlank() -> error = "Choose a connection type."
            mode == "encrypted" && Build.VERSION.SDK_INT < 33 -> error = "Encrypted connections need Android 13 or newer."
            mode == "encrypted" && key.isBlank() && !hasKey -> error = "Enter the device's encryption key."
            mode == "web_auth" && (username.isBlank() || (password.isBlank() && webCredentials == null)) -> error = "Enter the web server username and password."
            else -> {
                try {
                    if (mode == "encrypted" && key.isNotBlank()) {
                        val raw = Base64.decode(key.trim(), Base64.DEFAULT)
                        try { require(raw.size == 32); secrets.save(device.id, raw) } finally { raw.fill(0) }
                    }
                    if (mode == "web_auth") {
                        val data = JSONObject().put("username", username.trim()).put("password", password.ifBlank { webCredentials!!.getString("password") }).toString().toByteArray(Charsets.UTF_8)
                        try { secrets.save("web:${device.id}", data) } finally { data.fill(0) }
                    }
                    val address = "${endpoint!!.host}:${endpoint.port}"
                    onSave(device.copy(name = name.trim(), address = address, mode = mode, followDiscovery = followDiscovery)); key = ""; password = ""
                } catch (_: Exception) { error = "Could not save these connection settings. Check your credentials and try again." }
            }
        }
    }) { Text(if (isNew) "Add device" else "Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
