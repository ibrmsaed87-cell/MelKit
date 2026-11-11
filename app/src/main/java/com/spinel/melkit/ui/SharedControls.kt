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
import androidx.compose.ui.res.stringResource
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

internal fun contentTypeLabel(path: String): Int = when (path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
    "melmod" -> R.string.library_mod
    "melmap" -> R.string.library_map
    "melsave" -> R.string.library_save
    "melworld" -> R.string.library_world
    else -> R.string.library_other
}
