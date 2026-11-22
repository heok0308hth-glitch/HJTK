package com.ht.hjtk.core.stream

import org.json.JSONArray
import org.json.JSONObject

/**
 * P2 — 서버 기록에서 '이번 질문에 대한' 마지막 답을 꺼낸다.
 * 응답 모양이 바뀌어도 버티도록 특정 경로를 가정하지 않고 role이 있는 메시지 객체를 깊이 우선으로 모은다.
 * 직전 사용자 메시지가 우리가 보낸 질문과 맞을 때만 인정한다(이전 답 오인 방지).
 */
object HistoryExtract {
    data class Turn(val role: String, val text: String)

    fun lastAnswerFor(json: String, sentPrompt: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val turns = ArrayList<Turn>()
        collect(root, turns, 0)
        if (turns.isEmpty()) return null
        val iA = turns.indexOfLast { it.role == "ASSISTANT" }
        if (iA < 0) return null
        val ans = turns[iA].text.trim().ifEmpty { return null }
        val user = turns.subList(0, iA).lastOrNull { it.role == "USER" }?.text ?: return null
        return if (sameStart(user, sentPrompt)) ans else null
    }

    private fun sameStart(a: String, b: String): Boolean {
        fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim().take(60)
        val x = norm(a); val y = norm(b)
        return x.isNotEmpty() && (x == y || x.startsWith(y.take(30)) || y.startsWith(x.take(30)))
    }

    private fun collect(v: Any?, out: MutableList<Turn>, depth: Int) {
        if (depth > 12) return
        when (v) {
            is JSONObject -> {
                val role = v.optString("role", "").uppercase()
                if (role == "USER" || role == "ASSISTANT") { out += Turn(role, textOf(v)); return }
                v.keys().forEach { collect(v.opt(it), out, depth + 1) }
            }
            is JSONArray -> for (i in 0 until v.length()) collect(v.opt(i), out, depth + 1)
        }
    }

    private fun textOf(m: JSONObject): String {
        m.optJSONArray("fragments")?.let { fr ->
            val sb = StringBuilder()
            for (i in 0 until fr.length()) {
                val f = fr.optJSONObject(i) ?: continue
                val type = f.optString("type", "RESPONSE").uppercase()
                if (type == "THINK" || type == "THINKING") continue
                if (type == "REQUEST" || type == "RESPONSE" || type == "TEXT" || type.isEmpty()) sb.append(f.optString("content", ""))
            }
            if (sb.isNotEmpty()) return sb.toString()
        }
        return m.optString("content", "")
    }
}

/**
 * 화면용 필터: AEP 봉투(헤더·JSON 블록·잔여 마커)가 대화에 그대로 노출되지 않게 한다.
 * 봉투 안의 say/final이 있으면 그것을, 없으면 봉투 밖 문장을 보여 준다.
 */
object AepView {
    private val header = Regex("""\[\[\s*AEP\d[^\]]*]]""")
    private val block = Regex("""<<<\s*AEP\d-JSON(.*?)AEP\d-JSON\s*>>>""", RegexOption.DOT_MATCHES_ALL)
    private val strayOpen = Regex("""<<<\s*AEP\d-JSON.*""", RegexOption.DOT_MATCHES_ALL)
    private val strayMarks = Regex("""AEP\d-JSON\s*>>>|<<<\s*AEP\d-JSON""")

    fun looksLikeEnvelope(t: String) = t.contains("AEP1") && (header.containsMatchIn(t) || t.contains("AEP1-JSON"))

    fun clean(text: String): String {
        if (!looksLikeEnvelope(text)) return text
        val json = block.find(text)?.groupValues?.get(1)?.trim()
        val o = json?.let { j -> runCatching { JSONObject(j.substring(j.indexOf('{').coerceAtLeast(0), j.lastIndexOf('}') + 1)) }.getOrNull() }
        val final = o?.optString("final", "")?.takeIf { it.isNotBlank() && it != "null" }
        val say = o?.optString("say", "")?.takeIf { it.isNotBlank() }
        val outside = text.replace(block, " ").replace(strayOpen, " ").replace(header, " ").replace(strayMarks, " ").replace(Regex("[ \\t]{2,}"), " ").trim()
        return when {
            final != null -> final
            outside.isNotBlank() && say != null && !outside.contains(say) -> "$outside\n\n$say".trim()
            outside.isNotBlank() -> outside
            say != null -> say
            else -> "(작업 형식의 답을 받았어요 — 작업 화면에서 진행 상황을 확인하세요)"
        }
    }
}
