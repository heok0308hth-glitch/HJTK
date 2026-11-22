package com.hjtk.app.core.plan

import com.hjtk.app.core.Clock
import com.hjtk.app.core.command.CommandRegistry
import com.hjtk.app.core.model.Plan
import com.hjtk.app.core.model.PlanStep
import com.hjtk.app.core.model.SkillStep
import com.hjtk.app.core.util.TimeFmt

sealed class CompileResult {
    data class Success(val plan: Plan) : CompileResult()
    data class NeedsArgs(val commandId: String, val missing: List<String>, val prompt: String) : CompileResult()
    data class Invalid(val reason: String) : CompileResult()
}

/**
 * Turns loosely-typed user intent into a typed DAG.
 *
 * Every step declares which command runs; a slot containing `{{out:N}}` or
 * `{{data:N:key}}` points at step N (1-based), which becomes an edge in the
 * graph. Validation happens *before* anything runs: unknown commands, missing
 * required slots and cycles are all rejected here, so the executor only ever
 * walks a graph that is known to be coherent.
 */
class PlanCompiler(
    private val registry: CommandRegistry,
    private val eta: EtaEstimator,
    private val clock: Clock
) {

    companion object {
        private val REF = Regex("\\{\\{(out|data):(\\d+)(?::([a-zA-Z0-9_]+))?}}")
    }

    fun compile(title: String, decls: List<SkillStep>, variables: Map<String, String> = emptyMap()): CompileResult {
        if (decls.isEmpty()) return CompileResult.Invalid("실행할 단계가 없습니다.")

        val indexed = LinkedHashMap<Int, PlanStep>()
        for ((i, rawDecl) in decls.withIndex()) {
            val d = rawDecl.copy(args = rawDecl.args.mapValues { (_, v) -> applyVariables(v, variables) })
            val spec = registry.spec(d.commandId)
                ?: return CompileResult.Invalid("알 수 없는 명령입니다: ${d.commandId}")

            val missing = spec.params.filter { it.required }.filter { p -> d.args[p.name].isNullOrBlank() }
            if (missing.isNotEmpty()) {
                return CompileResult.NeedsArgs(
                    commandId = d.commandId,
                    missing = missing.map { it.name },
                    prompt = spec.labelKo + "에 필요한 값: " + missing.joinToString(", ") { it.labelKo + "(" + it.example + ")" }
                )
            }

            val deps = d.args.values.mapNotNull { resolveRef(it)?.first }.distinct().sorted()
            indexed[i + 1] = PlanStep(
                id = "s" + (i + 1),
                commandId = d.commandId,
                args = d.args,
                effect = spec.effect,
                titleKo = d.titleKo.ifBlank { spec.labelKo },
                dependsOn = deps.map { "s$it" },
                estimatedSeconds = eta.estimateSeconds(d.commandId)
            )
        }

        for (step in indexed.values) {
            for (dep in step.dependsOn) {
                val idx = dep.removePrefix("s").toIntOrNull() ?: return CompileResult.Invalid("잘못된 참조: $dep")
                if (idx !in indexed) return CompileResult.Invalid("참조한 단계($dep)가 계획에 없습니다.")
                if (idx >= step.id.removePrefix("s").toInt()) {
                    return CompileResult.Invalid("단계 ${step.id}이(가) 뒤 단계의 결과를 참조합니다(순환).")
                }
            }
        }

        val ordered = indexed.values.toList()
        val total = ordered.sumOf { it.estimatedSeconds }
        return CompileResult.Success(
            Plan(
                titleKo = title.ifBlank { ordered.first().titleKo + " 외 " + (ordered.size - 1) + "단계" },
                steps = ordered,
                createdAt = clock.nowMs()
            )
        )
    }

    /** `{{var:name}}` slots are filled once, at compile time. */
    private fun applyVariables(value: String, variables: Map<String, String>): String {
        val re = Regex("\\{\\{var:([a-zA-Z0-9_]+)}}")
        return re.replace(value) { m -> variables[m.groupValues[1]] ?: "" }
    }

    /** Returns (stepIndex, dataKey?) for a `{{...}}` reference, else null. */
    private fun resolveRef(value: String): Pair<Int, String?>? {
        val m = REF.find(value) ?: return null
        val idx = m.groupValues[2].toIntOrNull() ?: return null
        val key = m.groupValues[3].ifBlank { null }
        return idx to key
    }

    /** Substitutes resolved values into a step's arguments. */
    fun resolveArgs(step: PlanStep, outputs: Map<Int, Pair<String, Map<String, String>>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in step.args) {
            out[k] = REF.replace(v) { m ->
                val idx = m.groupValues[2].toIntOrNull()
                val key = m.groupValues[3].ifBlank { null }
                val ref = idx?.let { outputs[it] }
                when {
                    ref == null -> ""
                    key == null -> ref.first
                    else -> ref.second[key] ?: ref.first
                }
            }
        }
        return out
    }

    fun describe(plan: Plan): String = buildString {
        appendLine("**${plan.titleKo}**")
        appendLine("${plan.steps.size}단계 · 예상 ${TimeFmt.duration(plan.estimatedSeconds)}")
        plan.steps.forEachIndexed { i, s ->
            appendLine("${i + 1}. ${s.titleKo}" + if (s.dependsOn.isNotEmpty()) "  (${s.dependsOn.joinToString() } 결과 사용)" else "")
        }
    }.trim()
}
