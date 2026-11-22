package com.hjtk.app.core.session

import com.hjtk.app.core.Clock
import com.hjtk.app.core.model.SessionSummary
import com.hjtk.app.core.storage.JsonCodec
import com.hjtk.app.core.storage.TextStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class StoredMessage(
    val id: String,
    /** "user" | "assistant" | "system" */
    val role: String,
    val text: String,
    val title: String = "",
    val at: Long = 0L
)

@Serializable
data class SessionRecord(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<StoredMessage> = emptyList()
)

/**
 * Multiple conversations, each persisted as its own record. A "session" is the
 * unit the chat screen switches between; conversation export happens here so
 * the UI never has to know the file format.
 */
class SessionStore(
    private val storage: TextStorage,
    private val clock: Clock
) {
    @Serializable
    private data class Payload(
        val currentId: String = "",
        val sessions: List<SessionRecord> = emptyList()
    )

    private val lock = Any()
    private var currentId: String = ""
    private val sessions = ArrayList<SessionRecord>()
    private val fileName = "sessions.json"

    init {
        load()
        if (sessions.isEmpty()) create("새 대화")
    }

    private fun load() {
        val raw = storage.read(fileName) ?: return
        val parsed = runCatching { JsonCodec.json.decodeFromString<Payload>(raw) }.getOrNull() ?: return
        synchronized(lock) {
            sessions.clear()
            sessions.addAll(parsed.sessions)
            currentId = parsed.currentId
        }
    }

    private fun persistLocked() {
        storage.write(fileName, JsonCodec.json.encodeToString(Payload(currentId, sessions.toList())))
    }

    fun current(): SessionRecord {
        synchronized(lock) {
            val found = sessions.firstOrNull { it.id == currentId }
            if (found != null) return found
            val created = SessionRecord(
                id = "s" + clock.nowMs().toString(36),
                title = "새 대화",
                createdAt = clock.nowMs(),
                updatedAt = clock.nowMs()
            )
            sessions.add(created)
            currentId = created.id
            persistLocked()
            return created
        }
    }

    fun create(title: String = "새 대화"): SessionRecord {
        synchronized(lock) {
            val record = SessionRecord(
                id = "s" + clock.nowMs().toString(36) + sessions.size,
                title = title,
                createdAt = clock.nowMs(),
                updatedAt = clock.nowMs()
            )
            sessions.add(record)
            currentId = record.id
            persistLocked()
            return record
        }
    }

    fun switchTo(id: String): Boolean {
        synchronized(lock) {
            if (sessions.none { it.id == id }) return false
            currentId = id
            persistLocked()
            return true
        }
    }

    fun append(role: String, text: String, title: String = ""): StoredMessage {
        synchronized(lock) {
            val msg = StoredMessage(
                id = "m" + clock.nowMs().toString(36) + sessions.sumOf { it.messages.size },
                role = role,
                text = text,
                title = title,
                at = clock.nowMs()
            )
            val idx = sessions.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
            val rec = sessions[idx]
            sessions[idx] = rec.copy(
                messages = rec.messages + msg,
                updatedAt = msg.at,
                title = if (rec.title == "새 대화" && role == "user" && text.isNotBlank()) {
                    text.trim().take(28)
                } else rec.title
            )
            persistLocked()
            return msg
        }
    }

    fun summary(): List<SessionSummary> {
        synchronized(lock) {
            return sessions.sortedByDescending { it.updatedAt }.map {
                SessionSummary(it.id, it.title, it.createdAt, it.updatedAt, it.messages.size)
            }
        }
    }

    fun record(id: String): SessionRecord? = synchronized(lock) { sessions.firstOrNull { it.id == id } }

    fun rename(id: String, title: String) {
        synchronized(lock) {
            val idx = sessions.indexOfFirst { it.id == id }
            if (idx >= 0) {
                sessions[idx] = sessions[idx].copy(title = title.trim().ifBlank { "새 대화" }, updatedAt = clock.nowMs())
                persistLocked()
            }
        }
    }

    fun delete(id: String): Boolean {
        synchronized(lock) {
            if (sessions.size <= 1) return false
            val removed = sessions.removeAll { it.id == id }
            if (removed) {
                if (currentId == id) currentId = sessions.first().id
                persistLocked()
            }
            return removed
        }
    }

    private fun stamp(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(at))

    fun exportMarkdown(id: String = currentId): String {
        val rec = record(id) ?: return "# 대화\n\n세션을 찾을 수 없습니다.\n"
        return buildString {
            appendLine("# ${rec.title}")
            appendLine()
            appendLine("시작: ${stamp(rec.createdAt)}")
            appendLine("메시지: ${rec.messages.size}개")
            appendLine()
            for (m in rec.messages) {
                val who = when (m.role) {
                    "user" -> "나"
                    "assistant" -> "HJTK"
                    else -> "시스템"
                }
                appendLine("### $who · ${stamp(m.at)}")
                if (m.title.isNotBlank()) appendLine("**${m.title}**")
                appendLine()
                appendLine(m.text)
                appendLine()
            }
        }
    }

    fun exportJson(id: String = currentId): String {
        val rec = record(id) ?: return "{}"
        return JsonCodec.pretty.encodeToString(rec)
    }
}
