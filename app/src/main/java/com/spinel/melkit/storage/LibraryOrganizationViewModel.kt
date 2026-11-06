package com.spinel.melkit.storage

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class LibraryLabel(val name: String = "", val category: String = "") {
    val empty: Boolean get() = name.isEmpty() && category.isEmpty()
}

/** Display metadata is keyed by game path. Only MelKit's private preferences are edited. */
internal class LibraryOrganizationViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("library-organization", Context.MODE_PRIVATE)
    private val journal = OperationJournal.get(application)
    private val mutex = Mutex()
    private val mutableLabels = MutableStateFlow(preferences.all.mapNotNull { (path, value) ->
        if (value !is String || !NewFileRestore.supported(path)) return@mapNotNull null
        runCatching {
            val item = JSONObject(value)
            LibraryLabel(item.optString("name"), item.optString("category"))
                .takeUnless { it.empty }?.let { path to it }
        }.getOrNull()
    }.toMap())
    val labels = mutableLabels.asStateFlow()

    suspend fun save(path: String, name: String, category: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!NewFileRestore.supported(path)) return@withLock false
            val label = LibraryLabel(name.trim().take(MAX_LABEL_LENGTH), category.trim().take(MAX_LABEL_LENGTH))
            if ((mutableLabels.value[path] ?: LibraryLabel()) == label) return@withLock true
            val editor = preferences.edit()
            if (label.empty) editor.remove(path)
            else editor.putString(path, JSONObject().put("name", label.name).put("category", label.category).toString())
            if (!runCatching { editor.commit() }.getOrDefault(false)) return@withLock false
            mutableLabels.update { if (label.empty) it - path else it + (path to label) }
            journal.add(OperationKind.ORGANIZE, path)
            true
        }
    }

    companion object { const val MAX_LABEL_LENGTH = 80 }
}
