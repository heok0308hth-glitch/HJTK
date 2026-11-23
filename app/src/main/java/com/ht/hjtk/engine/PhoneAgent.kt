package com.ht.hjtk.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ht.hjtk.core.command.CommandHandler
import com.ht.hjtk.core.command.CommandResult
import com.ht.hjtk.core.command.PhoneGuard
import com.ht.hjtk.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI에게 전화 시키기.
 *  - phone.prepare: 키 없이. 전화 앱에 번호를 채우고(통화 버튼은 사용자), 대본을 알림으로 띄운다.
 *  - phone.ai_call: Bland AI(번호 + 할 일만으로 통화) 또는 Vapi(대시보드에서 만든 어시스턴트 + 번호). 앱은 통화 ID로 상태를 물어 결과를 받아 온다.
 * 안전: 승인(금전 등급) · AI임을 밝히는 첫 문장 고정 · 긴급번호 차단 · 하루 5통 · 같은 번호 10분 쿨다운.
 */
class PhoneAgent(private val ctx: Context, private val store: Store, private val conn: ConnectorHub, private val launcher: Launcher) {
    private val log = File(ctx.filesDir, "hjtk/calls.json")

    private fun history(): List<Pair<String, Long>> = runCatching {
        val a = JSONArray(log.readText()); (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("n") to o.getLong("t") } }
    }.getOrDefault(emptyList())

    private fun remember(n: String, id: String, via: String) = runCatching {
        val a = runCatching { JSONArray(log.readText()) }.getOrDefault(JSONArray())
        a.put(JSONObject().put("n", n).put("t", System.currentTimeMillis()).put("id", id).put("via", via))
        val keep = JSONArray(); val from = (a.length() - 50).coerceAtLeast(0); for (i in from until a.length()) keep.put(a.get(i))
        log.parentFile?.mkdirs(); log.writeText(keep.toString())
    }

    private fun viaOf(id: String): String? = runCatching { val a = JSONArray(log.readText()); (0 until a.length()).map { a.getJSONObject(it) }.lastOrNull { it.optString("id") == id }?.optString("via") }.getOrNull()

    private suspend fun http(method: String, url: String, auth: String, body: JSONObject? = null): Pair<Int, String> = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $auth")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        try {
            body?.let { b -> c.outputStream.use { it.write(b.toString().toByteArray()) } }
            val code = c.responseCode
            code to ((if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText().orEmpty())
        } finally { c.disconnect() }
    }

    fun available(): String? = when {
        conn.token(Service.BLAND) != null -> "Bland AI"
        conn.token(Service.VAPI) != null -> "Vapi"
        else -> null
    }

    val handlers: Map<String, CommandHandler> = mapOf(
        "phone.prepare" to CommandHandler { c ->
            val n = c.arg("number")
            if (PhoneGuard.normalize(n) == null) return@CommandHandler CommandResult.fail("전화번호 형식이 아니에요")
            Notifier.simple(ctx, "통화 대본 · $n", c.arg("script").take(3500))
            CommandResult.ok(launcher.launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + n.filter { it.isDigit() || it == '+' })),
                "전화 앱 열기", "번호를 채웠어요. 통화 버튼은 직접 눌러 주세요. 대본은 알림에 있어요.") + "\n통화가 끝나면 결과를 말해 주시면 정리해 둘게요.")
        },
        "phone.ai_call" to CommandHandler { c ->
            val raw = c.arg("number")
            PhoneGuard.check(raw, history(), System.currentTimeMillis())?.let { return@CommandHandler CommandResult.fail(it) }
            val n = PhoneGuard.normalize(raw)!!
            val user = store.settings.value.userName.ifBlank { null }
            val minutes = c.int("minutes", 5).coerceIn(1, 15)
            val first = PhoneGuard.disclosure(user)
            val task = PhoneGuard.task(c.arg("goal"), c.arg("facts"), user)
            val bland = conn.token(Service.BLAND)
            val vapi = conn.token(Service.VAPI)
            val (via, id) = when {
                bland != null -> {
                    val (code, txt) = http("POST", "https://api.bland.ai/v1/calls", bland.second, JSONObject()
                        .put("phone_number", n).put("task", task).put("first_sentence", first).put("wait_for_greeting", true).put("max_duration", minutes))
                    if (code >= 400) return@CommandHandler CommandResult.fail("Bland HTTP $code · ${txt.take(160)}")
                    "bland" to (runCatching { JSONObject(txt).optString("call_id") }.getOrNull()?.ifBlank { null } ?: return@CommandHandler CommandResult.fail("통화 ID를 받지 못했어요"))
                }
                vapi != null -> {
                    // 토큰 형식: API키:어시스턴트ID:전화번호ID (어시스턴트 지시문에서 {{task}}를 쓰면 이번 목적이 들어간다)
                    val parts = vapi.second.split(":").map { it.trim() }
                    if (parts.size < 3) return@CommandHandler CommandResult.fail("Vapi는 'API키:어시스턴트ID:전화번호ID' 형식으로 넣어 주세요")
                    val (code, txt) = http("POST", "https://api.vapi.ai/call", parts[0], JSONObject()
                        .put("assistantId", parts[1]).put("phoneNumberId", parts[2]).put("customer", JSONObject().put("number", n))
                        .put("assistantOverrides", JSONObject().put("firstMessage", first).put("variableValues", JSONObject().put("task", task))))
                    if (code >= 400) return@CommandHandler CommandResult.fail("Vapi HTTP $code · ${txt.take(160)}")
                    "vapi" to (runCatching { JSONObject(txt).optString("id") }.getOrNull()?.ifBlank { null } ?: return@CommandHandler CommandResult.fail("통화 ID를 받지 못했어요"))
                }
                else -> return@CommandHandler CommandResult.fail("AI 전화 계정이 없어요. 허브 → 계정 연결에서 Bland AI 또는 Vapi 키를 넣거나, phone.prepare로 대본을 받아 직접 거세요.")
            }
            remember(n, id, via)
            // 끝날 때까지 기다린다(통화 길이 + 여유 2분). 그 사이 작업은 '대기 중'으로 보인다.
            val deadline = System.currentTimeMillis() + (minutes + 2) * 60_000L
            var last = ""
            while (System.currentTimeMillis() < deadline) {
                delay(6_000)
                val r = result(via, id) ?: continue
                last = r.second
                if (r.first) return@CommandHandler CommandResult.ok("통화 ID $id\n$last")
            }
            CommandResult.ok("통화 ID $id · 아직 진행 중이거나 결과가 늦어요. phone.call_result로 다시 확인하세요.\n$last")
        },
        "phone.call_result" to CommandHandler { c ->
            val id = c.arg("id")
            val via = viaOf(id) ?: if (conn.token(Service.BLAND) != null) "bland" else "vapi"
            result(via, id)?.let { CommandResult.ok(it.second) } ?: CommandResult.fail("결과를 가져오지 못했어요")
        }
    )

    /** (끝났나, 사람이 읽는 결과) */
    private suspend fun result(via: String, id: String): Pair<Boolean, String>? {
        return if (via == "bland") {
            val t = conn.token(Service.BLAND)?.second ?: return null
            val (code, txt) = http("GET", "https://api.bland.ai/v1/calls/$id", t); if (code >= 400) return null
            val o = runCatching { JSONObject(txt) }.getOrNull() ?: return null
            val done = o.optBoolean("completed") || o.optString("status").lowercase() in setOf("completed", "ended", "failed", "no-answer", "busy")
            done to listOfNotNull(
                "상태 " + o.optString("status").ifBlank { if (done) "완료" else "진행 중" } + (o.optString("answered_by").takeIf { it.isNotBlank() && it != "null" }?.let { " · 받은 쪽 $it" } ?: ""),
                o.optDouble("call_length").takeIf { !it.isNaN() && it > 0 }?.let { "통화 ${"%.1f".format(it)}분" },
                o.optString("summary").takeIf { it.isNotBlank() && it != "null" }?.let { "요약: $it" },
                o.optString("concatenated_transcript").takeIf { it.isNotBlank() && it != "null" }?.let { "대화록:\n" + it.take(4000) }
            ).joinToString("\n")
        } else {
            val parts = conn.token(Service.VAPI)?.second?.split(":") ?: return null
            val (code, txt) = http("GET", "https://api.vapi.ai/call/$id", parts[0].trim()); if (code >= 400) return null
            val o = runCatching { JSONObject(txt) }.getOrNull() ?: return null
            val done = o.optString("status") == "ended"
            val summary = o.optJSONObject("analysis")?.optString("summary")?.ifBlank { null } ?: o.optString("summary").ifBlank { null }
            val transcript = o.optString("transcript").ifBlank { null } ?: o.optJSONObject("artifact")?.optString("transcript")?.ifBlank { null }
            done to listOfNotNull("상태 " + o.optString("status") + (o.optString("endedReason").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                summary?.let { "요약: $it" }, transcript?.let { "대화록:\n" + it.take(4000) }).joinToString("\n")
        }
    }
}
