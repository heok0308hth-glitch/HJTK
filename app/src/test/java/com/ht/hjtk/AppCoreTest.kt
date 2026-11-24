package com.ht.hjtk

import com.ht.ensemble.agent.Conductor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 앱 쪽 코어(SDK 밖): GitHub 업로드 · Gmail 주소 · 마크다운 완성본 · 입력 라우팅 */
class AppCoreTest {
    @Test fun codeFiles_andUploadIntent() {
        val md = "설명\n\n**index.html**\n```html\n<!doctype html>\n<p>a</p>\n<p>b</p>\n```\n그리고\n```python\n# file: tools/run.py\nprint(1)\nprint(2)\n```\n```text\n출력1\n출력2\n출력3\n```"
        assertEquals(listOf("index.html", "tools/run.py"), com.ht.ensemble.util.CodeFiles.extract(md).map { it.first })
        assertTrue(Conductor.uploadOnly("방금 결과 깃허브에 올려줘"))
        assertFalse(Conductor.uploadOnly("할 일 앱 만들어서 깃허브에 올려줘"))
        assertEquals("my-app", Conductor.repoFromGoal("my-app 저장소로 깃허브에 올려줘"))
        assertEquals("todo-kit", Conductor.repoFromGoal("깃허브에 올려줘. 레포 이름: todo-kit"))
        assertTrue(com.ht.hjtk.core.InputRouter.looksLikeMission("이거 깃허브에 올려줘"))
    }


    @Test fun github_push_newRepo_singleCommit_skipsSecrets() = runBlocking {
        val calls = ArrayList<String>()
        val gh = com.ht.hjtk.core.GitHubPush { m, p, b ->
            calls += "$m $p"
            when {
                m == "GET" && p == "/user" -> 200 to """{"login":"taehyeok"}"""
                m == "GET" && p == "/repos/taehyeok/my-app" -> 404 to """{"message":"Not Found"}"""
                m == "POST" && p == "/user/repos" -> { assertTrue(b!!.getBoolean("private")); assertTrue(b.getBoolean("auto_init")); 201 to """{"default_branch":"main"}""" }
                p.endsWith("/git/ref/heads/main") -> 200 to """{"object":{"sha":"p1"}}"""
                p.endsWith("/git/commits/p1") -> 200 to """{"tree":{"sha":"tr0"}}"""
                p.endsWith("/git/blobs") -> 201 to """{"sha":"b${calls.size}"}"""
                p.endsWith("/git/trees") -> { assertEquals(2, b!!.getJSONArray("tree").length()); 201 to """{"sha":"tr1"}""" }
                p.endsWith("/git/commits") -> 201 to """{"sha":"c0ffee123"}"""
                m == "PATCH" && p.endsWith("/git/refs/heads/main") -> 200 to "{}"
                else -> 500 to "{}"
            }
        }
        val r = gh.push("my app", listOf(com.ht.hjtk.core.GitHubPush.File("index.html", "<h1>hi</h1>".toByteArray()), com.ht.hjtk.core.GitHubPush.File("app/main.py", "print(1)".toByteArray()),
            com.ht.hjtk.core.GitHubPush.File("release.jks", ByteArray(10)), com.ht.hjtk.core.GitHubPush.File(".env", "KEY=1".toByteArray())), "첫 업로드")
        assertEquals("https://github.com/taehyeok/my-app/tree/main", r.url)
        assertTrue(r.created); assertEquals(2, r.files); assertEquals(listOf("release.jks", ".env"), r.skipped)
        assertEquals("커밋은 한 번", 1, calls.count { it == "POST /repos/taehyeok/my-app/git/commits" })
    }


    @Test fun limitDetection_andGmailUrl() {
        assertNotNull(com.ht.hjtk.engine.Scoreboard.limitRest("You've hit your limit · resets in 2 hours", "P0"))
        assertNull(com.ht.hjtk.engine.Scoreboard.limitRest("한도 초과 문제를 다룬 긴 보고서입니다. ".repeat(20), "P0"))
        val u = com.ht.hjtk.engine.GmailWeb.composeUrl("a@b.com", "참가 문의 & 질문", "안녕하세요\n줄바꿈")
        assertTrue(u.startsWith("https://mail.google.com/mail/u/0/?view=cm&fs=1"))
        assertTrue(u.contains("su=%EC%B0%B8%EA%B0%80%20%EB%AC%B8%EC%9D%98%20%26%20"))
        assertFalse(u.contains("+"))
    }



    @Test fun bundle_markdownToHtml() {
        val h = com.ht.hjtk.core.forge.Bundle.toHtml("# 제목\n\n문단 **굵게** `코드` [링크](https://ex.com)\n\n- 하나\n- 둘\n\n| a | b |\n|---|---|\n| 1 | <2> |\n\n```kotlin\nval x = 1 < 2\n```")
        assertTrue(h, h.contains("<h2>제목</h2>")); assertTrue(h.contains("<strong>굵게</strong>")); assertTrue(h.contains("<code>코드</code>"))
        assertTrue(h.contains("<a href=\"https://ex.com\">링크</a>")); assertTrue(h.contains("<ul><li>하나</li><li>둘</li></ul>"))
        assertTrue(h.contains("<td>&lt;2&gt;</td>")); assertTrue(h.contains("val x = 1 &lt; 2"))
        val doc = com.ht.hjtk.core.forge.Bundle.html("청주 카페", "요약", listOf(com.ht.hjtk.core.forge.Bundle.Part("01.md", "# 시장\n본문"), com.ht.hjtk.core.forge.Bundle.Part("02.md", "# 재무\n본문")), "HJTK")
        assertTrue(doc.contains("<nav>")); assertTrue(doc.contains("href=\"#p1\">재무"))
    }

}
