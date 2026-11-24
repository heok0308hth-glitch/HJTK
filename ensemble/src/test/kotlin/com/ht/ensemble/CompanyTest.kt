package com.ht.ensemble

import com.ht.ensemble.aegis.ApprovalGate
import com.ht.ensemble.agent.*
import com.ht.ensemble.ai.AiProfiles
import com.ht.ensemble.ai.Provider
import com.ht.ensemble.command.CommandCall
import com.ht.ensemble.command.CommandResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 3.5 회사 모드: 가짜 시계로 '2시간짜리 프로젝트'를 몇 초 만에 끝까지 돌린다.
 * AI 답 하나 = 4분이 흐른 것으로 친다(실제 웹 AI의 긴 답 속도).
 */
class CompanyTest {
    private val ctx = AgentContext("CO1", "청주 지역 카페 창업 사업계획서를 만들어줘", "2026-11-24 16:00")
    private val human = object : HumanPort {
        override suspend fun approve(req: ApprovalRequest) = ApprovalAnswer.Rejected("no")
        override suspend fun ask(jobId: String, question: String, choices: List<String>) = "기본값"
        override suspend fun takeover(jobId: String, what: String, url: String?) = null
    }
    private val tools = object : ToolPort {
        override fun spec(id: String) = TestSpecs.find(id)
        override suspend fun run(call: CommandCall) = CommandResult.ok("ok")
    }

    private val charter = """{"title":"청주 카페 사업계획","summary":"청주 성안길 소형 카페 창업 계획","audience":"예비 창업자·투자자",
 "rubric":[{"name":"정확성","desc":"수치·출처"},{"name":"실행성","desc":"바로 실행 가능한 계획"},{"name":"깊이","desc":"지역 사례"},{"name":"명료성","desc":"구조"}],
 "files":[{"path":"01_시장조사.md","purpose":"상권·경쟁"},{"path":"02_사업모델.md","purpose":"메뉴·가격"},{"path":"03_재무계획.md","purpose":"투자·손익"},{"path":"04_실행계획.md","purpose":"일정"}],
 "milestones":["조사","설계","다듬기"],
 "backlog":[{"id":"T1","title":"상권 조사","file":"01_시장조사.md","brief":"성안길 유동인구·경쟁 카페"},
  {"id":"T2","title":"메뉴·가격","file":"02_사업모델.md","brief":"시그니처 메뉴와 가격대"},
  {"id":"T3","title":"재무 추정","file":"03_재무계획.md","brief":"초기 투자·월 손익","inputs":["01_시장조사.md","02_사업모델.md"],"after":["T1","T2"]},
  {"id":"T4","title":"실행 일정","file":"04_실행계획.md","brief":"12주 일정"}]}"""

    private inner class Firm(val reviewScore: (String) -> Double = { p -> if (p.contains("초안")) 6.5 else 9.0 }, val pmDone: () -> Boolean = { false }, val stepMin: Long = 4, val charterJson: String = charter) : Crew {
        val now = AtomicLong(0)
        val sends = AtomicInteger()
        val authored = ConcurrentHashMap<String, MutableList<String>>()   // 누가 어떤 티켓을 썼나
        val prompts = java.util.Collections.synchronizedList(ArrayList<String>())
        val team = listOf(CrewMember("C", "Claude", Provider.CLAUDE), CrewMember("G", "ChatGPT", Provider.GPT), CrewMember("M", "Gemini", Provider.GEMINI), CrewMember("X", "Grok", Provider.GROK), CrewMember("D", "DeepSeek", Provider.DEEPSEEK, lanes = 2))
        override fun members() = team
        override fun available(m: CrewMember) = true
        override fun rate(m: CrewMember, s: AiProfiles.Skill) = m.score(s).toDouble()
        override fun report(m: CrewMember, ok: Boolean, ms: Long, text: String, via: String) {}
        override fun port(m: CrewMember, lane: Int) = object : LlmPort {
            override val name = m.name
            override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply {
                sends.incrementAndGet(); prompts += text
                // 동시에 보낸 요청은 시간이 겹친다(실제처럼): 시작 시각 + 4분까지 시계를 민다
                val start = now.get(); delay(3); now.accumulateAndGet(start + stepMin * 60_000) { a, b -> maxOf(a, b) }
                val out = when {
                    text.contains("헌장 규칙") -> charterJson
                    text.contains("[스탠드업]") -> if (pmDone()) """{"summary":"충분함","done":true,"tickets":[]}"""
                        else """{"summary":"재무 근거 보강","done":false,"decisions":["임대료는 성안길 평균 사용"],"tickets":[{"id":"A","title":"재무 보강","file":"03_재무계획.md","brief":"민감도 분석 추가","inputs":["01_시장조사.md"]},{"id":"B","title":"경쟁 사례 추가","file":"01_시장조사.md","brief":"경쟁 카페 5곳"},{"id":"C","title":"리스크","file":"05_리스크.md","brief":"리스크와 대응"}],"files":[{"path":"05_리스크.md","purpose":"리스크"}]}"""
                    text.contains("[코드 리뷰 방식 채점]") -> { val s = reviewScore(text); """{"scores":{"정확성":$s,"실행성":$s,"깊이":$s,"명료성":$s},"must":${if (s < 8) "[\"수치 출처를 붙일 것\"]" else "[]"},"issues":[]}""" }
                    text.contains("[최종 QA]") -> """{"scores":{"정확성":8.6,"실행성":8.9,"깊이":8.4,"명료성":9.0},"must":[],"issues":[]}"""
                    text.contains("[프로젝트 보고서]") -> "# 청주 카페 사업계획 — 보고서\n\n요약: 성안길 12평 카페, 초기 투자 7,800만 원, 14개월 회수.\n\n결과물 안내와 결정 기록…"
                    text.contains("리뷰 반려") -> "# 완성본\n\n지적 반영: 출처(소상공인시장진흥공단 상권정보) 포함. " + "구체 수치와 사례. ".repeat(30)
                    else -> {
                        val tid = Regex("""티켓 ([\w-]+)\]""").find(text)?.groupValues?.get(1) ?: "?"
                        authored.getOrPut(m.name) { java.util.Collections.synchronizedList(ArrayList()) }.add(tid)
                        (if (tid == "T1") "# 초안\n\n" else "# ${tid} 결과\n\n") + "${m.name}가 쓴 내용. " + "구체적인 근거와 수치. ".repeat(30) + "\n\n[칠판]\n사실: ${m.name} 확인 사실 $tid\n질문: 없음"
                    }
                }
                return LlmReply(out, "fake", true, 5)
            }
        }
    }

    private fun company(f: Firm, files: ProjectFiles, minutes: Int = 120, control: JobControl = JobControl(), id: String = "CO1",
                        saved: StringBuilder = StringBuilder(), load: String? = null, events: MutableList<AgentEvent> = ArrayList(),
                        deliver: suspend (Company.Delivery) -> List<String> = { emptyList() }, smoke: suspend (String) -> String? = { null }) =
        Company(ctx.copy(jobId = id), f, tools, human, ApprovalGate(), control, { synchronized(events) { events += it } }, clock = { f.now.get() },
            minutes = minutes, files = files, save = { saved.setLength(0); saved.append(it) }, load = { load }, deliver = deliver, smoke = smoke)

    @Test fun twoHourProject_runsSprints_reviewsAndRevises_untilTimeIsSpent() = runBlocking {
        val f = Firm(); val files = MemoryFiles(); val saved = StringBuilder(); val ev = ArrayList<AgentEvent>()
        val out = kotlinx.coroutines.withTimeoutOrNull(60_000) { company(f, files, 120, saved = saved, events = ev).run() }
        assertNotNull("멈춤(교착) 없이 끝나야 한다 · 카드=" + StageBus.stages.value["CO1"]?.cards?.joinToString { "${it.id}:${it.status}" } + " · 마지막 사건=" + ev.takeLast(3), out)
        assertEquals(out!!.final, JobState.COMPLETED, out.state)
        val st = StageBus.stages.value["CO1"]!!
        assertTrue("스프린트를 여러 번 돈다(${st.sprint})", st.sprint >= 3)
        assertTrue("2시간 예산의 대부분을 쓴다(${f.now.get() / 60_000}분)", f.now.get() >= 90 * 60_000L)
        assertTrue("예산을 크게 넘기지 않는다(${f.now.get() / 60_000}분)", f.now.get() <= 150 * 60_000L)
        listOf("01_시장조사.md", "02_사업모델.md", "03_재무계획.md", "04_실행계획.md", "05_리스크.md", Company.REPORT).forEach { assertNotNull("파일 $it", files.read(it)) }
        assertTrue("반려된 초안은 고친 판으로 합쳐진다", files.read("01_시장조사.md")!!.contains("완성본") || !files.read("01_시장조사.md")!!.contains("초안"))
        assertFalse("PM(Claude)은 티켓을 쓰지 않는다: ${f.authored.keys}", f.authored.containsKey("Claude"))
        assertTrue("최소 3명이 티켓을 쓴다: ${f.authored.keys}", f.authored.keys.size >= 3)
        assertTrue("보고서에 실제 작업 기록", files.read(Company.REPORT)!!.contains("작업 기록"))
        assertTrue("체크포인트 저장", saved.contains("\"tickets\""))
        assertTrue("스탠드업 결정이 기록된다", saved.contains("임대료"))
        assertTrue(ev.any { it is AgentEvent.Collab && it.role == "회고" })
        // 리뷰어는 작성자와 다른 회사
        val snap = org.json.JSONObject(saved.toString()).getJSONArray("tickets")
        for (i in 0 until snap.length()) { val t = snap.getJSONObject(i); if (t.getString("status") == "done") assertNotEquals(t.getString("who"), t.getString("reviewer")) }
        StageBus.drop("CO1")
    }

    @Test fun excellentAndDone_finishesEarly() = runBlocking {
        val f = Firm(reviewScore = { 9.6 }, pmDone = { true })
        val files = MemoryFiles()
        val out = company(f, files, 180, id = "CO2").run()
        assertEquals(JobState.COMPLETED, out.state)
        assertTrue("품질 9 이상 + PM 완료 판단이면 일찍 끝낸다(${f.now.get() / 60_000}분)", f.now.get() < 90 * 60_000L)
        StageBus.drop("CO2")
    }

    @Test fun abort_thenResume_continuesWithoutRedoingFinishedTickets() = runBlocking {
        val files = MemoryFiles(); val saved = StringBuilder(); val control = JobControl()
        val f1 = Firm()
        var first: LoopOutcome? = null
        val job = launch(kotlinx.coroutines.Dispatchers.Default) { first = company(f1, files, 120, control = control, id = "CO3", saved = saved).run() }
        while (files.read("04_실행계획.md") == null || files.read("03_재무계획.md") == null) delay(5)
        control.abort(); job.join()
        assertEquals(JobState.ABORTED, first!!.state)
        val doneBefore = org.json.JSONObject(saved.toString()).getJSONArray("tickets").let { a -> (0 until a.length()).count { a.getJSONObject(it).getString("status") == "done" } }
        assertTrue("저장본: ${saved.toString().take(50)} … " + org.json.JSONObject(saved.toString()).getJSONArray("tickets").let { a -> (0 until a.length()).joinToString { a.getJSONObject(it).let { t -> t.getString("id") + "=" + t.getString("status") } } }, doneBefore >= 3)
        // 앱을 다시 켠 것처럼: 새 엔진이 저장본을 읽어 이어 간다
        val f2 = Firm(); f2.now.set(0)
        val out = company(f2, files, 120, id = "CO3", saved = StringBuilder(), load = saved.toString()).run()
        assertEquals(JobState.COMPLETED, out.state)
        assertFalse("헌장을 다시 쓰지 않는다", f2.prompts.any { it.contains("헌장 규칙") })
        val t1 = org.json.JSONObject(saved.toString()).getJSONArray("tickets").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == "T1" } }
        assertFalse("끝난 T1을 다시 쓰지 않는다 · 저장본 T1=${t1.getString("status")} · 이어서 쓴 티켓=${f2.authored}", t1.getString("status") == "done" && f2.authored.values.flatten().contains("T1"))
        StageBus.drop("CO3")
    }

    @Test fun reviewParsing_andPathSafety() {
        val c = company(Firm(), MemoryFiles())
        assertEquals(7.5, c.parseReview("""채점 {"scores":{"a":7,"b":8},"must":["표 추가"]}""")!!.first, 0.01)
        assertEquals(listOf("표 추가"), c.parseReview("""{"scores":{"a":7,"b":8},"must":["표 추가"]}""")!!.second)
        assertEquals(6.0, c.parseReview("총점 6/10 입니다")!!.first, 0.01)
        assertEquals("src/app/main.py", Company.cleanPath("/../src/./app/main.py"))
        assertEquals("a_b.md", Company.cleanPath("a:b.md"))
    }

    @Test fun appProject_runsSmokeTest_fixesErrors_thenDeliversApk() = runBlocking {
        val appCharter = """{"title":"할 일 정원","summary":"물 주듯 할 일을 키우는 앱","audience":"학생",
 "rubric":[{"name":"동작","desc":"오류 없이 동작"},{"name":"완성도","desc":"요구 기능 전부"}],
 "files":[{"path":"01_기획.md","purpose":"기능"},{"path":"app/index.html","purpose":"앱"},{"path":"app/main.js","purpose":"로직"}],
 "milestones":["기획","구현"],
 "backlog":[{"id":"T1","title":"기획","file":"01_기획.md","brief":"기능"},{"id":"T2","title":"화면","file":"app/index.html","brief":"화면"},{"id":"T3","title":"로직","file":"app/main.js","brief":"로직","inputs":["app/index.html"],"after":["T2"]}]}"""
        val f = Firm(reviewScore = { 9.2 }, pmDone = { true }, charterJson = appCharter)
        val files = MemoryFiles()
        val smokes = AtomicInteger(); var delivered: Company.Delivery? = null
        val out = company(f, files, 60, id = "CO4",
            smoke = { if (smokes.getAndIncrement() == 0) "Uncaught ReferenceError: water is not defined (main.js:12)" else null },
            deliver = { d -> delivered = d; listOf("설치 파일(APK) — 12KB", "프로젝트 ZIP — 4KB") }).run()
        assertEquals(JobState.COMPLETED, out.state)
        assertEquals("실행 검사는 고친 뒤 한 번 더", 2, smokes.get())
        assertTrue("오류 난 파일(main.js)을 고치는 티켓", f.prompts.any { it.contains("티켓 FIX1]") && it.contains("파일: app/main.js") && it.contains("water is not defined") })
        assertNotNull(delivered); assertTrue("앱으로 전달", delivered!!.app)
        assertEquals(Company.REPORT, delivered!!.order.first())
        assertTrue("결과물이 최종 답에", out.final.contains("받은 결과물") && out.final.contains("APK"))
        assertTrue("보고서에 AI별 성적", files.read(Company.REPORT)!!.contains("| AI | 끝낸 일 |"))
        assertTrue("앱 규칙이 작성 지시에", f.prompts.any { it.contains("APK로 포장될 웹 앱") })
        StageBus.drop("CO4")
    }
}
