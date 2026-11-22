package com.hjtk.app.core.command.catalog

import com.hjtk.app.core.command.CommandRegistry
import com.hjtk.app.core.model.CommandCategory
import com.hjtk.app.core.model.CommandResult
import com.hjtk.app.core.model.CommandSpec
import com.hjtk.app.core.model.EffectClass
import com.hjtk.app.core.model.ResultAction

/** App-level settings, diagnostics and help. */
object SystemCommands {

    fun register(reg: CommandRegistry) {

        reg.register(
            CommandSpec(
                id = "system.settings",
                labelKo = "설정 열기",
                category = CommandCategory.SYSTEM,
                effect = EffectClass.EXTERNAL,
                description = "메일·알림·권한 등 관련 설정 화면을 엽니다.",
                params = listOf(CommandRegistry.param("section", "항목", false, "ai")),
                keywords = listOf("설정", "settings", "권한")
            )
        ) { req, ports ->
            val section = req.arg("section") ?: "app"
            runCatching { ports.device.openSettings(section) }
                .map { CommandResult.Ok("설정 화면을 열었습니다: $section") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "system.diagnostics",
                labelKo = "진단 정보",
                category = CommandCategory.SYSTEM,
                effect = EffectClass.READ,
                description = "엔진·캐리어·저장소 상태를 한 번에 확인합니다.",
                keywords = listOf("진단", "상태 점검", "diagnostics", "health")
            )
        ) { _, ports ->
            CommandResult.Ok(
                output = ports.diag.snapshot(),
                actions = listOf(ResultAction("진단 파일로 저장", "system.export_diagnostics"))
            )
        }

        reg.register(
            CommandSpec(
                id = "system.export_diagnostics",
                labelKo = "진단 내보내기",
                category = CommandCategory.SYSTEM,
                effect = EffectClass.LOCAL_WRITE,
                description = "진단 스냅샷을 텍스트 파일로 저장합니다(비밀값 제외).",
                keywords = listOf("진단 내보내기", "로그 저장", "export diagnostics")
            )
        ) { _, ports ->
            runCatching {
                val name = "diagnostics-" + com.hjtk.app.core.util.TimeFmt.iso(ports.now()).replace(':', '-') + ".txt"
                ports.files.saveExport(name, ports.diag.snapshot())
            }.map { saved -> CommandResult.Ok("진단 정보를 저장했습니다: $saved", data = mapOf("file" to saved)) }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "system.help",
                labelKo = "도움말",
                category = CommandCategory.SYSTEM,
                effect = EffectClass.READ,
                description = "할 수 있는 일과 예시 문장을 보여줍니다.",
                keywords = listOf("도움말", "뭐 할 수 있어", "사용법", "help")
            )
        ) { _, _ ->
            CommandResult.Ok(
                output = """
                    **HJTK가 하는 일**
                    말로 시키면 명령어로 바꿔 실행하고, 바깥으로 나가는 일은 실행 전에 확인을 받습니다.

                    **예시**
                    · "안드로이드 15 변경점 검색해줘"
                    · "내일 3시 치과 예약 일정 추가해줘"
                    · "최근 메일 5통 보여줘"
                    · "이 문장 다듬어줘: 회의가 늦어질 것 같아요"
                    · "토스 앱 열어줘"
                    · "5분 뒤 알려줘"
                    · "내 혈액형은 O형이라고 기억해"
                    · "이 대화 요약해서 파일로 저장해줘"

                    명령 팔레트에서 전체 명령 목록을 볼 수 있습니다.
                """.trimIndent()
            )
        }

        reg.register(
            CommandSpec(
                id = "system.about",
                labelKo = "앱 정보",
                category = CommandCategory.SYSTEM,
                effect = EffectClass.READ,
                description = "버전과 설계 원칙을 보여줍니다.",
                keywords = listOf("버전", "정보", "about", "version")
            )
        ) { _, _ ->
            CommandResult.Ok(
                """
                **HJTK** · 개인용 AI 오케스트레이션 툴킷
                기기 안에 모델을 두지 않고, 결정론적 명령 라우터와 계획 실행기로 동작합니다.
                바깥으로 나가는 모든 동작은 실행 전 승인 카드를 거치고, 단계마다 해시 체인 저널에 기록됩니다.
                """.trimIndent()
            )
        }

        reg.register(
            CommandSpec(
                id = "system.speak",
                labelKo = "읽어주기",
                category = CommandCategory.SYSTEM,
                effect = EffectClass.LOCAL_WRITE,
                description = "텍스트를 음성으로 읽습니다.",
                params = listOf(CommandRegistry.param("text", "내용", true, "읽을 문장")),
                keywords = listOf("읽어줘", "말해줘", "tts", "speak")
            )
        ) { req, ports ->
            missing(reg, req, "system.speak")?.let { return@register it }
            runCatching { ports.speak(req.arg("text")!!) }
                .map { CommandResult.Ok("읽기 시작했습니다.") }
                .getOrElse { CommandResult.Failed("음성 엔진을 사용할 수 없습니다. 기기 TTS 설정을 확인해 주세요.") }
        }
    }
}
