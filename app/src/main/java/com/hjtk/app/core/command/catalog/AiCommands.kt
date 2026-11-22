package com.hjtk.app.core.command.catalog

import com.hjtk.app.core.command.CommandRegistry
import com.hjtk.app.core.model.CommandCategory
import com.hjtk.app.core.model.CommandResult
import com.hjtk.app.core.model.CommandSpec
import com.hjtk.app.core.model.EffectClass
import com.hjtk.app.core.model.ResultAction

/**
 * AI commands never contain model logic. They build a prompt (with the user's
 * own memory as context) and hand it to whichever carrier can answer, which
 * keeps the expensive, fragile part behind one interface.
 */
object AiCommands {

    fun register(reg: CommandRegistry) {

        reg.register(
            CommandSpec(
                id = "ai.ask",
                labelKo = "AI에게 묻기",
                category = CommandCategory.AI,
                effect = EffectClass.READ,
                description = "선택된 캐리어로 질문을 보내고 답을 받아옵니다.",
                params = listOf(CommandRegistry.param("prompt", "질문", true, "이 문장 다듬어줘: ...")),
                keywords = listOf("물어봐", "물어", "ai", "알려줘", "설명해", "ask"),
                patterns = listOf("^(?:ai|에이아이)\\s*[:：]?\\s*(?<prompt>.+)$")
            )
        ) { req, ports ->
            missing(reg, req, "ai.ask")?.let { return@register it }
            ask(ports, req.arg("prompt")!!, null)
        }

        reg.register(
            CommandSpec(
                id = "ai.deepseek",
                labelKo = "DeepSeek에게",
                category = CommandCategory.AI,
                effect = EffectClass.READ,
                description = "DeepSeek 캐리어를 우선 사용해 질문합니다.",
                params = listOf(CommandRegistry.param("prompt", "질문", true, "파이썬 리스트 컴프리헨션 예시")),
                keywords = listOf("딥시크", "deepseek"),
                patterns = listOf("^(?:딥시크|deepseek)\\s*[:：]?\\s*(?<prompt>.+)$")
            )
        ) { req, ports ->
            missing(reg, req, "ai.deepseek")?.let { return@register it }
            ask(ports, req.arg("prompt")!!, "deepseek")
        }

        reg.register(
            CommandSpec(
                id = "ai.gemini",
                labelKo = "Gemini에게",
                category = CommandCategory.AI,
                effect = EffectClass.READ,
                description = "Gemini 캐리어를 우선 사용해 질문합니다.",
                params = listOf(CommandRegistry.param("prompt", "질문", true, "이 코드의 버그 찾아줘")),
                keywords = listOf("제미나이", "제미니", "gemini"),
                patterns = listOf("^(?:제미나이|제미니|gemini)\\s*[:：]?\\s*(?<prompt>.+)$")
            )
        ) { req, ports ->
            missing(reg, req, "ai.gemini")?.let { return@register it }
            ask(ports, req.arg("prompt")!!, "gemini")
        }

        reg.register(
            CommandSpec(
                id = "ai.summarize",
                labelKo = "요약",
                category = CommandCategory.AI,
                effect = EffectClass.READ,
                description = "긴 글을 핵심 위주로 요약합니다.",
                params = listOf(
                    CommandRegistry.param("text", "내용", true, "요약할 글"),
                    CommandRegistry.param("style", "스타일", false, "3줄")
                ),
                keywords = listOf("요약", "정리해", "summarize", "summarise"),
                patterns = listOf("^(?<text>.+?)\\s*(?:를|을)?\\s*(?:요약해|정리해)")
            )
        ) { req, ports ->
            missing(reg, req, "ai.summarize")?.let { return@register it }
            val style = req.arg("style") ?: "3줄"
            ask(ports, "다음 내용을 ${style}로 요약해 주세요. 사실을 추가하지 말고 원문에 있는 것만 쓰세요.\n\n" + req.arg("text")!!, null)
        }

        reg.register(
            CommandSpec(
                id = "ai.translate",
                labelKo = "번역",
                category = CommandCategory.AI,
                effect = EffectClass.READ,
                description = "문장을 다른 언어로 번역합니다.",
                params = listOf(
                    CommandRegistry.param("text", "문장", true, "오늘 회의는 3시입니다"),
                    CommandRegistry.param("to", "대상 언어", false, "영어")
                ),
                keywords = listOf("번역", "translate", "영어로", "한국어로"),
                patterns = listOf("^(?<text>.+?)\\s*(?:를|을)?\\s*(?<to>영어|일본어|중국어|한국어|스페인어)\\s*(?:로|으로)\\s*번역")
            )
        ) { req, ports ->
            missing(reg, req, "ai.translate")?.let { return@register it }
            val to = req.arg("to") ?: "영어"
            ask(ports, "다음을 $to(으)로 자연스럽게 번역하고, 번역문만 출력하세요.\n\n" + req.arg("text")!!, null)
        }

        reg.register(
            CommandSpec(
                id = "ai.rewrite",
                labelKo = "문장 다듬기",
                category = CommandCategory.AI,
                effect = EffectClass.READ,
                description = "문장을 더 명확하고 자연스럽게 고칩니다.",
                params = listOf(
                    CommandRegistry.param("text", "문장", true, "이 문장 다듬어줘"),
                    CommandRegistry.param("tone", "톤", false, "정중하게")
                ),
                keywords = listOf("다듬어", "고쳐", "rewrite", "proofread"),
                patterns = listOf("^(?<text>.+?)\\s*(?:를|을)?\\s*(?:다듬어|고쳐)")
            )
        ) { req, ports ->
            missing(reg, req, "ai.rewrite")?.let { return@register it }
            val tone = req.arg("tone") ?: "정중하게"
            ask(ports, "다음 문장을 ${tone} 다듬어 주세요. 뜻은 바꾸지 말고 결과 문장만 출력하세요.\n\n" + req.arg("text")!!, null)
        }

        reg.register(
            CommandSpec(
                id = "ai.compare",
                labelKo = "두 AI 교차 확인",
                category = CommandCategory.AI,
                effect = EffectClass.READ,
                description = "서로 다른 캐리어 두 곳에 같은 질문을 보내 답을 비교합니다.",
                params = listOf(CommandRegistry.param("prompt", "질문", true, "코루틴과 스레드의 차이")),
                keywords = listOf("교차", "비교해", "두 ai", "cross check")
            )
        ) { req, ports ->
            missing(reg, req, "ai.compare")?.let { return@register it }
            val prompt = req.arg("prompt")!!
            val first = runCatching { ports.ai.ask(prompt, "deepseek") }.getOrNull()
            val second = runCatching { ports.ai.ask(prompt, "gemini") }.getOrNull()
            if (first == null && second == null) {
                CommandResult.Failed("두 캐리어 모두 응답하지 않았습니다. 설정에서 사용할 캐리어를 확인해 주세요.")
            } else {
                CommandResult.Ok(
                    output = buildString {
                        appendLine("**교차 확인**")
                        appendLine()
                        appendLine("· ${first?.carrierLabel ?: "캐리어 1"}: " + (first?.text ?: "응답 없음"))
                        appendLine()
                        appendLine("· ${second?.carrierLabel ?: "캐리어 2"}: " + (second?.text ?: "응답 없음"))
                        if (first != null && second != null) {
                            appendLine()
                            appendLine("두 답의 표현이 다르면 사실 관계를 직접 확인하세요.")
                        }
                    }.trim()
                )
            }
        }

        reg.register(
            CommandSpec(
                id = "ai.status",
                labelKo = "AI 캐리어 상태",
                category = CommandCategory.AI,
                effect = EffectClass.READ,
                description = "사용 가능한 AI 경로와 준비 상태를 보여줍니다.",
                keywords = listOf("캐리어", "ai 상태", "연결 상태", "carrier")
            )
        ) { _, ports ->
            val list = ports.ai.carriers()
            CommandResult.Ok(
                output = buildString {
                    appendLine("**AI 캐리어**")
                    list.forEach {
                        val mark = if (it.available) "사용 가능" else "사용 불가"
                        appendLine("· ${it.label} — $mark" + if (it.requiresKey) " (키 필요)" else "")
                        if (it.note.isNotBlank()) appendLine("  ${it.note}")
                    }
                }.trim(),
                actions = listOf(ResultAction("AI 설정 열기", "system.settings", mapOf("section" to "ai")))
            )
        }
    }

    private suspend fun ask(
        ports: com.hjtk.app.core.HostPorts,
        prompt: String,
        preferred: String?
    ): CommandResult {
        val context = ports.memory.contextFor(prompt)
        val fullPrompt = if (context.isBlank()) prompt else "사용자가 저장한 기억(참고용):\n$context\n\n요청: $prompt"
        return runCatching { ports.ai.ask(fullPrompt, preferred) }
            .map { answer ->
                if (answer.text.isBlank()) {
                    CommandResult.Failed("${answer.carrierLabel}에서 빈 응답을 받았습니다.")
                } else {
                    CommandResult.Ok(
                        output = answer.text.trim(),
                        data = mapOf("carrier" to answer.carrierId),
                        actions = listOf(
                            ResultAction("복사", "clip.copy", mapOf("text" to answer.text.trim())),
                            ResultAction("파일로 저장", "file.export_text", mapOf("content" to answer.text.trim(), "name" to "ai-answer.txt"))
                        )
                    )
                }
            }
            .getOrElse { failure(it, "설정에서 AI 캐리어를 확인해 주세요.") }
    }
}
