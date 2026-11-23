package com.ht.hjtk.engine

import android.content.Context
import android.webkit.CookieManager
import com.ht.hjtk.core.ai.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 계정 하나로 전부 — 구글(또는 네이버)에 한 번 로그인하면:
 *  1) 같은 쿠키를 쓰는 HJTK의 모든 웹 화면이 그 계정으로 열린다(Gemini · Gmail · 드라이브 · Keep · 캘린더 · 유튜브 · 네이버 메일·메모…)
 *  2) Claude · ChatGPT · Grok은 'Google로 계속'을 HJTK가 대신 눌러 이어서 로그인한다
 *  3) AI가 메일·메모·드라이브를 볼 때는 로그인된 Gemini의 Google 앱 연결(@Gmail 등)을 쓴다 — 키·앱 비밀번호 없이
 */
object AccountHub {
    data class Step(val provider: Provider, val state: String = "대기", val done: Boolean = false)
    val google = MutableStateFlow(false)
    val naver = MutableStateFlow(false)
    val steps = MutableStateFlow<List<Step>>(emptyList())
    val running = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    const val GOOGLE_LOGIN = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmyaccount.google.com%2F"
    const val NAVER_LOGIN = "https://nid.naver.com/nidlogin.login?url=https%3A%2F%2Fm.naver.com"
    private val loginUrls = mapOf(
        Provider.GEMINI to "https://gemini.google.com/app",
        Provider.CLAUDE to "https://claude.ai/login",
        Provider.GPT to "https://chatgpt.com/auth/login",
        Provider.GROK to "https://accounts.x.ai/sign-in?redirect=grok-com",
    )

    /** 쿠키로 로그인 여부를 본다(구글 SID · 네이버 NID_AUT) */
    fun refresh() {
        val cm = CookieManager.getInstance()
        google.value = (cm.getCookie("https://accounts.google.com") ?: "").let { it.contains("SID=") || it.contains("__Secure-3PSID") }
        naver.value = (cm.getCookie("https://nid.naver.com") ?: "").contains("NID_AUT")
    }

    /** 구글 로그인 뒤: 네 회사 AI를 차례로 이어서 연결 */
    fun connectAll() {
        if (running.value) return
        job = scope.launch {
            running.value = true
            val order = listOf(Provider.GEMINI, Provider.CLAUDE, Provider.GPT, Provider.GROK)
            steps.value = order.map { Step(it) }
            for (p in order) {
                val port = WebAiHub.port(p) ?: continue
                fun mark(s: String, done: Boolean = false) = steps.update { l -> l.map { if (it.provider == p) it.copy(state = s, done = done) else it } }
                mark("연결하는 중")
                val ok = runCatching { port.ssoConnect(loginUrls.getValue(p)) { mark(it) } }.getOrDefault(false)
                mark(if (ok) "연결됨" else "직접 확인이 필요해요(탭해서 열기)", ok)
                port.park()
            }
            running.value = false
        }
    }

    fun cancel() { job?.cancel(); running.value = false }

    /** 구글 앱(메일·드라이브·Keep·캘린더·문서)에 묻기: 로그인된 Gemini의 Google 앱 연결로 */
    fun googlePrompt(app: String, q: String): String {
        val tag = when (app.lowercase()) { "gmail", "mail", "메일" -> "@Gmail"; "drive", "드라이브" -> "@Google Drive"; "keep", "메모" -> "@Google Keep"; "calendar", "캘린더", "일정" -> "@Google Calendar"; "docs", "문서" -> "@Google Docs"; "youtube", "유튜브" -> "@YouTube"; "maps", "지도" -> "@Google Maps"; else -> "@Gmail" }
        return "$tag $q\n(내 계정의 실제 내용만 근거로, 찾은 것을 목록으로 짧게 정리해 줘. 없으면 없다고.)"
    }

    @Suppress("unused") fun install(c: Context) { refresh() }
}
