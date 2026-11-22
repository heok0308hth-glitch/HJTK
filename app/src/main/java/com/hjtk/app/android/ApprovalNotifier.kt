package com.hjtk.app.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hjtk.app.MainActivity
import com.hjtk.app.R
import com.hjtk.app.core.orchestrator.Orchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Asks for approval where the user actually is.
 *
 * A plan that needs a side effect stops. If the app is not on screen, the only
 * honest way to ask is a notification with the same two choices as the card:
 * 실행 / 취소. Both actions route back to the orchestrator and disappear as soon
 * as the request is answered.
 */
class ApprovalNotifier(
    private val context: Context,
    private val orchestrator: Orchestrator
) {
    companion object {
        const val CHANNEL = "hjtk_approval"
        const val NOTIFICATION_ID = 6101
        const val ACTION_APPROVE = "com.hjtk.app.approval.APPROVE"
        const val ACTION_REJECT = "com.hjtk.app.approval.REJECT"
        const val EXTRA_PLAN = "plan_id"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastKey: String? = null

    init {
        createChannel()
        scope.launch {
            orchestrator.state
                .map { state -> state.jobs.firstOrNull { it.pendingApproval != null }?.let { it.pendingApproval!!.planId to it.pendingApproval!!.titleKo } }
                .distinctUntilChanged()
                .collect { request -> request?.let { post(it.first, it.second) } ?: dismiss() }
        }
    }

    private fun createChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "승인 요청", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "외부로 나가는 단계의 실행 여부를 묻습니다"
            }
        )
    }

    private fun post(planId: String, title: String) {
        if (lastKey == planId) return
        lastKey = planId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun action(actionName: String, label: String): NotificationCompat.Action {
            val intent = Intent(context, ApprovalActionReceiver::class.java).apply {
                action = actionName
                putExtra(EXTRA_PLAN, planId)
            }
            val pending = PendingIntent.getBroadcast(
                context, (actionName + planId).hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Action(0, label, pending)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hjtk)
            .setContentTitle("확인이 필요합니다")
            .setContentText("$title — 실행할까요?")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n외부로 나가는 단계입니다. 실행하면 되돌릴 수 없습니다."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(action(ACTION_APPROVE, "실행"))
            .addAction(action(ACTION_REJECT, "취소"))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notification permission was revoked; the in-app card still works.
        }
    }

    private fun dismiss() {
        lastKey = null
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}

/** Routes the notification buttons back into the running plan. */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val planId = intent.getStringExtra(ApprovalNotifier.EXTRA_PLAN) ?: return
        val orchestrator = Engine.of(context).orchestrator
        when (intent.action) {
            ApprovalNotifier.ACTION_APPROVE -> orchestrator.approve(planId)
            ApprovalNotifier.ACTION_REJECT -> orchestrator.reject(planId)
        }
        runCatching {
            NotificationManagerCompat.from(context).cancel(ApprovalNotifier.NOTIFICATION_ID)
        }
    }
}
