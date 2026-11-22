package com.hjtk.app.core.command.catalog

import com.hjtk.app.core.command.CommandRegistry
import com.hjtk.app.core.model.CommandCategory
import com.hjtk.app.core.model.CommandResult
import com.hjtk.app.core.model.CommandSpec
import com.hjtk.app.core.model.EffectClass
import com.hjtk.app.core.model.HubKind
import com.hjtk.app.core.model.ResultAction
import com.hjtk.app.core.util.TimeFmt

/** Memory, clipboard, notifications, device controls and hub accessors. */
object UtilityCommands {

    fun register(reg: CommandRegistry) {

        // ---- memory -------------------------------------------------------
        reg.register(
            CommandSpec(
                id = "memory.remember",
                labelKo = "기억하기",
                category = CommandCategory.MEMORY,
                effect = EffectClass.LOCAL_WRITE,
                description = "오래 남길 사실을 저장합니다.",
                params = listOf(
                    CommandRegistry.param("key", "키", true, "선호 커피"),
                    CommandRegistry.param("value", "내용", true, "아이스 아메리카노"),
                    CommandRegistry.param("tags", "태그(쉼표)", false, "취향")
                ),
                keywords = listOf("기억해", "저장해둬", "외워", "remember"),
                patterns = listOf("^(?<key>[^:=]+?)\\s*(?:은|는|이|가)?\\s*(?<value>.+?)\\s*(?:이라고|라고)?\\s*기억해")
            )
        ) { req, ports ->
            missing(reg, req, "memory.remember")?.let { return@register it }
            val tags = req.arg("tags")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val entry = ports.memory.remember(req.arg("key")!!, req.arg("value")!!, tags)
            CommandResult.Ok("기억했습니다: ${entry.key} = ${entry.value}", actions = listOf(ResultAction("기억 목록", "memory.list")))
        }

        reg.register(
            CommandSpec(
                id = "memory.recall",
                labelKo = "기억 검색",
                category = CommandCategory.MEMORY,
                effect = EffectClass.READ,
                description = "저장한 기억에서 관련 내용을 찾습니다.",
                params = listOf(CommandRegistry.param("query", "검색어", false, "")),
                keywords = listOf("기억나", "기억해?", "내가 뭐라", "recall"),
                patterns = listOf("^(?<query>.+?)\\s*(?:기억나|기억해\\?|뭐였지)")
            )
        ) { req, ports ->
            val query = req.arg("query").orEmpty()
            val hits = ports.memory.recall(query, 6)
            if (hits.isEmpty()) {
                CommandResult.Ok(if (query.isBlank()) "저장된 기억이 없습니다." else "‘$query’와 관련된 기억을 찾지 못했습니다.")
            } else {
                CommandResult.Ok(
                    output = buildString {
                        appendLine("**관련 기억 ${hits.size}개**")
                        hits.forEach {
                            appendLine()
                            appendLine("· ${it.key}: ${it.value}")
                            if (it.tags.isNotEmpty()) appendLine("  ${it.tags.joinToString(", ")}")
                        }
                    }.trim(),
                    actions = listOf(ResultAction("전체 보기", "memory.list"))
                )
            }
        }

        reg.register(
            CommandSpec(
                id = "memory.list",
                labelKo = "기억 목록",
                category = CommandCategory.MEMORY,
                effect = EffectClass.READ,
                description = "저장한 모든 기억을 보여줍니다.",
                keywords = listOf("기억 목록", "메모리", "memory list")
            )
        ) { _, ports ->
            val all = ports.memory.all()
            if (all.isEmpty()) {
                CommandResult.Ok("저장된 기억이 없습니다. ‘내 혈액형은 O형이라고 기억해’처럼 말해 보세요.")
            } else {
                CommandResult.Ok("**기억 ${all.size}개**\n\n" + all.take(25).joinToString("\n") { "· ${it.key}: ${it.value}" })
            }
        }

        reg.register(
            CommandSpec(
                id = "memory.forget",
                labelKo = "기억 삭제",
                category = CommandCategory.MEMORY,
                effect = EffectClass.DESTRUCTIVE,
                description = "키가 일치하는 기억을 삭제합니다.",
                params = listOf(CommandRegistry.param("key", "키", true, "선호 커피")),
                keywords = listOf("기억 삭제", "잊어", "forget")
            )
        ) { req, ports ->
            missing(reg, req, "memory.forget")?.let { return@register it }
            val removed = ports.memory.forget(req.arg("key")!!)
            if (removed > 0) CommandResult.Ok("기억을 ${removed}개 삭제했습니다.")
            else CommandResult.Ok("일치하는 기억이 없습니다.")
        }

        // ---- clipboard ----------------------------------------------------
        reg.register(
            CommandSpec(
                id = "clip.copy",
                labelKo = "클립보드 복사",
                category = CommandCategory.CLIPBOARD,
                effect = EffectClass.LOCAL_WRITE,
                description = "텍스트를 클립보드에 복사합니다.",
                params = listOf(CommandRegistry.param("text", "내용", true, "복사할 내용")),
                keywords = listOf("복사", "클립보드", "copy"),
                patterns = listOf("^(?<text>.+?)\\s*(?:복사해|복사)$")
            )
        ) { req, ports ->
            missing(reg, req, "clip.copy")?.let { return@register it }
            runCatching { ports.clipboard.copy(req.arg("text")!!) }
                .map { CommandResult.Ok("클립보드에 복사했습니다.") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "clip.read",
                labelKo = "클립보드 읽기",
                category = CommandCategory.CLIPBOARD,
                effect = EffectClass.READ,
                description = "클립보드의 텍스트를 읽습니다.",
                keywords = listOf("클립보드 읽어", "붙여넣기", "paste")
            )
        ) { _, ports ->
            runCatching { ports.clipboard.read() }.map { text ->
                if (text.isBlank()) CommandResult.Ok("클립보드가 비어 있습니다.")
                else CommandResult.Ok(text.take(2000), actions = listOf(ResultAction("요약", "ai.summarize", mapOf("text" to text.take(4000)))))
            }.getOrElse { failure(it) }
        }

        // ---- notifications ------------------------------------------------
        reg.register(
            CommandSpec(
                id = "notify.show",
                labelKo = "알림 보내기",
                category = CommandCategory.NOTIFY,
                effect = EffectClass.LOCAL_WRITE,
                description = "지금 알림을 띄웁니다.",
                params = listOf(
                    CommandRegistry.param("title", "제목", true, "리마인더"),
                    CommandRegistry.param("body", "내용", false, "확인해 주세요")
                ),
                keywords = listOf("알림", "알려줘", "notify")
            )
        ) { req, ports ->
            missing(reg, req, "notify.show")?.let { return@register it }
            if (!ports.notify.canPost()) {
                return@register CommandResult.Failed("알림 권한이 없습니다. 설정에서 알림을 허용해 주세요.", retryable = false)
            }
            runCatching { ports.notify.show(req.arg("title")!!, req.arg("body").orEmpty()) }
                .map { CommandResult.Ok("알림을 보냈습니다.") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "notify.timer",
                labelKo = "타이머",
                category = CommandCategory.NOTIFY,
                effect = EffectClass.LOCAL_WRITE,
                description = "지정한 시간 뒤 알림을 띄웁니다.",
                params = listOf(
                    CommandRegistry.param("seconds", "초", true, "300"),
                    CommandRegistry.param("label", "이름", false, "타이머")
                ),
                keywords = listOf("타이머", "분 뒤", "초 뒤", "timer"),
                patterns = listOf("^(?<minutes>[0-9]{1,3})\\s*분\\s*(?:뒤에?)?\\s*(?:알려|타이머)")
            )
        ) { req, ports ->
            missing(reg, req, "notify.timer")?.let { return@register it }
            val seconds = req.arg("seconds")?.toIntOrNull()
                ?: (req.arg("minutes")?.toIntOrNull()?.times(60))
                ?: return@register CommandResult.Failed("시간을 이해하지 못했습니다. 예: 5분 뒤 알려줘")
            runCatching { ports.notify.scheduleTimer(req.arg("label") ?: "타이머", seconds.coerceIn(5, 86400)) }
                .map { CommandResult.Ok("${TimeFmt.duration(seconds)} 뒤에 알려드리겠습니다.") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "notify.alarm",
                labelKo = "알람 맞추기",
                category = CommandCategory.NOTIFY,
                effect = EffectClass.LOCAL_WRITE,
                description = "시계 앱의 알람 설정 화면을 엽니다.",
                params = listOf(
                    CommandRegistry.param("hour", "시", true, "7"),
                    CommandRegistry.param("minute", "분", false, "0"),
                    CommandRegistry.param("label", "이름", false, "기상")
                ),
                keywords = listOf("알람", "alarm", "깨워")
            )
        ) { req, ports ->
            missing(reg, req, "notify.alarm")?.let { return@register it }
            val hour = req.arg("hour")?.toIntOrNull() ?: return@register CommandResult.Failed("시(hour)를 숫자로 입력해 주세요.")
            val minute = req.arg("minute")?.toIntOrNull() ?: 0
            runCatching { ports.notify.setAlarm(req.arg("label") ?: "알람", hour.coerceIn(0, 23), minute.coerceIn(0, 59)) }
                .map { CommandResult.Ok("알람 화면을 열었습니다: ${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}") }
                .getOrElse { failure(it) }
        }

        // ---- device -------------------------------------------------------
        reg.register(
            CommandSpec(
                id = "device.battery",
                labelKo = "배터리 상태",
                category = CommandCategory.DEVICE,
                effect = EffectClass.READ,
                description = "배터리 잔량과 충전 상태를 확인합니다.",
                keywords = listOf("배터리", "충전", "battery")
            )
        ) { _, ports -> runCatching { CommandResult.Ok(ports.device.battery()) }.getOrElse { failure(it) } }

        reg.register(
            CommandSpec(
                id = "device.storage",
                labelKo = "저장 공간",
                category = CommandCategory.DEVICE,
                effect = EffectClass.READ,
                description = "내부 저장 공간 사용량을 확인합니다.",
                keywords = listOf("저장공간", "용량", "storage")
            )
        ) { _, ports -> runCatching { CommandResult.Ok(ports.device.storage()) }.getOrElse { failure(it) } }

        reg.register(
            CommandSpec(
                id = "device.network",
                labelKo = "네트워크 상태",
                category = CommandCategory.DEVICE,
                effect = EffectClass.READ,
                description = "연결 유형과 인터넷 도달 여부를 확인합니다.",
                keywords = listOf("네트워크", "와이파이", "인터넷", "network", "wifi")
            )
        ) { _, ports -> runCatching { CommandResult.Ok(ports.device.network()) }.getOrElse { failure(it) } }

        reg.register(
            CommandSpec(
                id = "device.volume",
                labelKo = "볼륨 조절",
                category = CommandCategory.DEVICE,
                effect = EffectClass.LOCAL_WRITE,
                description = "미디어 볼륨을 비율로 설정합니다.",
                params = listOf(CommandRegistry.param("percent", "비율", true, "40")),
                keywords = listOf("볼륨", "소리", "volume"),
                patterns = listOf("^(?:볼륨|소리)\\s*(?<percent>[0-9]{1,3})\\s*%?")
            )
        ) { req, ports ->
            missing(reg, req, "device.volume")?.let { return@register it }
            val percent = req.arg("percent")?.toIntOrNull()
                ?: return@register CommandResult.Failed("비율을 숫자로 입력해 주세요(0~100).")
            runCatching { CommandResult.Ok(ports.device.setVolume(percent)) }.getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "device.brightness",
                labelKo = "밝기 조절",
                category = CommandCategory.DEVICE,
                effect = EffectClass.LOCAL_WRITE,
                description = "화면 밝기를 비율로 설정합니다(쓰기 설정 권한 필요).",
                params = listOf(CommandRegistry.param("percent", "비율", true, "60")),
                keywords = listOf("밝기", "화면 밝게", "brightness"),
                patterns = listOf("^(?:밝기|화면)\\s*(?<percent>[0-9]{1,3})\\s*%?")
            )
        ) { req, ports ->
            missing(reg, req, "device.brightness")?.let { return@register it }
            val percent = req.arg("percent")?.toIntOrNull()
                ?: return@register CommandResult.Failed("비율을 숫자로 입력해 주세요(0~100).")
            runCatching { CommandResult.Ok(ports.device.setBrightness(percent)) }.getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "device.torch",
                labelKo = "손전등",
                category = CommandCategory.DEVICE,
                effect = EffectClass.LOCAL_WRITE,
                description = "후면 손전등을 켜거나 끕니다.",
                params = listOf(CommandRegistry.param("on", "켜기(true)/끄기(false)", true, "true")),
                keywords = listOf("손전등", "플래시", "torch", "flashlight"),
                patterns = listOf("^(?:손전등|플래시)\\s*(?<on>켜|끄|on|off)")
            )
        ) { req, ports ->
            val onText = req.arg("on") ?: "true"
            val on = onText.lowercase() !in setOf("false", "off", "끄", "끄기", "0")
            runCatching { CommandResult.Ok(ports.device.torch(on)) }.getOrElse { failure(it) }
        }

        // ---- hub ----------------------------------------------------------
        reg.register(
            CommandSpec(
                id = "hub.list",
                labelKo = "허브 보기",
                category = CommandCategory.HUB,
                effect = EffectClass.READ,
                description = "Personal Hub의 즐겨찾기를 보여줍니다.",
                keywords = listOf("허브", "즐겨찾기 목록", "hub")
            )
        ) { _, ports ->
            val items = ports.hub.ranked()
            if (items.isEmpty()) CommandResult.Ok("허브가 비어 있습니다. 자주 쓰는 앱이나 사이트를 추가해 보세요.")
            else CommandResult.Ok(
                output = buildString {
                    appendLine("**허브 ${items.size}개**")
                    items.forEach { appendLine("· [${it.kind.labelKo}] ${it.label}" + if (it.pinned) " ★" else "") }
                }.trim(),
                actions = items.take(3).map { ResultAction(it.label + " 열기", "hub.open", mapOf("label" to it.label)) }
            )
        }

        reg.register(
            CommandSpec(
                id = "hub.open",
                labelKo = "허브 항목 열기",
                category = CommandCategory.HUB,
                effect = EffectClass.EXTERNAL,
                description = "허브의 앱/사이트/명령을 이름으로 실행합니다.",
                params = listOf(CommandRegistry.param("label", "이름", true, "Gemini")),
                keywords = listOf("허브에서 열어", "즐겨찾기 열어")
            )
        ) { req, ports ->
            missing(reg, req, "hub.open")?.let { return@register it }
            val label = req.arg("label")!!
            val entry = ports.hub.search(label).firstOrNull()
                ?: return@register CommandResult.Failed("‘$label’ 항목을 허브에서 찾지 못했습니다.")
            ports.hub.markUsed(entry.id)
            when (entry.kind) {
                HubKind.APP -> {
                    val ok = runCatching { ports.apps.launch(entry.label) }.getOrDefault(false)
                    if (ok) CommandResult.Ok("‘${entry.label}’ 앱을 열었습니다.") else CommandResult.Failed("앱을 열지 못했습니다: ${entry.label}")
                }
                HubKind.SITE, HubKind.AI -> {
                    runCatching { ports.apps.openCustomTab(entry.target) }
                        .map { CommandResult.Ok("‘${entry.label}’ 페이지를 열었습니다.") }
                        .getOrElse { failure(it) }
                }
                HubKind.FILE -> {
                    runCatching { ports.files.shareExport(entry.target) }
                        .map { CommandResult.Ok("‘${entry.label}’ 파일 공유 시트를 열었습니다.") }
                        .getOrElse { failure(it) }
                }
                HubKind.COMMAND -> {
                    if (!reg.exists(entry.target)) {
                        CommandResult.Failed("허브의 명령 ‘${entry.target}’이(가) 사라졌습니다.")
                    } else {
                        val spec = reg.spec(entry.target)!!
                        val miss = reg.missingArg(com.hjtk.app.core.model.CommandRequest(entry.target), spec)
                        if (miss != null) {
                            CommandResult.NeedInput(miss.param, miss.prompt)
                        } else {
                            // A hub shortcut must actually run the command: hand it to
                            // the plan gateway, which applies the usual approval rules.
                            runCatching {
                                ports.plans.submit(
                                    listOf(com.hjtk.app.core.model.SkillStep(entry.target)),
                                    spec.labelKo
                                )
                            }.map { CommandResult.Ok("‘${spec.labelKo}’ 명령을 실행합니다.") }
                                .getOrElse { failure(it) }
                        }
                    }
                }
                HubKind.MAIL -> {
                    runCatching { ports.mail.composeDraft(entry.target, "", "") }
                        .map { CommandResult.Ok("메일 작성 화면을 열었습니다: ${entry.label}") }
                        .getOrElse { failure(it) }
                }
            }
        }

        reg.register(
            CommandSpec(
                id = "hub.add",
                labelKo = "허브에 추가",
                category = CommandCategory.HUB,
                effect = EffectClass.LOCAL_WRITE,
                description = "앱·사이트·명령을 허브에 추가합니다.",
                params = listOf(
                    CommandRegistry.param("label", "이름", true, "Gmail"),
                    CommandRegistry.param("target", "대상", true, "https://mail.google.com"),
                    CommandRegistry.param("kind", "종류(site/app/ai/command)", false, "site")
                ),
                keywords = listOf("허브 추가", "즐겨찾기 추가")
            )
        ) { req, ports ->
            missing(reg, req, "hub.add")?.let { return@register it }
            val kindText = req.arg("kind").orEmpty().lowercase()
            val kind = when (kindText) {
                "app" -> HubKind.APP
                "ai" -> HubKind.AI
                "command" -> HubKind.COMMAND
                "mail" -> HubKind.MAIL
                "file" -> HubKind.FILE
                else -> if (req.arg("target")!!.startsWith("http")) HubKind.SITE else HubKind.APP
            }
            val entry = ports.hub.add(kind, req.arg("label")!!, req.arg("target")!!)
            CommandResult.Ok("허브에 추가했습니다: ${entry.label} (${kind.labelKo})")
        }
    }
}
