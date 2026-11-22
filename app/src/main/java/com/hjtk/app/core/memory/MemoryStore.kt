package com.hjtk.app.core.memory

import com.hjtk.app.core.Clock
import com.hjtk.app.core.model.MemoryEntry
import com.hjtk.app.core.storage.JsonCodec
import com.hjtk.app.core.storage.TextStorage
import com.hjtk.app.core.text.TextScore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Long-term memory without a neural net: user facts are stored, scored by
 * keyword overlap and injected into prompts only when relevant.
 */
class MemoryStore(
    private val storage: TextStorage,
    private val clock: Clock
) {
    @Serializable
    private data class Payload(val entries: List<MemoryEntry> = emptyList())

    private val lock = Any()
    private val items = ArrayList<MemoryEntry>()
    private val fileName = "memory.json"

    init {
        load()
    }

    private fun load() {
        val raw = storage.read(fileName) ?: return
        val parsed = runCatching { JsonCodec.json.decodeFromString<Payload>(raw) }.getOrNull() ?: return
        synchronized(lock) {
            items.clear()
            items.addAll(parsed.entries)
        }
    }

    private fun persistLocked() {
        storage.write(fileName, JsonCodec.json.encodeToString(Payload(items.toList())))
    }

    fun all(): List<MemoryEntry> = synchronized(lock) { items.sortedByDescending { it.createdAt } }

    fun size(): Int = synchronized(lock) { items.size }

    /** Upserts on key: remembering the same key twice keeps one entry. */
    fun remember(key: String, value: String, tags: List<String> = emptyList(), source: String = "user"): MemoryEntry {
        val cleanKey = key.trim().ifBlank { value.take(24) }
        val cleanTags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        synchronized(lock) {
            val existing = items.indexOfFirst { it.key.equals(cleanKey, ignoreCase = true) }
            val entry = if (existing >= 0) {
                items[existing].copy(value = value.trim(), tags = cleanTags, source = source)
                    .also { items[existing] = it }
            } else {
                MemoryEntry(
                    key = cleanKey,
                    value = value.trim(),
                    tags = cleanTags,
                    createdAt = clock.nowMs(),
                    source = source
                ).also { items.add(it) }
            }
            persistLocked()
            return entry
        }
    }

    fun forget(query: String): Int {
        synchronized(lock) {
            val before = items.size
            items.removeAll { it.id == query || it.key.equals(query, ignoreCase = true) }
            if (items.size != before) persistLocked()
            return before - items.size
        }
    }

    fun forgetId(id: String): Boolean {
        synchronized(lock) {
            val removed = items.removeAll { it.id == id }
            if (removed) persistLocked()
            return removed
        }
    }

    fun recall(query: String, limit: Int = 5): List<MemoryEntry> {
        val snapshot = all()
        if (query.isBlank()) return snapshot.take(limit)
        return snapshot
            .map { it to TextScore.bestScore(query, it.searchable) }
            .filter { it.second >= 0.55 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** Compact context block for an AI prompt; empty when nothing matches. */
    fun contextFor(query: String, limit: Int = 4): String {
        val hits = recall(query, limit)
        if (hits.isEmpty()) return ""
        return hits.joinToString("\n") { "- ${it.key}: ${it.value}" }
    }

    fun exportMarkdown(): String {
        if (items.isEmpty()) return "# 기억\n\n아직 저장된 기억이 없습니다.\n"
        return buildString {
            appendLine("# 기억")
            appendLine()
            for (e in all()) {
                appendLine("## ${e.key}")
                appendLine()
                appendLine(e.value)
                if (e.tags.isNotEmpty()) appendLine()
                if (e.tags.isNotEmpty()) appendLine("태그: " + e.tags.joinToString(", "))
                appendLine()
            }
        }
    }
}
