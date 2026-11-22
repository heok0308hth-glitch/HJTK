package com.hjtk.app.core.plan

import com.hjtk.app.core.Clock
import com.hjtk.app.core.storage.JsonCodec
import com.hjtk.app.core.storage.TextStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Latency that adapts to this device, not to a benchmark.
 *
 * Each command keeps an EWMA of its wall-clock time plus a sample count. The
 * estimate shown before a plan runs is optimistic until enough samples exist,
 * and it is replaced by the observed value afterwards, so a "5분 남음" promise
 * gets more honest the more the app is used.
 */
class EtaEstimator(
    private val storage: TextStorage,
    private val clock: Clock
) {
    @Serializable
    private data class Stat(val avgMs: Double = 0.0, val samples: Int = 0, val lastMs: Long = 0)

    @Serializable
    private data class Payload(val stats: Map<String, Stat> = emptyMap())

    private val fileName = "eta.json"
    private val lock = Any()
    private val stats = HashMap<String, Stat>()

    private val defaults: Map<String, Int> = mapOf(
        "search.web" to 2600,
        "search.news" to 2600,
        "search.wiki" to 1800,
        "search.page_summary" to 3000,
        "ai.ask" to 9000,
        "ai.deepseek" to 9000,
        "ai.gemini" to 9000,
        "ai.summarize" to 9000,
        "ai.translate" to 7000,
        "ai.rewrite" to 7000,
        "ai.compare" to 16000,
        "mail.unread" to 2200,
        "mail.latest" to 3500,
        "mail.send" to 6000,
        "calendar.today" to 900,
        "app.open" to 700,
        "app.list" to 600,
        "device.battery" to 300,
        "device.storage" to 300,
        "device.network" to 300,
        "system.diagnostics" to 600,
        "file.export_text" to 400,
        "file.export_session" to 500,
        "memory.recall" to 200,
        "memory.list" to 200
    )

    init {
        val raw = storage.read(fileName)
        if (raw != null) {
            runCatching { JsonCodec.json.decodeFromString<Payload>(raw) }.getOrNull()?.let {
                synchronized(lock) { stats.putAll(it.stats) }
            }
        }
    }

    fun estimateSeconds(commandId: String): Int {
        val stat = synchronized(lock) { stats[commandId] }
        val ms = if (stat != null && stat.samples >= 3) stat.avgMs else (defaults[commandId]?.toDouble() ?: 3000.0)
        return ((ms / 1000.0).toInt()).coerceAtLeast(1)
    }

    fun observe(commandId: String, tookMs: Long) {
        synchronized(lock) {
            val prev = stats[commandId] ?: Stat()
            val alpha = if (prev.samples < 3) 0.6 else 0.25
            val avg = if (prev.samples == 0) tookMs.toDouble() else prev.avgMs * (1 - alpha) + tookMs * alpha
            stats[commandId] = Stat(avgMs = avg, samples = prev.samples + 1, lastMs = tookMs)
            storage.write(fileName, JsonCodec.json.encodeToString(Payload(stats.toMap())))
        }
    }

    fun summary(): String = synchronized(lock) {
        if (stats.isEmpty()) "측정된 명령 실행 기록이 아직 없습니다."
        else stats.entries.sortedByDescending { it.value.avgMs }.take(8)
            .joinToString("\n") { (id, s) -> "· $id: 평균 ${(s.avgMs / 1000.0)}초 (${s.samples}회)" }
    }
}
