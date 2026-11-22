package com.hjtk.app.android.ports

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hjtk.app.core.SearchHit
import com.hjtk.app.core.WebPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Network reads for search, news, encyclopedia and page text.
 *
 * Everything here is a public, documented endpoint (DuckDuckGo HTML, Bing News
 * RSS, Wikipedia REST). Results are parsed defensively: if a provider changes
 * its markup the command fails with a readable message instead of returning
 * invented data.
 */
class WebPortImpl(
    private val context: Context,
    private val client: OkHttpClient = defaultClient()
) : WebPort {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "ko,en;q=0.8").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    override suspend fun search(query: String, limit: Int): List<SearchHit> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = runCatching { get("https://html.duckduckgo.com/html/?q=$q&kl=kr-kr") }.getOrDefault("")
        val hits = parseDdg(html, limit)
        if (hits.isNotEmpty()) return@withContext hits
        val lite = runCatching { get("https://lite.duckduckgo.com/lite/?q=$q") }.getOrDefault("")
        parseLite(lite, limit)
    }

    private fun parseDdg(html: String, limit: Int): List<SearchHit> {
        if (html.isBlank()) return emptyList()
        val linkRe = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val snippetRe = Regex("<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val links = linkRe.findAll(html).toList()
        val snippets = snippetRe.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        return links.take(limit).mapIndexed { i, m ->
            SearchHit(
                title = stripTags(m.groupValues[2]).ifBlank { "결과 ${i + 1}" },
                url = cleanDdgUrl(m.groupValues[1]),
                snippet = snippets.getOrNull(i).orEmpty().trim()
            )
        }.filter { it.url.startsWith("http") }
    }

    private fun parseLite(html: String, limit: Int): List<SearchHit> {
        if (html.isBlank()) return emptyList()
        val linkRe = Regex("<a[^>]*class=\"result-link\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val snippetRe = Regex("<td[^>]*class=\"result-snippet\"[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
        val links = linkRe.findAll(html).toList()
        val snippets = snippetRe.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        return links.take(limit).mapIndexed { i, m ->
            SearchHit(stripTags(m.groupValues[2]), cleanDdgUrl(m.groupValues[1]), snippets.getOrNull(i).orEmpty().trim())
        }.filter { it.url.startsWith("http") }
    }

    private fun cleanDdgUrl(raw: String): String {
        val decoded = raw.replace("&amp;", "&")
        val idx = decoded.indexOf("uddg=")
        if (idx < 0) return decoded
        val encoded = decoded.substring(idx + 5).substringBefore('&')
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(decoded)
    }

    override suspend fun news(query: String, limit: Int): List<SearchHit> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val xml = get("https://www.bing.com/news/search?q=$q&format=RSS")
        Regex("<item>([\\s\\S]*?)</item>").findAll(xml).take(limit).map { item ->
            val block = item.groupValues[1]
            SearchHit(
                title = unescape(Regex("<title>(.*?)</title>").find(block)?.groupValues?.get(1).orEmpty()),
                url = Regex("<link>(.*?)</link>").find(block)?.groupValues?.get(1).orEmpty().trim(),
                snippet = unescape(stripTags(Regex("<description>(.*?)</description>").find(block)?.groupValues?.get(1).orEmpty()))
            )
        }.filter { it.title.isNotBlank() && it.url.startsWith("http") }.toList()
    }

    override suspend fun summary(topic: String): SearchHit? = withContext(Dispatchers.IO) {
        val title = URLEncoder.encode(topic.trim().replace(' ', '_'), "UTF-8")
        val body = runCatching { get("https://ko.wikipedia.org/api/rest_v1/page/summary/$title") }.getOrNull() ?: return@withContext null
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@withContext null
        val extract = (obj["extract"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        if (extract.isBlank()) return@withContext null
        val url = runCatching {
            obj["content_urls"]?.jsonObject?.get("desktop")?.jsonObject?.get("page")?.jsonPrimitive?.content
        }.getOrNull() ?: "https://ko.wikipedia.org/wiki/$title"
        SearchHit(
            title = (obj["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: topic,
            url = url,
            snippet = extract
        )
    }

    override suspend fun fetchText(url: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        val html = get(url)
        val body = html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<nav[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<footer[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), " ")
        val text = unescape(stripTags(body)).replace(Regex("[ \\t\\u00A0]+"), " ").replace(Regex("\\n{3,}"), "\n\n")
        text.trim().take(maxChars)
    }

    override suspend fun openUrl(url: String) = withContext(Dispatchers.Main) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun stripTags(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun unescape(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")

}
