package com.example.esphomecontroller

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.esphomecontroller.api.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Level filter chips; Verbose covers both verbose levels. */
private val levelChips = listOf(
    "Errors" to setOf(LogLevel.ERROR), "Warnings" to setOf(LogLevel.WARN), "Info" to setOf(LogLevel.INFO),
    "Config" to setOf(LogLevel.CONFIG), "Debug" to setOf(LogLevel.DEBUG), "Verbose" to setOf(LogLevel.VERBOSE, LogLevel.VERY_VERBOSE),
)

private val rowTime = DateTimeFormatter.ofPattern("HH:mm:ss")

private enum class Export { COPY, SHARE }

/**
 * A device's live logs: coloured levels, tags and receive times; level, state, tag and text filters; follows new lines only while at the
 * bottom; pause, copy, share (optionally without addresses) and clear; and, for the Native API, the level requested from the device.
 */
@Composable
fun DeviceLogs(device: LocationStore.Device, session: ControlSession?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var deviceLevel by rememberSaveable { mutableStateOf(LogLevel.VERY_VERBOSE) }
    // Subscribing is per connection; a reconnect makes a new session, so subscribe again.
    LaunchedEffect(session, session.isConnected, deviceLevel) { if (session.isConnected) session?.subscribeLogs(deviceLevel) }

    var levels by remember { mutableStateOf(LogLevel.entries.toSet()) }
    var showStates by rememberSaveable { mutableStateOf(true) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var frozen by remember { mutableStateOf<List<LogLine>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var levelDialog by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf<Export?>(null) }

    val live = session?.logs.orEmpty()
    val source = frozen ?: live
    val visible = remember(source, levels, showStates, tag, query) {
        source.filter { l ->
            (if (l.isState) showStates else l.level == null || l.level in levels) &&
                (tag == null || l.tag == tag) &&
                (query.isBlank() || l.message.contains(query, ignoreCase = true) || l.tag?.contains(query, ignoreCase = true) == true)
        }
    }

    // Follow new lines only while the list is at the bottom: dragging stops following, settling at the bottom resumes it.
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    var seenSeq by remember { mutableLongStateOf(0L) }
    val lastSeq = visible.lastOrNull()?.seq ?: 0L
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (!scrolling && !listState.canScrollForward) follow = true }
    }
    LaunchedEffect(lastSeq, follow) {
        if (follow) { if (visible.isNotEmpty()) listState.scrollToItem(visible.lastIndex); seenSeq = lastSeq }
    }
    val newCount = if (frozen != null) live.count { it.seq > (frozen?.lastOrNull()?.seq ?: 0L) } else if (!follow) visible.count { it.seq > seenSeq } else 0

    ScreenHeader("Logs", subtitle = device.name, navigation = HeaderNavigation.back("Back to ${device.name}", onBack)) {
        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
            Icon(if (searching) Icons.Default.SearchOff else Icons.Default.Search, contentDescription = if (searching) "Close search" else "Search logs")
        }
        IconButton(onClick = { frozen = if (frozen == null) live else null; if (frozen == null) follow = true }) {
            Icon(if (frozen == null) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (frozen == null) "Pause" else "Resume")
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Copy all") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, enabled = visible.isNotEmpty(), onClick = { menuOpen = false; export = Export.COPY })
                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) }, enabled = visible.isNotEmpty(), onClick = { menuOpen = false; export = Export.SHARE })
                DropdownMenuItem(text = { Text("Clear") }, leadingIcon = { Icon(Icons.Default.DeleteSweep, null) }, onClick = {
                    menuOpen = false; session?.clearLogs(); if (frozen != null) frozen = emptyList(); seenSeq = 0L; follow = true
                })
                if (!device.isWeb) DropdownMenuItem(text = { Text("Device log level") }, leadingIcon = { Icon(Icons.Default.Tune, null) }, onClick = { menuOpen = false; levelDialog = true })
            }
        }
    }

    if (searching) OutlinedTextField(
        query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search messages and tags") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") } },
    )

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tag?.let { t ->
            InputChip(selected = true, onClick = { tag = null }, label = { Text(t) }, leadingIcon = { Icon(Icons.Default.Tag, null, Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove tag filter", modifier = Modifier.size(18.dp)) })
        }
        levelChips.forEach { (label, group) ->
            val on = group.all { it in levels }
            FilterChip(selected = on, onClick = { levels = if (on) levels - group else levels + group }, label = { Text(label) })
        }
        FilterChip(selected = showStates, onClick = { showStates = !showStates }, label = { Text("State updates") })
    }

    if (!session.isConnected) Surface(Modifier.fillMaxWidth(), shape = AppCardShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudOff, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(connectionSummary(session, null), style = MaterialTheme.typography.bodySmall)
        }
    }

    Card(Modifier.fillMaxSize().padding(bottom = PageGap), shape = AppCardShape, colors = appCardColors(), elevation = appCardElevation()) {
        Box(Modifier.fillMaxSize()) {
            if (visible.isEmpty()) Text(
                when { source.isNotEmpty() -> "No lines match the filters."; session.isConnected -> "Waiting for log messages…"; else -> "Logs appear once the device is connected." },
                Modifier.align(Alignment.Center).padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
                items(visible, key = { it.seq }) { line ->
                    LogRow(line, onTap = { line.tag?.let { t -> tag = if (tag == t) null else t } }, onLongPress = {
                        clipboard.setText(AnnotatedString(line.export()))
                        // Android 13+ shows its own confirmation.
                        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Line copied", Toast.LENGTH_SHORT).show()
                    })
                }
            }
            val showJump = frozen != null || !follow
            if (showJump) SmallFloatingActionButton(
                onClick = { frozen = null; follow = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (frozen != null) Icons.Default.PlayArrow else Icons.Default.ArrowDownward, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when { frozen != null -> if (newCount > 0) "Paused · $newCount new" else "Paused"; newCount > 0 -> "$newCount new"; else -> "Latest" },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }

    export?.let { action ->
        var hide by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { export = null },
            title = { Text(if (action == Export.COPY) "Copy logs" else "Share logs") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${visible.size} ${if (visible.size == 1) "line" else "lines"}, as currently filtered.")
                    Row(Modifier.fillMaxWidth().selectable(selected = hide, role = Role.Checkbox, onClick = { hide = !hide }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = hide, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Hide IP and MAC addresses")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val text = visible.joinToString("\n") { it.export() }.let { if (hide) redactAddresses(it) else it }
                    if (action == Export.COPY) {
                        clipboard.setText(AnnotatedString(text))
                        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                    } else {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "${device.name} logs").putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, "Share logs"))
                    }
                    export = null
                }) { Text(if (action == Export.COPY) "Copy" else "Share") }
            },
            dismissButton = { TextButton(onClick = { export = null }) { Text("Cancel") } },
        )
    }

    if (levelDialog) AlertDialog(
        onDismissRequest = { levelDialog = false },
        title = { Text("Device log level") },
        text = {
            Column {
                Text("The device sends lines up to this level, limited by its own logger level.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LogLevel.entries.forEach { l ->
                    Row(Modifier.fillMaxWidth().selectable(selected = deviceLevel == l, role = Role.RadioButton, onClick = { deviceLevel = l; levelDialog = false }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = deviceLevel == l, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(if (l == LogLevel.VERY_VERBOSE) "Very verbose (everything)" else l.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { levelDialog = false }) { Text("Close") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(line: LogLine, onTap: () -> Unit, onLongPress: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val amber = if (dark) Color(0xFFFFC857) else Color(0xFF9A6200)
    val bar = when {
        line.isState -> colors.primary
        else -> when (line.level) {
            LogLevel.ERROR -> colors.error
            LogLevel.WARN -> amber
            LogLevel.INFO -> Color(0xFF36A76B)
            LogLevel.CONFIG -> colors.secondary
            else -> Color.Transparent
        }
    }
    val text = when (line.level) {
        LogLevel.ERROR -> colors.error
        LogLevel.WARN -> amber
        LogLevel.DEBUG -> colors.onSurfaceVariant
        LogLevel.VERBOSE, LogLevel.VERY_VERBOSE -> colors.onSurfaceVariant.copy(alpha = 0.75f)
        else -> colors.onSurface
    }
    val time = remember(line.time) { rowTime.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(line.time)) }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onTap, onLongClick = onLongPress, onLongClickLabel = "Copy line").padding(horizontal = 12.dp, vertical = 3.dp).height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(bar, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        if (line.isState) {
            Icon(Icons.Default.Sensors, contentDescription = "State update", tint = colors.primary, modifier = Modifier.size(14.dp).padding(top = 2.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.onSurfaceVariant)) { append(time) }
                append("  ")
                line.tag?.let { withStyle(SpanStyle(color = if (line.isState) colors.primary else colors.tertiary, fontWeight = FontWeight.SemiBold)) { append(it) }; append("  ") }
                withStyle(SpanStyle(color = text)) { append(line.message) }
            },
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
        )
    }
}
