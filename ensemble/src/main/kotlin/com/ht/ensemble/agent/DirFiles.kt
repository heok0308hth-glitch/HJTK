package com.ht.ensemble.agent

import java.io.File

/** 프로젝트 폴더를 실제 디렉터리에. 하위 폴더(src/…) 허용, 상위(..)는 막고, 숨김 파일·dist/는 목록에서 뺀다 */
class DirFiles(private val root: File) : ProjectFiles {
    init { root.mkdirs() }
    private fun f(p: String) = File(root, Company.cleanPath(p))
    override fun write(path: String, text: String) { f(path).also { it.parentFile?.mkdirs() }.writeText(text) }
    override fun read(path: String): String? = f(path).takeIf { it.isFile }?.readText()
    override fun list(): List<String> = root.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && it.name != "foundry.json" && !it.relativeTo(root).path.startsWith("dist") }
        .map { it.relativeTo(root).path.replace('\\', '/') }.toList()
}
