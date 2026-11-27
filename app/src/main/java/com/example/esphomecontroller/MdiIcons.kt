package com.example.esphomecontroller

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One Material Design Icons glyph (https://pictogrammers.com/library/mdi/, Apache 2.0; see `assets/mdi/LICENSE`).
 * [name] is the MDI name without the "mdi:" prefix, as ESPHome advertises it.
 */
data class MdiIcon(val name: String, val codepoint: Int, val deprecated: Boolean = false, val aliases: List<String> = emptyList(), val tags: List<String> = emptyList()) {
    val glyph: String = String(Character.toChars(codepoint))
    /** "ceiling-light" → "Ceiling light". */
    val label: String = name.replace('-', ' ').replaceFirstChar { it.uppercase() }
}

/**
 * The bundled MDI index (`assets/mdi/icons.tsv`, built by `tools/mdi/build_index.py`). It loads once in the
 * background; [icons] is Compose state, so icons drawn before it finishes appear as soon as it has.
 */
object Mdi {
    var icons by mutableStateOf<List<MdiIcon>>(emptyList()); private set
    private var byName: Map<String, MdiIcon> = emptyMap()

    suspend fun load(context: Context) {
        if (icons.isNotEmpty()) return
        val parsed = withContext(Dispatchers.IO) { context.assets.open("mdi/icons.tsv").bufferedReader().useLines { parse(it) } }
        byName = index(parsed)
        icons = parsed
    }

    /** Parses `name, codepoint (hex), deprecated (0/1), aliases (space separated), tags (| separated)` lines. */
    fun parse(lines: Sequence<String>): List<MdiIcon> = lines.mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 2) return@mapNotNull null
        val codepoint = f[1].toIntOrNull(16) ?: return@mapNotNull null
        MdiIcon(f[0], codepoint, f.getOrNull(2) == "1",
            f.getOrNull(3).orEmpty().split(' ').filter { it.isNotBlank() },
            f.getOrNull(4).orEmpty().split('|').filter { it.isNotBlank() })
    }.toList()

    /** Names win over aliases, so an alias never hides a real icon of the same name. */
    fun index(icons: List<MdiIcon>): Map<String, MdiIcon> =
        icons.flatMap { i -> i.aliases.map { it to i } }.toMap() + icons.associateBy { it.name }

    /** The icon for "mdi:name" or "name" (case-insensitive), or null when unknown or not loaded yet. */
    fun find(name: String?): MdiIcon? = name?.trim()?.lowercase()?.removePrefix("mdi:")?.takeIf { it.isNotEmpty() }?.let { byName[it] }

    /**
     * Icons matching [query], best first: exact name, name prefix, name word prefix, then name, alias or tag
     * containing it. Deprecated icons appear only when their name matches exactly or by prefix.
     */
    fun search(query: String, all: List<MdiIcon> = icons): List<MdiIcon> {
        val q = query.trim().lowercase().removePrefix("mdi:").replace(' ', '-')
        if (q.isEmpty()) return all.filterNot { it.deprecated }
        fun rank(i: MdiIcon): Int? = when {
            i.name == q -> 0
            i.name.startsWith(q) -> 1
            i.deprecated -> null
            i.name.split('-').any { it.startsWith(q) } -> 2
            q in i.name -> 3
            i.aliases.any { q in it } -> 4
            i.tags.any { q in it.lowercase() } -> 5
            else -> null
        }
        return all.mapNotNull { i -> rank(i)?.let { it to i } }.sortedWith(compareBy({ it.first }, { it.second.name.length }, { it.second.name })).map { it.second }
    }
}

private val MdiFontFamily = FontFamily(Font(R.font.mdi))

/** Starts loading the MDI index if it is not loaded yet. */
@Composable
fun LoadMdi() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Mdi.load(context.applicationContext) }
}

/** Draws [icon] at [size] (unaffected by the system font scale). */
@Composable
fun MdiGlyph(icon: MdiIcon, size: Dp, modifier: Modifier = Modifier, tint: Color = Color.Unspecified, contentDescription: String? = null) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    Box(modifier.size(size).then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier), contentAlignment = Alignment.Center) {
        Text(
            icon.glyph,
            color = tint.takeOrElse { LocalContentColor.current },
            style = TextStyle(
                fontFamily = MdiFontFamily, fontSize = fontSize, lineHeight = fontSize,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
    }
}

/** Default size of Material icons, for MDI glyphs drawn in their place. */
val DefaultIconSize = 24.dp
