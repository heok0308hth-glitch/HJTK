package com.hjtk.app

import com.hjtk.app.core.AiAnswer
import com.hjtk.app.core.AiPort
import com.hjtk.app.core.AppPort
import com.hjtk.app.core.CalendarItem
import com.hjtk.app.core.CalendarPort
import com.hjtk.app.core.CarrierInfo
import com.hjtk.app.core.ClipboardPort
import com.hjtk.app.core.Clock
import com.hjtk.app.core.DevicePort
import com.hjtk.app.core.DiagPort
import com.hjtk.app.core.ExportFile
import com.hjtk.app.core.FilePort
import com.hjtk.app.core.HostPorts
import com.hjtk.app.core.MailPort
import com.hjtk.app.core.MailSummary
import com.hjtk.app.core.NotifyPort
import com.hjtk.app.core.PlanGateway
import com.hjtk.app.core.SearchHit
import com.hjtk.app.core.model.SkillStep
import com.hjtk.app.core.WebPort
import com.hjtk.app.core.command.CommandCatalog
import com.hjtk.app.core.command.CommandRegistry
import com.hjtk.app.core.hub.HubStore
import com.hjtk.app.core.memory.MemoryStore
import com.hjtk.app.core.nlu.NluRouter
import com.hjtk.app.core.orchestrator.Orchestrator
import com.hjtk.app.core.plan.CheckpointStore
import com.hjtk.app.core.plan.EtaEstimator
import com.hjtk.app.core.plan.Executor
import com.hjtk.app.core.plan.Journal
import com.hjtk.app.core.plan.PlanCompiler
import com.hjtk.app.core.session.SessionStore
import com.hjtk.app.core.skill.BuiltinSkills
import com.hjtk.app.core.skill.PipelineStore
import com.hjtk.app.core.skill.SkillStore
import com.hjtk.app.core.storage.MemoryTextStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FixedClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMs(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

/**
 * A complete engine wired to in-memory fakes. Every port records what it was
 * asked to do, so tests assert on effects rather than on log strings.
 */
class TestWorld(val clock: FixedClock = FixedClock()) {

    val storage = MemoryTextStorage()
    val memory = MemoryStore(storage, clock)
    val skills = SkillStore(storage, clock)
    val pipelines = PipelineStore(storage, clock)
    val hub = HubStore(storage, clock)
    val sessions = SessionStore(storage, clock)
    val journal = Journal(storage, clock)
    val eta = EtaEstimator(storage, clock)
    val checkpoints = CheckpointStore(storage)
    val registry: CommandRegistry = CommandCatalog.build()
    val compiler = PlanCompiler(registry, eta, clock)

    val exported = mutableListOf<Pair<String, String>>()
    val shared = mutableListOf<String>()
    val openedUrls = mutableListOf<String>()
    val notifications = mutableListOf<Pair<String, String>>()
    val alarms = mutableListOf<Pair<Int, Int>>()
    val mailSent = mutableListOf<Triple<String, String, String>>()
    val calendarInserted = mutableListOf<String>()
    val volumeSet = mutableListOf<Int>()
    val clipboard = StringBuilder()
    val mentionedApps = mutableListOf<String>()
    var aiAnswer = "가짜 응답"
    var aiFails = false
    var mailConfigured = true
    var unread = 3
    var calendarPermission = true

    val files = object : FilePort {
        override suspend fun saveExport(name: String, content: String, mime: String): String {
            exported.add(name to content)
            return name
        }
        override suspend fun listExports(): List<ExportFile> = exported.map { ExportFile(it.first, it.second.length.toLong(), clock.nowMs()) }
        override suspend fun openExportsFolder() { shared.add("open-folder") }
        override suspend fun shareExport(name: String) { shared.add("share:$name") }
    }

    val web = object : WebPort {
        override suspend fun search(query: String, limit: Int): List<SearchHit> =
            listOf(SearchHit("결과1: $query", "https://example.com/1", "요약1"), SearchHit("결과2", "https://example.com/2", "요약2"))
        override suspend fun news(query: String, limit: Int): List<SearchHit> =
            listOf(SearchHit("뉴스: $query", "https://news.example.com/1", "짧은 설명"))
        override suspend fun summary(topic: String): SearchHit = SearchHit(topic, "https://ko.wikipedia.org/wiki/$topic", "$topic 요약")
        override suspend fun fetchText(url: String, maxChars: Int): String = "본문 텍스트"
        override suspend fun openUrl(url: String) { openedUrls.add(url) }
    }

    val mail = object : MailPort {
        override suspend fun unreadCount(): Int = unread
        override suspend fun latest(limit: Int): List<MailSummary> = listOf(MailSummary("보낸이", "제목", "1/1", "미리보기"))
        override suspend fun send(to: String, subject: String, body: String) { mailSent.add(Triple(to, subject, body)) }
        override suspend fun composeDraft(to: String, subject: String, body: String) { shared.add("compose:$to") }
        override fun isConfigured(): Boolean = mailConfigured
        override fun providerName(): String = "테스트 메일"
    }

    val calendar = object : CalendarPort {
        override fun canRead(): Boolean = calendarPermission
        override suspend fun today(): List<CalendarItem> = listOf(CalendarItem("회의", "09:00", "10:00", "회의실"))
        override suspend fun insert(title: String, startMs: Long, endMs: Long, location: String): String {
            calendarInserted.add(title)
            return "ok"
        }
    }

    val apps = object : AppPort {
        override suspend fun launch(query: String): Boolean {
            mentionedApps.add(query)
            return query != "없는앱"
        }
        override suspend fun installed(): List<Pair<String, String>> = listOf("카카오톡" to "com.kakao.talk")
        override suspend fun shareText(text: String, chooserTitle: String) { shared.add("share-text:$text") }
        override suspend fun openCustomTab(url: String) { openedUrls.add(url) }
        override suspend fun dial(number: String) { shared.add("dial:$number") }
        override suspend fun sms(number: String, body: String) { shared.add("sms:$number") }
        override suspend fun openMap(destination: String) { shared.add("map:$destination") }
        override suspend fun openStoreSearch(query: String) { shared.add("store:$query") }
    }

    val clips = object : ClipboardPort {
        override suspend fun read(): String = clipboard.toString()
        override suspend fun copy(text: String) { clipboard.clear(); clipboard.append(text) }
    }

    val notify = object : NotifyPort {
        override suspend fun show(title: String, body: String) { notifications.add(title to body) }
        override suspend fun scheduleTimer(label: String, seconds: Int) { notifications.add(label to "$seconds") }
        override suspend fun setAlarm(label: String, hour: Int, minute: Int) { alarms.add(hour to minute) }
        override fun canPost(): Boolean = true
    }

    val device = object : DevicePort {
        override suspend fun battery(): String = "배터리 80% · 충전 중"
        override suspend fun storage(): String = "저장 공간 50%"
        override suspend fun network(): String = "Wi-Fi · 인터넷 가능"
        override suspend fun setVolume(percent: Int): String {
            volumeSet.add(percent)
            return "볼륨 $percent%"
        }
        override suspend fun setBrightness(percent: Int): String = "밝기 $percent%"
        override suspend fun torch(on: Boolean): String = "손전등 ${if (on) "켬" else "끔"}"
        override suspend fun openSettings(section: String) { shared.add("settings:$section") }
    }

    val ai = object : AiPort {
        override suspend fun ask(prompt: String, preferredCarrier: String?): AiAnswer {
            if (aiFails) error("캐리어 없음")
            return AiAnswer(aiAnswer, preferredCarrier ?: "deepseek", "테스트 AI", 12)
        }
        override fun statusLine(): String = "테스트 AI"
        override fun isConfigured(): Boolean = true
        override fun carriers(): List<CarrierInfo> = listOf(CarrierInfo("deepseek", "테스트 AI", true, false, ""))
    }

    val diag = object : DiagPort {
        override fun snapshot(): String = "진단 스냅샷"
    }

    lateinit var ports: HostPorts
    lateinit var router: NluRouter
    lateinit var executor: Executor
    lateinit var orchestrator: Orchestrator

    init {
        skills.installBuiltIns(BuiltinSkills.all)
        BuiltinSkills.pipelines.forEach { bp -> pipelines.create(bp.name, bp.description, bp.steps, bp.params) }
        router = NluRouter(registry, skills, pipelines)
        executor = Executor(registry, journal, eta, compiler, checkpoints, clock)
        orchestrator = Orchestrator(
            registry = registry,
            router = router,
            compiler = compiler,
            executor = executor,
            journal = journal,
            checkpoints = checkpoints,
            sessions = sessions,
            pipelines = pipelines,
            clock = clock,
            reportMinutes = { 5 },
            maxConcurrent = 3,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
        ports = HostPorts(
            clock = clock,
            files = files,
            web = web,
            mail = mail,
            calendar = calendar,
            apps = apps,
            clipboard = clips,
            notify = notify,
            device = device,
            ai = ai,
            diag = diag,
            memory = memory,
            skills = skills,
            pipelines = pipelines,
            hub = hub,
            sessions = sessions,
            plans = orchestrator,
            speak = { shared.add("speak:$it") }
        )
        orchestrator.attachPorts(ports)
    }
}

/** Polls until [condition] holds; fails the test with [message] on timeout. */
fun awaitUntil(timeoutMs: Long = 4_000, message: String = "조건이 시간 안에 만족되지 않음", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(10)
    }
    if (!condition()) throw AssertionError("$message (${timeoutMs}ms)")
}
