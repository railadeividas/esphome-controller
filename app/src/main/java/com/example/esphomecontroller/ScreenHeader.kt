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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The leading button of a [ScreenHeader]: back on sub-pages, close on full-screen forms. */
class HeaderNavigation(val icon: ImageVector, val description: String, val onClick: () -> Unit) {
    companion object {
        fun back(description: String, onClick: () -> Unit) = HeaderNavigation(Icons.AutoMirrored.Filled.ArrowBack, description, onClick)
        fun close(description: String, onClick: () -> Unit) = HeaderNavigation(Icons.Default.Close, description, onClick)
    }
}

/** Width the header reaches past the page's 16 dp side padding, so its icons sit 16 dp from the screen edge like a Material top bar. */
private val HeaderBleed = 12.dp

/**
 * The header every page uses: a 64 dp bar with an optional back/close button, a titleLarge title
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
    Row(
        Modifier
            .layout { measurable, constraints ->
                val bleed = HeaderBleed.roundToPx()
                val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + 2 * bleed, maxWidth = constraints.maxWidth + 2 * bleed))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
            }
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigation != null) IconButton(onClick = navigation.onClick) { Icon(navigation.icon, contentDescription = navigation.description) }
        else Spacer(Modifier.width(HeaderBleed + 4.dp))
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
        Spacer(Modifier.width(4.dp))
    }
}

/** A heading inside a page (e.g. a Settings section), styled so it does not read as a page title. */
@Composable
fun PageSectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 8.dp).semantics { heading() })
