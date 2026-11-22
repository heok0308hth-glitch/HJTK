package com.hjtk.app.core

import com.hjtk.app.core.memory.MemoryStore
import com.hjtk.app.core.skill.PipelineStore
import com.hjtk.app.core.skill.SkillStore
import com.hjtk.app.core.hub.HubStore
import com.hjtk.app.core.session.SessionStore

/** Wall clock, injectable so tests do not sleep. */
interface Clock {
    fun nowMs(): Long
}

/** Everything a command may touch outside the pure engine. */
interface FilePort {
    /** Writes a UTF-8 export into the app's own exports folder. Returns a display name. */
    suspend fun saveExport(name: String, content: String, mime: String = "text/plain"): String
    suspend fun listExports(): List<ExportFile>
    suspend fun openExportsFolder()
    suspend fun shareExport(name: String)
}

data class ExportFile(val name: String, val sizeBytes: Long, val modifiedAt: Long)

data class SearchHit(val title: String, val url: String, val snippet: String)

interface WebPort {
    suspend fun search(query: String, limit: Int = 5): List<SearchHit>
    suspend fun news(query: String, limit: Int = 5): List<SearchHit>
    /** Short encyclopedic summary of a topic, or null when there is no page. */
    suspend fun summary(topic: String): SearchHit?
    /** Plain-text body of a page, trimmed for prompting. */
    suspend fun fetchText(url: String, maxChars: Int = 4000): String
    suspend fun openUrl(url: String)
}

/**
 * The door between a command and the orchestrator. A command can ask for a
 * multi-step plan to be queued without knowing how plans are executed.
 */
interface PlanGateway {
    suspend fun submit(steps: List<com.hjtk.app.core.model.SkillStep>, title: String): String
    fun status(): String
    fun stop(): Boolean
    fun isRunning(): Boolean
}

data class MailSummary(val from: String, val subject: String, val date: String, val preview: String)

interface MailPort {
    suspend fun unreadCount(): Int
    suspend fun latest(limit: Int): List<MailSummary>
    suspend fun send(to: String, subject: String, body: String)
    suspend fun composeDraft(to: String, subject: String, body: String)
    fun isConfigured(): Boolean
    fun providerName(): String
}

data class CalendarItem(val title: String, val start: String, val end: String, val location: String)

interface CalendarPort {
    fun canRead(): Boolean
    suspend fun today(): List<CalendarItem>
    /** Inserts through the platform calendar UI (no calendar permission needed). */
    suspend fun insert(title: String, startMs: Long, endMs: Long, location: String): String
}

interface AppPort {
    suspend fun launch(query: String): Boolean
    suspend fun installed(): List<Pair<String, String>>
    suspend fun shareText(text: String, chooserTitle: String)
    suspend fun openCustomTab(url: String)
    suspend fun dial(number: String)
    suspend fun sms(number: String, body: String)
    suspend fun openMap(destination: String)
    suspend fun openStoreSearch(query: String)
}

interface ClipboardPort {
    suspend fun read(): String
    suspend fun copy(text: String)
}

interface NotifyPort {
    suspend fun show(title: String, body: String)
    suspend fun scheduleTimer(label: String, seconds: Int)
    suspend fun setAlarm(label: String, hour: Int, minute: Int)
    fun canPost(): Boolean
}

interface DevicePort {
    suspend fun battery(): String
    suspend fun storage(): String
    suspend fun network(): String
    suspend fun setVolume(percent: Int): String
    suspend fun setBrightness(percent: Int): String
    suspend fun torch(on: Boolean): String
    suspend fun openSettings(section: String)
}

data class AiAnswer(val text: String, val carrierId: String, val carrierLabel: String, val tookMs: Long)

data class CarrierInfo(
    val id: String,
    val label: String,
    val available: Boolean,
    val requiresKey: Boolean,
    val note: String
)

interface AiPort {
    suspend fun ask(prompt: String, preferredCarrier: String? = null): AiAnswer
    fun statusLine(): String
    fun isConfigured(): Boolean
    fun carriers(): List<CarrierInfo>
}

/** Read-only view of the engine's health for the diagnostics screen. */
interface DiagPort {
    fun snapshot(): String
}

/**
 * The single object a command receives. Pure-Kotlin stores are shared so the
 * engine can be tested on the JVM with fakes for the Android-bound ports.
 */
class HostPorts(
    val clock: Clock,
    val files: FilePort,
    val web: WebPort,
    val mail: MailPort,
    val calendar: CalendarPort,
    val apps: AppPort,
    val clipboard: ClipboardPort,
    val notify: NotifyPort,
    val device: DevicePort,
    val ai: AiPort,
    val diag: DiagPort,
    val memory: MemoryStore,
    val skills: SkillStore,
    val pipelines: PipelineStore,
    val hub: HubStore,
    val sessions: SessionStore,
    val plans: PlanGateway,
    /** Speaks a line aloud (TTS). Optional so JVM fakes can ignore it. */
    val speak: suspend (String) -> Unit = {}
)
