package com.ht.ensemble.command

/**
 * 명령(도구) 타입. 엔진은 AI가 고른 명령을 이 모양으로 받고, 효과(Effect)에 따라 승인을 받은 뒤 실행한다.
 * 명령 목록(사전)은 SDK 밖 — 앱마다 자기 도구를 ToolPort로 넣는다.
 */
enum class Effect(val level: Int, val label: String, val needsApproval: Boolean) {
    READ(0, "읽기", false),
    LOCAL(1, "기기 안 변경", false),
    EXTERNAL(2, "밖으로 보냄", true),
    MONEY(3, "금전", true),
    DESTRUCT(4, "삭제·되돌릴 수 없음", true);
    companion object { fun of(s: String?) = values().firstOrNull { it.name.equals(s, true) || "E${it.level}" == s?.uppercase() } }
}

enum class Category(val label: String, val code: String) {
    AI("AI 협업", "AI"), WEB("웹·검색", "WEB"), DATA("데이터", "DATA"), MAIL("메일", "MAIL"),
    CAL("일정", "CAL"), COMM("연락", "COMM"), DEVICE("기기", "DEV"), FILE("파일", "FILE"),
    TEXT("텍스트", "TEXT"), CALC("계산", "CALC"), MEMORY("기억", "MEM"), TASK("작업", "TASK"),
    HUB("허브", "HUB"), FLOW("파이프라인·스킬", "FLOW"), UI("화면·사용자", "UI"), LIFE("생활·특화", "LIFE")
}

data class Param(val name: String, val desc: String, val required: Boolean)

data class CommandSpec(
    val id: String,
    val title: String,
    val category: Category,
    val effect: Effect,
    val params: List<Param>,
    val desc: String,
    val aliases: List<String> = emptyList()
) {
    /** 프롬프트용 한 줄. 토큰을 아끼기 위해 짧게. */
    fun line(): String {
        val p = params.joinToString(",") { (if (it.required) "" else "?") + it.name }
        val e = if (effect.level >= 2) " [E${effect.level}:승인]" else ""
        return "$id($p) $desc$e"
    }
}

data class CommandCall(val callId: String, val cmd: String, val args: Map<String, String>) {
    fun arg(k: String, def: String = ""): String = args[k]?.takeIf { it.isNotBlank() } ?: def
    fun int(k: String, def: Int): Int = args[k]?.trim()?.toDoubleOrNull()?.toInt() ?: def
}

data class CommandResult(
    val ok: Boolean,
    val text: String,
    val artifact: Artifact? = null,
    val userVisible: Boolean = false
) {
    companion object {
        fun ok(t: String, artifact: Artifact? = null) = CommandResult(true, t, artifact)
        fun fail(t: String) = CommandResult(false, t)
    }
}

data class Artifact(val name: String, val mime: String, val content: String)

fun interface CommandHandler { suspend fun run(call: CommandCall): CommandResult }
