package com.hjtk.app.core.command.catalog

import com.hjtk.app.core.command.CommandRegistry
import com.hjtk.app.core.model.CommandCategory
import com.hjtk.app.core.model.CommandResult
import com.hjtk.app.core.model.CommandSpec
import com.hjtk.app.core.model.EffectClass
import com.hjtk.app.core.model.ResultAction
import com.hjtk.app.core.util.TimeFmt

/**
 * Web capabilities. Every network call goes through [com.hjtk.app.core.WebPort],
 * an interface with one Android adapter, so the command layer stays testable
 * and a different backend can be swapped in without touching the router.
 */
object SearchCommands {

    fun register(reg: CommandRegistry) {

        reg.register(
            CommandSpec(
                id = "search.web",
                labelKo = "웹 검색",
                category = CommandCategory.SEARCH,
                effect = EffectClass.READ,
                description = "검색어로 웹을 검색하고 상위 결과를 요약해 보여줍니다.",
                params = listOf(CommandRegistry.param("query", "검색어", true, "안드로이드 15 변경점")),
                keywords = listOf("검색", "찾아", "알아봐", "search", "look up", "웹"),
                patterns = listOf(
                    "^(?<query>.+?)\\s*(?:검색해|검색해줘|검색)$",
                    "^(?:검색|search)\\s*[:：]?\\s*(?<query>.+)$",
                    "^(?<query>.+?)\\s*(?:에 대해 )?(?:찾아봐|알아봐|조사해)"
                )
            )
        ) { req, ports ->
            missing(reg, req, "search.web")?.let { return@register it }
            val query = req.arg("query")!!
            runCatching { ports.web.search(query, 5) }
                .map { hits ->
                    if (hits.isEmpty()) {
                        CommandResult.Ok("‘$query’ 검색 결과를 찾지 못했습니다. 검색어를 바꾸거나 네트워크를 확인해 주세요.")
                    } else {
                        val body = buildString {
                            appendLine("**‘$query’ 검색 결과**")
                            hits.forEachIndexed { i, h ->
                                appendLine()
                                appendLine("${i + 1}. ${h.title}")
                                appendLine(h.url)
                                if (h.snippet.isNotBlank()) appendLine(h.snippet.take(160))
                            }
                        }
                        CommandResult.Ok(
                            output = body.trim(),
                            data = mapOf("firstUrl" to hits.first().url, "firstTitle" to hits.first().title),
                            actions = listOf(ResultAction("첫 결과 열기", "search.open_url", mapOf("url" to hits.first().url)))
                        )
                    }
                }
                .getOrElse { failure(it, "네트워크 상태를 확인해 주세요.") }
        }

        reg.register(
            CommandSpec(
                id = "search.news",
                labelKo = "뉴스 검색",
                category = CommandCategory.SEARCH,
                effect = EffectClass.READ,
                description = "최근 뉴스 헤드라인을 검색합니다(RSS 피드 기반).",
                params = listOf(CommandRegistry.param("query", "검색어", true, "인공지능 규제")),
                keywords = listOf("뉴스", "기사", "헤드라인", "news"),
                patterns = listOf(
                    "^(?<query>.+?)\\s*(?:뉴스|기사)\\s*(?:검색|찾아|보여|알려)"
                )
            )
        ) { req, ports ->
            missing(reg, req, "search.news")?.let { return@register it }
            val query = req.arg("query")!!
            runCatching { ports.web.news(query, 5) }
                .map { hits ->
                    if (hits.isEmpty()) {
                        CommandResult.Ok("‘$query’ 관련 뉴스를 찾지 못했습니다.")
                    } else {
                        CommandResult.Ok(
                            output = buildString {
                                appendLine("**‘$query’ 뉴스**")
                                hits.forEach {
                                    appendLine()
                                    appendLine("· ${it.title}")
                                    appendLine(it.snippet.take(120))
                                    appendLine(it.url)
                                }
                            }.trim(),
                            data = mapOf("firstUrl" to hits.first().url)
                        )
                    }
                }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "search.wiki",
                labelKo = "백과 요약",
                category = CommandCategory.SEARCH,
                effect = EffectClass.READ,
                description = "위키백과 요약으로 개념을 빠르게 확인합니다.",
                params = listOf(CommandRegistry.param("topic", "주제", true, "람지 수")),
                keywords = listOf("뜻", "의미", "정의", "백과", "위키", "뭐야", "무엇"),
                patterns = listOf(
                    "^(?<topic>.+?)\\s*(?:이란|란|은 뭐야|는 뭐야|이 뭐야|뜻이 뭐야|정의)",
                    "^(?:정의|뜻|의미)\\s*[:：]?\\s*(?<topic>.+)$"
                )
            )
        ) { req, ports ->
            missing(reg, req, "search.wiki")?.let { return@register it }
            val topic = req.arg("topic")!!
            runCatching { ports.web.summary(topic) }
                .map { hit ->
                    if (hit == null) {
                        CommandResult.Ok("‘$topic’에 대한 백과 문서를 찾지 못했습니다. 웹 검색을 시도해 보세요.")
                    } else {
                        CommandResult.Ok(
                            output = "**${hit.title}**\n\n${hit.snippet}",
                            data = mapOf("url" to hit.url),
                            actions = listOf(ResultAction("문서 열기", "search.open_url", mapOf("url" to hit.url)))
                        )
                    }
                }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "search.images",
                labelKo = "이미지 검색",
                category = CommandCategory.SEARCH,
                effect = EffectClass.EXTERNAL,
                description = "이미지 검색 페이지를 브라우저로 엽니다.",
                params = listOf(CommandRegistry.param("query", "검색어", true, "고양이 캐릭터")),
                keywords = listOf("이미지", "사진", "그림", "image"),
                patterns = listOf("^(?<query>.+?)\\s*(?:이미지|사진|그림)\\s*(?:검색|찾아|보여)")
            )
        ) { req, ports ->
            missing(reg, req, "search.images")?.let { return@register it }
            val q = req.arg("query")!!
            val url = "https://www.google.com/search?tbm=isch&q=" + java.net.URLEncoder.encode(q, "UTF-8")
            runCatching { ports.web.openUrl(url) }
                .map { CommandResult.Ok("이미지 검색을 열었습니다: $q") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "search.open_url",
                labelKo = "주소 열기",
                category = CommandCategory.SEARCH,
                effect = EffectClass.EXTERNAL,
                description = "입력한 주소를 브라우저에서 엽니다.",
                params = listOf(CommandRegistry.param("url", "주소", true, "https://example.com")),
                keywords = listOf("열어", "브라우저", "사이트", "open url"),
                patterns = listOf("^(?:열어|오픈)\\s*[:：]?\\s*(?<url>https?://\\S+)$")
            )
        ) { req, ports ->
            missing(reg, req, "search.open_url")?.let { return@register it }
            val url = req.arg("url")!!
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@register CommandResult.Failed("http(s) 주소만 열 수 있습니다.")
            }
            runCatching { ports.web.openUrl(url) }
                .map { CommandResult.Ok("열었습니다: $url") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "search.page_summary",
                labelKo = "페이지 요약 준비",
                category = CommandCategory.SEARCH,
                effect = EffectClass.READ,
                description = "페이지 본문을 읽어 AI 요약에 넘길 준비를 합니다.",
                params = listOf(CommandRegistry.param("url", "주소", true, "https://example.com/article")),
                keywords = listOf("페이지", "본문", "읽어", "요약할", "article"),
                patterns = listOf("^(?<url>https?://\\S+)\\s*(?:읽어|본문|내용)")
            )
        ) { req, ports ->
            missing(reg, req, "search.page_summary")?.let { return@register it }
            val url = req.arg("url")!!
            runCatching { ports.web.fetchText(url, 4000) }
                .map { text ->
                    if (text.isBlank()) {
                        CommandResult.Ok("페이지에서 읽을 수 있는 본문을 찾지 못했습니다(로그인/스크립트 페이지일 수 있습니다).")
                    } else {
                        CommandResult.Ok(
                            output = "본문 ${text.length}자를 읽었습니다.\n\n" + text.take(600) + if (text.length > 600) "\n…" else "",
                            data = mapOf("text" to text, "url" to url),
                            actions = listOf(
                                ResultAction("이 본문 요약", "ai.summarize", mapOf("text" to text.take(3500))),
                                ResultAction("파일로 저장", "file.export_text", mapOf("content" to text, "name" to "page-${TimeFmt.iso(ports.now())}.txt"))
                            )
                        )
                    }
                }
                .getOrElse { failure(it) }
        }
    }
}
