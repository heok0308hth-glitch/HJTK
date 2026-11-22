package com.ht.hjtk.core.stream

import org.json.JSONArray
import org.json.JSONObject

/**
 * P0 경로: 페이지 자신의 fetch/XHR에서 tee로 받은 SSE를 해석한다.
 * DOM 구조에 계약을 걸지 않고 "스트림의 의미"에 계약을 건다.
 */
class SseParser(private val onData: (String) -> Unit) {
    private val line = java.io.ByteArrayOutputStream()
    private val data = StringBuilder()
    private var lastCR = false

    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    /** 바이트 단위로 줄을 자른다. UTF-8 연속 바이트는 모두 0x80 이상이므로 한글이 청크 경계에서 깨지지 않는다. */
    fun feed(b: ByteArray, len: Int = b.size) {
        for (i in 0 until len) {
            when (val v = b[i].toInt() and 0xFF) {
                0x0A -> if (lastCR) lastCR = false else endLine()
                0x0D -> { lastCR = true; endLine() }
                else -> { lastCR = false; line.write(v) }
            }
        }
    }

    private fun endLine() {
        if (line.size() == 0) { dispatch(); return }
        val l = String(line.toByteArray(), Charsets.UTF_8); line.reset()
        if (l.startsWith(":")) return
        val i = l.indexOf(':')
        val f = if (i < 0) l else l.substring(0, i)
        var v = if (i < 0) "" else l.substring(i + 1)
        if (v.startsWith(" ")) v = v.substring(1)
        if (f == "data") { if (data.isNotEmpty()) data.append('\n'); data.append(v) }
    }

    private fun dispatch() { if (data.isNotEmpty()) { onData(data.toString()); data.setLength(0) } }

    fun finish() { if (line.size() > 0) endLine(); dispatch() }
}

/** OpenAI 호환(델타) 스트림 프레임 해석 */
object DeltaExtractor {
    private val ANSWER = listOf("choices[0].delta.content", "choices[0].message.content", "choices[0].text",
        "delta.content", "delta.text", "content", "text", "data.content", "result.content", "message.content",
        "candidates[0].content.parts[0].text")
    private val THINK = listOf("choices[0].delta.reasoning_content", "delta.reasoning_content", "thinking_content", "reasoning_content")
    private val FINISH = listOf("choices[0].finish_reason", "finish_reason", "candidates[0].finishReason")

    data class Frame(val answer: String?, val think: String?, val done: Boolean)

    fun parse(raw: String): Frame {
        val t = raw.trim()
        if (t == "[DONE]") return Frame(null, null, true)
        val o = runCatching { JSONObject(t) }.getOrNull() ?: return Frame(t, null, false)
        val done = FINISH.any { p ->
            val v = path(o, p)
            v != null && v != JSONObject.NULL && v.toString().isNotEmpty() && v.toString() != "null"
        }
        return Frame(pick(o, ANSWER), pick(o, THINK), done)
    }

    private fun pick(o: JSONObject, paths: List<String>): String? {
        for (p in paths) { val v = path(o, p); if (v is String && v.isNotEmpty()) return v }
        return null
    }

    fun path(o: Any?, p: String): Any? {
        var cur: Any? = o
        for (seg in p.split('.')) {
            val name = seg.substringBefore('[')
            val idx = Regex("\\[(\\d+)]").find(seg)?.groupValues?.get(1)?.toIntOrNull()
            cur = (cur as? JSONObject)?.opt(name) ?: return null
            if (idx != null) cur = (cur as? JSONArray)?.opt(idx) ?: return null
        }
        return cur
    }
}

/**
 * chat.deepseek.com 웹 스트림은 JSON-패치 형태다.
 *   {"v":{"response":{"fragments":[{"type":"RESPONSE","content":"안"}]}}}
 *   {"p":"response/fragments/-1/content","o":"APPEND","v":"녕"}
 *   {"v":"하세요"}                      ← p가 없으면 직전 경로에 이어붙인다
 *   {"p":"response/fragments","o":"APPEND","v":[{"type":"THINK","content":"..."}]}
 *   {"p":"response","o":"BATCH","v":[{"p":"quasi_status","v":"FINISHED"}]}
 * 구형 포맷 {"p":"response/content","v":"..."} / thinking_content 도 함께 처리한다.
 */
class DeepSeekPatchAssembler {
    private data class Frag(val type: String, val sb: StringBuilder)
    private val frags = ArrayList<Frag>()
    private var path: String = ""
    private val legacyAnswer = StringBuilder()
    private val legacyThink = StringBuilder()
    var finished = false; private set
    var recognized = false; private set

    fun accept(data: String): Boolean {
        val t = data.trim()
        if (t == "[DONE]") { finished = true; return true }
        val o = runCatching { JSONObject(t) }.getOrNull() ?: return false
        return applyPatch(o)
    }

    private fun applyPatch(o: JSONObject): Boolean {
        val hasP = o.has("p"); val hasV = o.has("v")
        if (!hasP && !hasV) return false
        if (hasP) path = o.optString("p")
        val op = o.optString("o", "")
        val v = o.opt("v")
        recognized = true
        when {
            op.equals("BATCH", true) && v is JSONArray -> {
                for (i in 0 until v.length()) (v.opt(i) as? JSONObject)?.let { sub ->
                    val sp = sub.optString("p")
                    val full = if (path.isNotEmpty() && sp.isNotEmpty()) "$path/$sp" else sp
                    val saved = path
                    applyPatch(JSONObject(sub.toString()).put("p", full))
                    path = saved
                }
            }
            v is JSONObject -> {
                val resp = v.optJSONObject("response") ?: if (path.endsWith("response")) v else null
                resp?.let { r ->
                    r.optJSONArray("fragments")?.let { arr -> frags.clear(); addFrags(arr) }
                    r.optString("content", "").takeIf { it.isNotEmpty() }?.let { legacyAnswer.setLength(0); legacyAnswer.append(it) }
                    statusCheck(r.optString("status", ""))
                }
            }
            v is JSONArray -> if (path.endsWith("fragments")) addFrags(v)
            v is String -> {
                val p = path
                when {
                    p.endsWith("status") || p.endsWith("quasi_status") -> statusCheck(v)
                    p.contains("fragments") && p.endsWith("content") -> {
                        val idx = Regex("fragments/(-?\\d+)/").find(p)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                        val f = if (idx < 0) frags.lastOrNull() else frags.getOrNull(idx)
                        if (f == null) { frags += Frag("RESPONSE", StringBuilder(v)) }
                        else if (op.equals("SET", true)) { f.sb.setLength(0); f.sb.append(v) } else f.sb.append(v)
                    }
                    p.endsWith("thinking_content") -> legacyThink.append(v)
                    p.endsWith("content") || p.isEmpty() -> {
                        if (frags.isNotEmpty() && p.isEmpty()) frags.last().sb.append(v)
                        else if (op.equals("SET", true)) { legacyAnswer.setLength(0); legacyAnswer.append(v) }
                        else legacyAnswer.append(v)
                    }
                    else -> {}
                }
            }
        }
        return true
    }

    private fun addFrags(arr: JSONArray) {
        for (i in 0 until arr.length()) (arr.opt(i) as? JSONObject)?.let {
            frags += Frag(it.optString("type", "RESPONSE").uppercase(), StringBuilder(it.optString("content", "")))
        }
    }

    private fun statusCheck(s: String) { if (s.equals("FINISHED", true) || s.equals("DONE", true)) finished = true }

    fun answer(): String {
        val fromFrags = frags.filter { it.type != "THINK" && it.type != "THINKING" && it.type != "SEARCH" }
            .joinToString("") { it.sb.toString() }
        return if (fromFrags.isNotEmpty()) fromFrags else legacyAnswer.toString()
    }

    fun thinking(): String {
        val f = frags.filter { it.type == "THINK" || it.type == "THINKING" }.joinToString("") { it.sb.toString() }
        return if (f.isNotEmpty()) f else legacyThink.toString()
    }
}

/** 델타 vs 누적 스냅샷 판정. 오판하면 "답이 3번 반복됨" 버그가 난다. */
class SnapshotReconciler {
    enum class Mode { UNKNOWN, DELTA, CUMULATIVE }
    private val sb = StringBuilder()
    var mode = Mode.UNKNOWN; private set

    fun accept(next: String): String {
        val cur = sb.toString()
        when (mode) {
            Mode.DELTA -> sb.append(next)
            Mode.CUMULATIVE -> when {
                next.startsWith(cur) -> sb.append(next.substring(cur.length))
                cur.startsWith(next) -> Unit
                else -> { val k = common(cur, next); sb.setLength(k); sb.append(next.substring(k)) }
            }
            Mode.UNKNOWN -> when {
                next.isEmpty() -> Unit
                cur.isEmpty() -> sb.append(next)
                next.startsWith(cur) -> { sb.append(next.substring(cur.length)); mode = Mode.CUMULATIVE }
                else -> { sb.append(next); mode = Mode.DELTA }
            }
        }
        return sb.toString()
    }

    fun text() = sb.toString()
    private fun common(a: String, b: String): Int { var i = 0; while (i < a.length && i < b.length && a[i] == b[i]) i++; return i }
}

/** 4중 종료 판정: 명시 종료 / EOF / 침묵 / 상한 */
class TerminationGate(
    private val silenceMs: Long = 3_500,
    private val emptyMs: Long = 25_000,
    private val hardMs: Long = 360_000,
    private val maxChars: Int = 200_000
) {
    enum class Verdict { RUNNING, DONE, SILENCE_DONE, EMPTY, TIMEOUT, TRUNCATED }
    private var started = 0L
    private var lastChunk = 0L
    fun start(now: Long) { started = now; lastChunk = now }
    fun onChunk(now: Long) { lastChunk = now }

    /**
     * chars는 '답' 길이다. 생각(THINK) 단계에서는 답이 0자여도 조각은 계속 온다.
     * 그래서 EMPTY는 '시작 후 경과'가 아니라 '마지막 조각 이후 침묵'으로 판단한다.
     */
    fun poll(now: Long, chars: Int, explicitDone: Boolean, strongMarker: Boolean): Verdict = when {
        explicitDone || strongMarker -> Verdict.DONE
        chars == 0 && now - lastChunk > emptyMs -> Verdict.EMPTY
        now - started > hardMs -> Verdict.TIMEOUT
        chars > maxChars -> Verdict.TRUNCATED
        chars > 0 && now - lastChunk > silenceMs -> Verdict.SILENCE_DONE
        else -> Verdict.RUNNING
    }
}
