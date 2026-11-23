package com.ht.hjtk

import com.ht.hjtk.core.aegis.ApprovalGate
import com.ht.hjtk.core.agent.*
import com.ht.hjtk.core.ai.AiProfiles
import com.ht.hjtk.core.ai.Provider
import com.ht.hjtk.core.command.CommandCall
import com.ht.hjtk.core.command.CommandCatalog
import com.ht.hjtk.core.command.CommandResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** 3.3 지휘자: 가짜 AI 넷(센터 Claude · 한도에 걸리는 GPT · Gemini · DeepSeek)과 막힌 검색 도구로 끝까지 돌린다 */
class ConductorTest {
    private val ctx = AgentContext("JOBC", "2026 디자인 공모전 비교표와 준비 계획 만들어줘", "2026-11-23 09:00")
    private val human = object : HumanPort {
        override suspend fun approve(req: ApprovalRequest) = ApprovalAnswer.Rejected("no")
        override suspend fun ask(jobId: String, question: String, choices: List<String>) = "기본값으로"
        override suspend fun takeover(jobId: String, what: String, url: String?) = null
    }
    private class Tools : ToolPort {
        val ran = ArrayList<String>()
        override fun spec(id: String) = CommandCatalog.find(id)
        override suspend fun run(call: CommandCall): CommandResult { synchronized(ran) { ran += call.cmd }; return if (call.cmd.startsWith("web.")) CommandResult.fail("검색 차단(403)") else CommandResult.ok("저장") }
    }

    private val plan = """설계입니다.
```json
{"deliverable":"공모전 비교표와 준비 계획","criteria":["마감 12월 안","참가비 확인"],"tasks":[
 {"id":"t1","title":"공모전 조사","who":"Gemini","tool":"web.search","args":{"q":"디자인 공모전 2026 12월 마감"}},
 {"id":"t2","title":"요강 분석 {중요}","who":"GPT","prompt":"요강에서 자격과 제출물을 정리해"},
 {"id":"t3","title":"비교표","who":"DeepSeek","prompt":"t1·t2로 비교표","after":["t1","t2"]}],
 "final":{"who":"Claude","how":"표 → 일정 순"}}
```"""

    private inner class Fake : Crew {
        val live = AtomicInteger(); val peak = AtomicInteger(); val rest = HashSet<String>(); val asked = HashMap<String, Int>()
        val team = listOf(CrewMember("CLAUDE|WEB", "Claude", Provider.CLAUDE), CrewMember("GPT|WEB", "GPT", Provider.GPT), CrewMember("GEMINI|WEB", "Gemini", Provider.GEMINI), CrewMember("DEEPSEEK|WEB", "DeepSeek", Provider.DEEPSEEK, lanes = 2))
        override fun members() = team
        override fun available(m: CrewMember) = synchronized(rest) { m.key !in rest }
        override fun rate(m: CrewMember, s: AiProfiles.Skill) = m.score(s).toDouble()
        override fun report(m: CrewMember, ok: Boolean, ms: Long, text: String, via: String) { if (text.contains("usage limit")) synchronized(rest) { rest += m.key } }
        override fun port(m: CrewMember, lane: Int) = object : LlmPort {
            override val name = m.name
            override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply {
                synchronized(asked) { asked[m.name] = (asked[m.name] ?: 0) + 1 }
                val n = live.incrementAndGet(); peak.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                try {
                    delay(120)
                    val out = when {
                        m.name == "GPT" -> "You've reached your usage limit. Try again in 3 hours."
                        text.contains("센터(지휘자)") -> plan
                        text.contains("[검수]") -> """{"pass":true,"fixes":[]}"""
                        text.contains("[최종 통합]") -> "# 공모전 비교표와 준비 계획\n\n| 공모전 | 마감 |\n|---|---|\n| 서울 공공디자인 | 12-12 |\n\n준비 일정: 1주차 리서치 → 2주차 시안 → 3주차 제출."
                        else -> "${m.name}의 결과: 서울 공공디자인 공모전(마감 12-12, 참가비 없음), 한글 타이포 공모전(마감 12-20). 출처 https://ex.com/a"
                    }
                    return LlmReply(out, "fake", true, 5)
                } finally { live.decrementAndGet() }
            }
        }
    }

    @Test fun centerPlans_parallel_swapsOnLimit_fallsBackFromBlockedSearch() = runBlocking {
        val crew = Fake(); val tools = Tools(); val ev = ArrayList<AgentEvent>()
        val out = Conductor(ctx, crew, tools, human, ApprovalGate(), JobControl(), { synchronized(ev) { ev += it } }, catalog = "web.search — 웹 검색").run()
        assertEquals(JobState.COMPLETED, out.state)
        assertTrue(out.final.contains("공모전 비교표"))
        assertTrue("GPT가 한도에 걸리면 쉬게 해야 한다", "GPT|WEB" in crew.rest)
        val st = StageBus.stages.value["JOBC"]!!
        assertNotEquals("한도 걸린 일은 다른 AI가 맡는다", "GPT", st.cards.first { it.id == "t2" }.who)
        assertEquals(StageBus.S.DONE, st.cards.first { it.id == "t1" }.status)   // 검색 막힘 → AI가 직접 조사해 완료
        assertTrue("t1·t2는 동시에 돈다(최대 동시 ${crew.peak.get()})", crew.peak.get() >= 2)
        assertEquals("센터는 계획·검수에 강한 Claude", "Claude", st.center)
        assertTrue(tools.ran.contains("task.artifact"))
        StageBus.drop("JOBC")
    }

    @Test fun unreadablePlan_usesTemplate_stillFinishes() = runBlocking {
        val crew = object : Crew by Fake() {
            override fun port(m: CrewMember, lane: Int) = object : LlmPort {
                override val name = m.name
                override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean) =
                    LlmReply(if (text.contains("[검수]")) "합격입니다" else "${m.name}: 계획 대신 산문으로 답합니다. 비교표: 서울 공공디자인 12-12, 한글 타이포 12-20. 준비는 3주.", "fake", true, 5)
            }
        }
        val out = Conductor(ctx.copy(jobId = "JOBT"), crew, Tools(), human, ApprovalGate(), JobControl(), {}).run()
        assertEquals(JobState.COMPLETED, out.state)
        assertTrue(StageBus.stages.value["JOBT"]!!.cards.size >= 2)
        StageBus.drop("JOBT")
    }

    @Test fun jsonPick_handlesBracesInStrings_andFences() {
        val o = JsonPick.obj("앞말 {잡음 ```json\n{\"a\":\"x } y {\",\"b\":[1,2]}\n``` 뒷말")
        assertNotNull(o); assertEquals("x } y {", o!!.getString("a"))
    }

    @Test fun limitDetection_andGmailUrl() {
        assertNotNull(com.ht.hjtk.engine.Scoreboard.limitRest("You've hit your limit · resets in 2 hours", "P0"))
        assertNull(com.ht.hjtk.engine.Scoreboard.limitRest("한도 초과 문제를 다룬 긴 보고서입니다. ".repeat(20), "P0"))
        val u = com.ht.hjtk.engine.GmailWeb.composeUrl("a@b.com", "참가 문의 & 질문", "안녕하세요\n줄바꿈")
        assertTrue(u.startsWith("https://mail.google.com/mail/u/0/?view=cm&fs=1"))
        assertTrue(u.contains("su=%EC%B0%B8%EA%B0%80%20%EB%AC%B8%EC%9D%98%20%26%20"))
        assertFalse(u.contains("+"))
    }

    // ───────── 3.4 합주 ─────────
    /** 지휘자가 전부 Claude에게 몰아도, 배정 시장이 고르게 나누고 센터는 할 일을 맡지 않는다 */
    private inner class Team5(val delayMs: Long = 60, val onSend: suspend (CrewMember, String) -> String? = { _, _ -> null }) : Crew {
        val asked = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
        val cancelled = AtomicInteger(); val sends = AtomicInteger()
        val team = listOf(CrewMember("C", "Claude", Provider.CLAUDE), CrewMember("G", "ChatGPT", Provider.GPT), CrewMember("M", "Gemini", Provider.GEMINI), CrewMember("X", "Grok", Provider.GROK), CrewMember("D", "DeepSeek", Provider.DEEPSEEK, lanes = 2))
        override fun members() = team
        override fun available(m: CrewMember) = true
        override fun rate(m: CrewMember, s: AiProfiles.Skill) = m.score(s).toDouble()
        override fun report(m: CrewMember, ok: Boolean, ms: Long, text: String, via: String) {}
        override fun port(m: CrewMember, lane: Int) = object : LlmPort {
            override val name = m.name
            override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply {
                sends.incrementAndGet(); asked.getOrPut(m.name) { java.util.Collections.synchronizedList(ArrayList()) }.add(text)
                try { delay(delayMs) } catch (e: kotlinx.coroutines.CancellationException) { cancelled.incrementAndGet(); throw e }
                onSend(m, text)?.let { return LlmReply(it, "fake", true, 5) }
                val out = when {
                    text.contains("센터(지휘자)") -> allToClaude
                    text.contains("[검수]") -> """{"pass":true}"""
                    text.contains("[센터 보강 판단]") -> """{"more":[]}"""
                    text.contains("[교차 확인]") -> "이상 없음"
                    text.contains("[최종 통합]") -> "# 결과\n\n" + "팀이 합친 본문입니다. ".repeat(20)
                    else -> "${m.name} 결과: " + "구체적인 내용입니다. ".repeat(15) + "\n\n[칠판]\n사실: ${m.name}가 확인한 사실 하나\n질문: 없음"
                }
                return LlmReply(out, "fake", true, 5)
            }
        }
    }
    private val allToClaude = """{"deliverable":"보고서","criteria":["완성"],"tasks":[
 {"id":"t1","title":"자료 조사","who":"Claude","prompt":"조사"},{"id":"t2","title":"관점 정리","who":"Claude","prompt":"정리"},
 {"id":"t3","title":"사례 분석","who":"Claude","prompt":"분석"},{"id":"t4","title":"본문 작성","who":"Claude","prompt":"작성","after":["t1","t2","t3"]}],"final":{"who":"Claude","how":"합치기"}}"""

    @Test fun dispatch_spreadsWork_centerOnlyConducts_boardFlows() = runBlocking {
        val crew = Team5()
        val out = Conductor(ctx.copy(jobId = "JOBF"), crew, Tools(), human, ApprovalGate(), JobControl(), {}).run()
        assertEquals(JobState.COMPLETED, out.state)
        val st = StageBus.stages.value["JOBF"]!!
        val doers = st.cards.filter { it.id.startsWith("t") }.map { it.who }
        assertFalse("센터(Claude)는 할 일을 맡지 않는다: $doers", "Claude" in doers)
        assertTrue("최소 3명이 나눠 일한다: $doers", doers.distinct().size >= 3)
        assertTrue("칠판에 메모가 쌓인다", st.board.isNotEmpty())
        val t4 = crew.asked.values.flatten().first { it.contains("네 할 일: 본문 작성") }
        assertTrue("뒤 팀원은 칠판을 읽고 일한다", t4.contains("팀 칠판"))
        assertFalse("칠판 블록은 본문에서 떼어 낸다", out.final.contains("[칠판]"))
        StageBus.drop("JOBF")
    }

    @Test fun pause_sendsNothingNew_untilResume() = runBlocking {
        val control = JobControl()
        val crew = Team5(delayMs = 150)
        var duringPause = -1
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            Conductor(ctx.copy(jobId = "JOBP"), crew, Tools(), human, ApprovalGate(), control, {}).run()
        }
        while (crew.asked.values.flatten().none { it.contains("[팀 작업") }) delay(10)
        control.pause()
        delay(250)                                   // 진행 중이던 답은 끝나게 둔다
        val atPause = crew.sends.get()
        delay(600)
        duringPause = crew.sends.get() - atPause
        control.resume()
        job.join()
        assertEquals("일시정지 동안 새 요청 0", 0, duringPause)
        assertEquals(JobState.COMPLETED.name, StageBus.stages.value["JOBP"]!!.phase.let { if (it == "완료") "COMPLETED" else it })
        StageBus.drop("JOBP")
    }

    @Test fun abort_cancelsInFlight_andStopsImmediately() = runBlocking {
        val control = JobControl()
        val crew = Team5(delayMs = 5_000) { _, t -> if (t.contains("센터(지휘자)")) allToClaude else null }
        val fast = Team5(delayMs = 30)
        val c = object : Crew by fast { override fun port(m: CrewMember, lane: Int): LlmPort = if (crew.sends.get() == 0 && fast.sends.get() == 0) fast.port(m, lane) else crew.port(m, lane) }
        val t0 = System.currentTimeMillis()
        var outcome: LoopOutcome? = null
        val job = launch(kotlinx.coroutines.Dispatchers.Default) { outcome = Conductor(ctx.copy(jobId = "JOBA"), c, Tools(), human, ApprovalGate(), control, {}).run() }
        while (crew.sends.get() < 2) delay(10)        // 팀원 둘 이상이 일하는 중
        val before = crew.sends.get()
        control.abort()
        job.join()
        assertEquals(JobState.ABORTED, outcome!!.state)
        assertTrue("중지는 즉시(${System.currentTimeMillis() - t0}ms)", System.currentTimeMillis() - t0 < 3_000)
        assertTrue("진행 중 요청이 취소된다", crew.cancelled.get() >= 2)
        assertEquals("중지 뒤 새 요청 없음", before, crew.sends.get())
        StageBus.drop("JOBA")
    }

    @Test fun board_split_andDigest() {
        val (body, notes) = Board.split("본문입니다.\n\n[칠판]\n사실: 마감은 12월 12일(공고문)\n결정: 포스터 부문\n질문: 없음")
        assertEquals("본문입니다.", body)
        assertEquals(listOf("사실", "결정"), notes.map { it.first })
        val b = Board(); b.add("Gemini", "사실", "마감 12/12"); b.add("ChatGPT", "주의", "상금 오기"); b.add("Gemini", "사실", "마감 12/12")
        assertEquals(2, b.all().size)
        assertTrue(b.digest().lineSequence().first().contains("주의"))
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

    @Test fun codeFiles_andUploadIntent() {
        val md = "설명\n\n**index.html**\n```html\n<!doctype html>\n<p>a</p>\n<p>b</p>\n```\n그리고\n```python\n# file: tools/run.py\nprint(1)\nprint(2)\n```\n```text\n출력1\n출력2\n출력3\n```"
        assertEquals(listOf("index.html", "tools/run.py"), com.ht.hjtk.core.CodeFiles.extract(md).map { it.first })
        assertTrue(Conductor.uploadOnly("방금 결과 깃허브에 올려줘"))
        assertFalse(Conductor.uploadOnly("할 일 앱 만들어서 깃허브에 올려줘"))
        assertEquals("my-app", Conductor.repoFromGoal("my-app 저장소로 깃허브에 올려줘"))
        assertEquals("todo-kit", Conductor.repoFromGoal("깃허브에 올려줘. 레포 이름: todo-kit"))
        assertTrue(com.ht.hjtk.core.InputRouter.looksLikeMission("이거 깃허브에 올려줘"))
    }
}
