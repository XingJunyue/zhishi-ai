package com.zhishi.timetable

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import org.json.JSONObject
import java.util.Locale

/**
 * 多语言：界面文字都用中文原文当键，其他语言从 assets/i18n/<lang>.json 查。
 * 找不到的就显示中文原文，不会空白。
 */
object Tr {
    @Volatile var lang = "zh"; private set
    private var map: Map<String, String> = emptyMap()
    private var rev: Map<String, String> = emptyMap()
    val SUPPORTED = listOf("zh", "en", "fr", "ja", "ko", "de", "es")
    /** 语言选项：代码到它自己的名字（空代码 = 跟随系统）。 */
    val NAMES = listOf("zh" to "简体中文", "en" to "English", "fr" to "Français", "ja" to "日本語", "ko" to "한국어", "de" to "Deutsch", "es" to "Español")

    private fun p(c: Context) = c.getSharedPreferences("lang", Context.MODE_PRIVATE)
    fun chosen(c: Context) = p(c).getString("l", "") ?: ""
    fun systemLang(): String {
        val l = try { Resources.getSystem().configuration.locales[0].language } catch (x: Exception) { "en" }
        return if (l in SUPPORTED) l else "en"
    }

    fun init(c: Context) { use(c, chosen(c).ifEmpty { systemLang() }) }

    /** 测试和切换用：立刻换成某种语言。 */
    fun use(c: Context, l: String) {
        lang = if (l in SUPPORTED) l else "en"
        Locale.setDefault(locale(lang))
        if (lang == "zh") { map = emptyMap(); rev = emptyMap(); return }
        map = try {
            val j = JSONObject(c.assets.open("i18n/$lang.json").bufferedReader().use { it.readText() })
            j.keys().asSequence().associateWith { j.getString(it) }
        } catch (x: Exception) { emptyMap() }
        rev = map.entries.associate { (k, v) -> v to k }
    }

    fun locale(l: String = lang): Locale = when (l) {
        "zh" -> Locale.SIMPLIFIED_CHINESE; "ja" -> Locale.JAPAN; "ko" -> Locale.KOREA
        "fr" -> Locale.FRANCE; "de" -> Locale.GERMANY; "es" -> Locale("es", "ES"); else -> Locale.US
    }

    /** 已翻译的文字反查中文原文（自定义按键文字用中文当键）。 */
    fun source(s: String) = rev[s] ?: s

    /** 中日韩文字之间不用空格。 */
    val cjk get() = lang == "zh" || lang == "ja" || lang == "ko"

    fun set(c: Context, l: String) {
        p(c).edit().putString("l", l).commit()
        // 整个进程重启，所有文字按新语言重新生成；小部件会按新语言重画
        val i = c.packageManager.getLaunchIntentForPackage(c.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (i != null) c.startActivity(i)
        Runtime.getRuntime().exit(0)
    }

    fun get(s: String): String = if (lang == "zh") s else map[s] ?: s
}

fun tr(s: String): String = Tr.get(s)
fun tr(s: String, vararg a: Any?): String {
    var r = Tr.get(s)
    a.forEachIndexed { i, v -> r = r.replace("{$i}", v.toString()) }
    return r
}

/** 星期简称（1 = 周一）。 */
fun dayShort(d: Int): String {
    val i = (d - 1).coerceIn(0, 6)
    return when (Tr.lang) {
        "en" -> listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")[i]
        "fr" -> listOf("Lu", "Ma", "Me", "Je", "Ve", "Sa", "Di")[i]
        "de" -> listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")[i]
        "es" -> listOf("Lu", "Ma", "Mi", "Ju", "Vi", "Sá", "Do")[i]
        "ja" -> listOf("月", "火", "水", "木", "金", "土", "日")[i]
        "ko" -> listOf("월", "화", "수", "목", "금", "토", "일")[i]
        else -> listOf("一", "二", "三", "四", "五", "六", "日")[i]
    }
}
/** 星期全称样式：周一 / Mon / 月曜 …（列表里的小标题用）。 */
fun dayLabel(d: Int): String {
    val i = (d - 1).coerceIn(0, 6)
    return when (Tr.lang) {
        "en" -> listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[i]
        "fr" -> listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")[i]
        "de" -> listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")[i]
        "es" -> listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")[i]
        "ja" -> listOf("月曜", "火曜", "水曜", "木曜", "金曜", "土曜", "日曜")[i]
        "ko" -> listOf("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")[i]
        else -> "周" + dayShort(d)
    }
}
fun daysText(days: Collection<Int>) = days.sorted().joinToString(if (Tr.cjk) "" else " ") { dayShort(it) }

class ZhishiApp : Application() {
    override fun onCreate() { Tr.init(this); GlassLens.init(this); super.onCreate(); WallTint.listen(this); TodoAlarm.rescheduleAll(this); CloudSync.init(this) }
}
