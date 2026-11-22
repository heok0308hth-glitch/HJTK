package com.hjtk.app

import com.hjtk.app.core.model.PlanState
import com.hjtk.app.core.model.SkillStep
import com.hjtk.app.core.plan.ApprovalGate
import com.hjtk.app.core.plan.CompileResult
import com.hjtk.app.core.plan.PlanEvent
import com.hjtk.app.core.model.ApprovalRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class PlanCompilerTest {

    private val w = TestWorld()

    @Test
    fun compilesSingleStep() {
        val result = w.compiler.compile("배터리", listOf(SkillStep("device.battery")))
        val plan = (result as CompileResult.Success).plan
        assertEquals(1, plan.steps.size)
        assertTrue(plan.estimatedSeconds >= 1)
    }

    @Test
    fun missingRequiredSlotAsksForIt() {
        val result = w.compiler.compile("검색", listOf(SkillStep("search.web")))
        assertTrue(result is CompileResult.NeedsArgs)
        assertTrue((result as CompileResult.NeedsArgs).missing.contains("query"))
    }

    @Test
    fun unknownCommandIsRejected() {
        val result = w.compiler.compile("x", listOf(SkillStep("nope.nothing")))
        assertTrue(result is CompileResult.Invalid)
    }

    @Test
    fun forwardReferenceIsRejected() {
        val result = w.compiler.compile(
            "순환",
            listOf(
                SkillStep("ai.summarize", mapOf("text" to "{{out:2}}")),
                SkillStep("search.web", mapOf("query" to "코틀린"))
            )
        )
        assertTrue(result is CompileResult.Invalid, "실제: $result")
    }

    @Test
    fun variablesAreSubstitutedAtCompileTime() {
        val result = w.compiler.compile(
            "리서치",
            listOf(SkillStep("search.web", mapOf("query" to "{{var:query}}"))),
            mapOf("query" to "안드로이드")
        )
        val plan = (result as CompileResult.Success).plan
        assertEquals("안드로이드", plan.steps.first().args["query"])
    }

    @Test
    fun missingVariableFailsValidation() {
        val result = w.compiler.compile("리서치", listOf(SkillStep("search.web", mapOf("query" to "{{var:query}}"))))
        assertTrue(result is CompileResult.NeedsArgs)
    }

    @Test
    fun referencedDataIsResolvedFromPreviousStep() {
        val plan = (w.compiler.compile(
            "체인",
            listOf(
                SkillStep("search.web", mapOf("query" to "코틀린")),
                SkillStep("ai.summarize", mapOf("text" to "{{out:1}}"))
            )
        ) as CompileResult.Success).plan
        val resolved = w.compiler.resolveArgs(plan.steps[1], mapOf(1 to ("검색 결과 본문" to emptyMap())))
        assertEquals("검색 결과 본문", resolved["text"])
    }

    @Test
    fun executorEmitsEventsAndRunsSteps() = runBlocking {
        val plan = (w.compiler.compile(
            "기억 저장",
            listOf(SkillStep("memory.remember", mapOf("key" to "커피", "value" to "아이스 아메리카노")))
        ) as CompileResult.Success).plan
        val events = mutableListOf<PlanEvent>()
        val gate = object : ApprovalGate {
            override suspend fun confirm(request: ApprovalRequest): Boolean = true
        }
        val outcome = w.executor.run(plan, w.ports, gate) { events.add(it) }
        assertEquals(PlanState.DONE, outcome.state)
        assertEquals(1, w.memory.all().size)
        assertTrue(events.any { it is PlanEvent.Finished })
    }

    @Test
    fun approvalRejectionCancelsWithoutEffect() = runBlocking {
        val plan = (w.compiler.compile(
            "메일 발송",
            listOf(SkillStep("mail.send", mapOf("to" to "a@b.c", "subject" to "안내", "body" to "본문")))
        ) as CompileResult.Success).plan
        val gate = object : ApprovalGate {
            override suspend fun confirm(request: ApprovalRequest): Boolean = false
        }
        val outcome = w.executor.run(plan, w.ports, gate)
        assertEquals(PlanState.CANCELLED, outcome.state)
        assertTrue(w.mailSent.isEmpty(), "승인을 거절했는데 메일이 나갔습니다")
    }

    @Test
    fun approvalAcceptedSendsOnce() = runBlocking {
        val plan = (w.compiler.compile(
            "메일 발송",
            listOf(SkillStep("mail.send", mapOf("to" to "a@b.c", "subject" to "안내", "body" to "본문")))
        ) as CompileResult.Success).plan
        val gate = object : ApprovalGate {
            override suspend fun confirm(request: ApprovalRequest): Boolean = true
        }
        val outcome = w.executor.run(plan, w.ports, gate)
        assertEquals(PlanState.DONE, outcome.state)
        assertEquals(1, w.mailSent.size)
    }

    @Test
    fun failingStepStopsThePlan() = runBlocking {
        w.mailConfigured = false
        val plan = (w.compiler.compile(
            "메일 확인",
            listOf(SkillStep("mail.unread"), SkillStep("device.battery"))
        ) as CompileResult.Success).plan
        val gate = object : ApprovalGate {
            override suspend fun confirm(request: ApprovalRequest): Boolean = true
        }
        val outcome = w.executor.run(plan, w.ports, gate)
        assertEquals(PlanState.FAILED, outcome.state)
        assertEquals(1, outcome.outcomes.size, "실패 이후 단계가 실행되었습니다")
    }

    @Test
    fun journalRecordsEveryTransition() = runBlocking {
        val plan = (w.compiler.compile("기억", listOf(SkillStep("memory.list"))) as CompileResult.Success).plan
        val gate = object : ApprovalGate {
            override suspend fun confirm(request: ApprovalRequest): Boolean = true
        }
        w.executor.run(plan, w.ports, gate)
        val kinds = w.journal.records().map { it.kind }
        assertTrue(kinds.contains("plan_started"))
        assertTrue(kinds.contains("step_started"))
        assertTrue(kinds.contains("step_finished"))
        assertTrue(kinds.contains("plan_finished"))
        assertTrue(w.journal.verify().ok)
    }

    @Test
    fun resumeSkipsCompletedStep() = runBlocking {
        // First half of a two-step plan, checkpointed, then resumed.
        val plan = (w.compiler.compile(
            "두 단계",
            listOf(
                SkillStep("clip.copy", mapOf("text" to "한 번만")),
                SkillStep("device.battery")
            )
        ) as CompileResult.Success).plan
        w.checkpoints.save(plan.id, plan.titleKo, mapOf(plan.steps[0].id to com.hjtk.app.core.plan.StepMemory("복사함")))
        val gate = object : ApprovalGate {
            override suspend fun confirm(request: ApprovalRequest): Boolean = true
        }
        w.clipboard.clear()
        val outcome = w.executor.run(plan, w.ports, gate, resume = true)
        assertEquals(PlanState.DONE, outcome.state)
        assertEquals("", w.clipboard.toString(), "건너뛴 단계가 다시 실행되었습니다")
        assertEquals(1, outcome.outcomes.size)
    }
}

/** Crash recovery: the archive is what makes "이어서 실행" real. */
class ResumeTest {

    @Test
    fun archiveRoundTripsAPlan() {
        val w = TestWorld()
        val plan = (w.compiler.compile(
            "이어서",
            listOf(
                SkillStep("clip.copy", mapOf("text" to "한 번만")),
                SkillStep("device.battery")
            )
        ) as CompileResult.Success).plan
        w.archive.save(plan)
        val loaded = w.archive.load(plan.id)
        assertEquals(plan.steps.size, loaded?.steps?.size)
        assertEquals(plan.steps[0].args["text"], loaded?.steps?.get(0)?.args?.get("text"))
        w.archive.clear(plan.id)
        assertNull(w.archive.load(plan.id))
    }

    @Test
    fun openPlanPlusArchiveResumesWithoutRepeatingTheSideEffect() = runBlocking {
        val w = TestWorld()
        val plan = (w.compiler.compile(
            "재개",
            listOf(
                SkillStep("clip.copy", mapOf("text" to "한 번만")),
                SkillStep("device.battery")
            )
        ) as CompileResult.Success).plan
        // Simulate a process death: journal says the plan opened, step 1 finished.
        w.journal.append("plan_started", plan.id, detail = plan.titleKo)
        w.journal.append("step_started", plan.id, plan.steps[0].id, plan.steps[0].commandId)
        w.journal.append("step_finished", plan.id, plan.steps[0].id, plan.steps[0].commandId)
        w.archive.save(plan)
        w.checkpoints.save(plan.id, plan.titleKo, mapOf(plan.steps[0].id to com.hjtk.app.core.plan.StepMemory("복사함")))

        val open = w.journal.openPlan()
        assertNotNull(open)
        assertEquals(plan.id, open.planId)

        val gate = object : ApprovalGate {
            override suspend fun confirm(request: ApprovalRequest): Boolean = true
        }
        w.clipboard.clear()
        val outcome = w.executor.run(w.archive.load(open.planId)!!, w.ports, gate, resume = true)
        assertEquals(PlanState.DONE, outcome.state)
        assertEquals("", w.clipboard.toString(), "이미 끝난 단계가 다시 실행되었습니다")
    }

    @Test
    fun missingArchiveIsReportedNotGuessed() {
        val w = TestWorld()
        w.journal.append("plan_started", "p-lost", detail = "사라진 계획")
        val open = w.journal.openPlan()
        assertNotNull(open)
        assertNull(w.archive.load(open.planId))
    }
}
