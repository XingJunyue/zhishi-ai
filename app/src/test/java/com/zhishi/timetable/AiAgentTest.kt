package com.zhishi.timetable

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** AI 助手的对话流程（模型换成假的）：提议 → 确认 / 取消、打开页面、参数错了重来、嘴上说改了却没调工具、出错。 */
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

    @Test fun proposalThenConfirm() {
        val m = Scripted(AiReply("我准备好了", listOf(AiToolCall("a", AiTools.PIN_TODO, """{"id":"t1"}"""))))
        val a = AiAgent(m)
        val steps = a.send("把背单词置顶", st, env, "zh")
        val p = steps.filterIsInstance<AiAgent.Step.Pending>().single()
        assertTrue(p.plan.after.todos.single().pin); assertEquals("我准备好了", p.text)
        assertTrue(a.hasPending)
        a.resolve(true, null)
        assertFalse(a.hasPending)
        val tool = a.history.last()
        assertEquals(AiRole.TOOL, tool.role); assertEquals("a", tool.toolCallId); assertTrue(tool.text!!.startsWith("applied"))
        // 系统提示里带着当前数据和规则
        val sys = m.seen.single().first()
        assertEquals(AiRole.SYSTEM, sys.role)
        assertTrue(sys.text!!.contains("\"id\":\"c1\"")); assertTrue(sys.text!!.contains("背单词")); assertTrue(sys.text!!.contains("简体中文"))
    }

    @Test fun cancelAndNewMessageWhilePending() {
        val a = AiAgent(Scripted(tc(AiTools.DELETE_TODO, """{"id":"t1"}"""), "好的"))
        a.send("删掉背单词", st, env, "zh")
        assertTrue(a.hasPending)
        val steps = a.send("算了，周一有什么课", st, env, "zh")
        assertFalse(a.hasPending)
        assertTrue(a.history.any { it.role == AiRole.TOOL && it.text!!.startsWith("cancelled") })
        assertEquals("好的", (steps.single() as AiAgent.Step.Text).text)
        // 每个 tool_call 后面都跟着它的结果（OpenAI 格式要求）
        val i = a.history.indexOfFirst { it.toolCalls.isNotEmpty() }
        assertEquals(AiRole.TOOL, a.history[i + 1].role)
    }

    @Test fun badArgumentsGoBackToModel() {
        val m = Scripted(tc(AiTools.DELETE_COURSE, """{"id":"zz"}"""), tc(AiTools.DELETE_COURSE, """{"id":"c1"}"""))
        val a = AiAgent(m)
        val steps = a.send("删掉高数", st, env, "zh")
        assertTrue(steps.single() is AiAgent.Step.Pending)
        assertEquals(2, m.seen.size)
        assertTrue(m.seen[1].any { it.role == AiRole.TOOL && it.text!!.contains("no course") })
    }

    @Test fun navigationIsImmediate() {
        val a = AiAgent(Scripted(AiReply("这就打开", listOf(AiToolCall("n", AiTools.OPEN_PAGE, """{"page":"widgets"}""")))))
        val steps = a.send("打开小部件", st, env, "zh")
        assertEquals("widget", (steps[0] as AiAgent.Step.Nav).dest)
        assertEquals("这就打开", (steps[1] as AiAgent.Step.Text).text)
        assertFalse(a.hasPending)
        assertEquals(AiRole.TOOL, a.history.last().role)
    }

    @Test fun claimWithoutToolIsNudgedOnce() {
        val m = Scripted("已经为你准备好设置调整，请点击确认卡片上的确认按钮。", tc(AiTools.SET_APP, """{"show_weekend":false}"""))
        val steps = AiAgent(m).send("隐藏周末", st, env, "zh")
        assertTrue(steps.single() is AiAgent.Step.Pending)
        assertTrue(m.seen[1].last().text!!.contains("did not call any tool"))
        // 第二次还是光说不做：照原样显示，不再追问
        val m2 = Scripted("请确认", "请确认")
        assertTrue(AiAgent(m2).send("隐藏周末", st, env, "zh").single() is AiAgent.Step.Text)
        assertEquals(2, m2.seen.size)
        assertTrue(AiAgent.claimsChange("已为你准备好，确认后生效")); assertFalse(AiAgent.claimsChange("周一有高等数学"))
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
        a.noteUndo()
        a.send("几节？", st, env, "en")
        assertTrue(m.seen.single().any { it.text?.contains("Recognised 6") == true })
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
