package com.zhishi.timetable

import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/*
 * AI 助手能做的事（工具）：模型只能「提议」，这里把提议算成一份新状态 + 前后对比，
 * 用户在确认卡片上点「确认」后才由 AiApply（安卓那层）写进存储；最后一批可以撤销。
 * 模型永远不直接写数据。本文件是纯 Kotlin（不碰 Android），单测和沙盒里的真实接口测试都直接用。
 */

/** 一种小部件（课表 / 待办）的外观：样式、玻璃颜色、透明度、深浅。 */
data class WidgetLook(val style: String = WidgetStyleIds.GLASS, val tint: String = WidgetStyleIds.WALL, val clear: Int = 65, val dmode: Int = 0)

/** 和 WidgetStyle 里一致的取值（WidgetStyle 在安卓那层，这里抄一份常量免得依赖它）。 */
object WidgetStyleIds { const val GLASS = "glass"; const val CLASSIC = "classic"; const val WALL = "wall"; const val SKIN = "skin" }

/** AI 能看到、能改的全部状态（一份快照）。 */
data class AppState(
    val courses: List<Slot> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val termStart: Long = -1L, val totalWeeks: Int = 20,
    val secStarts: List<Int> = emptyList(), val secEnds: List<Int> = emptyList(),
    val skin: String = "chunyu", val mode: Int = 0,
    val rotMode: Int = 0, val rotEvery: Int = 1, val favs: List<String> = emptyList(),
    val widgetCourse: WidgetLook = WidgetLook(), val widgetTodo: WidgetLook = WidgetLook(),
    val showWeekend: Boolean = true, val showOff: Boolean = true, val showLevel: Boolean = true, val widgetLevel: Boolean = true,
)

/** 状态按块存，写入和撤销都按块来。 */
enum class StatePart { COURSES, TODOS, TERM, SECS, SKIN, ROTATION, WIDGET_COURSE, WIDGET_TODO, DISPLAY }

object AiState {
    fun part(s: AppState, p: StatePart): Any = when (p) {
        StatePart.COURSES -> s.courses
        StatePart.TODOS -> s.todos
        StatePart.TERM -> s.termStart to s.totalWeeks
        StatePart.SECS -> s.secStarts to s.secEnds
        StatePart.SKIN -> s.skin to s.mode
        StatePart.ROTATION -> Triple(s.rotMode, s.rotEvery, s.favs)
        StatePart.WIDGET_COURSE -> s.widgetCourse
        StatePart.WIDGET_TODO -> s.widgetTodo
        StatePart.DISPLAY -> listOf(s.showWeekend, s.showOff, s.showLevel, s.widgetLevel)
    }
    fun changed(a: AppState, b: AppState): Set<StatePart> = StatePart.values().filter { part(a, it) != part(b, it) }.toSet()
    /** 把 from 里这几块拷到 to 上。 */
    fun take(to: AppState, from: AppState, parts: Set<StatePart>): AppState {
        var r = to
        for (p in parts) r = when (p) {
            StatePart.COURSES -> r.copy(courses = from.courses)
            StatePart.TODOS -> r.copy(todos = from.todos)
            StatePart.TERM -> r.copy(termStart = from.termStart, totalWeeks = from.totalWeeks)
            StatePart.SECS -> r.copy(secStarts = from.secStarts, secEnds = from.secEnds)
            StatePart.SKIN -> r.copy(skin = from.skin, mode = from.mode)
            StatePart.ROTATION -> r.copy(rotMode = from.rotMode, rotEvery = from.rotEvery, favs = from.favs)
            StatePart.WIDGET_COURSE -> r.copy(widgetCourse = from.widgetCourse)
            StatePart.WIDGET_TODO -> r.copy(widgetTodo = from.widgetTodo)
            StatePart.DISPLAY -> r.copy(showWeekend = from.showWeekend, showOff = from.showOff, showLevel = from.showLevel, widgetLevel = from.widgetLevel)
        }
        return r
    }
}

/** 确认后应用过的一批：撤销 = 把改过的那几块换回 before。 */
class AiBatch(val before: AppState, val after: AppState) {
    val parts = AiState.changed(before, after)
    /** 撤销（纯函数）：应用之后又被改过的块不动（免得把用户后来的修改冲掉），记在 conflicts 里。 */
    fun undo(current: AppState): Pair<AppState, Set<StatePart>> {
        val clean = parts.filter { AiState.part(current, it) == AiState.part(after, it) }.toSet()
        return AiState.take(current, before, clean) to (parts - clean)
    }
}

/** 确认卡片上的一行前后对比；before 为 null = 新增，after 为 null = 删除。 */
data class DiffLine(val label: String, val before: String?, val after: String?)
/** 一项改动：标题 + 若干行对比。 */
data class AiChange(val title: String, val lines: List<DiffLine>)
/** 一个工具调用的结果（回给模型的话）。 */
data class AiToolResult(val callId: String, val name: String, val ok: Boolean, val message: String)

/** 一批提议：前后状态、给人看的改动、每个工具的结果、要打开的页面。 */
class AiPlan(val before: AppState, val after: AppState, val changes: List<AiChange>, val results: List<AiToolResult>, val nav: String?,
             /** 每项改动动过的东西（改之前 / 之后），撤销列表和依赖用。 */
             val edits: List<AiEdit> = emptyList()) {
    val hasChanges get() = changes.isNotEmpty() && AiState.changed(before, after).isNotEmpty()
}

/** 工具执行需要的环境（时间、时区、皮肤列表、新 id）。 */
class AiEnv(
    val now: Long = System.currentTimeMillis(),
    val tz: TimeZone = TimeZone.getDefault(),
    /** 皮肤 id 到（中文）名字。 */
    val skins: List<Pair<String, String>> = emptyList(),
    val newId: (String) -> String = { p -> p + System.nanoTime().toString(36) },
)

class ToolError(msg: String) : Exception(msg)

/** 宽松地读参数：数字可以是字符串，布尔可以是 "true"。 */
class Args(val j: JSONObject) {
    fun has(k: String) = j.has(k) && !j.isNull(k) && j.opt(k).toString().isNotEmpty()
    fun str(k: String): String? = if (has(k)) j.opt(k).toString().trim() else null
    fun int(k: String): Int? = if (!has(k)) null else when (val v = j.opt(k)) {
        is Number -> v.toInt()
        else -> v.toString().trim().let { t -> t.toIntOrNull() ?: Regex("-?\\d+").find(t)?.value?.toIntOrNull() }
    }
    fun bool(k: String): Boolean? = if (!has(k)) null else when (val v = j.opt(k)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        else -> v.toString().trim().lowercase().let { it == "true" || it == "yes" || it == "1" || it == "on" || it == "是" }
    }
    fun list(k: String): List<String>? = if (!has(k)) null else when (val v = j.opt(k)) {
        is JSONArray -> (0 until v.length()).map { v.opt(it).toString().trim() }.filter { it.isNotEmpty() }
        else -> v.toString().split(',', '，', '、', ';').map { it.trim() }.filter { it.isNotEmpty() }
    }
    fun need(k: String): String = str(k)?.takeIf { it.isNotEmpty() } ?: throw ToolError("missing required argument '$k'")
}

object AiTools {
    // ---------- 名字 ----------
    const val ADD_COURSE = "add_course"; const val UPDATE_COURSE = "update_course"; const val MOVE_COURSE = "move_course"; const val DELETE_COURSE = "delete_course"
    const val SET_TERM = "set_term"; const val SET_PERIOD_TIMES = "set_period_times"
    const val ADD_TODO = "add_todo"; const val UPDATE_TODO = "update_todo"; const val COMPLETE_TODO = "complete_todo"; const val DELETE_TODO = "delete_todo"; const val PIN_TODO = "pin_todo"
    const val SET_SKIN = "set_skin"; const val SET_SKIN_ROTATION = "set_skin_rotation"
    const val SET_WIDGET = "set_widget_appearance"; const val SET_APP = "set_app_settings"
    const val OPEN_PAGE = "open_page"

    /** open_page 的页面 → 应用内跳转（Nav.open 的写法）。 */
    val PAGES = linkedMapOf(
        "timetable" to "table:0", "todo" to "todo", "calendar" to "calendar", "me" to "me", "widgets" to "widget", "about" to "about",
        "ai_settings" to "ai_settings", "import" to "table:8", "school_import" to "table:1", "term_settings" to "table:2", "period_times" to "table:7",
        "add_course" to "table:3", "add_todo" to "todo:1",
    )

    private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()) = JSONObject().put("type", "object")
        .put("properties", JSONObject().also { p -> props.forEach { p.put(it.first, it.second) } })
        .also { if (required.isNotEmpty()) it.put("required", JSONArray(required)) }
    private fun s(d: String, enum: List<String>? = null) = JSONObject().put("type", "string").put("description", d).also { if (enum != null) it.put("enum", JSONArray(enum)) }
    private fun i(d: String) = JSONObject().put("type", "integer").put("description", d)
    private fun b(d: String) = JSONObject().put("type", "boolean").put("description", d)
    private fun ints(d: String) = JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")).put("description", d)
    private fun strs(d: String) = JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", d)

    private val courseFields = arrayOf(
        "name" to s("Course name"),
        "weekdays" to ints("Days of week, 1=Monday … 7=Sunday"),
        "start_period" to i("First period number (1-based), as in the period table"),
        "end_period" to i("Last period number (inclusive)"),
        "start_time" to s("Start time HH:mm (use instead of periods when the class has clock times)"),
        "end_time" to s("End time HH:mm"),
        "weeks" to s("Teaching weeks, e.g. \"1-16\" or \"1-8,10-16\"; empty = every week"),
        "odd_even" to s("Only odd or even weeks", listOf("all", "odd", "even")),
        "room" to s("Classroom"), "teacher" to s("Teacher"),
        "level" to s("Display tag", listOf("important", "normal", "easy")),
        "note" to s("Note"),
    )

    /** 给模型的工具定义（OpenAI function calling 的 JSON Schema）。 */
    val TOOLS: List<AiTool> by lazy { listOf(
        AiTool(ADD_COURSE, "Add a course to the timetable. Give either start/end period or start/end time.",
            obj(*courseFields, required = listOf("name", "weekdays"))),
        AiTool(UPDATE_COURSE, "Change fields of an existing course (only the fields given change). Use the course id from the context. " +
            "If the course meets on several weekdays and only one of them should change, pass only_weekday.",
            obj("id" to s("Course id from the context"), "only_weekday" to i("Change only this weekday's meeting (splits it off)"), *courseFields, required = listOf("id"))),
        AiTool(MOVE_COURSE, "Move a course to another weekday and/or periods/time.",
            obj("id" to s("Course id"), "from_weekday" to i("Which weekday's meeting to move when the course meets on several days"),
                "to_weekday" to i("New weekday 1-7"), "to_start_period" to i("New first period"), "to_end_period" to i("New last period"),
                "to_start_time" to s("New start HH:mm"), "to_end_time" to s("New end HH:mm"), required = listOf("id"))),
        AiTool(DELETE_COURSE, "Delete a course (or only one weekday's meeting of it).",
            obj("id" to s("Course id"), "only_weekday" to i("Delete only this weekday's meeting"), required = listOf("id"))),
        AiTool(SET_TERM, "Set the term: the date of any day in week 1, or which week it is now, and/or the total number of weeks.",
            obj("start_date" to s("Any date in week 1, YYYY-MM-DD"), "current_week" to i("This week's number"), "total_weeks" to i("Total weeks in the term (1-30)"))),
        AiTool(SET_PERIOD_TIMES, "Set class period start/end times. Only the listed periods change; count truncates or extends the number of periods.",
            obj("periods" to JSONObject().put("type", "array").put("description", "Periods to set")
                .put("items", obj("period" to i("Period number, 1-based"), "start" to s("HH:mm"), "end" to s("HH:mm"), required = listOf("period"))),
                "count" to i("Total number of periods per day (optional)"))),
        AiTool(ADD_TODO, "Add a to-do item.",
            obj("title" to s("Title"), "note" to s("Note"), "due" to s("Due date/time, local, \"YYYY-MM-DD HH:mm\" or \"YYYY-MM-DD\" for all-day"),
                "all_day" to b("Due date only, no time"), "remind" to b("Send a reminder notification"),
                "remind_before_minutes" to i("Remind this many minutes before due (0, 5, 15, 30, 60, 1440)"),
                "course" to s("Related course name"), "pinned" to b("Pin to top"), required = listOf("title"))),
        AiTool(UPDATE_TODO, "Change fields of a to-do (only the fields given change).",
            obj("id" to s("To-do id from the context"), "title" to s("Title"), "note" to s("Note"), "due" to s("New due \"YYYY-MM-DD HH:mm\" or \"YYYY-MM-DD\""),
                "all_day" to b("All-day"), "clear_due" to b("Remove the due date"), "remind" to b("Reminder on/off"),
                "remind_before_minutes" to i("Minutes before due"), "course" to s("Related course name, empty string to unlink"), required = listOf("id"))),
        AiTool(COMPLETE_TODO, "Mark a to-do done (or not done).", obj("id" to s("To-do id"), "done" to b("true = done (default), false = reopen"), required = listOf("id"))),
        AiTool(DELETE_TODO, "Delete a to-do.", obj("id" to s("To-do id"), required = listOf("id"))),
        AiTool(PIN_TODO, "Pin or unpin a to-do.", obj("id" to s("To-do id"), "pinned" to b("true = pin (default), false = unpin"), required = listOf("id"))),
        AiTool(SET_SKIN, "Switch the app skin and/or light/dark mode. Skins are listed in the context.",
            obj("skin" to s("Skin id or name"), "mode" to s("Light/dark mode", listOf("system", "light", "dark")))),
        AiTool(SET_SKIN_ROTATION, "Skin favourites and automatic rotation.",
            obj("mode" to s("Rotation mode", listOf("off", "random", "favorites")), "every" to s("How often", listOf("launch", "daily", "weekly")),
                "add_favorites" to strs("Skin ids/names to add to favourites"), "remove_favorites" to strs("Skin ids/names to remove"),
                "favorites" to strs("Replace the whole favourites list"))),
        AiTool(SET_WIDGET, "Change home-screen widget appearance (timetable widget, to-do widget, or both).",
            obj("widget" to s("Which widget", listOf("timetable", "todo", "both")), "style" to s("Style", listOf("glass", "classic")),
                "glass_color" to s("Glass colour source (glass style only)", listOf("wallpaper", "skin")),
                "transparency" to i("Glass transparency 0-100 (%)"), "shade" to s("Light/dark", listOf("follow_app", "light", "dark")), required = listOf("widget"))),
        AiTool(SET_APP, "Change timetable display settings.",
            obj("show_weekend" to b("Show Saturday/Sunday columns"), "show_other_week_courses" to b("Show courses not held this week (greyed)"),
                "show_level_tags" to b("Show important/normal/easy tags on course blocks"), "show_level_tags_in_widget" to b("Show those tags on widgets"))),
        AiTool(OPEN_PAGE, "Open a page of the app for the user (no data change).",
            obj("page" to s("Page", PAGES.keys.toList()), required = listOf("page"))),
    ) }

    /** 只在手机上有意义的工具（MCP 服务器不提供）：open_page = 打开 App 里的某一页，远程的智能体没有界面可打开。 */
    val LOCAL_ONLY = setOf(OPEN_PAGE)

    /**
     * 和 MCP 服务器共用的一份工具定义（tools/ai-tools.json，单测 AiToolsSchemaTest 保证它和这里一致；
     * zhishi-cloud 的 src/app-tools.json 是它的拷贝，那边的测试要求每个工具都有同名、参数不少的 MCP 工具）。
     */
    fun schemaJson(skins: List<Pair<String, String>>): JSONObject = JSONObject()
        .put("version", 1)
        .put("tools", JSONArray().also { a -> TOOLS.forEach { t ->
            a.put(JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters)
                .put("mutating", t.name in MUTATING).put("local_only", t.name in LOCAL_ONLY)) } })
        .put("skins", JSONArray().also { a -> skins.forEach { (id, n) -> a.put(JSONObject().put("id", id).put("name", n)) } })

    val MUTATING = setOf(ADD_COURSE, UPDATE_COURSE, MOVE_COURSE, DELETE_COURSE, SET_TERM, SET_PERIOD_TIMES, ADD_TODO, UPDATE_TODO, COMPLETE_TODO, DELETE_TODO, PIN_TODO,
        SET_SKIN, SET_SKIN_ROTATION, SET_WIDGET, SET_APP)

    // ---------- 小工具（纯函数） ----------
    fun clock(m: Int): String { val x = ((m % 1440) + 1440) % 1440; return "${x / 60}:${"%02d".format(x % 60)}" }
    fun parseClock(t0: String?): Int? {
        val t = t0?.trim()?.replace('：', ':')?.replace('.', ':') ?: return null
        val m = Regex("^(\\d{1,2})(?::(\\d{2}))?$").find(t) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toIntOrNull() ?: 0
        return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }
    /** "2026-12-01" / "2026-12-01 18:30" / "2026-12-01T18:30:00" → (epoch 天, 分钟或 null)。 */
    fun parseDate(t0: String?): Pair<Long, Int?>? {
        val t = t0?.trim() ?: return null
        val m = Regex("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?:[ T](\\d{1,2})[:：](\\d{2}))?").find(t) ?: return null
        val d = try { java.time.LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()).toEpochDay() } catch (x: Exception) { return null }
        val min = if (m.groupValues[4].isNotEmpty()) (m.groupValues[4].toInt().coerceIn(0, 23) * 60 + m.groupValues[5].toInt().coerceIn(0, 59)) else null
        return d to min
    }
    fun ymd(day: Long): String = java.time.LocalDate.ofEpochDay(day).toString()
    fun dayOf(v: String): Int? {
        val t = v.trim().lowercase()
        t.toIntOrNull()?.let { return it.takeIf { d -> d in 1..7 } }
        Regex("(?:周|星期|礼拜|週)([一二三四五六日天])").find(t)?.let { return "一二三四五六日天".indexOf(it.groupValues[1]).let { i -> if (i >= 6) 7 else i + 1 } }
        val en = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        en.indexOfFirst { t.startsWith(it) }.takeIf { it >= 0 }?.let { return it + 1 }
        return null
    }
    fun mondayOf(epochDay: Long): Long = epochDay - Math.floorMod(epochDay + 3, 7L)
    fun weekOf(term: Long, day: Long) = Math.floorDiv(mondayOf(day) - term, 7L).toInt() + 1
    fun secSpan(starts: List<Int>, ends: List<Int>, from: Int, to: Int): Pair<Int, Int>? {
        if (starts.isEmpty()) return null
        val a = starts.indices.firstOrNull { starts[it] == from } ?: return null
        val b = ends.indices.firstOrNull { ends[it] == to && it >= a } ?: return null
        return (a + 1) to (b + 1)
    }
    fun levelOf(v: String?): Int? = when (v?.trim()?.lowercase()) {
        null, "" -> null; "important", "重要", "2" -> 2; "normal", "普通", "1" -> 1; "easy", "water", "水课", "0" -> 0; else -> null
    }

    /** 周次文字 + 单双周 → 周次集合（空 = 每周）。 */
    fun weeksOf(text: String?, oddEven: String?, total: Int): Set<Int> {
        val oe = oddEven?.trim()?.lowercase()
        val odd = oe == "odd" || oe == "单" || oe == "单周"; val even = oe == "even" || oe == "双" || oe == "双周"
        val t = text?.trim().orEmpty()
        var w: Set<Int> = if (t.isEmpty() || t == "all" || t == "每周") (1..total).toSet() else Slot.parseWeeks(t, maxOf(total, 30))
        if (w.isEmpty()) w = (1..total).toSet()
        if (odd) w = w.filter { it % 2 == 1 }.toSet()
        if (even) w = w.filter { it % 2 == 0 }.toSet()
        return if (w == (1..total).toSet()) emptySet() else w
    }

    // ---------- 给人看的文字 ----------
    private fun daysTxt(d: Set<Int>) = d.sorted().joinToString(" ") { dayLabel(it) }
    fun timeTxt(s: AppState, f: Int, t: Int): String {
        val sp = secSpan(s.secStarts, s.secEnds, f, t)
        return (if (sp != null) tr("第{0}-{1}节", sp.first, sp.second) + " " else "") + clock(f) + "–" + clock(t)
    }
    private fun levelTxt(l: Int) = Slot.LEVELS.getOrElse(l) { "" }
    private fun courseLines(s: AppState, c: Slot) = listOfNotNull(
        DiffLine(tr("星期"), null, daysTxt(c.days)), DiffLine(tr("时间"), null, timeTxt(s, c.from, c.to)),
        DiffLine(tr("周次"), null, Slot.weeksText(c.weeks)),
        c.room.takeIf { it.isNotBlank() }?.let { DiffLine(tr("教室"), null, it) }, c.teacher.takeIf { it.isNotBlank() }?.let { DiffLine(tr("老师"), null, it) },
        DiffLine(tr("标签"), null, levelTxt(c.level)), c.note.takeIf { it.isNotBlank() }?.let { DiffLine(tr("备注"), null, it) })
    private fun courseDiff(s: AppState, a: Slot, b: Slot): List<DiffLine> {
        val out = ArrayList<DiffLine>()
        fun cmp(l: String, x: String, y: String) { if (x != y) out += DiffLine(l, x.ifBlank { "—" }, y.ifBlank { "—" }) }
        cmp(tr("课程"), a.name, b.name); cmp(tr("星期"), daysTxt(a.days), daysTxt(b.days)); cmp(tr("时间"), timeTxt(s, a.from, a.to), timeTxt(s, b.from, b.to))
        cmp(tr("周次"), Slot.weeksText(a.weeks), Slot.weeksText(b.weeks)); cmp(tr("教室"), a.room, b.room); cmp(tr("老师"), a.teacher, b.teacher)
        cmp(tr("标签"), levelTxt(a.level), levelTxt(b.level)); cmp(tr("备注"), a.note, b.note)
        return out
    }
    fun dueTxt(t: Todo, tz: TimeZone): String {
        if (t.due < 0) return tr("没有截止时间")
        val day = Todos.dayOf(t, tz)!!
        return ymd(day) + if (t.allDay) " " + tr("全天") else " " + clock(Todos.minuteOf(t.due, tz))
    }
    /** 给模型看的截止时间（不翻译）。 */
    fun dueTxtPlain(t: Todo, tz: TimeZone): String {
        if (t.due < 0) return ""
        val day = Todos.dayOf(t, tz)!!
        return ymd(day) + if (t.allDay) " (all day)" else " " + clock(Todos.minuteOf(t.due, tz))
    }
    private fun remindTxt(t: Todo) = if (!t.remind) tr("关") else if (t.before == 0) tr("到点提醒") else tr("提前 {0} 分钟", t.before)
    private fun todoDiff(a: Todo, b: Todo, tz: TimeZone): List<DiffLine> {
        val out = ArrayList<DiffLine>()
        fun cmp(l: String, x: String, y: String) { if (x != y) out += DiffLine(l, x.ifBlank { "—" }, y.ifBlank { "—" }) }
        cmp(tr("标题"), a.title, b.title); cmp(tr("备注"), a.note, b.note); cmp(tr("截止"), dueTxt(a, tz), dueTxt(b, tz))
        cmp(tr("提醒"), remindTxt(a), remindTxt(b)); cmp(tr("关联课程"), a.course, b.course)
        cmp(tr("完成"), if (a.done) tr("是") else tr("否"), if (b.done) tr("是") else tr("否"))
        cmp(tr("置顶"), if (a.pin) tr("是") else tr("否"), if (b.pin) tr("是") else tr("否"))
        return out
    }

    /**
     * 一样东西从 a 变成 b（智能体改的、撤销列表里用）：标题 + 前后对比。a / b 是 Slot / Todo，null = 不存在。
     * who = 谁改的（智能体名字），拼进标题前面。
     */
    fun describe(s: AppState, a: Any?, b: Any?, tz: TimeZone): AiChange = when {
        a is Slot? && b is Slot? && (a != null || b != null) -> when {
            a == null -> AiChange(tr("新增课程：{0}", b!!.name), courseLines(s, b))
            b == null -> AiChange(tr("删除课程：{0}", a.name), courseLines(s, a).map { DiffLine(it.label, it.after, null) })
            else -> AiChange(tr("修改课程：{0}", a.name), courseDiff(s, a, b))
        }
        a is Todo? && b is Todo? && (a != null || b != null) -> when {
            a == null -> AiChange(tr("新增待办：{0}", b!!.title), listOfNotNull(DiffLine(tr("截止"), null, dueTxt(b, tz)), if (b.remind) DiffLine(tr("提醒"), null, remindTxt(b)) else null,
                b.course.takeIf { it.isNotBlank() }?.let { DiffLine(tr("关联课程"), null, it) }, b.note.takeIf { it.isNotBlank() }?.let { DiffLine(tr("备注"), null, it) }))
            b == null -> AiChange(tr("删除待办：{0}", a.title), listOf(DiffLine(tr("截止"), dueTxt(a, tz), null)))
            else -> AiChange((when { a.done != b.done -> if (b.done) tr("完成待办：{0}") else tr("恢复待办：{0}"); a.pin != b.pin -> if (b.pin) tr("置顶待办：{0}") else tr("取消置顶：{0}"); else -> tr("修改待办：{0}") }).replace("{0}", a.title), todoDiff(a, b, tz))
        }
        else -> AiChange(tr("学期设置"), emptyList())
    }

    // ---------- 查找 ----------
    private fun course(s: AppState, a: Args): Slot {
        val id = a.need("id")
        return s.courses.firstOrNull { it.id == id } ?: s.courses.filter { it.name == id }.let { if (it.size == 1) it[0] else null }
            ?: throw ToolError("no course with id '$id' (use an id from the context)")
    }
    private fun todo(s: AppState, a: Args): Todo {
        val id = a.need("id")
        return s.todos.firstOrNull { it.id == id } ?: s.todos.filter { it.title == id }.let { if (it.size == 1) it[0] else null }
            ?: throw ToolError("no to-do with id '$id' (use an id from the context)")
    }
    private fun skinId(env: AiEnv, v: String): String {
        val t = v.trim()
        return env.skins.firstOrNull { it.first.equals(t, true) || it.second == t || tr(it.second) == t }?.first
            ?: throw ToolError("unknown skin '$v'; valid: ${env.skins.joinToString { it.first }}")
    }

    /** 时间：优先具体时刻，其次节次；都没有返回 null。 */
    private fun timeFrom(s: AppState, a: Args, pre: String = ""): Pair<Int, Int>? {
        val st = parseClock(a.str(pre + "start_time")); val en = parseClock(a.str(pre + "end_time"))
        if (st != null && en != null) { if (en <= st) throw ToolError("end_time must be after start_time"); return st to en }
        val p1 = a.int(pre + "start_period"); val p2 = a.int(pre + "end_period") ?: p1
        if (p1 != null && p2 != null) {
            val n = s.secStarts.size
            if (n == 0) throw ToolError("period times are not set; give start_time/end_time")
            if (p1 !in 1..n || p2 !in 1..n) throw ToolError("periods must be within 1..$n")
            val a1 = minOf(p1, p2); val b1 = maxOf(p1, p2)
            return s.secStarts[a1 - 1] to s.secEnds.getOrElse(b1 - 1) { s.secStarts[b1 - 1] + 45 }
        }
        if (st != null) return st to (st + 45)
        return null
    }
    private fun days(a: Args, k: String = "weekdays"): Set<Int>? {
        val l = a.list(k) ?: a.str("weekday")?.let { listOf(it) } ?: return null
        val d = l.map { dayOf(it) ?: throw ToolError("bad weekday '$it' (1=Mon…7=Sun)") }.toSet()
        if (d.isEmpty()) throw ToolError("weekdays is empty"); return d
    }

    /** 把一节课里某一天单独拆出来（返回 剩下的、拆出来的；只上一天时剩下的是 null）。 */
    private fun split(c: Slot, d: Int?, env: AiEnv): Pair<Slot?, Slot> {
        if (d == null || c.days.size <= 1) return null to c
        if (d !in c.days) throw ToolError("course ${c.id} does not meet on weekday $d")
        return c.copy(days = c.days - d) to c.copy(id = env.newId("c"), days = setOf(d))
    }

    // ---------- 一个工具 ----------
    private class Step(val s: AppState, val change: AiChange?, val msg: String)

    private fun run(s: AppState, name: String, a: Args, env: AiEnv): Step = when (name) {
        ADD_COURSE -> {
            val nm = a.need("name").take(30)
            val d = days(a) ?: throw ToolError("weekdays is required")
            val (f, t) = timeFrom(s, a) ?: throw ToolError("give start_period/end_period or start_time/end_time")
            val c = Slot(env.newId("c"), nm, d, f, t, level = levelOf(a.str("level")) ?: 1, room = a.str("room").orEmpty().take(30), teacher = a.str("teacher").orEmpty().take(30),
                weeks = weeksOf(a.str("weeks"), a.str("odd_even"), s.totalWeeks), note = a.str("note").orEmpty())
            Step(s.copy(courses = s.courses + c), AiChange(tr("新增课程：{0}", nm), courseLines(s, c)), "added course id=${c.id}")
        }
        UPDATE_COURSE, MOVE_COURSE -> {
            val c0 = course(s, a)
            val move = name == MOVE_COURSE
            val (rest, c) = split(c0, a.int(if (move) "from_weekday" else "only_weekday"), env)
            var n = c
            if (!move) {
                a.str("name")?.let { n = n.copy(name = it.take(30)) }
                a.str("room")?.let { n = n.copy(room = it.take(30)) }
                a.str("teacher")?.let { n = n.copy(teacher = it.take(30)) }
                a.str("note")?.let { n = n.copy(note = it) }
                levelOf(a.str("level"))?.let { n = n.copy(level = it) }
                if (a.has("weeks") || a.has("odd_even")) n = n.copy(weeks = weeksOf(a.str("weeks") ?: if (n.weeks.isEmpty()) "" else n.weeks.sorted().joinToString(","), a.str("odd_even"), s.totalWeeks))
                days(a)?.let { n = n.copy(days = it) }
                timeFrom(s, a)?.let { (f, t) -> n = n.copy(from = f, to = t) }
            } else {
                a.int("to_weekday")?.let { d -> if (d !in 1..7) throw ToolError("to_weekday must be 1..7"); n = n.copy(days = setOf(d)) }
                timeFrom(s, a, "to_")?.let { (f, t) -> n = n.copy(from = f, to = t) }
            }
            val diff = courseDiff(s, c0.copy(days = c.days), n)
            if (diff.isEmpty()) throw ToolError("nothing to change for course ${c0.id}")
            val list = s.courses.flatMap { if (it.id != c0.id) listOf(it) else listOfNotNull(rest, n) }
            Step(s.copy(courses = list), AiChange((if (move) tr("移动课程：{0}") else tr("修改课程：{0}")).replace("{0}", c0.name), diff), "updated course ${n.id}")
        }
        DELETE_COURSE -> {
            val c0 = course(s, a)
            val d = a.int("only_weekday")
            val (rest, c) = split(c0, d, env)
            val list = s.courses.flatMap { if (it.id != c0.id) listOf(it) else listOfNotNull(rest) }
            Step(s.copy(courses = list), AiChange(tr("删除课程：{0}", c0.name), courseLines(s, c).map { DiffLine(it.label, it.after, null) }), "deleted course ${c0.id}")
        }
        SET_TERM -> {
            var n = s
            val lines = ArrayList<DiffLine>()
            val today = Todos.dayOfMs(env.now, env.tz)
            a.str("start_date")?.let { v -> val (d, _) = parseDate(v) ?: throw ToolError("start_date must be YYYY-MM-DD"); n = n.copy(termStart = mondayOf(d)) }
            a.int("current_week")?.let { w -> if (w !in 1..60) throw ToolError("current_week must be 1..60"); n = n.copy(termStart = mondayOf(today) - (w - 1) * 7L) }
            a.int("total_weeks")?.let { w -> if (w !in 1..30) throw ToolError("total_weeks must be 1..30"); n = n.copy(totalWeeks = w) }
            if (n.termStart != s.termStart) {
                lines += DiffLine(tr("第 1 周周一"), if (s.termStart < 0) tr("没设") else ymd(s.termStart), ymd(n.termStart))
                lines += DiffLine(tr("这周是"), if (s.termStart < 0) "—" else tr("第{0}周", weekOf(s.termStart, today)), tr("第{0}周", weekOf(n.termStart, today)))
            }
            if (n.totalWeeks != s.totalWeeks) lines += DiffLine(tr("总周数"), "${s.totalWeeks}", "${n.totalWeeks}")
            if (lines.isEmpty()) throw ToolError("nothing to change")
            Step(n, AiChange(tr("学期设置"), lines), "term updated")
        }
        SET_PERIOD_TIMES -> {
            val st = s.secStarts.toMutableList(); val en = s.secEnds.toMutableList()
            while (en.size < st.size) en += st[en.size] + 45
            val arr = a.j.optJSONArray("periods") ?: JSONArray()
            for (k in 0 until arr.length()) {
                val p = Args(arr.optJSONObject(k) ?: continue)
                val no = p.int("period") ?: throw ToolError("period number missing")
                if (no !in 1..20) throw ToolError("period must be 1..20")
                while (st.size < no) { val last = en.lastOrNull() ?: 470; st += last + 10; en += last + 55 }
                val f = p.str("start")?.let { parseClock(it) ?: throw ToolError("bad start '$it'") } ?: st[no - 1]
                val t = p.str("end")?.let { parseClock(it) ?: throw ToolError("bad end '$it'") } ?: maxOf(en[no - 1], f + 5)
                if (t <= f) throw ToolError("period $no: end must be after start")
                st[no - 1] = f; en[no - 1] = t
            }
            a.int("count")?.let { n -> if (n !in 1..20) throw ToolError("count must be 1..20")
                while (st.size > n) { st.removeAt(st.lastIndex); en.removeAt(en.lastIndex) }
                while (st.size < n) { val last = en.lastOrNull() ?: 470; st += last + 10; en += last + 55 } }
            val lines = ArrayList<DiffLine>()
            for (k in 0 until maxOf(st.size, s.secStarts.size)) {
                val b = if (k < s.secStarts.size) clock(s.secStarts[k]) + "–" + clock(s.secEnds.getOrElse(k) { s.secStarts[k] + 45 }) else null
                val af = if (k < st.size) clock(st[k]) + "–" + clock(en[k]) else null
                if (b != af) lines += DiffLine(tr("第{0}节", k + 1), b, af)
            }
            if (lines.isEmpty()) throw ToolError("nothing to change")
            Step(s.copy(secStarts = st, secEnds = en), AiChange(tr("每节课时间"), lines), "period times updated")
        }
        ADD_TODO -> {
            val title = a.need("title").take(80)
            var t = Todo(env.newId("t"), title, note = a.str("note").orEmpty(), created = env.now, course = a.str("course").orEmpty())
            a.str("due")?.let { v -> t = withDue(t, v, a.bool("all_day"), env) }
            if (a.bool("remind") == true || a.has("remind_before_minutes")) {
                if (t.due < 0) throw ToolError("a reminder needs a due date")
                t = t.copy(remind = a.bool("remind") ?: true, before = (a.int("remind_before_minutes") ?: 0).coerceIn(0, 7 * 1440))
            }
            if (a.bool("pinned") == true) t = t.copy(pin = true)
            val lines = listOfNotNull(DiffLine(tr("截止"), null, dueTxt(t, env.tz)), if (t.remind) DiffLine(tr("提醒"), null, remindTxt(t)) else null,
                t.course.takeIf { it.isNotBlank() }?.let { DiffLine(tr("关联课程"), null, it) }, t.note.takeIf { it.isNotBlank() }?.let { DiffLine(tr("备注"), null, it) },
                if (t.pin) DiffLine(tr("置顶"), null, tr("是")) else null)
            Step(s.copy(todos = s.todos + t), AiChange(tr("新增待办：{0}", title), lines), "added to-do id=${t.id}")
        }
        UPDATE_TODO, COMPLETE_TODO, PIN_TODO -> {
            val t0 = todo(s, a)
            var t = t0
            when (name) {
                COMPLETE_TODO -> { val d = a.bool("done") ?: true; t = t.copy(done = d, doneAt = if (d) env.now else 0L) }
                PIN_TODO -> { val p = a.bool("pinned") ?: true; if (p != t.pin) t = t.copy(pin = p, order = 0) }
                else -> {
                    a.str("title")?.let { t = t.copy(title = it.take(80)) }
                    if (a.j.has("note")) t = t.copy(note = a.str("note").orEmpty())
                    if (a.j.has("course")) t = t.copy(course = a.str("course").orEmpty())
                    if (a.bool("clear_due") == true) t = Todos.cleared(t)
                    a.str("due")?.let { v -> t = withDue(t, v, a.bool("all_day"), env) }
                    if (a.str("due") == null && a.has("all_day") && t.due >= 0) t = Todos.withAllDay(t, a.bool("all_day") == true, env.now, env.tz)
                    a.bool("remind")?.let { r -> if (r && t.due < 0) throw ToolError("a reminder needs a due date"); t = t.copy(remind = r) }
                    a.int("remind_before_minutes")?.let { m -> if (t.due < 0) throw ToolError("a reminder needs a due date"); t = t.copy(remind = true, before = m.coerceIn(0, 7 * 1440)) }
                }
            }
            val diff = todoDiff(t0, t, env.tz)
            if (diff.isEmpty()) throw ToolError("nothing to change for to-do ${t0.id}")
            val title = when (name) { COMPLETE_TODO -> if (t.done) tr("完成待办：{0}") else tr("恢复待办：{0}"); PIN_TODO -> if (t.pin) tr("置顶待办：{0}") else tr("取消置顶：{0}"); else -> tr("修改待办：{0}") }
            Step(s.copy(todos = s.todos.map { if (it.id == t0.id) t else it }), AiChange(title.replace("{0}", t0.title), diff), "to-do ${t0.id} updated")
        }
        DELETE_TODO -> {
            val t0 = todo(s, a)
            Step(s.copy(todos = s.todos.filter { it.id != t0.id }), AiChange(tr("删除待办：{0}", t0.title), listOf(DiffLine(tr("截止"), dueTxt(t0, env.tz), null))), "deleted to-do ${t0.id}")
        }
        SET_SKIN -> {
            var n = s
            a.str("skin")?.let { n = n.copy(skin = skinId(env, it)) }
            a.str("mode")?.let { m -> n = n.copy(mode = when (m.lowercase()) { "system", "auto", "跟随系统" -> 0; "light", "浅色" -> 1; "dark", "深色" -> 2; else -> throw ToolError("mode must be system/light/dark") }) }
            val lines = ArrayList<DiffLine>()
            fun nm(id: String) = tr(env.skins.firstOrNull { it.first == id }?.second ?: id)
            if (n.skin != s.skin) lines += DiffLine(tr("皮肤"), nm(s.skin), nm(n.skin))
            val modes = listOf(tr("跟随系统"), tr("浅色"), tr("深色"))
            if (n.mode != s.mode) lines += DiffLine(tr("深浅"), modes[s.mode], modes[n.mode])
            if (lines.isEmpty()) throw ToolError("nothing to change")
            Step(n, AiChange(tr("皮肤"), lines), "skin updated")
        }
        SET_SKIN_ROTATION -> {
            var n = s
            a.str("mode")?.let { m -> n = n.copy(rotMode = when (m.lowercase()) { "off" -> 0; "random", "all" -> 1; "favorites", "favourites", "fav" -> 2; else -> throw ToolError("mode must be off/random/favorites") }) }
            a.str("every")?.let { m -> n = n.copy(rotEvery = when (m.lowercase()) { "launch", "open" -> 0; "daily", "day" -> 1; "weekly", "week" -> 2; else -> throw ToolError("every must be launch/daily/weekly") }) }
            a.list("favorites")?.let { l -> n = n.copy(favs = l.map { skinId(env, it) }.distinct()) }
            a.list("add_favorites")?.let { l -> n = n.copy(favs = (n.favs + l.map { skinId(env, it) }).distinct()) }
            a.list("remove_favorites")?.let { l -> val r = l.map { skinId(env, it) }.toSet(); n = n.copy(favs = n.favs.filter { it !in r }) }
            val modes = listOf(tr("关闭"), tr("全部随机"), tr("收藏轮换")); val every = listOf(tr("每次打开"), tr("每天"), tr("每周"))
            fun favTxt(l: List<String>) = l.joinToString("、") { id -> tr(env.skins.firstOrNull { it.first == id }?.second ?: id) }.ifEmpty { "—" }
            val lines = ArrayList<DiffLine>()
            if (n.rotMode != s.rotMode) lines += DiffLine(tr("皮肤轮换"), modes[s.rotMode], modes[n.rotMode])
            if (n.rotEvery != s.rotEvery) lines += DiffLine(tr("多久换一次"), every[s.rotEvery], every[n.rotEvery])
            if (n.favs != s.favs) lines += DiffLine(tr("收藏的皮肤"), favTxt(s.favs), favTxt(n.favs))
            if (lines.isEmpty()) throw ToolError("nothing to change")
            Step(n, AiChange(tr("皮肤轮换"), lines), "rotation updated" + if (n.rotMode == 2 && n.favs.size < 2) " (note: favourites rotation needs at least 2 favourite skins)" else "")
        }
        SET_WIDGET -> {
            val which = a.need("widget").lowercase()
            val kinds = when (which) { "timetable", "course", "课表" -> listOf(true); "todo", "待办" -> listOf(false); "both", "all" -> listOf(true, false); else -> throw ToolError("widget must be timetable/todo/both") }
            fun edit(w: WidgetLook): WidgetLook {
                var x = w
                a.str("style")?.let { v -> x = x.copy(style = when (v.lowercase()) { "glass", "液态玻璃" -> WidgetStyleIds.GLASS; "classic", "经典" -> WidgetStyleIds.CLASSIC; else -> throw ToolError("style must be glass/classic") }) }
                a.str("glass_color")?.let { v -> x = x.copy(tint = when (v.lowercase()) { "wallpaper", "wall" -> WidgetStyleIds.WALL; "skin" -> WidgetStyleIds.SKIN; else -> throw ToolError("glass_color must be wallpaper/skin") }) }
                a.int("transparency")?.let { v -> x = x.copy(clear = v.coerceIn(0, 100)) }
                a.str("shade")?.let { v -> x = x.copy(dmode = when (v.lowercase()) { "follow_app", "follow", "app" -> 0; "light" -> 1; "dark" -> 2; else -> throw ToolError("shade must be follow_app/light/dark") }) }
                return x
            }
            fun lines(a0: WidgetLook, b0: WidgetLook): List<DiffLine> {
                val o = ArrayList<DiffLine>()
                fun st(v: String) = if (v == WidgetStyleIds.CLASSIC) tr("经典") else tr("液态玻璃")
                fun ti(v: String) = if (v == WidgetStyleIds.SKIN) tr("跟随皮肤") else tr("跟随壁纸")
                val sh = listOf(tr("跟随 App"), tr("浅色"), tr("深色"))
                if (a0.style != b0.style) o += DiffLine(tr("小部件样式"), st(a0.style), st(b0.style))
                if (a0.tint != b0.tint) o += DiffLine(tr("玻璃颜色"), ti(a0.tint), ti(b0.tint))
                if (a0.clear != b0.clear) o += DiffLine(tr("透明度"), "${a0.clear}%", "${b0.clear}%")
                if (a0.dmode != b0.dmode) o += DiffLine(tr("深浅"), sh[a0.dmode], sh[b0.dmode])
                return o
            }
            var n = s; val all = ArrayList<DiffLine>()
            for (course in kinds) {
                val w0 = if (course) s.widgetCourse else s.widgetTodo; val w1 = edit(w0)
                val l = lines(w0, w1).map { it.copy(label = (if (course) tr("课表小部件") else tr("待办小部件")) + " · " + it.label) }
                all += l
                n = if (course) n.copy(widgetCourse = w1) else n.copy(widgetTodo = w1)
            }
            if (all.isEmpty()) throw ToolError("nothing to change")
            Step(n, AiChange(tr("桌面小部件"), all), "widget appearance updated")
        }
        SET_APP -> {
            var n = s
            a.bool("show_weekend")?.let { n = n.copy(showWeekend = it) }
            a.bool("show_other_week_courses")?.let { n = n.copy(showOff = it) }
            a.bool("show_level_tags")?.let { n = n.copy(showLevel = it) }
            a.bool("show_level_tags_in_widget")?.let { n = n.copy(widgetLevel = it) }
            fun yn(b: Boolean) = if (b) tr("显示") else tr("不显示")
            val lines = ArrayList<DiffLine>()
            if (n.showWeekend != s.showWeekend) lines += DiffLine(tr("周末"), yn(s.showWeekend), yn(n.showWeekend))
            if (n.showOff != s.showOff) lines += DiffLine(tr("非本周的课"), yn(s.showOff), yn(n.showOff))
            if (n.showLevel != s.showLevel) lines += DiffLine(tr("课块上的标签"), yn(s.showLevel), yn(n.showLevel))
            if (n.widgetLevel != s.widgetLevel) lines += DiffLine(tr("小部件上的标签"), yn(s.widgetLevel), yn(n.widgetLevel))
            if (lines.isEmpty()) throw ToolError("nothing to change")
            Step(n, AiChange(tr("显示设置"), lines), "settings updated")
        }
        else -> throw ToolError("unknown tool '$name'")
    }

    private fun withDue(t: Todo, v: String, allDay: Boolean?, env: AiEnv): Todo {
        val (d, m) = parseDate(v) ?: throw ToolError("due must be \"YYYY-MM-DD HH:mm\" or \"YYYY-MM-DD\"")
        return if (m == null || allDay == true) t.copy(due = Todos.allDayAt(d, env.tz), allDay = true) else t.copy(due = Todos.at(d, m, env.tz), allDay = false)
    }

    /** 把模型的一组工具调用依次算到状态上（后一个看得到前一个的结果）。出错的那个跳过并把原因回给模型。 */
    fun plan(state: AppState, calls: List<AiToolCall>, env: AiEnv): AiPlan {
        var s = state; val changes = ArrayList<AiChange>(); val results = ArrayList<AiToolResult>(); var nav: String? = null
        val edits = ArrayList<AiEdit>()
        for (c in calls) {
            val args = try { Args(JSONObject(repairJson(c.arguments).ifBlank { "{}" })) } catch (x: Exception) { results += AiToolResult(c.id, c.name, false, "error: arguments are not valid JSON"); continue }
            if (c.name == OPEN_PAGE) {
                val p = args.str("page")?.lowercase()
                val dest = PAGES[p]
                if (dest == null) results += AiToolResult(c.id, c.name, false, "error: unknown page '$p'; valid: ${PAGES.keys.joinToString()}")
                else { nav = dest; results += AiToolResult(c.id, c.name, true, "opened page $p") }
                continue
            }
            try {
                val st = run(s, c.name, args, env)
                st.change?.let { ch -> changes += ch; Entities.edit(ch, s, st.s).takeIf { it.keys.isNotEmpty() }?.let { edits += it } }
                s = st.s
                results += AiToolResult(c.id, c.name, true, st.msg)
            } catch (e: ToolError) { results += AiToolResult(c.id, c.name, false, "error: " + e.message) }
            catch (e: Exception) { results += AiToolResult(c.id, c.name, false, "error: " + (e.message ?: e.javaClass.simpleName)) }
        }
        return AiPlan(state, s, changes, results, nav, edits)
    }

    /** 常见的坏 JSON 小修：去掉 ``` 围栏、末尾多余的逗号、中文引号。 */
    fun repairJson(s0: String): String {
        var s = s0.trim()
        if (s.startsWith("```")) s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim().removeSuffix("```").trim()
        s = s.replace('“', '"').replace('”', '"')
        s = Regex(",\\s*([}\\]])").replace(s, "$1")
        return s
    }
}
