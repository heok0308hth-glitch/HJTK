package com.hjtk.app.android

import android.content.Context
import com.hjtk.app.android.carrier.CarrierManager
import com.hjtk.app.android.carrier.DeepSeekApiCarrier
import com.hjtk.app.android.carrier.GeminiApiCarrier
import com.hjtk.app.android.ports.AppPortImpl
import com.hjtk.app.android.ports.CalendarPortImpl
import com.hjtk.app.android.ports.ClipboardPortImpl
import com.hjtk.app.android.ports.DevicePortImpl
import com.hjtk.app.android.ports.FilePortImpl
import com.hjtk.app.android.ports.MailPortImpl
import com.hjtk.app.android.ports.NotifyPortImpl
import com.hjtk.app.android.ports.WebPortImpl
import com.hjtk.app.android.prefs.Settings
import com.hjtk.app.core.Clock
import com.hjtk.app.core.DiagPort
import com.hjtk.app.core.HostPorts
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
import com.hjtk.app.core.storage.FileTextStorage
import com.hjtk.app.core.util.TimeFmt
import java.io.File

/**
 * Composition root. One instance per process; screens take what they need.
 *
 * Everything here is constructed once and shared: stores, journal, registry,
 * carriers, ports and the orchestrator. Nothing is a global mutable singleton
 * beyond this object, which keeps tests free to build their own instances.
 */
class Engine private constructor(context: Context) {

    val appContext: Context = context.applicationContext
    val settings = Settings(appContext)
    val clock: Clock = object : Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }

    private val storage = FileTextStorage(File(appContext.filesDir, "store"))
    val memory = MemoryStore(storage, clock)
    val skills = SkillStore(storage, clock)
    val pipelines = PipelineStore(storage, clock)
    val hub = HubStore(storage, clock)
    val sessions = SessionStore(storage, clock)
    val journal = Journal(storage, clock)
    val eta = EtaEstimator(storage, clock)
    private val checkpoints = CheckpointStore(storage)
    private val archive = com.hjtk.app.core.plan.PlanArchive(storage)
    val registry: CommandRegistry = CommandCatalog.build()
    val compiler = PlanCompiler(registry, eta, clock)
    val router = NluRouter(registry, skills, pipelines)
    val carriers = CarrierManager(
        listOf(DeepSeekApiCarrier(settings), GeminiApiCarrier(settings)) +
            (extraCarrierFactory?.invoke(appContext, settings) ?: emptyList()),
        settings,
        clock
    )

    val executor = Executor(registry, journal, eta, compiler, checkpoints, clock)

    val orchestrator = Orchestrator(
        registry = registry,
        router = router,
        compiler = compiler,
        executor = executor,
        journal = journal,
        checkpoints = checkpoints,
        archive = archive,
        sessions = sessions,
        pipelines = pipelines,
        clock = clock,
        reportMinutes = { settings.reportMinutes },
        reportsEnabled = { settings.reportsEnabled }
    )

    private val tts = TtsSpeaker(appContext)
    private var approvalWatch: ApprovalNotifier? = null

    val ports: HostPorts

    init {
        skills.installBuiltIns(BuiltinSkills.all)
        if (pipelines.all().isEmpty()) {
            BuiltinSkills.pipelines.forEach { bp ->
                pipelines.create(bp.name, bp.description, bp.steps, bp.params)
            }
        }
        val notify = NotifyPortImpl(appContext)
        val device = DevicePortImpl(appContext, settings)
        ports = HostPorts(
            clock = clock,
            files = FilePortImpl(appContext),
            web = WebPortImpl(appContext),
            mail = MailPortImpl(appContext, settings),
            calendar = CalendarPortImpl(appContext),
            apps = AppPortImpl(appContext),
            clipboard = ClipboardPortImpl(appContext),
            notify = notify,
            device = device,
            ai = carriers,
            diag = object : DiagPort {
                override fun snapshot(): String = diagnostics()
            },
            memory = memory,
            skills = skills,
            pipelines = pipelines,
            hub = hub,
            sessions = sessions,
            plans = orchestrator,
            speak = { text -> tts.speak(text) }
        )
        orchestrator.attachPorts(ports)
        orchestrator.restoreConversation()
        approvalWatch = ApprovalNotifier(appContext, orchestrator)
    }

    fun diagnosticsForUi(): String = diagnostics()

    private fun diagnostics(): String {
        val verify = journal.verify()
        val counts = journal.countByKind()
        return buildString {
            appendLine("**HJTK 진단**")
            appendLine("· 명령 ${registry.size()}개 / 카테고리 ${registry.byCategory().size}개")
            appendLine("· 저널 ${verify.records}건 · 무결성 ${if (verify.ok) "정상" else "손상(${verify.reason})"}")
            appendLine("· 계획 시작 ${counts["plan_started"] ?: 0} / 완료 ${counts["plan_finished"] ?: 0} / 승인 ${counts["approval"] ?: 0}")
            appendLine("· 기억 ${memory.size()}건 · 스킬 ${skills.all().size}개 · 파이프라인 ${pipelines.all().size}개")
            appendLine("· AI: ${carriers.statusLine()}")
            appendLine("· 메일: ${if (ports.mail.isConfigured()) ports.mail.providerName() else "미연결"}")
            appendLine("· 캘린더 읽기: ${if (ports.calendar.canRead()) "허용" else "권한 없음"}")
            appendLine("· 알림: ${if (ports.notify.canPost()) "허용" else "차단"}")
            appendLine("· 마지막 갱신 ${TimeFmt.stamp(clock.nowMs())}")
        }.trim()
    }

    companion object {
        /**
         * Extension point for builds that add carriers (for example a WebView
         * session carrier). The open build leaves it null; nothing else changes.
         */
        @Volatile
        var extraCarrierFactory: ((Context, Settings) -> List<com.hjtk.app.android.carrier.AiCarrier>)? = null

        @Volatile
        private var instance: Engine? = null

        fun of(context: Context): Engine =
            instance ?: synchronized(this) {
                instance ?: Engine(context.applicationContext).also { instance = it }
            }
    }
}
