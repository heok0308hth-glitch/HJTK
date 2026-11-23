package com.ht.hjtk.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.ht.hjtk.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 전화의 중심. HJTK가 '기본 전화 앱'이면 모든 통화가 여기로 온다(받기·끊기·스피커·음소거·키패드·보류).
 * 다른 앱으로 넘기지 않는다: 걸기·받기·통화 화면이 전부 HJTK 안에 있다.
 */
object CallCenter {
    data class Line(val id: Int, val number: String, val name: String?, val state: Int, val since: Long, val incoming: Boolean, val ai: Boolean = false) {
        val ringing get() = state == Call.STATE_RINGING
        val active get() = state == Call.STATE_ACTIVE
        val ended get() = state == Call.STATE_DISCONNECTED
        fun label() = name ?: number.ifBlank { "알 수 없는 번호" }
    }
    data class Audio(val muted: Boolean = false, val speaker: Boolean = false, val bluetooth: Boolean = false)

    val lines = MutableStateFlow<List<Line>>(emptyList())
    val audio = MutableStateFlow(Audio())
    @Volatile internal var service: InCallService? = null
    private val calls = LinkedHashMap<Int, Call>()
    /** AI 통화로 건 번호(다음에 추가되는 발신 통화를 AI 통화로 표시) */
    @Volatile var pendingAiNumber: String? = null
    const val CH_CALL = "calls"; private const val NOTIF_INCOMING = 4401

    fun isDefaultDialer(ctx: Context): Boolean = ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == ctx.packageName

    /** 시스템이 묻는 창 한 번(다른 앱으로 넘어가지 않는다) */
    fun roleIntent(ctx: Context): Intent? = if (Build.VERSION.SDK_INT >= 29)
        ctx.getSystemService(RoleManager::class.java)?.takeIf { it.isRoleAvailable(RoleManager.ROLE_DIALER) && !it.isRoleHeld(RoleManager.ROLE_DIALER) }?.createRequestRoleIntent(RoleManager.ROLE_DIALER)
    else Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, ctx.packageName)

    @SuppressLint("MissingPermission")
    fun place(ctx: Context, number: String, ai: Boolean = false): String? {
        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return "전화 걸기 권한이 필요해요"
        val tm = ctx.getSystemService(TelecomManager::class.java) ?: return "전화 기능이 없는 기기예요"
        if (ai) pendingAiNumber = digits(number)
        return runCatching { tm.placeCall(Uri.fromParts("tel", number, null), Bundle()); null }.getOrElse { "걸지 못했어요: ${it.message}" }
    }

    fun digits(n: String) = n.filter { it.isDigit() || it == '+' }

    // ── InCallService가 알려 주는 것 ──
    internal fun added(ctx: Context, c: Call) {
        val id = System.identityHashCode(c)
        calls[id] = c
        c.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) { refresh(ctx); if (state != Call.STATE_RINGING) cancelIncoming(ctx) }
            override fun onDetailsChanged(call: Call, details: Call.Details) = refresh(ctx)
        })
        val num = c.details?.handle?.schemeSpecificPart.orEmpty()
        val ai = pendingAiNumber?.let { p -> digits(num).endsWith(p.takeLast(8)) } == true
        if (ai) pendingAiNumber = null
        refresh(ctx, aiId = if (ai) id else null)
        if (stateOf(c) == Call.STATE_RINGING) notifyIncoming(ctx, lines.value.first { it.id == id })
        else ctx.startActivity(Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    internal fun removed(ctx: Context, c: Call) {
        calls.remove(System.identityHashCode(c)); refresh(ctx); cancelIncoming(ctx)
    }

    private val aiIds = HashSet<Int>()
    @Suppress("DEPRECATION")
    private fun stateOf(c: Call): Int = if (Build.VERSION.SDK_INT >= 31) c.details.state else c.state

    private fun refresh(ctx: Context, aiId: Int? = null) {
        aiId?.let { aiIds += it }
        val old = lines.value.associateBy { it.id }
        lines.value = calls.map { (id, c) ->
            val num = c.details?.handle?.schemeSpecificPart.orEmpty()
            val st = stateOf(c)
            val prev = old[id]
            Line(id, num, prev?.name ?: contactName(ctx, num), st,
                if (st == Call.STATE_ACTIVE && prev?.active != true) System.currentTimeMillis() else prev?.since ?: System.currentTimeMillis(),
                if (Build.VERSION.SDK_INT >= 29) c.details?.callDirection == Call.Details.DIRECTION_INCOMING else prev?.incoming ?: (st == Call.STATE_RINGING), id in aiIds)
        }
        aiIds.retainAll(calls.keys)
    }

    internal fun audioChanged(a: CallAudioState?) {
        a ?: return
        audio.value = Audio(a.isMuted, a.route == CallAudioState.ROUTE_SPEAKER, a.route == CallAudioState.ROUTE_BLUETOOTH)
    }

    // ── 조작 ──
    private fun call(id: Int) = calls[id]
    fun answer(id: Int) { call(id)?.answer(VideoProfile.STATE_AUDIO_ONLY) }
    fun reject(id: Int) { call(id)?.reject(false, null) }
    fun end(id: Int) { call(id)?.let { if (stateOf(it) == Call.STATE_RINGING) it.reject(false, null) else it.disconnect() } }
    fun hold(id: Int, on: Boolean) { call(id)?.let { if (on) it.hold() else it.unhold() } }
    fun dtmf(id: Int, c: Char) { call(id)?.let { it.playDtmfTone(c); it.stopDtmfTone() } }
    fun mute(on: Boolean) { service?.setMuted(on) }
    @Suppress("DEPRECATION")
    fun speaker(on: Boolean) { service?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE) }
    fun current(): Line? = lines.value.firstOrNull { it.active } ?: lines.value.firstOrNull { !it.ended }

    // ── 수신 알림(잠금 화면 전체 화면) ──
    fun channel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CH_CALL, "전화", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false) })
    }

    private fun notifyIncoming(ctx: Context, l: Line) {
        channel(ctx)
        val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        // 받기는 화면을 바로 여는 PendingIntent로(알림→브로드캐스트→화면 열기는 Android 12부터 막혀 있다)
        val ans = PendingIntent.getActivity(ctx, 2, Intent(ctx, CallActivity::class.java).setAction("answer").putExtra("id", l.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dec = PendingIntent.getBroadcast(ctx, 3, Intent(ctx, CallActionReceiver::class.java).setAction("reject").putExtra("id", l.id), PendingIntent.FLAG_IMMUTABLE)
        val who = Person.Builder().setName(l.label()).setImportant(true).build()
        val n = NotificationCompat.Builder(ctx, CH_CALL).setSmallIcon(R.drawable.ic_stat_hjtk)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(who, dec, ans))
            .setCategory(NotificationCompat.CATEGORY_CALL).setOngoing(true).setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(open, true).setContentIntent(open).build()
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_INCOMING, n) }
    }

    private fun cancelIncoming(ctx: Context) { if (lines.value.none { it.ringing }) ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_INCOMING) }

    private fun contactName(ctx: Context, number: String): String? {
        if (number.isBlank() || ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)), arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }
}

class HjtkInCallService : InCallService() {
    override fun onCreate() { super.onCreate(); CallCenter.service = this }
    override fun onDestroy() { if (CallCenter.service === this) CallCenter.service = null; super.onDestroy() }
    override fun onCallAdded(call: Call) { CallCenter.added(this, call) }
    override fun onCallRemoved(call: Call) { CallCenter.removed(this, call) }
    @Deprecated("API 34에서 대체되었지만 하위 호환을 위해 둔다")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) { CallCenter.audioChanged(audioState) }
}

class CallActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        when (intent.action) {
            "reject" -> CallCenter.reject(id)
            "end" -> CallCenter.end(id)
        }
    }
}
