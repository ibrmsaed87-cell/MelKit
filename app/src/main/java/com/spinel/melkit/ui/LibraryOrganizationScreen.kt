package com.spinel.melkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.spinel.melkit.R
import com.spinel.melkit.storage.LibraryLabel
import com.spinel.melkit.storage.LibraryOrganizationViewModel
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun LibraryOrganizationScreen(labels: Map<String, LibraryLabel>, model: LibraryOrganizationViewModel,
    browse: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val categories = remember(labels) { categoryNames(labels) }
    val active = categories.firstOrNull { it.equals(selected, ignoreCase = true) }.orEmpty()
    val visible = remember(labels, active, query) {
        val term = query.trim()
        labels.entries.filter { (path, label) ->
            (active.isEmpty() || label.category.equals(active, ignoreCase = true)) &&
                (path.contains(term, ignoreCase = true) || label.name.contains(term, ignoreCase = true) ||
                    label.category.contains(term, ignoreCase = true))
        }.sortedBy { (path, label) -> label.name.ifEmpty { path.substringAfterLast('/') }.lowercase(Locale.ROOT) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.organization_help))
            FilledTonalButton(onClick = browse) { Text(stringResource(R.string.library_browse)) }
        }
        if (labels.isNotEmpty()) item {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text(stringResource(R.string.organization_search)) })
            if (categories.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = active.isEmpty(), onClick = { selected = "" },
                        label = { Text(stringResource(R.string.organization_all)) })
                }
                items(categories, key = { it }) { category ->
                    FilterChip(selected = active == category, onClick = { selected = category }, label = { Text(category) })
                }
            }
        }
        if (visible.isEmpty()) item {
            Text(stringResource(if (labels.isEmpty()) R.string.organization_empty else R.string.organization_no_results))
        }
        items(visible, key = { it.key }) { (path, label) ->
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MelkitSurface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label.name.ifEmpty { path.substringAfterLast('/') }, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.ContentOrLtr))
                    Text(path, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                    if (label.category.isNotEmpty()) Text(label.category, color = MelkitMint)
                    TextButton(onClick = { editing = path }) { Text(stringResource(R.string.profile_edit)) }
                }
            }
        }
    }
    editing?.let { path -> LibraryLabelEditor(path, labels, model) { editing = null } }
}

@Composable
internal fun LibraryLabelEditor(path: String, labels: Map<String, LibraryLabel>, model: LibraryOrganizationViewModel,
    dismiss: () -> Unit) {
    var name by rememberSaveable(path) { mutableStateOf(labels[path]?.name.orEmpty()) }
    var category by rememberSaveable(path) { mutableStateOf(labels[path]?.category.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val suggestions = remember(labels) { categoryNames(labels) }
    val limit = LibraryOrganizationViewModel.MAX_LABEL_LENGTH
    AlertDialog(onDismissRequest = { if (!busy) dismiss() },
        title = { Text(stringResource(R.string.organization_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(path, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                OutlinedTextField(value = name, onValueChange = { name = it.take(limit) }, modifier = Modifier.fillMaxWidth(),
                    enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.organization_name)) })
                OutlinedTextField(value = category, onValueChange = { category = it.take(limit) }, modifier = Modifier.fillMaxWidth(),
                    enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.organization_category)) })
                if (suggestions.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(suggestions, key = { it }) { label ->
                        SuggestionChip(enabled = !busy, onClick = { category = label }, label = { Text(label) })
                    }
                }
                if (failed) Text(stringResource(R.string.organization_save_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                failed = false
                scope.launch {
                    if (model.save(path, name, category)) dismiss() else failed = true
                    busy = false
                }
            }) { Text(stringResource(R.string.profile_save)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text(stringResource(R.string.saved_cancel)) } },
    )
}

private fun categoryNames(labels: Map<String, LibraryLabel>): List<String> = labels.values.map { it.category }
    .filter { it.isNotEmpty() }.sorted().distinctBy { it.lowercase(Locale.ROOT) }
