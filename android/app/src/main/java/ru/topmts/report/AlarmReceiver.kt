package ru.topmts.report

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Срабатывает по расписанию: открывает экран отправки отчёта. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val settings = Settings(context)
        if (!settings.enabled) return
        val i = Intent(context, RunnerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(RunnerActivity.EXTRA_FROM_ALARM, true)
        context.startActivity(i)
    }
}
