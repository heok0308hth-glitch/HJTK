package com.hjtk.app.core.skill

import com.hjtk.app.core.Clock
import com.hjtk.app.core.model.Skill
import com.hjtk.app.core.model.SkillStep
import com.hjtk.app.core.storage.JsonCodec
import com.hjtk.app.core.storage.TextStorage
import com.hjtk.app.core.text.TextScore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Skills are *declarations*, not code: a name, a trigger and a short list of
 * command steps. The registry validates every step against the live command
 * catalog, so a skill can never reference a command that does not exist.
 */
class SkillStore(
    private val storage: TextStorage,
    private val clock: Clock
) {
    @Serializable
    private data class Payload(val skills: List<Skill> = emptyList())

    private val lock = Any()
    private val userSkills = ArrayList<Skill>()
    private val builtIns = ArrayList<Skill>()
    private val fileName = "skills.json"

    init {
        load()
    }

    private fun load() {
        val raw = storage.read(fileName) ?: return
        val parsed = runCatching { JsonCodec.json.decodeFromString<Payload>(raw) }.getOrNull() ?: return
        synchronized(lock) {
            userSkills.clear()
            userSkills.addAll(parsed.skills)
        }
    }

    private fun persistLocked() {
        storage.write(fileName, JsonCodec.json.encodeToString(Payload(userSkills.toList())))
    }

    fun installBuiltIns(skills: List<Skill>) {
        synchronized(lock) {
            builtIns.clear()
            builtIns.addAll(skills.map { it.copy(builtIn = true) })
        }
    }

    fun all(): List<Skill> = synchronized(lock) { builtIns + userSkills }

    fun userDefined(): List<Skill> = synchronized(lock) { userSkills.toList() }

    fun byId(id: String): Skill? = all().firstOrNull { it.id == id }

    /** Best skill for a free-text request, with the score that produced it. */
    fun match(text: String): Pair<Skill, Double>? {
        val candidates = all()
        if (candidates.isEmpty()) return null
        val lower = text.lowercase()
        var best: Pair<Skill, Double>? = null
        var bestStrong = false
        for (s in candidates) {
            var score = TextScore.score(text, s.name + " " + s.description + " " + s.keywords.joinToString(" "))
            // A skill only fires when the user actually said one of its words,
            // typed its name, or matched its pattern. A vague similarity score
            // alone must never hijack a request.
            var strong = lower.contains(s.name.lowercase())
            val keywordHit = s.keywords.any { it.isNotBlank() && lower.contains(it.lowercase()) }
            if (keywordHit) {
                score += 0.35
                strong = true
            }
            if (s.pattern.isNotBlank() && runCatching { Regex(s.pattern, RegexOption.IGNORE_CASE).containsMatchIn(text) }.getOrDefault(false)) {
                score += 0.6
                strong = true
            }
            if (best == null || score > best.second) {
                best = s to score
                bestStrong = strong
            }
        }
        return best?.takeIf { it.second >= 0.55 && bestStrong }
    }

    fun upsert(skill: Skill): Skill {
        synchronized(lock) {
            val idx = userSkills.indexOfFirst { it.id == skill.id }
            if (idx >= 0) userSkills[idx] = skill.copy(builtIn = false) else userSkills.add(skill.copy(builtIn = false))
            persistLocked()
        }
        return skill
    }

    fun create(name: String, description: String, keywords: List<String>, steps: List<SkillStep>): Skill {
        val s = Skill(
            name = name.trim(),
            description = description.trim(),
            keywords = keywords.map { it.trim() }.filter { it.isNotEmpty() },
            steps = steps
        )
        upsert(s)
        return s
    }

    fun delete(id: String): Boolean {
        synchronized(lock) {
            val removed = userSkills.removeAll { it.id == id }
            if (removed) persistLocked()
            return removed
        }
    }

    fun exportJson(): String = JsonCodec.pretty.encodeToString(userSkills.toList())
}
