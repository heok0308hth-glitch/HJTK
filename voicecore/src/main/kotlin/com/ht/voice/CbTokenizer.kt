package com.ht.voice

/**
 * Chatterbox 다국어 토크나이저(BPE) — HF tokenizers와 같은 결과를 내도록 옮긴 것.
 * 자료 파일(cb_tok.txt): `V 토큰 번호` · `M 왼쪽 오른쪽`(병합 순서대로) · `A 특수토큰 번호`, 탭 구분.
 */
class CbTokenizer(lines: Sequence<String>) {
    private val vocab = HashMap<String, Int>(4096)
    private val rank = HashMap<String, Int>(512)
    private val added = ArrayList<Pair<String, Int>>()
    private val unk: Int

    init {
        var r = 0
        for (l in lines) {
            val p = l.split('\t')
            when (p.firstOrNull()) {
                "V" -> vocab[p[1]] = p[2].toInt()
                "M" -> rank[p[1] + '\u0001' + p[2]] = r++
                "A" -> added += p[1] to p[2].toInt()
            }
        }
        added.sortByDescending { it.first.length }
        unk = vocab["[UNK]"] ?: 1
    }

    /** 엔진 입력: <EXAGGERATION> <s> [ko]…글자… </s> <START_SPEECH> <START_SPEECH> */
    fun encode(text: String, lang: String = "ko"): LongArray {
        val body = (if (lang == "ko") KoText.jamo(text) else text.lowercase())
        val t = ("[$lang]$body").replace(" ", "[SPACE]")
        val ids = ArrayList<Int>()
        ids += EXAGGERATION; ids += BOS
        var pos = 0
        while (pos < t.length) {
            val hit = added.firstOrNull { t.startsWith(it.first, pos) }
            if (hit != null) { ids += hit.second; pos += hit.first.length; continue }
            var end = pos
            while (end < t.length && added.none { t.startsWith(it.first, end) }) end++
            for (w in pretokenize(t.substring(pos, end))) ids += bpe(w)
            pos = end
        }
        ids += EOS; ids += START_SPEECH; ids += START_SPEECH
        return LongArray(ids.size) { ids[it].toLong() }
    }

    /** HF Whitespace 전처리(\w+ | [^\w\s]+)를 정규식 없이: 기기마다 정규식 유니코드 처리가 달라서 */
    private fun pretokenize(s: String): List<String> {
        val out = ArrayList<String>(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isWhitespace(c)) { i++; continue }
            val word = isWord(c); val st = i
            while (i < s.length && !Character.isWhitespace(s[i]) && isWord(s[i]) == word) i++
            out += s.substring(st, i)
        }
        return out
    }

    private fun isWord(c: Char): Boolean = Character.isLetterOrDigit(c) || c == '_' || when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.CONNECTOR_PUNCTUATION.toInt() -> true
        else -> false
    }

    private fun bpe(word: String): List<Int> {
        val parts = word.map { it.toString() }.toMutableList()
        while (parts.size > 1) {
            var best = Int.MAX_VALUE; var bi = -1
            for (i in 0 until parts.size - 1) {
                val r = rank[parts[i] + '\u0001' + parts[i + 1]] ?: continue
                if (r < best) { best = r; bi = i }
            }
            if (bi < 0) break
            parts[bi] = parts[bi] + parts[bi + 1]; parts.removeAt(bi + 1)
        }
        return parts.map { vocab[it] ?: unk }
    }

    companion object {
        const val BOS = 255; const val EOS = 0
        const val START_SPEECH = 6561; const val STOP_SPEECH = 6562; const val EXAGGERATION = 6563
    }
}
