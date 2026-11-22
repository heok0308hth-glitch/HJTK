package com.hjtk.app

import com.hjtk.app.core.model.PlanState
import com.hjtk.app.core.model.SkillStep
import com.hjtk.app.core.orchestrator.Orchestrator
import com.hjtk.app.core.plan.ResourceGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Concurrency, per-job cancellation and the resource guard. */
class OrchestratorTest {

    @Test
    fun threeIndependentPlansRunAtTheSameTime() {
        val w = TestWorld()
        val concurrent = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        val slow = object : com.hjtk.app.core.FilePort {
            override suspend fun saveExport(name: String, content: String, mime: String): String {
                val now = concurrent.incrementAndGet()
                maxSeen.updateAndGet { maxOf(it, now) }
                delay(250)
                concurrent.decrementAndGet()
                return name
            }
            override suspend fun listExports() = emptyList<com.hjtk.app.core.ExportFile>()
            override suspend fun openExportsFolder() = Unit
            override suspend fun shareExport(name: String) = Unit
        }
        val ports = com.hjtk.app.core.HostPorts(
            clock = w.clock, files = slow, web = w.web, mail = w.mail, calendar = w.calendar,
            apps = w.apps, clipboard = w.clips, notify = w.notify, device = w.device, ai = w.ai,
            diag = w.diag, memory = w.memory, skills = w.skills, pipelines = w.pipelines,
            hub = w.hub, sessions = w.sessions, plans = w.orchestrator
        )
        w.orchestrator.attachPorts(ports)

        repeat(3) { i ->
            w.orchestrator.runExplicit("file.export_text", mapOf("content" to "본문$i", "name" to "f$i.txt"))
        }
        awaitUntil(15_000, "세 작업이 동시에 실행되지 않았습니다") { maxSeen.get() >= 2 }
        awaitUntil(15_000, "작업이 끝나지 않았습니다") { w.orchestrator.state.value.jobs.isEmpty() }
        assertTrue(maxSeen.get() >= 2, "동시 실행 최대치: ${maxSeen.get()}")
    }

    @Test
    fun fourthJobWaitsInQueue() = runBlocking {
        val w = TestWorld()
        val releasedFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val started = AtomicInteger(0)
        val blocking = object : com.hjtk.app.core.FilePort {
            override suspend fun saveExport(name: String, content: String, mime: String): String {
                started.incrementAndGet()
                while (!releasedFlag.get()) delay(15)
                return name
            }
            override suspend fun listExports() = emptyList<com.hjtk.app.core.ExportFile>()
            override suspend fun openExportsFolder() = Unit
            override suspend fun shareExport(name: String) = Unit
        }
        val ports = com.hjtk.app.core.HostPorts(
            clock = w.clock, files = blocking, web = w.web, mail = w.mail, calendar = w.calendar,
            apps = w.apps, clipboard = w.clips, notify = w.notify, device = w.device, ai = w.ai,
            diag = w.diag, memory = w.memory, skills = w.skills, pipelines = w.pipelines,
            hub = w.hub, sessions = w.sessions, plans = w.orchestrator
        )
        w.orchestrator.attachPorts(ports)
        repeat(4) { i ->
            w.orchestrator.runExplicit("file.export_text", mapOf("content" to "본문$i", "name" to "q$i.txt"))
        }
        awaitUntil(15_000, "3개가 시작되지 않았습니다") { started.get() == 3 }
        assertEquals(1, w.orchestrator.state.value.jobs.count { it.queued }, "4번째 작업이 대기하지 않았습니다")
        releasedFlag.set(true)
        awaitUntil(15_000, "대기 작업이 시작되지 않았습니다") { started.get() >= 4 }
        awaitUntil(15_000, "작업이 정리되지 않았습니다") { w.orchestrator.state.value.jobs.isEmpty() }
    }

    @Test
    fun stoppingOneJobLeavesTheOthersRunning() = runBlocking {
        val w = TestWorld()
        val slow = object : com.hjtk.app.core.FilePort {
            override suspend fun saveExport(name: String, content: String, mime: String): String {
                delay(4_000)
                return name
            }
            override suspend fun listExports() = emptyList<com.hjtk.app.core.ExportFile>()
            override suspend fun openExportsFolder() = Unit
            override suspend fun shareExport(name: String) = Unit
        }
        val ports = com.hjtk.app.core.HostPorts(
            clock = w.clock, files = slow, web = w.web, mail = w.mail, calendar = w.calendar,
            apps = w.apps, clipboard = w.clips, notify = w.notify, device = w.device, ai = w.ai,
            diag = w.diag, memory = w.memory, skills = w.skills, pipelines = w.pipelines,
            hub = w.hub, sessions = w.sessions, plans = w.orchestrator
        )
        w.orchestrator.attachPorts(ports)
        w.orchestrator.runExplicit("file.export_text", mapOf("content" to "A", "name" to "a.txt"))
        w.orchestrator.runExplicit("mail.send", mapOf("to" to "a@b.c", "subject" to "s", "body" to "b"))
        awaitUntil(10_000, "두 작업이 동시에 등록되지 않았습니다") { w.orchestrator.state.value.jobs.size == 2 }
        // Wait until the mail job is actually parked on its approval prompt.
        awaitUntil(10_000, "메일 작업의 승인 요청이 나타나지 않았습니다") {
            w.orchestrator.state.value.jobs.any {
                it.plan.steps.first().commandId == "mail.send" && it.pendingApproval != null
            }
        }
        val slowJob = w.orchestrator.state.value.jobs.first { it.plan.steps.first().commandId == "file.export_text" }
        w.orchestrator.cancel(slowJob.plan.id)
        awaitUntil(10_000, "중단된 작업이 남아 있습니다") { w.orchestrator.state.value.jobs.none { it.plan.id == slowJob.plan.id } }

        // The mail job is untouched and still waiting for its own approval.
        val mailJob = w.orchestrator.state.value.jobs.first { it.plan.steps.first().commandId == "mail.send" }
        assertTrue(mailJob.pendingApproval != null, "메일 작업의 승인 요청이 사라졌습니다")
        assertTrue(w.mailSent.isEmpty())
        w.orchestrator.approve(mailJob.plan.id)
        awaitUntil(10_000, "승인 후에도 메일이 나가지 않았습니다") { w.mailSent.size == 1 }
    }

    @Test
    fun approvalIsScopedToItsOwnPlan() {
        val w = TestWorld()
        w.orchestrator.runExplicit("mail.send", mapOf("to" to "a@b.c", "subject" to "s", "body" to "b"))
        w.orchestrator.runExplicit("device.battery")
        awaitUntil(3_000, "승인 요청이 표시되지 않았습니다") {
            w.orchestrator.state.value.jobs.any { it.pendingApproval != null }
        }
        val pending = w.orchestrator.state.value.jobs.first { it.pendingApproval != null }
        assertEquals("mail.send", pending.plan.steps.first().commandId)

        // Rejecting the mail job must not touch the battery job.
        w.orchestrator.reject(pending.plan.id)
        awaitUntil(4_000, "거절 후 정리되지 않았습니다") {
            w.orchestrator.state.value.jobs.none { it.plan.id == pending.plan.id }
        }
        assertTrue(w.mailSent.isEmpty())
        awaitUntil(3_000, "다른 작업이 사라졌습니다") { w.orchestrator.state.value.jobs.isEmpty() }
    }

    @Test
    fun resourceGuardSerialisesSameKeyAndAllowsDifferentKeys() = runBlocking {
        val guard = ResourceGuard()
        val active = AtomicInteger(0)
        val maxForMail = AtomicInteger(0)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val jobs = (1..4).map {
            scope.async {
                guard.withResource("mail.send") {
                    val n = active.incrementAndGet()
                    maxForMail.updateAndGet { m -> maxOf(m, n) }
                    delay(80)
                    active.decrementAndGet()
                }
            }
        }
        jobs.awaitAll()
        assertEquals(1, maxForMail.get(), "같은 자원이 동시에 두 번 사용되었습니다")

        val activeOther = AtomicInteger(0)
        val maxOther = AtomicInteger(0)
        val mixed = (1..4).map { i ->
            scope.async {
                val cmd = if (i % 2 == 0) "device.battery" else "device.storage"
                guard.withResource(cmd) {
                    val n = activeOther.incrementAndGet()
                    maxOther.updateAndGet { m -> maxOf(m, n) }
                    delay(60)
                    activeOther.decrementAndGet()
                }
            }
        }
        mixed.awaitAll()
        assertTrue(maxOther.get() >= 2, "독립 명령이 병렬로 실행되지 않았습니다")
    }

    @Test
    fun planStateAndStatusAreExposed() {
        val w = TestWorld()
        w.orchestrator.runExplicit("device.battery")
        awaitUntil(3_000) { w.orchestrator.state.value.jobs.isEmpty() }
        assertTrue(w.orchestrator.status().contains("대기") || w.orchestrator.status().isNotBlank())
        assertFalse(w.orchestrator.isRunning())
    }
}
