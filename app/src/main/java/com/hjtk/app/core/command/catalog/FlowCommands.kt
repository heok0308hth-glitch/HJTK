package com.hjtk.app.core.command.catalog

import com.hjtk.app.core.command.CommandRegistry
import com.hjtk.app.core.model.CommandCategory
import com.hjtk.app.core.model.CommandResult
import com.hjtk.app.core.model.CommandSpec
import com.hjtk.app.core.model.EffectClass
import com.hjtk.app.core.model.ResultAction
import com.hjtk.app.core.model.SkillStep

/**
 * Pipelines and skills. Both are *declarations*: a list of command steps.
 * Running one hands the steps to the orchestrator, which applies the same
 * approval gate and journal as a typed request.
 */
object FlowCommands {

    fun register(reg: CommandRegistry) {

        reg.register(
            CommandSpec(
                id = "pipeline.list",
                labelKo = "파이프라인 목록",
                category = CommandCategory.PIPELINE,
                effect = EffectClass.READ,
                description = "저장된 파이프라인과 마지막 실행 결과를 보여줍니다.",
                keywords = listOf("파이프라인", "플로우", "워크플로", "pipeline")
            )
        ) { _, ports ->
            val items = ports.pipelines.all()
            if (items.isEmpty()) {
                CommandResult.Ok("저장된 파이프라인이 없습니다. 작업 탭에서 명령을 조합해 만들 수 있습니다.")
            } else {
                CommandResult.Ok(
                    output = buildString {
                        appendLine("**파이프라인 ${items.size}개**")
                        items.forEach { p ->
                            appendLine()
                            appendLine("· ${p.name}  (${p.steps.size}단계)")
                            if (p.description.isNotBlank()) appendLine("  ${p.description}")
                            val last = if (p.lastRunAt <= 0) "실행 기록 없음" else (if (p.lastRunOk) "마지막 실행 성공" else "마지막 실행 실패")
                            appendLine("  $last")
                        }
                    }.trim(),
                    actions = items.take(3).map { ResultAction("${it.name} 실행", "pipeline.run", mapOf("name" to it.name)) }
                )
            }
        }

        reg.register(
            CommandSpec(
                id = "pipeline.run",
                labelKo = "파이프라인 실행",
                category = CommandCategory.PIPELINE,
                effect = EffectClass.EXTERNAL,
                description = "저장된 파이프라인을 실행합니다(단계별 승인 포함).",
                params = listOf(CommandRegistry.param("name", "파이프라인 이름", true, "아침 루틴")),
                keywords = listOf("파이프라인 실행", "플로우 실행", "run pipeline"),
                patterns = listOf("^(?<name>.+?)\\s*(?:파이프라인|플로우)\\s*(?:실행|돌려)")
            )
        ) { req, ports ->
            missing(reg, req, "pipeline.run")?.let { return@register it }
            val name = req.arg("name")!!
            val pipeline = ports.pipelines.byName(name)
                ?: return@register CommandResult.Failed("‘$name’ 파이프라인을 찾지 못했습니다." + if (ports.pipelines.all().isEmpty()) "" else " 저장된 목록: " + ports.pipelines.all().joinToString(", ") { it.name })
            if (pipeline.steps.isEmpty()) {
                return@register CommandResult.Failed("‘${pipeline.name}’에 단계가 없습니다. 작업 탭에서 단계를 추가해 주세요.")
            }
            runCatching { ports.plans.submit(pipeline.steps, pipeline.name) }
                .map { CommandResult.Ok("파이프라인 ‘${pipeline.name}’을(를) 대기열에 넣었습니다. $it") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "pipeline.status",
                labelKo = "실행 상태",
                category = CommandCategory.PIPELINE,
                effect = EffectClass.READ,
                description = "지금 실행 중인 작업의 상태를 확인합니다.",
                keywords = listOf("상태", "진행", "얼마나", "status", "progress")
            )
        ) { _, ports ->
            CommandResult.Ok(ports.plans.status())
        }

        reg.register(
            CommandSpec(
                id = "pipeline.stop",
                labelKo = "실행 중단",
                category = CommandCategory.PIPELINE,
                effect = EffectClass.DESTRUCTIVE,
                description = "진행 중인 계획을 중단합니다.",
                keywords = listOf("중단", "멈춰", "취소", "stop")
            )
        ) { _, ports ->
            if (ports.plans.stop()) CommandResult.Ok("진행 중인 작업을 중단했습니다.")
            else CommandResult.Ok("지금 실행 중인 작업이 없습니다.")
        }

        reg.register(
            CommandSpec(
                id = "skill.list",
                labelKo = "스킬 목록",
                category = CommandCategory.SKILL,
                effect = EffectClass.READ,
                description = "사용 가능한 스킬(내장 + 사용자 정의)을 보여줍니다.",
                keywords = listOf("스킬", "skill", "매크로")
            )
        ) { _, ports ->
            val skills = ports.skills.all()
            CommandResult.Ok(
                output = buildString {
                    appendLine("**스킬 ${skills.size}개**")
                    skills.forEach { s ->
                        appendLine()
                        appendLine("· ${s.name}  (${s.steps.size}단계${if (s.builtIn) ", 기본" else ""})")
                        if (s.description.isNotBlank()) appendLine("  ${s.description}")
                    }
                }.trim(),
                actions = skills.take(3).map { ResultAction("${it.name} 실행", "skill.run", mapOf("name" to it.name)) }
            )
        }

        reg.register(
            CommandSpec(
                id = "skill.run",
                labelKo = "스킬 실행",
                category = CommandCategory.SKILL,
                effect = EffectClass.EXTERNAL,
                description = "이름으로 스킬을 찾아 단계를 실행합니다.",
                params = listOf(CommandRegistry.param("name", "스킬 이름", true, "아침 브리핑")),
                keywords = listOf("스킬 실행", "run skill"),
                patterns = listOf("^(?<name>.+?)\\s*(?:스킬)\\s*(?:실행|돌려)")
            )
        ) { req, ports ->
            missing(reg, req, "skill.run")?.let { return@register it }
            val name = req.arg("name")!!
            val skill = ports.skills.byId(name)
                ?: ports.skills.all().firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: return@register CommandResult.Failed("‘$name’ 스킬을 찾지 못했습니다.")
            if (skill.steps.isEmpty()) return@register CommandResult.Failed("‘${skill.name}’에 단계가 없습니다.")
            runCatching { ports.plans.submit(skill.steps, skill.name) }
                .map { CommandResult.Ok("스킬 ‘${skill.name}’을(를) 실행 대기열에 넣었습니다. $it") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "skill.create",
                labelKo = "스킬 만들기",
                category = CommandCategory.SKILL,
                effect = EffectClass.LOCAL_WRITE,
                description = "명령 단계를 묶어 새 스킬을 저장합니다(JSON 단계 목록).",
                params = listOf(
                    CommandRegistry.param("name", "스킬 이름", true, "아침 브리핑"),
                    CommandRegistry.param("keywords", "호출 키워드(쉼표)", false, "아침,브리핑"),
                    CommandRegistry.param("steps", "단계(JSON)", true, """[{"commandId":"calendar.today"},{"commandId":"mail.unread"}]""")
                ),
                keywords = listOf("스킬 만들어", "스킬 추가", "create skill")
            )
        ) { req, ports ->
            missing(reg, req, "skill.create")?.let { return@register it }
            val steps = parseSteps(req.arg("steps")!!)
                ?: return@register CommandResult.Failed("단계 형식을 읽지 못했습니다. 예: [{\"commandId\":\"calendar.today\"}]")
            val unknown = steps.map { it.commandId }.filterNot { reg.exists(it) }
            if (unknown.isNotEmpty()) return@register CommandResult.Failed("알 수 없는 명령: ${unknown.joinToString(", ")}")
            val keywords = req.arg("keywords")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val skill = ports.skills.create(req.arg("name")!!, "", keywords, steps)
            CommandResult.Ok("스킬 ‘${skill.name}’을(를) 저장했습니다(${steps.size}단계).")
        }
    }

    /** Lenient JSON-ish step parser: accepts the app's own export format. */
    internal fun parseSteps(raw: String): List<SkillStep>? {
        val text = raw.trim()
        if (!text.startsWith("[")) return null
        val objects = Regex("\\{[^}]*}").findAll(text).map { it.value }.toList()
        if (objects.isEmpty()) return null
        val steps = objects.mapNotNull { obj ->
            val cmd = Regex("\"commandId\"\\s*:\\s*\"([^\"]+)\"").find(obj)?.groupValues?.get(1) ?: return@mapNotNull null
            val args = Regex("\"args\"\\s*:\\s*\\{([^}]*)}").find(obj)?.groupValues?.get(1)
            val map = if (args.isNullOrBlank()) emptyMap() else {
                Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(args).associate { it.groupValues[1] to it.groupValues[2] }
            }
            SkillStep(commandId = cmd, args = map)
        }
        return steps.ifEmpty { null }
    }
}
