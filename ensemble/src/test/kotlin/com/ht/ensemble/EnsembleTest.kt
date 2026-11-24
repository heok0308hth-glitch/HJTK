package com.ht.ensemble

import com.ht.ensemble.agent.AgentEvent
import com.ht.ensemble.agent.JobState
import com.ht.ensemble.ai.AiProfiles
import com.ht.ensemble.ai.Provider
import com.ht.ensemble.ports.Ports
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/** 공개 API: 빌더로 팀을 만들고 → 지휘자/회사 모드를 끝까지 · HTTP 포트는 로컬 가짜 서버로 */
class EnsembleTest {
    private fun fake(name: String) = Ports.fn(name) { p ->
        when {
            p.contains("센터(지휘자)") -> """{"deliverable":"짧은 글","criteria":["완성"],"tasks":[{"id":"t1","title":"조사","who":"","prompt":"조사"},{"id":"t2","title":"초안","who":"","prompt":"쓰기","after":["t1"]}],"final":{"who":"","how":"합치기"}}"""
            p.contains("헌장 규칙") -> """{"title":"짧은 프로젝트","summary":"시험","audience":"개발자","rubric":[{"name":"완성도","desc":"빠짐없이"}],"files":[{"path":"01_본문.md","purpose":"본문"}],"milestones":["하나"],"backlog":[{"id":"T1","title":"본문","file":"01_본문.md","brief":"본문 쓰기"}]}"""
            p.contains("[검수]") -> """{"pass":true}"""
            p.contains("[코드 리뷰 방식 채점]") || p.contains("[최종 QA]") -> """{"scores":{"완성도":9}}"""
            p.contains("[스탠드업]") -> """{"summary":"끝","done":true,"tickets":[]}"""
            p.contains("[센터 보강 판단]") -> """{"more":[]}"""
            else -> "# $name 결과\n\n" + "구체적인 내용과 근거. ".repeat(20)
        }
    }

    @Test fun builder_conduct_runsTeamAndReturnsResult() = runBlocking {
        val events = ArrayList<AgentEvent>()
        val team = Ensemble.team {
            member(Provider.CLAUDE, fake("Claude")); member(Provider.GPT, fake("ChatGPT")); member(Provider.GEMINI, fake("Gemini"))
            onEvent { synchronized(events) { events += it } }
        }
        val r = team.conduct("짧은 글을 써줘")
        assertEquals(r.text, JobState.COMPLETED, r.state); assertTrue(r.ok)
        assertTrue(events.any { it is AgentEvent.Done })
        assertTrue(r.text.contains("결과"))
    }

    @Test fun builder_project_writesFilesToDirAndCheckpoints() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("ensemble").toFile()
        val now = AtomicLong(0)
        val team = Ensemble.team { member(Provider.CLAUDE, fake("Claude")); member(Provider.GPT, fake("ChatGPT")); member(Provider.DEEPSEEK, fake("DeepSeek"), lanes = 2) }
        val r = team.project("짧은 프로젝트", minutes = 30, dir = dir, clock = { now.addAndGet(60_000) })
        assertEquals(r.text, JobState.COMPLETED, r.state)
        assertTrue(java.io.File(dir, "01_본문.md").exists())
        assertTrue(java.io.File(dir, "00_프로젝트_보고서.md").readText().contains("작업 기록"))
        assertTrue(java.io.File(dir, ".company.json").exists())
    }

    @Test fun builder_requiresAMember() {
        try { Ensemble.team { }; fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun httpPorts_openAiCompatible_anthropic_and429() = runBlocking {
        val seen = ArrayList<String>()
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/v1/chat/completions") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8); seen += ex.requestHeaders.getFirst("Authorization") + " " + JSONObject(body).getString("model")
            val out = """{"choices":[{"message":{"role":"assistant","content":"안녕하세요"}}]}""".toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        srv.createContext("/v1/messages") { ex ->
            seen += ex.requestHeaders.getFirst("x-api-key") + " " + ex.requestHeaders.getFirst("anthropic-version")
            val out = """{"content":[{"type":"text","text":"네, "},{"type":"text","text":"Claude예요"}]}""".toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        srv.createContext("/limited/chat/completions") { ex ->
            val out = """{"error":{"message":"Rate limit reached"}}""".toByteArray()
            ex.sendResponseHeaders(429, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        srv.start()
        try {
            val base = "http://127.0.0.1:${srv.address.port}"
            val a = Ports.openAiCompatible("Local", "$base/v1", "sk-test", "my-model").send("hi", "j", 1, true)
            assertEquals("안녕하세요", a.text); assertEquals("Bearer sk-test my-model", seen[0])
            val b = Ports.anthropic("ak-test", baseUrl = base).send("hi", "j", 1, true)
            assertEquals("네, Claude예요", b.text); assertEquals("ak-test 2023-06-01", seen[1])
            val c = Ports.openAiCompatible("Busy", "$base/limited", "k", "m").send("hi", "j", 1, true)
            assertEquals("", c.text); assertTrue(c.via.startsWith("HTTP429")); assertTrue("429는 한도로 인식", AiProfiles.limitHit(c.text, c.via))
            val d = Ports.openAiCompatible("Down", "http://127.0.0.1:1", null, "m").send("hi", "j", 1, true)
            assertTrue(d.via.startsWith("ERR"))
        } finally { srv.stop(0) }
    }
}
