package com.ht.hjtk.core.vision

/**
 * 화면 글자 지도 — 사진 대신 '어디에 무슨 글자가 있는지'를 짧은 글로.
 *
 * 스크린샷 한 장을 그림으로 보내면 약 1,000~1,450 토큰(Claude·GPT, 긴 변 1280px)이 든다.
 * 같은 화면을 이 지도로 보내면 보통 150~300 토큰이고, 그림을 못 보는 DeepSeek(키 없음)도 읽는다.
 * 줄 = 화면 높이 40칸 중 몇 번째, c = 가로 12칸 중 몇 번째. #은 큰 글자(제목), ●색은 강조색 글자.
 * 같은 대화에서 이어 보낸 화면은 바뀐 줄만 보낸다.
 */
object ScreenMap {
    data class Block(val text: String, val l: Int, val t: Int, val r: Int, val b: Int, val color: String? = null)

    const val ROWS = 40; const val COLS = 12

    fun build(w: Int, h: Int, blocks: List<Block>, title: String = "화면"): String {
        val bs = blocks.filter { it.text.isNotBlank() && it.b > it.t }
        if (bs.isEmpty() || w <= 0 || h <= 0) return ""
        val heights = bs.map { it.b - it.t }.sorted()
        val med = heights[heights.size / 2].coerceAtLeast(1)
        val rows = sortedMapOf<Int, MutableList<Block>>()
        for (b in bs) rows.getOrPut(((b.t + b.b) / 2 * ROWS / h).coerceIn(0, ROWS - 1)) { ArrayList() } += b
        val sb = StringBuilder("[$title 글자 지도 · 줄=세로 ${ROWS}칸 · c=가로 ${COLS}칸 · #=큰 글자]\n")
        for ((y, list) in rows) {
            sb.append("%02d ".format(y))
            list.sortedBy { it.l }.forEachIndexed { i, b ->
                if (i > 0) sb.append("  ")
                sb.append('c').append((b.l * COLS / w).coerceIn(0, COLS - 1)).append(' ')
                if (b.b - b.t > med * 1.6) sb.append('#')
                sb.append(clean(b.text))
                b.color?.let { sb.append(" ●").append(it) }
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun clean(s: String) = s.replace('\n', ' ').replace(Regex("""\s{2,}"""), " ").trim().take(120)

    /** 직전에 보낸 지도와 비교: 같으면 한 줄, 절반 이상 같으면 바뀐 줄만, 아니면 전체 */
    fun diff(prev: String?, cur: String): String {
        if (prev.isNullOrBlank() || cur.isBlank()) return cur
        if (prev == cur) return "(직전 화면과 같아요)"
        val p = prev.lines().drop(1).map { it.substringAfter(' ') }.toSet()
        val c = cur.lines()
        val body = c.drop(1)
        val same = body.count { it.substringAfter(' ') in p }
        if (body.isEmpty() || same < body.size / 2) return cur
        val changed = body.filter { it.substringAfter(' ') !in p }
        return c.first() + "\n(직전 화면에서 바뀐 줄만 · 나머지 ${same}줄은 그대로)\n" + changed.joinToString("\n").ifBlank { "(글자는 그대로, 그림만 바뀜)" }
    }

    /** 사진 대신 글자로 충분한가: 글자가 어느 정도 있어야 지도가 의미 있다 */
    fun enoughText(blocks: List<Block>): Boolean = blocks.sumOf { it.text.count { c -> c.isLetterOrDigit() } } >= 24

    /** 대략 토큰 수(한글 1.6자, 그 밖 4자당 1토큰) */
    fun tokens(s: String): Int { val han = s.count { it in '가'..'힣' }; return (han / 1.6 + (s.length - han) / 4.0).toInt() + 1 }

    /** 같은 크기 스크린샷을 그림으로 보냈다면(Claude 기준, 긴 변 1280px) */
    fun imageTokens(w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return 0
        val s = 1280f / maxOf(w, h); return ((w * s) * (h * s) / 750f).toInt()
    }
}
