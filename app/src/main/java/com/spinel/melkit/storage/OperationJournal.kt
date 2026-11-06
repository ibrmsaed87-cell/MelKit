package com.spinel.melkit.storage

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal enum class OperationKind {
    COPY, RESTORE, QUARANTINE, RETURN_QUARANTINE, DISABLE_MOD, ENABLE_MOD, APPLY_GROUP, UNDO_GROUP,
    EDIT_GROUP, EDIT_CORE, DELETE_GROUP, ORGANIZE, EDIT_COVER,
}

internal data class OperationRecord(val id: String, val kind: OperationKind, val subject: String, val time: Long)

/** Local history for display. Live vaults and the group journal determine which actions are safe to offer. */
internal class OperationJournal private constructor(application: Application) {
    private val preferences = application.getSharedPreferences("operation-history", Context.MODE_PRIVATE)
    private val mutableRecords = MutableStateFlow(read())
    val records = mutableRecords.asStateFlow()

    fun add(kind: OperationKind, subject: String = "") = add(kind, listOf(subject))

    @Synchronized
    fun add(kind: OperationKind, subjects: List<String>) {
        if (subjects.isEmpty()) return
        val now = System.currentTimeMillis()
        val entries = (subjects.asReversed().map {
            OperationRecord(UUID.randomUUID().toString(), kind, it, now)
        } + mutableRecords.value).take(LIMIT)
        mutableRecords.value = entries
        runCatching {
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(JSONObject().put("id", entry.id).put("kind", entry.kind.name)
                    .put("subject", entry.subject).put("time", entry.time))
            }
            preferences.edit().putString("records", array.toString()).apply()
        }
    }

    private fun read(): List<OperationRecord> = runCatching {
        val array = JSONArray(preferences.getString("records", "[]") ?: "[]")
        (0 until minOf(array.length(), LIMIT)).mapNotNull { index ->
            runCatching {
                val entry = array.getJSONObject(index)
                OperationRecord(entry.getString("id"), OperationKind.valueOf(entry.getString("kind")),
                    entry.getString("subject"), entry.getLong("time"))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val LIMIT = 200
        private var instance: OperationJournal? = null

        @Synchronized
        fun get(application: Application): OperationJournal = instance ?: OperationJournal(application).also { instance = it }
    }
}
