package com.ht.hjtk

import com.ht.hjtk.core.InputRouter
import com.ht.hjtk.core.Journal
import com.ht.hjtk.core.Masker
import com.ht.hjtk.core.Memory
import com.ht.hjtk.core.MemoryIndex
import com.ht.hjtk.core.Pipeline
import com.ht.hjtk.core.PipelineCompiler
import com.ht.hjtk.core.PipelineStep
import com.ht.hjtk.core.Verifiers
import com.ht.ensemble.aegis.ApprovalGate
import com.ht.ensemble.aegis.Canonical
import com.ht.ensemble.aegis.NormalizedAction
import com.ht.hjtk.core.aep.AepParser
import com.ht.hjtk.core.aep.Correlation
import com.ht.hjtk.core.aep.JobId
import com.ht.hjtk.core.aep.ParseLevel
import com.ht.hjtk.core.agent.*
import com.ht.ensemble.agent.*
import com.ht.hjtk.core.command.*
import com.ht.ensemble.command.*
import com.ht.hjtk.core.stream.DeepSeekPatchAssembler
import com.ht.hjtk.core.stream.DeltaExtractor
import com.ht.hjtk.core.stream.SnapshotReconciler
import com.ht.hjtk.core.stream.SseParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CoreTest {
    private val J = "7F3K9Q2M4X" + JobId.checksum("7F3K9Q2M4X")
    private val p = AepParser()

    // ── AEP-1 벡터 V1~V12 ──────────────────────────────────────
    @Test fun v1_exact() {
        val r = p.parse("[[AEP1 j=$J s=1/3 k=act v=1 t=0]]\n좋아요\n<<<AEP1-JSON\n{\"say\":\"hi\",\"done\":false}\nAEP1-JSON>>>", J)
        assertEquals(ParseLevel.EXACT, r.level); assertTrue(r.autoExecutable); assertEquals(Correlation.MATCH, r.correlation); assertEquals("좋아요", r.prose)
    }
    @Test fun v2_smartQuotes() {
        val r = p.parse("[[AEP1 j=$J s=1/3 k=act v=1]]\n<<<AEP1-JSON\n{\u201Csay\u201D:\u201Chi\u201D}\nAEP1-JSON>>>", J)
        assertEquals(ParseLevel.NORMALIZED, r.level); assertEquals("hi", r.payload!!.getString("say"))
    }
    @Test fun v3_fence() {
        val r = p.parse("[[AEP1 j=$J s=1/1 k=act v=1]]\n```json\n{\"say\":\"x\",\"cmds\":[]}\n```", J)
        assertEquals(ParseLevel.BRACE_EXTRACT, r.level); assertTrue(r.autoExecutable)
    }
    @Test fun v4_trailingCommas() {
        val r = p.parse("[[AEP1 j=$J s=1/1 k=act v=1]]\n{\"a\":1,\"b\":[1,2,],}", J)
        assertEquals(ParseLevel.LENIENT_JSON, r.level); assertEquals(1, r.payload!!.getInt("a"))
    }
    @Test fun v5_rawNewlineInString() {
        val r = p.parse("[[AEP1 j=$J s=1/1 k=act v=1]]\n{\"say\":\"줄1\n줄2\"}", J)
        assertEquals(ParseLevel.LENIENT_JSON, r.level); assertEquals("줄1\n줄2", r.payload!!.getString("say"))
    }
    @Test fun v_singleQuotes_appendixA4() {
        val r = p.parse("[[AEP1 j=$J s=2/4 k=result v=1]]\n본문을 추출했습니다.\n\n{\n  'status': 'ok',\n  'output': '본문 3건 추출',\n  'confidence': 0.7,\n}", J)
        assertEquals(ParseLevel.LENIENT_JSON, r.level); assertTrue(r.autoExecutable); assertEquals("ok", r.payload!!.getString("status"))
    }
    @Test fun v6_keyRegexNotExecutable() {
        val r = p.parse("[[AEP1 j=$J s=1/1 k=act v=1]]\n\"say\": \"hello\" ... broken {{{ \"done\": true", J)
        assertTrue(r.level.step >= 4); if (r.level == ParseLevel.KEY_REGEX) assertFalse(r.autoExecutable)
    }
    @Test fun v7_proseOnly() {
        val r = p.parse("[[AEP1 j=$J s=1/1 k=act v=1]]\n그냥 산문입니다.", J)
        assertEquals(ParseLevel.PROSE_ONLY, r.level); assertTrue(r.needsRepair)
    }
    @Test fun v8_noHeader() {
        val r = p.parse("{\"say\":\"x\"}", J)
        assertEquals(Correlation.ABSENT, r.correlation)
    }
    @Test fun v9_mismatch() {
        val other = "AAAAAAAAAA" + JobId.checksum("AAAAAAAAAA")
        val r = p.parse("[[AEP1 j=$other s=1/1 k=act v=1]]\n<<<AEP1-JSON\n{\"say\":\"x\"}\nAEP1-JSON>>>", J)
        assertEquals(Correlation.MISMATCH, r.correlation); assertFalse(r.autoExecutable)
    }
    @Test fun v12_streamingMidway() {
        val r = p.parse("[[AEP1 j=$J s=1/1 k=act v=1]]\n{\"say\":\"작업 중\",\"cmds\":[{\"id\":\"c1\",\"cmd\":\"web.search\",\"args\":{\"q\":\"a\"}}", J)
        assertNotNull(r.payload)
    }
    @Test fun fuzz_neverThrows() {
        val base = "[[AEP1 j=$J s=1/1 k=act v=1]]\n<<<AEP1-JSON\n{\"say\":\"hi\",\"cmds\":[{\"id\":\"c1\",\"cmd\":\"web.search\",\"args\":{\"q\":\"x\"}}]}\nAEP1-JSON>>>"
        val rnd = java.util.Random(7)
        repeat(1000) {
            val sb = StringBuilder(base)
            repeat(sb.length / 20) { val i = rnd.nextInt(sb.length); when (rnd.nextInt(3)) { 0 -> sb.deleteCharAt(i); 1 -> sb.insert(i, "{}\"',\n"[rnd.nextInt(6)]); else -> sb.setCharAt(i, ('a' + rnd.nextInt(26))) } }
            p.parse(sb.toString(), J)
        }
    }
    @Test fun jobId() { val id = JobId.new(); assertTrue(JobId.isValid(id)); assertFalse(JobId.isValid(id.dropLast(1) + if (id.last() == '0') '1' else '0')) }

    // ── 스트림 ───────────────────────────────────────────────
    @Test fun sse_koreanSplitAcrossChunks() {
        val out = ArrayList<String>(); val s = SseParser { out += it }
        val bytes = "data: {\"v\":\"안녕하세요\"}\n\n".toByteArray()
        s.feed(bytes.copyOfRange(0, 13)); s.feed(bytes.copyOfRange(13, bytes.size))
        assertEquals("{\"v\":\"안녕하세요\"}", out.single())
    }
    @Test fun deepseek_patch_fragments() {
        val a = DeepSeekPatchAssembler()
        listOf(
            """{"v":{"response":{"message_id":2,"fragments":[{"type":"THINK","content":"생각"}]}}}""",
            """{"p":"response/fragments/-1/content","o":"APPEND","v":"중"}""",
            """{"p":"response/fragments","o":"APPEND","v":[{"type":"RESPONSE","content":"답"}]}""",
            """{"p":"response/fragments/-1/content","o":"APPEND","v":"변"}""",
            """{"v":"입니다"}""",
            """{"p":"response","o":"BATCH","v":[{"p":"quasi_status","v":"FINISHED"}]}"""
        ).forEach { a.accept(it) }
        assertEquals("답변입니다", a.answer()); assertEquals("생각중", a.thinking()); assertTrue(a.finished)
    }
    @Test fun deepseek_patch_legacy() {
        val a = DeepSeekPatchAssembler()
        listOf("""{"p":"response/thinking_content","v":"음"}""", """{"p":"response/content","v":"Hel"}""", """{"v":"lo"}""", """{"p":"response/status","v":"FINISHED"}""").forEach { a.accept(it) }
        assertEquals("Hello", a.answer()); assertTrue(a.finished)
    }
    @Test fun openai_delta() {
        val f = DeltaExtractor.parse("""{"choices":[{"delta":{"content":"hi"},"finish_reason":null}]}""")
        assertEquals("hi", f.answer); assertFalse(f.done); assertTrue(DeltaExtractor.parse("[DONE]").done)
    }
    @Test fun reconciler_cumulative() {
        val r = SnapshotReconciler(); listOf("안", "안녕", "안녕하", "안녕하세요").forEach { r.accept(it) }
        assertEquals("안녕하세요", r.text())
    }

    // ── 명령어 ───────────────────────────────────────────────
    @Test fun catalog_isLargeAndUnique() {
        val ids = CommandCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("명령어 ${ids.size}개", ids.size >= 140)
        assertEquals("web.search", CommandCatalog.find("/검색")?.id)
    }
    @Test fun pure_calc_and_units() = runBlocking {
        val h = PureHandlers.all { LocalDate.of(2026, 11, 23) }
        assertTrue(h["calc.eval"]!!.run(CommandCall("1", "calc.eval", mapOf("expr" to "(3+4)*2^2 - 10%"))).text.endsWith("= 27.9"))
        assertTrue(h["calc.unit"]!!.run(CommandCall("1", "calc.unit", mapOf("value" to "33", "from" to "평", "to" to "m2"))).text.contains("109.09"))
        assertTrue(h["calc.unit"]!!.run(CommandCall("1", "calc.unit", mapOf("value" to "100", "from" to "c", "to" to "f"))).text.contains("212"))
        assertEquals("D-35 (2026-12-28 월요일)", h["calc.dday"]!!.run(CommandCall("1", "calc.dday", mapOf("date" to "2026-12-28"))).text)
        assertTrue(h["calc.loan"]!!.run(CommandCall("1", "calc.loan", mapOf("principal" to "10000000", "rate" to "6", "months" to "12"))).text.contains("860,664원"))
    }
    @Test fun pure_text() = runBlocking {
        val h = PureHandlers.all()
        assertEquals("a@b.com", h["text.extract"]!!.run(CommandCall("1", "text.extract", mapOf("text" to "메일 a@b.com 로", "what" to "email"))).text)
        assertTrue(h["text.diff"]!!.run(CommandCall("1", "text.diff", mapOf("a" to "x\ny", "b" to "x\nz"))).text.startsWith("변경 2줄"))
        assertEquals("| a | b |\n|---|---|\n| 1 | 2 |", h["text.table"]!!.run(CommandCall("1", "text.table", mapOf("csv" to "a,b\n1,2"))).text)
    }
    @Test fun router() {
        val r = InputRouter.route("/검색 서울 디자인 공모전", emptyList(), null)
        assertTrue(r is InputRouter.Route.Direct); assertEquals("서울 디자인 공모전", (r as InputRouter.Route.Direct).call.args["q"])
        assertTrue(InputRouter.route("공모전 찾아서 준비해줘", emptyList(), null) is InputRouter.Route.Mission)
        assertTrue(InputRouter.route("안녕?", emptyList(), null) is InputRouter.Route.Quick)
        val kv = InputRouter.parseArgs("calc.unit", "value=3 from=km to=m")
        assertEquals("km", kv["from"])
    }

    // ── AEGIS ────────────────────────────────────────────────
    @Test fun gate_singleUse_and_digestBinding() {
        val g = ApprovalGate()
        val a = NormalizedAction("mail.send", mapOf("to" to "a@b.c", "subject" to "s", "body" to "b"))
        val t = g.issue(Canonical.digest(a), 0)
        assertEquals(ApprovalGate.Verdict.Ok, g.verifyAndConsume(t, a, 1))
        assertEquals("E_REPLAY", (g.verifyAndConsume(t, a, 2) as ApprovalGate.Verdict.Bad).code)
        val t2 = g.issue(Canonical.digest(a), 0)
        val tampered = a.copy(params = a.params + ("to" to "evil@x.y"))
        assertEquals("E_DIGEST", (g.verifyAndConsume(t2, tampered, 1) as ApprovalGate.Verdict.Bad).code)
    }
    @Test fun canonical_sortsKeys() {
        assertEquals(Canonical.digest(NormalizedAction("t", mapOf("b" to "1", "a" to "2"))), Canonical.digest(NormalizedAction("t", mapOf("a" to "2", "b" to "1"))))
    }

    // ── 저널·기억·파이프라인·검증·마스킹 ─────────────────────
    @Test fun journal_detectsTamper() {
        val j = Journal(); j.append("A", "1", 1); j.append("B", "2", 2); assertTrue(j.chainValid())
        val lines = j.all.map { org.json.JSONObject().put("seq", it.seq).put("at", it.at).put("type", it.type).put("payload", it.payload).put("digest", it.digest).toString() }.toMutableList()
        lines[0] = lines[0].replace("\"1\"", "\"X\"")
        val k = Journal(); k.load(lines); assertFalse(k.chainValid())
    }
    @Test fun memory_bm25_korean() {
        val items = listOf(Memory("1", "사용자는 무료 공모전만 원한다", "선호", 0), Memory("2", "고양이를 키운다", "생활", 0), Memory("3", "디자인 전공 학생", "프로필", 0))
        assertEquals("1", MemoryIndex.search(items, "공모전 조건", 3, 0).first().first.id)
    }
    @Test fun pipeline_autoInsertsApproval() {
        val p = Pipeline("p", "x", "", listOf(PipelineStep.parseLine("web.search q={input}")!!, PipelineStep.parseLine("mail.send to=a@b.c subject=s body={prev}")!!))
        val c = PipelineCompiler.compile(p)
        assertEquals(1, c.insertedApprovals); assertEquals("approval", c.steps[1].kind)
    }
    @Test fun verifier_catchesSumAndPlaceholder() {
        val r = Verifiers.run("| 항목 | 금액 |\n|---|---|\n| A | 1,000 |\n| B | 2,000 |\n합계 4,000\nTODO 채우기", listOf("마감일 포함"))
        val codes = r.violations.map { it.code }
        assertTrue(codes.contains("SUM_MISMATCH")); assertTrue(codes.contains("PLACEHOLDER")); assertTrue(codes.contains("REQUIREMENT_MISSING"))
    }
    @Test fun masker() {
        assertEquals("key [MASKED_API_KEY]", Masker.mask("key sk-abcdefghijklmnop1234"))
    }

    // ── 에이전트 루프 통합: 승인 거절 → 다른 방법 → 완료 ────
    @Test fun agentLoop_endToEnd() = runBlocking {
        val replies = ArrayDeque(listOf(
            "[[AEP1 j=$J s=1/? k=act v=1]]\n찾아볼게요\n<<<AEP1-JSON\n{\"say\":\"검색\",\"plan\":[{\"step\":\"검색\",\"eta_s\":30,\"status\":\"doing\"},{\"step\":\"정리\",\"eta_s\":30,\"status\":\"todo\"}],\"next\":\"정리\",\"progress\":0.3,\"cmds\":[{\"id\":\"c1\",\"cmd\":\"calc.eval\",\"args\":{\"expr\":\"2+2\"}},{\"id\":\"c2\",\"cmd\":\"mail.send\",\"args\":{\"to\":\"x@y.z\",\"subject\":\"s\",\"body\":\"b\"}}],\"done\":false}\nAEP1-JSON>>>",
            "[[AEP1 j=$J s=2/? k=act v=1]]\n끝\n<<<AEP1-JSON\n{\"say\":\"완료\",\"done\":true,\"final\":\"답은 4\"}\nAEP1-JSON>>>"
        ))
        val sent = ArrayList<String>()
        val llm = object : LlmPort { override val name = "fake"
            override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply { sent += text; return LlmReply(replies.removeFirst(), "fake", true, 10) } }
        val pure = PureHandlers.all()
        val tools = object : ToolPort {
            override fun spec(id: String) = CommandCatalog.find(id)
            override suspend fun run(call: CommandCall) = pure[call.cmd]?.run(call) ?: CommandResult.fail("no")
        }
        val human = object : HumanPort {
            override suspend fun approve(req: ApprovalRequest) = ApprovalAnswer.Rejected("보내지 마")
            override suspend fun ask(jobId: String, question: String, choices: List<String>) = null
            override suspend fun takeover(jobId: String, what: String, url: String?) = null
        }
        val events = ArrayList<AgentEvent>()
        val out = AgentLoop(AgentContext(J, "2+2 계산하고 메일", "now"), llm, tools, human, ApprovalGate(), JobControl(), { events += it }).run()
        assertEquals(JobState.COMPLETED, out.state); assertEquals("답은 4", out.final)
        assertTrue(sent[1].contains("2+2 = 4")); assertTrue(sent[1].contains("사용자가 거절했다"))
        assertTrue(events.any { it is AgentEvent.NeedApproval })
        val rep = ReportComposer().compose(100_000, events, listOf(PlanStep("검색", 30, StepStatus.DONE), PlanStep("정리", 30, StepStatus.TODO)), "정리", 0.5, Eta(60, 90, "x"), JobState.RUNNING, 0)
        assertTrue(rep.headline.contains("실행"))
    }
}
