package ru.topmts.report

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Срабатывает по расписанию: запускает отправку отчёта. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val settings = Settings(context)
        if (settings.enabled) {
            SendService.start(context)
        }
        // следующий будильник переставится после завершения отправки в SendService
    }
}
