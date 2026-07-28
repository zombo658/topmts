package ru.topmts.report

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
    private lateinit var status: TextView

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
        status = findViewById(R.id.status)
        time.setIs24HourView(true)

        load()
        showStatus(animated = false)

        tap(findViewById(R.id.save)) { save(); toast("Сохранено"); showStatus() }
        tap(findViewById(R.id.loginPortal)) { openLogin(settings.portalUrl) }
        tap(findViewById(R.id.loginVk)) {
            save()
            openLogin("https://" + host() + "/im")
        }
        tap(findViewById(R.id.pickChat)) {
            save()
            startActivityForResult(
                Intent(this, ChatPickerActivity::class.java)
                    .putExtra(ChatPickerActivity.EXTRA_HOST, host()),
                REQ_PICK_CHAT
            )
        }
        tap(findViewById(R.id.sendNow)) {
            save()
            if (settings.peer.isBlank()) { toast("Укажите чат"); return@tap }
            startActivity(Intent(this, RunnerActivity::class.java))
        }

        tap(findViewById(R.id.testNotify)) { testNotification() }

        // расписание сохраняется сразу — без этого напоминание не ставилось,
        // если пользователь менял настройки и не нажимал «Сохранить»
        enabled.setOnCheckedChangeListener { _, _ -> saveSchedule() }
        dayBoxes.forEach { it.setOnCheckedChangeListener { _, _ -> saveSchedule() } }
        time.setOnTimeChangedListener { _, _, _ -> saveSchedule() }

        animateIn()
        requestRuntimePermissions()
    }

    private fun host() = vkHost.text.toString().trim().ifEmpty { "vk.ru" }

    /** Сохранить только расписание и сразу переставить будильник. */
    private fun saveSchedule() {
        settings.enabled = enabled.isChecked
        settings.hour = getHour()
        settings.minute = getMinute()
        val days = mutableSetOf<Int>()
        for (i in dayBoxes.indices) if (dayBoxes[i].isChecked) days.add(i + 1)
        settings.days = days
        if (settings.peer.isBlank()) settings.peer = peer.text.toString().trim()
        AlarmScheduler.schedule(this, settings)
        showStatus()
    }

    /** Проверка: уведомление придёт через 10 секунд — не нужно ждать вечера. */
    private fun testNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.areNotificationsEnabled()) {
            toast("Уведомления запрещены — включите их в настройках приложения")
            openNotificationSettings()
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
        toast("Проверочное уведомление придёт через 10 секунд")
    }

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (e2: Exception) { /* игнор */ }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_CHAT && resultCode == RESULT_OK) {
            val id = data?.getStringExtra(ChatPickerActivity.EXTRA_PEER)?.trim().orEmpty()
            if (id.isNotEmpty()) {
                peer.setText(id)
                settings.peer = id
                // подсветим поле, чтобы было видно, что подставилось
                peer.alpha = 0.3f
                peer.animate().alpha(1f).setDuration(500).start()
                toast("Чат выбран: $id")
                // чат выбран — расписание теперь можно поставить
                AlarmScheduler.schedule(this, settings)
                showStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // вернулись из системных настроек — статус мог измениться
        if (::status.isInitialized) showStatus(animated = false)
    }

    // ---- оформление ----

    /** Карточки появляются каскадом при открытии экрана. */
    private fun animateIn() {
        val root = findViewById<LinearLayout>(R.id.content)
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            v.alpha = 0f
            v.translationY = 48f
            v.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(50L * i)
                .setDuration(420)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** Нажатие кнопки с лёгким «пружинящим» откликом. */
    private fun tap(button: Button, action: () -> Unit) {
        button.setOnClickListener { v ->
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90)
                .withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(OvershootInterpolator(2.5f)).start()
                    action()
                }.start()
        }
    }

    /** Строка под заголовком: когда придёт напоминание и что мешает. */
    private fun showStatus(animated: Boolean = true) {
        val text = buildStatus()
        if (!animated) { status.text = text; return }
        status.animate().alpha(0f).setDuration(110).withEndAction {
            status.text = text
            status.animate().alpha(1f).setDuration(200).start()
        }.start()
    }

    private fun buildStatus(): String {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.areNotificationsEnabled()) return "Уведомления запрещены — напоминание не придёт"
        if (!enabled.isChecked) return "Напоминание выключено"
        if (dayBoxes.none { it.isChecked }) return "Дни не выбраны — напоминание не придёт"
        if (settings.peer.isBlank() && peer.text.toString().isBlank()) {
            return "Чат не выбран — напоминание не придёт"
        }
        val next = AlarmScheduler.nextRun(settings)
            ?: return String.format("Напоминание в %02d:%02d", getHour(), getMinute())
        return "Напоминание: " + humanTime(next)
    }

    /** «сегодня в 22:00», «завтра в 22:00», «в Пн, 22:00». */
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

    companion object {
        private const val REQ_PICK_CHAT = 42
        private val DAY_NAMES = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    }
}
