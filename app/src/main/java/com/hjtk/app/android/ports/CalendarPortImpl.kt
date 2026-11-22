package com.hjtk.app.android.ports

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.hjtk.app.core.CalendarItem
import com.hjtk.app.core.CalendarPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CalendarPortImpl(private val context: Context) : CalendarPort {

    override fun canRead(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun today(): List<CalendarItem> = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext emptyList()
        val begin = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = begin + 24 * 60 * 60 * 1000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(begin.toString())
            .appendPath(end.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY
        )
        val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)
        val out = ArrayList<CalendarItem>()
        context.contentResolver.query(uri, projection, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            val titleIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val locIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val allDayIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            while (c.moveToNext()) {
                val allDay = c.getInt(allDayIdx) == 1
                out.add(
                    CalendarItem(
                        title = c.getString(titleIdx).orEmpty().ifBlank { "(제목 없음)" },
                        start = if (allDay) "종일" else fmt.format(c.getLong(beginIdx)),
                        end = if (allDay) "" else fmt.format(c.getLong(endIdx)),
                        location = c.getString(locIdx).orEmpty()
                    )
                )
            }
        }
        out.take(30)
    }

    override suspend fun insert(title: String, startMs: Long, endMs: Long, location: String): String =
        withContext(Dispatchers.Main) {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                putExtra(CalendarContract.Events.TITLE, title)
                if (location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "ok"
        }

    /** Used by the developer console to show which calendar providers are installed. */
    fun providers(): List<String> {
        val out = ArrayList<String>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars.ACCOUNT_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) out.add(c.getString(0).orEmpty())
        }
        return out.distinct()
    }

    fun eventUri(id: Long) = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
}
