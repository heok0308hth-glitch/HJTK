package com.hjtk.app

import com.hjtk.app.core.model.ApprovalRequest
import com.hjtk.app.core.model.CommandRequest
import com.hjtk.app.core.model.CommandResult
import com.hjtk.app.core.model.CommandSpec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Executes every command in the catalog against fakes.
 *
 * This is the "no dead button" test: a command that throws, or that has a slot
 * the UI cannot fill, fails the suite. Commands that legitimately need an
 * account or permission are expected to return a handled [CommandResult.Failed]
 * with a readable reason, never an exception.
 */
class CommandSmokeTest {

    private val w = TestWorld()

    private fun argsFor(spec: CommandSpec): Map<String, String> =
        spec.params.associate { it.name to it.example.ifBlank { "테스트" } }

    private fun run(spec: CommandSpec, args: Map<String, String> = argsFor(spec)): CommandResult = runBlocking {
        val executor = w.registry.get(spec.id)!!
        executor.run(CommandRequest(spec.id, args), w.ports)
    }

    @Test
    fun everyCommandRunsWithoutThrowing() {
        val failures = LinkedHashMap<String, String>()
        w.registry.all().forEach { spec ->
            val result = runCatching { run(spec) }
            if (result.isFailure) {
                failures[spec.id] = result.exceptionOrNull()?.message ?: "exception"
                return@forEach
            }
            val value = result.getOrThrow()
            if (value is CommandResult.NeedInput) {
                failures[spec.id] = "NeedInput: ${value.prompt}"
            }
        }
        assertTrue(failures.isEmpty(), "실행 불가 명령: $failures")
    }

    @Test
    fun readOnlyCommandsSucceedWithFakes() {
        val mustSucceed = listOf(
            "search.web", "search.news", "search.wiki", "device.battery", "device.storage",
            "device.network", "mail.unread", "mail.latest", "calendar.today", "memory.list",
            "memory.recall", "clip.read", "pipeline.list", "pipeline.status", "skill.list",
            "hub.list", "system.diagnostics", "system.help", "system.about", "ai.ask",
            "ai.summarize", "ai.translate", "ai.compare", "ai.status"
        )
        mustSucceed.forEach { id ->
            val spec = w.registry.spec(id)!!
            val result = run(spec)
            assertTrue(result is CommandResult.Ok, "$id → $result")
        }
    }

    @Test
    fun writeCommandsActuallyWrite() {
        run(w.registry.spec("memory.remember")!!)
        assertTrue(w.memory.all().isNotEmpty())

        run(w.registry.spec("clip.copy")!!)
        assertTrue(w.clipboard.isNotEmpty())

        run(w.registry.spec("file.export_text")!!)
        assertTrue(w.exported.isNotEmpty())

        run(w.registry.spec("notify.show")!!)
        assertTrue(w.notifications.isNotEmpty())

        run(w.registry.spec("device.volume")!!)
        assertTrue(w.volumeSet.contains(40))

        run(w.registry.spec("calendar.new_event")!!)
        assertTrue(w.calendarInserted.isNotEmpty())

        run(w.registry.spec("mail.send")!!)
        assertTrue(w.mailSent.isNotEmpty())
    }

    @Test
    fun mailCommandsFailWithReadableReasonWhenUnconfigured() {
        w.mailConfigured = false
        val result = run(w.registry.spec("mail.unread")!!)
        assertTrue(result is CommandResult.Failed)
        assertTrue((result as CommandResult.Failed).reason.contains("연결"))
    }

    @Test
    fun calendarWithoutPermissionExplainsItself() {
        w.calendarPermission = false
        val result = run(w.registry.spec("calendar.today")!!)
        assertTrue(result is CommandResult.Failed)
        assertTrue((result as CommandResult.Failed).reason.contains("권한"))
    }

    @Test
    fun aiFailureIsReportedNotSwallowed() {
        w.aiFails = true
        val result = run(w.registry.spec("ai.ask")!!)
        assertTrue(result is CommandResult.Failed)
    }

    @Test
    fun skillRunSubmitsToGateway() {
        val result = run(w.registry.spec("skill.run")!!, mapOf("name" to "상태 점검"))
        assertTrue(result is CommandResult.Ok, "$result")
    }

    @Test
    fun pipelineRunSubmitsToGateway() {
        val result = run(w.registry.spec("pipeline.run")!!, mapOf("name" to "아침 루틴"))
        assertTrue(result is CommandResult.Ok, "$result")
    }

    @Test
    fun unknownPipelineNameIsExplained() {
        val result = run(w.registry.spec("pipeline.run")!!, mapOf("name" to "없는파이프"))
        assertTrue(result is CommandResult.Failed)
    }

    @Test
    fun hubCommandShortcutRunsTheCommand() {
        val entry = w.hub.add(com.hjtk.app.core.model.HubKind.COMMAND, "배터리 확인", "device.battery")
        val result = run(w.registry.spec("hub.open")!!, mapOf("label" to entry.label))
        assertTrue(result is CommandResult.Ok, "$result")
        awaitUntil(5_000, "허브 명령이 계획으로 제출되지 않았습니다") {
            w.journal.records().any { it.kind == "plan_started" && it.detail.contains("배터리") }
        }
    }

    @Test
    fun approvalGateIsNotNeededForReadCommands() {
        val spec = w.registry.spec("device.battery")!!
        assertTrue(!spec.effect.needsApproval)
        val request = ApprovalRequest(
            planId = "p", stepId = "s", commandId = spec.id, titleKo = "", summaryKo = "",
            effect = spec.effect
        )
        assertTrue(!request.effect.needsApproval)
    }
}
