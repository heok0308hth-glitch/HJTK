package com.ht.hjtk.call

import com.ht.hjtk.Hjtk
import com.ht.hjtk.core.command.PhoneGuard
import com.ht.hjtk.data.Role
import com.ht.hjtk.engine.Thermal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AI 통화(3.1) — 상황을 미리 나누지 않는다. AI가 듣고, 생각하고, 내 목소리로 말한다.
 *  듣기: '통화 귀'(접근성)가 켜져 있으면 스피커로 나오는 상대 목소리를 받아 적는다. 꺼져 있으면 상대 말을 한 줄로 적어 주면 된다.
 *  생각: 대화 AI(키 있는 빠른 모델 → 없으면 로그인한 웹 AI/DeepSeek)가 목적·대화록을 보고 다음 한 마디를 쓴다.
 *  말하기: 기기 음성 엔진(내 목소리). 첫 인사는 걸기 전에 미리 만들어 두고, 생각하는 동안은 '네, 잠시만요'를 먼저 낸다.
 * 첫 문장은 늘 'AI 비서'임을 밝힌다(바꿀 수 없다). 목적을 이루거나 상대가 끝내면 인사하고 끊는다.
 */
class AiCaller(private val h: Hjtk) {
    data class Say(val key: String, val label: String, val text: String, val ready: Boolean = false, val fixed: Boolean = false)
    data class Turn(val who: String, val text: String, val at: Long = System.currentTimeMillis())
    enum class Phase { IDLE, PLANNING, RENDERING, READY, CALLING, LIVE, ENDED, ERROR }
    enum class Mind { IDLE, LISTENING, THINKING, SPEAKING }
    data class Session(
        val number: String = "", val goal: String = "", val facts: String = "",
        val phase: Phase = Phase.IDLE, val note: String = "", val mind: Mind = Mind.IDLE,
        val opening: Say? = null, val transcript: List<Turn> = emptyList(), val speaking: String? = null,
        val convId: String? = null, val autoDial: Boolean = false, val paused: Boolean = false,
        // 예전 화면 호환(3.0 갈래 대본) — 더 이상 쓰지 않는다
        val branches: List<Say> = emptyList(), val closing: Say? = null, val basics: List<Say> = emptyList(), val thinking: Boolean = false, val rendered: Int = 0, val total: Int = 0,
    ) { fun all(): List<Say> = listOfNotNull(opening) }

    val session = MutableStateFlow(Session())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var speakJob: Job? = null
    private val turnLock = Mutex()
    private val log = File(h.app.filesDir, "hjtk/calls.json")
    private fun user(): String? = h.store.settings.value.userName.ifBlank { null }

    fun history(): List<Pair<String, Long>> = runCatching {
        val a = JSONArray(log.readText()); (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("n") to o.getLong("t") } }
    }.getOrDefault(emptyList())
    private fun remember(n: String) = runCatching {
        val a = runCatching { JSONArray(log.readText()) }.getOrDefault(JSONArray())
        a.put(JSONObject().put("n", n).put("t", System.currentTimeMillis()).put("via", "device"))
        val keep = JSONArray(); for (i in (a.length() - 50).coerceAtLeast(0) until a.length()) keep.put(a.get(i))
        log.parentFile?.mkdirs(); log.writeText(keep.toString())
    }

    /** 버튼 하나: 첫 인사를 준비하고, 준비되면 바로 건다 */
    fun start(number: String, goal: String, facts: String): String? = prepare(number, goal, facts, autoDial = true)

    fun prepare(number: String, goal: String, facts: String, autoDial: Boolean = false): String? {
        PhoneGuard.check(number, history(), System.currentTimeMillis())?.let { return it }
        if (!h.voice.packReady()) return "음성 엔진을 먼저 받아 주세요"
        if (autoDial && !CallCenter.isDefaultDialer(h.app)) return "HJTK를 기본 전화 앱으로 지정해 주세요"
        job?.cancel()
        session.value = Session(number = number, goal = goal, facts = facts, phase = Phase.PLANNING, note = "첫 인사를 쓰는 중…", autoDial = autoDial)
        job = scope.launch {
            try {
                val line = runCatching { h.runner.callAsk(openingPrompt(goal, facts), "open:" + System.currentTimeMillis()) }.getOrNull()
                    ?.lines()?.firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '“', '”')?.take(140)?.ifBlank { null } ?: "$goal 때문에 연락드렸어요."
                val opening = Say("open", "첫 인사", PhoneGuard.disclosure(user()) + " " + line)
                session.update { it.copy(opening = opening, phase = Phase.RENDERING, note = "내 목소리로 첫 인사를 만드는 중") }
                Thermal.coolDown(20_000)
                for (t in listOf(opening.text, FILLER, REPEAT)) if (h.voice.cached(t) == null) runCatching { h.voice.sentence(t) }
                session.update { it.copy(opening = opening.copy(ready = true), phase = Phase.READY, note = "준비 완료") }
                if (session.value.autoDial) dial()?.let { err -> session.update { it.copy(phase = Phase.ERROR, note = err) } }
            } catch (e: Exception) { session.update { it.copy(phase = Phase.ERROR, note = "준비하지 못했어요: ${e.message?.take(80)}") } }
        }
        return null
    }

    fun dial(): String? {
        val s = session.value
        if (s.phase != Phase.READY) return "아직 준비 중이에요"
        PhoneGuard.check(s.number, history(), System.currentTimeMillis())?.let { return it }
        if (!CallCenter.isDefaultDialer(h.app)) return "AI 통화는 HJTK가 기본 전화 앱일 때 걸 수 있어요"
        CallCenter.place(h.app, s.number, ai = true)?.let { return it }
        remember(PhoneGuard.normalize(s.number) ?: s.number)
        val conv = h.store.newConversation("AI 통화 · ${s.number}")
        session.update { it.copy(phase = Phase.CALLING, note = "거는 중…", convId = conv.id, transcript = emptyList()) }
        h.store.msg(conv.id, Role.SYSTEM, "AI 통화 시작 · 목적: ${s.goal}", "note")
        job = scope.launch {
            val live = withTimeoutOrNull(70_000) { CallCenter.lines.first { l -> l.any { it.ai && it.active } } }
            if (live == null) { finish("상대가 받지 않았어요"); return@launch }
            session.update { it.copy(phase = Phase.LIVE, note = "통화 중") }
            CallCenter.speaker(true)
            if (Ear.isOn(h.app)) Ear.start { pcm -> onUtterance(pcm) }
            delay(900)
            speakNow(session.value.opening?.text ?: return@launch)
            CallCenter.lines.first { l -> l.none { it.ai && !it.ended } }
            finish("통화 종료")
        }
        return null
    }

    private suspend fun onUtterance(pcm: ShortArray) {
        if (session.value.paused || session.value.phase != Phase.LIVE) return
        session.update { it.copy(mind = Mind.THINKING) }
        val text = Ear.transcribe(h.app, pcm)
        if (text == null) { session.update { it.copy(mind = Mind.LISTENING, note = "알아듣지 못했어요 · 계속 듣는 중") }; return }
        heard(text)
    }

    /** 상대가 한 말(받아 적은 것 또는 사용자가 적은 것) → AI가 다음 한 마디 */
    fun heard(note: String) {
        val t = note.trim(); if (t.isEmpty()) return
        log("상대", t)
        scope.launch {
            turnLock.withLock {
                if (session.value.paused) return@withLock
                session.update { it.copy(mind = Mind.THINKING) }
                val filler = launch { delay(1_500); playCached(FILLER) }
                val reply = runCatching { h.runner.callAsk(replyPrompt(), "call:${session.value.convId}") }.getOrNull()
                    ?.lines()?.filter { it.isNotBlank() }?.joinToString(" ")?.trim()?.trim('"', '“', '”')?.removePrefix("AI:")?.trim()?.take(160)
                filler.cancel(); filler.join()
                if (reply.isNullOrBlank()) { speakNow(REPEAT); return@withLock }
                val end = reply.contains("[끝]")
                speakNow(reply.replace("[끝]", "").trim().ifBlank { BYE })
                if (end) { delay(600); hangup() }
            }
        }
    }

    /** 사용자가 대신 말하게 하기(직접 문장) */
    fun sayText(text: String) { scope.launch { speakNow(text) } }
    fun say(s: Say) = sayText(s.text)

    fun pause(on: Boolean) = session.update { it.copy(paused = on, note = if (on) "AI 멈춤 · 직접 말해도 돼요" else "AI가 다시 이어서 해요") }

    private suspend fun speakNow(text: String) {
        speakJob?.cancel(); h.voice.stop()
        Ear.muted = true
        session.update { it.copy(speaking = text, mind = Mind.SPEAKING) }
        try { runCatching { h.voice.speak(text, call = true) } } finally {
            log("AI", text)
            delay(450)                       // 스피커 잔향이 끝날 때까지 듣지 않는다
            Ear.muted = false
            session.update { it.copy(speaking = null, mind = if (Ear.listening.value) Mind.LISTENING else Mind.IDLE) }
        }
    }

    private suspend fun playCached(t: String) { if (h.voice.cached(t) != null) { Ear.muted = true; runCatching { h.voice.play(h.voice.sentence(t), call = true) }; Ear.muted = false } }

    fun hangup() { CallCenter.lines.value.firstOrNull { it.ai && !it.ended }?.let { CallCenter.end(it.id) } }
    fun reset() { job?.cancel(); Ear.stop(); h.voice.stop(); session.value = Session() }

    private fun log(who: String, text: String) {
        session.update { it.copy(transcript = it.transcript + Turn(who, text)) }
        session.value.convId?.let { h.store.msg(it, if (who == "AI") Role.AI else Role.USER, text, meta = mapOf("who" to if (who == "AI") "AI 통화" else "상대")) }
    }

    private fun finish(why: String) {
        Ear.stop(); h.voice.stop()
        val s = session.value
        session.update { it.copy(phase = Phase.ENDED, note = why, speaking = null, mind = Mind.IDLE) }
        s.convId?.let { h.store.msg(it, Role.SYSTEM, "$why · ${s.transcript.size}마디", "note") }
        scope.launch { delay(60_000); if (session.value.phase == Phase.ENDED) h.voice.release() }
    }

    fun summary(): String = session.value.let { s -> "${s.note}\n" + s.transcript.joinToString("\n") { "${it.who}: ${it.text}" } }

    private fun openingPrompt(goal: String, facts: String) = """
[전화 첫마디 · 한 줄만] ${user() ?: "사용자"}님을 대신해 AI 비서가 전화를 건다. 'AI 비서'라고 밝히는 문장은 앞에 따로 붙으니 쓰지 마라.
목적: $goal${if (facts.isNotBlank()) "\n말해도 되는 정보: $facts" else ""}
용건을 존댓말 1~2문장(50자 안팎)으로. 따옴표·설명 없이 할 말만.""".trim()

    private fun replyPrompt(): String {
        val s = session.value
        return buildString {
            appendLine("[전화 통화 중 · 다음 한 마디만] 너는 ${user() ?: "사용자"}님을 대신해 통화 중인 AI 비서다. 이미 AI임을 밝혔다.")
            appendLine("목적: ${s.goal}"); if (s.facts.isNotBlank()) appendLine("말해도 되는 정보: ${s.facts}")
            appendLine("규칙: 존댓말 · 1~2문장 · 60자 안팎 · 상대 말이 끊겨 들렸으면 자연스럽게 되묻기 · 결제/계약/취소는 동의하지 말고 본인이 직접 확인한다고 · 카드·주민번호·비밀번호는 말하지 않기 · AI냐고 물으면 그렇다고.")
            appendLine("목적을 이뤘거나 상대가 끝내려 하면 짧게 인사하고 맨 끝에 [끝]을 붙여라. 따옴표·설명 없이 할 말만.")
            appendLine("지금까지:"); s.transcript.takeLast(14).forEach { appendLine("${it.who}: ${it.text}") }
            append("AI:")
        }
    }

    companion object {
        const val FILLER = "네, 잠시만요."
        const val REPEAT = "죄송한데, 한 번만 다시 말씀해 주시겠어요?"
        const val BYE = "네, 감사합니다. 좋은 하루 보내세요."
        const val INCOMING_PREFIX = "안녕하세요, 저는 AI 비서예요. "
        val INCOMING = listOf("지금 통화가 어려워요. 끝나는 대로 다시 연락드릴게요.", "지금 운전 중이에요. 이따 다시 전화드릴게요.", "회의 중이라 문자로 남겨 주시면 확인할게요.")
    }

    @Volatile private var warmed = false
    fun warmUp() {
        if (!h.voice.packReady() || warmed || Thermal.level.value >= 1 && !Thermal.charging.value) return
        warmed = true
        scope.launch { (listOf(FILLER, REPEAT, BYE) + INCOMING.flatMap { com.ht.voice.KoText.sentences(INCOMING_PREFIX + it) }).forEach { t -> if (h.voice.cached(t) == null) runCatching { h.voice.sentence(t) } } }
    }

    // 3.0 화면 호환용(없어진 기능): 미리보기·다시·고치기는 첫 인사에만
    fun preview(key: String) { val s = session.value.opening ?: return; scope.launch { h.voice.speak(s.text) } }
    fun redo(key: String) { val s = session.value.opening ?: return; scope.launch { runCatching { h.voice.sentence(s.text, fresh = true) }; preview(key) } }
    fun edit(key: String, text: String) { session.update { it.copy(opening = it.opening?.copy(text = text, ready = false)) }; scope.launch { runCatching { h.voice.sentence(text) }; session.update { it.copy(opening = it.opening?.copy(ready = true)) } } }
}
