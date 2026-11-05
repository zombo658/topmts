package ru.topmts.report

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class MainActivity : Activity() {

    lateinit var webView: WebView
    lateinit var appSettings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appSettings = Settings(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.parseColor("#0A0D14")
            window.navigationBarColor = Color.parseColor("#0D111A")
        }

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)

        webView.apply {
            setBackgroundColor(Color.parseColor("#0A0D14"))
            val ws = settings
            ws.javaScriptEnabled = true
            ws.domStorageEnabled = true
            ws.databaseEnabled = true
            ws.allowFileAccess = true
            ws.cacheMode = WebSettings.LOAD_DEFAULT
            ws.useWideViewPort = true
            ws.loadWithOverviewMode = true
            ws.builtInZoomControls = false
            ws.displayZoomControls = false

            addJavascriptInterface(WebAppInterface(this@MainActivity), "AndroidBridge")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return false
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")

        requestRuntimePermissions()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_CHAT && resultCode == RESULT_OK && data != null) {
            val p = data.getStringExtra(ChatPickerActivity.EXTRA_PEER) ?: ""
            if (p.isNotEmpty()) {
                appSettings.peer = p
                runOnUiThread {
                    webView.evaluateJavascript("window.onChatPicked ? window.onChatPicked('$p') : null", null)
                }
                Toast.makeText(this, "Чат выбран: $p", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onBackPressed() {
        webView.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : false") { result ->
            val handled = result != null && (result == "true" || result == "\"true\"")
            if (!handled) {
                super.onBackPressed()
            }
        }
    }

    fun getStatusText(): String {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.areNotificationsEnabled()) return "Уведомления запрещены — напоминание не придёт"
        if (!appSettings.enabled) return "Автоотправка по расписанию выключена"
        if (appSettings.peer.isBlank()) return "Не выбран чат — напоминание не придёт"
        if (appSettings.days.isEmpty()) return "Не выбраны дни недели — напоминание не придёт"

        val next = AlarmScheduler.nextRun(appSettings)
            ?: return "Напоминание не запланировано"
        return "Следующее напоминание: " + humanTime(next)
    }

    private fun humanTime(at: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val now = java.util.Calendar.getInstance()
        val time = String.format(
            "%02d:%02d",
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE)
        )
        val days = ((at - now.timeInMillis) / 86_400_000L).toInt()
        val sameDay = c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR) &&
            c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
        return when {
            sameDay -> "сегодня в $time"
            days < 1 -> "завтра в $time"
            else -> {
                val iso = c.get(java.util.Calendar.DAY_OF_WEEK)
                    .let { if (it == java.util.Calendar.SUNDAY) 7 else it - 1 }
                "${DAY_NAMES[iso - 1]}, $time"
            }
        }
    }

    fun testNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.areNotificationsEnabled()) {
            Toast.makeText(this, "Уведомления запрещены — включите их в настройках", Toast.LENGTH_SHORT).show()
            try {
                startActivity(
                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName)
                )
            } catch (e: Exception) {}
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        maybeAskExactAlarm()
        AlarmScheduler.scheduleTest(this, 10)
        Toast.makeText(this, "Проверочное уведомление придёт через 10 секунд", Toast.LENGTH_SHORT).show()
    }

    fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    fun maybeAskExactAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            if (!am.canScheduleExactAlarms()) {
                try {
                    startActivity(
                        Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {}
            }
        }
    }

    fun maybeAskBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {}
            }
        }
    }

    companion object {
        const val REQ_PICK_CHAT = 42
        val DAY_NAMES = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    }
}
