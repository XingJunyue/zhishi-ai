package com.zhishi.timetable

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** 自定义撤回：依赖（先加课再改它）、只撤销所选、之后又被改过的不动、删掉的按原位置放回。 */
class UndoTest {
    private val tz = TimeZone.getTimeZone("Asia/Shanghai")
    private var n = 0
    private val env = AiEnv(1_790_000_000_000L, tz, listOf("chunyu" to "春雨"), { p -> p + "new" + (++n) })
    private val st0 = AppState(courses = listOf(Slot("c1", "高等数学", setOf(1), 480, 580), Slot("c2", "大学英语", setOf(2), 600, 700)),
        todos = listOf(Todo("t1", "背单词")), secStarts = listOf(480, 535, 600, 655), secEnds = listOf(525, 580, 645, 700))
    private fun call(name: String, args: String, id: String = name) = AiToolCall(id, name, args)

    /** 一轮：加课 → 改它的教室（第二轮，用返回的 id）→ 加待办。 */
    private fun batch(): Pair<AppState, List<AiEdit>> {
        val p1 = AiTools.plan(st0, listOf(call(AiTools.ADD_COURSE, """{"name":"机器学习","weekdays":[3],"start_period":1,"end_period":2}""")), env)
        val id = p1.after.courses.last().id
        val p2 = AiTools.plan(p1.after, listOf(call(AiTools.UPDATE_COURSE, """{"id":"$id","room":"B302"}"""), call(AiTools.ADD_TODO, """{"title":"预习机器学习"}""")), env)
        return p2.after to (p1.edits + p2.edits)
    }

    @Test fun editsRecordWhatEachChangeTouched() {
        val (after, edits) = batch()
        assertEquals(3, edits.size)
        val cid = after.courses.last().id
        assertEquals(setOf("course:$cid"), edits[0].keys); assertNull(edits[0].before["course:$cid"])
        assertEquals("B302", (edits[1].after["course:$cid"] as Slot).room)
        assertTrue(edits[2].keys.single().startsWith("todo:"))
        assertTrue(edits[0].change.title.contains("机器学习"))
    }

    @Test fun dependenciesFollowTheSameThing() {
        val (_, edits) = batch()
        val e = edits.map { UndoEntry(it.change, it.keys) }
        // 勾「加课」→ 后面改这门课的那项一起勾上；待办不受影响
        assertEquals(setOf(0, 1), UndoDeps.toggle(e, emptySet(), 0, true))
        // 全选后取消「改教室」→ 前面的「加课」也得取消（不能只撤加课留着改）
        assertEquals(setOf(2), UndoDeps.toggle(e, setOf(0, 1, 2), 1, false))
        assertEquals(setOf(1), UndoDeps.toggle(e, emptySet(), 1, true))
        // 已经撤销过的不能再勾
        val e2 = e.mapIndexed { i, x -> if (i == 2) x.copy(undone = true) else x }
        assertEquals(setOf(0, 1), UndoDeps.all(e2))
        assertEquals(emptySet<Int>(), UndoDeps.toggle(e2, emptySet(), 2, true))
    }

    @Test fun undoOnlySelected() {
        val (after, edits) = batch()
        val exp = Entities.finalValues(edits)
        // 只撤销「加待办」
        val r = Entities.undo(edits, emptySet(), setOf(2), after, st0, exp)
        assertEquals(setOf(2), r.done)
        assertEquals(listOf("背单词"), r.state.todos.map { it.title })
        assertTrue(r.state.courses.any { it.name == "机器学习" && it.room == "B302" })
        // 再撤销「加课」：自动连同「改教室」，课没了
        val r2 = Entities.undo(edits, r.done, setOf(0), r.state, st0, exp)
        assertEquals(setOf(0, 1), r2.done)
        assertEquals(st0.courses, r2.state.courses)
        assertEquals(st0, r2.state)
    }

    @Test fun undoOnlyTheRoomChangeKeepsTheCourse() {
        val (after, edits) = batch()
        val r = Entities.undo(edits, emptySet(), setOf(1), after, st0, Entities.finalValues(edits))
        val c = r.state.courses.single { it.name == "机器学习" }
        assertEquals("", c.room)
        assertEquals(2, r.state.todos.size)
    }

    @Test fun laterManualEditIsAConflictAndNotOverwritten() {
        val (after, edits) = batch()
        val cid = after.courses.last().id
        // 用户之后又手动把这门课挪到周五
        val cur = after.copy(courses = after.courses.map { if (it.id == cid) it.copy(days = setOf(5)) else it })
        val r = Entities.undo(edits, emptySet(), setOf(0, 1, 2), cur, st0, Entities.finalValues(edits))
        assertEquals(setOf("course:$cid"), r.conflicts)
        assertEquals(setOf(2), r.done)
        assertEquals(setOf(5), r.state.courses.single { it.id == cid }.days)
        assertEquals(1, r.state.todos.size)
    }

    @Test fun deletedCourseGoesBackToItsPlaceAndSettingsParts() {
        val p = AiTools.plan(st0, listOf(call(AiTools.DELETE_COURSE, """{"id":"c1"}"""), call(AiTools.SET_TERM, """{"total_weeks":16}"""), call(AiTools.SET_SKIN, """{"skin":"chunyu","mode":"dark"}""")), env)
        assertEquals(3, p.edits.size)
        assertEquals(setOf("part:TERM"), p.edits[1].keys)
        val r = Entities.undo(p.edits, emptySet(), setOf(0, 1, 2), p.after, st0, Entities.finalValues(p.edits))
        assertEquals(listOf("c1", "c2"), r.state.courses.map { it.id })
        assertEquals(st0, r.state)
    }

    @Test fun applyOnOnlyTouchesTheBatch() {
        val (_, edits) = batch()
        // 模型想的时候，别处（比如智能体同步）加了一条待办：写入时不能把它冲掉
        val cur = st0.copy(todos = st0.todos + Todo("tx", "同步下来的"))
        val s = Entities.applyOn(cur, edits, st0)
        assertTrue(s.todos.any { it.id == "tx" })
        assertEquals(3, s.todos.size)
        assertTrue(s.courses.any { it.name == "机器学习" && it.room == "B302" })
    }

    @Test fun describeAgentChanges() {
        val a = Todo("t1", "背单词"); val b = a.copy(done = true)
        assertTrue(AiTools.describe(st0, a, b, tz).title.startsWith("完成待办"))
        assertTrue(AiTools.describe(st0, null, a, tz).title.startsWith("新增待办"))
        assertTrue(AiTools.describe(st0, st0.courses[0], null, tz).title.startsWith("删除课程"))
        assertEquals("B1", AiTools.describe(st0, st0.courses[0], st0.courses[0].copy(room = "B1"), tz).lines.single().after)
    }
}
