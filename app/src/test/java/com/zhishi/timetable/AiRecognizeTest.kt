package com.zhishi.timetable

import org.junit.Assert.*
import org.junit.Test

/** AI 截图识别：模型回的 JSON 怎么校验、修补、变成导入预览里的课。 */
class AiRecognizeTest {
    private val st = listOf(480, 535, 600, 655, 840, 895, 960, 1015, 1140, 1195, 1250, 1305)
    private val per = st.map { it to it + 45 }

    @Test fun strictJson() {
        val r = AiRecognize.parse("""{"courses":[{"name":"高等数学","teacher":"张伟","room":"A101","weekday":1,"start_period":1,"end_period":2,"weeks":"1-16","odd_even":"all"}]}""", per, 20)!!
        assertEquals(1, r.courses.size)
        val c = r.courses[0]
        assertEquals("高等数学", c.name); assertEquals(1, c.day); assertEquals(1, c.start); assertEquals(2, c.end)
        assertEquals((1..16).toSet(), c.weeks); assertEquals("A101", c.room); assertEquals("张伟", c.teacher)
    }

    @Test fun oddEvenAndMultiWeek() {
        val r = AiRecognize.parse("""{"courses":[
            {"name":"大学物理","weekday":1,"start_period":5,"end_period":6,"weeks":"1-15","odd_even":"odd"},
            {"name":"大学英语","weekday":"周二","start_period":3,"end_period":4,"weeks":"2-16","odd_even":"even"},
            {"name":"线代","weekday":3,"start_period":1,"end_period":2,"weeks":"1-8,10-16","odd_even":"all"},
            {"name":"体育","weekday":4,"start_period":1,"end_period":2,"weeks":"","odd_even":"单"}]}""", per, 20)!!
        val m = r.courses.associateBy { it.name }
        assertEquals((1..15 step 2).toSet(), m["大学物理"]!!.weeks)
        assertEquals((2..16 step 2).toSet(), m["大学英语"]!!.weeks); assertEquals(2, m["大学英语"]!!.day)
        assertEquals((1..8).toSet() + (10..16).toSet(), m["线代"]!!.weeks)
        assertEquals((1..20 step 2).toSet(), m["体育"]!!.weeks)
    }

    @Test fun everyWeekIsEmptySet() {
        val r = AiRecognize.parse("""{"courses":[{"name":"马原","weekday":5,"start_period":3,"end_period":4,"weeks":"1-20"}]}""", per, 20)!!
        assertTrue(r.courses[0].weeks.isEmpty())
    }

    @Test fun fencedWithProseAndTrailingComma() {
        val txt = "好的，识别结果如下：\n```json\n{\"courses\":[{\"name\":\"程序设计\",\"weekday\":4,\"start_period\":7,\"end_period\":8,},]}\n```\n希望有帮助"
        val r = AiRecognize.parse(txt, per)!!
        assertEquals(listOf("程序设计"), r.courses.map { it.name })
    }

    @Test fun arrayRootAndAliases() {
        val r = AiRecognize.parse("""[{"course":"化学","day":"Wed","periods":"3-4","location":"C1"}]""", per)!!
        val c = r.courses.single()
        assertEquals(3, c.day); assertEquals(3, c.start); assertEquals(4, c.end); assertEquals("C1", c.room)
    }

    @Test fun clockTimesMapToPeriods() {
        val r = AiRecognize.parse("""{"courses":[{"name":"Physics","weekday":2,"start_period":0,"end_period":0,"start_time":"10:00","end_time":"11:40"}]}""", per)!!
        assertEquals(3, r.courses[0].start); assertEquals(4, r.courses[0].end)
    }

    @Test fun badEntriesDroppedAndDuplicatesMerged() {
        val r = AiRecognize.parse("""{"courses":[
            {"name":"","weekday":1,"start_period":1,"end_period":2},
            {"name":"A","weekday":9,"start_period":1,"end_period":2},
            {"name":"B","weekday":1,"start_period":0,"end_period":0},
            {"name":"C","weekday":1,"start_period":4,"end_period":3},
            {"name":"C","weekday":1,"start_period":3,"end_period":4},
            "junk"]}""", per)!!
        assertEquals(listOf("C"), r.courses.map { it.name })
        assertEquals(3 to 4, r.courses[0].start to r.courses[0].end)
        assertEquals(4, r.dropped)
    }

    @Test fun notJson() {
        assertNull(AiRecognize.parse("抱歉，我看不清这张图片。", per))
        assertTrue(AiRecognize.parse("""{"courses":[]}""", per)!!.courses.isEmpty())
    }

    @Test fun promptListsPeriods() {
        val p = AiRecognize.prompt(per.take(2))
        assertTrue(p.contains("1=8:00-8:45")); assertTrue(p.contains("odd_even"))
    }
}
