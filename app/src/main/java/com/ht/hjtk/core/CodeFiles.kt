package com.ht.hjtk.core

/**
 * 결과 글(마크다운)의 코드 블록을 실제 파일로 — GitHub에 올릴 때 '글 한 덩어리'가 아니라 동작하는 저장소가 되게.
 * 파일 이름은 블록 바로 위 줄(`app.py`, **index.html**, 파일: main.kt) 또는 블록 첫 줄 주석(// file: x)에서, 없으면 언어로 짓는다.
 */
object CodeFiles {
    private val FENCE = Regex("""```([\w+#.-]*)[^\n]*\n([\s\S]*?)```""")
    private val NAME = Regex("""([\w./-]+\.[A-Za-z0-9]{1,6})""")
    private val EXT = mapOf("python" to "py", "py" to "py", "kotlin" to "kt", "kt" to "kt", "java" to "java", "javascript" to "js", "js" to "js", "typescript" to "ts", "ts" to "ts",
        "html" to "html", "css" to "css", "json" to "json", "bash" to "sh", "sh" to "sh", "shell" to "sh", "yaml" to "yml", "yml" to "yml", "xml" to "xml", "sql" to "sql",
        "c" to "c", "cpp" to "cpp", "c++" to "cpp", "go" to "go", "rust" to "rs", "rs" to "rs", "swift" to "swift", "dart" to "dart", "gradle" to "gradle", "toml" to "toml", "md" to "md")

    fun extract(md: String): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        val count = HashMap<String, Int>()
        for (m in FENCE.findAll(md)) {
            val lang = m.groupValues[1].lowercase()
            val code = m.groupValues[2].trimEnd() + "\n"
            if (code.lines().size < 3 || lang in setOf("text", "txt", "output", "console", "plaintext")) continue
            val before = md.substring(0, m.range.first).trimEnd().lines().lastOrNull().orEmpty().takeLast(120)
            val first = code.lineSequence().first()
            val hinted = NAME.findAll(before).lastOrNull()?.value?.takeIf { Regex("""(파일|file|`|\*\*|:|#)""", RegexOption.IGNORE_CASE).containsMatchIn(before) }
                ?: Regex("""^\s*(?://|#|<!--|--)\s*(?:file(?:name)?|파일)\s*[:：]\s*([\w./-]+)""", RegexOption.IGNORE_CASE).find(first)?.groupValues?.get(1)
            val ext = EXT[lang] ?: hinted?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() } ?: continue
            var name = (hinted ?: when (ext) { "html" -> "index.html"; "py" -> "main.py"; "js" -> "app.js"; "css" -> "style.css"; "kt" -> "Main.kt"; "java" -> "Main.java"; "sh" -> "run.sh"; else -> "code.$ext" })
                .trimStart('/').replace("..", "")
            if (name in out) { val n = (count[name] ?: 1) + 1; count[name] = n; name = name.substringBeforeLast('.') + "_" + n + "." + name.substringAfterLast('.') }
            out[name] = code
        }
        return out.toList()
    }
}
