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

data class ScanFinding(val index: Int, val path: String, val issue: String, val reference: String,
    val bytes: Long, val selectable: Boolean, val retained: Boolean)

data class ScanPage(val token: String, val files: Int, val total: Int, val partial: Boolean,
    val offset: Int, val next: Boolean, val entries: List<ScanFinding>)

data class QuarantinedFile(val id: String, val path: String, val bytes: Long, val state: String)

data class QuarantinePage(val total: Int, val warnings: Int, val offset: Int, val next: Boolean,
    val entries: List<QuarantinedFile>)

data class ScanRequest(val indices: List<Int> = emptyList(), val file: QuarantinedFile? = null)

data class FileScanState(val tab: Int = 0, val busy: Boolean = false, val scan: ScanPage? = null,
    val vault: QuarantinePage? = null, val selection: Set<Int> = emptySet(), val request: ScanRequest? = null,
    val issue: String? = null, val moved: Int = 0, val restored: Boolean = false)

class FileScanViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(FileScanState())
    val state = mutableState.asStateFlow()

    private class ScanFailure(val status: String) : Exception()

    private fun checked(response: JSONObject): JSONObject {
        val status = response.getString("status")
        if (status != "ok") throw ScanFailure(status)
        return response
    }

    private fun run(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, issue = null, moved = 0, restored = false) }
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val status = (error as? ScanFailure)?.status ?: "incomplete"
                mutableState.update {
                    if (status == "expired") it.copy(issue = status, scan = null, selection = emptySet())
                    else it.copy(issue = status)
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun parseScan(response: JSONObject): ScanPage {
        checked(response)
        val entries = response.getJSONArray("entries")
        val rows = List(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            ScanFinding(item.getInt("index"), item.getString("path"), item.getString("issue"),
                item.getString("reference"), item.getLong("size"), item.getBoolean("selectable"), item.getBoolean("retained"))
        }
        return ScanPage(response.getString("token"), response.getInt("files"), response.getInt("total"),
            response.getBoolean("partial"), response.getInt("offset"), response.getBoolean("next"), rows)
    }

    private fun updateScan(response: JSONObject) { mutableState.update { it.copy(scan = parseScan(response)) } }

    fun start(library: GameLibraryViewModel) = run {
        mutableState.update { it.copy(scan = null, selection = emptySet(), request = null) }
        val page = parseScan(library.scanFiles("start"))
        library.adBreak(com.spinel.melkit.ads.AdPlacement.SCAN) { mutableState.update { it.copy(scan = page) } }
    }

    fun scanPage(library: GameLibraryViewModel, offset: Int) {
        val token = mutableState.value.scan?.token ?: return
        run { updateScan(library.scanFiles("page", JSONObject().put("token", token).put("offset", offset))) }
    }

    fun selectTab(tab: Int) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(tab = tab, issue = null) }
    }

    private suspend fun loadVault(library: GameLibraryViewModel, offset: Int) {
        val response = checked(library.scanFiles("vault", JSONObject().put("offset", offset)))
        val entries = response.getJSONArray("entries")
        val rows = List(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            QuarantinedFile(item.getString("id"), item.getString("path"), item.getLong("size"), item.getString("state"))
        }
        val page = QuarantinePage(response.getInt("total"), response.getInt("warnings"),
            response.getInt("offset"), response.getBoolean("next"), rows)
        mutableState.update { it.copy(vault = page) }
    }

    fun vaultPage(library: GameLibraryViewModel, offset: Int = 0) = run { loadVault(library, offset) }

    fun select(entry: ScanFinding) {
        if (!entry.selectable || mutableState.value.busy) return
        mutableState.update {
            val selection = when {
                entry.index in it.selection -> it.selection - entry.index
                it.selection.size < PAGE_SIZE -> it.selection + entry.index
                else -> it.selection
            }
            it.copy(selection = selection)
        }
    }

    fun requestQuarantine() {
        mutableState.update {
            if (it.busy || it.scan == null || it.selection.isEmpty()) it
            else it.copy(request = ScanRequest(indices = it.selection.sorted()))
        }
    }

    fun requestRestore(file: QuarantinedFile) {
        mutableState.update { if (it.busy) it else it.copy(request = ScanRequest(file = file)) }
    }

    fun dismiss() { mutableState.update { it.copy(request = null) } }

    fun confirm(library: GameLibraryViewModel) {
        val request = mutableState.value.request ?: return
        val token = mutableState.value.scan?.token
        dismiss()
        run {
            if (request.file != null) {
                checked(library.scanFiles("restore", JSONObject().put("id", request.file.id)))
                mutableState.update { it.copy(restored = true, scan = null, selection = emptySet(), vault = null) }
                loadVault(library, 0)
            } else if (token != null) {
                val response = library.scanFiles("quarantine", JSONObject().put("token", token)
                    .put("indices", JSONArray(request.indices)))
                val moved = response.optInt("moved")
                mutableState.update { it.copy(moved = if (response.optString("status") == "ok") 0 else moved,
                    scan = null, selection = emptySet(), vault = null) }
                checked(response)
                val publish = { mutableState.update { it.copy(moved = moved) } }
                if (moved == request.indices.size && request.indices.isNotEmpty()) library.adBreak(com.spinel.melkit.ads.AdPlacement.CLEAN, publish)
                else publish()
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 40
    }
}
