package com.zhishi.timetable

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** AI 助手的每个工具：算出的新状态对不对、前后对比有没有、确认后撤销能不能回到原样。 */
class AiToolsTest {
    private val tz = TimeZone.getTimeZone("Asia/Shanghai")
    private val now = Todos.at(java.time.LocalDate.of(2026, 11, 26).toEpochDay(), 10 * 60, tz)   // 周四 10:00
    private val today = Todos.dayOfMs(now, tz)
    private var n = 0
    private val env = AiEnv(now, tz, listOf("chunyu" to "春雨", "yehang" to "夜航", "qingci" to "青瓷", "ying" to "樱"), { p -> p + "_n" + (++n) })
    private val st = listOf(480, 535, 600, 655, 840, 895, 960, 1015, 1140, 1195, 1250, 1305)
    private val en = st.map { it + 45 }
    private val base = AppState(
        courses = listOf(Slot("c1", "高等数学", setOf(1), 480, 580, room = "A101", teacher = "张伟"), Slot("c2", "大学英语", setOf(2, 4), 600, 700, room = "外302"),
            Slot("c4", "形势与政策", setOf(5), 1140, 1240)),
        todos = listOf(Todo("t1", "写实验报告", due = Todos.at(today + 2, 22 * 60, tz)), Todo("t2", "背单词"), Todo("t3", "买教材")),
        termStart = Term.mondayOf(today) - 77, secStarts = st, secEnds = en)

    private fun call(name: String, args: String) = AiToolCall("id_$name", name, args)
    private fun plan(name: String, args: String, s: AppState = base) = AiTools.plan(s, listOf(call(name, args)), env)
    /** 提议成功、有改动，并且撤销后完全回到原样。 */
    private fun ok(p: AiPlan): AppState {
        assertTrue(p.results.toString(), p.results.all { it.ok })
        assertTrue(p.hasChanges); assertTrue(p.changes.all { it.lines.isNotEmpty() })
        val b = AiBatch(p.before, p.after)
        val (undone, conflicts) = b.undo(p.after)
        assertEquals(p.before, undone); assertTrue(conflicts.isEmpty())
        return p.after
    }
    private fun err(p: AiPlan) { assertFalse(p.results.single().ok); assertFalse(p.hasChanges); assertEquals(p.before, p.after) }

    @Test fun addCourse() {
        val s = ok(plan(AiTools.ADD_COURSE, """{"name":"机器学习","weekdays":[3],"start_period":5,"end_period":6,"room":"B302","teacher":"王老师","weeks":"1-16","level":"important"}"""))
        val c = s.courses.last()
        assertEquals("机器学习", c.name); assertEquals(setOf(3), c.days); assertEquals(840 to 940, c.from to c.to)
        assertEquals((1..16).toSet(), c.weeks); assertEquals(2, c.level); assertEquals("B302", c.room)
        ok(plan(AiTools.ADD_COURSE, """{"name":"Yoga","weekdays":["Fri"],"start_time":"18:30","end_time":"20:00","odd_even":"odd"}""")).courses.last().let {
            assertEquals(setOf(5), it.days); assertEquals(1110 to 1200, it.from to it.to); assertEquals((1..20 step 2).toSet(), it.weeks) }
        err(plan(AiTools.ADD_COURSE, """{"name":"x"}"""))
        err(plan(AiTools.ADD_COURSE, """{"name":"x","weekdays":[1]}"""))
        err(plan(AiTools.ADD_COURSE, """{"name":"x","weekdays":[1],"start_period":30,"end_period":31}"""))
    }

    @Test fun updateCourse() {
        val s = ok(plan(AiTools.UPDATE_COURSE, """{"id":"c1","room":"A205","teacher":"李老师","weeks":"1-8"}"""))
        val c = s.courses.first { it.id == "c1" }
        assertEquals("A205", c.room); assertEquals("李老师", c.teacher); assertEquals((1..8).toSet(), c.weeks)
        // 只改一周两次课里的周四那次：拆开
        val s2 = ok(plan(AiTools.UPDATE_COURSE, """{"id":"c2","only_weekday":4,"room":"B101"}"""))
        assertEquals(setOf(2), s2.courses.first { it.id == "c2" }.days)
        assertEquals("B101", s2.courses.first { it.name == "大学英语" && it.days == setOf(4) }.room)
        err(plan(AiTools.UPDATE_COURSE, """{"id":"nope","room":"x"}"""))
        err(plan(AiTools.UPDATE_COURSE, """{"id":"c1","room":"A101"}"""))   // 没变化
    }

    @Test fun moveCourse() {
        val s = ok(plan(AiTools.MOVE_COURSE, """{"id":"c1","to_weekday":3,"to_start_period":3,"to_end_period":4}"""))
        assertEquals(setOf(3), s.courses.first { it.id == "c1" }.days); assertEquals(600 to 700, s.courses.first { it.id == "c1" }.let { it.from to it.to })
        val s2 = ok(plan(AiTools.MOVE_COURSE, """{"id":"c2","from_weekday":2,"to_weekday":5}"""))
        assertEquals(listOf(setOf(4), setOf(5)), s2.courses.filter { it.name == "大学英语" }.map { it.days })
        err(plan(AiTools.MOVE_COURSE, """{"id":"c2","from_weekday":3,"to_weekday":5}"""))
    }

    @Test fun deleteCourse() {
        assertFalse(ok(plan(AiTools.DELETE_COURSE, """{"id":"c4"}""")).courses.any { it.id == "c4" })
        assertEquals(setOf(4), ok(plan(AiTools.DELETE_COURSE, """{"id":"c2","only_weekday":2}""")).courses.first { it.id == "c2" }.days)
        // 按唯一的课名也能找到
        assertFalse(ok(plan(AiTools.DELETE_COURSE, """{"id":"高等数学"}""")).courses.any { it.id == "c1" })
        err(plan(AiTools.DELETE_COURSE, """{"id":"zz"}"""))
    }

    @Test fun term() {
        val s = ok(plan(AiTools.SET_TERM, """{"start_date":"2026-09-09","total_weeks":18}"""))
        assertEquals("2026-09-07", AiTools.ymd(s.termStart)); assertEquals(18, s.totalWeeks)
        assertEquals(3, AiTools.weekOf(ok(plan(AiTools.SET_TERM, """{"current_week":3}""")).termStart, today))
        err(plan(AiTools.SET_TERM, """{"total_weeks":99}"""))
        err(plan(AiTools.SET_TERM, """{}"""))
    }

    @Test fun periodTimes() {
        val s = ok(plan(AiTools.SET_PERIOD_TIMES, """{"periods":[{"period":1,"start":"8:10","end":"8:55"},{"period":2,"start":"09:05"}]}"""))
        assertEquals(490, s.secStarts[0]); assertEquals(535, s.secEnds[0]); assertEquals(545, s.secStarts[1])
        assertEquals(10, ok(plan(AiTools.SET_PERIOD_TIMES, """{"count":10}""")).secStarts.size)
        assertEquals(14, ok(plan(AiTools.SET_PERIOD_TIMES, """{"count":14}""")).secEnds.size)
        err(plan(AiTools.SET_PERIOD_TIMES, """{"periods":[{"period":1,"start":"9:00","end":"8:00"}]}"""))
    }

    @Test fun addTodo() {
        val s = ok(plan(AiTools.ADD_TODO, """{"title":"交线代作业","due":"2026-11-27 22:00","remind":true,"remind_before_minutes":30,"course":"线性代数","pinned":true}"""))
        val t = s.todos.last()
        assertEquals(today + 1, Todos.dayOf(t, tz)); assertEquals(22 * 60, Todos.minuteOf(t.due, tz)); assertFalse(t.allDay)
        assertTrue(t.remind); assertEquals(30, t.before); assertEquals("线性代数", t.course); assertTrue(t.pin); assertEquals(now, t.created)
        val d = ok(plan(AiTools.ADD_TODO, """{"title":"考试","due":"2026-12-01"}""")).todos.last()
        assertTrue(d.allDay); assertEquals(java.time.LocalDate.of(2026, 12, 1).toEpochDay(), Todos.dayOf(d, tz))
        assertEquals(-1L, ok(plan(AiTools.ADD_TODO, """{"title":"随便"}""")).todos.last().due)
        err(plan(AiTools.ADD_TODO, """{"title":"x","remind":true}"""))   // 提醒要有截止时间
        err(plan(AiTools.ADD_TODO, """{"title":"x","due":"明天"}"""))
    }

    @Test fun updateTodo() {
        val t = ok(plan(AiTools.UPDATE_TODO, """{"id":"t1","due":"2026-11-28 20:00","title":"写物理实验报告"}""")).todos.first { it.id == "t1" }
        assertEquals(20 * 60, Todos.minuteOf(t.due, tz)); assertEquals("写物理实验报告", t.title)
        assertEquals(-1L, ok(plan(AiTools.UPDATE_TODO, """{"id":"t1","clear_due":true}""")).todos.first { it.id == "t1" }.due)
        assertTrue(ok(plan(AiTools.UPDATE_TODO, """{"id":"t1","remind_before_minutes":60}""")).todos.first { it.id == "t1" }.let { it.remind && it.before == 60 })
        err(plan(AiTools.UPDATE_TODO, """{"id":"t2","remind":true}"""))
    }

    @Test fun completePinDeleteTodo() {
        val done = ok(plan(AiTools.COMPLETE_TODO, """{"id":"t2"}""")).todos.first { it.id == "t2" }
        assertTrue(done.done); assertEquals(now, done.doneAt)
        val s = AppState(todos = listOf(done))
        assertFalse(ok(plan(AiTools.COMPLETE_TODO, """{"id":"t2","done":false}""", s)).todos[0].done)
        val pinned = ok(plan(AiTools.PIN_TODO, """{"id":"t2"}""")).todos.first { it.id == "t2" }
        assertTrue(pinned.pin)
        assertFalse(ok(plan(AiTools.PIN_TODO, """{"id":"t2","pinned":false}""", AppState(todos = listOf(pinned.copy(order = 3))))).todos[0].pin)
        err(plan(AiTools.PIN_TODO, """{"id":"t2","pinned":false}"""))
        assertFalse(ok(plan(AiTools.DELETE_TODO, """{"id":"t3"}""")).todos.any { it.id == "t3" })
        assertFalse(ok(plan(AiTools.DELETE_TODO, """{"id":"买教材"}""")).todos.any { it.id == "t3" })
        err(plan(AiTools.DELETE_TODO, """{"id":"t9"}"""))
    }

    @Test fun skinAndRotation() {
        val s = ok(plan(AiTools.SET_SKIN, """{"skin":"夜航","mode":"dark"}"""))
        assertEquals("yehang", s.skin); assertEquals(2, s.mode)
        err(plan(AiTools.SET_SKIN, """{"skin":"不存在"}"""))
        err(plan(AiTools.SET_SKIN, """{"skin":"chunyu"}"""))
        val r = ok(plan(AiTools.SET_SKIN_ROTATION, """{"mode":"favorites","every":"weekly","add_favorites":["qingci","樱"]}"""))
        assertEquals(listOf("qingci", "ying"), r.favs); assertEquals(2, r.rotMode); assertEquals(2, r.rotEvery)
        assertEquals(listOf("ying"), ok(plan(AiTools.SET_SKIN_ROTATION, """{"remove_favorites":["qingci"]}""", r)).favs)
        assertEquals(1, ok(plan(AiTools.SET_SKIN_ROTATION, """{"mode":"random"}""")).rotMode)
    }

    @Test fun widgetAndSettings() {
        val s = ok(plan(AiTools.SET_WIDGET, """{"widget":"both","style":"classic","shade":"dark"}"""))
        assertEquals("classic", s.widgetCourse.style); assertEquals("classic", s.widgetTodo.style); assertEquals(2, s.widgetTodo.dmode)
        val t = ok(plan(AiTools.SET_WIDGET, """{"widget":"timetable","transparency":80,"glass_color":"skin"}"""))
        assertEquals(80, t.widgetCourse.clear); assertEquals("skin", t.widgetCourse.tint); assertEquals(base.widgetTodo, t.widgetTodo)
        err(plan(AiTools.SET_WIDGET, """{"widget":"phone","style":"classic"}"""))
        val a = ok(plan(AiTools.SET_APP, """{"show_weekend":false,"show_other_week_courses":"false","show_level_tags_in_widget":0}"""))
        assertFalse(a.showWeekend); assertFalse(a.showOff); assertFalse(a.widgetLevel); assertTrue(a.showLevel)
        err(plan(AiTools.SET_APP, """{"show_weekend":true}"""))
    }

    @Test fun navigationAndBadCalls() {
        val p = plan(AiTools.OPEN_PAGE, """{"page":"widgets"}""")
        assertEquals("widget", p.nav); assertFalse(p.hasChanges); assertTrue(p.results.single().ok)
        assertNull(plan(AiTools.OPEN_PAGE, """{"page":"moon"}""").nav)
        err(plan("format_disk", "{}"))
        err(plan(AiTools.ADD_TODO, "not json"))
        // 末尾多逗号的参数修一下照样能用
        assertTrue(plan(AiTools.ADD_TODO, """{"title":"x",}""").hasChanges)
        AiTools.PAGES.values.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test fun batchSeesEarlierCallsAndUndoSkipsLaterEdits() {
        val p = AiTools.plan(base, listOf(call(AiTools.ADD_COURSE, """{"name":"新课","weekdays":[6],"start_period":1,"end_period":2}"""),
            AiToolCall("x2", AiTools.SET_SKIN, """{"skin":"ying"}"""), AiToolCall("x3", AiTools.DELETE_TODO, """{"id":"t1"}""")), env)
        assertEquals(3, p.changes.size)
        val b = AiBatch(p.before, p.after)
        assertEquals(setOf(StatePart.COURSES, StatePart.SKIN, StatePart.TODOS), b.parts)
        // 应用后用户又手动换了皮肤：撤销不动皮肤，其余撤回
        val later = p.after.copy(skin = "qingci")
        val (u, conflicts) = b.undo(later)
        assertEquals(setOf(StatePart.SKIN), conflicts)
        assertEquals("qingci", u.skin); assertEquals(base.courses, u.courses); assertEquals(base.todos, u.todos)
    }

    @Test fun toolSchemasAreValid() {
        val names = AiTools.TOOLS.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.containsAll(AiTools.MUTATING)); assertTrue(AiTools.OPEN_PAGE in names)
        for (t in AiTools.TOOLS) {
            assertEquals("object", t.parameters.getString("type"))
            assertTrue(t.description.isNotBlank())
            JSONObject(t.parameters.toString())
            assertTrue(Regex("^[a-z_]+$").matches(t.name))
        }
    }

    @Test fun helpers() {
        assertEquals(490, AiTools.parseClock("8:10")); assertEquals(490, AiTools.parseClock("08：10")); assertNull(AiTools.parseClock("25:00"))
        assertEquals(java.time.LocalDate.of(2026, 1, 2).toEpochDay() to 21 * 60 + 5, AiTools.parseDate("2026-01-02T21:05:00"))
        assertEquals(3, AiTools.dayOf("周三")); assertEquals(7, AiTools.dayOf("星期日")); assertEquals(2, AiTools.dayOf("Tuesday")); assertNull(AiTools.dayOf("8"))
        assertEquals(emptySet<Int>(), AiTools.weeksOf("1-20", null, 20))
        assertEquals((2..16 step 2).toSet(), AiTools.weeksOf("2-16", "even", 20))
    }
}
