package com.hjtk.app.android.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App settings. Non-secret values live in plain preferences (they are UI
 * state), secrets go through [SecurePrefs]. Every setter publishes a
 * [StateFlow] so Compose screens update without polling.
 */
class Settings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("hjtk_settings", Context.MODE_PRIVATE)
    private val secure = SecurePrefs(context)

    data class Snapshot(
        val themeMode: String,
        val reportMinutes: Int,
        val preferredCarrier: String,
        val developerMode: Boolean,
        val reportsEnabled: Boolean,
        val mailProvider: String,
        val mailAddress: String
    )

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Snapshot> = _state

    private fun read(): Snapshot = Snapshot(
        themeMode = prefs.getString("theme", "system") ?: "system",
        reportMinutes = prefs.getInt("report_minutes", 5).coerceIn(1, 60),
        preferredCarrier = prefs.getString("carrier", "auto") ?: "auto",
        developerMode = prefs.getBoolean("dev_mode", false),
        reportsEnabled = prefs.getBoolean("reports", true),
        mailProvider = prefs.getString("mail_provider", "naver") ?: "naver",
        mailAddress = prefs.getString("mail_address", "") ?: ""
    )

    private fun publish() {
        _state.value = read()
    }

    var themeMode: String
        get() = _state.value.themeMode
        set(v) { prefs.edit().putString("theme", v).apply(); publish() }

    var reportMinutes: Int
        get() = _state.value.reportMinutes
        set(v) { prefs.edit().putInt("report_minutes", v.coerceIn(1, 60)).apply(); publish() }

    var preferredCarrier: String
        get() = _state.value.preferredCarrier
        set(v) { prefs.edit().putString("carrier", v).apply(); publish() }

    var developerMode: Boolean
        get() = _state.value.developerMode
        set(v) { prefs.edit().putBoolean("dev_mode", v).apply(); publish() }

    var reportsEnabled: Boolean
        get() = _state.value.reportsEnabled
        set(v) { prefs.edit().putBoolean("reports", v).apply(); publish() }

    // ---- secrets -------------------------------------------------------

    var deepSeekKey: String?
        get() = secure.get(KEY_DEEPSEEK)
        set(v) { secure.put(KEY_DEEPSEEK, v) }

    var geminiKey: String?
        get() = secure.get(KEY_GEMINI)
        set(v) { secure.put(KEY_GEMINI, v) }

    fun maskedKey(carrier: String): String = secure.masked(if (carrier == "gemini") KEY_GEMINI else KEY_DEEPSEEK)

    var mailAddress: String
        get() = _state.value.mailAddress
        set(v) { prefs.edit().putString("mail_address", v).apply(); publish() }

    var mailProvider: String
        get() = _state.value.mailProvider
        set(v) { prefs.edit().putString("mail_provider", v).apply(); publish() }

    var mailPassword: String?
        get() = secure.get(KEY_MAIL_PW)
        set(v) { secure.put(KEY_MAIL_PW, v) }

    fun hasMailPassword(): Boolean = secure.has(KEY_MAIL_PW)

    fun clearAllSecrets() {
        secure.remove(KEY_DEEPSEEK)
        secure.remove(KEY_GEMINI)
        secure.remove(KEY_MAIL_PW)
    }

    companion object {
        private const val KEY_DEEPSEEK = "deepseek_key"
        private const val KEY_GEMINI = "gemini_key"
        private const val KEY_MAIL_PW = "mail_password"

        // Provider presets, all official public endpoints.
        val MAIL_PRESETS: Map<String, MailPreset> = mapOf(
            "naver" to MailPreset("네이버", "imap.naver.com", 993, "smtp.naver.com", 587),
            "gmail" to MailPreset("Gmail", "imap.gmail.com", 993, "smtp.gmail.com", 587),
            "daum" to MailPreset("다음", "imap.daum.net", 993, "smtp.daum.net", 465),
            "custom" to MailPreset("직접 입력", "", 993, "", 587)
        )
    }
}

data class MailPreset(
    val label: String,
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int
)
