package com.hjtk.app.core.protocol

/**
 * RRE — Response Reconciliation Engine.
 *
 * A model answer can arrive three ways: as a stream of deltas, as a periodic
 * snapshot of the visible conversation, or as both at once. Snapshot text may
 * already contain earlier turns; delta text may be cumulative rather than
 * incremental. Naively concatenating either produces duplicated or missing
 * text, which is the single most common bug in bridge apps.
 *
 * RRE treats every arrival as a *candidate*, scores how complete it is, and
 * commits one reconciled text. Conflicts are surfaced instead of hidden: the
 * confidence drop is what the developer console shows and what makes the app
 * ask for a snapshot refresh.
 */
class ResponseReconciler {

    data class Reconciled(
        val text: String,
        val confidence: Double,
        /** "deltas" | "snapshot" | "snapshot+deltas" | "none" */
        val source: String,
        val conflicted: Boolean,
        val note: String = ""
    )

    /**
     * @param snapshot latest full snapshot of the answer area (may be null)
     * @param deltas every delta received so far, in arrival order
     */
    fun reconcile(snapshot: String?, deltas: List<String>): Reconciled {
        val cleanSnapshot = snapshot?.trim().orEmpty()
        val joined = buildFromDeltas(deltas)

        if (joined.isBlank() && cleanSnapshot.isBlank()) {
            return Reconciled("", 0.0, "none", false, "수신된 텍스트가 없습니다.")
        }
        if (joined.isBlank()) {
            // snapshot only: complete enough to show, but unverified
            return Reconciled(cleanSnapshot, 0.62, "snapshot", false, "스트림 없이 스냅샷만 수신")
        }
        if (cleanSnapshot.isBlank()) {
            return Reconciled(joined, 0.80, "deltas", false, "스냅샷 없이 델타만 수신")
        }

        val snapNorm = normalize(cleanSnapshot)
        val deltasNorm = normalize(joined)
        return when {
            snapNorm == deltasNorm -> Reconciled(joined, 0.99, "snapshot+deltas", false)
            snapNorm.endsWith(deltasNorm) -> Reconciled(joined, 0.94, "snapshot+deltas", false, "스냅샷이 델타를 포함")
            deltasNorm.endsWith(snapNorm) -> Reconciled(joined, 0.90, "snapshot+deltas", false, "델타가 더 김")
            snapNorm.contains(deltasNorm) -> Reconciled(joined, 0.88, "snapshot+deltas", false)
            else -> Reconciled(joined, 0.55, "snapshot+deltas", true, "스냅샷과 델타가 불일치 — 재확인 필요")
        }
    }

    /**
     * Adds one delta to the running text. Handles both incremental chunks and
     * cumulative frames (the model re-sending the whole answer each time).
     */
    fun appendDelta(previous: String, incoming: String): String {
        if (incoming.isEmpty()) return previous
        if (previous.isEmpty()) return incoming
        if (incoming == previous) return previous
        if (incoming.startsWith(previous)) return incoming          // cumulative
        if (previous.endsWith(incoming)) return previous            // repeated tail
        val overlap = longestSuffixPrefix(previous, incoming)
        return if (overlap >= minOf(3, incoming.length)) previous + incoming.substring(overlap) else previous + incoming
    }

    private fun buildFromDeltas(deltas: List<String>): String {
        var acc = ""
        for (d in deltas) acc = appendDelta(acc, d)
        return acc.trim()
    }

    /** Whitespace-insensitive comparison, keeps text otherwise intact. */
    private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun longestSuffixPrefix(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        for (len in max downTo 1) {
            if (a.regionMatches(a.length - len, b, 0, len)) return len
        }
        return 0
    }
}
