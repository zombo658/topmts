package ru.topmts.report

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class WebAppInterface(private val activity: MainActivity) {

    private val context: Context get() = activity
    private val settings = Settings(activity)
    private val appPrefs = context.getSharedPreferences("topmts_data", Context.MODE_PRIVATE)

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("MTS Report", text)
        clipboard.setPrimaryClip(clip)
        toast("Скопировано в буфер")
    }

    @JavascriptInterface
    fun shareText(text: String) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Отправить отчёт")
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(shareIntent)
    }

    @JavascriptInterface
    fun vibrate(durationMs: Long) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        }
    }

    @JavascriptInterface
    fun toast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun saveToStorage(key: String, json: String) {
        appPrefs.edit().putString(key, json).apply()
    }

    @JavascriptInterface
    fun loadFromStorage(key: String): String {
        return appPrefs.getString(key, "") ?: ""
    }

    // ---- VK & Portal MTS integration (Классический отчёт) ----

    @JavascriptInterface
    fun openLoginPortal() {
        activity.runOnUiThread {
            activity.startActivity(
                Intent(activity, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_URL, settings.portalUrl)
            )
        }
    }

    @JavascriptInterface
    fun openLoginVk(host: String) {
        val h = host.trim().ifEmpty { settings.vkHost }
        settings.vkHost = h
        activity.runOnUiThread {
            activity.startActivity(
                Intent(activity, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_URL, "https://$h/im")
            )
        }
    }

    @JavascriptInterface
    fun pickChat(host: String) {
        val h = host.trim().ifEmpty { settings.vkHost }
        settings.vkHost = h
        activity.runOnUiThread {
            activity.startActivityForResult(
                Intent(activity, ChatPickerActivity::class.java).putExtra(ChatPickerActivity.EXTRA_HOST, h),
                MainActivity.REQ_PICK_CHAT
            )
        }
    }

    @JavascriptInterface
    fun sendReportNow(peer: String, vkHost: String, calls: String, template: String) {
        if (peer.isNotBlank()) settings.peer = peer.trim()
        if (vkHost.isNotBlank()) settings.vkHost = vkHost.trim()
        if (calls.isNotBlank()) settings.calls = calls.trim()
        if (template.isNotBlank()) settings.template = template.trim()

        if (settings.peer.isBlank()) {
            toast("Укажите или выберите чат ВК")
            return
        }

        activity.runOnUiThread {
            activity.startActivity(Intent(activity, RunnerActivity::class.java))
        }
    }

    @JavascriptInterface
    fun saveVkSettings(jsonStr: String): Boolean {
        return try {
            val obj = JSONObject(jsonStr)
            if (obj.has("peer")) settings.peer = obj.getString("peer").trim()
            if (obj.has("vkHost")) settings.vkHost = obj.getString("vkHost").trim()
            if (obj.has("calls")) settings.calls = obj.getString("calls").trim()
            if (obj.has("template")) settings.template = obj.getString("template")
            if (obj.has("enabled")) settings.enabled = obj.getBoolean("enabled")
            if (obj.has("hour")) settings.hour = obj.getInt("hour")
            if (obj.has("minute")) settings.minute = obj.getInt("minute")

            if (obj.has("days")) {
                val arr = obj.getJSONArray("days")
                val dSet = mutableSetOf<Int>()
                for (i in 0 until arr.length()) {
                    dSet.add(arr.getInt(i))
                }
                settings.days = dSet
            }

            AlarmScheduler.schedule(activity, settings)
            activity.runOnUiThread {
                activity.maybeAskExactAlarm()
                activity.maybeAskBatteryExemption()
            }
            toast("Настройки сохранены")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            toast("Ошибка сохранения: ${e.message}")
            false
        }
    }

    @JavascriptInterface
    fun getVkSettings(): String {
        val obj = JSONObject()
        obj.put("peer", settings.peer)
        obj.put("vkHost", settings.vkHost)
        obj.put("calls", settings.calls)
        obj.put("template", settings.template)
        obj.put("enabled", settings.enabled)
        obj.put("hour", settings.hour)
        obj.put("minute", settings.minute)
        obj.put("portalUrl", settings.portalUrl)

        val daysArr = JSONArray()
        settings.days.forEach { daysArr.put(it) }
        obj.put("days", daysArr)
        obj.put("statusText", activity.getStatusText())

        return obj.toString()
    }

    @JavascriptInterface
    fun testNotification() {
        activity.runOnUiThread {
            activity.testNotification()
        }
    }
}
