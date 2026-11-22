package com.hjtk.app.core.skill

import com.hjtk.app.core.Clock
import com.hjtk.app.core.model.Pipeline
import com.hjtk.app.core.model.SkillStep
import com.hjtk.app.core.storage.JsonCodec
import com.hjtk.app.core.storage.TextStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Named, re-runnable multi-step flows shown in the Work tab. */
class PipelineStore(
    private val storage: TextStorage,
    private val clock: Clock
) {
    @Serializable
    private data class Payload(val pipelines: List<Pipeline> = emptyList())

    private val lock = Any()
    private val items = ArrayList<Pipeline>()
    private val fileName = "pipelines.json"

    init {
        load()
    }

    private fun load() {
        val raw = storage.read(fileName) ?: return
        val parsed = runCatching { JsonCodec.json.decodeFromString<Payload>(raw) }.getOrNull() ?: return
        synchronized(lock) {
            items.clear()
            items.addAll(parsed.pipelines)
        }
    }

    private fun persistLocked() {
        storage.write(fileName, JsonCodec.json.encodeToString(Payload(items.toList())))
    }

    fun all(): List<Pipeline> = synchronized(lock) { items.sortedByDescending { it.lastRunAt } }

    fun byId(id: String): Pipeline? = synchronized(lock) { items.firstOrNull { it.id == id } }

    fun byName(name: String): Pipeline? = synchronized(lock) {
        items.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?: items.firstOrNull { it.name.contains(name.trim(), ignoreCase = true) }
    }

    fun create(
        name: String,
        description: String,
        steps: List<SkillStep>,
        params: List<com.hjtk.app.core.model.PipelineParam> = emptyList()
    ): Pipeline {
        val p = Pipeline(
            name = name.trim(),
            description = description.trim(),
            params = params,
            steps = steps
        )
        synchronized(lock) {
            items.add(p)
            persistLocked()
        }
        return p
    }

    fun update(pipeline: Pipeline) {
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == pipeline.id }
            if (idx >= 0) items[idx] = pipeline
            persistLocked()
        }
    }

    fun markRun(id: String, ok: Boolean) {
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) {
                items[idx] = items[idx].copy(lastRunAt = clock.nowMs(), lastRunOk = ok)
                persistLocked()
            }
        }
    }

    fun delete(id: String): Boolean {
        synchronized(lock) {
            val removed = items.removeAll { it.id == id }
            if (removed) persistLocked()
            return removed
        }
    }

    fun exportJson(): String = JsonCodec.pretty.encodeToString(items.toList())
}
