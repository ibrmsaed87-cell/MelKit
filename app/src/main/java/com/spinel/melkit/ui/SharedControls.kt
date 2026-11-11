package com.spinel.melkit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.spinel.melkit.R
import com.spinel.melkit.storage.GameLibraryState

internal data class ContentSection(val path: String, val title: Int, val icon: ImageVector)
internal val contentSections = listOf(
    ContentSection("Maps", R.string.maps, Icons.Outlined.Map),
    ContentSection("Mods", R.string.mods, Icons.Outlined.Inventory2),
    ContentSection("Saves", R.string.saves, Icons.Outlined.Save),
    ContentSection("Worlds", R.string.worlds, Icons.Outlined.Public),
)

@Composable
internal fun ContentFolderGrid(state: GameLibraryState, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        contentSections.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { section ->
                    val count = state.counts?.let { when (section.path) {
                        "Maps" -> it.maps; "Mods" -> it.mods; "Saves" -> it.saves; else -> it.worlds
                    } }
                    Card(onClick = { onOpen(state.entries.firstOrNull {
                        it.directory && it.name.equals(section.path, true)
                    }?.path ?: section.path) }, enabled = state.connected && !state.busy,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MelkitSurface)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(section.icon, null, tint = MelkitMint)
                            Text(stringResource(section.title), style = MaterialTheme.typography.titleMedium)
                            if (state.countsLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(if (count != null) stringResource(R.string.content_file_count, count)
                                else stringResource(R.string.library_count_unavailable),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** Positioned by Material's popup provider, so the menu stays anchored and within the window. */
@Composable
internal fun FileActionsMenu(expanded: Boolean, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    MaterialTheme(shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(16.dp))) {
        DropdownMenu(expanded = expanded, onDismissRequest = dismiss,
            modifier = Modifier.widthIn(min = 220.dp, max = 300.dp), content = content)
    }
}

@Composable
internal fun FileAction(text: Int, icon: ImageVector, enabled: Boolean = true, action: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(text)) }, leadingIcon = { Icon(icon, null) },
        modifier = Modifier.heightIn(min = 48.dp), enabled = enabled, onClick = action)
}

internal data class ActionButton(val label: String, val icon: ImageVector, val enabled: Boolean = true,
    val primary: Boolean = false, val onClick: () -> Unit)

/** Full words determine the minimum width. Rows wrap before labels can become vertical letters. */
@Composable
internal fun ActionButtons(actions: List<ActionButton>, maxColumns: Int = 2) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelLarge
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = with(density) { maxWidth.roundToPx() }
        val gap = with(density) { 8.dp.roundToPx() }
        // Leading icon 20dp, gap 8dp, 12dp padding per side and 4dp rounding/border allowance.
        val decoration = with(density) { 56.dp.roundToPx() }
        val longestWord = actions.flatMap { it.label.split(Regex("\\s+")) }.maxOfOrNull {
            measurer.measure(AnnotatedString(it), style = style, softWrap = false).size.width
        } ?: 0
        val columns = (maxColumns.coerceAtMost(actions.size).coerceAtLeast(1) downTo 1).firstOrNull {
            (available - gap * (it - 1)) / it >= longestWord + decoration
        } ?: 1
        val labelWidth = ((available - gap * (columns - 1)) / columns - decoration).coerceAtLeast(1)
        val labelHeight = actions.maxOfOrNull {
            measurer.measure(AnnotatedString(it.label), style = style,
                constraints = Constraints(maxWidth = labelWidth)).size.height
        } ?: 0
        val height = with(density) { labelHeight.toDp() + 20.dp }.coerceAtLeast(48.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { action ->
                        val modifier = Modifier.weight(1f).heightIn(min = height)
                        val padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                        if (action.primary) FilledTonalButton(onClick = action.onClick, enabled = action.enabled,
                            modifier = modifier, contentPadding = padding) { ActionButtonContent(action) }
                        else OutlinedButton(onClick = action.onClick, enabled = action.enabled,
                            modifier = modifier, contentPadding = padding) { ActionButtonContent(action) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButtonContent(action: ActionButton) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(action.icon, null, Modifier.size(20.dp))
        Text(action.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center)
    }
}

internal fun contentTypeLabel(path: String): Int = when (path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
    "melmod" -> R.string.library_mod
    "melmap" -> R.string.library_map
    "melsave" -> R.string.library_save
    "melworld" -> R.string.library_world
    else -> R.string.library_other
}
