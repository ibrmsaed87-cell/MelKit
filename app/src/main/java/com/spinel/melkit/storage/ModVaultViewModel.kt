package com.spinel.melkit.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VaultMod(val id: String, val path: String, val name: String, val status: String,
    val bytes: Long, val modified: Long)

data class ModVaultState(val entries: List<VaultMod> = emptyList(), val loaded: Boolean = false,
    val busy: Boolean = false, val offset: Int = 0, val hasNext: Boolean = false, val warnings: Int = 0,
    val revision: Int = 0, val issue: String? = null, val request: VaultMod? = null, val result: String? = null)

class ModVaultViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(ModVaultState())
    val state = mutableState.asStateFlow()

    fun refresh(library: GameLibraryViewModel, offset: Int = 0) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, issue = null, request = null, result = null) }
        viewModelScope.launch {
            try { load(library, offset) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(issue = "read") } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    private suspend fun load(library: GameLibraryViewModel, offset: Int) {
        val response = library.listMods(offset)
        if (response.getString("status") != "ok") {
            mutableState.update { it.copy(issue = response.getString("status")) }; return
        }
        val array = response.getJSONArray("entries")
        val entries = List(array.length()) {
            val item = array.getJSONObject(it)
            VaultMod(item.getString("id"), item.getString("path"), item.getString("name"), item.getString("state"),
                item.getLong("size"), item.getLong("modified"))
        }
        mutableState.update { it.copy(entries = entries, loaded = true, offset = offset,
            warnings = response.getInt("warnings"), hasNext = response.getBoolean("next"), revision = it.revision + 1) }
    }

    fun request(entry: VaultMod) { if (!mutableState.value.busy) mutableState.update { it.copy(request = entry, issue = null, result = null) } }
    fun dismissRequest() { mutableState.update { it.copy(request = null) } }
    fun dismissResult() { mutableState.update { it.copy(result = null) } }

    fun confirm(library: GameLibraryViewModel) {
        val entry = mutableState.value.request ?: return
        if (mutableState.value.busy) return
        mutableState.update { it.copy(request = null, busy = true, issue = null, result = null) }
        viewModelScope.launch {
            try {
                val response = library.toggleMod(entry).getString("status")
                // Refresh even after an error: an interrupted move may leave a recoverable vault copy.
                load(library, 0)
                mutableState.update { if (response == "ok") it.copy(result = if (entry.id.isEmpty()) "disabled" else "enabled")
                    else it.copy(issue = response) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(issue = "incomplete") } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
