package com.example.esphomecontroller

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Space between cards in a page's list (Devices, Discover, device page, Settings). */
val PageGap = 12.dp

/** Size of the round icon at the start of an [AppListRow]. */
val ListIconSize = 38.dp

/** Where row text starts: the row's start padding, its icon and the gap after it. Inset dividers start here. */
val ListTextInset = 16.dp + ListIconSize + 16.dp

/** A white icon in a filled circle, for the start of an [AppListRow]. */
@Composable
fun CircleIcon(icon: ImageVector, color: Color) =
    Box(Modifier.size(ListIconSize).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }

/**
 * The one list row used on Settings, Devices and Discover: a leading round icon, a one-line title, an optional summary of up to two lines,
 * optional trailing content and a chevron when it opens another page. Put it in a card on its own or with others in one card.
 */
@Composable
fun AppListRow(
    title: String, summary: String?, leading: @Composable () -> Unit,
    onClick: (() -> Unit)? = null, chevron: Boolean = false, summaryIsError: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!summary.isNullOrBlank()) Text(
                summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (summaryIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing()
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
    }
}
