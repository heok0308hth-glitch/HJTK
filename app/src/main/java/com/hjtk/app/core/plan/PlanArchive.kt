package com.hjtk.app.core.plan

import com.hjtk.app.core.storage.JsonCodec
import com.hjtk.app.core.storage.TextStorage
import com.hjtk.app.core.model.Plan
import kotlinx.serialization.encodeToString

/**
 * Keeps the plan that is currently running on disk.
 *
 * The journal says *that* a plan was open; this says *what it was*, so after a
 * process death the same steps (with their arguments) can be rebuilt and run
 * again from the checkpoint. Without it "이어서 실행" could only be a promise.
 */
class PlanArchive(private val storage: TextStorage) {

    private fun name(planId: String) = "plan-$planId.json"

    fun save(plan: Plan) {
        storage.write(name(plan.id), JsonCodec.json.encodeToString(plan))
    }

    fun load(planId: String): Plan? {
        val raw = storage.read(name(planId)) ?: return null
        return runCatching { JsonCodec.json.decodeFromString<Plan>(raw) }.getOrNull()
    }

    fun clear(planId: String) {
        storage.delete(name(planId))
    }
}
