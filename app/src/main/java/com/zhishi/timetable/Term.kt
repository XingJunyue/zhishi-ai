package com.zhishi.timetable

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/** 课表的存储和计算：课程、开学日期与周次、每节课时间、显示开关、备份。 */
object Term {
    private fun p(c: Context) = c.getSharedPreferences("term", Context.MODE_PRIVATE)

    private val memo = java.util.concurrent.ConcurrentHashMap<String, List<Slot>>()
    private fun list(c: Context, key: String): List<Slot> {
        val raw = p(c).getString(key, "[]") ?: "[]"
        memo[raw]?.let { return it }
        val a = try { JSONArray(raw) } catch (x: Exception) { JSONArray() }
        if (memo.size > 8) memo.clear()
        // 一条坏掉的（老版本 / 被改坏的数据）只跳过它，不让整张课表、小部件和启动跟着崩
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { try { Slot.of(it) } catch (x: Exception) { null } } }.also { memo[raw] = it }
    }
    private fun put(c: Context, key: String, l: List<Slot>) {
        val a = JSONArray(); l.forEach { a.put(it.json()) }
        p(c).edit().putString(key, a.toString()).apply()
    }

    /** 所有课程。 */
    fun desired(c: Context): List<Slot> = list(c, "courses")
    fun setDesired(c: Context, d: List<Slot>) {
        put(c, "courses", d)
        // 有过课（导入、日历、手动加）就记下来：以后课表页再也不自动弹「导入课表」
        if (d.any { it.kind == "course" }) ImportPrompt.markImported(c)
        CourseWidgets.safeUpdate(c)
        CloudSync.localChanged(c)
    }

    // ---------- 时间小工具 ----------
    fun nowMin(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    fun clock(min: Int): String { val x = ((min % 1440) + 1440) % 1440; return "${x / 60}:${"%02d".format(x % 60)}" }
    /** 今天星期几（1 = 周一 … 7 = 周日）。 */
    fun weekday(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }

    // ---------- 周次 ----------
    /** 第 1 周周一（epoch 天）；-1 = 没设，所有课按每周都上。 */
    fun termStart(c: Context) = try { p(c).getLong("term", -1L) } catch (x: ClassCastException) { -1L }
    fun setTermStart(c: Context, day: Long) { p(c).edit().putLong("term", day).apply(); CourseWidgets.safeUpdate(c); CloudSync.localChanged(c) }
    fun totalWeeks(c: Context) = safeInt(c, "weeks", 20).coerceIn(1, 60)
    fun setTotalWeeks(c: Context, n: Int) { p(c).edit().putInt("weeks", n.coerceIn(1, 30)).apply(); CloudSync.localChanged(c) }
    fun showOff(c: Context) = p(c).getBoolean("showOff", true)
    fun setShowOff(c: Context, v: Boolean) = p(c).edit().putBoolean("showOff", v).apply()
    fun showWeekend(c: Context) = p(c).getBoolean("weekend", true)
    fun setShowWeekend(c: Context, v: Boolean) = p(c).edit().putBoolean("weekend", v).apply()
    /** 课块 / 小部件上标出水课、普通、重要。 */
    fun showLevel(c: Context) = p(c).getBoolean("lvlApp", true)
    fun setShowLevel(c: Context, v: Boolean) = p(c).edit().putBoolean("lvlApp", v).apply()
    fun widgetLevel(c: Context) = p(c).getBoolean("lvlWidget", true)
    fun setWidgetLevel(c: Context, v: Boolean) { p(c).edit().putBoolean("lvlWidget", v).apply(); CourseWidgets.safeUpdate(c) }
    fun gridView(c: Context) = p(c).getBoolean("grid", true)
    fun setGridView(c: Context, v: Boolean) = p(c).edit().putBoolean("grid", v).apply()

    /** 今天所在周一的 epoch 天。 */
    fun mondayOf(epochDay: Long): Long { val dow = Math.floorMod(epochDay + 3, 7L); return epochDay - dow }  // 1970-01-01 是周四
    fun todayEpoch(): Long { val k = Calendar.getInstance(); return Math.floorDiv(k.timeInMillis + k.get(Calendar.ZONE_OFFSET) + k.get(Calendar.DST_OFFSET), 86_400_000L) }
    /** 现在是第几周；没设开学日期返回 null。 */
    fun weekNow(c: Context): Int? { val t = termStart(c); return if (t < 0) null else weekOf(t, todayEpoch()) }
    fun weekOf(term: Long, day: Long) = Math.floorDiv(mondayOf(day) - term, 7L).toInt() + 1
    /** 「现在是第 n 周」：倒推出第 1 周的周一。 */
    fun setWeekNow(c: Context, n: Int) = setTermStart(c, mondayOf(todayEpoch()) - (n - 1) * 7L)

    // ---------- 每节课的时间 ----------
    private val DEFAULT_SEC = listOf(480, 535, 600, 655, 840, 895, 960, 1015, 1140, 1195, 1250, 1305)
    fun secStarts(c: Context): List<Int> = ((try { p(c).getString("secs", null) } catch (x: ClassCastException) { null })
        ?.split(',')?.mapNotNull { it.trim().toIntOrNull()?.takeIf { v -> v in 0..1439 } } ?: DEFAULT_SEC).ifEmpty { DEFAULT_SEC }
    fun setSecStarts(c: Context, l: List<Int>) { p(c).edit().putString("secs", l.joinToString(",")).apply(); CloudSync.localChanged(c) }
    fun secLen(c: Context) = safeInt(c, "secLen", 45).coerceIn(5, 300)
    /** 读 int：类型不对（老版本存成了别的类型）就当没存。 */
    private fun safeInt(c: Context, k: String, def: Int) = try { p(c).getInt(k, def) } catch (x: ClassCastException) { def }
    fun setSecLen(c: Context, m: Int) { p(c).edit().putInt("secLen", m).apply(); CloudSync.localChanged(c) }
    /** 每节下课时间：单独改过的用改过的，没改过的 = 上课时间 + 每节时长。 */
    fun secEnds(c: Context): List<Int> {
        val st = secStarts(c); val len = secLen(c)
        val saved = (try { p(c).getString("secE", null) } catch (x: ClassCastException) { null })?.split(',')?.map { it.trim().toIntOrNull() } ?: emptyList()
        return st.indices.map { i -> saved.getOrNull(i)?.takeIf { it > st[i] } ?: (st[i] + len) }
    }
    fun setSecEnds(c: Context, l: List<Int>) { p(c).edit().putString("secE", l.joinToString(",")).apply(); CloudSync.localChanged(c) }
    /** 统一时长：清掉单独改过的下课时间。 */
    fun resetSecEnds(c: Context) { p(c).edit().remove("secE").apply(); CloudSync.localChanged(c) }
    fun secRange(c: Context, a: Int, b: Int): Pair<Int, Int> {
        val s = secStarts(c); val e = secEnds(c)
        val i = (a - 1).coerceIn(0, s.lastIndex); val j = (b - 1).coerceIn(i, s.lastIndex)
        return s[i] to e[j]
    }
    /** 一段时间落在第几节到第几节（就近算）。 */
    fun secSpan(starts: List<Int>, ends: List<Int>, from: Int, to: Int): Pair<Int, Int> {
        if (starts.isEmpty()) return 1 to 1
        val a = starts.indices.minByOrNull { abs(starts[it] - from) } ?: 0
        val b = (ends.indices.minByOrNull { abs(ends[it] - to) } ?: a).coerceAtLeast(a)
        return (a + 1) to (b + 1)
    }

    /**
     * 某一天（epoch 天）上的课：按星期和周次筛。没设开学日期时每周都一样；
     * 设了开学日期时，学期外（第 1 周之前、总周数之后）的日子没有课。
     */
    fun classesOn(courses: List<Slot>, term: Long, total: Int, day: Long): List<Slot> {
        val wd = Math.floorMod(day + 3, 7L).toInt() + 1
        val week: Int? = if (term < 0) null else weekOf(term, day)
        if (week != null && (week < 1 || week > total)) return emptyList()
        return courses.filter { wd in it.days && it.inWeek(week) && it.from < it.to }.sortedBy { it.from }
    }

    fun secSpanOf(c: Context, s: Slot) = secSpan(secStarts(c), secEnds(c), s.from, s.to)

    // ---------- 备份 / 导出 ----------
    /** todos = null：备份里没有待办（旧备份、留白的备份），恢复时不动现有待办。 */
    class Backup(val courses: List<Slot>, val starts: List<Int>, val ends: List<Int>, val len: Int, val term: Long, val weeks: Int, val todos: List<Todo>? = null)

    /** 课表备份：课程 + 每节课时间 + 开学日期 + 总周数 + 待办。 */
    fun backupJson(courses: List<Slot>, starts: List<Int>, ends: List<Int>, len: Int, term: Long, weeks: Int, todos: List<Todo>? = null): String {
        val a = JSONArray(); courses.forEach { a.put(it.json()) }
        val j = JSONObject().put("app", "zhishi-timetable").put("v", 1).put("courses", a)
            .put("secs", JSONArray(starts)).put("secE", JSONArray(ends)).put("secLen", len).put("term", term).put("weeks", weeks)
        if (todos != null) j.put("todos", Todos.toJson(todos))
        return j.toString(2)
    }
    fun parseBackup(s: String): Backup? = try {
        val j = JSONObject(s)
        val a = j.optJSONArray("courses") ?: JSONArray()
        fun ints(k: String) = j.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.getInt(it) } } ?: emptyList()
        Backup((0 until a.length()).map { Slot.of(a.getJSONObject(it)).copy(kind = "course") }, ints("secs"), ints("secE"),
            j.optInt("secLen", 45), j.optLong("term", -1L), j.optInt("weeks", 20),
            if (j.has("todos")) Todos.fromJson(j.optJSONArray("todos")) else null)
    } catch (x: Exception) { null }
    fun exportBackup(c: Context) = backupJson(desired(c).filter { it.kind == "course" }, secStarts(c), secEnds(c), secLen(c), termStart(c), totalWeeks(c), TodoStore.all(c))
    /** 用备份替换课表。也认留白导出的课表备份（格式相同）。 */
    fun applyBackup(c: Context, b: Backup) {
        setDesired(c, b.courses)
        if (b.starts.isNotEmpty()) { setSecStarts(c, b.starts); setSecLen(c, b.len); if (b.ends.size == b.starts.size) setSecEnds(c, b.ends) else resetSecEnds(c) }
        setTermStart(c, b.term); setTotalWeeks(c, b.weeks)
        b.todos?.let { TodoStore.replaceAll(c, it) }
    }

    // ---------- 导入 ----------
    /** 从系统日历读接下来两周的日程，按「名字 + 星期 + 时间」合成每周的课。 */
    fun fromCalendar(c: Context, calId: Long): List<Slot> {
        val out = LinkedHashMap<String, Slot>()
        val start = System.currentTimeMillis() - 86_400_000L
        val end = start + 15 * 86_400_000L
        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start); android.content.ContentUris.appendId(it, end)
        }.build()
        c.contentResolver.query(uri, arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN,
            android.provider.CalendarContract.Instances.END, android.provider.CalendarContract.Instances.ALL_DAY),
            "${android.provider.CalendarContract.Instances.CALENDAR_ID} = ?", arrayOf(calId.toString()), null)?.use { cur ->
            while (cur.moveToNext()) {
                if (cur.getInt(3) == 1) continue
                addSlot(out, cur.getString(0) ?: continue, cur.getLong(1), cur.getLong(2), TimeZone.getDefault())
            }
        }
        return out.values.toList()
    }

    fun calendars(c: Context): List<Pair<Long, String>> {
        val l = ArrayList<Pair<Long, String>>()
        c.contentResolver.query(android.provider.CalendarContract.Calendars.CONTENT_URI,
            arrayOf(android.provider.CalendarContract.Calendars._ID, android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME), null, null, null)?.use {
            while (it.moveToNext()) l += it.getLong(0) to (it.getString(1) ?: tr("日历"))
        }
        return l
    }

    private fun addSlot(out: MutableMap<String, Slot>, title: String, b: Long, e: Long, tz: TimeZone) {
        val k = Calendar.getInstance(tz).apply { timeInMillis = b }
        val ke = Calendar.getInstance(tz).apply { timeInMillis = e }
        val day = k.get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }
        val from = k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE)
        val to = ke.get(Calendar.HOUR_OF_DAY) * 60 + ke.get(Calendar.MINUTE)
        if (to <= from || to - from > 6 * 60) return
        val name = title.trim().take(20)
        val key = "$name|$day|$from|$to"
        if (key !in out) out[key] = Slot("c${key.hashCode()}", name, setOf(day), from, to)
    }

    /** 读 .ics 文件（课表 App、学校教务系统常能导出）。 */
    fun fromIcs(c: Context, uri: Uri): List<Slot> {
        val raw = c.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return emptyList()
        return parseIcs(raw)
    }

    fun parseIcs(raw: String): List<Slot> {
        val lines = ArrayList<String>()
        for (l in raw.replace("\r\n", "\n").split('\n')) { if ((l.startsWith(" ") || l.startsWith("\t")) && lines.isNotEmpty()) lines[lines.lastIndex] += l.substring(1) else lines += l }
        val out = LinkedHashMap<String, Slot>()
        var title = ""; var b = 0L; var e = 0L; val tz = TimeZone.getDefault()
        fun time(prop: String, v: String): Long {
            val zone = Regex("TZID=([^;:]+)").find(prop)?.groupValues?.get(1)?.let { TimeZone.getTimeZone(it) }
            val utc = v.endsWith("Z")
            val s = v.trimEnd('Z')
            if (s.length < 15) return 0L
            val k = Calendar.getInstance(if (utc) TimeZone.getTimeZone("UTC") else zone ?: TimeZone.getDefault())
            k.clear(); k.set(s.substring(0, 4).toInt(), s.substring(4, 6).toInt() - 1, s.substring(6, 8).toInt(), s.substring(9, 11).toInt(), s.substring(11, 13).toInt())
            return k.timeInMillis
        }
        for (l in lines) {
            val i = l.indexOf(':'); if (i < 0) continue
            val prop = l.substring(0, i); val v = l.substring(i + 1).trim()
            when {
                l.startsWith("BEGIN:VEVENT") -> { title = ""; b = 0; e = 0 }
                prop.startsWith("SUMMARY") -> title = v.replace("\\,", ",").replace("\\n", " ")
                prop.startsWith("DTSTART") -> b = try { time(prop, v) } catch (x: Exception) { 0L }
                prop.startsWith("DTEND") -> e = try { time(prop, v) } catch (x: Exception) { 0L }
                l.startsWith("END:VEVENT") -> if (title.isNotBlank() && b > 0 && e > b) addSlot(out, title, b, e, tz)
            }
        }
        return out.values.toList()
    }
}
