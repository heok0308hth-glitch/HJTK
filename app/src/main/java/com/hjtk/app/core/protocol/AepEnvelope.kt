package com.hjtk.app.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * AEP-1 — Agent Envelope Protocol, version 1.
 *
 * HJTK talks to an AI through a short, typed envelope instead of screenshots
 * and coordinate clicking:
 *
 * ```text
 * @aep:1 plan
 * {"schema":"plan.v1","title":"아침 루틴","steps":[{"commandId":"calendar.today"}]}
 * ```
 *
 * One envelope can carry a whole multi-step plan, so a single model turn
 * replaces N screenshot→tap round trips. The parser is deliberately forgiving
 * in a fixed order ([AepParser]) and never executes anything it could not
 * parse into a validated schema.
 */
data class AepEnvelope(
    val kind: String,
    val schema: String,
    val json: JsonObject,
    val raw: String
) {
    fun steps(): List<AepStep> {
        val arr = (json["steps"] as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val cmd = (obj["commandId"] as? JsonPrimitive)?.contentOrNullSafe() ?: return@mapNotNull null
            val args = (obj["args"] as? JsonObject)?.mapValues { it.value.toString().trim('"') } ?: emptyMap()
            AepStep(cmd, args)
        }
    }

    fun text(): String = (json["text"] as? JsonPrimitive)?.contentOrNullSafe().orEmpty()

    fun title(): String = (json["title"] as? JsonPrimitive)?.contentOrNullSafe().orEmpty()
}

data class AepStep(val commandId: String, val args: Map<String, String> = emptyMap())

sealed class AepParse {
    data class Ok(val envelope: AepEnvelope) : AepParse()
    /** Understood enough to ask for one repair turn, with a concrete reason. */
    data class Repair(val reason: String) : AepParse()
    /** Plain prose: a chat answer, not a protocol message. */
    data class Plain(val text: String) : AepParse()
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (isString) content else content

/**
 * Seven-step recovery ladder. Each rung is cheaper to reach than the next, and
 * the parser records which rung answered so the developer console can show it.
 */
class AepParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Result(val parse: AepParse, val rung: Int, val rungName: String)

    fun parse(text: String): Result {
        if (text.isBlank()) return Result(AepParse.Repair("빈 응답"), 0, "empty")

        // rung 1: strict fenced block with the @aep header
        strictBlock(text)?.let { return finish(it, 1, "strict-fence") }

        // rung 2: any fenced block that carries a schema
        fencedWithSchema(text)?.let { return finish(it, 2, "fence+schema") }

        // rung 3: an @aep header without a fence
        headerLoose(text)?.let { return finish(it, 3, "header-loose") }

        // rung 4: first balanced object that carries a schema
        balancedObject(text)?.let { return finish(it, 4, "balanced-object") }

        // rung 5: no envelope, but the text looks like an attempt
        if (text.contains("@aep", ignoreCase = true) || text.contains("\"schema\"", ignoreCase = true)) {
            return Result(AepParse.Repair("봉투 형식을 해석하지 못했습니다. JSON 본문이 완전한지 확인해 주세요."), 5, "repair")
        }

        // rung 6: plain prose
        return Result(AepParse.Plain(text.trim()), 6, "plain")
    }

    private fun finish(parse: AepParse, rung: Int, name: String): Result =
        if (parse is AepParse.Repair) Result(parse, 5, "repair") else Result(parse, rung, name)

    private fun strictBlock(text: String): AepParse? {
        val m = Regex("```(?:aep)?\\s*\\n?@aep:(\\d+)\\s+(\\w+)\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(text)
            ?: return null
        val kind = m.groupValues[2].lowercase()
        val body = m.groupValues[3].trim()
        return envelopeOrNull(kind, body, m.value)
    }

    private fun fencedWithSchema(text: String): AepParse? {
        val fences = Regex("```(?:json)?\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE).findAll(text)
        for (f in fences) {
            val body = f.groupValues[1].trim()
            if (!body.contains("\"schema\"")) continue
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: continue
            val schema = (obj["schema"] as? JsonPrimitive)?.contentOrNullSafe() ?: continue
            val kind = if (schema.startsWith("plan")) "plan" else if (schema.startsWith("answer")) "answer" else "unknown"
            return AepParse.Ok(AepEnvelope(kind, schema, obj, f.value))
        }
        return null
    }

    private fun balancedObject(text: String): AepParse? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) {
                        val body = text.substring(start, i + 1)
                        if (!body.contains("\"schema\"")) return null
                        return envelopeOrNull(null, body, body)
                    }
                }
            }
        }
        return null
    }

    private fun headerLoose(text: String): AepParse? {
        val m = Regex("@aep:\\d+\\s+(\\w+)", RegexOption.IGNORE_CASE).find(text) ?: return null
        val kind = m.groupValues[1].lowercase()
        val body = text.substring(m.range.last + 1).trim()
        return envelopeOrNull(kind, body, text)
    }

    private fun envelopeOrNull(kindHint: String?, body: String, raw: String): AepParse? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val schema = (obj["schema"] as? JsonPrimitive)?.contentOrNullSafe()
        if (schema.isNullOrBlank()) return null
        val kind = kindHint ?: if (schema.startsWith("plan")) "plan" else "answer"
        val envelope = AepEnvelope(kind, schema, obj, raw)
        // schema validation: a plan needs at least one step with a commandId
        if (schema.startsWith("plan") && envelope.steps().isEmpty()) {
            return AepParse.Repair("plan.v1 봉투에 실행 가능한 steps가 없습니다.")
        }
        return AepParse.Ok(envelope)
    }

    /** Builds the repair request sent back to the model once (never twice). */
    fun repairPrompt(reason: String): String = """
        이전 응답을 AEP-1 형식으로 다시 보내 주세요. 다른 설명은 쓰지 마세요.

        @aep:1 plan
        {"schema":"plan.v1","title":"...","steps":[{"commandId":"...","args":{}}]}

        문제: $reason
    """.trimIndent()

    fun encodePlan(title: String, steps: List<AepStep>): String {
        val stepsJson = steps.joinToString(",") { s ->
            val args = if (s.args.isEmpty()) "" else ", \"args\": {" + s.args.entries.joinToString(",") { (k, v) -> "\"$k\":\"${v.replace("\"", "\\\"")}\"" } + "}"
            "{\"commandId\":\"${s.commandId}\"$args}"
        }
        return "@aep:1 plan\n{\"schema\":\"plan.v1\",\"title\":\"${title.replace("\"", "\\\"")}\",\"steps\":[$stepsJson]}"
    }
}
