package com.ht.hjtk.core.aep

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer

/**
 * AEP-1 — 블랙박스 AI 앱을 RPC 엔드포인트처럼 쓰기 위한 텍스트 봉투.
 *
 * [[AEP1 j=<JOB11> s=<STEP>/<TOTAL> k=<KIND> v=1 t=<UNIXEXP>]]
 * 사람이 읽는 설명
 * <<<AEP1-JSON
 * { ... }
 * AEP1-JSON>>>
 *
 * 마커는 ASCII만 쓴다. 채팅 UI가 유니코드 괄호를 바꾸거나 백틱을 코드블록으로 재조판하기 때문이다.
 */
object JobId {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val rnd = SecureRandom()

    fun new(): String {
        val sb = StringBuilder()
        repeat(10) { sb.append(ALPHABET[rnd.nextInt(32)]) }
        return sb.toString() + checksum(sb)
    }

    fun checksum(data: CharSequence): Char =
        ALPHABET[data.sumOf { ALPHABET.indexOf(it).coerceAtLeast(0) } % 32]

    fun normalize(id: String): String =
        id.uppercase().replace('I', '1').replace('L', '1').replace('O', '0')

    fun isValid(id: String): Boolean {
        val n = normalize(id)
        if (n.length != 11 || n.any { it !in ALPHABET }) return false
        return checksum(n.substring(0, 10)) == n[10]
    }
}

enum class Kind(val wire: String) {
    KICKOFF("kickoff"), ACT("act"), OBS("obs"), ASK("ask"), RESULT("result"), REPAIR("repair"), CRITIQUE("critique");
    companion object { fun of(w: String?) = values().firstOrNull { it.wire == w?.lowercase() } }
}

data class AepHeader(
    val job: String,
    val step: Int,
    val total: Int,
    val kind: String,
    val version: Int = 1,
    val expiresAt: Long = 0
) {
    fun render(): String = "[[AEP1 j=$job s=$step/${if (total <= 0) "?" else total} k=$kind v=$version t=$expiresAt]]"
}

object Envelope {
    const val OPEN = "<<<AEP1-JSON"
    const val CLOSE = "AEP1-JSON>>>"

    fun wrap(header: AepHeader, prose: String, json: String? = null): String = buildString {
        append(header.render()).append('\n')
        append(prose.trim())
        if (json != null) { append("\n\n").append(OPEN).append('\n').append(json.trim()).append('\n').append(CLOSE) }
    }
}

enum class ParseLevel(val step: Int, val penalty: Double) {
    EXACT(1, 0.0), NORMALIZED(2, -0.02), BRACE_EXTRACT(3, -0.03), LENIENT_JSON(4, -0.06),
    KEY_REGEX(5, -0.15), PROSE_ONLY(6, -0.30), UNRECOVERABLE(7, -1.0)
}

enum class Correlation { MATCH, MISMATCH, ABSENT }

data class ParseResult(
    val level: ParseLevel,
    val header: AepHeader?,
    val payload: JSONObject?,
    val prose: String,
    val issues: List<String>,
    val correlation: Correlation,
    val fingerprint: String
) {
    /** 5단계 이하로 복구된 응답은 증거로만 쓴다. 자동 실행 금지. */
    val autoExecutable get() = level.step <= 4 && payload != null && correlation != Correlation.MISMATCH
    val needsRepair get() = level == ParseLevel.KEY_REGEX || level == ParseLevel.PROSE_ONLY
}

/** 7단계 복구 사다리. 계약: 절대 예외를 던지지 않는다. */
class AepParser(private val maxJsonChars: Int = 96_000) {

    private val headerRx = Regex(
        """\[\[\s*AEP1\s+j\s*=\s*["']?([0-9A-Za-z]{6,16})["']?\s+s\s*=\s*(\d+)\s*/\s*(\d+|\?)\s+k\s*=\s*([a-zA-Z_]+)(?:\s+v\s*=\s*(\d+))?(?:\s+t\s*=\s*(\d+))?\s*]]""",
        RegexOption.IGNORE_CASE
    )

    fun parse(raw: String, expectedJob: String? = null): ParseResult = try {
        parseInternal(raw, expectedJob)
    } catch (t: Throwable) {
        ParseResult(ParseLevel.UNRECOVERABLE, null, null, raw.take(2000), listOf("parser:${t.javaClass.simpleName}"), Correlation.ABSENT, fingerprint(raw))
    }

    fun parseHeader(text: String): AepHeader? {
        val m = headerRx.find(text) ?: return null
        val g = m.groupValues
        return AepHeader(
            job = JobId.normalize(g[1]),
            step = g[2].toIntOrNull() ?: 0,
            total = g[3].toIntOrNull() ?: 0,
            kind = g[4].lowercase(),
            version = g[5].toIntOrNull() ?: 1,
            expiresAt = g[6].toLongOrNull() ?: 0
        )
    }

    private fun parseInternal(raw0: String, expectedJob: String?): ParseResult {
        val issues = ArrayList<String>()
        val raw = if (raw0.length > maxJsonChars * 2) { issues += "truncated"; raw0.take(maxJsonChars * 2) } else raw0
        val header = parseHeader(raw)
        val corr = when {
            header == null || expectedJob == null -> Correlation.ABSENT
            JobId.normalize(header.job) == JobId.normalize(expectedJob) -> Correlation.MATCH
            else -> Correlation.MISMATCH
        }
        val prose = proseOf(raw)
        val fp = fingerprint(normalize(raw))

        // 1 — exact block
        block(raw)?.let { b -> obj(b)?.let { return ParseResult(ParseLevel.EXACT, header, it, prose, issues, corr, fp) } }
        issues += "L1:no-exact-block"

        // 2 — normalized
        val n = normalize(raw)
        if (n != raw) block(n)?.let { b -> obj(b)?.let { return ParseResult(ParseLevel.NORMALIZED, header ?: parseHeader(n), it, prose, issues, corr, fp) } }
        issues += "L2:no-normalized"

        // 3 — fences stripped, balanced object
        val body = stripFences(n)
        val balanced = extractBalancedObject(body)
        if (balanced != null) {
            obj(balanced)?.let { return ParseResult(ParseLevel.BRACE_EXTRACT, header, it, prose, issues, corr, fp) }
            issues += "L3:balanced-invalid"
            // 4 — lenient repair
            obj(repairJson(balanced), strict = true)?.let { return ParseResult(ParseLevel.LENIENT_JSON, header, it, prose, issues, corr, fp) }
            issues += "L4:repair-failed"
        } else {
            // 스트리밍 중간(닫는 괄호 없음): 여는 괄호부터 끝까지 수선 시도
            val open = body.indexOf('{')
            if (open >= 0) {
                val tail = body.substring(open)
                obj(repairJson(closeDangling(tail)))?.let { return ParseResult(ParseLevel.LENIENT_JSON, header, it, prose, issues + "L4:closed-dangling", corr, fp) }
            }
            issues += "L3:no-object"
        }

        // 5 — key regex salvage
        extractByKeys(body)?.let { return ParseResult(ParseLevel.KEY_REGEX, header, it, prose, issues, corr, fp) }
        issues += "L5:no-keys"

        // 6 — prose only
        if (prose.isNotBlank()) return ParseResult(ParseLevel.PROSE_ONLY, header, null, prose, issues, corr, fp)
        return ParseResult(ParseLevel.UNRECOVERABLE, header, null, "", issues, corr, fp)
    }

    private fun block(text: String): String? {
        val o = text.indexOf(Envelope.OPEN)
        if (o < 0) return null
        val start = o + Envelope.OPEN.length
        val c = text.indexOf(Envelope.CLOSE, start)
        return if (c < 0) null else text.substring(start, c).trim()
    }

    /** 1~3단계는 엄격 JSON만 인정한다(org.json은 관대해서 따옴표 없는 키·스마트쿼트를 받아 키 이름을 오염시킨다). */
    private fun obj(s: String, strict: Boolean = true): JSONObject? {
        val t = s.trim()
        if (!t.startsWith("{") || t.length > maxJsonChars) return null
        if (strict && !StrictJson.valid(t)) return null
        return try { JSONObject(t) } catch (_: Exception) { null }
    }

    fun proseOf(raw: String): String {
        var t = headerRx.replace(raw, "")
        val o = t.indexOf(Envelope.OPEN)
        if (o >= 0) {
            val c = t.indexOf(Envelope.CLOSE, o)
            t = if (c >= 0) t.removeRange(o, c + Envelope.CLOSE.length) else t.substring(0, o)
        }
        t = t.replace(Regex("```(?:json)?\\s*\\{[\\s\\S]*?}\\s*```"), "")
        return t.trim()
    }

    fun normalize(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        t = t.replace('“', '"').replace('”', '"').replace('„', '"')
            .replace('‘', '\'').replace('’', '\'')
            .replace("​", "").replace("‌", "").replace("‍", "").replace("﻿", "")
            .replace(' ', ' ')
        return t
    }

    fun stripFences(text: String): String {
        val m = Regex("```[a-zA-Z0-9_-]*\\s*\\n([\\s\\S]*?)```").find(text)
        return m?.groupValues?.get(1) ?: text.replace("```json", "").replace("```", "")
    }

    /** 문자열·이스케이프를 인식하는 중괄호 매칭 */
    fun extractBalancedObject(text: String): String? {
        var start = text.indexOf('{')
        while (start >= 0) {
            var depth = 0; var inStr = false; var esc = false; var q = '"'
            for (i in start until text.length) {
                val c = text[i]
                if (inStr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == q) inStr = false
                    continue
                }
                when (c) {
                    '"', '\'' -> { inStr = true; q = c }
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
                }
            }
            start = text.indexOf('{', start + 1)
            if (depth > 0) return null
        }
        return null
    }

    private fun closeDangling(t: String): String {
        var depthC = 0; var depthS = 0; var inStr = false; var esc = false
        for (c in t) {
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{' -> depthC++; '}' -> depthC--; '[' -> depthS++; ']' -> depthS-- }
        }
        val sb = StringBuilder(t.trimEnd().trimEnd(','))
        if (inStr) sb.append('"')
        repeat(depthS.coerceAtLeast(0)) { sb.append(']') }
        repeat(depthC.coerceAtLeast(0)) { sb.append('}') }
        return sb.toString()
    }

    /** 4단계 관대한 JSON 수선(순서 중요) */
    fun repairJson(input: String): String {
        // 1 주석 제거 + 2 문자열 내 원시 줄바꿈 이스케이프 + 3 단일 인용부호 → 이중
        val sb = StringBuilder()
        var i = 0; var inStr = false; var q = '"'; var esc = false
        while (i < input.length) {
            val c = input[i]
            if (inStr) {
                when {
                    esc -> { sb.append(c); esc = false }
                    c == '\\' -> { sb.append(c); esc = true }
                    c == q -> { sb.append('"'); inStr = false }
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> {}
                    c == '\t' -> sb.append("\\t")
                    c == '"' && q == '\'' -> sb.append("\\\"")
                    else -> sb.append(c)
                }
                i++; continue
            }
            if (c == '/' && i + 1 < input.length && input[i + 1] == '/') {
                while (i < input.length && input[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < input.length && input[i + 1] == '*') {
                val e = input.indexOf("*/", i + 2); i = if (e < 0) input.length else e + 2; continue
            }
            if (c == '"' || c == '\'') { inStr = true; q = c; sb.append('"'); i++; continue }
            sb.append(c); i++
        }
        var t = sb.toString()
        t = t.replace(Regex(",(\\s*[}\\]])"), "$1")
        t = t.replace(Regex("([{,]\\s*)([A-Za-z_][A-Za-z0-9_\\-]*)(\\s*:)"), "$1\"$2\"$3")
        t = t.replace(Regex("(?<=[:\\[,\\s])True(?=[,}\\]\\s])"), "true")
            .replace(Regex("(?<=[:\\[,\\s])False(?=[,}\\]\\s])"), "false")
            .replace(Regex("(?<=[:\\[,\\s])None(?=[,}\\]\\s])"), "null")
        return t
    }

    /** 5단계: 키 단위 정규식 추출(자동 실행 금지) */
    fun extractByKeys(text: String): JSONObject? {
        val keys = listOf("say", "status", "output", "next", "done", "progress", "ask")
        val o = JSONObject()
        for (k in keys) {
            val m = Regex("\"$k\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|true|false|-?[0-9.]+)").find(text) ?: continue
            val v = m.groupValues[1]
            when {
                v.startsWith("\"") -> o.put(k, m.groupValues[2].replace("\\n", "\n").replace("\\\"", "\""))
                v == "true" || v == "false" -> o.put(k, v == "true")
                else -> v.toDoubleOrNull()?.let { o.put(k, it) }
            }
        }
        return if (o.length() == 0) null else o
    }

    companion object {
        fun fingerprint(text: String): String {
            if (text.isEmpty()) return "0".repeat(32)
            val d = MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray())
            return d.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}

/** RFC 8259 엄격 문법 검사기(값 생성 없이 검사만). */
object StrictJson {
    fun valid(s: String): Boolean = try { val p = P(s); p.ws(); p.value(); p.ws(); p.i == s.length } catch (_: Exception) { false }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }
        fun value() {
            ws()
            if (i >= s.length) error("eof")
            when (s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> lit("true"); 'f' -> lit("false"); 'n' -> lit("null")
                else -> num()
            }
        }
        fun obj() {
            i++; ws(); if (s[i] == '}') { i++; return }
            while (true) { ws(); str(); ws(); if (s[i] != ':') error(":"); i++; value(); ws()
                when (s[i]) { ',' -> i++; '}' -> { i++; return }; else -> error(",") } }
        }
        fun arr() {
            i++; ws(); if (s[i] == ']') { i++; return }
            while (true) { value(); ws(); when (s[i]) { ',' -> i++; ']' -> { i++; return }; else -> error(",") } }
        }
        fun str() {
            if (s[i] != '"') error("\""); i++
            while (true) {
                val c = s[i]
                when {
                    c == '"' -> { i++; return }
                    c == '\\' -> { i++; val e = s[i]; if (e == 'u') { repeat(4) { i++; if (!s[i].isLetterOrDigit()) error("u") } } else if (e !in "\"\\/bfnrt") error("esc"); i++ }
                    c.code < 0x20 -> error("ctrl")
                    else -> i++
                }
            }
        }
        fun lit(w: String) { if (!s.startsWith(w, i)) error(w); i += w.length }
        fun num() {
            val st = i
            if (s[i] == '-') i++
            if (i >= s.length || !s[i].isDigit()) error("num")
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            if (st == i) error("num")
        }
    }
}
