package com.ht.ensemble

import com.ht.ensemble.command.Category
import com.ht.ensemble.command.CommandSpec
import com.ht.ensemble.command.Effect
import com.ht.ensemble.command.Param

/** 시험용 작은 도구 사전(앱의 150개 명령 대신) */
object TestSpecs {
    private val all = listOf(
        CommandSpec("web.search", "웹 검색", Category.WEB, Effect.READ, listOf(Param("q", "검색어", true)), "웹 검색"),
        CommandSpec("task.artifact", "결과 파일", Category.TASK, Effect.LOCAL, listOf(Param("name", "", true), Param("content", "", true)), "결과 저장"),
        CommandSpec("github.push", "GitHub에 올리기", Category.FILE, Effect.EXTERNAL, listOf(Param("repo", "", true)), "업로드"),
    )
    fun find(id: String) = all.firstOrNull { it.id == id }
}
