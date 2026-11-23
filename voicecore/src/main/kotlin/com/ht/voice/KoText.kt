package com.ht.voice

/**
 * 말하기 전 글 다듬기: 숫자·시각·전화번호·단위를 한국어로 읽고, 엔진이 받는 자모로 풀고, 문장 단위로 자른다.
 * 엔진은 글자 그대로 읽으므로 "3시"를 "삼시"로 읽지 않게 여기서 "세 시"로 바꿔 둔다.
 */
object KoText {
    private val SINO = arrayOf("", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
    private val DIGIT = arrayOf("공", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
    private val NATIVE1 = arrayOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉")
    private val NATIVE10 = arrayOf("", "열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔")
    /** 고유어로 세는 단위(1~99) */
    private val NATIVE_COUNTERS = listOf("시간", "시", "개", "명", "살", "마리", "잔", "권", "번째", "번", "곳", "대", "병", "장", "벌", "켤레", "통", "달", "가지", "사람")

    /** 한자어 읽기: 0..9999조 */
    fun sino(n: Long): String {
        if (n == 0L) return "영"
        val units = arrayOf("", "만", "억", "조")
        var x = n; var i = 0; val parts = ArrayList<String>()
        while (x > 0 && i < units.size) {
            val chunk = (x % 10000).toInt()
            if (chunk > 0) {
                val s = under10k(chunk)
                parts.add(0, (if (i == 1 && chunk == 1) "" else s) + units[i])
            }
            x /= 10000; i++
        }
        return parts.joinToString(" ")
    }

    private fun under10k(n: Int): String {
        val sb = StringBuilder()
        val th = n / 1000; val h = n / 100 % 10; val t = n / 10 % 10; val o = n % 10
        if (th > 0) sb.append(if (th == 1) "" else SINO[th]).append("천")
        if (h > 0) sb.append(if (h == 1) "" else SINO[h]).append("백")
        if (t > 0) sb.append(if (t == 1) "" else SINO[t]).append("십")
        if (o > 0) sb.append(SINO[o])
        return sb.toString()
    }

    /** 고유어 읽기(1~99), 단위 앞 형태: 스물 → 스무 */
    fun native(n: Int): String? {
        if (n !in 1..99) return null
        val t = n / 10; val o = n % 10
        val ten = if (t == 2 && o == 0) "스무" else NATIVE10[t]
        return ten + NATIVE1[o]
    }

    private val TIME = Regex("""(\d{1,2}):(\d{2})""")
    private val PHONE = Regex("""(?<![\d.])(\d{2,4})-(\d{3,4})-(\d{4})(?![\d])""")
    private val LONGDIGITS = Regex("""(?<![\d.,])\d{5,}(?![\d.,])""")
    private val DECIMAL = Regex("""(\d+)\.(\d+)""")
    private val NUM_COUNTER = Regex("""(\d[\d,]*)\s*(""" + NATIVE_COUNTERS.joinToString("|") + """)""")
    private val NUM = Regex("""\d[\d,]*""")

    fun normalize(input: String): String {
        var s = input
        s = s.replace("%", " 퍼센트").replace("&", " 그리고 ").replace("~", " 에서 ")
        s = TIME.replace(s) { m ->
            val h = m.groupValues[1].toInt(); val mm = m.groupValues[2].toInt()
            (native(h)?.let { "$it 시" } ?: "${sino(h.toLong())} 시") + if (mm > 0) " ${sino(mm.toLong())} 분" else ""
        }
        s = PHONE.replace(s) { m -> m.groupValues.drop(1).joinToString(", ") { g -> g.map { DIGIT[it - '0'] }.joinToString("") } }
        s = LONGDIGITS.replace(s) { m -> m.value.map { DIGIT[it - '0'] }.joinToString("") }
        s = DECIMAL.replace(s) { m -> sino(m.groupValues[1].toLong()) + " 점 " + m.groupValues[2].map { DIGIT[it - '0'] }.joinToString("") }
        s = NUM_COUNTER.replace(s) { m ->
            val n = m.groupValues[1].replace(",", "").toLongOrNull() ?: return@replace m.value
            val unit = m.groupValues[2]
            val word = if (n in 1..99) native(n.toInt())!! else sino(n)
            "$word $unit"
        }
        s = NUM.replace(s) { m -> m.value.replace(",", "").toLongOrNull()?.let { sino(it) } ?: m.value }
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    /** 완성형 한글 → 자모(초·중·종성). 엔진의 어휘가 자모 단위다 */
    fun jamo(text: String): String {
        val sb = StringBuilder(text.length * 3)
        for (ch in text) {
            val o = ch.code
            if (o in 0xAC00..0xD7A3) {
                val b = o - 0xAC00
                sb.append((0x1100 + b / 588).toChar()).append((0x1161 + (b % 588) / 28).toChar())
                if (b % 28 != 0) sb.append((0x11A7 + b % 28).toChar())
            } else sb.append(ch)
        }
        return sb.toString().trim()
    }

    /**
     * 말하기 단위로 자르기. 너무 긴 문장은 쉼표에서 한 번 더 자른다.
     * 짧게 자를수록 첫 소리가 빨리 나온다(통화에서는 첫 소리가 가장 중요하다).
     */
    fun sentences(text: String, max: Int = 60): List<String> {
        val out = ArrayList<String>()
        val raw = text.replace("\r", "").split(Regex("""(?<=[.!?…。])\s+|\n+""")).map { it.trim() }.filter { it.isNotEmpty() }
        for (r in raw) {
            if (r.length <= max) { out += r; continue }
            var cur = StringBuilder()
            for (piece in r.split(Regex("""(?<=[,，、])\s*"""))) {
                if (cur.length + piece.length > max && cur.isNotEmpty()) { out += cur.toString().trim(); cur = StringBuilder() }
                cur.append(piece).append(' ')
            }
            if (cur.isNotBlank()) out += cur.toString().trim()
        }
        return out
    }
}
