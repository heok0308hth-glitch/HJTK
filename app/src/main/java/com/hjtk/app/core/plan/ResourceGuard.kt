package com.hjtk.app.core.plan

import com.hjtk.app.core.model.PlanStep
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises only what cannot run twice at the same time.
 *
 * Local commands (memory, files, device reads), web fetches and mail reads are
 * independent and run in parallel. What must not overlap: two prompts to the
 * same model session, two mail transports on one account, or two system UI
 * handoffs (only one chooser can be on screen). Everything else is free.
 */
class ResourceGuard {

    private val locks = ConcurrentHashMap<String, Mutex>()

    companion object {
        /** null means "no shared resource — safe to run in parallel". */
        fun resourceOf(commandId: String): String? = when {
            commandId.startsWith("ai.deepseek") -> "carrier:deepseek"
            commandId.startsWith("ai.gemini") -> "carrier:gemini"
            commandId == "ai.ask" || commandId == "ai.compare" -> "carrier:any"
            commandId.startsWith("ai.") -> "carrier:any"
            commandId.startsWith("mail.") -> "mail"
            commandId.startsWith("app.") -> "system-ui"
            commandId == "calendar.new_event" || commandId == "calendar.open" -> "system-ui"
            commandId.startsWith("notify.") -> "system-ui"
            commandId == "file.share_export" || commandId == "file.open_folder" -> "system-ui"
            commandId.startsWith("hub.open") -> "system-ui"
            else -> null
        }
    }

    private fun mutex(key: String): Mutex = locks.getOrPut(key) { Mutex() }

    suspend fun <T> withResource(commandId: String, block: suspend () -> T): T {
        val key = resourceOf(commandId) ?: return block()
        return mutex(key).withLock { block() }
    }

    fun heldKeys(): List<String> = locks.filterValues { it.isLocked }.keys.toList()

    /** Helper for the executor: acquire nothing, used by tests. */
    suspend fun noop(step: PlanStep) = Unit
}
