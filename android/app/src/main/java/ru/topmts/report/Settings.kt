package ru.topmts.report

import android.content.Context

/** Хранилище настроек в SharedPreferences. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("topmts", Context.MODE_PRIVATE)

    var vkHost: String
        get() = prefs.getString("vkHost", "vk.ru") ?: "vk.ru"
        set(v) = prefs.edit().putString("vkHost", v).apply()

    var peer: String
        get() = prefs.getString("peer", "") ?: ""
        set(v) = prefs.edit().putString("peer", v).apply()

    var hour: Int
        get() = prefs.getInt("hour", 22)
        set(v) = prefs.edit().putInt("hour", v).apply()

    var minute: Int
        get() = prefs.getInt("minute", 0)
        set(v) = prefs.edit().putInt("minute", v).apply()

    /** Дни недели: 1=Пн … 7=Вс (как Calendar в человекочитаемом виде). */
    var days: Set<Int>
        get() = (prefs.getStringSet("days", setOf("1", "2", "3", "4", "5")) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = prefs.edit().putStringSet("days", v.map { it.toString() }.toSet()).apply()

    var calls: String
        get() = prefs.getString("calls", "0") ?: "0"
        set(v) = prefs.edit().putString("calls", v).apply()

    var template: String
        get() = prefs.getString("template", DEFAULT_TEMPLATE) ?: DEFAULT_TEMPLATE
        set(v) = prefs.edit().putString("template", v).apply()

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    val portalUrl = "https://inventory.ural.mts.ru/pc/agent_day.php"

    fun chatUrl(): String {
        var p = peer.trim()
        val n = p.toLongOrNull()
        if (n != null && n >= 2_000_000_000L) p = "c" + (n - 2_000_000_000L)
        return "https://$vkHost/im?sel=$p"
    }

    companion object {
        const val DEFAULT_TEMPLATE =
            "{дата} {тип дня}\n" +
            "Общее количество ДМХ: {общее количество дмх}\n" +
            "Поквартирный обход ДМХ: {поквартирный обход дмх}\n" +
            "Общее время поквартирного обхода: {общее время поквартирного обхода}\n" +
            "Визуализация, дмх: {визуализация дмх}\n" +
            "Общее время визуализации: {общее время визуализации}\n" +
            "Общее время: {общее время}\n" +
            "Количество звонков: {количество звонков}"
    }
}
