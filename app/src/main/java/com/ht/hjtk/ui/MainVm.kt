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
    data object Routines : Route
    data object Usage : Route
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
    /** 입력창에 붙인 사진(경로) — 보내면 비운다 */
    val pendingImage = MutableStateFlow<String?>(null)
    /** 다이얼에서 'AI에게'로 넘어온 번호 */
    val phonePrefill = MutableStateFlow("")
    /** HJTK 안 브라우저로 열 주소 */
    val webUrl = MutableStateFlow("")
    fun openWeb(url: String) { webUrl.value = url; go(Route.Browser) }
    val difficulty = MutableStateFlow(h.ai.state.value.difficulty)

    fun send(text0: String) {
        val text = text0.trim()
        val img = pendingImage.value
        if (text.isEmpty() && img == null) return
        draft.value = ""
        val cid = convId.value
        if (img != null) {
            pendingImage.value = null
            store.msg(cid, Role.USER, text, meta = mapOf("image" to img))
            runner.quickAsk(cid, text, img); return
        }
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
    /** 사진 첨부: 고른 사진을 작업공간에 줄여서 복사(긴 변 1280 · JPEG) → 토큰·전송량 절약 */
    fun attachImage(uri: android.net.Uri) {
        runCatching {
            val ctx = h.app
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1; while (maxOf(opts.outWidth, opts.outHeight) / sample > 2560) sample *= 2
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return
            val sc = 1280f / maxOf(bmp.width, bmp.height)
            val out = if (sc < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true) else bmp
            val f = java.io.File(java.io.File(ctx.filesDir, "workspace/images").apply { mkdirs() }, "img_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
            pendingImage.value = f.absolutePath
        }
    }

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
        toast(ctx, saveToDownloads(ctx, f, mime))
    }

    /** 다른 앱으로 넘기지 않고 '다운로드/HJTK'에 저장한다 */
    fun saveToDownloads(ctx: Context, f: File, mime: String): String = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val v = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, f.name); put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/HJTK")
            }
            val u = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("저장 위치를 만들 수 없어요")
            ctx.contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            "다운로드/HJTK에 저장했어요 · ${f.name}"
        } else {
            val d = File(ctx.getExternalFilesDir(null), "HJTK").apply { mkdirs() }; f.copyTo(File(d, f.name), overwrite = true)
            "앱 폴더(Android/data/com.ht.hjtk/files/HJTK)에 저장했어요 · ${f.name}"
        }
    }.getOrElse { "저장하지 못했어요: ${it.message}" }

    private fun toast(ctx: Context, s: String) = android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_LONG).show()

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
        val type = when (f.extension.lowercase()) { "html" -> "text/html"; "md" -> "text/markdown"; "csv" -> "text/csv"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "json" -> "application/json"; else -> "text/plain" }
        toast(ctx, saveToDownloads(ctx, f, type))
    }

    fun addCustom(trigger: String, expands: String, mission: Boolean) {
        val t = if (trigger.startsWith("/")) trigger else "/$trigger"
        store.customs.value = store.customs.value + CustomCommand(store.id("u"), t, expands, mission); store.persistHub()
    }
}
