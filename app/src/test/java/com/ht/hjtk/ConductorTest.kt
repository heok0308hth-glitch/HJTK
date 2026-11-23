package com.ht.hjtk

import com.ht.hjtk.core.aegis.ApprovalGate
import com.ht.hjtk.core.agent.*
import com.ht.hjtk.core.ai.AiProfiles
import com.ht.hjtk.core.ai.Provider
import com.ht.hjtk.core.command.CommandCall
import com.ht.hjtk.core.command.CommandCatalog
import com.ht.hjtk.core.command.CommandResult
import kotlinx.coroutines.delay
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
                        text.contains("[센터 검수]") -> """{"pass":true,"fixes":[]}"""
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
                    LlmReply(if (text.contains("[센터 검수]")) "합격입니다" else "${m.name}: 계획 대신 산문으로 답합니다. 비교표: 서울 공공디자인 12-12, 한글 타이포 12-20. 준비는 3주.", "fake", true, 5)
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
}
