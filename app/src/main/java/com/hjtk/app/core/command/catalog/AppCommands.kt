package com.hjtk.app.core.command.catalog

import com.hjtk.app.core.command.CommandRegistry
import com.hjtk.app.core.model.CommandCategory
import com.hjtk.app.core.model.CommandResult
import com.hjtk.app.core.model.CommandSpec
import com.hjtk.app.core.model.EffectClass
import com.hjtk.app.core.model.HubKind
import com.hjtk.app.core.model.ResultAction

/** Bridge to the rest of the phone: launch, share, dial, map, store. */
object AppCommands {

    fun register(reg: CommandRegistry) {

        reg.register(
            CommandSpec(
                id = "app.open",
                labelKo = "앱 실행",
                category = CommandCategory.APPS,
                effect = EffectClass.EXTERNAL,
                description = "이름으로 설치된 앱을 찾아 실행합니다.",
                params = listOf(CommandRegistry.param("name", "앱 이름", true, "카카오톡")),
                keywords = listOf("앱 실행", "열어줘", "켜줘", "launch", "open app"),
                patterns = listOf("^(?<name>.+?)\\s*(?:앱\\s*)?(?:실행|열어|켜줘)$")
            )
        ) { req, ports ->
            missing(reg, req, "app.open")?.let { return@register it }
            val name = req.arg("name")!!
            runCatching { ports.apps.launch(name) }
                .map { launched ->
                    if (launched) CommandResult.Ok("‘$name’ 앱을 열었습니다.")
                    else CommandResult.Ok("‘$name’ 앱을 찾지 못했습니다. ‘앜 목록’으로 설치된 앱 이름을 확인해 보세요.")
                }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "app.list",
                labelKo = "앱 목록",
                category = CommandCategory.APPS,
                effect = EffectClass.READ,
                description = "실행 가능한 앱 목록을 보여줍니다.",
                keywords = listOf("앱 목록", "설치된 앱", "installed apps")
            )
        ) { _, ports ->
            runCatching { ports.apps.installed() }.map { apps ->
                if (apps.isEmpty()) {
                    CommandResult.Ok("앱 목록을 읽을 수 없습니다. 패키지 가시성 설정을 확인해 주세요.")
                } else {
                    CommandResult.Ok(
                        output = buildString {
                            appendLine("**실행 가능한 앱 ${apps.size}개**")
                            apps.take(30).forEach { (label, pkg) -> appendLine("· $label  ($pkg)") }
                        }.trim()
                    )
                }
            }.getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "app.share",
                labelKo = "텍스트 공유",
                category = CommandCategory.APPS,
                effect = EffectClass.EXTERNAL,
                description = "다른 앱으로 텍스트를 공유합니다.",
                params = listOf(CommandRegistry.param("text", "공유할 내용", true, "오늘 회의는 3시입니다")),
                keywords = listOf("공유", "보내기", "share"),
                patterns = listOf("^(?<text>.+?)\\s*(?:공유해|공유)$")
            )
        ) { req, ports ->
            missing(reg, req, "app.share")?.let { return@register it }
            val text = req.arg("text")!!
            runCatching { ports.apps.shareText(text, "HJTK로 공유") }
                .map { CommandResult.Ok("공유 시트를 열었습니다. 보낼 앱을 골라 주세요.") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "app.custom_tab",
                labelKo = "웹 페이지 열기",
                category = CommandCategory.APPS,
                effect = EffectClass.EXTERNAL,
                description = "Custom Tab으로 웹 페이지를 엽니다(앱 안에서 닫기 쉽게).",
                params = listOf(CommandRegistry.param("url", "주소", true, "https://gemini.google.com/app")),
                keywords = listOf("웹 열어", "브라우저", "custom tab")
            )
        ) { req, ports ->
            missing(reg, req, "app.custom_tab")?.let { return@register it }
            runCatching { ports.apps.openCustomTab(req.arg("url")!!) }
                .map { CommandResult.Ok("웹 페이지를 열었습니다.") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "app.dial",
                labelKo = "전화 걸기 화면",
                category = CommandCategory.APPS,
                effect = EffectClass.EXTERNAL,
                description = "번호를 채운 전화 앱을 엽니다. 발신은 사용자가 누릅니다.",
                params = listOf(CommandRegistry.param("number", "전화번호", true, "010-0000-0000")),
                keywords = listOf("전화", "통화", "dial", "call"),
                patterns = listOf("^(?<number>[0-9+\\-\\s]{7,20})\\s*(?:로)?\\s*(?:전화|통화)")
            )
        ) { req, ports ->
            missing(reg, req, "app.dial")?.let { return@register it }
            runCatching { ports.apps.dial(req.arg("number")!!) }
                .map { CommandResult.Ok("전화 앱에 번호를 채웠습니다. 발신 버튼을 눌러 주세요.") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "app.sms",
                labelKo = "문자 작성",
                category = CommandCategory.APPS,
                effect = EffectClass.EXTERNAL,
                description = "문자 앱에 번호와 내용을 채워 엽니다.",
                params = listOf(
                    CommandRegistry.param("number", "전화번호", true, "010-0000-0000"),
                    CommandRegistry.param("body", "내용", false, "안녕하세요")
                ),
                keywords = listOf("문자", "sms", "text message")
            )
        ) { req, ports ->
            missing(reg, req, "app.sms")?.let { return@register it }
            runCatching { ports.apps.sms(req.arg("number")!!, req.arg("body").orEmpty()) }
                .map { CommandResult.Ok("문자 앱을 열었습니다. 내용을 확인하고 보내 주세요.") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "app.map",
                labelKo = "길찾기",
                category = CommandCategory.APPS,
                effect = EffectClass.EXTERNAL,
                description = "지도 앱에서 목적지까지 길찾기를 엽니다.",
                params = listOf(CommandRegistry.param("destination", "목적지", true, "강남역")),
                keywords = listOf("길찾기", "지도", "경로", "map", "navigate"),
                patterns = listOf("^(?<destination>.+?)\\s*(?:까지|로|으로)\\s*(?:길찾기|가는 길|경로)")
            )
        ) { req, ports ->
            missing(reg, req, "app.map")?.let { return@register it }
            runCatching { ports.apps.openMap(req.arg("destination")!!) }
                .map { CommandResult.Ok("지도에서 길찾기를 열었습니다: ${req.arg("destination")}") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "app.store_search",
                labelKo = "스토어 검색",
                category = CommandCategory.APPS,
                effect = EffectClass.EXTERNAL,
                description = "앱 마켓에서 검색합니다.",
                params = listOf(CommandRegistry.param("query", "검색어", true, "메모 앱")),
                keywords = listOf("플레이스토어", "스토어", "앱 검색", "play store")
            )
        ) { req, ports ->
            missing(reg, req, "app.store_search")?.let { return@register it }
            runCatching { ports.apps.openStoreSearch(req.arg("query")!!) }
                .map { CommandResult.Ok("스토어 검색을 열었습니다.") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "app.save_to_hub",
                labelKo = "허브에 저장",
                category = CommandCategory.APPS,
                effect = EffectClass.LOCAL_WRITE,
                description = "지금 연 앱/사이트를 Personal Hub 즐겨찾기에 추가합니다.",
                params = listOf(
                    CommandRegistry.param("label", "이름", true, "Gemini"),
                    CommandRegistry.param("target", "대상(패키지/주소)", true, "https://gemini.google.com/app")
                ),
                keywords = listOf("즐겨찾기", "허브에 추가", "pin")
            )
        ) { req, ports ->
            missing(reg, req, "app.save_to_hub")?.let { return@register it }
            val label = req.arg("label")!!
            val target = req.arg("target")!!
            val kind = if (target.startsWith("http")) HubKind.SITE else HubKind.APP
            ports.hub.add(kind, label, target)
            CommandResult.Ok("허브에 추가했습니다: $label", actions = listOf(ResultAction("허브 열기", "hub.list")))
        }
    }
}
