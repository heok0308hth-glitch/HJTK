package com.hjtk.app.android.ports

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hjtk.app.MainActivity
import com.hjtk.app.R
import com.hjtk.app.core.NotifyPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotifyPortImpl(private val context: Context) : NotifyPort {

    companion object {
        const val CHANNEL_TASK = "hjtk_task"
        const val CHANNEL_TIMER = "hjtk_timer"
        private var nextId = 4200
    }

    init {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // minSdk 26: notification channels always exist.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TASK, "작업 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "계획 진행과 완료 보고"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TIMER, "타이머", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "타이머와 리마인더"
            }
        )
    }

    override fun canPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    override suspend fun show(title: String, body: String) = withContext(Dispatchers.Main) {
        post(CHANNEL_TASK, title, body)
    }

    private fun post(channel: String, title: String, body: String, id: Int = ++nextId) {
        // Permission can be revoked at any time; posting must be a no-op then.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_hjtk)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was revoked between the check and this call.
        }
    }

    override suspend fun scheduleTimer(label: String, seconds: Int): Unit = withContext(Dispatchers.Main) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TimerReceiver::class.java).apply {
            putExtra("label", label)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            (label + seconds).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = System.currentTimeMillis() + seconds * 1000L
        // Exact alarms need a user grant on API 31+. When it is missing we fall
        // back to an inexact alarm instead of throwing SecurityException — the
        // reminder may land a little late on a dozing device, which is honest.
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
                .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    override suspend fun setAlarm(label: String, hour: Int, minute: Int) = withContext(Dispatchers.Main) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/** Fires the timer notification when the alarm wakes the app. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: "타이머"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NotifyPortImpl.CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_stat_hjtk)
            .setContentTitle(label)
            .setContentText("설정한 시간이 되었습니다.")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(label.hashCode(), notification) }
    }
}
