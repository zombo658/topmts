package ru.topmts.report

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TimePicker
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var settings: Settings

    private lateinit var enabled: CheckBox
    private lateinit var peer: EditText
    private lateinit var vkHost: EditText
    private lateinit var calls: EditText
    private lateinit var time: TimePicker
    private lateinit var template: EditText
    private lateinit var dayBoxes: List<CheckBox>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        enabled = findViewById(R.id.enabled)
        peer = findViewById(R.id.peer)
        vkHost = findViewById(R.id.vkHost)
        calls = findViewById(R.id.calls)
        time = findViewById(R.id.time)
        template = findViewById(R.id.template)
        dayBoxes = listOf(
            findViewById(R.id.d1), findViewById(R.id.d2), findViewById(R.id.d3),
            findViewById(R.id.d4), findViewById(R.id.d5), findViewById(R.id.d6),
            findViewById(R.id.d7)
        )
        time.setIs24HourView(true)

        load()

        findViewById<Button>(R.id.save).setOnClickListener { save(); toast("Сохранено") }
        findViewById<Button>(R.id.loginPortal).setOnClickListener { openLogin(settings.portalUrl) }
        findViewById<Button>(R.id.loginVk).setOnClickListener {
            save()
            openLogin("https://" + vkHost.text.toString().trim().ifEmpty { "vk.ru" } + "/im")
        }
        findViewById<Button>(R.id.sendNow).setOnClickListener {
            save()
            if (settings.peer.isBlank()) { toast("Укажите чат"); return@setOnClickListener }
            startActivity(Intent(this, RunnerActivity::class.java))
        }

        requestRuntimePermissions()
    }

    private fun load() {
        enabled.isChecked = settings.enabled
        peer.setText(settings.peer)
        vkHost.setText(settings.vkHost)
        calls.setText(settings.calls)
        template.setText(settings.template)
        setHour(settings.hour); setMinute(settings.minute)
        val d = settings.days
        for (i in dayBoxes.indices) dayBoxes[i].isChecked = d.contains(i + 1)
    }

    private fun save() {
        settings.enabled = enabled.isChecked
        settings.peer = peer.text.toString().trim()
        settings.vkHost = vkHost.text.toString().trim().ifEmpty { "vk.ru" }
        settings.calls = calls.text.toString().trim().ifEmpty { "0" }
        settings.template = template.text.toString().ifEmpty { Settings.DEFAULT_TEMPLATE }
        settings.hour = getHour(); settings.minute = getMinute()
        val days = mutableSetOf<Int>()
        for (i in dayBoxes.indices) if (dayBoxes[i].isChecked) days.add(i + 1)
        settings.days = days
        AlarmScheduler.schedule(this, settings)
        maybeAskExactAlarm()
        maybeAskFullScreen()
        maybeAskBatteryExemption()
    }

    private fun openLogin(url: String) {
        startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_URL, url))
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ---- совместимость getHour/getMinute для разных API ----
    @Suppress("DEPRECATION")
    private fun getHour() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) time.hour else time.currentHour
    @Suppress("DEPRECATION")
    private fun getMinute() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) time.minute else time.currentMinute
    @Suppress("DEPRECATION")
    private fun setHour(h: Int) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) time.hour = h else time.currentHour = h }
    @Suppress("DEPRECATION")
    private fun setMinute(m: Int) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) time.minute = m else time.currentMinute = m }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun maybeAskExactAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            if (!am.canScheduleExactAlarms()) {
                try {
                    startActivity(
                        Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) { /* некоторые прошивки не имеют экрана — игнор */ }
            }
        }
    }

    // Android 14+: разрешение на полноэкранные уведомления (чтобы окно
    // отправки открывалось при погашенном экране)
    private fun maybeAskFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!nm.canUseFullScreenIntent()) {
                try {
                    startActivity(
                        Intent(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) { /* игнор */ }
            }
        }
    }

    // Просим исключить приложение из экономии батареи, иначе система
    // «усыпит» будильник на многих прошивках
    private fun maybeAskBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) { /* игнор */ }
            }
        }
    }
}
