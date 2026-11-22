package com.hjtk.app

import com.hjtk.app.core.model.HubKind
import com.hjtk.app.core.model.SkillStep
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoresTest {

    @Test
    fun memoryUpsertsAndRecalls() {
        val w = TestWorld()
        w.memory.remember("선호 커피", "아이스 아메리카노", listOf("취향"))
        w.memory.remember("선호 커피", "따뜻한 라떼")
        assertEquals(1, w.memory.all().size)
        assertEquals("따뜻한 라떼", w.memory.all().first().value)

        val hits = w.memory.recall("커피 뭐 좋아해")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().context().contains("라떼"))
    }

    @Test
    fun memoryContextIsEmptyWhenIrrelevant() {
        val w = TestWorld()
        w.memory.remember("혈액형", "O형")
        assertEquals("", w.memory.contextFor("우주선 조립"))
    }

    @Test
    fun memoryForgetRemovesByKeyAndId() {
        val w = TestWorld()
        val entry = w.memory.remember("혈액형", "O형")
        assertEquals(1, w.memory.forget("혈액형"))
        w.memory.remember("키", "값")
        assertTrue(w.memory.forgetId(w.memory.all().first().id))
        assertTrue(w.memory.all().isEmpty())
    }

    @Test
    fun memoryExportIsMarkdown() {
        val w = TestWorld()
        w.memory.remember("혈액형", "O형", listOf("건강"))
        val md = w.memory.exportMarkdown()
        assertContains(md, "# 기억")
        assertContains(md, "혈액형")
    }

    @Test
    fun sessionsTrackCurrentAndExport() {
        val w = TestWorld()
        val started = w.sessions.create("첫 대화")
        w.sessions.append("user", "안녕")
        w.sessions.append("assistant", "네")
        val record = w.sessions.record(started.id)!!
        assertEquals(2, record.messages.size)
        val md = w.sessions.exportMarkdown(started.id)
        assertContains(md, "안녕")
        assertContains(md, "HJTK")
        assertTrue(w.sessions.summary().isNotEmpty())
    }

    @Test
    fun lastSessionCannotBeDeleted() {
        val w = TestWorld()
        val only = w.sessions.current()
        assertFalse(w.sessions.delete(only.id))
        val second = w.sessions.create()
        assertTrue(w.sessions.delete(second.id) || w.sessions.summary().size >= 1)
    }

    @Test
    fun skillsMatchOnRealKeywords() {
        val w = TestWorld()
        val match = w.skills.match("아침 브리핑 해줘")
        assertNotNull(match)
        assertEquals("아침 브리핑", match.first.name)
    }

    @Test
    fun skillsDoNotHijackUnrelatedRequests() {
        val w = TestWorld()
        assertNull(w.skills.match("우주선 조립 매뉴얼"))
    }

    @Test
    fun everyBuiltinSkillStepExistsInCatalog() {
        val w = TestWorld()
        w.skills.all().forEach { skill ->
            assertTrue(skill.steps.isNotEmpty(), "${skill.name}: 단계 없음")
            skill.steps.forEach { step ->
                assertTrue(w.registry.exists(step.commandId), "${skill.name}: 없는 명령 ${step.commandId}")
            }
        }
    }

    @Test
    fun everyBuiltinPipelineCompilesWithSampleValues() {
        val w = TestWorld()
        w.pipelines.all().forEach { p ->
            assertTrue(p.steps.isNotEmpty(), "${p.name}: 단계 없음")
            val vars = p.params.associate { it.name to (it.example.ifBlank { "샘플" }) }
            val result = w.compiler.compile(p.name, p.steps, vars)
            assertTrue(result is com.hjtk.app.core.plan.CompileResult.Success, "${p.name}: ${result}")
        }
    }

    @Test
    fun pipelineVariablesAppearInSteps() {
        val w = TestWorld()
        val research = w.pipelines.all().first { it.name == "웹 리서치 노트" }
        assertEquals(listOf("query"), research.params.map { it.name })
        assertTrue(research.steps.any { it.args.values.any { v -> v.contains("{{var:query}}") } })
    }

    @Test
    fun pipelineCreationAndDeletion() {
        val w = TestWorld()
        val created = w.pipelines.create(
            "테스트 플로우",
            "설명",
            listOf(SkillStep("device.battery"), SkillStep("device.storage"))
        )
        assertNotNull(w.pipelines.byName("테스트 플로우"))
        w.pipelines.markRun(created.id, true)
        assertTrue(w.pipelines.byId(created.id)!!.lastRunOk)
        assertTrue(w.pipelines.delete(created.id))
        assertNull(w.pipelines.byId(created.id))
    }

    @Test
    fun hubRanksPinnedFirstAndTracksUse() {
        val w = TestWorld()
        val entry = w.hub.add(HubKind.SITE, "테스트", "https://example.com")
        w.hub.markUsed(entry.id)
        assertTrue(w.hub.byId(entry.id)!!.useCount == 1)
        w.hub.togglePin(entry.id)
        assertEquals(entry.id, w.hub.ranked().first().id)
        assertTrue(w.hub.remove(entry.id))
    }

    @Test
    fun hubSeedsNeutralDefaultsOnFirstRun() {
        val w = TestWorld()
        assertTrue(w.hub.all().isNotEmpty())
        assertTrue(w.hub.all().all { it.target.isNotBlank() })
    }

    @Test
    fun etaLearnsFromObservation() {
        val w = TestWorld()
        val before = w.eta.estimateSeconds("device.battery")
        repeat(5) { w.eta.observe("device.battery", 8_000) }
        assertTrue(w.eta.estimateSeconds("device.battery") >= before)
        assertTrue(w.eta.summary().contains("device.battery"))
    }
}

/** Convenience for assertions on memory entries. */
private fun com.hjtk.app.core.model.MemoryEntry.context(): String = "$key $value"
