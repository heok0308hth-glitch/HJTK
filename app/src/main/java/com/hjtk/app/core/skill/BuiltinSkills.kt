package com.hjtk.app.core.skill

import com.hjtk.app.core.model.PipelineBlueprint
import com.hjtk.app.core.model.PipelineParam
import com.hjtk.app.core.model.Skill
import com.hjtk.app.core.model.SkillStep

/**
 * Skills and pipelines that ship with the app.
 *
 * They are declarations, not demos: every step points at a real command in the
 * catalog, and the ones with `{{var:…}}` slots ask for their inputs before they
 * run. A user can copy any of them in the Work tab and edit the copy.
 */
object BuiltinSkills {

    val all: List<Skill> = listOf(
        Skill(
            id = "k.morning",
            name = "아침 브리핑",
            description = "오늘 일정, 최근 메일, 안 읽은 수, 배터리를 한 번에 확인합니다.",
            keywords = listOf("아침", "브리핑", "모닝", "morning"),
            steps = listOf(
                SkillStep("calendar.today", titleKo = "오늘 일정"),
                SkillStep("mail.latest", mapOf("limit" to "3"), "최근 메일"),
                SkillStep("mail.unread", titleKo = "안 읽은 메일"),
                SkillStep("device.battery", titleKo = "배터리")
            ),
            builtIn = true
        ),
        Skill(
            id = "k.health",
            name = "상태 점검",
            description = "배터리·저장 공간·네트워크·엔진 진단을 순서대로 확인합니다.",
            keywords = listOf("점검", "상태", "헬스", "health"),
            steps = listOf(
                SkillStep("device.battery"),
                SkillStep("device.storage"),
                SkillStep("device.network"),
                SkillStep("system.diagnostics")
            ),
            builtIn = true
        ),
        Skill(
            id = "k.focus",
            name = "집중 모드",
            description = "볼륨을 낮추고 25분 타이머를 겁니다.",
            keywords = listOf("집중", "뽀모도로", "focus"),
            steps = listOf(
                SkillStep("device.volume", mapOf("percent" to "30")),
                SkillStep("notify.timer", mapOf("seconds" to "1500", "label" to "집중 시간"))
            ),
            builtIn = true
        ),
        Skill(
            id = "k.inbox",
            name = "받은편지 훑기",
            description = "최근 메일 5통과 안 읽은 수를 확인합니다.",
            keywords = listOf("메일 훑기", "받은편지", "inbox"),
            steps = listOf(
                SkillStep("mail.latest", mapOf("limit" to "5")),
                SkillStep("mail.unread")
            ),
            builtIn = true
        ),
        Skill(
            id = "k.cleanup",
            name = "마무리 정리",
            description = "현재 대화를 파일로 저장하고 저장된 기억을 확인합니다.",
            keywords = listOf("정리", "마무리", "기록"),
            steps = listOf(
                SkillStep("file.export_session"),
                SkillStep("memory.list")
            ),
            builtIn = true
        )
    )

    /** Pipelines with their declared inputs. Order matches the Work tab. */
    val pipelines: List<PipelineBlueprint> = listOf(
        PipelineBlueprint(
            name = "아침 루틴",
            description = "일정 → 메일 → 배터리 순으로 하루를 연다.",
            params = emptyList(),
            steps = listOf(
                SkillStep("calendar.today", titleKo = "오늘 일정"),
                SkillStep("mail.latest", mapOf("limit" to "3"), "최근 메일 3통"),
                SkillStep("device.battery", titleKo = "배터리")
            )
        ),
        PipelineBlueprint(
            name = "퇴근 정리",
            description = "대화를 저장하고 내일 첫 일정을 확인한다.",
            params = emptyList(),
            steps = listOf(
                SkillStep("file.export_session", titleKo = "대화 저장"),
                SkillStep("calendar.today", titleKo = "일정 확인")
            )
        ),
        PipelineBlueprint(
            name = "웹 리서치 노트",
            description = "검색 → 요약 → 파일 저장까지 한 번에. 주제만 입력하면 된다.",
            params = listOf(PipelineParam("query", "주제", "안드로이드 15 변경점")),
            steps = listOf(
                SkillStep("search.web", mapOf("query" to "{{var:query}}"), "웹 검색"),
                SkillStep("ai.summarize", mapOf("text" to "{{out:1}}", "style" to "5줄"), "요약"),
                SkillStep(
                    "file.export_text",
                    mapOf("content" to "{{out:2}}", "name" to "research-note.md"),
                    "노트 저장"
                )
            )
        ),
        PipelineBlueprint(
            name = "공모전 탐색",
            description = "키워드로 공모전을 훑고 뉴스로 마감을 확인해 지원 후보를 정리한다.",
            params = listOf(PipelineParam("keyword", "분야", "AI 앱 개발")),
            steps = listOf(
                SkillStep("search.web", mapOf("query" to "공모전 {{var:keyword}} 2026 모집"), "공모전 검색"),
                SkillStep("search.news", mapOf("query" to "공모전 {{var:keyword}}"), "관련 뉴스"),
                SkillStep(
                    "ai.summarize",
                    mapOf("text" to "검색 결과:\n{{out:1}}\n\n뉴스:\n{{out:2}}", "style" to "5줄 요약과 지원 후보 3개"),
                    "후보 정리"
                ),
                SkillStep(
                    "file.export_text",
                    mapOf("content" to "{{out:3}}", "name" to "contest-scan.md"),
                    "스캔 저장"
                )
            )
        ),
        PipelineBlueprint(
            name = "메일·일정 정리",
            description = "최근 메일을 읽고 오늘 처리할 일과 일정을 함께 보여준다.",
            params = emptyList(),
            steps = listOf(
                SkillStep("mail.latest", mapOf("limit" to "10"), "메일 10통"),
                SkillStep("ai.summarize", mapOf("text" to "{{out:1}}", "style" to "오늘 처리할 일 3개"), "할 일 정리"),
                SkillStep("calendar.today", titleKo = "오늘 일정")
            )
        ),
        PipelineBlueprint(
            name = "교차검증",
            description = "같은 질문을 DeepSeek과 Gemini에 보내 답을 나란히 저장한다(키 필요).",
            params = listOf(PipelineParam("question", "질문", "코루틴과 스레드의 차이")),
            steps = listOf(
                SkillStep("ai.deepseek", mapOf("prompt" to "{{var:question}}"), "DeepSeek"),
                SkillStep("ai.gemini", mapOf("prompt" to "{{var:question}}"), "Gemini"),
                SkillStep(
                    "file.export_text",
                    mapOf("content" to "## DeepSeek\n{{out:1}}\n\n## Gemini\n{{out:2}}", "name" to "cross-check.md"),
                    "비교 저장"
                )
            )
        ),
        PipelineBlueprint(
            name = "집중 모드",
            description = "볼륨 30%로 낮추고 25분 타이머를 건다.",
            params = emptyList(),
            steps = listOf(
                SkillStep("device.volume", mapOf("percent" to "30")),
                SkillStep("notify.timer", mapOf("seconds" to "1500", "label" to "집중 시간"))
            )
        ),
        PipelineBlueprint(
            name = "상태 점검",
            description = "배터리·저장 공간·네트워크·엔진 진단을 한 번에.",
            params = emptyList(),
            steps = listOf(
                SkillStep("device.battery"),
                SkillStep("device.storage"),
                SkillStep("device.network"),
                SkillStep("system.diagnostics")
            )
        )
    )
}
