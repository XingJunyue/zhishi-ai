package com.zhishi.timetable

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** 待办：存储往返、分组（今天 / 即将 / 已完成）、按天归类、备份里带上待办；日历页按天取课。 */
class TodoTest {
    private val tz = TimeZone.getTimeZone("Asia/Shanghai")
    private val day = 20_413L                       // 2025-11-21 之类的某一天（epoch 天）
    private fun at(d: Long, m: Int) = Todos.at(d, m, tz)

    @Test fun jsonRoundTrip() {
        val l = listOf(
            Todo("a", "交作业", "第三章", at(day, 22 * 60), true, "高等数学", created = 5),
            Todo("b", "借书", created = 6),
            Todo("c", "体测", due = at(day - 1, 600), done = true, doneAt = 99, created = 1),
        )
        val back = Todos.parse(Todos.toJson(l).toString())
        assertEquals(l, back)
        // 坏数据不崩、空标题 / 空 id 的丢掉
        assertTrue(Todos.parse("not json").isEmpty())
        assertEquals(1, Todos.parse("""[{"id":"x","t":"ok"},{"id":"","t":"no"},{"id":"y","t":""}]""").size)
    }

    @Test fun grouping() {
        val now = at(day, 700)                         // 今天 11:40
        val l = listOf(
            Todo("later", "下周", due = at(day + 5, 480), created = 1),
            Todo("tonight", "今晚", due = at(day, 22 * 60), created = 2),
            Todo("overdue", "昨天的", due = at(day - 1, 600), created = 3),
            Todo("nodue2", "没日期 2", created = 9),
            Todo("nodue1", "没日期 1", created = 4),
            Todo("tomorrow", "明天", due = at(day + 1, 480), created = 5),
            Todo("done1", "早完成", done = true, doneAt = 10, created = 6),
            Todo("done2", "晚完成", due = at(day, 600), done = true, doneAt = 20, created = 7),
            Todo("morning", "今天早上（已过）", due = at(day, 480), created = 8),
            Todo("allday", "今天全天", due = Todos.allDayAt(day, tz), allDay = true, created = 10),
            Todo("ydayAll", "昨天全天", due = Todos.allDayAt(day - 1, tz), allDay = true, created = 11),
        )
        val g = Todos.group(l, now, tz)
        // 过期的单独一组（以前混在「今天」里，标题写「今天 N 项」却包括上周过期的）
        assertEquals(listOf("overdue", "ydayAll", "morning"), g.overdue.map { it.id })
        // 今天：有时间的按时间，全天的排在最后；今天全天的在今天结束前都不算过期
        assertEquals(listOf("tonight", "allday"), g.today.map { it.id })
        assertEquals(listOf("tomorrow", "later", "nodue1", "nodue2"), g.upcoming.map { it.id })
        assertEquals(listOf("done2", "done1"), g.done.map { it.id })
        assertTrue(Todos.overdue(l[2], now, tz))
        assertFalse(Todos.overdue(l[1], now, tz))
        assertFalse(Todos.overdue(l[7], at(day + 3, 0), tz))   // 完成的不算过期
        assertFalse(Todos.overdue(l[9], at(day, 23 * 60 + 59) + 30_000, tz))   // 全天：23:59:30 还没过期
        assertTrue(Todos.overdue(l[9], at(day + 1, 0), tz))
    }

    // 置顶：没完成的置顶排最上面（按默认顺序：过期 → 今天 → 之后），其余照旧分组；完成的不算置顶
    @Test fun pinnedOnTop() {
        val now = at(day, 700)
        val l = listOf(
            Todo("nodue", "没日期", created = 1, pin = true),
            Todo("tonight", "今晚", due = at(day, 22 * 60), created = 2),
            Todo("old", "过期", due = at(day - 1, 600), created = 3, pin = true),
            Todo("tomorrow", "明天", due = at(day + 1, 480), created = 4),
            Todo("donepin", "完成的", done = true, doneAt = 5, pin = true, created = 5),
            Todo("late2", "也过期", due = at(day - 2, 600), created = 6),
        )
        val g = Todos.group(l, now, tz)
        assertEquals(listOf("old", "nodue"), g.pinned.map { it.id })
        assertEquals(listOf("late2"), g.overdue.map { it.id })
        assertEquals(listOf("tonight"), g.today.map { it.id })
        assertEquals(listOf("tomorrow"), g.upcoming.map { it.id })
        assertEquals(listOf("donepin"), g.done.map { it.id })
        assertEquals(listOf("old", "nodue", "late2", "tonight", "tomorrow"), g.open.map { it.id })
        // 数量按日期算（置顶的也算进去）：已过期 2、今天 1、之后 2
        assertEquals(2, g.lateN); assertEquals(1, g.todayN); assertEquals(2, g.laterN)
    }

    // 手动顺序：组里排过的按手动顺序；没排过的（新加的）按默认顺序排在这一组最前面；置顶 / 取消置顶后在新组里排最前
    @Test fun manualOrder() {
        val now = at(day, 700)
        var l = listOf(
            Todo("t1", "1", due = at(day, 800), created = 1),
            Todo("t2", "2", due = at(day, 900), created = 2),
            Todo("t3", "3", due = at(day, 1000), created = 3),
            Todo("n1", "无1", created = 4),
            Todo("n2", "无2", created = 5),
        )
        assertEquals(listOf("t1", "t2", "t3"), Todos.group(l, now, tz).today.map { it.id })
        l = Todos.reorder(l, listOf("t3", "t1", "t2"))
        assertEquals(listOf("t3", "t1", "t2"), Todos.group(l, now, tz).today.map { it.id })
        assertEquals(listOf(1, 2, 3), listOf("t3", "t1", "t2").map { id -> l.first { it.id == id }.order })
        // 新加一条今天的：排在今天组最前，手动排过的顺序不变
        l = l + Todo("new", "新", due = at(day, 1200), created = 9)
        assertEquals(listOf("new", "t3", "t1", "t2"), Todos.group(l, now, tz).today.map { it.id })
        // 别的组不受影响
        assertEquals(listOf("n1", "n2"), Todos.group(l, now, tz).upcoming.map { it.id })
        l = Todos.reorder(l, listOf("n2", "n1"))
        assertEquals(listOf("n2", "n1"), Todos.group(l, now, tz).upcoming.map { it.id })
        // 置顶：清掉手动顺序，进置顶组；取消置顶回到原来的组最前
        l = l.map { if (it.id == "t2") Todos.togglePin(it) else it }
        val g = Todos.group(l, now, tz)
        assertEquals(listOf("t2"), g.pinned.map { it.id }); assertEquals(0, g.pinned[0].order)
        assertEquals(listOf("new", "t3", "t1"), g.today.map { it.id })
        l = l.map { if (it.id == "t2") Todos.togglePin(it) else it }
        assertEquals(listOf("t2", "new", "t3", "t1"), Todos.group(l, now, tz).today.map { it.id })   // 没排过的两条按时间
    }

    // 老数据没有 pn / or：不置顶、没排过，顺序和以前一样；新字段能存能读
    @Test fun pinOrderMigration() {
        val old = Todos.parse("""[{"id":"a","t":"老的","due":-1,"cr":2},{"id":"b","t":"老的2","due":-1,"cr":1}]""")
        assertTrue(old.all { !it.pin && it.order == 0 })
        assertEquals(listOf("b", "a"), Todos.group(old, at(day, 600), tz).upcoming.map { it.id })
        val t = Todo("x", "新", pin = true, order = 3)
        assertEquals(t, Todos.parse(Todos.toJson(listOf(t)).toString()).single())
        assertEquals(0, Todos.parse("""[{"id":"x","t":"坏","or":-5}]""").single().order)
    }

    // 皮肤轮换：全部随机不会选到当前这套；收藏轮换按收藏顺序一套接一套；周期按天 / 周（周一开始）
    @Test fun skinRotation() {
        val all = listOf("a", "b", "c")
        for (r in 0 until 10) { val n = SkinRotation.pick(SkinRotation.ALL, emptyList(), all, "b", r); assertNotNull(n); assertNotEquals("b", n) }
        assertNull(SkinRotation.pick(SkinRotation.ALL, emptyList(), listOf("a"), "a", 0))
        assertNull(SkinRotation.pick(SkinRotation.OFF, all, all, "a", 0))
        assertEquals("c", SkinRotation.pick(SkinRotation.FAV, listOf("a", "c"), all, "a", 0))
        assertEquals("a", SkinRotation.pick(SkinRotation.FAV, listOf("a", "c"), all, "c", 0))
        assertEquals("a", SkinRotation.pick(SkinRotation.FAV, listOf("a", "c"), all, "b", 0))   // 当前不在收藏里：从第一套开始
        assertNull(SkinRotation.pick(SkinRotation.FAV, emptyList(), all, "a", 0))
        assertNull(SkinRotation.pick(SkinRotation.FAV, listOf("a"), all, "a", 0))
        assertEquals("a", SkinRotation.pick(SkinRotation.FAV, listOf("a"), all, "b", 0))
        // 2025-11-24 是周一（epoch 天 20416）：周一到周日同一周
        val mon = java.time.LocalDate.of(2025, 11, 24).toEpochDay()
        assertEquals(SkinRotation.period(SkinRotation.WEEK, mon), SkinRotation.period(SkinRotation.WEEK, mon + 6))
        assertNotEquals(SkinRotation.period(SkinRotation.WEEK, mon), SkinRotation.period(SkinRotation.WEEK, mon - 1))
        assertTrue(SkinRotation.due(SkinRotation.DAY, mon - 1, mon)); assertFalse(SkinRotation.due(SkinRotation.DAY, mon, mon))
        assertFalse(SkinRotation.due(SkinRotation.WEEK, SkinRotation.period(SkinRotation.WEEK, mon), mon + 3))
        assertTrue(SkinRotation.due(SkinRotation.WEEK, SkinRotation.period(SkinRotation.WEEK, mon), mon + 7))
        assertFalse(SkinRotation.due(SkinRotation.OPEN, -1, mon))
        assertEquals(listOf("chunyu"), SkinRotation.parseFavs("chunyu,nope,,chunyu"))
    }

    @Test fun editingTime() {
        val now = at(day, 600)
        val base = Todo("a", "交报告", created = 1)
        // 新设日期：没有时刻就给默认（别的日子 22:00；今天 = 一小时后取整点）
        assertEquals(at(day + 2, 22 * 60), Todos.withDay(base, day + 2, now, tz).due)
        assertEquals(at(day, 11 * 60), Todos.withDay(base, day, now, tz).due)
        assertEquals(at(day, 12 * 60), Todos.withDay(base, day, at(day, 610), tz).due)
        // 换日期保留原来的时刻（以前改日期必须重选时间，取消就丢）
        val timed = base.copy(due = at(day, 8 * 60 + 25))
        assertEquals(at(day + 3, 8 * 60 + 25), Todos.withDay(timed, day + 3, now, tz).due)
        // 换时刻保留日期，精确到分钟
        assertEquals(at(day + 1, 23 * 60 + 7), Todos.withMinute(timed.copy(due = at(day + 1, 600)), 23 * 60 + 7, now, tz).due)
        // 没日期直接拨时刻：今天还没到就是今天，过了就是明天
        assertEquals(at(day, 18 * 60), Todos.withMinute(base, 18 * 60, now, tz).due)
        assertEquals(at(day + 1, 9 * 60), Todos.withMinute(base, 9 * 60, now, tz).due)
        // 全天开关
        val ad = Todos.withAllDay(timed, true, now, tz)
        assertTrue(ad.allDay); assertEquals(day, Todos.dayOf(ad, tz)); assertEquals(Todos.allDayAt(day, tz), ad.due)
        assertEquals(at(day + 3, 22 * 60 + 0).let { Todos.dayOf(Todo("x", "x", due = it), tz) }, Todos.dayOf(Todos.withDay(ad, day + 3, now, tz), tz))
        assertTrue(Todos.withDay(ad, day + 3, now, tz).allDay)
        val back = Todos.withAllDay(ad, false, now, tz)
        assertFalse(back.allDay); assertEquals(at(day, 11 * 60), back.due)
        // 清除：提醒、全天、提前量一起清掉
        val cl = Todos.cleared(ad.copy(remind = true, before = 60))
        assertEquals(-1L, cl.due); assertFalse(cl.remind); assertFalse(cl.allDay); assertEquals(0, cl.before)
    }

    @Test fun presets() {
        val base = Todo("a", "x")
        val morning = at(day, 9 * 60 + 2)
        assertEquals(at(day, 10 * 60 + 5), Todos.preset(base, Todos.Preset.IN_1H, morning, tz).due)
        assertEquals(at(day, 21 * 60), Todos.preset(base, Todos.Preset.TONIGHT, morning, tz).due)
        // 已经 22:10 了：「今晚」= 一小时后取整（最晚 23:55），不会设成已经过去的 21:00
        val late = at(day, 22 * 60 + 10)
        assertEquals(at(day, 23 * 60 + 10), Todos.preset(base, Todos.Preset.TONIGHT, late, tz).due)
        assertEquals(at(day, 23 * 60 + 55), Todos.preset(base, Todos.Preset.TONIGHT, at(day, 23 * 60 + 30), tz).due)
        assertTrue(Todos.preset(base, Todos.Preset.TONIGHT, at(day, 23 * 60 + 58), tz).due > at(day, 23 * 60 + 58))
        // +1h 跨天
        assertEquals(at(day + 1, 0), Todos.preset(base, Todos.Preset.IN_1H, at(day, 22 * 60 + 58), tz).due)
        // 今天 / 明天 / 下周一：没设时刻就是全天，设了就保留时刻
        val td = Todos.preset(base, Todos.Preset.TODAY, morning, tz)
        assertTrue(td.allDay); assertEquals(day, Todos.dayOf(td, tz))
        val timed = base.copy(due = at(day + 9, 8 * 60))
        assertEquals(at(day + 1, 8 * 60), Todos.preset(timed, Todos.Preset.TOMORROW, morning, tz).due)
        // 今天的那个时刻已经过了：变成今天全天，而不是设成已过期的时间
        val past = Todos.preset(timed, Todos.Preset.TODAY, morning, tz)
        assertTrue(past.allDay); assertEquals(day, Todos.dayOf(past, tz))
        val nw = Todos.preset(base, Todos.Preset.NEXT_WEEK, morning, tz)
        assertEquals(Term.mondayOf(day) + 7, Todos.dayOf(nw, tz)); assertTrue(nw.allDay)
    }

    @Test fun reminders() {
        val now = at(day, 600)
        val t = Todo("a", "x", due = at(day, 20 * 60), remind = true)
        assertEquals(at(day, 20 * 60), Todos.alarmAt(t, now, tz))
        assertEquals(at(day, 19 * 60 + 45), Todos.alarmAt(t.copy(before = 15), now, tz))
        assertEquals(at(day - 1, 20 * 60), Todos.alarmAt(t.copy(before = 1440), now - 86_400_000L * 2, tz))
        // 提醒时刻过了但截止还没到（截止前 10 分钟才设「提前 30 分钟」）：马上提醒一次，而不是一声不吭
        val soon = t.copy(due = now + 10 * 60_000L, before = 30)
        assertEquals(now + 1000L, Todos.alarmAt(soon, now, tz))
        // 截止都过了、完成了、没开提醒：不提醒
        assertNull(Todos.alarmAt(t.copy(due = now - 1), now, tz))
        assertNull(Todos.alarmAt(t.copy(done = true), now, tz))
        assertNull(Todos.alarmAt(t.copy(remind = false), now, tz))
        // 全天：当天 8:00 往前算；当天 9:00 打开提醒（8:00 已过、当天还没结束）→ 马上一次
        val ad = Todo("b", "x", due = Todos.allDayAt(day + 1, tz), allDay = true, remind = true)
        assertEquals(at(day + 1, 8 * 60), Todos.alarmAt(ad, now, tz))
        assertEquals(at(day, 8 * 60), Todos.alarmAt(ad.copy(before = 1440), at(day, 7 * 60), tz))
        assertEquals(at(day + 1, 9 * 60) + 1000L, Todos.alarmAt(ad, at(day + 1, 9 * 60), tz))
    }

    @Test fun jsonKeepsNewFields() {
        val t = Todo("a", "x", due = Todos.allDayAt(day, tz), allDay = true, remind = true, before = 60)
        assertEquals(listOf(t), Todos.parse(Todos.toJson(listOf(t)).toString()))
        // 旧数据没有这两个字段：默认不是全天、到点提醒
        val old = Todos.parse("""[{"id":"x","t":"ok","due":5,"re":true}]""").single()
        assertFalse(old.allDay); assertEquals(0, old.before)
    }

    @Test fun nextClassOfLinkedCourse() {
        val term = Term.mondayOf(day)
        val wd = Math.floorMod(day + 3, 7L).toInt() + 1
        val math = Slot("1", "高等数学", setOf(wd), 480, 570)
        val other = Slot("2", "英语", setOf(wd), 600, 690)
        // 今天 7:00：就是今天 8:00 那节
        assertEquals(at(day, 480), Todos.nextClass(listOf(math, other), term, 16, "高等数学", at(day, 420), tz))
        // 今天的已经开始了：下周同一天
        assertEquals(at(day + 7, 480), Todos.nextClass(listOf(math, other), term, 16, "高等数学", at(day, 500), tz))
        assertNull(Todos.nextClass(listOf(other), term, 16, "高等数学", at(day, 420), tz))
        assertNull(Todos.nextClass(listOf(math), term, 16, "", at(day, 420), tz))
    }

    @Test fun importPromptOnlyWhenEmpty() {
        assertTrue(ImportPrompt.shouldAuto(hasCourses = false, everImported = false, shown = false))
        assertFalse(ImportPrompt.shouldAuto(hasCourses = true, everImported = false, shown = false))
        assertFalse(ImportPrompt.shouldAuto(hasCourses = false, everImported = true, shown = false))   // 导入过又清空：也不再弹
        assertFalse(ImportPrompt.shouldAuto(hasCourses = false, everImported = false, shown = true))   // 每次启动最多一次
    }

    @Test fun byDay() {
        val l = listOf(Todo("a", "a", due = at(day, 0)), Todo("b", "b", due = at(day, 23 * 60 + 59)), Todo("c", "c", due = at(day + 1, 0)), Todo("d", "d"))
        assertEquals(day, Todos.dayOf(l[0], tz)); assertEquals(day, Todos.dayOf(l[1], tz)); assertEquals(day + 1, Todos.dayOf(l[2], tz))
        assertNull(Todos.dayOf(l[3], tz))
        assertEquals(listOf("a", "b"), Todos.on(l, day, tz).map { it.id })
        assertEquals(22 * 60 + 5, Todos.minuteOf(at(day, 22 * 60 + 5), tz), )
    }

    @Test fun backupCarriesTodos() {
        val todos = listOf(Todo("a", "交作业", due = at(day, 600), course = "高等数学", created = 1))
        val j = Term.backupJson(emptyList(), listOf(480), listOf(525), 45, 20000L, 18, todos)
        val b = Term.parseBackup(j)!!
        assertEquals(todos, b.todos)
        // 旧备份（没有 todos 字段）：null，恢复时不动现有待办
        val old = Term.parseBackup(Term.backupJson(emptyList(), listOf(480), listOf(525), 45, 20000L, 18))!!
        assertNull(old.todos)
    }

    @Test fun classesOnADay() {
        val term = Term.mondayOf(day)           // 这周是第 1 周
        val wd = Math.floorMod(day + 3, 7L).toInt() + 1
        val every = Slot("1", "每周", setOf(wd), 480, 580)
        val odd = Slot("2", "单周", setOf(wd), 600, 700, weeks = setOf(1, 3))
        val even = Slot("3", "双周", setOf(wd), 800, 900, weeks = setOf(2))
        val other = Slot("4", "别的天", setOf(wd % 7 + 1), 480, 580)
        val all = listOf(even, odd, every, other)
        assertEquals(listOf("1", "2"), Term.classesOn(all, term, 16, day).map { it.id })
        assertEquals(listOf("1", "3"), Term.classesOn(all, term, 16, day + 7).map { it.id })
        assertTrue(Term.classesOn(all, term, 16, day - 7).isEmpty())      // 开学前
        assertTrue(Term.classesOn(all, term, 1, day + 7).isEmpty())       // 学期结束后
        assertEquals(listOf("1", "2", "3"), Term.classesOn(all, -1L, 16, day).map { it.id })   // 没设开学日期：每周都上
    }

    @Test fun whatsNewHasRedesign() {
        val r = Tour.WHATS_NEW.first { it.id == "redesign" }
        val txt = r.pages.joinToString { it.title + it.body }
        assertTrue(txt.contains("待办") && txt.contains("日历"))
    }

    @Test fun whatsNewNewestIsTodoScroll() {
        assertEquals("voice", Tour.WHATS_NEW.first().id)
        val vo = Tour.WHATS_NEW.first().pages.joinToString { it.title + it.body }
        assertTrue(vo.contains("麦克风") && vo.contains("按住") && vo.contains("上滑取消") && vo.contains("离线") && vo.contains("AI 设置"))
        val ac = Tour.WHATS_NEW.first { it.id == "aichats" }.pages.joinToString { it.title + it.body }
        assertTrue(ac.contains("AI 对话记录") && ac.contains("回复") && ac.contains("正中间"))
        assertTrue(Tour.WHATS_NEW.any { it.id == "ai1" })
        val pr = Tour.WHATS_NEW.first { it.id == "pinrotate" }.pages.joinToString { it.title + it.body }
        assertTrue(pr.contains("置顶") && pr.contains("顺序") && pr.contains("皮肤轮换") && pr.contains("收藏") && pr.contains("待办小部件") && pr.contains("分开设置"))
        val ts = Tour.WHATS_NEW.first { it.id == "todoscroll" }.pages.joinToString { it.title + it.body }
        assertTrue(ts.contains("待办小部件") && ts.contains("滑动") && ts.contains("4×2") && ts.contains("2×2") && ts.contains("所有"))
        val tw = Tour.WHATS_NEW.first { it.id == "todowidget" }.pages.joinToString { it.title + it.body }
        assertTrue(tw.contains("待办小部件") && tw.contains("4×2") && tw.contains("2×2") && tw.contains("透明度"))
        val sk = Tour.WHATS_NEW.first { it.id == "skins21" }.pages.joinToString { it.title + it.body }
        assertTrue(sk.contains("银杏") && sk.contains("归雁"))
        assertEquals(18, Skins.ALL.size)
        val w = Tour.WHATS_NEW.first { it.id == "widget19" }.pages.joinToString { it.title + it.body }
        assertTrue(w.contains("小部件") && w.contains("自启动") && w.contains("还剩"))
        val n = Tour.WHATS_NEW.first { it.id == "notify18" }.pages.joinToString { it.title + it.body }
        assertTrue(n.contains("待办") && n.contains("提醒") && n.contains("测试通知") && n.contains("自启动"))
        val g3 = Tour.WHATS_NEW.first { it.id == "glass3" }.pages.joinToString { it.title + it.body }
        assertTrue(g3.contains("小部件") && g3.contains("透明度") && g3.contains("100%") && g3.contains("滑块"))
        val fix = Tour.WHATS_NEW.first { it.id == "fix17" }.pages.joinToString { it.title + it.body }
        assertTrue(fix.contains("导入课表") && fix.contains("小部件") && fix.contains("闪退"))
        val ink = Tour.WHATS_NEW.first { it.id == "ink" }.pages.joinToString { it.title + it.body }
        assertTrue(ink.contains("书法") && ink.contains("待办") && ink.contains("跟随壁纸") && ink.contains("透明度"))
        val txt = Tour.WHATS_NEW.first { it.id == "glass2" }.pages.joinToString { it.title + it.body }
        assertTrue(txt.contains("液态玻璃") && txt.contains("小部件") && txt.contains("待办") && txt.contains("经典") && txt.contains("导入"))
        val g = Tour.WHATS_NEW.first { it.id == "glass" }.pages.joinToString { it.title + it.body }
        assertTrue(g.contains("液态玻璃"))
        assertEquals(Tour.WHATS_NEW.size, Tour.WHATS_NEW.map { it.id }.toSet().size)
    }
}
