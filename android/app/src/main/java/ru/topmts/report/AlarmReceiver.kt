package ru.topmts.report

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Срабатывает по расписанию и показывает НАПОМИНАНИЕ.
 * Сама отправка не запускается в фоне: Android блокирует фоновый старт окон,
 * а без окна WebView не работает. Поэтому по времени приходит уведомление —
 * одно нажатие по нему открывает отправку.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val settings = Settings(context)
        if (!settings.enabled) return

        // если сегодня уже отправляли — не напоминаем
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (settings.lastSentDate == today) {
            AlarmScheduler.schedule(context, settings)
            return
        }

        val activityIntent = Intent(context, RunnerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(RunnerActivity.EXTRA_FROM_ALARM, true)

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(context, 200, activityIntent, flags)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL, "Напоминание об отчёте", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Напоминает отправить отчёт в назначенное время" }
            nm.createNotificationChannel(ch)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        val notif = builder
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Пора отправить отчёт")
            .setContentText("Нажмите, чтобы собрать и отправить в ВК")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "Нажмите — приложение соберёт отчёт с портала МТС и отправит его в чат ВК."
                )
            )
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .apply {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) setPriority(Notification.PRIORITY_HIGH)
            }
            .build()
        nm.notify(NOTIF_LAUNCH, notif)

        // переставляем будильник на следующий раз
        AlarmScheduler.schedule(context, settings)
    }

    companion object {
        private const val CHANNEL = "report_reminder"
        const val NOTIF_LAUNCH = 10
    }
}
