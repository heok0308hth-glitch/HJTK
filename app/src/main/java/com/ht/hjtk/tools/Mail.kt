package com.ht.hjtk.tools

import com.ht.hjtk.data.MailAccount
import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.UUID
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.SubjectTerm

/** 프리셋: OAuth 없이 '앱 비밀번호'로 붙는 메일들. */
object MailPresets {
    data class Preset(val id: String, val label: String, val imap: String, val imapPort: Int, val smtp: String, val smtpPort: Int, val ssl: Boolean, val howTo: String)
    val ALL = listOf(
        Preset("gmail", "Gmail", "imap.gmail.com", 993, "smtp.gmail.com", 587, false, "Google 계정 → 보안 → 2단계 인증 → 앱 비밀번호 생성(16자리)"),
        Preset("naver", "네이버 메일", "imap.naver.com", 993, "smtp.naver.com", 587, false, "네이버 메일 → 환경설정 → POP3/IMAP 설정 → IMAP 사용함. 2단계 인증이면 '애플리케이션 비밀번호' 발급"),
        Preset("daum", "다음 메일", "imap.daum.net", 993, "smtp.daum.net", 465, true, "다음 메일 → 설정 → IMAP/SMTP 사용 설정, 2단계 인증 시 앱 비밀번호"),
        Preset("kakao", "카카오 메일", "imap.kakao.com", 993, "smtp.kakao.com", 465, true, "카카오메일 → 설정 → IMAP/SMTP 사용, 앱 비밀번호"),
        Preset("icloud", "iCloud 메일", "imap.mail.me.com", 993, "smtp.mail.me.com", 587, false, "appleid.apple.com → 앱 암호 생성"),
        Preset("outlook", "Outlook(제한될 수 있음)", "outlook.office365.com", 993, "smtp.office365.com", 587, false, "Microsoft는 기본 인증을 막는 추세 — 실패하면 다른 계정 사용"),
        Preset("custom", "직접 입력", "", 993, "", 587, false, "IMAP/SMTP 서버 주소를 직접 입력")
    )
}

data class MailItem(val uid: Long, val from: String, val subject: String, val date: String, val unread: Boolean, val snippet: String) {
    fun line() = "[${uid}] ${if (unread) "● " else ""}$subject — $from · $date" + (if (snippet.isNotBlank()) "\n   ${snippet.take(140)}" else "")
}

object MailClient {
    private val df = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA)

    private fun session(a: MailAccount): Session {
        val p = Properties().apply {
            put("mail.store.protocol", "imaps"); put("mail.imaps.host", a.imapHost); put("mail.imaps.port", a.imapPort.toString())
            put("mail.imaps.ssl.enable", "true"); put("mail.imaps.timeout", "15000"); put("mail.imaps.connectiontimeout", "15000")
            put("mail.smtp.host", a.smtpHost); put("mail.smtp.port", a.smtpPort.toString()); put("mail.smtp.auth", "true")
            if (a.smtpSsl) { put("mail.smtp.ssl.enable", "true") } else put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.timeout", "20000"); put("mail.smtp.connectiontimeout", "15000"); put("mail.smtp.writetimeout", "20000")
        }
        return Session.getInstance(p)
    }

    private inline fun <T> inbox(a: MailAccount, pw: String, write: Boolean = false, f: (IMAPFolder) -> T): T {
        val st: Store = session(a).getStore("imaps"); st.connect(a.imapHost, a.imapPort, a.email, pw)
        try {
            val folder = st.getFolder("INBOX") as IMAPFolder
            folder.open(if (write) Folder.READ_WRITE else Folder.READ_ONLY)
            try { return f(folder) } finally { runCatching { folder.close(false) } }
        } finally { runCatching { st.close() } }
    }

    suspend fun test(a: MailAccount, pw: String): String = withContext(Dispatchers.IO) {
        runCatching { inbox(a, pw) { f -> "연결됨 · 받은편지함 ${f.messageCount}통, 안 읽음 ${f.unreadMessageCount}통" } }
            .getOrElse { "연결 실패: ${it.message?.take(160)}" }
    }

    suspend fun list(a: MailAccount, pw: String, n: Int, unreadOnly: Boolean): List<MailItem> = withContext(Dispatchers.IO) {
        inbox(a, pw) { f ->
            val total = f.messageCount
            val msgs = if (total == 0) emptyArray() else f.getMessages(maxOf(1, total - (if (unreadOnly) 80 else n) + 1), total)
            msgs.reversed().map { item(f, it, false) }.filter { !unreadOnly || it.unread }.take(n)
        }
    }

    suspend fun search(a: MailAccount, pw: String, q: String, n: Int): List<MailItem> = withContext(Dispatchers.IO) {
        inbox(a, pw) { f -> f.search(OrTerm(SubjectTerm(q), FromStringTerm(q))).reversed().take(n).map { item(f, it, false) } }
    }

    suspend fun read(a: MailAccount, pw: String, uid: Long): String = withContext(Dispatchers.IO) {
        inbox(a, pw) { f ->
            val m = f.getMessageByUID(uid) ?: return@inbox "메일을 찾지 못했습니다"
            val it = item(f, m, true)
            "제목: ${it.subject}\n보낸이: ${it.from}\n날짜: ${it.date}\n\n${bodyText(m).take(12000)}"
        }
    }

    suspend fun unread(a: MailAccount, pw: String): Int = withContext(Dispatchers.IO) { inbox(a, pw) { it.unreadMessageCount } }

    suspend fun markRead(a: MailAccount, pw: String, uid: Long): Boolean = withContext(Dispatchers.IO) {
        inbox(a, pw, write = true) { f -> f.getMessageByUID(uid)?.setFlag(Flags.Flag.SEEN, true); true }
    }

    suspend fun send(a: MailAccount, pw: String, to: String, subject: String, body: String, replyToUid: Long? = null): String = withContext(Dispatchers.IO) {
        val s = session(a)
        val m = object : MimeMessage(s) {
            private val mid = "<hjtk-${UUID.randomUUID()}@${a.email.substringAfter('@')}>"
            override fun updateMessageID() { setHeader("Message-ID", mid) }     // 재개 시 중복 발송 확인용으로 우리가 생성
        }
        m.setFrom(InternetAddress(a.email)); m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
        m.setSubject(subject, "UTF-8"); m.setText(body, "UTF-8"); m.sentDate = Date()
        if (replyToUid != null) runCatching { inbox(a, pw) { f -> f.getMessageByUID(replyToUid)?.getHeader("Message-ID")?.firstOrNull()?.let { m.setHeader("In-Reply-To", it); m.setHeader("References", it) } } }
        val t: Transport = s.getTransport("smtp")
        t.connect(a.smtpHost, a.smtpPort, a.email, pw)
        try { t.sendMessage(m, m.allRecipients) } finally { t.close() }
        "발송 완료 · Message-ID ${m.getHeader("Message-ID")?.firstOrNull()}"
    }

    private fun item(f: UIDFolder, m: Message, full: Boolean): MailItem {
        val from = (m.from?.firstOrNull() as? InternetAddress)?.let { it.personal ?: it.address } ?: "?"
        val snippet = if (full) "" else ""
        return MailItem(f.getUID(m), from, m.subject ?: "(제목 없음)", m.sentDate?.let { df.format(it) } ?: "", !m.isSet(Flags.Flag.SEEN), snippet)
    }

    fun bodyText(p: Part): String = runCatching {
        when {
            p.isMimeType("text/plain") -> p.content.toString()
            p.isMimeType("text/html") -> HtmlText.extract(p.content.toString(), 20000)
            p.isMimeType("multipart/alternative") -> {
                val mp = p.content as Multipart
                (0 until mp.count).map { mp.getBodyPart(it) }.firstOrNull { it.isMimeType("text/plain") }?.let { bodyText(it) }
                    ?: (0 until mp.count).joinToString("\n") { bodyText(mp.getBodyPart(it)) }
            }
            p.isMimeType("multipart/*") -> { val mp = p.content as Multipart; (0 until mp.count).joinToString("\n") { i -> mp.getBodyPart(i).let { if (Part.ATTACHMENT.equals(it.disposition, true)) "[첨부] ${it.fileName}" else bodyText(it) } } }
            else -> ""
        }
    }.getOrDefault("")
}
