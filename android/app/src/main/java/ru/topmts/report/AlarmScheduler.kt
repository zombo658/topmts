package ru.topmts.report

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** Планирует точный будильник на ближайший подходящий день и время. */
object AlarmScheduler {

    private const val REQUEST = 100

    private fun pending(context: Context): PendingIntent {
        val i = Intent(context, AlarmReceiver::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST, i, flags)
    }

    fun schedule(context: Context, settings: Settings) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!settings.enabled || settings.peer.isBlank() || settings.days.isEmpty()) {
            am.cancel(pending(context))
            return
        }

        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, settings.hour)
            set(Calendar.MINUTE, settings.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = Calendar.getInstance()
        // ищем ближайший день из выбранных (1=Пн … 7=Вс)
        for (i in 0..7) {
            val dow = isoDay(next.get(Calendar.DAY_OF_WEEK))
            if (next.after(now) && settings.days.contains(dow)) break
            next.add(Calendar.DAY_OF_MONTH, 1)
            next.set(Calendar.HOUR_OF_DAY, settings.hour)
            next.set(Calendar.MINUTE, settings.minute)
        }

        val pi = pending(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            // нет разрешения на точные будильники — ставим неточный
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
        }
    }

    /** Calendar.DAY_OF_WEEK (1=Вс … 7=Сб) → 1=Пн … 7=Вс. */
    private fun isoDay(cal: Int): Int = if (cal == Calendar.SUNDAY) 7 else cal - 1
}
