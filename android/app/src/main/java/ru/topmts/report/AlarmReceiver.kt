package ru.topmts.report

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Срабатывает по расписанию. Чтобы отправка работала даже при погашенном
 * экране, запуск идёт через полноэкранное уведомление (как у будильника):
 * система сама будит экран и открывает окно отправки поверх блокировки.
 * Прямой startActivity оставлен как запасной путь для старых версий Android.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val settings = Settings(context)
        if (!settings.enabled) return

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
                CHANNEL, "Запуск отправки", NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(ch)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        val notif = builder
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Отправка отчёта в ВК")
            .setContentText("Открываю… нажмите, если не открылось само")
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .apply {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) setPriority(Notification.PRIORITY_HIGH)
            }
            .build()
        nm.notify(NOTIF_LAUNCH, notif)

        // запасной прямой запуск (сработает на старых Android и когда приложение
        // недавно было на переднем плане)
        try {
            context.startActivity(activityIntent)
        } catch (e: Exception) {
            // на Android 10+ фоновый старт может быть запрещён — тогда сработает
            // полноэкранное уведомление выше
        }
    }

    companion object {
        private const val CHANNEL = "report_launch"
        const val NOTIF_LAUNCH = 10
    }
}
