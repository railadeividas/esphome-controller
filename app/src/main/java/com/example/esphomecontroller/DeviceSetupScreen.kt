package com.example.esphomecontroller

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.esphomecontroller.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val MODE_LABELS = mapOf(
    "encrypted" to ("Encrypted" to "Native API with the device's encryption key"),
    "plain" to ("Unencrypted" to "Native API without encryption"),
    "web" to ("Web server" to "HTTP, no login"),
    "web_auth" to ("Web server with login" to "HTTP with username and password"),
)
private val ALL_MODES = listOf("encrypted", "plain", "web", "web_auth")
private fun isWebMode(mode: String) = mode == "web" || mode == "web_auth"

private sealed interface SetupStatus {
    data object None : SetupStatus
    data class Busy(val text: String) : SetupStatus
    data class Tested(val result: ProbeResult, val offerSaveAnyway: Boolean = false) : SetupStatus
    data class Message(val text: String, val isError: Boolean) : SetupStatus
}

/**
 * Adds or edits a device. The connection type is pre-selected from the device's advertisement,
 * the settings are tested before saving, and nothing ever falls back to a weaker mode silently.
 */
@Composable
fun DeviceSetupScreen(
    device: HomeStore.Device,
    discovered: DiscoveredDevice?,
    isNew: Boolean,
    secrets: SecretStore,
    onDismiss: () -> Unit,
    onSave: (HomeStore.Device) -> Unit,
    onRemove: (() -> Unit)?,
) {
    BackHandler(onBack = onDismiss)
    val manual = device.id.startsWith("manual:")
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val initial = remember(device.id) { splitAddress(device.address) }
    val hasKey = remember(device.id) { try { secrets.load(device.id)?.let { it.fill(0); true } ?: false } catch (_: Exception) { false } }
    val webCredentials = remember(device.id) { try { secrets.load("web:${device.id}")?.let { bytes -> try { JSONObject(String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) } } } catch (_: Exception) { null } }

    var name by remember(device.id) { mutableStateOf(if (isNew) discovered?.friendlyName ?: device.name else device.name) }
    var host by remember(device.id) { mutableStateOf(initial.first) }
    var port by remember(device.id) { mutableStateOf(if (manual && isNew) "6053" else initial.second) }
    var followDiscovery by remember(device.id) { mutableStateOf(device.followDiscovery) }
    var mode by remember(device.id) { mutableStateOf(device.mode ?: if (hasKey) "encrypted" else if (isNew) recommendedMode(discovered) ?: "" else "") }
    var key by remember(device.id) { mutableStateOf("") }
    var username by remember(device.id) { mutableStateOf(webCredentials?.optString("username") ?: "") }
    var password by remember(device.id) { mutableStateOf("") }
    var showAdvanced by remember(device.id) { mutableStateOf(false) }
    var status by remember(device.id) { mutableStateOf<SetupStatus>(SetupStatus.None) }
    val busy = status is SetupStatus.Busy

    // Options matching the advertisement come first; the rest stay one tap away.
    val primary = when (discovered?.encryption) {
        EncryptionAdvert.REQUIRED -> listOf("encrypted")
        EncryptionAdvert.AVAILABLE, EncryptionAdvert.NONE -> listOf("plain")
        else -> listOf("encrypted", "plain")
    }
    var showAllModes by remember(device.id) { mutableStateOf(mode.isNotBlank() && mode !in primary) }
    val modes = if (showAllModes) ALL_MODES else primary

    val keyError = key.isNotBlank() && decodeNoiseKey(key) == null
    fun endpoint() = parseEndpoint(host, port)

    /** The key to test with: a newly entered one, otherwise the saved one. Caller's copy is zeroed by the probe. */
    fun probeKey(): ByteArray? = if (mode != "encrypted") null else if (key.isNotBlank()) decodeNoiseKey(key) else try { secrets.load(device.id) } catch (_: Exception) { null }

    fun validate(): String? {
        val e = endpoint()
        return when {
            name.isBlank() -> "Enter a device name."
            e == null -> "Enter a valid address and port."
            mode.isBlank() -> "Choose a connection type."
            mode == "encrypted" && Build.VERSION.SDK_INT < 33 -> "Encrypted connections need Android 13 or newer."
            mode == "encrypted" && key.isBlank() && !hasKey -> "Enter the device's encryption key."
            mode == "encrypted" && keyError -> "The encryption key is not valid."
            mode == "web_auth" && (username.isBlank() || (password.isBlank() && webCredentials == null)) -> "Enter the web server username and password."
            else -> null
        }
    }

    fun commit() {
        val e = endpoint() ?: return
        try {
            if (mode == "encrypted" && key.isNotBlank()) {
                val raw = decodeNoiseKey(key) ?: throw IllegalArgumentException()
                try { secrets.save(device.id, raw) } finally { raw.fill(0) }
            }
            if (mode == "web_auth") {
                val data = JSONObject().put("username", username.trim()).put("password", password.ifBlank { webCredentials!!.getString("password") }).toString().toByteArray(Charsets.UTF_8)
                try { secrets.save("web:${device.id}", data) } finally { data.fill(0) }
            }
            onSave(device.copy(name = name.trim(), address = joinAddress(e.host, e.port), mode = mode, followDiscovery = followDiscovery && !manual))
            key = ""; password = ""
        } catch (_: Exception) { status = SetupStatus.Message("Could not store the credentials securely. Try again.", true) }
    }

    fun runProbe(afterSuccess: Boolean, offerSaveAnyway: Boolean) {
        val e = endpoint() ?: return
        val probeKey = probeKey()
        if (mode == "encrypted" && probeKey == null) { status = SetupStatus.Message("Enter the device's encryption key.", true); return }
        status = SetupStatus.Busy("Connecting to the device…")
        scope.launch {
            val result = withContext(Dispatchers.IO) { probeNative(e.host, e.port, probeKey) }
            status = SetupStatus.Tested(result, offerSaveAnyway && result is ProbeResult.Failure)
            if (afterSuccess && result is ProbeResult.Success) commit()
        }
    }

    fun save() {
        validate()?.let { status = SetupStatus.Message(it, true); return }
        // Web connections have no handshake to test here; native ones are tested before saving.
        if (isWebMode(mode)) commit() else runProbe(afterSuccess = true, offerSaveAnyway = true)
    }

    /** Manual devices: connect without a key to learn the name and whether encryption is required. */
    fun lookUp() {
        val e = endpoint() ?: run { status = SetupStatus.Message("Enter a valid address and port.", true); return }
        status = SetupStatus.Busy("Looking up the device…")
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { probeNative(e.host, e.port, null) }) {
                is ProbeResult.Success -> {
                    if (name.isBlank()) name = result.info.displayName
                    mode = "plain"
                    status = SetupStatus.Tested(result)
                }
                is ProbeResult.Failure -> {
                    if (result.kind == ProbeFailure.ENCRYPTION_REQUIRED) { mode = "encrypted"; status = SetupStatus.Message("This device uses encryption. Enter its key, then add it.", false) }
                    else status = SetupStatus.Tested(result)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("‹ Cancel") }
        }
        Text(if (isNew) "Set up device" else "Edit device", style = MaterialTheme.typography.headlineSmall)

        discovered?.let { d ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(d.displayName, style = MaterialTheme.typography.titleMedium)
                        if (d.summary.isNotBlank()) Text(d.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        d.project?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(encryptionText(d.encryption), style = MaterialTheme.typography.bodySmall)
                    }
                    EncryptionIcon(d.encryption)
                }
            }
        }

        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        if (manual) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("Address or hostname") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.width(96.dp))
            }
            if (!isWebMode(mode)) OutlinedButton(enabled = !busy && host.isNotBlank(), onClick = ::lookUp) { Text("Look up device") }
        }

        Text("Connection", style = MaterialTheme.typography.titleSmall)
        modes.forEach { option ->
            val (label, description) = MODE_LABELS.getValue(option)
            Row(
                Modifier.fillMaxWidth().selectable(selected = mode == option, role = Role.RadioButton, onClick = {
                    if (isWebMode(mode) != isWebMode(option) && (port == "6053" || port == "80")) port = if (isWebMode(option)) "80" else "6053"
                    mode = option; status = SetupStatus.None
                }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == option, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(label + if (option == recommendedMode(discovered) && isNew) " (recommended)" else "")
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!showAllModes) TextButton(onClick = { showAllModes = true }) { Text("Other connection types") }
        if (discovered?.encryption == EncryptionAdvert.AVAILABLE && mode == "plain")
            Text("This device supports encryption but has no key yet. Add api: encryption: key: to its configuration to secure it.", style = MaterialTheme.typography.bodySmall)
        if (mode == "plain" || isWebMode(mode))
            Text("This connection is unencrypted. Device controls and states may be visible on your network.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

        if (mode == "encrypted") {
            OutlinedTextField(
                key, { key = it; if (status !is SetupStatus.Busy) status = SetupStatus.None },
                label = { Text(if (hasKey) "New encryption key (leave empty to keep)" else "Encryption key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, isError = keyError,
                supportingText = { Text(if (keyError) "The key is 32 bytes in Base64: 44 characters ending in =." else "From the device's api: encryption: key setting.") },
                trailingIcon = { IconButton(onClick = { clipboard.getText()?.text?.trim()?.let { key = it } }) { Icon(Icons.Default.ContentPaste, contentDescription = "Paste key") } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (mode == "web_auth") {
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text(if (webCredentials != null) "New password (leave empty to keep)" else "Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        if (!manual) {
            TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Hide advanced settings" else "Advanced settings") }
            if (showAdvanced) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = followDiscovery, onCheckedChange = { followDiscovery = it })
                    Text("Update the address automatically when the device moves")
                }
                OutlinedTextField(host, { host = it }, label = { Text("Address") }, singleLine = true, enabled = !followDiscovery, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, enabled = !followDiscovery || isWebMode(mode), modifier = Modifier.width(120.dp))
            }
        }

        when (val s = status) {
            SetupStatus.None -> Unit
            is SetupStatus.Busy -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(s.text) }
            is SetupStatus.Message -> Text(s.text, color = if (s.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            is SetupStatus.Tested -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (val r = s.result) {
                        is ProbeResult.Success -> {
                            Text(if (r.encrypted) "Connected securely" else "Connected without encryption", style = MaterialTheme.typography.titleSmall)
                            Text(listOf(r.info.displayName, r.info.model, r.info.esphomeVersion.takeIf { it.isNotBlank() }?.let { "ESPHome $it" }).filterNot { it.isNullOrBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            Text("${r.info.entityCount} entities", style = MaterialTheme.typography.bodySmall)
                        }
                        is ProbeResult.Failure -> {
                            Text(r.message, color = MaterialTheme.colorScheme.error)
                            if (s.offerSaveAnyway) TextButton(onClick = ::commit) { Text("Save anyway") }
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!isWebMode(mode)) OutlinedButton(enabled = !busy && mode.isNotBlank(), onClick = { validate()?.let { status = SetupStatus.Message(it, true) } ?: runProbe(afterSuccess = false, offerSaveAnyway = false) }) { Text("Test connection") }
            Button(enabled = !busy, onClick = ::save) { Text(if (isNew) "Add device" else "Save") }
        }
        if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove from home", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(20.dp))
    }
}

fun encryptionText(advert: EncryptionAdvert) = when (advert) {
    EncryptionAdvert.REQUIRED -> "Encrypted · needs its encryption key"
    EncryptionAdvert.AVAILABLE -> "Not encrypted · encryption can be enabled on the device"
    EncryptionAdvert.NONE -> "Not encrypted"
    EncryptionAdvert.UNKNOWN -> "Encryption unknown"
}

@Composable
fun EncryptionIcon(advert: EncryptionAdvert) {
    when (advert) {
        EncryptionAdvert.REQUIRED -> Icon(Icons.Default.Lock, contentDescription = "Encrypted", tint = MaterialTheme.colorScheme.primary)
        EncryptionAdvert.AVAILABLE, EncryptionAdvert.NONE -> Icon(Icons.Default.LockOpen, contentDescription = "Not encrypted", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        EncryptionAdvert.UNKNOWN -> Unit
    }
}
