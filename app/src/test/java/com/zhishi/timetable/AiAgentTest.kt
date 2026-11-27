package com.zhishi.timetable

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** AI 助手的对话流程（模型换成假的）：改动马上生效、一轮合成一批、打开页面、参数错了重来、嘴上说改了却没调工具、出错。 */
class AiAgentTest {
    class Scripted(vararg replies: Any) : AiClient {
        val queue = ArrayDeque(replies.toList()); val seen = ArrayList<List<AiMessage>>()
        override fun chat(messages: List<AiMessage>, tools: List<AiTool>): AiReply {
            seen += messages
            return when (val r = queue.removeFirst()) { is AiReply -> r; is Throwable -> throw r; else -> AiReply(r.toString(), emptyList()) }
        }
        override fun vision(system: String, prompt: String, images: List<AiImage>) = ""
        override fun test() = "OK"
        override fun models() = emptyList<String>()
    }
    private val tz = TimeZone.getTimeZone("Asia/Shanghai")
    private val env = AiEnv(1_790_000_000_000L, tz, listOf("chunyu" to "春雨", "ying" to "樱"), { "n_$it" })
    private val st = AppState(courses = listOf(Slot("c1", "高等数学", setOf(1), 480, 580)), todos = listOf(Todo("t1", "背单词")), secStarts = listOf(480, 535), secEnds = listOf(525, 580))
    private fun tc(name: String, args: String, id: String = "call_$name") = AiReply(null, listOf(AiToolCall(id, name, args)))

    @Test fun changesApplyImmediately() {
        val applied = ArrayList<AiPlan>()
        val m = Scripted(AiReply(null, listOf(AiToolCall("a", AiTools.PIN_TODO, """{"id":"t1"}"""))), "置顶好了")
        val a = AiAgent(m)
        val steps = a.send("把背单词置顶", st, env, "zh") { p -> applied += p; p.after }
        assertEquals(1, applied.size)
        assertTrue(applied[0].after.todos.single().pin)
        assertEquals("置顶好了", (steps[0] as AiAgent.Step.Text).text)
        val batch = steps[1] as AiAgent.Step.Applied
        assertEquals(1, batch.edits.size); assertEquals(setOf("todo:t1"), batch.edits[0].keys)
        // 工具结果「已应用」回给了模型，模型再说一句
        val tool = a.history.first { it.role == AiRole.TOOL }
        assertEquals("a", tool.toolCallId); assertTrue(tool.text!!.startsWith("applied"))
        assertTrue(m.seen[1].any { it.role == AiRole.TOOL && it.text!!.startsWith("applied") })
        // 系统提示里带着当前数据和规则（不再要求用户确认）
        val sys = m.seen.first().first()
        assertEquals(AiRole.SYSTEM, sys.role)
        assertTrue(sys.text!!.contains("\"id\":\"c1\"")); assertTrue(sys.text!!.contains("背单词")); assertTrue(sys.text!!.contains("简体中文"))
        assertTrue(sys.text!!.contains("take effect immediately")); assertFalse(sys.text!!.contains("Confirm"))
        // 第二轮的系统提示里已经是改过的数据
        assertTrue(m.seen[1].first().text!!.contains("\"pinned\":true"))
    }

    @Test fun severalRoundsBecomeOneBatch() {
        val m = Scripted(tc(AiTools.ADD_COURSE, """{"name":"机器学习","weekdays":[3],"start_period":1,"end_period":2}"""),
            AiReply(null, listOf(AiToolCall("u", AiTools.UPDATE_COURSE, """{"id":"n_c","room":"B302"}"""), AiToolCall("t", AiTools.ADD_TODO, """{"title":"预习"}"""))),
            "加好了，教室是 B302，还记了个待办")
        var n = 0
        val e = AiEnv(env.now, tz, env.skins, { p -> if (p == "c") "n_c" else p + (++n) })
        val steps = AiAgent(m).send("加机器学习，教室 B302，再提醒我预习", st, e, "zh")
        val b = steps.filterIsInstance<AiAgent.Step.Applied>().single()
        assertEquals(3, b.edits.size)
        assertEquals(listOf("course:n_c"), b.edits.take(2).flatMap { it.keys }.distinct())
        assertEquals(1, steps.filterIsInstance<AiAgent.Step.Text>().size)
    }

    @Test fun saveFailureIsReportedToTheModel() {
        val m = Scripted(tc(AiTools.DELETE_TODO, """{"id":"t1"}"""), "没删成")
        val steps = AiAgent(m).send("删掉背单词", st, env, "zh") { null }
        assertTrue(steps.none { it is AiAgent.Step.Applied })
        assertTrue(m.seen[1].any { it.role == AiRole.TOOL && it.text!!.startsWith("error: saving failed") })
    }

    @Test fun badArgumentsGoBackToModel() {
        val m = Scripted(tc(AiTools.DELETE_COURSE, """{"id":"zz"}"""), tc(AiTools.DELETE_COURSE, """{"id":"c1"}"""), "删了")
        val a = AiAgent(m)
        val steps = a.send("删掉高数", st, env, "zh")
        assertEquals(1, steps.filterIsInstance<AiAgent.Step.Applied>().single().edits.size)
        assertEquals(1, steps.filterIsInstance<AiAgent.Step.Applied>().single().failed)
        assertEquals(3, m.seen.size)
        assertTrue(m.seen[1].any { it.role == AiRole.TOOL && it.text!!.contains("no course") })
        // 每个 tool_call 后面都跟着它的结果（OpenAI 格式要求）
        val i = a.history.indexOfFirst { it.toolCalls.isNotEmpty() }
        assertEquals(AiRole.TOOL, a.history[i + 1].role)
    }

    @Test fun navigationIsImmediate() {
        val a = AiAgent(Scripted(AiReply("这就打开", listOf(AiToolCall("n", AiTools.OPEN_PAGE, """{"page":"widgets"}""")))))
        val steps = a.send("打开小部件", st, env, "zh")
        assertEquals("widget", (steps[0] as AiAgent.Step.Nav).dest)
        assertEquals("这就打开", (steps[1] as AiAgent.Step.Text).text)
        assertEquals(AiRole.TOOL, a.history.last().role)
    }

    @Test fun claimWithoutToolIsNudgedOnce() {
        val m = Scripted("已经帮你把周末隐藏了。", tc(AiTools.SET_APP, """{"show_weekend":false}"""), "好了")
        val steps = AiAgent(m).send("隐藏周末", st, env, "zh")
        assertNotNull(steps.filterIsInstance<AiAgent.Step.Applied>().singleOrNull())
        assertTrue(m.seen[1].last().text!!.contains("did not call any tool"))
        // 第二次还是光说不做：照原样显示，不再追问
        val m2 = Scripted("请确认", "请确认")
        assertTrue(AiAgent(m2).send("隐藏周末", st, env, "zh").single() is AiAgent.Step.Text)
        assertEquals(2, m2.seen.size)
        assertTrue(AiAgent.claimsChange("已为你准备好，确认后生效")); assertTrue(AiAgent.claimsChange("已经帮你添加好了"))
        assertTrue(AiAgent.claimsChange("I've added it to Wednesday."))
        assertFalse(AiAgent.claimsChange("周一有高等数学"))
        assertFalse(AiAgent.claimsChange("有两门叫高数的课，请确认是哪一门？"))
        // 真改过之后的总结（「已经加好了」）不追问
        val m3 = Scripted(tc(AiTools.PIN_TODO, """{"id":"t1"}"""), "已经帮你置顶了")
        assertEquals("已经帮你置顶了", (AiAgent(m3).send("置顶", st, env, "zh")[0] as AiAgent.Step.Text).text)
        assertEquals(2, m3.seen.size)
    }

    @Test fun errorsAndRetry() {
        val m = Scripted(AiError.Network("down"), "周一有高等数学")
        val a = AiAgent(m)
        val s = a.send("周一有什么课", st, env, "zh")
        assertTrue((s.single() as AiAgent.Step.Failed).error is AiError.Network)
        assertEquals(AiRole.USER, a.history.last().role)
        assertEquals("周一有高等数学", (a.retry(st, env, "zh").single() as AiAgent.Step.Text).text)
        assertTrue(a.retry(st, env, "zh").isEmpty())
    }

    @Test fun networkErrorAfterAChangeStillReportsTheBatch() {
        val m = Scripted(tc(AiTools.PIN_TODO, """{"id":"t1"}"""), AiError.Network("down"))
        val s = AiAgent(m).send("置顶", st, env, "zh")
        assertTrue(s[0] is AiAgent.Step.Failed)
        assertEquals(1, (s[1] as AiAgent.Step.Applied).edits.size)
    }

    @Test fun givesUpAfterMaxRounds() {
        val m = Scripted(*Array(4) { tc(AiTools.DELETE_COURSE, """{"id":"zz"}""") })
        val s = AiAgent(m).send("删", st, env, "zh")
        assertEquals(4, m.seen.size)
        assertTrue(s.single() is AiAgent.Step.Text)
    }

    @Test fun recognitionAndUndoNotes() {
        val m = Scripted("6 节")
        val a = AiAgent(m)
        a.noteRecognition(6, listOf("高等数学"))
        a.noteUndo(listOf("新增课程：机器学习"))
        a.send("几节？", st, env, "en")
        assertTrue(m.seen.single().any { it.text?.contains("Recognised 6") == true })
        assertTrue(m.seen.single().any { it.text?.contains("I undid these changes: 新增课程：机器学习") == true })
        assertTrue(m.seen.single().first().text!!.contains("English"))
    }

    @Test fun historyTrimStartsAtUserMessage() {
        val replies = (1..30).map { "ok$it" }.toTypedArray()
        val m = Scripted(*replies)
        val a = AiAgent(m)
        repeat(30) { a.send("q$it", st, env, "zh") }
        val sent = m.seen.last()
        assertTrue(sent.size <= 41)
        assertEquals(AiRole.USER, sent[1].role)
    }
}
