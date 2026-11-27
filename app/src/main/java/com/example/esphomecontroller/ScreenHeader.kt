package com.example.esphomecontroller

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The leading button of a [ScreenHeader]: back on sub-pages, close on full-screen forms. */
class HeaderNavigation(val icon: ImageVector, val description: String, val onClick: () -> Unit) {
    companion object {
        fun back(description: String, onClick: () -> Unit) = HeaderNavigation(Icons.AutoMirrored.Filled.ArrowBack, description, onClick)
        fun close(description: String, onClick: () -> Unit) = HeaderNavigation(Icons.Default.Close, description, onClick)
    }
}

/** The side padding AppScreen gives every page. */
private val PagePadding = 16.dp

/** Extra space under the header, so content starts 12 dp below its divider on every page. */
private val HeaderContentGap = 4.dp

/**
 * The header every page uses: a tonal 64 dp bar (same tone as the bottom navigation), separated from the content by a full-width divider, with an optional back/close button, a titleLarge title
 * with an optional one-line subtitle, and trailing actions (icon buttons, or a text button to confirm).
 * With [onTitleClick] the title shows a drop-down arrow and [titleMenu] is anchored to it.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    subtitleIsError: Boolean = false,
    navigation: HeaderNavigation? = null,
    onTitleClick: (() -> Unit)? = null,
    titleMenu: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    // The header reaches past the page's 16 dp side padding: the bar spans the screen, icons sit 16 dp from its edges.
    // Its tone matches the bottom navigation bar and also fills the status bar area above it.
    val barColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
    val statusBarHeight = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    // Every page's content starts the same distance below the header: this bottom padding plus AppScreen's 8 dp spacing.
    Column(
        Modifier
            .padding(bottom = HeaderContentGap)
            .layout { measurable, constraints ->
                val bleed = PagePadding.roundToPx()
                val width = constraints.maxWidth + 2 * bleed
                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
            }
            .drawBehind { drawRect(barColor, topLeft = Offset(0f, -statusBarHeight), size = Size(size.width, size.height + statusBarHeight)) },
    ) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        if (navigation != null) IconButton(onClick = navigation.onClick) { Icon(navigation.icon, contentDescription = navigation.description) }
        else Spacer(Modifier.width(PagePadding - 4.dp))
        Box(Modifier.weight(1f).padding(end = 4.dp)) {
            Column(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).semantics { heading() })
                    if (onTitleClick != null) Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch")
                }
                if (!subtitle.isNullOrBlank()) Text(
                    subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (subtitleIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            titleMenu()
        }
        actions()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

/** A heading inside a page (e.g. a Settings section), styled so it does not read as a page title. */
@Composable
fun PageSectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = modifier.semantics { heading() })

/**
 * Lays content out without [trim] at its top and bottom, so a control's invisible touch padding does not count as
 * spacing: with it, the first visible element of every page starts the same 12 dp below the header divider. Touch
 * targets keep their full size and reach into the neighbouring space.
 */
fun Modifier.trimVertical(trim: Dp) = layout { measurable, constraints ->
    val t = trim.roundToPx()
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + 2 * t else constraints.maxHeight))
    val height = (placeable.height - 2 * t).coerceAtLeast(0)
    layout(placeable.width, height) { placeable.place(0, -t) }
}

/** Material's minimum touch target; icon buttons and chips take this much layout space around a smaller visible shape. */
val TouchTarget = 48.dp
