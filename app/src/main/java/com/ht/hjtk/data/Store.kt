package com.ht.hjtk.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ht.hjtk.core.CustomCommand
import com.ht.hjtk.core.Memory
import com.ht.hjtk.core.Pipeline
import com.ht.hjtk.core.Skill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class Role { USER, AI, SYSTEM }

/** kind: text · cmd · report · approval · ask · plan · final · note · error · takeover */
data class Msg(
    val id: String, val convId: String, val role: Role, val text: String, val at: Long,
    val kind: String = "text", val meta: Map<String, String> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("c", convId).put("r", role.name).put("t", text).put("at", at).put("k", kind).put("m", JSONObject(meta as Map<*, *>))
    companion object {
        fun from(o: JSONObject) = Msg(o.getString("id"), o.getString("c"), Role.valueOf(o.getString("r")), o.optString("t"), o.optLong("at"), o.optString("k", "text"),
            o.optJSONObject("m")?.let { m -> m.keys().asSequence().associateWith { m.optString(it) } } ?: emptyMap())
    }
}

data class Conversation(val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val pinned: Boolean = false, val dsSession: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("ca", createdAt).put("ua", updatedAt).put("pin", pinned).put("ds", dsSession ?: "")
    companion object { fun from(o: JSONObject) = Conversation(o.getString("id"), o.optString("title"), o.optLong("ca"), o.optLong("ua"), o.optBoolean("pin"), o.optString("ds").ifBlank { null }) }
}

data class JobRec(
    val id: String, val convId: String, val goal: String, val state: String, val createdAt: Long, val updatedAt: Long,
    val progress: Double = 0.0, val eta: String = "", val next: String = "", val now: String = "", val plan: String = "[]",
    val via: String = "", val final: String = "", val label: String = "", val reports: Int = 0, val cmds: Int = 0, val dsSession: String? = null,
    val lastPrompt: String = "",
    /** 2.0: 난이도 · 팀(역할=이름 줄들) · 토큰 추정 */
    val diff: String = "STANDARD", val team: String = "", val tokens: Int = 0,
    /** 3.5: 프로젝트(회사 모드) 시간 예산(분) */
    val minutes: Int = 0
) {
    val running get() = state == "RUNNING" || state.startsWith("PAUSED")
    fun toJson(): JSONObject = JSONObject().put("id", id).put("c", convId).put("g", goal).put("s", state).put("ca", createdAt).put("ua", updatedAt)
        .put("p", progress).put("eta", eta).put("n", next).put("now", now).put("plan", plan).put("via", via).put("f", final).put("l", label)
        .put("rep", reports).put("cmds", cmds).put("ds", dsSession ?: "").put("lp", lastPrompt).put("diff", diff).put("team", team).put("tok", tokens).put("min", minutes)
    companion object {
        fun from(o: JSONObject) = JobRec(o.getString("id"), o.optString("c"), o.optString("g"), o.optString("s"), o.optLong("ca"), o.optLong("ua"),
            o.optDouble("p", 0.0), o.optString("eta"), o.optString("n"), o.optString("now"), o.optString("plan", "[]"), o.optString("via"), o.optString("f"), o.optString("l"),
            o.optInt("rep"), o.optInt("cmds"), o.optString("ds").ifBlank { null }, o.optString("lp"), o.optString("diff", "STANDARD"), o.optString("team"), o.optInt("tok"), o.optInt("min"))
    }
}

data class Site(val id: String, val name: String, val url: String)
data class HubApp(val pkg: String, val label: String)
data class PinnedFile(val uri: String, val name: String)

data class MailAccount(val id: String, val provider: String, val email: String, val imapHost: String, val imapPort: Int, val smtpHost: String, val smtpPort: Int, val smtpSsl: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("pv", provider).put("e", email).put("ih", imapHost).put("ip", imapPort).put("sh", smtpHost).put("sp", smtpPort).put("ss", smtpSsl)
    companion object { fun from(o: JSONObject) = MailAccount(o.getString("id"), o.optString("pv"), o.optString("e"), o.optString("ih"), o.optInt("ip", 993), o.optString("sh"), o.optInt("sp", 587), o.optBoolean("ss")) }
}

data class Settings(
    val dsMode: String = "web",              // web | app | api
    val geminiMode: String = "app",          // app | web | api
    val dsModel: String = "deepseek-flash",
    val geminiModel: String = "gemini-3.8-flash",
    val petEnabled: Boolean = false,   // 2.0에서 캐릭터 기능 제거 — 저장 호환용으로만 남김
    val reportMinutes: Int = 5,
    val autoAllowE2: Boolean = false,
    val userName: String = "",
    val homeCity: String = "서울",
    val defaultMission: Boolean = false,
    val onboarded: Boolean = false,
    val geminiWaitMin: Int = 10,
    /** 작업 공정의 검토 AI: auto(키 있으면 Gemini, 없으면 DeepSeek 두 번째 창) · gemini · gemini_app(1탭) · deepseek2 · off */
    val collab: String = "auto",
    /** 화면·AI 답 언어: "" 아직 안 고름(첫 실행에 묻는다) · ko · en */
    val lang: String = "",
    /** 3.5: 프로젝트(회사 모드) 기본 시간(분) — 30 · 60 · 120 · 180 */
    val projectMinutes: Int = 60
) {
    fun toJson(): JSONObject = JSONObject().put("dsMode", dsMode).put("gMode", geminiMode).put("dsModel", dsModel).put("gModel", geminiModel).put("pet", petEnabled)
        .put("rep", reportMinutes).put("autoE2", autoAllowE2).put("name", userName).put("city", homeCity).put("defM", defaultMission).put("onb", onboarded).put("gw", geminiWaitMin).put("collab", collab).put("lang", lang).put("projMin", projectMinutes)
    companion object {
        fun from(o: JSONObject) = Settings(o.optString("dsMode", "web"), o.optString("gMode", "app"), o.optString("dsModel", "deepseek-flash"), o.optString("gModel", "gemini-3.8-flash"),
            o.optBoolean("pet", true), o.optInt("rep", 5), o.optBoolean("autoE2"), o.optString("name"), o.optString("city", "서울"), o.optBoolean("defM"), o.optBoolean("onb"), o.optInt("gw", 10), o.optString("collab", "auto"), o.optString("lang", if (o.optBoolean("onb")) "ko" else ""), o.optInt("projMin", 60))
    }
}

/** 로컬 JSON 저장소. 서버 없음. 모든 대화·작업·허브는 기기 안에만 있다. */
class Store(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "hjtk").apply { mkdirs() }
    val workspace = File(ctx.filesDir, "workspace").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.ht.hjtk.engine.CrashLog.handler("store"))
    private val pendingSaves = HashMap<String, Job>()
    val secrets = Secrets(ctx)

    val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val messages = MutableStateFlow<Map<String, List<Msg>>>(emptyMap())
    val jobs = MutableStateFlow<List<JobRec>>(emptyList())
    val sites = MutableStateFlow<List<Site>>(emptyList())
    val apps = MutableStateFlow<List<HubApp>>(emptyList())
    val files = MutableStateFlow<List<PinnedFile>>(emptyList())
    val mail = MutableStateFlow<List<MailAccount>>(emptyList())
    val memories = MutableStateFlow<List<Memory>>(emptyList())
    val pipelines = MutableStateFlow<List<Pipeline>>(emptyList())
    val skills = MutableStateFlow<List<Skill>>(emptyList())
    val customs = MutableStateFlow<List<CustomCommand>>(emptyList())
    val settings = MutableStateFlow(Settings())
    val knownRecipients = MutableStateFlow<Set<String>>(emptySet())
    val deadlines = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    /** 메시지를 디스크에서 읽어 둔 대화 id (init보다 먼저 선언해야 한다) */
    private val loaded = HashSet<String>()

    init { load() }

    private fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()
    private fun arr(name: String): JSONArray = runCatching { JSONArray(read(name) ?: "[]") }.getOrDefault(JSONArray())
    private inline fun <T> list(name: String, f: (JSONObject) -> T): List<T> = arr(name).let { a -> (0 until a.length()).mapNotNull { runCatching { f(a.getJSONObject(it)) }.getOrNull() } }

    private fun load() {
        conversations.value = list("conversations.json") { Conversation.from(it) }
        jobs.value = list("jobs.json") { JobRec.from(it) }
        sites.value = list("sites.json") { Site(it.getString("id"), it.getString("name"), it.getString("url")) }.ifEmpty { defaultSites() }
        apps.value = list("apps.json") { HubApp(it.getString("pkg"), it.getString("label")) }
        files.value = list("files.json") { PinnedFile(it.getString("uri"), it.getString("name")) }
        mail.value = list("mail.json") { MailAccount.from(it) }
        memories.value = list("memories.json") { Memory(it.getString("id"), it.getString("text"), it.optString("tag"), it.optLong("at"), it.optInt("uses")) }
        pipelines.value = list("pipelines.json") { Pipeline.from(it) }.ifEmpty { defaultPipelines() }
        skills.value = list("skills.json") { Skill.from(it) }
        customs.value = list("customs.json") { CustomCommand.from(it) }.ifEmpty { defaultCustoms() }
        knownRecipients.value = list("recipients.json") { it.getString("a") }.toSet()
        deadlines.value = list("deadlines.json") { it.getString("t") to it.getString("d") }
        settings.value = runCatching { Settings.from(JSONObject(read("settings.json") ?: "{}")) }.getOrDefault(Settings())
        // 시작 속도: 최근 대화 3개만 바로 읽고, 나머지는 백그라운드에서 채운다(앱 시작이 대화 수에 비례해 느려지지 않게)
        val recent = conversations.value.sortedByDescending { it.updatedAt }
        recent.take(3).forEach { ensureLoaded(it.id) }
        if (recent.size > 3) scope.launch { recent.drop(3).forEach { ensureLoaded(it.id) } }
    }


    /** 이 대화의 메시지를 디스크에서 읽어 둔다. 쓰기 전에 반드시 호출된다(덜 읽은 목록으로 파일을 덮어쓰지 않게). */
    fun ensureLoaded(convId: String) {
        synchronized(loaded) {
            if (convId in loaded) return
            val disk = list("m_$convId.json") { Msg.from(it) }
            messages.update { m -> m + (convId to (disk + (m[convId] ?: emptyList()).filter { x -> disk.none { it.id == x.id } })) }
            loaded += convId
        }
    }

    private fun save(name: String, content: () -> String) {
        synchronized(pendingSaves) {
            pendingSaves[name]?.cancel()
            pendingSaves[name] = scope.launch {
                delay(250)
                val f = File(dir, name); val tmp = File(dir, "$name.tmp")
                tmp.writeText(content()); tmp.renameTo(f)
            }
        }
    }

    private fun persistConvs() = save("conversations.json") { JSONArray(conversations.value.map { it.toJson() }).toString() }
    private fun persistMsgs(convId: String) = if (synchronized(loaded) { convId !in loaded }) Unit else save("m_$convId.json") { JSONArray((messages.value[convId] ?: emptyList()).map { it.toJson() }).toString() }
    private fun persistJobs() = save("jobs.json") { JSONArray(jobs.value.map { it.toJson() }).toString() }
    fun persistHub() {
        save("sites.json") { JSONArray(sites.value.map { JSONObject().put("id", it.id).put("name", it.name).put("url", it.url) }).toString() }
        save("apps.json") { JSONArray(apps.value.map { JSONObject().put("pkg", it.pkg).put("label", it.label) }).toString() }
        save("files.json") { JSONArray(files.value.map { JSONObject().put("uri", it.uri).put("name", it.name) }).toString() }
        save("mail.json") { JSONArray(mail.value.map { it.toJson() }).toString() }
        save("memories.json") { JSONArray(memories.value.map { JSONObject().put("id", it.id).put("text", it.text).put("tag", it.tag).put("at", it.at).put("uses", it.uses) }).toString() }
        save("pipelines.json") { JSONArray(pipelines.value.map { it.toJson() }).toString() }
        save("skills.json") { JSONArray(skills.value.map { it.toJson() }).toString() }
        save("customs.json") { JSONArray(customs.value.map { it.toJson() }).toString() }
        save("recipients.json") { JSONArray(knownRecipients.value.map { JSONObject().put("a", it) }).toString() }
        save("deadlines.json") { JSONArray(deadlines.value.map { JSONObject().put("t", it.first).put("d", it.second) }).toString() }
    }
    fun updateSettings(f: (Settings) -> Settings) { settings.update(f); save("settings.json") { settings.value.toJson().toString() } }

    // ── 대화 ──────────────────────────────────────────────────────
    fun newConversation(title: String = "새 대화"): Conversation {
        val now = System.currentTimeMillis()
        val c = Conversation("c" + UUID.randomUUID().toString().take(8), title, now, now)
        conversations.update { listOf(c) + it }; messages.update { it + (c.id to emptyList()) }; synchronized(loaded) { loaded += c.id }
        persistConvs(); return c
    }
    fun updateConversation(id: String, f: (Conversation) -> Conversation) { conversations.update { l -> l.map { if (it.id == id) f(it) else it } }; persistConvs() }
    fun deleteConversation(id: String) {
        conversations.update { l -> l.filter { it.id != id } }; messages.update { it - id }
        File(dir, "m_$id.json").delete(); persistConvs()
    }
    fun add(m: Msg) {
        ensureLoaded(m.convId)
        messages.update { it + (m.convId to ((it[m.convId] ?: emptyList()) + m)) }
        updateConversation(m.convId) { c ->
            val title = if (c.title == "새 대화" && m.role == Role.USER) m.text.lines().first().take(28) else c.title
            c.copy(updatedAt = m.at, title = title)
        }
        persistMsgs(m.convId)
    }
    fun updateMsg(convId: String, msgId: String, f: (Msg) -> Msg) {
        ensureLoaded(convId)
        messages.update { it + (convId to (it[convId] ?: emptyList()).map { m -> if (m.id == msgId) f(m) else m }) }
        persistMsgs(convId)
    }
    fun msg(convId: String, role: Role, text: String, kind: String = "text", meta: Map<String, String> = emptyMap()): Msg =
        Msg("m" + UUID.randomUUID().toString().take(10), convId, role, text, System.currentTimeMillis(), kind, meta).also { add(it) }

    // ── 작업 ──────────────────────────────────────────────────────
    fun putJob(j: JobRec) { jobs.update { l -> if (l.any { it.id == j.id }) l.map { if (it.id == j.id) j else it } else listOf(j) + l }; persistJobs() }
    fun job(id: String) = jobs.value.firstOrNull { it.id == id }
    fun updateJob(id: String, f: (JobRec) -> JobRec) { job(id)?.let { putJob(f(it).copy(updatedAt = System.currentTimeMillis())) } }
    fun deleteJob(id: String) { jobs.update { l -> l.filter { it.id != id } }; persistJobs() }
    fun journalFile(jobId: String) = File(dir, "journal_$jobId.jsonl")
    fun jobDir(jobId: String) = File(workspace, jobId).apply { mkdirs() }

    // ── 허브 기본값 ────────────────────────────────────────────────
    private fun defaultSites() = listOf(
        Site("s1", "네이버", "https://www.naver.com"), Site("s2", "구글", "https://www.google.com"),
        Site("s3", "위비티(공모전)", "https://www.wevity.com"), Site("s4", "유튜브", "https://www.youtube.com")
    )
    private fun defaultPipelines() = listOf(
        Pipeline("p_contest", "공모전 레이더", "키워드로 공모전을 찾아 마감순 표로 저장", listOf(
            com.ht.hjtk.core.PipelineStep("cmd", "contest.search", mapOf("q" to "{input}", "n" to "10")),
            com.ht.hjtk.core.PipelineStep("ai", "", emptyMap(), "위 결과를 마감일·주최·상금·링크 표로 정리해줘. 마감 임박 순."),
            com.ht.hjtk.core.PipelineStep("cmd", "file.write", mapOf("name" to "공모전_레이더.md", "content" to "{prev}"))
        )),
        Pipeline("p_brief", "아침 브리핑", "일정·날씨·안 읽은 메일 요약", listOf(
            com.ht.hjtk.core.PipelineStep("cmd", "cal.today", emptyMap()),
            com.ht.hjtk.core.PipelineStep("cmd", "data.weather", mapOf("place" to "{input}")),
            com.ht.hjtk.core.PipelineStep("ai", "", emptyMap(), "위 내용을 5줄 아침 브리핑으로 요약해줘.")
        ))
    )
    private fun defaultCustoms() = listOf(
        CustomCommand("u1", "/아침", "오늘 일정, 날씨, 안 읽은 메일을 모아서 아침 브리핑 해줘", true),
        CustomCommand("u2", "/공모", "내 관심 분야 공모전을 찾아서 마감 순으로 정리하고 지원 준비해줘", true)
    )

    fun id(prefix: String) = prefix + UUID.randomUUID().toString().take(8)
}

/** Android Keystore AES-GCM. API 키·메일 앱 비밀번호는 평문으로 저장하지 않는다. */
class Secrets(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("hjtk_secrets", Context.MODE_PRIVATE)
    private val alias = "hjtk_secrets_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) { prefs.edit().remove(name).apply(); return }
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
            val blob = c.iv + c.doFinal(value.toByteArray())
            prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        }
    }

    fun get(name: String): String? = runCatching {
        val b = Base64.decode(prefs.getString(name, null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
        String(c.doFinal(b.copyOfRange(12, b.size)))
    }.getOrNull()

    fun has(name: String) = prefs.contains(name)
}
