package com.ht.hjtk.core.command

import com.ht.ensemble.agent.*
import com.ht.ensemble.command.*

/** 계정 연동 명령의 사전 항목(실행은 engine/ConnectorHub). 사전에 있어야 공정 설계자가 쓸 수 있다. */
object ConnectorCommands {
    private fun spec(id: String, title: String, e: Effect, params: String, desc: String, alias: String) = CommandSpec(id, title, if (id.startsWith("site")) Category.WEB else Category.HUB, e,
        params.takeIf { it.isNotBlank() }?.split('|')?.map { p -> Param(p.substringBefore(':').trimEnd('*'), p.substringAfter(':', ""), p.substringBefore(':').endsWith("*")) } ?: emptyList(), desc, listOf(alias))

    val specs: List<CommandSpec> = listOf(
        spec("github.repos", "깃허브 저장소", Effect.READ, "account:계정 이름", "내 저장소 목록(최근 수정순)", "깃허브"),
        spec("github.issues", "깃허브 이슈", Effect.READ, "repo*:owner/name|state:open/closed|account:계정", "이슈 목록", "이슈"),
        spec("github.issue_create", "이슈 만들기", Effect.EXTERNAL, "repo*:owner/name|title*:제목|body:내용|account:계정", "저장소에 이슈를 연다", "이슈만들기"),
        spec("github.file", "깃허브 파일 읽기", Effect.READ, "repo*:owner/name|path*:경로|ref:브랜치|account:계정", "파일 내용을 읽는다", "깃허브파일"),
        spec("github.commit_file", "깃허브 파일 커밋", Effect.EXTERNAL, "repo*:owner/name|path*:경로|content*:내용|message:커밋 메시지|branch:브랜치|account:계정", "파일을 만들거나 고쳐 커밋", "커밋"),
        spec("github.search", "깃허브 저장소 검색", Effect.READ, "q*:검색어|account:계정", "저장소 검색", "깃허브검색"),
        spec("github.notifications", "깃허브 알림", Effect.READ, "account:계정", "읽지 않은 알림", "깃허브알림"),
        spec("notion.search", "노션 검색", Effect.READ, "q*:검색어|account:계정", "연결된 페이지·DB 검색", "노션"),
        spec("notion.read", "노션 페이지 읽기", Effect.READ, "id*:페이지 ID 또는 링크|account:계정", "페이지 본문을 글로", "노션읽기"),
        spec("notion.append", "노션에 쓰기", Effect.EXTERNAL, "id*:페이지 ID 또는 링크|text*:내용|account:계정", "페이지 끝에 문단 추가", "노션쓰기"),
        spec("site.read", "로그인한 사이트 읽기", Effect.READ, "url*:주소", "로그인 브라우저의 세션으로 페이지 글을 읽는다(내 카페·내 메일함 웹 등)", "사이트읽기"),
        spec("account.list", "연결된 계정", Effect.READ, "", "AI·메일·깃허브·노션·로그인 사이트 목록", "계정")
    )
}
