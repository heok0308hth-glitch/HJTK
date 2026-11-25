package com.ht.ensemble

import com.ht.ensemble.aegis.ApprovalGate
import com.ht.ensemble.agent.*
import com.ht.ensemble.ai.AiProfiles
import com.ht.ensemble.ai.Provider
import com.ht.ensemble.command.CommandCall
import com.ht.ensemble.command.CommandResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * 3.7.1 회귀 시험: 2026-11-25 '모바일용 블렌더 제작' 실패를 그대로 재현한다.
 * 웹 화면에서 내 말풍선을 답으로 읽음(지시문 회신) · 리뷰어가 빈 답 → 8.0 둔갑 · 답이 중간에 끊김 ·
 * PM 스탠드업을 못 읽어 60분 프로젝트가 21분에 끝남.
 */
class GateReplayTest {
    private val saved1 = "당신\n\n[회사 프로젝트 · 티켓 S3-1] PM Claude가 너에게 배정했다. 이 결과는 다른 회사 AI가 아래 기준으로 채점한다(8점 미만이면 반려). " +
        "채점 기준(각 10점): 정확성 … 파일에 들어갈 완성된 내용만 출력(마크다운, 제목부터). 인사·설명·되묻기 금지."
    private val saved2 = "# 02_분석\n\n" + "근거와 수치. ".repeat(40) + "\n\n반면 AR 미리보기는 추가 엔지니어링이 필요하고, “ 고친 완성본 전체만 출력."
    private val saved3 = "# Ht 3D 제품 기획서\n\n" + "본문 ".repeat(60) + "\n\n| 항목 | 목표 | 근거 |\n|---|---|---|\n| 목표 FPS | 60fps (중급 기기) | 모바일 WebGL 실측에서"

    @Test fun theSavedFilesFromThatRun_allFailTheGate() {
        assertTrue(Gate.check(saved1, "01_리서치.md").echo)
        assertFalse(Gate.check(saved2, "02_분석.md").ok)
        assertTrue(Gate.check(saved3, "03_본문.md").truncated)
        assertTrue(Gate.check("# 리스크\n\n" + "리스크마다 확률·영향·대응 주차를 적었다. ".repeat(8), "04_검토.md").ok)
    }

    private val ctx = AgentContext("RG1", "모바일용 블렌더 제작.", "2026-11-25 15:00")
    private val human = object : HumanPort {
        override suspend fun approve(req: ApprovalRequest) = ApprovalAnswer.Rejected("no")
        override suspend fun ask(jobId: String, question: String, choices: List<String>) = "기본값"
        override suspend fun takeover(jobId: String, what: String, url: String?) = null
    }
    private val tools = object : ToolPort {
        override fun spec(id: String) = TestSpecs.find(id)
        override suspend fun run(call: CommandCall) = CommandResult.ok("ok")
    }
    private val charter = """{"title":"Ht 3D","summary":"폰 우선 3D 툴","audience":"의뢰인",
 "rubric":[{"name":"정확성","desc":"출처"},{"name":"깊이","desc":"수치"}],
 "files":[{"path":"01_리서치.md","purpose":"근거"},{"path":"02_분석.md","purpose":"결정"},{"path":"03_본문.md","purpose":"기획서"}],
 "milestones":["조사","설계"],
 "backlog":[{"id":"T1","title":"자료 조사","file":"01_리서치.md","brief":"근거"},{"id":"T2","title":"분석","file":"02_분석.md","brief":"결정"},{"id":"T3","title":"기획서","file":"03_본문.md","brief":"기획서"}]}"""

    /** Grok은 지시문을 돌려주고, DeepSeek은 빈 답, ChatGPT는 채점을 글로만, PM 스탠드업은 JSON이 아닌 글, 각 파일 첫 답은 끊긴다 */
    private inner class Broken : Crew {
        val now = AtomicLong(0)
        val prompts = java.util.Collections.synchronizedList(ArrayList<String>())
        private val cut = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val team = listOf(CrewMember("C", "Claude", Provider.CLAUDE), CrewMember("G", "ChatGPT", Provider.GPT), CrewMember("M", "Gemini", Provider.GEMINI),
            CrewMember("X", "Grok", Provider.GROK), CrewMember("D", "DeepSeek", Provider.DEEPSEEK))
        override fun members() = team
        override fun available(m: CrewMember) = true
        override fun rate(m: CrewMember, s: AiProfiles.Skill) = m.score(s).toDouble()
        override fun report(m: CrewMember, ok: Boolean, ms: Long, text: String, via: String) {}
        override fun port(m: CrewMember, lane: Int) = object : LlmPort {
            override val name = m.name
            override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply {
                prompts += text
                val start = now.get(); delay(2); now.accumulateAndGet(start + 3 * 60_000) { a, b -> maxOf(a, b) }
                val file = Regex("""(?m)^파일:\s*(\S+)""").find(text)?.groupValues?.get(1) ?: "x.md"
                val work = text.contains("[회사 프로젝트 · 티켓") || text.contains("리뷰 반려")
                val out = when {
                    text.contains("헌장 규칙") -> charter
                    text.contains("[스탠드업]") -> "전반적으로 잘 진행되고 있어요. 다음 스프린트에서 약한 부분을 더 다듬으면 좋겠습니다."
                    m.name == "Grok" && work -> "당신\n\n$text"
                    m.name == "DeepSeek" -> ""
                    text.contains("[코드 리뷰 방식 채점]") && m.name == "ChatGPT" -> "전체적으로 괜찮지만 2절에 출처가 더 필요합니다."
                    text.contains("[코드 리뷰 방식 채점]") -> """{"scores":{"정확성":7.2,"깊이":7.6},"must":["벤치마크 출처"],"issues":[]}"""
                    text.contains("[최종 QA]") -> """{"scores":{"정확성":7.9,"깊이":8.1},"must":[],"issues":[]}"""
                    text.contains("[이어 쓰기]") -> "보수적인 수치다.” 나머지 절도 출처와 함께 이어진다.\n\n끝."
                    text.contains("[프로젝트 보고서]") -> "# 보고서\n\n모든 파일을 설명한 요약."
                    work && cut.add(file) -> "# $file\n\n" + "근거와 수치와 출처. ".repeat(20) + "\n\n벤치마크는 “"
                    work -> "# $file\n\n" + "${m.name}: 근거와 수치와 출처. ".repeat(20)
                    else -> "확인했습니다."
                }
                return LlmReply(out, "fake", true, 2)
            }
        }
    }

    @Test fun company_underThatFailure_savesNoEcho_noFake8_andUsesTheBudget() = runBlocking {
        val crew = Broken()
        val files = MemoryFiles()
        val saved = StringBuilder()
        val events = ArrayList<AgentEvent>()
        val out = Company(ctx, crew, tools, human, ApprovalGate(), JobControl(), { synchronized(events) { events += it } }, clock = { crew.now.get() },
            minutes = 60, files = files, save = { saved.setLength(0); saved.append(it) }).run()
        assertEquals(out.final, JobState.COMPLETED, out.state)
        for (p in files.list()) {
            val text = files.read(p)!!
            assertFalse("$p 에 지시문이 저장됨", Gate.echo(text))
            assertNull("$p 가 끊긴 채 저장됨: ${text.takeLast(60)}", Gate.truncated(text, p))
        }
        assertTrue("끊긴 답은 이어 쓰기로 받았다", crew.prompts.count { it.contains("[이어 쓰기]") && it.contains("벤치마크는 “") } >= 3)
        val tickets = JSONObject(saved.toString()).getJSONArray("tickets")
        for (i in 0 until tickets.length()) {
            val t = tickets.getJSONObject(i)
            if (t.getString("status") == "done") {
                assertNotEquals("지시문을 돌려준 AI는 저장된 파일의 담당이 될 수 없다", "Grok", t.getString("who"))
                assertTrue("저장된 점수는 실제 채점(7.4)이거나 '미채점' 표시 — 8.0 둔갑 없음", t.getBoolean("ungraded") || t.getDouble("score") == 7.4)
            }
        }
        assertTrue("지시문 회신을 잡아 교체했다", events.any { it is AgentEvent.Collab && (it.text.contains("지시문을 그대로") || it.text.contains("지시문을 그대로 돌려줬어요")) })
        assertTrue("PM 답을 못 읽어도 예산을 쓴다(${crew.now.get() / 60_000}분 / 60분)", crew.now.get() >= 40 * 60_000L)
        assertTrue("작업 기록에 이유가 남는다", files.read(Company.REPORT)!!.contains(ECHOED))
    }
}
