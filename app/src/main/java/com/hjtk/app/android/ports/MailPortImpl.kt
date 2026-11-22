package com.hjtk.app.android.ports

import android.content.Context
import com.hjtk.app.android.prefs.Settings
import com.hjtk.app.core.MailPort
import com.hjtk.app.core.MailSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Properties
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * IMAP + SMTP through the account the user set up.
 *
 * The app never bundles provider-specific hacks: host and port come from a
 * preset table of official endpoints, credentials from the Keystore, and every
 * call opens and closes its own connection so a dropped network cannot leave a
 * dangling store. Errors are translated into something a person can act on.
 */
class MailPortImpl(
    private val context: Context,
    private val settings: Settings
) : MailPort {

    private fun preset() = Settings.MAIL_PRESETS[settings.mailProvider] ?: Settings.MAIL_PRESETS.getValue("naver")

    override fun isConfigured(): Boolean =
        settings.mailAddress.isNotBlank() && settings.hasMailPassword() && preset().imapHost.isNotBlank()

    override fun providerName(): String = "${preset().label} (${settings.mailAddress})"

    private fun sessionProps(): Properties {
        val p = Settings.MAIL_PRESETS.getValue(settings.mailProvider)
        return Properties().apply {
            put("mail.imaps.host", p.imapHost)
            put("mail.imaps.port", p.imapPort.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "12000")
            put("mail.imaps.timeout", "15000")
            put("mail.imaps.writetimeout", "12000")
            put("mail.smtp.host", p.smtpHost)
            put("mail.smtp.port", p.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "12000")
            put("mail.smtp.timeout", "15000")
            put("mail.smtp.writetimeout", "12000")
            if (p.smtpPort == 465) {
                put("mail.smtp.ssl.enable", "true")
            } else {
                put("mail.smtp.starttls.enable", "true")
            }
        }
    }

    private fun kotlinx.coroutines.Job.invokeOnCancellationCompat(block: () -> Unit): () -> Unit {
        val handle = invokeOnCompletion { if (it != null) block() }
        return { handle.dispose() }
    }

    private fun session(p: Properties): Session {
        val password = settings.mailPassword ?: error("메일 비밀번호가 저장되어 있지 않습니다.")
        return Session.getInstance(p, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication() =
                javax.mail.PasswordAuthentication(settings.mailAddress, password)
        })
    }

    /**
     * Opens a store, runs [block], always closes.
     *
     * javax.mail blocks a real socket thread. A coroutine cancel only interrupts
     * that thread, so the running job is asked to close the store too: without
     * it a cancelled mail command would hold the mail resource lock forever.
     */
    private suspend fun <T> withStore(block: (Store) -> T): T = withContext(Dispatchers.IO) {
        if (!isConfigured()) error("메일 계정이 연결되지 않았습니다.")
        val props = sessionProps()
        val store: Store = session(props).getStore("imaps")
        store.connect(props.getProperty("mail.imaps.host"), settings.mailAddress, settings.mailPassword)
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val closer = job?.invokeOnCancellationCompat { runCatching { store.close() } }
        try {
            block(store)
        } finally {
            closer?.invoke()
            runCatching { store.close() }
        }
    }

    override suspend fun unreadCount(): Int = withStore { store ->
        val inbox = store.getFolder("INBOX")
        inbox.open(Folder.READ_ONLY)
        try {
            inbox.unreadMessageCount
        } finally {
            runCatching { inbox.close(false) }
        }
    }

    override suspend fun latest(limit: Int): List<MailSummary> = withStore { store ->
        val inbox = store.getFolder("INBOX")
        inbox.open(Folder.READ_ONLY)
        try {
            val total = inbox.messageCount
            if (total == 0) return@withStore emptyList()
            val from = (total - limit).coerceAtLeast(1)
            val messages = inbox.getMessages(from, total)
            val fmt = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
            messages.reversed().map { m -> toSummary(m, fmt) }
        } finally {
            runCatching { inbox.close(false) }
        }
    }

    private fun toSummary(m: Message, fmt: SimpleDateFormat): MailSummary {
        val from = runCatching {
            (m.from?.firstOrNull() as? InternetAddress)?.personal?.takeIf { it.isNotBlank() }
                ?: m.from?.firstOrNull()?.toString().orEmpty()
        }.getOrDefault("")
        val subject = runCatching { m.subject.orEmpty() }.getOrDefault("")
        val date = runCatching { fmt.format(m.sentDate ?: java.util.Date()) }.getOrDefault("")
        val preview = runCatching {
            when {
                m.isMimeType("text/plain") -> (m.content as? String).orEmpty()
                m.isMimeType("text/html") -> m.content.toString().replace(Regex("<[^>]+>"), " ")
                else -> ""
            }.replace(Regex("\\s+"), " ").trim().take(140)
        }.getOrDefault("")
        return MailSummary(from, subject, date, preview)
    }

    override suspend fun send(to: String, subject: String, body: String) = withContext(Dispatchers.IO) {
        if (!isConfigured()) error("메일 계정이 연결되지 않았습니다.")
        val props = sessionProps()
        val session = session(props)
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(settings.mailAddress))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
            setHeader("X-Mailer", "HJTK")
        }
        Transport.send(message)
    }

    override suspend fun composeDraft(to: String, subject: String, body: String) = withContext(Dispatchers.Main) {
        // The platform composer keeps the user in control of the final send.
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
            putExtra(android.content.Intent.EXTRA_TEXT, body)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, "메일 보내기")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
