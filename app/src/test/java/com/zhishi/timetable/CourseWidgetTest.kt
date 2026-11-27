package com.zhishi.timetable

import org.junit.Assert.*
import org.junit.Test

class CourseWidgetTest {
    private fun s(n: String, f: Int, t: Int) = Slot(n, n, setOf(1), f, t)
    private val day = listOf(s("英语", 840, 935), s("高数", 610, 705), s("物理", 480, 575))

    // 待办改了怎么刷新小部件：数量变了只换头部（partiallyUpdate，不重绑列表）并通知列表；有没有列表变了才整个 updateAppWidget；只改了内容只通知列表
    @Test fun todoWidgetRefreshTrigger() {
        val first = CourseWidgets.todoUpdate(false, "", "s1", "", "h1", "", "r1")
        assertEquals(CourseWidgets.T_FULL, first.kind); assertTrue(first.rows)
        val count = CourseWidgets.todoUpdate(false, "s1", "s1", "h1", "h2", "r1", "r2")
        assertEquals(CourseWidgets.T_HEAD, count.kind); assertTrue(count.rows)
        val title = CourseWidgets.todoUpdate(false, "s1", "s1", "h1", "h1", "r1", "r2")
        assertEquals(CourseWidgets.T_NONE, title.kind); assertTrue(title.rows)
        val same = CourseWidgets.todoUpdate(false, "s1", "s1", "h1", "h1", "r1", "r1")
        assertEquals(CourseWidgets.T_NONE, same.kind); assertFalse(same.rows)
        assertEquals(CourseWidgets.T_FULL, CourseWidgets.todoUpdate(false, "s1", "s2", "h1", "h2", "r1", "r2").kind)   // 空 ↔ 有待办
        assertEquals(CourseWidgets.T_FULL, CourseWidgets.todoUpdate(true, "s1", "s1", "h1", "h1", "r1", "r1").kind)    // 改样式等强制
    }

    @Test fun empty() { val n = CourseWidgets.plan(emptyList(), 600); assertNull(n.main); assertFalse(n.doneAll) }
    @Test fun inClass() {
        val n = CourseWidgets.plan(day, 650)
        assertEquals("高数", n.main?.name); assertTrue(n.live); assertEquals("英语", n.next?.name); assertEquals(2, n.index); assertEquals(3, n.total)
    }
    @Test fun between() {
        val n = CourseWidgets.plan(day, 720)
        assertEquals("英语", n.main?.name); assertFalse(n.live); assertNull(n.next)
    }
    @Test fun before() { val n = CourseWidgets.plan(day, 400); assertEquals("物理", n.main?.name); assertEquals("高数", n.next?.name) }
    @Test fun done() { val n = CourseWidgets.plan(day, 1000); assertNull(n.main); assertTrue(n.doneAll) }

    // 小部件位图：按真实 dp × 密度画（以前密度封顶 2.75，Xiaomi 15 这种 3.0+ 的屏幕上会被放大发糊）
    @Test fun bitmapAtRealDensity() {
        val (w, h, k) = CourseWidgets.bitmapSize(320f, 156f, 3.0f, 1200L * 2670)
        assertEquals(960, w); assertEquals(468, h); assertEquals(3.0f, k, 0.0001f)
        // 超过内存预算（屏幕像素 × 1.5 的一半）才等比缩小
        val (w2, h2, k2) = CourseWidgets.bitmapSize(2000f, 2000f, 3.5f, 1080L * 2400)
        assertTrue(w2.toLong() * h2 <= 1080L * 2400 * 3 / 4 + 4000); assertTrue(k2 < 3.5f); assertEquals(w2, h2)
    }
    @Test fun styleNorm() {
        assertEquals("glass", WidgetStyle.norm(null)); assertEquals("glass", WidgetStyle.norm("x")); assertEquals("classic", WidgetStyle.norm("classic"))
        // 课表 / 待办小部件各自的设置：课表沿用原来的键（老用户设置不丢），待办用 todo_ 前缀
        assertEquals("clear", WidgetStyle.key(WidgetStyle.COURSE, "clear")); assertEquals("todo_clear", WidgetStyle.key(WidgetStyle.TODO, "clear"))
        assertEquals("style", WidgetStyle.key(WidgetStyle.COURSE, "style")); assertEquals("todo_tint", WidgetStyle.key(WidgetStyle.TODO, "tint"))
    }

    // 待办小部件：过期的在前，再今天、之后、没设截止；全做完 / 一条没有分得开
    private val tz = java.util.TimeZone.getTimeZone("Asia/Shanghai")
    private val d0 = 20_413L
    @Test fun todoOrder() {
        val now = Todos.at(d0, 700, tz)
        val l = listOf(
            Todo("nod", "没时间", created = 1),
            Todo("later", "之后", due = Todos.at(d0 + 2, 600, tz), created = 2),
            Todo("late", "过期", due = Todos.at(d0, 600, tz), created = 3),
            Todo("today", "今天", due = Todos.at(d0, 900, tz), created = 4),
            Todo("done", "完成", done = true, doneAt = 5, created = 5),
        )
        val p = CourseWidgets.todoPlan(l, now, tz)
        assertEquals(listOf("late", "today", "later", "nod"), p.list.map { it.id })
        assertEquals(1, p.overdue); assertEquals(1, p.today); assertFalse(p.allDone)
        assertEquals(listOf(0, 1, 2, 3), p.list.map { CourseWidgets.todoState(it, now, tz) })
        assertTrue(CourseWidgets.todoPlan(listOf(l[4]), now, tz).allDone)
        assertFalse(CourseWidgets.todoPlan(emptyList(), now, tz).allDone)
    }
    // 真机反馈：App 里 3 条（今天全天的取快递、明年的心理慕课、没设截止的准备雅思），小部件写「共 2 项」、第三行又是取快递。
    // 小部件的列表、数量和待办页（Todos.group）必须完全一致，没设截止的也在，没有重复。
    @Test fun todoWidgetMatchesTodoPage() {
        val now = Todos.at(d0, 10 * 60 + 26, tz)
        val l = listOf(
            Todo("kd", "取快递", due = Todos.allDayAt(d0, tz), allDay = true, course = "", note = "顺风", remind = true, created = 1),
            Todo("xl", "心理慕课", due = Todos.allDayAt(d0 + 433, tz), allDay = true, note = "雨课堂", created = 2),
            Todo("ys", "准备雅思", created = 3),
        )
        val p = CourseWidgets.todoPlan(l, now, tz)
        val g = Todos.group(l, now, tz)
        assertEquals(listOf("kd", "xl", "ys"), p.list.map { it.id })
        assertEquals(g.open.map { it.id }, p.list.map { it.id })
        assertEquals(3, p.list.size); assertEquals(p.list.size, p.list.map { it.id }.toSet().size)
        assertEquals(0, p.overdue); assertEquals(1, p.today)
        assertEquals(listOf(1, 2, 3), p.list.map { CourseWidgets.todoState(it, now, tz) })
        // 行的 id 稳定、互不相同（桌面按 id 对行，不会把旧位置的行套到新位置）
        val ids = p.list.map { TodoRows.stableId(it.id) }
        assertEquals(3, ids.toSet().size); assertEquals(TodoRows.stableId("kd"), TodoRows.stableId("kd"))
    }

    // 置顶和手动顺序：小部件和待办页一样
    @Test fun todoWidgetPinsAndOrder() {
        val now = Todos.at(d0, 700, tz)
        var l = listOf(
            Todo("a", "a", due = Todos.at(d0, 900, tz), created = 1),
            Todo("b", "b", due = Todos.at(d0, 1000, tz), created = 2),
            Todo("c", "c", created = 3),
            Todo("late", "late", due = Todos.at(d0, 600, tz), created = 4),
        )
        l = l.map { if (it.id == "c") Todos.togglePin(it) else it }
        l = Todos.reorder(l, listOf("b", "a"))
        val p = CourseWidgets.todoPlan(l, now, tz)
        assertEquals(listOf("c", "late", "b", "a"), p.list.map { it.id })
        assertEquals(Todos.group(l, now, tz).open.map { it.id }, p.list.map { it.id })
        assertEquals(1, p.overdue); assertEquals(2, p.today)
    }

    @Test fun nextTick() {
        val now = Todos.at(d0, 700, tz) + 20_000L
        val midnight = Todos.at(d0 + 1, 0, tz) + 5_000L
        assertNull(CourseWidgets.nextTick(now, false, emptyList(), 700, false, emptyList(), tz))
        // 只有待办小部件：最近一条到截止就重画，没有就到 0 点
        val t = Todo("a", "x", due = Todos.at(d0, 900, tz))
        assertEquals(Todos.at(d0, 900, tz) + 1_000L, CourseWidgets.nextTick(now, false, emptyList(), 700, true, listOf(t), tz))
        assertEquals(midnight, CourseWidgets.nextTick(now, false, emptyList(), 700, true, listOf(t.copy(done = true)), tz))
        // 课表小部件今天还有课：下一分钟
        assertEquals(Todos.at(d0, 701, tz), CourseWidgets.nextTick(now, true, listOf(s("物理", 480, 760)), 700, true, listOf(t), tz))
        assertEquals(midnight, CourseWidgets.nextTick(now, true, listOf(s("物理", 480, 575)), 700, false, emptyList(), tz))
    }
}
