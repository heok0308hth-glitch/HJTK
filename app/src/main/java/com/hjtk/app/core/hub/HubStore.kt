package com.hjtk.app.core.hub

import com.hjtk.app.core.Clock
import com.hjtk.app.core.model.HubEntry
import com.hjtk.app.core.model.HubKind
import com.hjtk.app.core.storage.JsonCodec
import com.hjtk.app.core.storage.TextStorage
import com.hjtk.app.core.text.TextScore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Personal Hub: the handful of apps, sites, AI pages and commands this user
 * actually reaches for. Ordered by a small usage score, not by recency alone.
 */
class HubStore(
    private val storage: TextStorage,
    private val clock: Clock
) {
    @Serializable
    private data class Payload(val entries: List<HubEntry> = emptyList())

    private val lock = Any()
    private val items = ArrayList<HubEntry>()
    private val fileName = "hub.json"

    init {
        load()
        if (all().isEmpty()) seed()
    }

    private fun load() {
        val raw = storage.read(fileName) ?: return
        val parsed = runCatching { JsonCodec.json.decodeFromString<Payload>(raw) }.getOrNull() ?: return
        synchronized(lock) {
            items.clear()
            items.addAll(parsed.entries)
        }
    }

    private fun seed() {
        // Neutral starting points: public pages and platform app ids only.
        val defaults = listOf(
            HubEntry(kind = HubKind.AI, label = "DeepSeek", target = "https://chat.deepseek.com/", note = "웹 세션", pinned = true),
            HubEntry(kind = HubKind.AI, label = "Gemini", target = "https://gemini.google.com/app", note = "웹 세션", pinned = true),
            HubEntry(kind = HubKind.SITE, label = "네이버 메일", target = "https://mail.naver.com/", note = "웹"),
            HubEntry(kind = HubKind.SITE, label = "GitHub", target = "https://github.com/", note = "웹"),
            HubEntry(kind = HubKind.COMMAND, label = "오늘 일정", target = "calendar.today", note = "명령")
        )
        synchronized(lock) {
            items.addAll(defaults)
            persistLocked()
        }
    }

    private fun persistLocked() {
        storage.write(fileName, JsonCodec.json.encodeToString(Payload(items.toList())))
    }

    fun all(): List<HubEntry> = synchronized(lock) { items.toList() }

    fun byKind(kind: HubKind): List<HubEntry> = synchronized(lock) { items.filter { it.kind == kind } }

    fun byId(id: String): HubEntry? = synchronized(lock) { items.firstOrNull { it.id == id } }

    /** Pinned first, then a blend of use count and recency. */
    fun ranked(): List<HubEntry> = synchronized(lock) {
        val now = clock.nowMs()
        items.sortedWith(
            compareByDescending<HubEntry> { it.pinned }
                .thenByDescending {
                    val ageDays = ((now - it.lastUsedAt).coerceAtLeast(0L) / 86_400_000.0)
                    it.useCount.toDouble() * 10.0 - ageDays.coerceAtMost(30.0) * 0.5
                }
                .thenBy { it.label }
        )
    }

    fun add(kind: HubKind, label: String, target: String, note: String = ""): HubEntry {
        val entry = HubEntry(kind = kind, label = label.trim(), target = target.trim(), note = note.trim())
        synchronized(lock) {
            items.removeAll { it.kind == kind && it.target.equals(target.trim(), ignoreCase = true) }
            items.add(entry)
            persistLocked()
        }
        return entry
    }

    fun remove(id: String): Boolean {
        synchronized(lock) {
            val removed = items.removeAll { it.id == id }
            if (removed) persistLocked()
            return removed
        }
    }

    fun togglePin(id: String) {
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) {
                items[idx] = items[idx].copy(pinned = !items[idx].pinned)
                persistLocked()
            }
        }
    }

    fun markUsed(id: String) {
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) {
                items[idx] = items[idx].copy(useCount = items[idx].useCount + 1, lastUsedAt = clock.nowMs())
                persistLocked()
            }
        }
    }

    fun search(query: String): List<HubEntry> {
        val snapshot = ranked()
        if (query.isBlank()) return snapshot
        return snapshot
            .map { it to TextScore.score(query, it.label + " " + it.target + " " + it.note) }
            .filter { it.second > 0.3 }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}
