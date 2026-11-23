package com.ht.hjtk.core.agent

import com.ht.hjtk.core.ai.AiProfiles.Skill

/**
 * HJTK 3.4 '합주(Ensemble)' — AI끼리 실제로 협업하게 만드는 두 부품.
 *
 *  · 칠판(Board): 팀원이 결과 끝에 [칠판] 사실·결정·주의·질문을 남기면, 이후 시작하는 모든 팀원이 그걸 읽고 일한다.
 *    의존 관계로 이어지지 않은 AI끼리도 서로 알게 된다(같은 일을 두 번 하거나 서로 모순되는 일을 줄인다).
 *  · 배정 시장(Dispatch): 할 일마다 팀원이 '입찰'한다 = 실제 능력(성적 반영) − 지금 맡은 일 − 센터 벌점 + 지휘자 지명.
 *    가장 잘하는 AI 한 명이 다 떠안지 않고, 비슷하게 잘하는 AI에게 자연스럽게 퍼진다. 센터는 지휘·검수에 집중한다.
 */
class Board {
    data class Note(val who: String, val kind: String, val text: String)
    private val notes = ArrayList<Note>()

    fun add(who: String, kind: String, text: String): Boolean = synchronized(notes) {
        val t = text.trim().take(220)
        if (t.length < 4 || notes.any { it.text.equals(t, true) }) return false
        notes += Note(who, kind, t); if (notes.size > 40) notes.removeAt(0); true
    }
    fun all(): List<Note> = synchronized(notes) { notes.toList() }
    fun isEmpty() = synchronized(notes) { notes.isEmpty() }

    /** 팀원에게 건넬 칠판 요약: 주의 → 결정 → 사실 → 질문 순(중요한 것 먼저) */
    fun digest(max: Int = 1400): String {
        val order = listOf("주의", "결정", "사실", "질문")
        val sb = StringBuilder()
        for (n in all().sortedBy { order.indexOf(it.kind).let { i -> if (i < 0) 9 else i } }) {
            val line = "- ${n.kind}(${n.who}): ${n.text}\n"
            if (sb.length + line.length > max) break
            sb.append(line)
        }
        return sb.toString().trimEnd()
    }

    companion object {
        private val HEAD = Regex("""(?m)^\s*[\[［【]?\s*칠판\s*[\]］】]?\s*:?\s*$""")
        private val LINE = Regex("""^\s*[-•*]?\s*(사실|결정|주의|질문)\s*[:：]\s*(.+)$""")
        /** AI 답에서 [칠판] 블록을 떼어 낸다 → (본문, 메모들) */
        fun split(raw: String): Pair<String, List<Pair<String, String>>> {
            val m = HEAD.findAll(raw).lastOrNull() ?: return raw.trim() to emptyList()
            val tail = raw.substring(m.range.last + 1)
            val items = tail.lines().mapNotNull { LINE.find(it)?.let { g -> g.groupValues[1] to g.groupValues[2].trim() } }.filter { it.second.isNotBlank() && !it.second.matches(Regex("(없음|none|-|…)", RegexOption.IGNORE_CASE)) }
            if (items.isEmpty()) return raw.trim() to emptyList()
            return raw.substring(0, m.range.first).trim() to items.take(5)
        }
    }
}

object Dispatch {
    const val LOAD = 0.22
    const val CENTER = 0.40
    const val HINT = 0.12

    /** 입찰가. rate는 0~10(성적 반영 능력), load는 지금 맡은 일 수 */
    fun bid(rate: Double, load: Int, lanes: Int, isCenter: Boolean, teamSize: Int, hinted: Boolean): Double =
        rate / 10.0 + (if (hinted) HINT else 0.0) - LOAD * load / lanes.coerceAtLeast(1) - (if (isCenter && teamSize >= 3) CENTER else 0.0)

    /** 계획의 할 일에 팀원을 붙인다(도구 일은 그대로). 지휘자가 적은 이름은 가산점일 뿐 강제가 아니다 */
    fun assign(tasks: List<Conductor.Task>, team: List<CrewMember>, center: CrewMember?, rate: (CrewMember, Skill) -> Double, skillOf: (Conductor.Task) -> Skill): List<Conductor.Task> {
        if (team.isEmpty()) return tasks
        // 팀이 3명 이상이면 센터는 할 일을 아예 받지 않는다(설계·검수에 집중, 한 AI가 다 떠안는 일 방지)
        val pool = if (team.size >= 3 && center != null) team.filter { it.key != center.key } else team
        val loads = HashMap<String, Int>()
        return tasks.map { t ->
            if (t.tool.isNotBlank()) return@map t
            val s = skillOf(t)
            val m = pool.maxBy { bid(rate(it, s), loads[it.key] ?: 0, it.lanes, it.key == center?.key, team.size, matches(t.who, it)) }
            loads[m.key] = (loads[m.key] ?: 0) + 1
            t.copy(who = m.name)
        }
    }

    fun matches(who: String, m: CrewMember): Boolean {
        val w = who.lowercase().trim(); if (w.isBlank()) return false
        return m.name.lowercase().contains(w) || w.contains(m.provider.label.lowercase()) || w.contains(m.provider.name.lowercase()) || w.contains(m.name.lowercase())
    }
}
