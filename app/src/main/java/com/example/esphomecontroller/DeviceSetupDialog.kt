package com.example.esphomecontroller

import android.os.Build
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.esphomecontroller.api.SecretStore
import org.json.JSONObject

/** Captures connection type and credentials before a device is saved; secrets go to the Keystore only. */
@Composable
fun DeviceSetupDialog(device: HomeStore.Device, isNew: Boolean, secrets: SecretStore, onDismiss: () -> Unit, onSave: (HomeStore.Device) -> Unit, onRemove: (() -> Unit)?) {
    var name by remember(device.id) { mutableStateOf(device.name) }
    val initial = remember(device.id) { splitAddress(device.address) }
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
                    val address = joinAddress(endpoint!!.host, endpoint.port)
                    onSave(device.copy(name = name.trim(), address = address, mode = mode, followDiscovery = followDiscovery)); key = ""; password = ""
                } catch (_: Exception) { error = "Could not save these connection settings. Check your credentials and try again." }
            }
        }
    }) { Text(if (isNew) "Add device" else "Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
