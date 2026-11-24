package com.ht.ensemble.ai

/**
 * AI 계층의 순수 코어(안드로이드 의존 없음).
 *  - 다섯 회사(DeepSeek·Claude·ChatGPT·Gemini·Grok)와 모델 카탈로그
 *  - 세 가지 연결 방식: API(키·스트리밍) · WEB(앱 안 DeepSeek, 무키) · APP(그 회사 앱으로 보내고 복사해 돌아오면 수신, 무키)
 *  - 역할(설계·조사·작성·검토·대화·음성) × 난이도 → 어떤 AI·모델이 맡을지 자동 배정(ModelRouter)
 *  - 토큰 추정과 맥락 압축(ContextPacker)
 * 새 회사를 붙일 때는 Provider·ModelCatalog에 한 줄, 엔진의 전송 어댑터 하나만 추가하면 된다.
 */
enum class Provider(val label: String, val company: String, val apiStyle: String, val defaultBase: String, val appPkg: String?, val webUrl: String) {
    DEEPSEEK("DeepSeek", "DeepSeek", "openai", "https://api.deepseek.com", "com.deepseek.chat", "https://chat.deepseek.com"),
    CLAUDE("Claude", "Anthropic", "anthropic", "https://api.anthropic.com", "com.anthropic.claude", "https://claude.ai/new"),
    GPT("ChatGPT", "OpenAI", "openai", "https://api.openai.com/v1", "com.openai.chatgpt", "https://chatgpt.com"),
    GEMINI("Gemini", "Google", "gemini", "https://generativelanguage.googleapis.com/v1beta", "com.google.android.apps.bard", "https://gemini.google.com/app"),
    GROK("Grok", "xAI", "openai", "https://api.x.ai/v1", "ai.x.grok", "https://grok.com");
    companion object { fun of(s: String?) = values().firstOrNull { it.name.equals(s, true) || it.label.equals(s, true) } }
}

enum class Tier(val label: String, val rank: Int) { FAST("빠름", 0), BALANCED("균형", 1), STRONG("최고", 2), REASONING("깊은 추론", 3) }
enum class Transport(val label: String) { API("API"), WEB("앱 안 웹"), APP("앱 연결") }

data class Model(val provider: Provider, val id: String, val label: String, val tier: Tier, val note: String, val ctxK: Int = 128, val reasoning: Boolean = false)

/** 2026-11 기준 기본값. 이름이 바뀌어도 설정에서 모델 ID를 직접 적을 수 있다. */
object ModelCatalog {
    val ALL: List<Model> = listOf(
        Model(Provider.DEEPSEEK, "deepseek-v4-pro", "DeepSeek V4 Pro", Tier.STRONG, "깊은 분석·에이전트 작업", 1000, true),
        Model(Provider.DEEPSEEK, "deepseek-flash", "DeepSeek Flash", Tier.FAST, "빠르고 저렴 · 긴 자료 요약", 1000),
        Model(Provider.CLAUDE, "claude-opus-5-5", "Claude Opus 5.5", Tier.STRONG, "복잡한 일·긴 글·코드", 200, true),
        Model(Provider.CLAUDE, "claude-sonnet-5-5", "Claude Sonnet 5.5", Tier.BALANCED, "일상 업무의 기본값", 200, true),
        Model(Provider.CLAUDE, "claude-haiku-5-5", "Claude Haiku 5.5", Tier.FAST, "빠른 답·분류·요약", 200),
        Model(Provider.GPT, "gpt-6.2-astra", "GPT-6.2 Astra", Tier.STRONG, "가장 똑똑한 GPT", 1050, true),
        Model(Provider.GPT, "gpt-6.2-sol", "GPT-6.2 Sol", Tier.BALANCED, "속도와 품질의 균형", 1050, true),
        Model(Provider.GPT, "gpt-6-luna", "GPT-6 Luna", Tier.FAST, "가볍고 빠름", 1050),
        Model(Provider.GEMINI, "gemini-3.1-pro", "Gemini 3.1 Pro", Tier.STRONG, "어려운 문제·긴 맥락", 1000, true),
        Model(Provider.GEMINI, "gemini-3.8-flash", "Gemini 3.8 Flash", Tier.BALANCED, "빠르면서 똑똑함 · 긴 자료", 1000, true),
        Model(Provider.GEMINI, "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", Tier.FAST, "가장 싸고 빠름", 1000),
        Model(Provider.GROK, "grok-4.7", "Grok 4.7", Tier.STRONG, "코딩·에이전트·지식 작업", 1000, true),
        Model(Provider.GROK, "grok-latest", "Grok (최신)", Tier.BALANCED, "항상 최신 Grok", 1000),
        Model(Provider.GROK, "grok-4-1-fast", "Grok 4.1 Fast", Tier.FAST, "저렴하고 빠름", 2000)
    )
    fun of(p: Provider) = ALL.filter { it.provider == p }
    fun find(p: Provider, id: String) = ALL.firstOrNull { it.provider == p && it.id == id } ?: Model(p, id, id, Tier.BALANCED, "직접 입력한 모델")
    /** 원하는 등급에 가장 가까운 모델(없으면 한 단계씩 내려간다) */
    fun forTier(p: Provider, want: Tier): Model? {
        val list = of(p); if (list.isEmpty()) return null
        return list.minByOrNull { val d = it.tier.rank - want.rank; if (d > 0) d * 2 + 1 else -d * 2 }
    }
}

/** 실제로 쓸 수 있는 AI 한 자리(회사 + 연결 방식 + 계정). WEB DeepSeek은 모델 대신 'DeepSeek 웹'. */
data class Member(val provider: Provider, val transport: Transport, val accountId: String? = null, val accountLabel: String = "", val pinnedModel: String? = null) {
    val key get() = "${provider.name}:${transport.name}:${accountId ?: "-"}"
    fun display(model: Model?) = provider.label + (model?.let { " · ${it.label}" } ?: if (transport == Transport.WEB) " · 웹" else if (transport == Transport.APP) " · 앱" else "") +
        if (accountLabel.isNotBlank()) " ($accountLabel)" else ""
}

enum class Role(val label: String, val en: String) {
    PLAN("설계", "Planner"), RESEARCH("조사", "Researcher"), WRITE("작성", "Writer"), REVIEW("검토", "Reviewer"), CHAT("대화", "Chat"), LIVE("음성", "Voice"), SUMMARY("요약", "Summarizer")
}

/** 작업 난이도 = 공정의 깊이와 쓰는 모델 등급, 그리고 토큰 예산 */
enum class Difficulty(val label: String, val en: String, val ideas: Int, val revisions: Int, val critic: Boolean, val budgetChars: Int, val maxSteps: Int, val desc: String) {
    QUICK("빠르게", "Quick", 1, 0, false, 4_000, 4, "계획 1개 · 검토 없음 · 빠른 모델"),
    STANDARD("보통", "Standard", 2, 1, true, 8_000, 6, "계획 2개 비교 · 검토 1회"),
    DEEP("깊게", "Deep", 3, 2, true, 14_000, 8, "계획 3개 · 강한 모델 · 검토 2회"),
    PROJECT("프로젝트", "Project", 3, 3, true, 22_000, 10, "AI 회사 · PM·스프린트·리뷰 채점 · 30분~3시간");
    fun tierFor(r: Role): Tier = when (this) {
        QUICK -> Tier.FAST
        STANDARD -> if (r == Role.WRITE || r == Role.PLAN || r == Role.REVIEW) Tier.BALANCED else Tier.FAST
        DEEP -> if (r == Role.RESEARCH || r == Role.SUMMARY) Tier.BALANCED else Tier.STRONG
        PROJECT -> when (r) { Role.PLAN, Role.REVIEW -> Tier.REASONING; Role.RESEARCH, Role.SUMMARY -> Tier.BALANCED; else -> Tier.STRONG }
    }
    companion object { fun of(s: String?) = values().firstOrNull { it.name.equals(s, true) } ?: STANDARD }
}

data class Assignment(val role: Role, val member: Member, val model: Model?, val why: String) {
    val name get() = member.display(model)
}

/**
 * 업무에 맞는 AI·모델 자동 배정.
 *  점수 = 역할 적합(회사 강점) + 등급 적합 + 연결 방식 비용(APP은 사람 손이 들어 크게 감점) + 다양성(검토자는 작성자와 다른 회사)
 * 사용자가 역할을 고정(pin)했으면 그대로 따른다.
 */
object ModelRouter {
    private val strength: Map<Role, Map<Provider, Int>> = mapOf(
        Role.PLAN to mapOf(Provider.GPT to 3, Provider.CLAUDE to 3, Provider.DEEPSEEK to 2, Provider.GEMINI to 2, Provider.GROK to 2),
        Role.RESEARCH to mapOf(Provider.GEMINI to 3, Provider.GROK to 3, Provider.DEEPSEEK to 2, Provider.GPT to 2, Provider.CLAUDE to 2),
        Role.WRITE to mapOf(Provider.CLAUDE to 3, Provider.GPT to 3, Provider.DEEPSEEK to 2, Provider.GEMINI to 2, Provider.GROK to 1),
        Role.REVIEW to mapOf(Provider.CLAUDE to 3, Provider.GPT to 3, Provider.GEMINI to 2, Provider.DEEPSEEK to 2, Provider.GROK to 2),
        Role.CHAT to mapOf(Provider.CLAUDE to 2, Provider.GPT to 2, Provider.DEEPSEEK to 2, Provider.GEMINI to 2, Provider.GROK to 2),
        Role.LIVE to mapOf(Provider.GEMINI to 3, Provider.GPT to 3, Provider.GROK to 2, Provider.CLAUDE to 2, Provider.DEEPSEEK to 1),
        Role.SUMMARY to mapOf(Provider.GEMINI to 3, Provider.DEEPSEEK to 3, Provider.GPT to 2, Provider.CLAUDE to 2, Provider.GROK to 2)
    )

    fun modelFor(m: Member, want: Tier): Model? = when {
        m.transport != Transport.API -> null
        m.pinnedModel != null -> ModelCatalog.find(m.provider, m.pinnedModel)
        else -> ModelCatalog.forTier(m.provider, want)
    }

    fun score(m: Member, role: Role, d: Difficulty, avoid: Provider?): Double {
        val want = d.tierFor(role)
        val model = modelFor(m, want)
        var s = (strength[role]?.get(m.provider) ?: 1) * 2.0
        s += when (m.transport) {
            Transport.API -> 3.0 - kotlin.math.abs((model?.tier?.rank ?: 1) - want.rank) * 0.8
            Transport.WEB -> if (role == Role.LIVE) -2.0 else 1.5          // 무료지만 한 번에 하나씩·느림
            Transport.APP -> if (role == Role.REVIEW || role == Role.CHAT) -4.0 else -9.0   // 사람 손이 든다
        }
        if (avoid != null && m.provider == avoid) s -= 2.5
        return s
    }

    fun pick(role: Role, d: Difficulty, available: List<Member>, pins: Map<Role, String> = emptyMap(), avoid: Provider? = null): Assignment? {
        pins[role]?.takeIf { it != "auto" }?.let { key -> available.firstOrNull { it.key == key || it.provider.name == key }?.let { m -> return Assignment(role, m, modelFor(m, d.tierFor(role)), "직접 지정") } }
        val best = available.maxByOrNull { score(it, role, d, avoid) } ?: return null
        val model = modelFor(best, d.tierFor(role))
        return Assignment(role, best, model, why(role, best, model, d))
    }

    /** 공정 한 번에 쓸 팀. 검토자는 가능하면 작성자와 다른 회사. QUICK은 검토 없음. */
    fun team(d: Difficulty, available: List<Member>, pins: Map<Role, String> = emptyMap()): Map<Role, Assignment> {
        val out = LinkedHashMap<Role, Assignment>()
        listOf(Role.PLAN, Role.RESEARCH, Role.WRITE).forEach { r -> pick(r, d, available, pins)?.let { out[r] = it } }
        if (d.critic) pick(Role.REVIEW, d, available, pins, avoid = out[Role.WRITE]?.member?.provider)?.let { out[Role.REVIEW] = it }
        return out
    }

    private fun why(r: Role, m: Member, model: Model?, d: Difficulty): String = when {
        m.transport == Transport.WEB -> "키 없이 바로 쓸 수 있음"
        m.transport == Transport.APP -> "앱으로 연결(복사해 돌아오면 수신)"
        else -> "${r.label}에 강함 · ${model?.tier?.label ?: ""} 모델(${d.label})"
    }
}

/** 토큰 추정: 한글은 대략 1.6자당 1토큰, 영문·기호는 4자당 1토큰(보수적으로) */
object Tokens {
    fun estimate(t: String): Int {
        var han = 0; var other = 0
        for (c in t) if (c in '가'..'힣' || c in 'ㄱ'..'ㆎ' || c in '一'..'鿿') han++ else other++
        return (han / 1.6 + other / 4.0).toInt() + 1
    }
    fun fmt(n: Int) = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()
}

/**
 * 맥락 압축: AI에게 보내는 자료를 줄여 토큰을 아낀다(의미는 유지).
 *  1) 공백·중복 줄·메뉴성 줄(짧고 반복되는 탐색 문구) 제거
 *  2) 예산을 넘으면 목표 단어와 겹치는 문장·숫자·링크가 있는 문장을 우선 남긴다(추출 요약)
 */
object ContextPacker {
    private val noise = Regex("^(홈|로그인|회원가입|메뉴|검색|공유하기|목록|이전|다음|top|home|login|sign in|menu|share|subscribe|cookie|쿠키|©|copyright).{0,12}$", RegexOption.IGNORE_CASE)

    fun pack(text: String, goal: String, budget: Int): String {
        val seen = HashSet<String>()
        val lines = text.lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }
            .filter { it.isNotEmpty() && !noise.matches(it) && seen.add(it.lowercase().take(80)) }
        val joined = lines.joinToString("\n")
        if (joined.length <= budget) return joined
        val keys = Regex("[\\p{L}\\p{N}]{2,}").findAll(goal.lowercase()).map { it.value }.toSet()
        val sentences = joined.split(Regex("(?<=[.!?。])\\s+|\\n")).filter { it.isNotBlank() }
        val scored = sentences.mapIndexed { i, s ->
            val l = s.lowercase()
            var sc = keys.count { l.contains(it) } * 3.0
            if (Regex("https?://").containsMatchIn(s)) sc += 2
            if (Regex("\\d").containsMatchIn(s)) sc += 1
            if (i < 3) sc += 1.5                      // 첫머리는 보통 요지
            Triple(i, s, sc - s.length / 400.0)
        }
        val keep = HashSet<Int>(); var used = 0
        for ((i, s, _) in scored.sortedByDescending { it.third }) { if (used + s.length > budget) continue; keep += i; used += s.length + 1 }
        val out = scored.filter { it.first in keep }.joinToString("\n") { it.second }
        return out + "\n…(자료 ${joined.length}자 중 ${out.length}자로 압축)"
    }
}

/** 작업별 토큰 장부: 누가 얼마나 썼나(API는 실제 사용량, 웹·앱은 추정) */
class TokenLedger {
    data class Line(var input: Int = 0, var output: Int = 0, var calls: Int = 0)
    private val by = LinkedHashMap<String, Line>()
    @Synchronized fun add(who: String, input: Int, output: Int) { by.getOrPut(who) { Line() }.apply { this.input += input; this.output += output; calls++ } }
    @Synchronized fun total() = by.values.sumOf { it.input + it.output }
    @Synchronized fun snapshot(): Map<String, Line> = by.mapValues { it.value.copy() }
    @Synchronized fun summary() = by.entries.joinToString(" · ") { "${it.key} ${Tokens.fmt(it.value.input + it.value.output)}" }
}
