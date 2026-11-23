package com.ht.hjtk.core.command

/** 루틴(예약 작업) 사전 항목 — 실행은 engine/Routines */
object RoutineCommands {
    private fun spec(id: String, title: String, params: String, desc: String, alias: String) = CommandSpec(id, title, Category.TASK, Effect.LOCAL,
        params.takeIf { it.isNotBlank() }?.split('|')?.map { p -> Param(p.substringBefore(':').trimEnd('*'), p.substringAfter(':', ""), p.substringBefore(':').endsWith("*")) } ?: emptyList(), desc, listOf(alias))
    val specs: List<CommandSpec> = listOf(
        spec("routine.add", "루틴 만들기", "goal*:정해진 시각에 할 일|time*:HH:mm|repeat:매일/평일/매주|day:요일(매주일 때)|title:이름|difficulty:QUICK/STANDARD/DEEP", "정해진 시각마다 작업을 자동으로 시작한다(예: 매일 08:00 브리핑)", "루틴"),
        spec("routine.list", "루틴 목록", "", "예약된 루틴들", "루틴목록"),
        spec("routine.remove", "루틴 지우기", "id*:루틴 ID 또는 이름", "루틴을 지운다", "루틴삭제")
    )
}

/** 계정 하나로(구글): 메일·드라이브·메모·캘린더를 로그인된 Gemini의 Google 앱 연결로 본다 — 키·앱 비밀번호 없이 */
object AccountCommands {
    val specs: List<CommandSpec> = listOf(
        CommandSpec("google.ask", "내 구글에 묻기", Category.WEB, Effect.READ,
            listOf(Param("app", "gmail/drive/keep/calendar/docs/youtube/maps", true), Param("q", "찾거나 정리할 것(예: 이번 주 받은 공모전 메일)", true)),
            "로그인한 구글 계정의 Gmail·드라이브·Keep·캘린더·문서를 읽고 정리한다(HJTK 안 Gemini 웹 경유)", listOf("구글")),
    )
}
