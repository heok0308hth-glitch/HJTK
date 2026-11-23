package com.ht.hjtk.core.command

/**
 * AI 전화 — 사전 항목과 안전 규칙(순수 코드, JVM 테스트 가능).
 * 3.0: 전부 기기 안에서, 무료로. 말하는 소리는 기기 음성 엔진(내 목소리), 통화는 HJTK 통화 화면.
 *  - phone.dial      번호를 채운 HJTK 다이얼을 연다(통화 버튼은 사용자)
 *  - phone.ai_call   대본을 짜고 미리 녹음한 뒤 건다. 사용자가 상대 반응을 누르면 AI가 내 목소리로 대답
 */
object PhoneCommands {
    private fun spec(id: String, title: String, e: Effect, params: String, desc: String, alias: String) = CommandSpec(id, title, Category.COMM, e,
        params.split('|').map { p -> Param(p.substringBefore(':').trimEnd('*'), p.substringAfter(':', ""), p.substringBefore(':').endsWith("*")) }, desc, listOf(alias))

    val specs: List<CommandSpec> = listOf(
        spec("phone.dial", "전화 다이얼", Effect.LOCAL, "number*:번호", "번호를 채운 HJTK 다이얼을 연다(통화 버튼은 사용자)", "전화걸기"),
        spec("phone.ai_call", "AI가 전화 걸기", Effect.MONEY, "number*:번호|goal*:통화 목적(무엇을 알아내거나 요청할지)|facts:AI가 말해도 되는 정보(이름·예약 시간 등)", "내 목소리로 AI가 직접 통화한다(듣고·생각하고·말하기) · 끝나면 대화록을 돌려준다", "ai전화"),
    )
}

object PhoneGuard {
    /** 긴급·공공 번호는 AI가 걸지 않는다 */
    private val blocked = setOf("112", "119", "110", "113", "117", "118", "120", "122", "125", "111", "182", "1301", "1332", "1333", "1366", "1388", "911", "999", "000", "100", "101", "102", "108")
    const val DAILY_CAP = 5
    const val COOLDOWN_MS = 10 * 60_000L

    /** 국제 형식(+국가번호…)으로. 한국 번호는 0을 +82로. 형식이 아니면 null */
    fun normalize(raw: String, country: String = "82"): String? {
        val t = raw.trim()
        val digits = t.filter { it.isDigit() }
        if (digits.length < 3) return null
        val e164 = when {
            t.startsWith("+") -> "+$digits"
            digits.startsWith("00") -> "+" + digits.drop(2)
            digits.startsWith("0") -> "+$country" + digits.drop(1)
            else -> "+$country$digits"
        }
        return e164.takeIf { it.length in 9..16 }
    }

    /** 막을 이유가 있으면 그 이유, 없으면 null */
    fun check(raw: String, history: List<Pair<String, Long>>, now: Long): String? {
        val bare = raw.filter { it.isDigit() }
        if (bare in blocked || bare.length <= 4) return "긴급·공공·단축 번호에는 AI가 전화하지 않아요"
        val n = normalize(raw) ?: return "전화번호 형식이 아니에요"
        val today = history.count { now - it.second < 24 * 3600_000L }
        if (today >= DAILY_CAP) return "AI 전화는 하루 ${DAILY_CAP}통까지예요(남용 방지)"
        history.lastOrNull { it.first == n }?.let { if (now - it.second < COOLDOWN_MS) return "같은 번호에는 10분 안에 다시 걸지 않아요" }
        return null
    }

    fun disclosure(userName: String?): String = "안녕하세요, 저는 ${userName?.takeIf { it.isNotBlank() } ?: "고객"}님을 대신해 전화드리는 AI 비서입니다."

    /** 음성 AI에게 주는 지시. 공개·정직·범위 제한은 사용자가 바꿀 수 없다. */
    fun task(goal: String, facts: String, userName: String?): String = buildString {
        appendLine("You are an AI phone assistant calling on behalf of ${userName?.takeIf { it.isNotBlank() } ?: "the user"}. Speak Korean unless the other person speaks another language.")
        appendLine("Rules you must always follow:")
        appendLine("1. At the start, clearly say you are an AI assistant calling on behalf of the user. If asked whether you are a human, say you are an AI.")
        appendLine("2. Never pretend to be the user or imitate a specific person.")
        appendLine("3. Only pursue this goal. Do not agree to payments, contracts, cancellations or anything beyond it; say the user will confirm directly.")
        appendLine("4. Share only the facts given below. If asked for anything else (card numbers, ID numbers, passwords), say you cannot share it and the user will follow up.")
        appendLine("5. Be polite and brief. When the goal is done or clearly not possible, thank them and end the call.")
        appendLine("Goal: $goal")
        if (facts.isNotBlank()) appendLine("Facts you may share: $facts")
    }
}
