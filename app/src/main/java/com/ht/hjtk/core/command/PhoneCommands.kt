package com.ht.hjtk.core.command

/**
 * AI에게 전화 시키기 — 사전 항목과 안전 규칙(순수 코드, JVM 테스트 가능).
 *
 * 안드로이드는 제3자 앱이 일반 통화에 목소리를 넣거나 상대 목소리를 듣는 것을 허용하지 않는다.
 * 그래서 두 갈래로 나눈다.
 *  1) phone.prepare — 키 없이: AI가 대본을 쓰고, 전화 앱에 번호를 채우고, 대본을 알림으로 띄운다. 통화는 사용자가.
 *  2) phone.ai_call — 선택 계정(Bland AI 또는 Vapi): 클라우드 음성 AI가 실제로 전화를 걸어 대화하고, 앱은 결과(요약·대화록)를 받아 온다.
 */
object PhoneCommands {
    private fun spec(id: String, title: String, e: Effect, params: String, desc: String, alias: String) = CommandSpec(id, title, Category.COMM, e,
        params.split('|').map { p -> Param(p.substringBefore(':').trimEnd('*'), p.substringAfter(':', ""), p.substringBefore(':').endsWith("*")) }, desc, listOf(alias))

    val specs: List<CommandSpec> = listOf(
        spec("phone.prepare", "통화 준비", Effect.LOCAL, "number*:번호|script*:대본(요점·물어볼 것)", "전화 앱에 번호를 채우고 대본을 알림으로 띄운다(통화는 사용자)", "통화준비"),
        spec("phone.ai_call", "AI가 전화 걸기", Effect.MONEY, "number*:번호|goal*:통화 목적(무엇을 알아내거나 요청할지)|facts:AI가 말해도 되는 정보(이름·예약 시간 등)|minutes:최대 통화 분(기본 5)", "클라우드 음성 AI가 대신 통화하고 요약·대화록을 가져온다(유료 계정 필요)", "ai전화"),
        spec("phone.call_result", "AI 통화 결과", Effect.READ, "id*:통화 ID", "진행 중이거나 끝난 AI 통화의 상태·요약", "통화결과")
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
