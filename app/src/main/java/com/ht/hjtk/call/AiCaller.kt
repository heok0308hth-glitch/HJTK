package com.ht.hjtk.call

import com.ht.hjtk.Hjtk
import com.ht.hjtk.core.command.PhoneGuard
import com.ht.hjtk.data.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 무료 AI 통화 — '갈래 대본'.
 *
 * 안드로이드는 일반 앱이 통화 중 마이크를 듣지 못하게 막는다(접근성·시스템 앱만 예외, 둘 다 쓸 수 없다).
 * 그래서 귀는 사용자, 입은 AI가 맡는다:
 *  1) 걸기 전에 AI가 목적에 맞는 대본을 짠다 — 첫 인사 + 상대 반응별 대답 6~8개 + 마무리 + 기본 대답
 *  2) 모든 줄을 '내 목소리'로 미리 만들어 둔다 → 통화 중에는 탭 즉시 재생(기기 합성 대기 0초)
 *  3) 통화는 스피커로. 상대 말을 듣고 해당 반응을 탭하면 그 줄이 나간다
 *  4) 대본에 없는 상황은 '상대가 한 말'을 짧게 적으면 AI가 다음 말을 새로 써서 말한다
 * 통화 요금 외에는 무료. 첫 문장은 늘 'AI 비서'임을 밝힌다(바꿀 수 없다).
 */
class AiCaller(private val h: Hjtk) {
    data class Say(val key: String, val label: String, val text: String, val ready: Boolean = false, val fixed: Boolean = false)
    data class Turn(val who: String, val text: String, val at: Long = System.currentTimeMillis())
    enum class Phase { IDLE, PLANNING, RENDERING, READY, CALLING, LIVE, ENDED, ERROR }
    data class Session(
        val number: String = "", val goal: String = "", val facts: String = "",
        val phase: Phase = Phase.IDLE, val note: String = "",
        val opening: Say? = null, val branches: List<Say> = emptyList(), val closing: Say? = null, val basics: List<Say> = emptyList(),
        val transcript: List<Turn> = emptyList(), val speaking: String? = null, val thinking: Boolean = false,
        val rendered: Int = 0, val total: Int = 0, val convId: String? = null,
    ) { fun all(): List<Say> = listOfNotNull(opening) + branches + listOfNotNull(closing) + basics }

    val session = MutableStateFlow(Session())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val log = File(h.app.filesDir, "hjtk/calls.json")

    private fun user(): String? = h.store.settings.value.userName.ifBlank { null }

    /** 대답 줄 중 늘 들어가는 것(대본과 무관하게 미리 만들어 둔다) */
    private fun basics(): List<Say> = listOf(
        Say("b_repeat", "다시 말해 달라", "죄송한데, 한 번만 다시 말씀해 주시겠어요?", fixed = true),
        Say("b_wait", "잠깐 기다려", "네, 잠시만 기다려 주세요.", fixed = true),
        Say("b_ok", "알겠다", "네, 알겠습니다.", fixed = true),
        Say("b_ai", "AI냐고 물음", "네, 저는 AI 비서예요. ${user() ?: "고객"}님 대신 연락드렸어요.", fixed = true),
        Say("b_later", "본인이 직접", "그 부분은 ${user() ?: "고객"}님이 직접 확인하고 다시 연락드릴게요.", fixed = true),
        Say("b_bye", "끊기 인사", "네, 감사합니다. 좋은 하루 보내세요.", fixed = true),
    )

    fun history(): List<Pair<String, Long>> = runCatching {
        val a = JSONArray(log.readText()); (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("n") to o.getLong("t") } }
    }.getOrDefault(emptyList())

    private fun remember(n: String) = runCatching {
        val a = runCatching { JSONArray(log.readText()) }.getOrDefault(JSONArray())
        a.put(JSONObject().put("n", n).put("t", System.currentTimeMillis()).put("via", "device"))
        val keep = JSONArray(); for (i in (a.length() - 50).coerceAtLeast(0) until a.length()) keep.put(a.get(i))
        log.parentFile?.mkdirs(); log.writeText(keep.toString())
    }

    /** 1~2단계: 대본 짜기 + 전부 미리 녹음. 막힐 이유가 있으면 그 이유를 돌려준다 */
    fun prepare(number: String, goal: String, facts: String): String? {
        PhoneGuard.check(number, history(), System.currentTimeMillis())?.let { return it }
        if (!h.voice.packReady()) return "음성 모델을 먼저 받아 주세요(AI 전화 화면 01)"
        job?.cancel()
        session.value = Session(number = number, goal = goal, facts = facts, phase = Phase.PLANNING, note = "대본을 짜는 중…")
        job = scope.launch {
            try {
                val plan = plan(goal, facts)
                val opening = Say("open", "첫 인사", PhoneGuard.disclosure(user()) + " " + plan.first)
                val closing = Say("close", "마무리", plan.third)
                session.update { it.copy(opening = opening, branches = plan.second, closing = closing, basics = basics(), phase = Phase.RENDERING, note = "내 목소리로 미리 녹음하는 중") }
                render()
            } catch (e: Exception) {
                session.update { it.copy(phase = Phase.ERROR, note = "대본을 만들지 못했어요: ${e.message?.take(80)}") }
            }
        }
        return null
    }

    /** 모든 줄을 미리 합성(통화 중 대기 0초) */
    private suspend fun render() {
        val todo = session.value.all()
        session.update { it.copy(total = todo.size, rendered = todo.count { s -> h.voice.cached(s.text) != null }) }
        for (s in todo) {
            if (h.voice.cached(s.text) == null) runCatching { h.voice.sentence(s.text, fast = true) }
            mark(s.key)
        }
        session.update { it.copy(phase = Phase.READY, note = "준비 완료 · 걸면 스피커로 바뀌고 첫 인사가 나가요") }
    }

    private fun mark(key: String) = session.update { s ->
        fun f(x: Say) = if (x.key == key) x.copy(ready = true) else x
        s.copy(opening = s.opening?.let(::f), branches = s.branches.map(::f), closing = s.closing?.let(::f), basics = s.basics.map(::f), rendered = s.rendered + 1)
    }

    /** 한 줄 다시 만들기(발음이 어색할 때) */
    fun redo(key: String) {
        val s = session.value.all().firstOrNull { it.key == key } ?: return
        scope.launch { runCatching { h.voice.sentence(s.text, fast = true, fresh = true) }; preview(key) }
    }

    /** 한 줄 고치기: 글을 바꾸면 그 자리에서 다시 녹음 */
    fun edit(key: String, text: String) {
        session.update { s ->
            fun f(x: Say) = if (x.key == key) x.copy(text = text, ready = false) else x
            s.copy(opening = s.opening?.let(::f), branches = s.branches.map(::f), closing = s.closing?.let(::f), basics = s.basics.map(::f))
        }
        scope.launch { runCatching { h.voice.sentence(text, fast = true) }; mark(key) }
    }

    fun preview(key: String) { val s = session.value.all().firstOrNull { it.key == key } ?: return; scope.launch { h.voice.speak(s.text, call = false) } }

    /** 3단계: 걸기. 상대가 받으면 스피커로 바꾸고 첫 인사 */
    fun dial(): String? {
        val s = session.value
        if (s.phase != Phase.READY) return "아직 준비 중이에요"
        PhoneGuard.check(s.number, history(), System.currentTimeMillis())?.let { return it }
        // 기본 전화 앱이 아니면 시스템 전화 앱 화면이 떠서 HJTK 밖으로 나가고, 스피커·끊기도 다룰 수 없다
        if (!CallCenter.isDefaultDialer(h.app)) return "AI 통화는 HJTK가 기본 전화 앱일 때 걸 수 있어요(AI 전화 화면 02)"
        CallCenter.place(h.app, s.number, ai = true)?.let { return it }
        remember(PhoneGuard.normalize(s.number) ?: s.number)
        val conv = h.store.newConversation("AI 통화 · ${s.number}")
        session.update { it.copy(phase = Phase.CALLING, note = "거는 중…", convId = conv.id, transcript = emptyList()) }
        h.store.msg(conv.id, Role.SYSTEM, "AI 통화 시작 · 목적: ${s.goal}", "note")
        job = scope.launch {
            val live = withTimeoutOrNull(70_000) { CallCenter.lines.first { l -> l.any { it.ai && it.active } } }
            if (live == null) { finish("상대가 받지 않았어요"); return@launch }
            session.update { it.copy(phase = Phase.LIVE, note = "통화 중 · 상대 말을 듣고 반응을 눌러 주세요") }
            CallCenter.speaker(true)
            delay(900)   // 상대가 "여보세요"를 말할 틈
            say(session.value.opening ?: return@launch)
            // 통화가 끝나면 정리
            CallCenter.lines.first { l -> l.none { it.ai && !it.ended } }
            finish("통화 종료")
        }
        return null
    }

    /** 4단계: 줄 말하기(미리 만든 소리는 즉시) */
    fun say(s: Say) { sayText(s.text) }

    private var speakJob: Job? = null
    fun sayText(text: String) {
        speakJob?.cancel(); h.voice.stop()
        speakJob = scope.launch {
            val cached = h.voice.cached(text) != null
            session.update { it.copy(speaking = text) }
            if (!cached) h.voice.cached(FILLER)?.let { h.voice.play(h.voice.sentence(FILLER), call = true) }
            runCatching { h.voice.speak(text, call = true) }
            log("AI", text)
            session.update { it.copy(speaking = null) }
        }
    }

    /** 대본 밖 상황: 상대가 한 말을 짧게 적으면 AI가 다음 말을 쓴다 */
    fun heard(note: String) {
        if (note.isBlank()) return
        log("상대", note)
        session.update { it.copy(thinking = true) }
        scope.launch {
            val filler = launch { delay(1200); h.voice.cached(FILLER)?.let { h.voice.play(h.voice.sentence(FILLER), call = true) } }
            val s = session.value
            val prompt = buildString {
                appendLine("[전화 통화 중 · 다음 한 마디만] 너는 ${user() ?: "사용자"}님을 대신해 통화 중인 AI 비서다. 이미 AI임을 밝혔다.")
                appendLine("목적: ${s.goal}"); if (s.facts.isNotBlank()) appendLine("말해도 되는 정보: ${s.facts}")
                appendLine("규칙: 존댓말 · 1~2문장 · 40자 안팎 · 결제/계약/개인정보는 동의하지 말고 '본인이 직접 확인한다'고. 따옴표·설명 없이 할 말만.")
                appendLine("지금까지:"); s.transcript.takeLast(10).forEach { appendLine("${it.who}: ${it.text}") }
                append("AI:")
            }
            val line = runCatching { h.runner.callAsk(prompt, "call:${s.convId}") }.getOrNull()?.lines()?.firstOrNull { it.isNotBlank() }
                ?.trim()?.trim('"', '“', '”')?.removePrefix("AI:")?.trim()?.take(120)
            filler.cancel()
            session.update { it.copy(thinking = false) }
            if (line.isNullOrBlank()) { say(basics().first { it.key == "b_wait" }); return@launch }
            sayText(line)
        }
    }

    fun hangup() { CallCenter.lines.value.firstOrNull { it.ai && !it.ended }?.let { CallCenter.end(it.id) } }

    fun reset() { job?.cancel(); h.voice.stop(); session.value = Session() }

    private fun log(who: String, text: String) {
        session.update { it.copy(transcript = it.transcript + Turn(who, text)) }
        session.value.convId?.let { h.store.msg(it, if (who == "AI") Role.AI else Role.USER, text, meta = mapOf("who" to if (who == "AI") "AI 통화" else "상대(메모)")) }
    }

    private fun finish(why: String) {
        h.voice.stop()
        scope.launch { kotlinx.coroutines.delay(60_000); if (session.value.phase == Phase.ENDED) h.voice.release() }   // 통화가 끝나면 1분 뒤 엔진 메모리(약 1GB)를 돌려준다
        val s = session.value
        session.update { it.copy(phase = Phase.ENDED, note = why, speaking = null, thinking = false) }
        s.convId?.let { h.store.msg(it, Role.SYSTEM, "$why · ${s.transcript.size}줄", "note") }
    }

    /** 결과 요약(명령 결과로 돌려줄 때) */
    fun summary(): String = session.value.let { s -> "${s.note}\n" + s.transcript.joinToString("\n") { "${it.who}: ${it.text}" } }

    /** AI가 대본을 짠다 → (용건, 갈래들, 마무리) */
    private suspend fun plan(goal: String, facts: String): Triple<String, List<Say>, String> {
        val prompt = """
[전화 대본 · JSON만] ${user() ?: "사용자"}님을 대신해 AI 비서가 전화를 건다. 첫 문장에 'AI 비서'라고 밝히는 문장이 따로 붙으니 반복하지 마라.
목적: $goal
${if (facts.isNotBlank()) "말해도 되는 정보: $facts" else "말해도 되는 정보: 없음(이름 외에는 말하지 말 것)"}
출력 형식(이 JSON 하나만):
{"opening":"용건 1~2문장","branches":[{"if":"상대 반응(4~10자)","say":"그때 할 말"}],"closing":"마무리 인사 1문장"}
규칙: 갈래 6~8개(긍정·부정·다른 시간/조건 제안·가격/조건 질문·이름/연락처 질문·담당자 연결 등 이 목적에서 실제로 나올 반응). 존댓말, 문장당 40자 안팎. 결제·계약·취소는 동의하지 말고 본인이 직접 확인한다고. 개인정보(카드·주민번호·비밀번호)는 말하지 않는다.
""".trim()
        val raw = h.runner.callAsk(prompt, "plan:" + System.currentTimeMillis())
        val o = Regex("""\{[\s\S]*\}""").find(raw)?.value?.let { runCatching { JSONObject(it) }.getOrNull() } ?: error("AI가 대본 형식을 지키지 않았어요")
        val br = o.optJSONArray("branches") ?: JSONArray()
        val list = (0 until br.length()).mapNotNull { i -> br.optJSONObject(i)?.let { b -> b.optString("say").takeIf { it.isNotBlank() }?.let { Say("br$i", b.optString("if").ifBlank { "반응 ${i + 1}" }.take(14), it.take(140)) } } }.take(8)
        val opening = o.optString("opening").ifBlank { goal }.take(160)
        val closing = o.optString("closing").ifBlank { "네, 감사합니다. 좋은 하루 보내세요." }.take(100)
        return Triple(opening, list, closing)
    }

    companion object {
        const val FILLER = "음, 잠시만요."
        const val INCOMING_PREFIX = "안녕하세요, 저는 AI 비서예요. "
        /** 걸려 온 전화를 내 목소리로 대신 받을 때 고르는 말(미리 만들어 둔다) */
        val INCOMING = listOf("지금 통화가 어려워요. 끝나는 대로 다시 연락드릴게요.", "지금 운전 중이에요. 이따 다시 전화드릴게요.", "회의 중이라 문자로 남겨 주시면 확인할게요.")
    }

    /** 앱이 켜질 때 짧은 말 몇 개를 미리 만들어 둔다(첫 통화부터 빠르게) */
    fun warmUp() {
        if (!h.voice.packReady() || warmed) return
        warmed = true
        scope.launch { (listOf(FILLER) + basics().map { it.text } + INCOMING.flatMap { com.ht.voice.KoText.sentences(INCOMING_PREFIX + it) }).forEach { t -> if (h.voice.cached(t) == null) runCatching { h.voice.sentence(t) } } }
    }
    @Volatile private var warmed = false
}
