package com.ht.ensemble.aegis

import com.ht.ensemble.command.CommandSpec
import com.ht.ensemble.command.Effect
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AEGIS — 중요한 일은 허락을 받는다.
 * 지문 인증 대신 "실행" 버튼 하나. 대신 승인은 '지금 보고 있는 그 행동'에만 묶인다:
 * 실행 직전에 행동을 다시 정규화해 다이제스트가 바이트 단위로 같아야 실행된다.
 */
data class NormalizedAction(val tool: String, val params: Map<String, String>, val account: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("tool", tool).put("params", JSONObject(params.toSortedMap() as Map<*, *>)).apply { account?.let { put("account", it) } }
}

object Canonical {
    /** RFC 8785(JCS)의 문자열·객체 부분집합: 키 UTF-16 정렬, 공백 없음, 최소 이스케이프 */
    fun of(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { k -> str(k) + ":" + of(v.opt(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { of(v.opt(it)) }
        is String -> str(v)
        is Boolean -> v.toString()
        is Number -> { val d = v.toDouble(); if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString() }
        else -> str(v.toString())
    }
    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n"); c == '\r' -> sb.append("\\r"); c == '\t' -> sb.append("\\t")
            c == '\b' -> sb.append("\\b"); c == '\u000C' -> sb.append("\\f")
            c.code < 0x20 -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
    fun digest(a: NormalizedAction): String = sha256(of(a.toJson()))
    fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** 승인 화면 문장은 모델이 쓰지 않는다. 같은 정규화 데이터에서 템플릿으로 만든다. */
object ApprovalSummaries {
    fun describe(spec: CommandSpec?, a: NormalizedAction): String {
        val p = a.params
        fun v(k: String) = p[k]?.let { if (it.length > 140) it.take(140) + "…" else it } ?: "-"
        return when (a.tool) {
            "mail.send" -> "${a.account ?: "기본 계정"}에서 ${v("to")}에게 메일을 보냅니다.\n제목: ${v("subject")}\n본문 ${p["body"]?.length ?: 0}자"
            "github.push" -> "GitHub 저장소 '${v("repo")}'에 ${if (p["path"].isNullOrBlank()) "이 작업의 결과물" else "'" + v("path") + "'"}을 올립니다(${if (p["private"] == "false") "공개" else "비공개"}).\n커밋: ${v("message")}\n키스토어·.env·키 파일은 자동으로 빠집니다."
            "mail.reply" -> "메일(${v("id")})에 답장을 보냅니다. 본문 ${p["body"]?.length ?: 0}자"
            "share.text" -> "다른 앱으로 내용을 공유합니다(${p["text"]?.length ?: 0}자)."
            "kakao.send" -> "카카오톡 공유 화면에 내용을 넣습니다(${p["text"]?.length ?: 0}자)."
            "file.delete" -> "작업공간 파일 '${v("name")}'을 삭제합니다. 되돌릴 수 없습니다."
            "file.export" -> "파일 '${v("name")}'을 기기 저장소로 내보냅니다."
            "file.share" -> "파일 '${v("name")}'을 다른 앱으로 공유합니다."
            "mem.forget" -> "기억 '${v("id")}'를 지웁니다."
            "task.approve" -> v("what")
            else -> (spec?.title ?: a.tool) + "\n" + p.entries.joinToString("\n") { "${it.key}: ${if (it.value.length > 80) it.value.take(80) + "…" else it.value}" }
        }
    }
}

data class PolicyInput(
    val effect: Effect,
    val recipientKnown: Boolean = true,
    val bodyHasLink: Boolean = false,
    val autoAllowE2: Boolean = false
)

sealed interface PolicyDecision {
    data class Auto(val reason: String) : PolicyDecision
    data class Ask(val reason: String, val warning: String? = null) : PolicyDecision
    data class Deny(val reason: String) : PolicyDecision
}

object Policy {
    /** 순수 함수. 더 구체적인 위험 규칙이 위에 있다. */
    fun decide(i: PolicyInput): PolicyDecision = when {
        i.effect == Effect.MONEY -> PolicyDecision.Deny("금전 명령은 이 버전에서 비활성")
        i.effect.level >= 2 && !i.recipientKnown && i.bodyHasLink -> PolicyDecision.Ask("처음 보내는 주소 + 링크", "처음 보내는 상대에게 링크가 포함된 내용을 보냅니다. 피싱·인젝션 여부를 확인하세요.")
        i.effect.level >= 2 && !i.recipientKnown -> PolicyDecision.Ask("처음 보내는 주소", "처음 보내는 상대입니다.")
        i.effect == Effect.DESTRUCT -> PolicyDecision.Ask("되돌릴 수 없음", "되돌릴 수 없는 작업입니다.")
        i.effect.level >= 2 && i.autoAllowE2 -> PolicyDecision.Auto("사용자가 자동 허용으로 설정")
        i.effect.level >= 2 -> PolicyDecision.Ask("외부로 나가는 작업")
        else -> PolicyDecision.Auto("읽기·기기 안 변경")
    }
}

/** 1회용 승인 토큰. HMAC으로 다이제스트·만료·논스를 묶는다. */
class ApprovalGate(private val key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }, private val ttlMs: Long = 10 * 60_000) {
    data class Token(val jti: String, val digest: String, val exp: Long, val sig: String)
    sealed interface Verdict { object Ok : Verdict; data class Bad(val code: String) : Verdict }

    private val used = HashSet<String>()

    fun issue(digest: String, now: Long): Token {
        val jti = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val exp = now + ttlMs
        return Token(jti, digest, exp, sign("$jti|$digest|$exp"))
    }

    @Synchronized
    fun verifyAndConsume(t: Token, action: NormalizedAction, now: Long): Verdict {
        if (sign("${t.jti}|${t.digest}|${t.exp}") != t.sig) return Verdict.Bad("E_SIGNATURE")
        if (now > t.exp) return Verdict.Bad("E_EXPIRED")
        if (t.jti in used) return Verdict.Bad("E_REPLAY")
        if (Canonical.digest(action) != t.digest) return Verdict.Bad("E_DIGEST")
        used += t.jti
        return Verdict.Ok
    }

    private fun sign(s: String): String {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
