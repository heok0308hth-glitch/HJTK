package com.hjtk.app.core.text

/**
 * Deliberately dumb text matching: no model, no embeddings, no network.
 *
 * Korean queries glue particles onto nouns ("메일을", "일정은"), so exact token
 * equality is not enough; a token also scores when it appears as a substring of
 * a word in the haystack. Character bigrams give partial credit for near
 * misses. The weights are tuned by the router tests.
 */
object TextScore {

    private val splitter = Regex("[^\\p{L}\\p{N}]+")

    fun tokens(text: String): List<String> =
        text.lowercase().split(splitter).filter { it.isNotBlank() }

    /** 0.0 .. 1.0-ish relevance of [query] against [haystack]. */
    fun score(query: String, haystack: String): Double {
        val q = tokens(query).filter { it.length > 0 }
        if (q.isEmpty()) return 0.0
        val h = haystack.lowercase()
        val hTokens = tokens(haystack).toSet()
        var total = 0.0
        for (t in q) {
            total += when {
                hTokens.contains(t) -> 1.0
                h.contains(t) -> 0.85
                t.length >= 3 && h.contains(t.take(3)) -> 0.45
                else -> bigramOverlap(t, h)
            }
        }
        return total / q.size
    }

    private fun bigramOverlap(token: String, haystack: String): Double {
        if (token.length < 2) return 0.0
        var hit = 0
        var n = 0
        for (i in 0 until token.length - 1) {
            n++
            if (haystack.contains(token.substring(i, i + 2))) hit++
        }
        if (n == 0) return 0.0
        val ratio = hit.toDouble() / n
        return if (ratio >= 0.75) 0.6 * ratio else 0.0
    }

    /**
     * Best single-token match rather than the average: a long query with one
     * strong keyword ("커피 뭐 좋아해") should still find "선호 커피".
     */
    fun bestScore(query: String, haystack: String): Double {
        val q = tokens(query)
        if (q.isEmpty()) return 0.0
        val h = haystack.lowercase()
        val hTokens = tokens(haystack).toSet()
        var best = 0.0
        var hits = 0
        for (t in q) {
            val s = when {
                hTokens.contains(t) -> 1.0
                h.contains(t) -> 0.9
                t.length >= 3 && h.contains(t.take(3)) -> 0.5
                else -> bigramOverlap(t, h) * 0.8
            }
            if (s >= 0.5) hits++
            best = maxOf(best, s)
        }
        return (best + 0.1 * (hits - 1).coerceAtLeast(0)).coerceAtMost(1.15)
    }

    fun containsAny(text: String, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val lower = text.lowercase()
        return words.any { w -> w.isNotBlank() && lower.contains(w.lowercase()) }
    }

    /** Counts how many of [words] appear in [text]. */
    fun hits(text: String, words: List<String>): Int {
        if (words.isEmpty()) return 0
        val lower = text.lowercase()
        return words.count { w -> w.isNotBlank() && lower.contains(w.lowercase()) }
    }
}
