package com.hjtk.app.core.command

import com.hjtk.app.core.HostPorts
import com.hjtk.app.core.model.CommandCategory
import com.hjtk.app.core.model.CommandRequest
import com.hjtk.app.core.model.CommandResult
import com.hjtk.app.core.model.CommandSpec
import com.hjtk.app.core.model.EffectClass
import com.hjtk.app.core.model.ParamSpec
import com.hjtk.app.core.text.TextScore

typealias CommandExecutor = suspend (CommandRequest, HostPorts) -> CommandResult

/**
 * The extension point of HJTK. Every capability — the built-in 50+, a skill,
 * or a user's custom command — is one [CommandSpec] plus one executor, so the
 * router, the planner, the approval gate and the UI treat them identically.
 */
class CommandRegistry {

    data class Entry(val spec: CommandSpec, val run: CommandExecutor)

    private val map = LinkedHashMap<String, Entry>()

    fun register(spec: CommandSpec, run: CommandExecutor) {
        require(spec.id.isNotBlank()) { "command id must not be blank" }
        require(!map.containsKey(spec.id)) { "duplicate command id: ${spec.id}" }
        map[spec.id] = Entry(spec, run)
    }

    fun registerAll(builder: CommandRegistry.() -> Unit) = builder()

    fun get(id: String): Entry? = map[id]

    fun spec(id: String): CommandSpec? = map[id]?.spec

    fun exists(id: String): Boolean = map.containsKey(id)

    fun all(): List<CommandSpec> = map.values.map { it.spec }

    fun size(): Int = map.size

    fun byCategory(): LinkedHashMap<CommandCategory, List<CommandSpec>> {
        val out = LinkedHashMap<CommandCategory, MutableList<CommandSpec>>()
        for (e in map.values) out.getOrPut(e.spec.category) { mutableListOf() }.add(e.spec)
        return LinkedHashMap(out.mapValues { it.value.sortedBy { s -> s.labelKo } })
    }

    fun byCategory(category: CommandCategory): List<CommandSpec> =
        map.values.filter { it.spec.category == category }.map { it.spec }

    /** Command-palette search: label, keywords and id, ranked. */
    fun search(query: String, limit: Int = 20): List<CommandSpec> {
        if (query.isBlank()) return all().take(limit)
        return all()
            .map { spec -> spec to TextScore.score(query, spec.labelKo + " " + spec.id + " " + spec.keywords.joinToString(" ") + " " + spec.description) }
            .filter { it.second > 0.25 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** Returns a NeedInput result when a required slot is empty. */
    fun missingArg(request: CommandRequest, spec: CommandSpec): CommandResult.NeedInput? {
        for (p in spec.params.filter { it.required }) {
            if (request.arg(p.name) == null) {
                return CommandResult.NeedInput(
                    param = p.name,
                    prompt = "${p.labelKo}이(가) 필요합니다. " + if (p.example.isNotBlank()) "예: ${p.example}" else ""
                )
            }
        }
        return null
    }

    fun effectOf(commandId: String): EffectClass = map[commandId]?.spec?.effect ?: EffectClass.EXTERNAL

    fun labelOf(commandId: String): String = map[commandId]?.spec?.labelKo ?: commandId

    /** A one-line explanation used by approval cards and the developer log. */
    fun summarize(request: CommandRequest): String {
        val spec = spec(request.commandId) ?: return request.commandId
        val args = request.args.entries
            .filter { it.value.isNotBlank() }
            .joinToString(", ") { (k, v) -> "$k=${v.take(60)}" }
        return if (args.isBlank()) spec.labelKo else "${spec.labelKo} ($args)"
    }

    companion object {
        fun param(name: String, labelKo: String, required: Boolean = true, example: String = "") =
            ParamSpec(name, labelKo, required, example)
    }
}
