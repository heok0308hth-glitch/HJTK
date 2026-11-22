package com.ht.hjtk.core

import com.ht.hjtk.core.aegis.Canonical
import com.ht.hjtk.core.command.CommandCall
import com.ht.hjtk.core.command.CommandCatalog
import com.ht.hjtk.core.command.Effect
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

// ────────────────────────────────────────────────────────────────────────────
// 저널 — 추가 전용 + 해시 체인. 변조되면 chainValid=false.
// ────────────────────────────────────────────────────────────────────────────
class Journal(private val sink: (String) -> Unit = {}) {
    data class Entry(val seq: Int, val at: Long, val type: String, val payload: String, val digest: String)
    private val entries = ArrayList<Entry>()
    val all: List<Entry> get() = entries
    val tail: String get() = entries.lastOrNull()?.digest ?: ZERO

    @Synchronized
    fun append(type: String, payload: String, at: Long = System.currentTimeMillis()): Entry {
        val d = Canonical.sha256("$tail|$type|$payload|$at")
        val e = Entry(entries.size + 1, at, type, payload, d)
        entries += e
        sink(JSONObject().put("seq", e.seq).put("at", at).put("type", type).put("payload", payload).put("digest", d).toString())
        return e
    }

    fun load(lines: List<String>) {
        entries.clear()
        lines.filter { it.isNotBlank() }.forEach { l -> runCatching {
            val o = JSONObject(l)
            entries += Entry(o.getInt("seq"), o.getLong("at"), o.getString("type"), o.getString("payload"), o.getString("digest"))
        } }
    }

    fun chainValid(): Boolean {
        var prev = ZERO
        for (e in entries) {
            if (Canonical.sha256("$prev|${e.type}|${e.payload}|${e.at}") != e.digest) return false
            prev = e.digest
        }
        return true
    }

    companion object { val ZERO = "0".repeat(64) }
}

// ────────────────────────────────────────────────────────────────────────────
// L0 결정론 검증기 — 비평의 대부분은 모델이 아니라 기계가 잡는다(B-2).
// ────────────────────────────────────────────────────────────────────────────
object Verifiers {
    data class Violation(val code: String, val where: String, val evidence: String)
    data class Report(val pass: Boolean, val violations: List<Violation>, val checks: Int) {
        fun text() = if (violations.isEmpty()) "L0 검증 통과 ($checks/$checks)" else
            "L0 위반 ${violations.size}건 / 검사 $checks\n" + violations.joinToString("\n") { "- [${it.code}] ${it.where}: ${it.evidence}" }
    }

    fun run(text: String, requirements: List<String>): Report {
        val v = ArrayList<Violation>(); var checks = 0
        // 1 플레이스홀더
        checks++; Regex("(?i)\\b(TODO|TBD|FIXME|lorem ipsum)\\b|\\[여기에[^]]*]|OOO|○○").findAll(text).take(5).forEach { v += Violation("PLACEHOLDER", "본문", it.value) }
        // 2 표 열 수 일관성
        checks++
        text.lines().filter { it.trim().startsWith("|") }.let { rows ->
            if (rows.size >= 2) { val cols = rows.map { r -> r.trim().trim('|').split('|').size }; if (cols.distinct().size > 1) v += Violation("TABLE_COLUMNS", "표", "행마다 열 수가 다름 ${cols.distinct()}") }
        }
        // 3 합계 검사: "합계|총" 줄의 숫자 = 위 숫자들의 합
        checks++
        val lines = text.lines()
        lines.forEachIndexed { i, l ->
            if (Regex("(합계|총합|총계|total)", RegexOption.IGNORE_CASE).containsMatchIn(l)) {
                val target = Regex("[0-9][0-9,]*").findAll(l).lastOrNull()?.value?.replace(",", "")?.toLongOrNull()
                val above = lines.subList(maxOf(0, i - 12), i).mapNotNull { Regex("[0-9][0-9,]{2,}").findAll(it).lastOrNull()?.value?.replace(",", "")?.toLongOrNull() }
                if (target != null && above.size >= 2 && above.sum() != target && above.drop(1).sum() != target) v += Violation("SUM_MISMATCH", "${i + 1}행", "표시 $target vs 계산 ${above.sum()}")
            }
        }
        // 4 링크 형식
        checks++; Regex("\\]\\(([^)]*)\\)").findAll(text).forEach { m -> val u = m.groupValues[1]; if (!u.startsWith("http") && !u.startsWith("#")) v += Violation("LINK_FORMAT", "링크", u) }
        // 5 근거 없는 단정
        checks++
        if (Regex("(연구에 따르면|통계에 따르면|according to (a )?stud)", RegexOption.IGNORE_CASE).containsMatchIn(text) && !text.contains("http")) v += Violation("CITATION_MISSING", "본문", "출처 언급은 있으나 URL 없음")
        // 6 요구사항 커버리지
        for (r in requirements.map { it.trim() }.filter { it.isNotEmpty() }) {
            checks++
            val keys = Regex("[\\p{L}\\p{N}]{2,}").findAll(r).map { it.value }.filter { it.length >= 2 }.toList()
            val hit = keys.count { text.contains(it, ignoreCase = true) }
            if (keys.isNotEmpty() && hit * 2 < keys.size) v += Violation("REQUIREMENT_MISSING", "요구", r)
        }
        // 7 날짜 형식
        checks++; Regex("\\b\\d{4}-(\\d{2})-(\\d{2})\\b").findAll(text).forEach { m ->
            val mo = m.groupValues[1].toInt(); val d = m.groupValues[2].toInt(); if (mo !in 1..12 || d !in 1..31) v += Violation("DATE_INVALID", "날짜", m.value)
        }
        return Report(v.isEmpty(), v, checks)
    }

    /** B-2: 비평 모드 결정. 무키 모드에서 다회전은 기본 OFF. */
    enum class CritiqueMode { OFF, ONE_SHOT, BATCHED }
    fun critiqueMode(hasApiKey: Boolean, maxEffect: Int, userAsked: Boolean, humanBudget: Int): CritiqueMode = when {
        userAsked && (hasApiKey || humanBudget > 0) -> CritiqueMode.BATCHED
        maxEffect >= 4 && humanBudget > 0 -> CritiqueMode.BATCHED
        else -> CritiqueMode.ONE_SHOT
    }

    fun batchedCritiquePrompt(draft: String, requirements: String, l0: Report): String = """
너는 지금부터 서로 다른 세 명의 검토자다. 하나의 답안을 각자 다른 관점에서 공격한다.

[검토 대상]
<<<DRAFT
$draft
DRAFT>>>

[작성자의 기계 검증 기록]
${l0.text()}

[요구사항]
${requirements.ifBlank { "(명시 없음)" }}

[역할]
- R1 반증자: 사실·수치·인용·인과에서 틀릴 수 있는 지점. "근거 없음"도 지적이다.
- R2 형식 검사자: 요구사항 각 항목이 답안 어디에 대응하는지, 형식·단위가 어긋난 곳.
- R3 사용자 대리인: 실제로 실행 가능한지, 빠진 전제·숨은 비용·되돌릴 수 없는 행동.

[규칙] 칭찬·요약 금지. 고칠 수 있는 형태로만. 확신 없으면 severity를 낮추고 "확인 필요". 지적 없으면 빈 배열.
[출력 — JSON만]
{"verdict":"ACCEPT|REVISE|REJECT","issues":[{"role":"R1","severity":"BLOCKER|MAJOR|MINOR","where":"...","claim":"...","fix":"..."}],"missing_requirements":[]}
""".trim()
}

// ────────────────────────────────────────────────────────────────────────────
// 기억 — 신경망 없이 기억하기. 한국어는 글자 2-gram + 단어 토큰으로 BM25.
// ────────────────────────────────────────────────────────────────────────────
data class Memory(val id: String, val text: String, val tag: String, val at: Long, val uses: Int = 0)

object MemoryIndex {
    fun tokens(s: String): List<String> {
        val t = s.lowercase()
        val words = Regex("[\\p{L}\\p{N}]+").findAll(t).map { it.value }.toList()
        val grams = words.flatMap { w -> if (w.length <= 2) listOf(w) else (0 until w.length - 1).map { w.substring(it, it + 2) } }
        return words + grams
    }

    fun search(items: List<Memory>, q: String, n: Int = 8, now: Long = System.currentTimeMillis()): List<Pair<Memory, Double>> {
        if (items.isEmpty()) return emptyList()
        val docs = items.map { tokens(it.text + " " + it.tag) }
        val avg = docs.map { it.size }.average().coerceAtLeast(1.0)
        val df = HashMap<String, Int>(); docs.forEach { d -> d.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        val qt = tokens(q).toSet()
        val k1 = 1.2; val b = 0.75; val N = items.size
        return items.mapIndexed { i, m ->
            val d = docs[i]; val tf = d.groupingBy { it }.eachCount()
            var s = 0.0
            for (t in qt) { val f = tf[t] ?: continue; val idf = ln(1 + (N - (df[t] ?: 0) + 0.5) / ((df[t] ?: 0) + 0.5)); s += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * d.size / avg)) }
            val ageDays = (now - m.at) / 86_400_000.0
            m to s * kotlin.math.exp(-ageDays / 180.0) * (1 + 0.05 * m.uses)
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(n)
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 파이프라인 · 스킬 · 사용자 명령어
// ────────────────────────────────────────────────────────────────────────────
data class PipelineStep(val kind: String, val cmd: String, val args: Map<String, String>, val prompt: String = "") {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("cmd", cmd).put("args", JSONObject(args as Map<*, *>)).put("prompt", prompt)
    companion object {
        fun from(o: JSONObject) = PipelineStep(o.optString("kind", "cmd"), o.optString("cmd"),
            o.optJSONObject("args")?.let { a -> a.keys().asSequence().associateWith { a.optString(it) } } ?: emptyMap(), o.optString("prompt"))
        /** "web.search q=공모전 n=5" 형태 한 줄 */
        fun parseLine(line: String): PipelineStep? {
            val t = line.trim().trimStart('-', '*', ' ')
            if (t.isEmpty()) return null
            if (t.startsWith("ai:") || t.startsWith("AI:")) return PipelineStep("ai", "", emptyMap(), t.substringAfter(':').trim())
            if (t.startsWith("approve") || t.startsWith("승인")) return PipelineStep("approval", "", emptyMap(), t.substringAfter(' ', "계속할까요?"))
            val name = t.substringBefore(' ')
            val rest = t.substringAfter(' ', "")
            val args = LinkedHashMap<String, String>()
            Regex("(\\w+)=(\"[^\"]*\"|\\S+)").findAll(rest).forEach { args[it.groupValues[1]] = it.groupValues[2].trim('"') }
            return PipelineStep("cmd", name, args)
        }
    }
}

data class Pipeline(val id: String, val name: String, val desc: String, val steps: List<PipelineStep>) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("desc", desc).put("steps", JSONArray(steps.map { it.toJson() }))
    companion object {
        fun from(o: JSONObject) = Pipeline(o.optString("id"), o.optString("name"), o.optString("desc"),
            (o.optJSONArray("steps") ?: JSONArray()).let { a -> (0 until a.length()).map { PipelineStep.from(a.getJSONObject(it)) } })
    }
}

object PipelineCompiler {
    data class Compiled(val steps: List<PipelineStep>, val issues: List<String>, val insertedApprovals: Int)

    /** E2+ 단계 앞에 승인 노드가 없으면 자동 삽입한다(사용자가 안전장치를 빼먹어도 복구). */
    fun compile(p: Pipeline): Compiled {
        val out = ArrayList<PipelineStep>(); val issues = ArrayList<String>(); var inserted = 0
        p.steps.forEachIndexed { i, s ->
            if (s.kind == "cmd") {
                val spec = CommandCatalog.find(s.cmd)
                if (spec == null) { issues += "${i + 1}단계: 없는 명령 '${s.cmd}'"; return@forEachIndexed }
                val missing = spec.params.filter { it.required && s.args[it.name].isNullOrBlank() && !(s.args.values.any { v -> v.contains("{") }) }
                if (missing.isNotEmpty()) issues += "${i + 1}단계 ${spec.id}: 필수 인자 ${missing.joinToString { it.name }} (실행 때 {input}/{prev}로 채워질 수 있음)"
                if (spec.effect.level >= Effect.EXTERNAL.level && out.lastOrNull()?.kind != "approval") {
                    out += PipelineStep("approval", "", emptyMap(), "${spec.title}을(를) 실행할까요?"); inserted++
                }
            }
            out += s
        }
        return Compiled(out, issues, inserted)
    }

    fun fill(v: String, input: String, prev: String) = v.replace("{input}", input).replace("{prev}", prev).replace("{today}", java.time.LocalDate.now().toString())
}

data class Skill(val id: String, val name: String, val whenToUse: String, val guide: String, val builtIn: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("when", whenToUse).put("guide", guide).put("builtIn", builtIn)
    companion object {
        fun from(o: JSONObject) = Skill(o.optString("id"), o.optString("name"), o.optString("when"), o.optString("guide"), o.optBoolean("builtIn"))
        val BUILT_IN = listOf(
            Skill("sk_contest", "공모전 준비", "공모전을 찾고 지원 준비를 할 때",
                "1) contest.search로 3개 이상 포털을 확인한다 2) 마감·자격·상금·제출물·주최를 표로 정리한다(출처 URL 필수) 3) 마감이 임박한 순으로 정렬하고 task.ask로 사용자에게 고르게 한다 4) 선택된 공모전의 요강을 web.fetch로 정독한다 5) 제출물 초안·체크리스트·일정(cal.deadline)을 만든다 6) ai.verify로 요구사항 커버리지를 검사한다 7) 결과물은 task.artifact로 저장한다", true),
            Skill("sk_research", "깊은 조사", "사실 확인이 중요한 조사·비교",
                "하위 질문 3~5개로 쪼개고 각각 web.search → web.fetch로 1차 출처를 읽는다. 주장마다 출처 URL을 붙이고, 반대 근거도 찾는다. 확인 못 한 것은 '확인되지 않음'이라고 쓴다.", true),
            Skill("sk_mail", "메일 정리", "메일함 정리·답장 초안",
                "mail.list/mail.search로 읽고, 중요도(답장 필요/참고/광고)로 분류해 표로 보고한다. 답장은 mail.draft로 먼저 만들고, 보내기는 사용자가 원할 때만 mail.send(승인)로 한다.", true),
            Skill("sk_daily", "하루 브리핑", "아침·저녁 브리핑",
                "cal.today, data.weather(사용자 지역), mail.unread, life.dday_board를 모아 5줄 이내로 요약한다.", true)
        )
    }
}

data class CustomCommand(val id: String, val trigger: String, val expandsTo: String, val asMission: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("trigger", trigger).put("expands", expandsTo).put("mission", asMission)
    companion object { fun from(o: JSONObject) = CustomCommand(o.optString("id"), o.optString("trigger"), o.optString("expands"), o.optBoolean("mission")) }
}

// ────────────────────────────────────────────────────────────────────────────
// 사용자 입력 라우팅 — 결정론 우선. /명령, 단축 명령, 작업 의도 판별.
// ────────────────────────────────────────────────────────────────────────────
object InputRouter {
    sealed interface Route {
        data class Direct(val call: CommandCall) : Route
        data class Mission(val goal: String) : Route
        data class Quick(val text: String) : Route
        data class Custom(val cmd: CustomCommand, val rest: String) : Route
    }

    private val missionVerbs = listOf("찾아", "준비해", "만들어", "정리해", "조사해", "보내", "예약", "작성해", "모아", "비교해", "분석해", "계획", "추천해", "알아봐", "검토해", "써줘", "짜줘", "등록해", "확인해서", "처리해", "제작")

    fun looksLikeMission(t: String): Boolean = t.length >= 8 && missionVerbs.any { t.contains(it) }

    fun route(text: String, customs: List<CustomCommand>, forceMission: Boolean?): Route {
        val t = text.trim()
        if (t.startsWith("/")) {
            val head = t.substringBefore(' ').removePrefix("/")
            customs.firstOrNull { it.trigger.removePrefix("/").equals(head, true) }?.let { return Route.Custom(it, t.substringAfter(' ', "")) }
            val spec = CommandCatalog.find(head)
            if (spec != null) return Route.Direct(CommandCall("u1", spec.id, parseArgs(spec.id, t.substringAfter(' ', ""))))
        }
        return when (forceMission) {
            true -> Route.Mission(t)
            false -> Route.Quick(t)
            null -> if (looksLikeMission(t)) Route.Mission(t) else Route.Quick(t)
        }
    }

    /** "/검색 서울 공모전" → 첫 필수 인자에 나머지 전체. key=value 도 허용. */
    fun parseArgs(cmdId: String, rest: String): Map<String, String> {
        val spec = CommandCatalog.find(cmdId) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val kv = Regex("(\\w+)=(\"[^\"]*\"|\\S+)").findAll(rest).toList()
        if (kv.isNotEmpty() && kv.all { m -> spec.params.any { it.name == m.groupValues[1] } }) {
            kv.forEach { out[it.groupValues[1]] = it.groupValues[2].trim('"') }
            val leftover = kv.fold(rest) { acc, m -> acc.replace(m.value, "") }.trim()
            if (leftover.isNotEmpty()) spec.params.firstOrNull { it.name !in out }?.let { out[it.name] = leftover }
        } else if (rest.isNotBlank()) {
            val first = spec.params.firstOrNull { it.required } ?: spec.params.firstOrNull()
            if (first != null) out[first.name] = rest.trim()
        }
        return out
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 내보내기 마스킹 — 기본값은 마스킹.
// ────────────────────────────────────────────────────────────────────────────
object Masker {
    private val rules = listOf(
        Regex("sk-[A-Za-z0-9_-]{16,}") to "[MASKED_API_KEY]",
        Regex("AIza[0-9A-Za-z_-]{30,}") to "[MASKED_API_KEY]",
        Regex("Bearer\\s+[A-Za-z0-9._~+/=-]{10,}") to "Bearer [MASKED_TOKEN]",
        Regex("ya29\\.[0-9A-Za-z_-]+") to "[MASKED_OAUTH_TOKEN]",
        Regex("(?i)(access_token|password|passwd|api_key|apikey|secret)\\s*[=:]\\s*\\S+") to "$1=[MASKED]",
        Regex("/data/(user|data)/[^\\s\"']+") to "[MASKED_PATH]"
    )
    fun mask(s: String, phones: Boolean = false): String {
        var t = s
        for ((r, rep) in rules) t = r.replace(t, rep)
        if (phones) t = Regex("01[016789][- .]?\\d{3,4}[- .]?\\d{4}").replace(t, "[MASKED_PHONE]")
        return t
    }
}
