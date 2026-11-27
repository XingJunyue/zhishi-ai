package com.zhishi.timetable

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * 待办：标题、备注（选填）、截止时间（选填，精确到分钟，或者「全天」）、关联的课（选填，按课名记）、提醒（可提前）、完成。
 * 只存在本机（SharedPreferences `todo` 的 `items`），课表备份 / 导出里一起带上。
 * due / doneAt / created 都是毫秒时间戳；due = -1 表示没设截止时间。
 * allDay = 全天：只认日期，due 记成那天 23:59（本地时区），当天结束前都不算过期。
 * before = 提前几分钟提醒（0 = 到点提醒）；全天的待办从当天 8:00 往前算。
 */
data class Todo(
    val id: String, val title: String, val note: String = "", val due: Long = -1L, val remind: Boolean = false,
    val course: String = "", val done: Boolean = false, val doneAt: Long = 0L, val created: Long = 0L,
    val allDay: Boolean = false, val before: Int = 0,
    /** 置顶：没完成时排在所有待办最上面（待办页和小部件都一样）。 */
    val pin: Boolean = false,
    /** 手动顺序（同一组里拖动排过的，1、2、3…）；0 = 没手动排过，按默认顺序排在这一组最前面。老数据没有这两个字段 = 不置顶、没排过。 */
    val order: Int = 0,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("t", title).put("no", note).put("due", due).put("re", remind)
        .put("co", course).put("dn", done).put("da", doneAt).put("cr", created).put("ad", allDay).put("rb", before)
        .put("pn", pin).put("or", order)

    companion object {
        fun of(j: JSONObject) = Todo(j.optString("id"), j.optString("t"), j.optString("no", ""), j.optLong("due", -1L),
            j.optBoolean("re", false), j.optString("co", ""), j.optBoolean("dn", false), j.optLong("da", 0L), j.optLong("cr", 0L),
            j.optBoolean("ad", false), j.optInt("rb", 0).coerceIn(0, 7 * 1440), j.optBoolean("pn", false), j.optInt("or", 0).coerceAtLeast(0))
    }
}

/** 纯逻辑（单测用）：序列化、分组、按天归类、快捷时间、提醒时刻。 */
object Todos {
    /** overdue = 已过期（没完成、截止时间已经过了），今天 = 今天之内还没到的，即将 = 明天以后 + 没设时间的。 */
    class Groups(val pinned: List<Todo>, val overdue: List<Todo>, val today: List<Todo>, val upcoming: List<Todo>, val done: List<Todo>,
                 /** 所有没完成的里：已过期 / 今天 / 之后各几项（置顶的也按日期算进去，标题和小部件头部用）。 */
                 val lateN: Int, val todayN: Int, val laterN: Int) {
        /** 所有没完成的，按显示顺序：置顶 → 已过期 → 今天 → 即将（待办页和小部件共用这一个顺序）。 */
        val open: List<Todo> get() = pinned + overdue + today + upcoming
    }

    /** 提醒可选的提前量（分钟）。 */
    val BEFORE = listOf(0, 5, 15, 30, 60, 1440)
    /** 全天待办的提醒基准：当天 8:00。 */
    const val ALL_DAY_REMIND = 8 * 60
    const val DAY_MS = 86_400_000L

    fun toJson(l: List<Todo>): JSONArray = JSONArray().also { a -> l.forEach { a.put(it.json()) } }
    fun fromJson(a: JSONArray?): List<Todo> = if (a == null) emptyList() else
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Todo.of(it) }?.takeIf { it.id.isNotBlank() && it.title.isNotBlank() } }
    fun parse(raw: String?): List<Todo> = try { fromJson(JSONArray(raw ?: "[]")) } catch (x: Exception) { emptyList() }

    /** 截止那一刻：全天的是那天结束（第二天 0 点），其余就是 due。 */
    fun endOf(t: Todo, tz: TimeZone = TimeZone.getDefault()): Long =
        if (t.due < 0) -1L else if (t.allDay) at((dayOf(t, tz) ?: 0L) + 1, 0, tz) else t.due

    fun overdue(t: Todo, now: Long, tz: TimeZone = TimeZone.getDefault()) = !t.done && t.due >= 0 && endOf(t, tz) <= now

    /** 排序键：同一天里有具体时间的按时间排在前，全天的排在那天最后。 */
    private fun sortKey(t: Todo, tz: TimeZone) = if (t.allDay) endOf(t, tz) - 1 else t.due

    /**
     * 已过期 = 没完成、截止已经过了（最早的在前）；
     * 今天 = 没完成、今天之内截止、还没过（按时间，全天的排最后）；
     * 即将 = 明天及以后（按时间），再接没设截止的（按创建时间）；
     * 已完成 = 最近完成的在前。
     */
    fun group(all: List<Todo>, now: Long, tz: TimeZone = TimeZone.getDefault()): Groups {
        val todayEnd = at(dayOfMs(now, tz) + 1, 0, tz)
        val open = all.filter { !it.done }
        val late = open.filter { overdue(it, now, tz) }.sortedBy { sortKey(it, tz) }
        val dated = open.filter { it.due >= 0 && !overdue(it, now, tz) }
        val today = dated.filter { sortKey(it, tz) < todayEnd }.sortedWith(compareBy({ sortKey(it, tz) }, { it.created }))
        val later = dated.filter { sortKey(it, tz) >= todayEnd }.sortedWith(compareBy({ sortKey(it, tz) }, { it.created })) +
            open.filter { it.due < 0 }.sortedBy { it.created }
        val done = all.filter { it.done }.sortedByDescending { it.doneAt }
        // 置顶的单独放最上面；每组里手动排过的按手动顺序，没排过的（order = 0，比如新加的）按默认顺序排在这一组前面
        fun arrange(l: List<Todo>) = l.sortedBy { it.order }      // sortedBy 是稳定的：order 相同的保持默认顺序
        val pinned = arrange((late + today + later).filter { it.pin })
        return Groups(pinned, arrange(late.filter { !it.pin }), arrange(today.filter { !it.pin }), arrange(later.filter { !it.pin }), done,
            late.size, today.size, later.size)
    }

    /** 拖动排好一组后：这一组按新顺序记 1、2、3…，别的不动。 */
    fun reorder(all: List<Todo>, ids: List<String>): List<Todo> {
        val rank = ids.withIndex().associate { it.value to it.index + 1 }
        return all.map { t -> rank[t.id]?.let { t.copy(order = it) } ?: t }
    }

    /** 置顶 / 取消置顶：换了一组，手动顺序清零（在新的一组里排最前）。 */
    fun togglePin(t: Todo): Todo = t.copy(pin = !t.pin, order = 0)

    /** 某个时刻在本地时区是哪一天（epoch 天）。 */
    fun dayOfMs(ms: Long, tz: TimeZone = TimeZone.getDefault()): Long = Math.floorDiv(ms + tz.getOffset(ms), DAY_MS)

    /** 截止时间落在哪一天（本地时区的 epoch 天）；没设返回 null。 */
    fun dayOf(t: Todo, tz: TimeZone = TimeZone.getDefault()): Long? = if (t.due < 0) null else dayOfMs(t.due, tz)

    /** 某一天要交的待办（日历页用）：没完成的在前，同一天里按时间、全天的排最后。 */
    fun on(all: List<Todo>, day: Long, tz: TimeZone = TimeZone.getDefault()) =
        all.filter { dayOf(it, tz) == day }.sortedWith(compareBy({ it.done }, { sortKey(it, tz) }))

    /** epoch 天 + 分钟 → 毫秒（本地时区）。 */
    fun at(day: Long, minute: Int, tz: TimeZone = TimeZone.getDefault()): Long {
        val k = Calendar.getInstance(tz); k.clear()
        val d = java.time.LocalDate.ofEpochDay(day)
        k.set(d.year, d.monthValue - 1, d.dayOfMonth, minute / 60, minute % 60)
        return k.timeInMillis
    }
    fun minuteOf(ms: Long, tz: TimeZone = TimeZone.getDefault()): Int = Calendar.getInstance(tz).apply { timeInMillis = ms }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /** 设成某天全天：due = 那天 23:59。 */
    fun allDayAt(day: Long, tz: TimeZone = TimeZone.getDefault()) = at(day, 23 * 60 + 59, tz)

    /** 换日期：保留原来的时刻（全天的还是全天）；原来没设时间就给一个合理的默认时刻。 */
    fun withDay(t: Todo, day: Long, now: Long, tz: TimeZone = TimeZone.getDefault()): Todo = when {
        t.due >= 0 && t.allDay -> t.copy(due = allDayAt(day, tz))
        t.due >= 0 -> t.copy(due = at(day, minuteOf(t.due, tz), tz))
        else -> t.copy(due = at(day, defaultMinute(day, now, tz), tz), allDay = false)
    }

    /** 换时刻（分钟）：日期不变；原来没日期就用今天（今天这个时刻已经过了就放明天）。 */
    fun withMinute(t: Todo, minute: Int, now: Long, tz: TimeZone = TimeZone.getDefault()): Todo {
        val m = minute.coerceIn(0, 1439)
        val day = dayOf(t, tz) ?: dayOfMs(now, tz).let { d -> if (at(d, m, tz) <= now) d + 1 else d }
        return t.copy(due = at(day, m, tz), allDay = false)
    }

    /** 全天开关：打开 = 只留日期；关掉 = 那天的默认时刻。 */
    fun withAllDay(t: Todo, on: Boolean, now: Long, tz: TimeZone = TimeZone.getDefault()): Todo {
        val day = dayOf(t, tz) ?: dayOfMs(now, tz)
        return if (on) t.copy(due = allDayAt(day, tz), allDay = true)
        else t.copy(due = at(day, defaultMinute(day, now, tz), tz), allDay = false)
    }

    /** 清除截止时间：提醒、全天、提前量一起清掉。 */
    fun cleared(t: Todo) = t.copy(due = -1L, remind = false, allDay = false, before = 0)

    /** 新设时间时的默认时刻：今天 = 下一个整点（至少一小时后，最晚 23:55），别的日子 = 22:00（交作业的常见截止）。 */
    fun defaultMinute(day: Long, now: Long, tz: TimeZone = TimeZone.getDefault()): Int {
        if (day != dayOfMs(now, tz)) return 22 * 60
        val m = minuteOf(now, tz)
        val r = (m + 60 + 59) / 60 * 60          // 一小时后再取到整点
        return if (r <= 23 * 60 + 55) r else maxOf(23 * 60 + 55, minOf(m + 1, 1439))
    }

    /** 快捷时间。 */
    enum class Preset { IN_1H, TONIGHT, TODAY, TOMORROW, NEXT_WEEK }

    /**
     * 快捷时间：
     *  +1 小时 = 现在往后一小时，取整到 5 分钟；
     *  今晚 = 今天 21:00（已经过了 20:30 就是一小时后，取整到 5 分钟，最晚 23:55）；
     *  今天 / 明天 = 那天全天，原来设了具体时刻就保留时刻；
     *  下周 = 下周一，同样保留时刻，没有就全天。
     */
    fun preset(t: Todo, p: Preset, now: Long, tz: TimeZone = TimeZone.getDefault()): Todo {
        val today = dayOfMs(now, tz)
        fun keep(day: Long) = if (t.due >= 0 && !t.allDay) t.copy(due = at(day, minuteOf(t.due, tz), tz)) else t.copy(due = allDayAt(day, tz), allDay = true)
        return when (p) {
            Preset.IN_1H -> t.copy(due = roundUp5(now + 3_600_000L, tz), allDay = false)
            Preset.TONIGHT -> {
                val m = minuteOf(now, tz)
                if (m < 20 * 60 + 30) t.copy(due = at(today, 21 * 60, tz), allDay = false)
                else t.copy(due = minOf(roundUp5(now + 3_600_000L, tz), at(today, 23 * 60 + 55, tz)).coerceAtLeast(now + 60_000L), allDay = false)
            }
            Preset.TODAY -> keep(today).let { if (!it.allDay && it.due <= now) t.copy(due = allDayAt(today, tz), allDay = true) else it }
            Preset.TOMORROW -> keep(today + 1)
            Preset.NEXT_WEEK -> keep(Term.mondayOf(today) + 7)
        }
    }

    private fun roundUp5(ms: Long, tz: TimeZone): Long {
        val day = dayOfMs(ms, tz); val m = minuteOf(ms, tz)
        val r = (m + 4) / 5 * 5
        return if (r >= 1440) at(day + 1, 0, tz) else at(day, r, tz)
    }

    /** 提醒的时刻：没开提醒 / 没设时间返回 null。全天的从当天 8:00 往前算。 */
    fun remindAt(t: Todo, tz: TimeZone = TimeZone.getDefault()): Long? {
        if (!t.remind || t.due < 0) return null
        val base = if (t.allDay) at(dayOf(t, tz)!!, ALL_DAY_REMIND, tz) else t.due
        return base - t.before * 60_000L
    }

    /**
     * 实际排闹钟的时刻：已完成 / 没开提醒返回 null；
     * 提醒时刻还没到就用它；提醒时刻过了但还没到截止（比如截止只剩 10 分钟却选了「提前 30 分钟」），马上提醒一次；
     * 截止也过了就不再提醒。
     */
    fun alarmAt(t: Todo, now: Long, tz: TimeZone = TimeZone.getDefault()): Long? {
        if (t.done) return null
        val r = remindAt(t, tz) ?: return null
        if (r > now) return r
        return if (endOf(t, tz) > now) now + 1_000L else null
    }

    /** 关联课程的下一次上课（从现在起 120 天内，开始时间在现在之后）；找不到返回 null。 */
    fun nextClass(courses: List<Slot>, term: Long, total: Int, name: String, now: Long, tz: TimeZone = TimeZone.getDefault()): Long? {
        if (name.isBlank()) return null
        val today = dayOfMs(now, tz)
        val mine = courses.filter { it.kind == "course" && it.name.trim() == name.trim() }
        if (mine.isEmpty()) return null
        for (d in today until today + 120) {
            val hit = Term.classesOn(mine, term, total, d).map { at(d, it.from, tz) }.filter { it > now }.minOrNull()
            if (hit != null) return hit
        }
        return null
    }
}

/** 存储：改了就通知界面（Compose 读 ver）、重画小部件，并重排提醒。 */
object TodoStore {
    val ver = mutableStateOf(0)
    private fun p(c: Context) = c.getSharedPreferences("todo", Context.MODE_PRIVATE)
    fun all(c: Context): List<Todo> = Todos.parse(p(c).getString("items", "[]"))
    /** 存下来、通知界面，并立刻重画桌面上的待办小部件（添加 / 完成 / 删除 / 从备份恢复都走这里）。 */
    fun save(c: Context, l: List<Todo>) {
        p(c).edit().putString("items", Todos.toJson(l).toString()).apply(); rev.incrementAndGet(); ver.value++
        CourseWidgets.todosChanged(c)
        CloudSync.localChanged(c)
    }
    /** 待办数据的版本（每次存盘 +1）：小部件列表的 RemoteViewsFactory 拿它判断手上的快照是不是旧的。 */
    val rev = java.util.concurrent.atomic.AtomicLong(0)
    fun put(c: Context, t: Todo) {
        val l = all(c); save(c, if (l.any { it.id == t.id }) l.map { if (it.id == t.id) t else it } else l + t)
        TodoAlarm.sync(c, t)
    }
    fun remove(c: Context, id: String) { save(c, all(c).filter { it.id != id }); TodoAlarm.cancel(c, id) }
    fun reorder(c: Context, ids: List<String>) = save(c, Todos.reorder(all(c), ids))
    fun togglePin(c: Context, t: Todo) = save(c, all(c).map { if (it.id == t.id) Todos.togglePin(it) else it })
    fun toggle(c: Context, t: Todo) = put(c, t.copy(done = !t.done, doneAt = if (!t.done) System.currentTimeMillis() else 0L))
    /** 从备份恢复：整体替换。 */
    fun replaceAll(c: Context, l: List<Todo>) { all(c).forEach { TodoAlarm.cancel(c, it.id) }; save(c, l); TodoAlarm.rescheduleAll(c) }
}

/** 到点提醒：有精确闹钟权限就用精确的，没有就用不精确的（系统可能晚几分钟）。 */
object TodoAlarm {
    const val CHANNEL = "todo"
    const val ACTION_DONE = "com.zhishi.timetable.TODO_DONE"
    private fun pi(c: Context, id: String, create: Boolean): PendingIntent? =
        PendingIntent.getBroadcast(c, id.hashCode(), Intent(c, TodoReminder::class.java).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE))

    /** 已经提醒过的「id@提醒时刻」：防止每次打开 App / 进程重启都把同一条再提醒一遍；改了时间就是新的键，会重新提醒。 */
    private fun fired(c: Context) = c.getSharedPreferences("todo_fired", Context.MODE_PRIVATE)
    private fun key(t: Todo) = t.id + "@" + (Todos.remindAt(t) ?: -1L)
    fun wasFired(c: Context, t: Todo) = fired(c).getBoolean(key(t), false)
    fun markFired(c: Context, t: Todo) { fired(c).edit().putBoolean(key(t), true).apply() }

    fun sync(c: Context, t: Todo) {
        val at = Todos.alarmAt(t, System.currentTimeMillis())
        if (at == null || wasFired(c, t)) { cancel(c, t.id); return }
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val p = pi(c, t.id, true) ?: return
        try {
            // Android 13+ 声明了 USE_EXACT_ALARM，装上就有精确闹钟权限（小米等手机默认不给 SCHEDULE_EXACT_ALARM，之前会退成不精确的，省电时被拖很久甚至丢掉）
            if (exactOk(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (x: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }
    fun exactOk(c: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        (c.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false)
    fun cancel(c: Context, id: String) {
        val p = pi(c, id, false) ?: return
        c.getSystemService(AlarmManager::class.java)?.cancel(p); p.cancel()
    }
    /** 重排全部：App 进程一启动（打开 App、小部件刷新、开机）就排一遍。被系统清掉的闹钟会补上，错过的（还没到截止）马上补一次提醒。 */
    fun rescheduleAll(c: Context) {
        try {
            val l = TodoStore.all(c)
            l.forEach { sync(c, it) }
            val keep = l.map { key(it) }.toSet()
            val f = fired(c)
            val stale = f.all.keys.filter { it !in keep }
            if (stale.isNotEmpty()) {
                val e = f.edit()
                stale.forEach { e.remove(it) }
                e.apply()
            }
        } catch (x: Exception) {}
    }

    /** 发一条通知（到点提醒和「测试通知」共用）。结果记进诊断。 */
    fun post(c: Context, notifId: Int, title: String, text: String, todoId: String? = null) {
        try {
            channel(c)
            val open = PendingIntent.getActivity(c, notifId, Intent(c, MainActivity::class.java).putExtra("open", "todo")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = android.app.Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_todo).setContentTitle(title).setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setCategory(android.app.Notification.CATEGORY_REMINDER).setShowWhen(true)
                .setContentIntent(open).setAutoCancel(true)
            // 提醒上直接点「完成」：标成完成（和在待办页勾上一样，小部件马上更新），收起通知
            if (todoId != null) b.addAction(android.app.Notification.Action.Builder(null, tr("完成"), PendingIntent.getBroadcast(c, notifId,
                Intent(c, TodoReminder::class.java).setAction(ACTION_DONE).putExtra("id", todoId).putExtra("notif", notifId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            val n = b.build()
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm == null) { log(c, "post", "no NotificationManager"); return }
            nm.notify(notifId, n)
            log(c, "post", "ok" + (if (!nm.areNotificationsEnabled()) " (notifications disabled)" else ""))
        } catch (x: Throwable) { log(c, "post", "error " + x.javaClass.simpleName + ": " + x.message) }
    }
    fun postNow(c: Context) = post(c, 0x7e58, tr("测试提醒"), tr("能看到这条，说明知时的通知是通的。"))

    /** 诊断：记下最近一次闹钟 / 发通知的时间和结果，界面上「提醒诊断」显示，可复制给开发者。 */
    private fun diag(c: Context) = c.getSharedPreferences("todo_diag", Context.MODE_PRIVATE)
    fun log(c: Context, what: String, res: String) {
        try { diag(c).edit().putString(what, res).putLong(what + "At", System.currentTimeMillis()).apply() } catch (x: Throwable) {}
    }
    private fun fmt(ms: Long) = if (ms <= 0) "-" else java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))
    fun report(c: Context): String {
        val l = mutableListOf<String>()
        fun yn(b: Boolean) = if (b) tr("是") else tr("否")
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            l += tr("系统通知开关") + ": " + yn(nm?.areNotificationsEnabled() == true)
            l += tr("通知权限") + ": " + yn(canNotify(c))
            val ch = nm?.getNotificationChannel(CHANNEL)
            l += tr("提醒通知类别") + ": " + (ch?.let { "importance " + it.importance + (if (it.importance == NotificationManager.IMPORTANCE_NONE) " (" + tr("已关闭") + ")" else "") } ?: tr("还没建"))
            l += tr("精确闹钟") + ": " + yn(exactOk(c))
            val pm = c.getSystemService(android.os.PowerManager::class.java)
            l += tr("忽略电池优化") + ": " + yn(pm?.isIgnoringBatteryOptimizations(c.packageName) == true)
            if (Build.VERSION.SDK_INT >= 28) l += tr("后台受限") + ": " + yn(c.getSystemService(android.app.ActivityManager::class.java)?.isBackgroundRestricted == true)
            val now = System.currentTimeMillis()
            val next = TodoStore.all(c).filter { !wasFired(c, it) }.mapNotNull { t -> Todos.alarmAt(t, now)?.let { it to t } }.minByOrNull { it.first }
            l += tr("下一条提醒") + ": " + (next?.let { fmt(it.first) + " " + (if (pi(c, it.second.id, false) != null) tr("已排上") else tr("没排上")) } ?: tr("没有"))
            l += tr("测试闹钟") + ": " + (if (pi(c, TEST_ID, false) != null) tr("已排上") else tr("没有"))
            val d = diag(c)
            l += tr("最近一次闹钟") + ": " + fmt(d.getLong("alarmAt", 0)) + " " + d.getString("alarm", "")
            l += tr("最近一次发通知") + ": " + fmt(d.getLong("postAt", 0)) + " " + d.getString("post", "")
            l += "Android " + Build.VERSION.SDK_INT + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · v" + BuildConfig.VERSION_CODE
        } catch (x: Throwable) { l += "error " + x.javaClass.simpleName + ": " + x.message }
        return l.joinToString("\n")
    }

    /** 测试通知：走和真提醒一样的闹钟，1 分钟后从后台发出，这样锁屏 / 回桌面也能验证后台提醒通不通。 */
    const val TEST_ID = "__test__"
    fun test(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val p = pi(c, TEST_ID, true) ?: return
        val at = System.currentTimeMillis() + 60_000L
        log(c, "test", "scheduled " + fmt(at))
        try {
            if (exactOk(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (x: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
    }
    fun postTest(c: Context) = post(c, 0x7e57, tr("测试提醒"), tr("能看到这条，说明知时在后台也能准时提醒。"))

    fun canNotify(c: Context) = Build.VERSION.SDK_INT < 33 ||
        c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("待办提醒"), NotificationManager.IMPORTANCE_HIGH))
    }
}

/** 闹钟到了：发通知；开机 / 更新后：把提醒重新排上。 */
class TodoReminder : BroadcastReceiver() {
    // 广播里抛异常会杀掉整个进程：兜住
    override fun onReceive(c: Context, i: Intent) { try { handle(c, i) } catch (x: Throwable) { android.util.Log.w("Zhishi", "待办提醒失败", x) } }
    private fun handle(c: Context, i: Intent) {
        when (i.action) {
            // 改时间 / 改时区：全天待办的提醒（当天 8:00）按新时区重排
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> { TodoAlarm.rescheduleAll(c); return }
            TodoAlarm.ACTION_DONE -> {
                val id = i.getStringExtra("id") ?: return
                TodoStore.all(c).firstOrNull { it.id == id }?.let { if (!it.done) TodoStore.toggle(c, it) }
                c.getSystemService(android.app.NotificationManager::class.java)?.cancel(i.getIntExtra("notif", id.hashCode()))
                return
            }
        }
        val id = i.getStringExtra("id") ?: run { TodoAlarm.log(c, "alarm", "no id"); return }
        if (id == TodoAlarm.TEST_ID) {
            TodoAlarm.log(c, "alarm", "test")
            if (TodoAlarm.canNotify(c)) TodoAlarm.postTest(c) else TodoAlarm.log(c, "post", "skipped: no permission")
            return
        }
        val t = TodoStore.all(c).firstOrNull { it.id == id } ?: run { TodoAlarm.log(c, "alarm", "todo gone"); return }
        TodoAlarm.log(c, "alarm", "todo")
        if (t.done || !t.remind) { TodoAlarm.log(c, "post", "skipped: done/off"); return }
        if (!TodoAlarm.canNotify(c)) { TodoAlarm.log(c, "post", "skipped: no permission"); return }
        val now = System.currentTimeMillis()
        if (TodoAlarm.wasFired(c, t)) { TodoAlarm.log(c, "post", "skipped: already fired"); return }
        val dueLine = if (Todos.endOf(t) <= now) tr("到截止时间了") else tr("截止：{0}", dueText(t.due, t.allDay))
        val text = listOf(dueLine, t.course, t.note).filter { it.isNotBlank() }.joinToString(" · ")
        TodoAlarm.markFired(c, t)
        TodoAlarm.post(c, id.hashCode(), t.title, text, t.id)
    }
}
