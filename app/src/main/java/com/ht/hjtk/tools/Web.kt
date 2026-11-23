package com.ht.hjtk.tools

import com.ht.hjtk.engine.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

data class Hit(val title: String, val url: String, val snippet: String, val source: String) {
    fun line(i: Int) = "${i + 1}. $title\n   $url" + (if (snippet.isNotBlank()) "\n   ${snippet.take(220)}" else "") + "  [$source]"
}

object HtmlText {
    private val ent = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ", "&middot;" to "·", "&hellip;" to "…")
    fun decode(s: String): String {
        var t = s
        ent.forEach { (k, v) -> t = t.replace(k, v) }
        t = Regex("&#(\\d+);").replace(t) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        t = Regex("&#x([0-9a-fA-F]+);").replace(t) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        return t
    }
    fun strip(s: String) = decode(s.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()

    fun title(html: String) = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let { strip(it) } ?: ""
    fun meta(html: String, prop: String) = Regex("<meta[^>]+(?:property|name)=[\"']$prop[\"'][^>]*content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let { decode(it) }
        ?: Regex("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']$prop[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let { decode(it) }

    /** 가독 본문 추출: 스크립트·내비 제거 → 블록 경계를 줄바꿈으로 → 짧은 메뉴 줄 제거 */
    fun extract(html: String, max: Int = 8000): String {
        var t = html
        t = t.replace(Regex("<(script|style|noscript|svg|iframe|template)[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("<(nav|footer|header|aside|form)[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("<!--[\\s\\S]*?-->"), " ")
        t = t.replace(Regex("<(br|/p|/div|/li|/h[1-6]|/tr|/section|/article|/table|/ul|/ol)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        t = t.replace(Regex("<h([1-6])[^>]*>", RegexOption.IGNORE_CASE), "\n## ")
        t = t.replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n- ")
        t = t.replace(Regex("<t[dh][^>]*>", RegexOption.IGNORE_CASE), " | ")
        t = decode(t.replace(Regex("<[^>]+>"), " "))
        val lines = t.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .filter { it.length > 1 }
        val keep = lines.filter { it.length > 18 || it.startsWith("##") || it.startsWith("-") || Regex("\\d").containsMatchIn(it) }
        return keep.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").take(max)
    }

    fun links(html: String, base: String): List<Pair<String, String>> =
        Regex("<a[^>]+href=[\"']([^\"'#]+)[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { m ->
            val href = runCatching { java.net.URL(java.net.URL(base), m.groupValues[1]).toString() }.getOrNull() ?: return@mapNotNull null
            val text = strip(m.groupValues[2])
            if (!href.startsWith("http") || text.length < 2) null else text.take(80) to href
        }.distinctBy { it.second }.toList()
}

object Search {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun ddg(q: String, n: Int): List<Hit> = runCatching {
        val r = Http.get("https://html.duckduckgo.com/html/?q=${enc(q)}&kl=kr-kr")
        val titles = Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>").findAll(r.body).toList()
        val snippets = Regex("class=\"result__snippet\"[^>]*>([\\s\\S]*?)</a>").findAll(r.body).map { HtmlText.strip(it.groupValues[1]) }.toList()
        titles.mapIndexedNotNull { i, m ->
            var u = HtmlText.decode(m.groupValues[1])
            Regex("uddg=([^&]+)").find(u)?.let { u = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (u.startsWith("//")) u = "https:$u"
            if (u.contains("duckduckgo.com/y.js")) null else Hit(HtmlText.strip(m.groupValues[2]), u, snippets.getOrElse(i) { "" }, "DDG")
        }.take(n)
    }.getOrDefault(emptyList())

    suspend fun bing(q: String, n: Int): List<Hit> = runCatching {
        val r = Http.get("https://www.bing.com/search?q=${enc(q)}&setlang=ko&cc=KR")
        Regex("<li class=\"b_algo\"[\\s\\S]*?<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>([\\s\\S]*?)</li>").findAll(r.body).map { m ->
            val snip = Regex("<p[^>]*>([\\s\\S]*?)</p>").find(m.groupValues[3])?.groupValues?.get(1)?.let { HtmlText.strip(it) } ?: ""
            Hit(HtmlText.strip(m.groupValues[2]), HtmlText.decode(m.groupValues[1]), snip, "Bing")
        }.filter { it.url.startsWith("http") }.take(n).toList()
    }.getOrDefault(emptyList())

    suspend fun naverHtml(q: String, n: Int, where: String = "web"): List<Hit> = runCatching {
        val w = when (where) { "news" -> "&where=news"; "blog" -> "&where=blog"; else -> "&where=web" }
        val r = Http.get("https://search.naver.com/search.naver?query=${enc(q)}$w")
        val rx = Regex("<a[^>]*href=\"(https?://[^\"]+)\"[^>]*class=\"[^\"]*(?:link_tit|title_link|news_tit|api_txt_lines|total_tit|lnk_tit)[^\"]*\"[^>]*>([\\s\\S]*?)</a>")
        val rx2 = Regex("<a[^>]*class=\"[^\"]*(?:link_tit|title_link|news_tit|api_txt_lines|total_tit|lnk_tit)[^\"]*\"[^>]*href=\"(https?://[^\"]+)\"[^>]*>([\\s\\S]*?)</a>")
        (rx.findAll(r.body) + rx2.findAll(r.body)).map { Hit(HtmlText.strip(it.groupValues[2]), HtmlText.decode(it.groupValues[1]), "", "네이버") }
            .filter { it.title.length > 3 && !it.url.contains("search.naver.com") }.distinctBy { it.url }.take(n).toList()
    }.getOrDefault(emptyList())

    suspend fun naverApi(q: String, type: String, id: String, secret: String, n: Int): List<Hit> = runCatching {
        val ep = when (type) { "news" -> "news"; "blog" -> "blog"; else -> "webkr" }
        val r = Http.get("https://openapi.naver.com/v1/search/$ep.json?query=${enc(q)}&display=$n", mapOf("X-Naver-Client-Id" to id, "X-Naver-Client-Secret" to secret))
        val items = JSONObject(r.body).getJSONArray("items")
        (0 until items.length()).map { val o = items.getJSONObject(it); Hit(HtmlText.strip(o.optString("title")), o.optString("originallink").ifBlank { o.optString("link") }, HtmlText.strip(o.optString("description")), "네이버API") }
    }.getOrDefault(emptyList())

    suspend fun news(q: String, n: Int): List<Hit> = runCatching {
        val r = Http.get("https://news.google.com/rss/search?q=${enc(q)}&hl=ko&gl=KR&ceid=KR:ko")
        rss(r.body, n, "뉴스")
    }.getOrDefault(emptyList()).ifEmpty { browser("$q 뉴스", n) }

    fun rss(xml: String, n: Int, src: String): List<Hit> {
        val items = Regex("<(item|entry)>([\\s\\S]*?)</\\1>").findAll(xml).take(n).map { it.groupValues[2] }
        return items.map { b ->
            fun tag(t: String) = Regex("<$t[^>]*>([\\s\\S]*?)</$t>").find(b)?.groupValues?.get(1)?.replace("<![CDATA[", "")?.replace("]]>", "")?.let { HtmlText.strip(it) } ?: ""
            val link = tag("link").ifBlank { Regex("<link[^>]*href=\"([^\"]+)\"").find(b)?.groupValues?.get(1) ?: "" }
            Hit(tag("title"), link, listOf(tag("pubDate").ifBlank { tag("updated") }, tag("source")).filter { it.isNotBlank() }.joinToString(" · "), src)
        }.toList()
    }

    suspend fun wiki(q: String, lang: String): String? = runCatching {
        val s = Http.get("https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc(q)}&format=json&srlimit=1")
        val title = JSONObject(s.body).getJSONObject("query").getJSONArray("search").getJSONObject(0).getString("title")
        val sum = Http.get("https://$lang.wikipedia.org/api/rest_v1/page/summary/${enc(title.replace(' ', '_'))}")
        val o = JSONObject(sum.body)
        "${o.optString("title")}\n${o.optString("extract")}\nhttps://$lang.wikipedia.org/wiki/${enc(title.replace(' ', '_'))}"
    }.getOrNull()

    /** 여러 검색원을 동시에 조회해 합친다. 하나가 막혀도 나머지로 답한다. */
    suspend fun multi(q: String, n: Int, naverKey: Pair<String, String>?): List<Hit> = coroutineScope {
        val jobs = listOf(
            async { if (naverKey != null) naverApi(q, "web", naverKey.first, naverKey.second, n) else naverHtml(q, n) },
            async { ddg(q, n).ifEmpty { bing(q, n) } }
        )
        val all = jobs.awaitAll().flatten().ifEmpty { browser(q, n) }   // 다 막히면 보이지 않는 진짜 브라우저로
        val seen = HashSet<String>()
        all.filter { seen.add(it.url.substringBefore('#').trimEnd('/')) }.take(n * 2)
    }

    suspend fun browser(q: String, n: Int): List<Hit> = runCatching { com.ht.hjtk.engine.Scout.search(q, n).map { Hit(it.title, it.url, it.snippet, "브라우저") } }.getOrDefault(emptyList())

    suspend fun site(site: String, q: String, n: Int): List<Hit> = ddg("site:$site $q", n).ifEmpty { bing("site:$site $q", n) }
}
