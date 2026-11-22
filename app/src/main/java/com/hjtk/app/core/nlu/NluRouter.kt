package com.hjtk.app.core.nlu

import com.hjtk.app.core.command.CommandRegistry
import com.hjtk.app.core.model.CommandRequest
import com.hjtk.app.core.model.CommandSpec
import com.hjtk.app.core.model.Pipeline
import com.hjtk.app.core.model.Skill
import com.hjtk.app.core.model.SkillStep
import com.hjtk.app.core.skill.PipelineStore
import com.hjtk.app.core.skill.SkillStore
import com.hjtk.app.core.text.TextScore

sealed class Route {
    data class Command(val request: CommandRequest, val confidence: Double) : Route()
    data class SkillRoute(val skill: Skill, val score: Double) : Route()
    data class PipelineRoute(val pipeline: Pipeline) : Route()
    /** Two deterministic commands chained by the user's phrasing. */
    data class PlanRoute(val title: String, val steps: List<SkillStep>) : Route()
    data class Chat(val reply: String) : Route()
    data class Unknown(val message: String, val suggestions: List<CommandSpec>) : Route()
}

/**
 * The router is the whole reason HJTK needs no on-device model.
 *
 * Order of evidence, strongest first:
 *  1. an explicit command id  (/mail.send to=… subject=…)
 *  2. a pipeline or skill the user named
 *  3. a regular expression attached to a command (slots come out of the match)
 *  4. keyword density, weighted by how specific the keyword is
 *  5. two commands joined by a conjunction ("검색해서 요약해줘")
 *
 * Everything is scored; nothing is sampled. Given the same text and the same
 * catalog the result is identical, which is what makes the router testable.
 */
class NluRouter(
    private val registry: CommandRegistry,
    private val skills: SkillStore,
    private val pipelines: PipelineStore
) {

    companion object {
        private const val MIN_CONFIDENCE = 0.55
        private const val MIN_PATTERN_SCORE = 0.80
        private val CONJUNCTION = Regex("^(.+?)\\s*(?:해서|하고|한\\s*뒤|한\\s*후|그리고)\\s+(.+)$")
        private val CHITCHAT = mapOf(
            "안녕" to "안녕하세요. 무엇을 도와드릴까요?",
            "하이" to "네, 말씀하세요.",
            "고마워" to "천만에요. 또 필요하면 불러 주세요.",
            "감사" to "도움이 되었다니 다행입니다.",
            "ㅎㅇ" to "안녕하세요.",
            "hi" to "Hello — what should I run?",
            "hello" to "Hello — what should I run?"
        )
    }

    fun route(input: String): Route {
        val text = input.trim()
        if (text.isEmpty()) return Route.Unknown("무엇을 도와드릴까요?", emptyList())

        // 0. small talk: only when the whole message is a greeting, so
        // "안녕하세요 번역해줘" still routes to the translator.
        val bare = text.lowercase().trim().trim('!', '.', '~', '?', ' ')
        CHITCHAT[bare]?.let { return Route.Chat(it) }
        if (bare.length <= 8) {
            for ((key, reply) in CHITCHAT) {
                if (bare == key || bare.startsWith(key + " ")) return Route.Chat(reply)
            }
        }

        // 1. explicit id
        explicitCommand(text)?.let { return it }

        // 2. named pipeline
        pipelineMatch(text)?.let { return it }

        // 3. two-step composition ("A해서 B")
        composite(text)?.let { return it }

        // 4. single command
        val best = bestCommand(text)
        if (best != null && best.route.confidence >= MIN_CONFIDENCE) return best.route

        // 4b. a skill whose trigger is more specific than any command
        skillMatch(text)?.let { return it }

        // 5. could not route: offer the closest entries instead of guessing
        val suggestions = registry.search(text, 4).filter { it.id != "system.help" }
        val message = if (suggestions.isEmpty()) {
            "이 요청을 명령으로 바꾸지 못했습니다. 명령 팔레트에서 비슷한 기능을 찾아보시거나, 조금 더 구체적으로 말해 주세요."
        } else {
            "정확히 어떤 작업인지 확실하지 않습니다. 아래 중 하나일까요?"
        }
        return Route.Unknown(message, suggestions)
    }

    // ------------------------------------------------------------------ 1

    private fun explicitCommand(text: String): Route.Command? {
        if (!text.startsWith("/")) return null
        val body = text.removePrefix("/").trim()
        val id = body.substringBefore(' ')
        val spec = registry.spec(id) ?: return null
        val args = LinkedHashMap<String, String>()
        val rest = body.substringAfter(' ', "")
        if (rest.isNotBlank()) {
            rest.split(Regex("\\s+(?=[a-zA-Z_]+=)")).forEach { pair ->
                val k = pair.substringBefore('=', "").trim()
                val v = pair.substringAfter('=', "").trim().trim('"')
                if (k.isNotBlank() && v.isNotBlank()) args[k] = v
            }
            if (args.isEmpty()) {
                spec.primaryParam?.let { args[it.name] = rest }
            }
        }
        return Route.Command(CommandRequest(id, args, text, explicit = true), 1.0)
    }

    // ------------------------------------------------------------------ 2

    private fun pipelineMatch(text: String): Route.PipelineRoute? {
        val p = pipelines.all().firstOrNull { it.name.isNotBlank() && text.contains(it.name, ignoreCase = true) }
        return p?.let { Route.PipelineRoute(it) }
    }

    private fun skillMatch(text: String): Route.SkillRoute? {
        val (skill, score) = skills.match(text) ?: return null
        return Route.SkillRoute(skill, score)
    }

    // ------------------------------------------------------------------ 3

    private fun composite(text: String): Route.PlanRoute? {
        val m = CONJUNCTION.find(text) ?: return null
        val first = m.groupValues[1].trim()
        val second = m.groupValues[2].trim()
        if (first.length < 2 || second.length < 2) return null
        val a = bestCommand(first)?.route as? Route.Command ?: return null
        val b = bestCommand(second)?.route as? Route.Command ?: return null
        if (a.confidence < MIN_PATTERN_SCORE || b.confidence < 0.40) return null
        if (a.request.commandId == b.request.commandId) return null
        // The second half must actually be missing its subject, otherwise the
        // user said two independent things and chaining would be wrong.
        val specB = registry.spec(b.request.commandId) ?: return null
        val needsSubject = specB.primaryParam?.let { b.request.args[it.name].isNullOrBlank() } ?: false
        if (!needsSubject) return null

        val stepA = SkillStep(a.request.commandId, a.request.args)
        val stepB = run {
            val args = LinkedHashMap(b.request.args)
            val spec = registry.spec(b.request.commandId)
            val primary = spec?.primaryParam
            if (primary != null && args[primary.name].isNullOrBlank()) {
                args[primary.name] = "{{out:1}}"   // feed step 1's output
            }
            SkillStep(b.request.commandId, args)
        }
        val title = "${registry.labelOf(stepA.commandId)} → ${registry.labelOf(stepB.commandId)}"
        return Route.PlanRoute(title, listOf(stepA, stepB))
    }

    // ------------------------------------------------------------------ 4

    private data class Scored(val spec: CommandSpec, val route: Route.Command)

    private fun bestCommand(text: String): Scored? {
        var best: Scored? = null
        var bestScore = 0.0
        var bestPattern = false

        for (spec in registry.all()) {
            val (score, args) = scoreSpec(spec, text)
            if (score <= 0.0) continue
            val hasPattern = score >= MIN_PATTERN_SCORE
            val better = best == null ||
                (hasPattern && !bestPattern) ||
                (hasPattern == bestPattern && score > bestScore + 1e-9)
            if (better) {
                bestScore = score
                bestPattern = hasPattern
                best = Scored(spec, Route.Command(CommandRequest(spec.id, args, text), score))
            }
        }
        return best
    }

    private fun scoreSpec(spec: CommandSpec, text: String): Pair<Double, Map<String, String>> {
        var score = 0.0
        val args = LinkedHashMap<String, String>()

        for (pattern in spec.patterns) {
            val match = runCatching { Regex(pattern, RegexOption.IGNORE_CASE).find(text) }.getOrNull() ?: continue
            if (match.range.first != 0) continue   // anchored phrases only
            score = maxOf(score, MIN_PATTERN_SCORE + 0.05)
            for (p in spec.params) {
                val v = runCatching { match.groups[p.name]?.value?.trim() }.getOrNull()
                if (!v.isNullOrBlank()) args[p.name] = v
            }
            break
        }

        // label and keyword evidence
        val lower = text.lowercase()
        if (lower.contains(spec.labelKo.lowercase())) score = maxOf(score, 0.72)
        var keywordWeight = 0.0
        for (k in spec.keywords) {
            val kw = k.lowercase()
            if (kw.isBlank() || !lower.contains(kw)) continue
            // A keyword that opens the sentence is far stronger evidence than
            // one buried in the middle ("허브 보여줘" vs "그 허브 말고").
            val atStart = lower.trimStart().startsWith(kw)
            val weight = 0.30 + 0.05 * kw.length + if (atStart) 0.18 else 0.0
            keywordWeight = maxOf(keywordWeight, weight)
        }
        score = maxOf(score, keywordWeight)

        // last resort: the whole text is the primary slot of a command whose
        // keywords appear somewhere ("요약해줘 <text>"). Kept weak on purpose.
        if (score == 0.0) {
            val sim = TextScore.score(text, spec.labelKo + " " + spec.keywords.joinToString(" "))
            if (sim > 0.62) score = 0.45
        }

        // Fill the primary slot from the text when the pattern did not.
        if (score > 0.0 && args.isEmpty()) {
            spec.primaryParam?.let { p ->
                val cleaned = cleanPrimary(text, spec)
                if (cleaned.isNotBlank() && cleaned.length >= 1) args[p.name] = cleaned
            }
        }
        return score to args
    }

    /** Removes the trigger words so "토스 앱 열어줘" leaves "토스". */
    private fun cleanPrimary(text: String, spec: CommandSpec): String {
        var t = text.trim()
        for (k in spec.keywords.sortedByDescending { it.length }) {
            t = Regex(Regex.escape(k), RegexOption.IGNORE_CASE).replace(t, " ")
        }
        t = t.replace(Regex("(?:좀|해\\s*줘|해줘|해|줘|앱|을|를|이|가|은|는|로|으로|에서|에게)\\s*$"), "")
        return t.replace(Regex("\\s+"), " ").trim().trim(',', '.', '!', '?')
    }
}
