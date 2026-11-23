package com.ht.hjtk.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ht.hjtk.Hjtk
import com.ht.hjtk.core.CustomCommand
import com.ht.hjtk.core.InputRouter
import com.ht.hjtk.core.Masker
import com.ht.hjtk.core.Pipeline
import com.ht.hjtk.core.command.CommandCall
import com.ht.hjtk.data.Conversation
import com.ht.hjtk.data.Msg
import com.ht.hjtk.data.Role
import com.ht.hjtk.tools.MiniMarkdown
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Route {
    data object Chat : Route
    data object Tasks : Route
    data object Hub : Route
    data object Tools : Route
    data object Settings : Route
    data object Cockpit : Route
    data object Conversations : Route
    data object Onboarding : Route
    data object Memory : Route
    data class Job(val id: String) : Route
    data class Command(val id: String) : Route
    data class PipelineEdit(val id: String?) : Route
    data object MailAdd : Route
    data object Ai : Route
    data object Accounts : Route
    data object Browser : Route
    data object Phone : Route
    data object Live : Route
    data object Pc : Route
}

enum class Mode(val label: String) { AUTO("자동"), QUICK("대화"), MISSION("작업 맡기기") }

/** UI ↔ 엔진 접점. 화면은 이 객체만 안다(도구를 직접 호출하지 않는다). */
class MainVm(val h: Hjtk) {
    val store = h.store
    val runner = h.runner
    val route = MutableStateFlow<Route>(if (store.settings.value.onboarded) Route.Chat else Route.Onboarding)
    private val back = ArrayDeque<Route>()
    val convId = MutableStateFlow((store.conversations.value.firstOrNull()?.id ?: store.newConversation().id).also { store.ensureLoaded(it) })
    val mode = MutableStateFlow(if (store.settings.value.defaultMission) Mode.MISSION else Mode.AUTO)
    val draft = MutableStateFlow("")
    val paletteOpen = MutableStateFlow(false)

    fun go(r: Route) { if (r != route.value) { if (r !in listOf(Route.Chat, Route.Tasks, Route.Hub, Route.Tools)) back.addLast(route.value) else back.clear(); route.value = r } }
    fun back(): Boolean { val r = back.removeLastOrNull() ?: return false; route.value = r; return true }
    fun tab(r: Route) { back.clear(); route.value = r }

    fun openConversation(id: String) { store.ensureLoaded(id); convId.value = id; tab(Route.Chat) }
    fun newChat() { val c = store.newConversation(); convId.value = c.id; tab(Route.Chat) }

    val pickerOpen = MutableStateFlow(false)
    val difficulty = MutableStateFlow(h.ai.state.value.difficulty)

    fun send(text0: String) {
        val text = text0.trim(); if (text.isEmpty()) return
        draft.value = ""
        val cid = convId.value
        val forced = when (mode.value) { Mode.AUTO -> null; Mode.QUICK -> false; Mode.MISSION -> true }
        when (val r = InputRouter.route(text, store.customs.value, forced)) {
            is InputRouter.Route.Direct -> { store.msg(cid, Role.USER, text); runner.runDirect(cid, r.call) }
            is InputRouter.Route.Custom -> {
                val goal = (r.cmd.expandsTo + " " + r.rest).trim()
                store.msg(cid, Role.USER, text, meta = mapOf("expanded" to goal))
                if (r.cmd.asMission) runner.startMission(cid, goal) else runner.quickAsk(cid, goal)
            }
            is InputRouter.Route.Mission -> {
                store.msg(cid, Role.USER, text, meta = mapOf("mission" to "true"))
                val sk = Regex("^\\[([^]]+)]\\s*").find(r.goal)
                runner.startMission(cid, if (sk != null) r.goal.removeRange(sk.range).ifBlank { r.goal } else r.goal, sk?.groupValues?.get(1), difficulty.value)
            }
            is InputRouter.Route.Quick -> { store.msg(cid, Role.USER, text); runner.quickAsk(cid, r.text) }
        }
    }

    /** 오류 카드의 '다시 보내기': 마지막 질문을 같은 대화에 다시 보낸다(사용자 말풍선은 중복으로 만들지 않는다) */
    fun retryLast() {
        val cid = convId.value
        if (cid in runner.busyQuick.value) return
        store.ensureLoaded(cid)
        val last = store.messages.value[cid]?.lastOrNull { it.role == Role.USER } ?: return
        val text = last.meta["expanded"] ?: last.text
        if (last.meta["mission"] == "true") runner.startMission(cid, text) else runner.quickAsk(cid, text)
    }

    /** 답을 기억에 저장(다음 작업부터 DeepSeek에게 맥락으로 전달) */
    fun remember(text: String) {
        val m = com.ht.hjtk.core.Memory(store.id("mem"), text.take(600), "대화", System.currentTimeMillis())
        store.memories.update { it + m }; store.persistHub()
    }

    fun runCommand(call: CommandCall) { store.msg(convId.value, Role.USER, "/${call.cmd} " + call.args.values.joinToString(" ")); runner.runDirect(convId.value, call); tab(Route.Chat) }
    fun runPipeline(p: Pipeline, input: String) { store.msg(convId.value, Role.USER, "▶ ${p.name} ${input}"); runner.runPipeline(convId.value, p, input); tab(Route.Chat) }

    fun approve(pid: String, ok: Boolean) = runner.resolve(pid, if (ok) "approve" else "사용자 거절")
    fun answer(pid: String, a: String) = runner.resolve(pid, a)

    fun completeOnboarding() { store.updateSettings { it.copy(onboarded = true) }; tab(Route.Chat) }

    // ── 내보내기(기본 마스킹) ───────────────────────────────────
    fun export(ctx: Context, c: Conversation, fmt: String, mask: Boolean = true) {
        store.ensureLoaded(c.id)
        val msgs = store.messages.value[c.id] ?: emptyList()
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
        fun m(s: String) = if (mask) Masker.mask(s) else s
        val (name, body, mime) = when (fmt) {
            "json" -> Triple("${c.title.take(20)}.json", JSONObject().put("schema", "hjtk.conversation.v1").put("conversation", c.toJson())
                .put("messages", JSONArray(msgs.map { JSONObject(m(it.toJson().toString())) }))
                .put("jobs", JSONArray(store.jobs.value.filter { it.convId == c.id }.map { it.toJson() })).toString(2), "application/json")
            "html" -> Triple("${c.title.take(20)}.html", MiniMarkdown.page(c.title, m(markdown(c, msgs, df))), "text/html")
            else -> Triple("${c.title.take(20)}.md", m(markdown(c, msgs, df)), "text/markdown")
        }
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_")); f.writeText(body)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "대화 내보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun markdown(c: Conversation, msgs: List<Msg>, df: SimpleDateFormat) = buildString {
        appendLine("# ${c.title}"); appendLine(); appendLine("> HJTK 대화 기록 · ${df.format(Date(c.createdAt))} ~ ${df.format(Date(c.updatedAt))}"); appendLine()
        msgs.forEach { x ->
            when (x.kind) {
                "cmd" -> appendLine("- `${x.meta["cmd"]}` ${if (x.meta["ok"] == "true") "✓" else "✗"} ${x.meta["args"] ?: ""}")
                "report" -> { appendLine(); appendLine("**보고 #${x.meta["n"]}** (${df.format(Date(x.at))})"); x.text.lines().forEach { appendLine("> $it") }; appendLine() }
                "approval" -> appendLine("- 승인 요청: ${x.text.lines().first()} → **${x.meta["status"]}**")
                "final" -> { appendLine(); appendLine("## 결과"); appendLine(x.text); appendLine() }
                else -> { appendLine(); appendLine("**${when (x.role) { Role.USER -> "나"; Role.AI -> "AI"; Role.SYSTEM -> "HJTK" }}** · ${df.format(Date(x.at))}"); appendLine(x.text) }
            }
        }
    }

    fun openFile(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val type = when (f.extension.lowercase()) { "html" -> "text/html"; "md" -> "text/markdown"; "csv" -> "text/csv"; "png" -> "image/png"; "json" -> "application/json"; else -> "text/plain" }
        runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun addCustom(trigger: String, expands: String, mission: Boolean) {
        val t = if (trigger.startsWith("/")) trigger else "/$trigger"
        store.customs.value = store.customs.value + CustomCommand(store.id("u"), t, expands, mission); store.persistHub()
    }
}
