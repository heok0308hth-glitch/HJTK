package com.hjtk.app.core.plan

import com.hjtk.app.core.Clock
import com.hjtk.app.core.storage.JsonCodec
import com.hjtk.app.core.storage.TextStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.MessageDigest

@Serializable
data class JournalRecord(
    val seq: Long,
    val at: Long,
    /** plan_started | step_started | approval | step_finished | plan_finished */
    val kind: String,
    val planId: String,
    val stepId: String = "",
    val commandId: String = "",
    /** Hash of the step's arguments or output — never the values themselves. */
    val digest: String = "",
    val detail: String = "",
    val prevHash: String,
    val hash: String
)

data class JournalVerification(val ok: Boolean, val records: Int, val brokenAt: Long = -1, val reason: String = "")

/**
 * Append-only write-ahead log with a hash chain.
 *
 * Every state transition is one line. Because each record hashes the previous
 * one, a truncated or edited history is detectable (`verify()`), and because
 * `plan_started` without a matching `plan_finished` is visible on startup, a
 * killed process can offer an honest "이어서 실행" instead of silently losing
 * work. Arguments and results are stored only as digests.
 */
class Journal(
    private val storage: TextStorage,
    private val clock: Clock
) {
    companion object {
        const val FILE = "journal.jsonl"
        private const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

        fun digest(text: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private val lock = Any()
    private val cache = ArrayList<JournalRecord>()

    /** True when a torn final line was cut off during load. */
    @Volatile
    var healedTail: Boolean = false
        private set

    init {
        loadAndHeal()
    }

    /**
     * Reads the log and cuts a half-written final line.
     *
     * A power loss can leave `{"seq":12,...` without its closing brace. Keeping
     * that byte range would corrupt the next append, so the file is truncated
     * exactly at the end of the last complete record and the event is recorded.
     */
    private fun loadAndHeal() {
        val raw = storage.read(FILE) ?: return
        var offset = 0L
        var expectedPrev: String? = null
        for (line in raw.split('\n')) {
            val lineBytes = line.toByteArray(Charsets.UTF_8).size.toLong() + 1
            if (line.isBlank()) {
                offset += lineBytes
                continue
            }
            val record = runCatching { JsonCodec.json.decodeFromString<JournalRecord>(line) }.getOrNull()
            if (record == null) {
                storage.truncate(FILE, offset)
                healedTail = true
                break
            }
            if (expectedPrev != null && record.prevHash != expectedPrev) {
                // Someone removed or reordered records in the middle: keep the
                // trusted prefix and stop reading.
                storage.truncate(FILE, offset)
                healedTail = true
                break
            }
            cache.add(record)
            expectedPrev = record.hash
            offset += lineBytes
        }
    }

    private fun lastHashLocked(): String = cache.lastOrNull()?.hash ?: GENESIS

    fun append(
        kind: String,
        planId: String,
        stepId: String = "",
        commandId: String = "",
        digest: String = "",
        detail: String = ""
    ): JournalRecord {
        synchronized(lock) {
            val seq = (cache.lastOrNull()?.seq ?: 0L) + 1
            val at = clock.nowMs()
            val prev = lastHashLocked()
            val payload = listOf(prev, seq.toString(), at.toString(), kind, planId, stepId, commandId, digest, detail).joinToString("|")
            val record = JournalRecord(
                seq = seq,
                at = at,
                kind = kind,
                planId = planId,
                stepId = stepId,
                commandId = commandId,
                digest = digest,
                detail = detail,
                prevHash = prev,
                hash = digest(payload)
            )
            cache.add(record)
            storage.append(FILE, JsonCodec.json.encodeToString(record))
            return record
        }
    }

    fun records(): List<JournalRecord> = synchronized(lock) { cache.toList() }

    fun tail(n: Int): List<JournalRecord> = records().takeLast(n)

    fun verify(): JournalVerification {
        val all = records()
        var prev = GENESIS
        for (r in all) {
            if (r.prevHash != prev) {
                return JournalVerification(false, all.size, r.seq, "체인 끊김: seq=${r.seq}")
            }
            val payload = listOf(prev, r.seq.toString(), r.at.toString(), r.kind, r.planId, r.stepId, r.commandId, r.digest, r.detail).joinToString("|")
            if (digest(payload) != r.hash) {
                return JournalVerification(false, all.size, r.seq, "해시 불일치: seq=${r.seq}")
            }
            prev = r.hash
        }
        return JournalVerification(true, all.size)
    }

    /** A plan that started but never finished — the resume candidate. */
    fun openPlan(): OpenPlan? {
        val all = records()
        val openIds = LinkedHashSet<String>()
        val lastActivity = HashMap<String, Long>()
        for (r in all) {
            when (r.kind) {
                "plan_started" -> {
                    openIds.add(r.planId)
                    lastActivity[r.planId] = r.at
                }
                "plan_finished" -> {
                    openIds.remove(r.planId)
                    lastActivity.remove(r.planId)
                }
                "step_finished" -> lastActivity[r.planId] = r.at
            }
        }
        val id = openIds.lastOrNull() ?: return null
        val steps = all.filter { it.planId == id && it.kind == "step_finished" }.map { it.stepId }
        val started = all.firstOrNull { it.planId == id && it.kind == "plan_started" } ?: return null
        return OpenPlan(
            planId = id,
            title = started.detail,
            completedSteps = steps,
            lastAt = lastActivity[id] ?: started.at
        )
    }

    fun countByKind(): Map<String, Int> = records().groupingBy { it.kind }.eachCount()
}

data class OpenPlan(
    val planId: String,
    val title: String,
    val completedSteps: List<String>,
    val lastAt: Long
)
