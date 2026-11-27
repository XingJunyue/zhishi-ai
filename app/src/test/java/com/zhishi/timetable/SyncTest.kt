package com.zhishi.timetable

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 云同步的合并 / outbox / 墓碑（服务器换成照 API.md 写的假服务器：updated_at 后写的赢，平局留服务器那份）。 */
class SyncTest {
    /** 假服务器：一个用户的 items + 版本号。 */
    class FakeServer(val pageLimit: Int = 500) : SyncNet {
        class Row(var data: JSONObject, var u: Long, var deleted: Boolean, var version: Long, var source: String)
        val rows = LinkedHashMap<String, Row>()
        var version = 0L
        var pushes = 0
        fun item(k: String, r: Row) = SyncItem(SyncData.kindOf(k), SyncData.idOf(k), r.u, r.deleted, JSONObject(r.data.toString()), r.version, r.source)
        fun write(c: SyncItem, source: String): Boolean {
            val r = rows[c.key]
            if (r != null && c.updatedAt <= r.u) return r.u == c.updatedAt && r.deleted == c.deleted && r.data.toString() == c.data.toString()
            version++
            rows[c.key] = Row(JSONObject(c.data.toString()), c.updatedAt, c.deleted, version, source)
            return true
        }
        /** 智能体通过 MCP 改的（服务器时间）。 */
        fun agent(kind: String, id: String, data: JSONObject, at: Long, deleted: Boolean = false) =
            write(SyncItem(kind, id, at, deleted, data), "agent:Grok")
        override fun push(changes: List<SyncItem>, since: Long?): SyncPush {
            pushes++
            val rejected = changes.filter { !write(it, "app") }.map { item(it.key, rows[it.key]!!) }
            return SyncPush(rejected, since?.let { pull(it) })
        }
        override fun pull(since: Long): SyncPull {
            val all = rows.entries.filter { it.value.version > since && (since > 0 || !it.value.deleted) }.sortedBy { it.value.version }
            val page = all.take(pageLimit)
            val more = all.size > pageLimit
            return SyncPull(page.map { item(it.key, it.value) }, if (more) page.last().value.version else version, more)
        }
        fun todo(id: String) = rows[SyncData.key(SyncData.TODO, id)]
        fun course(id: String) = rows[SyncData.key(SyncData.COURSE, id)]
    }

    class Store(var local: SyncLocal) : SyncStore {
        var writes = 0
        override fun read() = local
        override fun write(old: SyncLocal, new: SyncLocal, taken: List<SyncItem>) { writes++; local = new }
    }

    private fun base() = SyncLocal(
        courses = listOf(Slot("c1", "高等数学", setOf(1), 480, 580, room = "A101"), Slot("c2", "大学英语", setOf(2), 600, 700)),
        todos = listOf(Todo("t1", "交作业", due = 1_800_000_000_000L, created = 1)),
        termStart = 20_400L, totalWeeks = 18, secStarts = listOf(480, 535), secEnds = listOf(525, 580), secLen = 45, tz = "Asia/Shanghai")

    private var clock = 1_000_000L
    private fun now(): Long = clock

    private fun enable(srv: FakeServer, st: Store, led: SyncLedger) {
        led.snapshotAll(st.read().snapshot(), now())
        SyncEngine.run(led, st, srv, ::now)
    }

    @Test fun firstEnablePushesFullSnapshot() {
        val srv = FakeServer(); val st = Store(base()); val led = SyncLedger()
        enable(srv, st, led)
        assertEquals(6, srv.rows.size)   // 2 课 + 1 待办 + term / periods / profile
        assertEquals("高等数学", srv.course("c1")!!.data.getString("name"))
        assertEquals(1, srv.course("c1")!!.data.getJSONArray("days").getInt(0))
        assertEquals(18, srv.rows["settings:term"]!!.data.getInt("totalWeeks"))
        assertEquals("Asia/Shanghai", srv.rows["settings:profile"]!!.data.getString("tz"))
        assertTrue(led.outbox.isEmpty())
        assertEquals(srv.version, led.cursor)
        // 自己推上去的拉回来不算改动，本机也不重写
        assertEquals(0, st.writes)
        // 再同步一次：什么都不推
        val o = SyncEngine.run(led, st, srv, ::now)
        assertEquals(0, o.pushed); assertTrue(o.taken.isEmpty())
    }

    @Test fun localEditsAndDeletesBecomeOutboxAndTombstones() {
        val srv = FakeServer(); val st = Store(base()); val led = SyncLedger()
        enable(srv, st, led)
        clock += 5000
        st.local = st.local.copy(todos = st.local.todos.map { it.copy(title = "交作业（改）") } + Todo("t2", "背单词"), courses = st.local.courses.filter { it.id != "c2" })
        val changed = led.scan(st.local.snapshot(), now())
        assertEquals(setOf("todo:t1", "todo:t2", "course:c2"), changed)
        assertTrue(led.entries["course:c2"]!!.d)
        val pend = led.pending(st.local.snapshot())
        assertTrue(pend.single { it.key == "course:c2" }.deleted)
        val o = SyncEngine.run(led, st, srv, ::now)
        assertEquals(3, o.pushed)
        assertEquals("交作业（改）", srv.todo("t1")!!.data.getString("title"))
        assertNotNull(srv.todo("t2"))
        assertTrue(srv.course("c2")!!.deleted)
        assertTrue(led.outbox.isEmpty())
        // 墓碑留在侧表里：不会再推一遍
        assertEquals(0, SyncEngine.run(led, st, srv, ::now).pushed)
    }

    @Test fun agentChangesArePulledAppliedAndNotEchoed() {
        val srv = FakeServer(); val st = Store(base()); val led = SyncLedger()
        enable(srv, st, led)
        val t = SyncData.todo(Todo("ta", "智能体加的", due = 1_800_000_000_000L, remind = true, before = 30, created = 5))
        srv.agent("todo", "ta", t, 2_000_000L)
        srv.agent("course", "c1", SyncData.course(st.local.courses[0].copy(room = "B202")), 2_000_001L)
        srv.agent("course", "c2", JSONObject(), 2_000_002L, deleted = true)
        val o = SyncEngine.run(led, st, srv, ::now)
        assertEquals(3, o.byAgent.size)
        val l = st.local
        assertEquals(listOf("c1"), l.courses.map { it.id })
        assertEquals("B202", l.courses[0].room)
        val ta = l.todos.single { it.id == "ta" }
        assertTrue(ta.remind); assertEquals(30, ta.before)
        // 收下的写回本机后不会当成本机改动再推回去
        assertTrue(led.scan(st.local.snapshot(), now()).isEmpty())
        assertEquals(0, SyncEngine.run(led, st, srv, ::now).pushed)
    }

    @Test fun newerLocalEditWinsOverOlderAgentEdit() {
        val srv = FakeServer(); val st = Store(base()); val led = SyncLedger()
        enable(srv, st, led)
        // 本机 t=3_000_000 改了，还没推；服务器上智能体在 t=2_500_000 改了同一条
        clock = 3_000_000L
        st.local = st.local.copy(todos = st.local.todos.map { it.copy(title = "本机改的") })
        led.scan(st.local.snapshot(), now())
        srv.agent("todo", "t1", SyncData.todo(base().todos[0].copy(title = "智能体改的")), 2_500_000L)
        SyncEngine.run(led, st, srv, ::now)
        assertEquals("本机改的", st.local.todos[0].title)
        assertEquals("本机改的", srv.todo("t1")!!.data.getString("title"))
    }

    @Test fun rejectedPushTakesServerCopy() {
        val srv = FakeServer(); val st = Store(base()); val led = SyncLedger()
        enable(srv, st, led)
        // 智能体后改（服务器时间更新）；本机在那之前改的推上去会被拒，本机换成服务器那份
        clock = 2_000_000L
        st.local = st.local.copy(todos = st.local.todos.map { it.copy(title = "本机旧的") })
        led.scan(st.local.snapshot(), now())
        srv.agent("todo", "t1", SyncData.todo(base().todos[0].copy(title = "智能体新的")), 2_500_000L)
        val o = SyncEngine.run(led, st, srv, ::now)
        assertEquals(1, o.rejected)
        assertEquals("智能体新的", st.local.todos[0].title)
        assertTrue(led.outbox.isEmpty())
        assertEquals(2_500_000L, led.entries["todo:t1"]!!.u)
    }

    @Test fun phoneClockBehindServerStillWinsNextEdit() {
        val srv = FakeServer(); val st = Store(base()); val led = SyncLedger()
        enable(srv, st, led)
        srv.agent("todo", "t1", SyncData.todo(base().todos[0].copy(title = "智能体")), 9_000_000L)
        SyncEngine.run(led, st, srv, ::now)
        // 手机时钟（1_000_000）比服务器慢很多：下一次本机改动的时间至少比服务器那份大 1
        st.local = st.local.copy(todos = st.local.todos.map { it.copy(title = "手机后改的") })
        SyncEngine.run(led, st, srv, ::now)
        assertEquals(9_000_001L, led.entries["todo:t1"]!!.u)
        assertEquals("手机后改的", srv.todo("t1")!!.data.getString("title"))
    }

    @Test fun pullPagesUntilDone() {
        val srv = FakeServer(pageLimit = 2); val st = Store(base()); val led = SyncLedger()
        enable(srv, st, led)
        for (i in 1..5) srv.agent("todo", "a$i", SyncData.todo(Todo("a$i", "智能体 $i", created = i.toLong())), 2_000_000L + i)
        val o = SyncEngine.run(led, st, srv, ::now)
        assertEquals(5, o.taken.size)
        assertEquals(srv.version, led.cursor)
        assertEquals(6, st.local.todos.size)
    }

    @Test fun settingsSplitAndProfileFollowsPhone() {
        val srv = FakeServer(); val st = Store(base()); val led = SyncLedger()
        enable(srv, st, led)
        srv.agent("settings", "term", SyncData.term(20_500L, 16), 2_000_000L)
        srv.agent("settings", "profile", SyncData.profile("Europe/Berlin"), 2_000_001L)
        SyncEngine.run(led, st, srv, ::now)
        SyncEngine.run(led, st, srv, ::now)
        assertEquals(20_500L, st.local.termStart); assertEquals(16, st.local.totalWeeks)
        assertEquals(listOf(480, 535), st.local.secStarts)          // 节次时间没被动
        assertEquals("Asia/Shanghai", st.local.tz)                  // 手机的时区为准……
        assertEquals("Asia/Shanghai", srv.rows["settings:profile"]!!.data.getString("tz"))  // ……并且推回去
    }

    @Test fun ledgerJsonRoundTrip() {
        val led = SyncLedger(); led.snapshotAll(base().snapshot(), 42L); led.cursor = 7
        led.entries["todo:x"] = SyncLedger.Entry(9, 0, true)
        val back = SyncLedger.of(led.toJson().toString())
        assertEquals(led.entries, back.entries); assertEquals(led.outbox, back.outbox); assertEquals(7L, back.cursor)
        assertTrue(SyncLedger.of("{broken").entries.isEmpty())
    }

    @Test fun dataMappingUsesKotlinNamesAndRoundTrips() {
        val s = Slot("c9", "线代", setOf(3, 1), 480, 580, 2, room = "教1", teacher = "王", weeks = setOf(1, 3), color = 4, note = "带书", credit = "3")
        val d = SyncData.course(s)
        assertEquals(listOf(1, 3), (0 until d.getJSONArray("days").length()).map { d.getJSONArray("days").getInt(it) })
        assertEquals(s, SyncData.slot("c9", JSONObject(d.toString())))
        val t = Todo("t9", "报告", "n", 123L, true, "线代", false, 0L, 5L, true, 60, true, 2)
        val td = SyncData.todo(t)
        assertTrue(td.getBoolean("allDay")); assertEquals(60, td.getInt("before")); assertEquals(2, td.getInt("order"))
        assertEquals(t, SyncData.todoOf("t9", JSONObject(td.toString())))
        // 智能体给的数字可能是 3.0 / "3"
        assertEquals(setOf(3), SyncData.slot("x", JSONObject("""{"name":"a","days":[3.0,"9"],"from":1,"to":2}""")).days)
    }
}
