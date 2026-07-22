package ru.topmts.report

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Небольшой видимый экран, внутри которого работает WebView отправки.
 * Активность гарантирует, что WebView прикреплён к окну и реально
 * загружает страницы (в фоновом сервисе он «висел»).
 * Запускается кнопкой «Отправить сейчас» и будильником по расписанию.
 */
class RunnerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // показать поверх экрана блокировки и включить экран (для расписания)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0D1117"))
        setContentView(root)

        // контейнер для WebView (занимает экран, но прикрыт статусом)
        val webHost = FrameLayout(this)
        root.addView(
            webHost,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC0D1117"))
            gravity = Gravity.CENTER
            text = "Готовлю отчёт…"
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val settings = Settings(this)

        ReportRunner(
            context = this,
            settings = settings,
            container = webHost,
            onProgress = { msg -> status.text = msg },
            onDone = { ok, message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                status.text = message
                // переставляем будильник на следующий раз
                AlarmScheduler.schedule(applicationContext, settings)
                // закрываемся с небольшой задержкой, чтобы было видно результат
                status.postDelayed({ if (!isFinishing) finish() }, 2500)
            }
        ).start()
    }
}
