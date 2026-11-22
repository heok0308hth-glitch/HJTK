package com.hjtk.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.hjtk.app.android.Engine
import com.hjtk.app.core.model.CommandSpec
import com.hjtk.app.core.model.HubEntry
import com.hjtk.app.core.model.HubKind
import com.hjtk.app.core.model.Pipeline
import com.hjtk.app.core.model.Skill
import com.hjtk.app.core.model.SkillStep
import kotlinx.coroutines.flow.StateFlow

/**
 * One view model for the whole app: screens stay dumb, the engine stays
 * testable, and no composable touches a store directly.
 */
class HjtkViewModel(app: Application) : AndroidViewModel(app) {

    val engine = Engine.of(app)
    val state = engine.orchestrator.state
    val settings = engine.settings.state

    fun submit(text: String) = engine.orchestrator.submit(text)
    fun approve(planId: String) = engine.orchestrator.approve(planId)
    fun reject(planId: String) = engine.orchestrator.reject(planId)
    fun stop(planId: String? = null) = engine.orchestrator.cancel(planId)
    fun jobs() = engine.orchestrator.state.value.jobs
    fun newSession() = engine.orchestrator.newSession()
    fun resume() = engine.orchestrator.resumeOpenPlan()

    fun commands(query: String): List<CommandSpec> = engine.registry.search(query, 40)
    fun categories() = engine.registry.byCategory()
    fun runCommand(id: String, args: Map<String, String>) = engine.orchestrator.runExplicit(id, args)

    fun exportSession() = engine.orchestrator.runExplicit("file.export_session")
    fun exportMemoryFile() = engine.orchestrator.runExplicit(
        "file.export_text",
        mapOf("content" to engine.memory.exportMarkdown(), "name" to "memory.md")
    )
    fun exportDiagnostics() = engine.orchestrator.runExplicit("system.export_diagnostics")

    fun hub(): List<HubEntry> = engine.hub.ranked()
    fun addHub(kind: HubKind, label: String, target: String) = engine.hub.add(kind, label, target)
    fun removeHub(id: String) = engine.hub.remove(id)
    fun pinHub(id: String) = engine.hub.togglePin(id)
    fun openHub(entry: HubEntry) {
        engine.hub.markUsed(entry.id)
        engine.orchestrator.runExplicit("hub.open", mapOf("label" to entry.label))
    }

    fun pipelines(): List<Pipeline> = engine.pipelines.all()
    fun skills(): List<Skill> = engine.skills.all()
    fun runPipeline(id: String) {
        val p = engine.pipelines.byId(id) ?: return
        engine.orchestrator.submit("/pipeline.run name=${p.name}")
    }

    fun runPipelineWith(id: String, variables: Map<String, String>) =
        engine.orchestrator.runPipeline(id, variables)

    fun runSkill(skill: Skill) = engine.orchestrator.runSkill(skill)
    fun createPipeline(name: String, description: String, steps: List<SkillStep>) {
        engine.pipelines.create(name, description, steps)
    }
    fun deletePipeline(id: String) = engine.pipelines.delete(id)
    fun deleteSkill(id: String) = engine.skills.delete(id)

    fun settingsState(): StateFlow<com.hjtk.app.android.prefs.Settings.Snapshot> = engine.settings.state
    fun diagnostics(): String = engine.diagnosticsForUi()

    fun memories(): List<com.hjtk.app.core.model.MemoryEntry> = engine.memory.all()
    fun forget(id: String) = engine.memory.forgetId(id)
    fun exportMemory(): String = engine.memory.exportMarkdown()
}
