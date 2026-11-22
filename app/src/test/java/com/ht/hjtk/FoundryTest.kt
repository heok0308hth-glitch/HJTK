package com.ht.hjtk

import com.ht.hjtk.core.aegis.ApprovalGate
import com.ht.hjtk.core.agent.*
import com.ht.hjtk.core.command.CommandCall
import com.ht.hjtk.core.command.CommandCatalog
import com.ht.hjtk.core.command.CommandResult
import com.ht.hjtk.core.command.PureHandlers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 작업 공정 — 가짜 AI 두 명(작가·검토자)과 가짜 도구로 끝까지 돌려 본다 */
class FoundryTest {
    private val ctx = AgentContext("JOB1", "2026 디자인 공모전 찾아서 준비 계획 세워줘", "2026-11-23 09:00")

    private class ScriptLlm(override val name: String, private val reply: (String) -> String) : LlmPort {
        val prompts = ArrayList<String>()
        override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply { prompts += text; return LlmReply(reply(text), "fake", true, 5) }
    }

    private class Tools(private val failSearch: Boolean = false) : ToolPort {
        val ran = ArrayList<String>()
        val pure = PureHandlers.all()
        override fun spec(id: String) = CommandCatalog.find(id)
        override suspend fun run(call: CommandCall): CommandResult {
            ran += call.cmd
            return when (call.cmd) {
                "contest.search" -> if (failSearch) CommandResult.fail("차단됨") else CommandResult.ok("1. 서울 공공디자인 공모전 https://ex.com/a 마감 12-12\n2. 한글 타이포 https://ex.com/b 마감 12-20")
                "web.search", "web.naver" -> CommandResult.ok("결과 https://ex.com/c")
                "web.multi" -> CommandResult.ok("요강: 참가비 없음, 제출물 A2 포스터 (출처 ${call.args["urls"]})")
                "task.artifact" -> CommandResult.ok("저장")
                else -> pure[call.cmd]?.run(call) ?: CommandResult.ok("ok")
            }
        }
    }

    private val human = object : HumanPort {
        override suspend fun approve(req: ApprovalRequest) = ApprovalAnswer.Rejected("no")
        override suspend fun ask(jobId: String, question: String, choices: List<String>) = "기본값으로"
        override suspend fun takeover(jobId: String, what: String, url: String?) = null
    }

    private val plan = """좋아요.
```json
{"deliverable":"공모전 비교표와 준비 계획","criteria":["마감이 12월 안","참가비 없음 확인","제출물 목록"],"ask":null,"ideas":[
 {"name":"A 정석","why":"포털 검색 후 요강 정독","steps":[{"do":"cmd","title":"공모전 찾기","cmd":"contest.search","args":{"q":"디자인"}},{"do":"cmd","title":"요강 읽기","cmd":"web.multi","args":{"urls":"{links}"}},{"do":"ai","title":"비교표와 계획","prompt":"표로"}]},
 {"name":"B 결합","why":"채용 공고식 체크리스트","steps":[{"do":"cmd","title":"없는 명령","cmd":"magic.do","args":{}},{"do":"ai","title":"작성","prompt":"써"}]},
 {"name":"C 전복","why":"마감 역산","steps":[{"do":"cmd","title":"검색","cmd":"web.search","args":{"q":"공모전 마감"}},{"do":"ai","title":"역산 계획","prompt":"써"}]}]}
```"""

    private fun run(writer: LlmPort, critic: LlmPort?, tools: ToolPort, control: JobControl = JobControl()): Pair<LoopOutcome, List<AgentEvent>> = runBlocking {
        val ev = ArrayList<AgentEvent>()
        val cp = arrayOf("")
        val out = Foundry(ctx, writer, critic, tools, human, ApprovalGate(), control, { ev += it }, checkpoint = { cp[0] = it }).run()
        out to ev
    }

    @Test fun happyPath_criticPicksAndApproves() {
        val writer = ScriptLlm("DeepSeek") { p -> when { p.contains("작업 설계자") -> plan; p.contains("[완성할 결과물]") -> "| 공모전 | 마감 |\n|---|---|\n| 서울 공공디자인 | 12-12 |\n참가비 없음, 제출물 A2 포스터. 출처 https://ex.com/a"; else -> "중간 결과" } }
        val critic = ScriptLlm("Gemini") { p -> if (p.contains("계획들을")) """```json
{"scores":[8,2,6],"pick":0,"why":"요강까지 읽음","improve":"제출 일정 역산표 추가"}
```""" else """{"pass":true,"score":8.5,"issues":[]}""" }
        val tools = Tools()
        val (out, ev) = run(writer, critic, tools)
        assertEquals(JobState.COMPLETED, out.state)
        assertTrue(out.final.contains("서울 공공디자인"))
        assertTrue(out.final.contains("협업"))
        assertEquals(listOf("contest.search", "web.multi", "task.artifact"), tools.ran)
        // {links}가 앞 단계의 URL로 채워졌다
        assertTrue(writer.prompts.any { it.contains("https://ex.com/a") })
        // 검토자의 보완 요청이 최종 작성 프롬프트에 들어갔다
        assertTrue(writer.prompts.last().contains("제출 일정 역산표"))
        assertTrue(ev.filterIsInstance<AgentEvent.Collab>().map { it.role }.containsAll(listOf("구상", "선택", "검증")))
    }

    @Test fun unreadablePlan_fallsBackToBuiltInPipeline() {
        val writer = ScriptLlm("DeepSeek") { p -> if (p.contains("작업 설계자") || p.contains("JSON을 읽지 못했다")) "음… 그냥 열심히 해볼게요!" else "공모전 표 https://ex.com/a" }
        val tools = Tools()
        val (out, ev) = run(writer, null, tools)
        assertEquals(JobState.COMPLETED, out.state)
        assertTrue(tools.ran.contains("contest.search"))   // 내장 '공모전' 공정
        assertTrue(ev.any { it is AgentEvent.Collab && it.text.contains("내장 공정표") })
    }

    @Test fun failedCommand_usesFallbackSource() {
        val writer = ScriptLlm("DeepSeek") { p -> if (p.contains("작업 설계자")) plan else "결과 https://ex.com/c" }
        val tools = Tools(failSearch = true)
        val (out, _) = run(writer, ScriptLlm("Gemini") { p -> if (p.contains("계획들을")) """{"scores":[9,1,5],"pick":0}""" else """{"pass":true,"score":9,"issues":[]}""" }, tools)
        assertEquals(JobState.COMPLETED, out.state)
        assertTrue(tools.ran.indexOf("web.search") > tools.ran.indexOf("contest.search"))
    }

    @Test fun criticRejects_writerRevises() {
        var reviews = 0
        val writer = ScriptLlm("DeepSeek") { p -> when { p.contains("작업 설계자") -> plan; p.contains("검토자가 아래 문제") -> "고친 결과물: 참가비 없음 확인 완료, 제출물 A2 포스터, 마감 12-12 https://ex.com/a"; else -> "초안" } }
        val critic = ScriptLlm("Gemini") { p -> if (p.contains("계획들을")) """{"scores":[9,1,5],"pick":0}""" else { reviews++; if (reviews == 1) """{"pass":false,"score":4,"issues":["참가비 확인이 없다"]}""" else """{"pass":true,"score":9,"issues":[]}""" } }
        val (out, ev) = run(writer, critic, Tools())
        assertEquals(JobState.COMPLETED, out.state)
        assertTrue(out.final.startsWith("고친 결과물"))
        assertTrue(ev.any { it is AgentEvent.Collab && it.role == "수정" })
    }

    @Test fun silentCritic_writerSelfReviews() {
        val writer = ScriptLlm("DeepSeek") { p -> when { p.contains("작업 설계자") -> plan; p.contains("역할 전환") && p.contains("계획들을") -> """{"scores":[7,2,5],"pick":0}"""; p.contains("역할 전환") -> """{"pass":true,"score":8,"issues":[]}"""; else -> "결과 https://ex.com/a" } }
        val critic = ScriptLlm("Gemini 앱") { "" }
        val (out, ev) = run(writer, critic, Tools())
        assertEquals(JobState.COMPLETED, out.state)
        assertTrue(ev.any { it is AgentEvent.Note && it.text.contains("스스로 검토") })
    }

    @Test fun abortStopsBetweenSteps() {
        val c = JobControl()
        val writer = ScriptLlm("DeepSeek") { p -> if (p.contains("작업 설계자")) { c.abort(); plan } else "x" }
        val (out, _) = run(writer, null, Tools(), c)
        assertEquals(JobState.ABORTED, out.state)
    }

    @Test fun mechanicalCheckDropsUnknownCommands() {
        val idea = Foundry.Idea("B", "", listOf(Foundry.Step("cmd", "x", "magic.do"), Foundry.Step("ai", "작성")))
        val checked = FoundryCheck.check(idea, Tools(), "공모전 찾아줘")
        assertTrue(checked.steps.none { it.cmd == "magic.do" })
        assertEquals("web.search", checked.steps.first().cmd)   // 근거 수집 단계를 앞에 넣었다
        assertTrue(checked.mech < 10)
    }
}
