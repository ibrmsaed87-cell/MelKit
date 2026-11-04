package com.spinel.melkit.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class ProfileCard(val id: String, val name: String, val count: Int, val active: Boolean)
data class ProfileChoice(val path: String, val name: String, val available: Boolean = true)
data class ProfileEditor(val id: String, val name: String, val selected: Set<String>,
    val choices: List<ProfileChoice>, val query: String = "")
data class ProfileRequest(val action: String, val id: String = "", val name: String = "")
data class ModProfilesState(val items: List<ProfileCard> = emptyList(), val coreCount: Int = 0,
    val loaded: Boolean = false, val busy: Boolean = false, val undo: Boolean = false, val recovery: Boolean = false, val warnings: Int = 0,
    val issue: String? = null, val result: String? = null, val editor: ProfileEditor? = null, val request: ProfileRequest? = null)

class ModProfilesViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(ModProfilesState())
    val state = mutableState.asStateFlow()

    private fun checked(response: JSONObject): JSONObject {
        val status = response.getString("status")
        if (status != "ok") throw ProfileFailure(status)
        return response
    }
    private suspend fun load(library: GameLibraryViewModel) {
        val response = checked(library.profiles("state"))
        val array = response.getJSONArray("items")
        val items = List(array.length()) {
            val item = array.getJSONObject(it)
            ProfileCard(item.getString("id"), item.getString("name"), item.getInt("count"), item.getBoolean("active"))
        }
        mutableState.update { it.copy(items = items, coreCount = response.getInt("core"),
            undo = response.getBoolean("undo"), recovery = response.getBoolean("recovery"), warnings = response.getInt("warnings"), loaded = true) }
    }
    private fun run(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, issue = null, result = null) }
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(issue = (error as? ProfileFailure)?.status ?: "incomplete") } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    fun refresh(library: GameLibraryViewModel) = run { load(library) }

    fun edit(library: GameLibraryViewModel, id: String = "") = run {
        val response = checked(library.profiles("editor", JSONObject().put("id", id)))
        val array = response.getJSONArray("paths")
        val selected = (0 until array.length()).map { array.getString(it) }.toSet()
        val choices = linkedMapOf<String, ProfileChoice>()
        var offset = 0
        var page = response
        while (true) {
            val entries = page.getJSONArray("entries")
            for (i in 0 until entries.length()) {
                val item = entries.getJSONObject(i)
                choices[item.getString("path")] = ProfileChoice(item.getString("path"), item.getString("name"))
            }
            if (!page.getBoolean("next")) break
            offset += 40
            if (offset > 10000) throw ProfileFailure("limit")
            page = checked(library.profiles("choices", JSONObject().put("token", response.getString("token")).put("offset", offset)))
        }
        selected.filter { it !in choices }.forEach { choices[it] = ProfileChoice(it, it.substringAfterLast('/'), false) }
        mutableState.update { it.copy(editor = ProfileEditor(response.getString("id"), response.getString("name"), selected,
            choices.values.sortedBy { it.path.lowercase() })) }
    }
    fun name(value: String) { mutableState.update { it.copy(editor = it.editor?.copy(name = value.take(80))) } }
    fun query(value: String) { mutableState.update { it.copy(editor = it.editor?.copy(query = value)) } }
    fun select(path: String) {
        mutableState.update { state -> state.copy(editor = state.editor?.let {
            it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path)
        }) }
    }
    fun closeEditor() { if (!mutableState.value.busy) mutableState.update { it.copy(editor = null, issue = null) } }
    fun save(library: GameLibraryViewModel) {
        val editor = mutableState.value.editor ?: return
        run {
            checked(library.profiles("save", JSONObject().put("id", editor.id).put("name", editor.name)
                .put("paths", JSONArray(editor.selected.toList()))))
            mutableState.update { it.copy(editor = null) }
            load(library)
            mutableState.update { it.copy(result = "save") }
        }
    }
    fun request(value: ProfileRequest) { if (!mutableState.value.busy) mutableState.update { it.copy(request = value) } }
    fun dismissRequest() { mutableState.update { it.copy(request = null) } }
    fun confirm(library: GameLibraryViewModel) {
        val request = mutableState.value.request ?: return
        mutableState.update { it.copy(request = null) }
        run {
            val response = library.profiles(request.action, JSONObject().put("id", request.id))
            // Read the journal even when switching fails so recovery controls become available.
            load(library)
            checked(response)
            mutableState.update { it.copy(result = request.action) }
        }
    }
    private class ProfileFailure(val status: String) : Exception()
}
