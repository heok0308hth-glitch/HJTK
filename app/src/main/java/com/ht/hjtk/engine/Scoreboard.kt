package com.ht.hjtk.engine

import android.content.Context
import com.ht.ensemble.ai.Provider
import org.json.JSONObject

/**
 * 실제 성적표 — 기본 장단점 표(AiProfiles)에 '써 보니 어땠나'를 더한다.
 *  성공률(최근 20회) · 평균 응답 시간 · 한도 걸림(쉬는 시간)
 * 한도 문구(영어·한국어)를 알아채면 그 AI를 정해진 시간 동안 빼고 다음 AI로 넘긴다.
 */
object Scoreboard {
    data class Stat(var ok: Int = 0, var fail: Int = 0, var avgMs: Double = 0.0, var restUntil: Long = 0, var lastNote: String = "",
                    var lastVia: String = "", var lastOkAt: Long = 0, var lastFailAt: Long = 0) {
        val rate get() = if (ok + fail == 0) 0.8 else ok.toDouble() / (ok + fail)
    }
    private val stats = HashMap<String, Stat>()
    private var ctx: Context? = null

    fun install(c: Context) {
        ctx = c
        runCatching { JSONObject(c.getSharedPreferences("score", 0).getString("s", "{}")!!) }.getOrNull()?.let { o ->
            o.keys().forEach { k -> o.optJSONObject(k)?.let { j -> stats[k] = Stat(j.optInt("ok"), j.optInt("fail"), j.optDouble("ms", 0.0), j.optLong("rest"), j.optString("note"), j.optString("via"), j.optLong("okAt"), j.optLong("failAt")) } }
        }
    }
    private fun save() = ctx?.getSharedPreferences("score", 0)?.edit()?.putString("s", JSONObject().apply {
        stats.forEach { (k, v) -> put(k, JSONObject().put("ok", v.ok).put("fail", v.fail).put("ms", v.avgMs).put("rest", v.restUntil).put("note", v.lastNote).put("via", v.lastVia).put("okAt", v.lastOkAt).put("failAt", v.lastFailAt)) }
    }.toString())?.apply()

    fun stat(key: String) = synchronized(stats) { stats.getOrPut(key) { Stat() } }

    fun record(key: String, ok: Boolean, ms: Long, text: String, via: String) = synchronized(stats) {
        val s = stats.getOrPut(key) { Stat() }
        if (ok) { s.ok++; s.lastOkAt = System.currentTimeMillis() } else { s.fail++; s.lastFailAt = System.currentTimeMillis(); s.lastVia = if (com.ht.ensemble.ai.AiProfiles.limitHit(text, via)) "LIMIT" else if (text.isBlank()) via.ifBlank { "EMPTY" } else "WEAK" }
        if (s.ok + s.fail > 20) { s.ok = (s.ok * 0.8).toInt(); s.fail = (s.fail * 0.8).toInt() }    // 오래된 기록은 옅게
        if (ok) s.avgMs = if (s.avgMs == 0.0) ms.toDouble() else s.avgMs * 0.7 + ms * 0.3
        limitRest(text, via)?.let { rest -> s.restUntil = System.currentTimeMillis() + rest; s.lastNote = "사용량 한도 · ${rest / 60_000}분 쉬는 중" }
        // 3.4: 로그인 풀림·입력창 없음·화면 미준비는 몇 번 더 시켜도 같다 → 15분 쉬게 해서 팀이 그 AI를 기다리며 시간을 버리지 않게
        if (!ok && text.isBlank() && HARD.any { via.contains(it) }) { s.restUntil = System.currentTimeMillis() + 15 * 60_000L; s.lastNote = if (via.contains("LOGIN")) "로그인 필요" else "화면 준비 안 됨" }
        if (ok && s.restUntil > 0 && s.lastNote.let { it == "로그인 필요" || it == "화면 준비 안 됨" }) s.restUntil = 0
        save()
    }

    private val HARD = listOf("NEEDS_LOGIN", "CHALLENGE", "NO_INPUT", "WEB_NOT_READY", "NO_WEB", "NOT_READY")
    fun resting(key: String) = stat(key).restUntil > System.currentTimeMillis()
    fun note(key: String) = stat(key).let { if (resting(key)) it.lastNote else "" }
    fun snapshot(): Map<String, Stat> = synchronized(stats) { stats.mapValues { it.value.copy() } }
    /** 실패 코드 → 사람 말 */
    fun reason(via: String): String = when {
        via.contains("LOGIN") -> "로그인이 풀렸어요"
        via.contains("CHALLENGE") -> "보안 확인(로봇 확인)이 떠 있어요"
        via.contains("NO_INPUT") -> "입력창을 못 찾았어요(사이트 화면이 바뀜)"
        via.contains("NOT_READY") || via.contains("NO_WEB") -> "화면이 준비되지 않았어요"
        via.contains("ECHO") -> "답 대신 보낸 지시문이 읽혔어요(내 말풍선을 답으로 읽음)"
        via.contains("NO_ANSWER") -> "보냈지만 답을 읽지 못했어요(답 위치를 못 찾음)"
        via == "LIMIT" || via.contains("429") -> "사용량 한도"
        via == "WEAK" -> "답이 너무 짧거나 거절"
        via == "EMPTY" -> "빈 답"
        via.startsWith("ERR") -> "오류: " + via.removePrefix("ERR:").take(60)
        via.isBlank() -> ""
        else -> via
    }
    fun clearSoft(key: String) = synchronized(stats) { stats[key]?.let { if (it.lastNote == "로그인 필요" || it.lastNote == "화면 준비 안 됨") { it.restUntil = 0; it.lastNote = ""; save() } } }
    fun clear(key: String) = synchronized(stats) { stats[key]?.restUntil = 0; save() }


    /** 한도 문구면 쉴 시간(ms). "in 3 hours", "2시간 후" 같은 말이 있으면 그 시간, 없으면 3시간 */
    fun limitRest(text: String, via: String): Long? {
        val t = text.take(600)
        if (!com.ht.ensemble.ai.AiProfiles.limitHit(t, via)) return null
        Regex("(\\d+)\\s*(hours?|시간)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.toLongOrNull()?.let { return (it * 3_600_000).coerceIn(600_000, 24 * 3_600_000) }
        Regex("(\\d+)\\s*(minutes?|분)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.toLongOrNull()?.let { return (it * 60_000).coerceIn(120_000, 6 * 3_600_000) }
        return 3 * 3_600_000L
    }

    /** 실제 성적을 반영한 점수: 기본 능력 × (0.6 + 0.4×성공률) − 느림 벌점 */
    fun adjust(base: Int, key: String): Double {
        val s = stat(key)
        val slow = when { s.avgMs > 90_000 -> 1.0; s.avgMs > 45_000 -> 0.5; else -> 0.0 }
        return base * (0.6 + 0.4 * s.rate) - slow
    }

    fun summary(p: Provider, key: String): String { val s = stat(key); return "${p.label} 성공 ${(s.rate * 100).toInt()}% · 평균 ${(s.avgMs / 1000).toInt()}초" + if (resting(key)) " · 한도로 쉬는 중" else "" }
}
