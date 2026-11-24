package com.ht.hjtk.core.forge

/**
 * 완성본 묶기: 프로젝트의 여러 마크다운 파일을 '한 권'으로.
 *  · HTML 완성본 — 목차, 장별 구획, 인쇄용 여백. 폰에서 열고, 공유하고, PDF로 인쇄할 수 있다.
 *  · 합본 마크다운 — 다른 도구로 옮길 때.
 * 마크다운 변환기는 결과물에 실제로 나오는 문법만 다룬다: 제목, 문단, 목록, 표, 코드, 인용, 굵게·기울임·코드·링크, 가로줄.
 */
object Bundle {
    data class Part(val path: String, val text: String)

    fun markdown(title: String, parts: List<Part>): String = buildString {
        appendLine("# $title"); appendLine()
        parts.forEach { appendLine("<!-- ${it.path} -->"); appendLine(it.text.trim()); appendLine(); appendLine("---"); appendLine() }
    }

    fun html(title: String, subtitle: String, parts: List<Part>, credit: String): String = buildString {
        append("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>")
        append(esc(title)); append("</title><style>").append(CSS).append("</style></head><body><main>")
        append("<header><p class=\"k\">HJTK 프로젝트</p><h1 class=\"t\">").append(esc(title)).append("</h1>")
        if (subtitle.isNotBlank()) append("<p class=\"s\">").append(esc(subtitle)).append("</p>")
        append("</header>")
        if (parts.size > 1) {
            append("<nav><p class=\"k\">목차</p><ol>")
            parts.forEachIndexed { i, p -> append("<li><a href=\"#p$i\">").append(esc(heading(p))).append("</a></li>") }
            append("</ol></nav>")
        }
        parts.forEachIndexed { i, p -> append("<section id=\"p$i\"><p class=\"f\">").append(esc(p.path)).append("</p>").append(toHtml(p.text)).append("</section>") }
        append("<footer>").append(esc(credit)).append("</footer></main></body></html>")
    }

    private fun heading(p: Part) = p.text.lineSequence().firstOrNull { it.trimStart().startsWith("#") }?.trimStart('#', ' ')?.trim()?.ifBlank { null } ?: p.path

    fun toHtml(md: String): String {
        val out = StringBuilder()
        val lines = md.replace("\r", "").lines()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val t = l.trim()
            when {
                t.startsWith("```") -> {
                    val lang = t.removePrefix("```").trim()
                    val code = StringBuilder(); i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code.appendLine(lines[i]); i++ }
                    out.append("<pre><code").append(if (lang.isNotBlank()) " data-lang=\"${esc(lang)}\"" else "").append(">").append(esc(code.toString().trimEnd())).append("</code></pre>")
                    i++; continue
                }
                t.isEmpty() -> { i++; continue }
                Regex("^#{1,6} ").containsMatchIn(t) -> { val n = t.takeWhile { it == '#' }.length.coerceIn(1, 4) + 1; out.append("<h$n>").append(inline(t.drop(n - 1).trim())).append("</h$n>") }
                Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> out.append("<hr>")
                t.startsWith(">") -> {
                    val q = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { q.append(lines[i].trim().removePrefix(">").trim()).append(' '); i++ }
                    out.append("<blockquote>").append(inline(q.toString().trim())).append("</blockquote>"); continue
                }
                t.startsWith("|") && i + 1 < lines.size && Regex("^\\|?\\s*:?-{2,}").containsMatchIn(lines[i + 1].trim()) -> {
                    fun cells(s: String) = s.trim().trim('|').split('|').map { it.trim() }
                    out.append("<div class=\"tw\"><table><thead><tr>"); cells(t).forEach { out.append("<th>").append(inline(it)).append("</th>") }; out.append("</tr></thead><tbody>")
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) { out.append("<tr>"); cells(lines[i]).forEach { out.append("<td>").append(inline(it)).append("</td>") }; out.append("</tr>"); i++ }
                    out.append("</tbody></table></div>"); continue
                }
                Regex("^([-*+]|\\d+[.)]) ").containsMatchIn(t) -> {
                    val ordered = t.first().isDigit()
                    out.append(if (ordered) "<ol>" else "<ul>")
                    while (i < lines.size && Regex("^\\s*([-*+]|\\d+[.)]) ").containsMatchIn(lines[i])) {
                        out.append("<li>").append(inline(lines[i].trim().replaceFirst(Regex("^([-*+]|\\d+[.)]) "), ""))).append("</li>"); i++
                    }
                    out.append(if (ordered) "</ol>" else "</ul>"); continue
                }
                else -> {
                    val para = StringBuilder()
                    while (i < lines.size && lines[i].isNotBlank() && !Regex("^\\s*(#{1,6} |```|>|\\||[-*+] |\\d+[.)] |-{3,}$)").containsMatchIn(lines[i])) { para.append(lines[i].trim()).append(' '); i++ }
                    if (para.isEmpty()) { para.append(t); i++ }
                    out.append("<p>").append(inline(para.toString().trim())).append("</p>"); continue
                }
            }
            i++
        }
        return out.toString()
    }

    private fun inline(s: String): String {
        var h = esc(s)
        h = Regex("`([^`]+)`").replace(h) { "<code>${it.groupValues[1]}</code>" }
        h = Regex("\\*\\*([^*]+)\\*\\*").replace(h) { "<strong>${it.groupValues[1]}</strong>" }
        h = Regex("(?<![*\\w])\\*([^*\\s][^*]*)\\*(?!\\w)").replace(h) { "<em>${it.groupValues[1]}</em>" }
        h = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)").replace(h) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        h = Regex("(?<![\"=>])(https?://[^\\s<)]+)").replace(h) { "<a href=\"${it.value}\">${it.value}</a>" }
        return h
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // 먹과 인장: 종이색 바탕, 먹색 글, 주홍은 표시에만. 인쇄하면 여백이 그대로 PDF가 된다
    private const val CSS = """
:root{--ink:#141414;--paper:#F6F3EC;--mute:#6B6862;--hair:#DDD8CC;--red:#E44D2E}
*{box-sizing:border-box}html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--paper);color:var(--ink);font:16px/1.75 "Pretendard","Apple SD Gothic Neo","Noto Sans KR",system-ui,sans-serif;word-break:keep-all}
main{max-width:760px;margin:0 auto;padding:56px 22px 80px}
header{border-bottom:1px solid var(--ink);padding-bottom:28px;margin-bottom:32px}
.k{font:600 11px/1 ui-monospace,monospace;letter-spacing:.12em;text-transform:uppercase;color:var(--red);margin:0 0 14px}
.t{font-size:34px;line-height:1.25;margin:0;letter-spacing:-.02em}.s{color:var(--mute);margin:12px 0 0}
nav{border-bottom:1px solid var(--hair);padding-bottom:22px;margin-bottom:12px}nav ol{margin:0;padding-left:22px}nav a{color:var(--ink);text-decoration:none}
section{padding-top:28px;border-top:1px solid var(--hair);margin-top:28px}section:first-of-type{border-top:0;margin-top:0}
.f{font:500 11px/1 ui-monospace,monospace;color:var(--mute);margin:0 0 8px}
h2{font-size:25px;line-height:1.35;margin:8px 0 14px;letter-spacing:-.01em}h3{font-size:19px;margin:28px 0 8px}h4,h5{font-size:16px;margin:22px 0 6px}
p{margin:0 0 14px}a{color:var(--ink);text-decoration-color:var(--red);text-underline-offset:3px}
ul,ol{padding-left:22px;margin:0 0 14px}li{margin:3px 0}
blockquote{margin:16px 0;padding:2px 0 2px 16px;border-left:2px solid var(--red);color:#3a3834}
code{font:14px/1.5 ui-monospace,monospace;background:#ECE7DC;padding:1px 5px;border-radius:3px}
pre{background:#141414;color:#F6F3EC;padding:16px;border-radius:6px;overflow:auto}pre code{background:none;padding:0;color:inherit}
.tw{overflow-x:auto;margin:0 0 16px}table{border-collapse:collapse;width:100%;font-size:14.5px}th,td{border-bottom:1px solid var(--hair);padding:8px 10px;text-align:left;vertical-align:top}th{border-bottom:1px solid var(--ink);font-weight:600}
hr{border:0;border-top:1px solid var(--hair);margin:28px 0}
footer{margin-top:48px;padding-top:16px;border-top:1px solid var(--ink);font-size:12.5px;color:var(--mute)}
@media print{body{background:#fff}main{padding:0}a{text-decoration:none}section{break-inside:auto}h2,h3{break-after:avoid}}
"""
}
