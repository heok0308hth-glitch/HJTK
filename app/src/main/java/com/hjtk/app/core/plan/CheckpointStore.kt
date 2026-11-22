package com.hjtk.app.core.plan

import com.hjtk.app.core.storage.JsonCodec
import com.hjtk.app.core.storage.TextStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class StepMemory(
    val output: String = "",
    val data: Map<String, String> = emptyMap()
)

@Serializable
private data class CheckpointPayload(
    val planId: String = "",
    val title: String = "",
    val steps: Map<String, StepMemory> = emptyMap(),
    val updatedAt: Long = 0L
)

/**
 * Checkpoints step results so a resumed plan does not repeat a mail send or an
 * app launch. One file per plan, because several plans can run at once; lives
 * in app-private storage and is deleted when the plan finishes.
 */
class CheckpointStore(private val storage: TextStorage) {

    private fun name(planId: String) = "checkpoint-$planId.json"

    fun save(planId: String, title: String, steps: Map<String, StepMemory>) {
        storage.write(name(planId), JsonCodec.json.encodeToString(CheckpointPayload(planId, title, steps)))
    }

    fun load(planId: String): Map<String, StepMemory> {
        val raw = storage.read(name(planId)) ?: return emptyMap()
        val parsed = runCatching { JsonCodec.json.decodeFromString<CheckpointPayload>(raw) }.getOrNull() ?: return emptyMap()
        return if (parsed.planId == planId) parsed.steps else emptyMap()
    }

    fun clear(planId: String) {
        storage.delete(name(planId))
    }
}
