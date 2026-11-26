package com.zhishi.timetable

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** AI 对话记录：存 / 读、标题、卡片读回来不会再应用、工具调用补结果、引用怎么拼进发给模型的话。 */
class AiHistoryTest {
    private val card = SavedCard(listOf(AiChange("新增课程：机器学习", listOf(DiffLine("时间", null, "周三 第5-6节"), DiffLine("教室", "B301", "B302")))), 1, AiConvos.APPLIED)
    private val items = listOf(
        SavedItem(AiConvos.USER, "周三第5-6节\n加一门机器学习"),
        SavedItem(AiConvos.BOT, "我准备好了，请确认。"),
        SavedItem(AiConvos.CARD, card = card),
        SavedItem(AiConvos.USER, "教室改成 B303", quote = AiQuote(false, "我准备好了，请确认。")),
        SavedItem(AiConvos.ERROR, "连不上 AI 接口", retry = true),
        SavedItem(AiConvos.INFO, "认出 4 节课", images = 0),
    )
    private val history = listOf(
        AiMessage(AiRole.USER, "周三第5-6节加一门机器学习"),
        AiMessage(AiRole.ASSISTANT, "我准备好了", toolCalls = listOf(AiToolCall("a", AiTools.ADD_COURSE, """{"name":"机器学习"}"""))),
        AiMessage(AiRole.TOOL, "applied: the user confirmed.", toolCallId = "a"),
        AiMessage(AiRole.USER, "[用户在回复这条消息（你之前的回复）：「我准备好了」]\n教室改成 B303"),
    )
    private fun convo(id: String = "c1", updated: Long = 2000L) = AiConvo(id, 1000L, updated, AiConvos.title(items), items, history)

    @Test fun roundTripKeepsEverything() {
        val back = AiConvos.parseList(AiConvos.listJson(listOf(convo()))).single()
        assertEquals("c1", back.id); assertEquals(1000L, back.created); assertEquals(2000L, back.updated)
        assertEquals(items, back.items)
        assertEquals(history, back.history)
        assertEquals("周三第5-6节 加一门机器学习", back.title)
        // 条数：用户 2 + AI 1 + 卡片 1（出错、提示不算）
        assertEquals(4, back.count)
        assertEquals(AiQuote(false, "我准备好了，请确认。"), back.items[3].quote)
        assertEquals(card, back.items[2].card)
    }

    @Test fun titleIsFirstUserLineTrimmed() {
        assertEquals("", AiConvos.title(listOf(SavedItem(AiConvos.BOT, "hi"))))
        val long = "一".repeat(60)
        assertEquals("一".repeat(40) + "…", AiConvos.title(listOf(SavedItem(AiConvos.USER, long))))
    }

    @Test fun pendingCardsComeBackCancelledAndToolCallsGetResults() {
        val pending = items.take(2) + SavedItem(AiConvos.CARD, card = card.copy(state = AiConvos.PENDING))
        // 存的时候卡片还没确认：模型历史里工具调用后面没有结果
        val h = history.take(2)
        val back = AiConvos.parseList(AiConvos.listJson(listOf(AiConvo("p", 1, 2, "", pending, h)))).single()
        assertEquals(AiConvos.PENDING, back.items[2].card!!.state)
        assertEquals(AiConvos.CANCELLED, AiConvos.restoredState(back.items[2].card!!.state))
        assertEquals(AiConvos.APPLIED, AiConvos.restoredState(AiConvos.APPLIED))
        assertEquals(AiConvos.UNDONE, AiConvos.restoredState(AiConvos.UNDONE))
        // 每个 tool_call 后面都跟着一条结果（补的是「已取消」）
        assertEquals(3, back.history.size)
        assertEquals(AiRole.TOOL, back.history[2].role); assertEquals("a", back.history[2].toolCallId)
        assertTrue(back.history[2].text!!.startsWith("cancelled"))
    }

    @Test fun repairDropsOrphansSystemAndImages() {
        val img = AiImage("image/jpeg", "AAAA")
        val h = listOf(AiMessage(AiRole.SYSTEM, "sys"), AiMessage(AiRole.TOOL, "orphan", toolCallId = "zz"),
            AiMessage(AiRole.USER, "看图", images = listOf(img)),
            AiMessage(AiRole.ASSISTANT, null, toolCalls = listOf(AiToolCall("x", "a", "{}"), AiToolCall("y", "b", "{}"))),
            AiMessage(AiRole.TOOL, "ok", toolCallId = "y"),
            AiMessage(AiRole.USER, "下一句"))
        val r = AiConvos.repair(h)
        assertEquals(listOf(AiRole.USER, AiRole.ASSISTANT, AiRole.TOOL, AiRole.TOOL, AiRole.USER), r.map { it.role })
        assertTrue(r[0].images.isEmpty())
        assertEquals(setOf("x", "y"), setOf(r[2].toolCallId, r[3].toolCallId))
        // 太长：只留最近的，从用户消息开始
        val many = (1..200).flatMap { listOf(AiMessage(AiRole.USER, "u$it"), AiMessage(AiRole.ASSISTANT, "a$it")) }
        val t = AiConvos.repair(many)
        assertTrue(t.size <= AiConvos.MAX_HISTORY); assertEquals(AiRole.USER, t.first().role); assertEquals("a200", t.last().text)
    }

    @Test fun restoredAgentCanContinue() {
        val m = AiAgentTest.Scripted("周三是机器学习，在 B303。")
        val a = AiAgent(m)
        a.restore(history.take(2))   // 存的时候卡片还没确认
        assertFalse(a.hasPending)
        val env = AiEnv(1_790_000_000_000L, TimeZone.getTimeZone("Asia/Shanghai"), listOf("chunyu" to "春雨"), { "n_$it" })
        val st = AppState(courses = listOf(Slot("c1", "高等数学", setOf(1), 480, 580)), secStarts = listOf(480), secEnds = listOf(525))
        val steps = a.send("周三有什么课", st, env, "zh")
        assertEquals("周三是机器学习，在 B303。", (steps.single() as AiAgent.Step.Text).text)
        val sent = m.seen.single()
        // 系统提示 + 用户 + 带工具调用的回复 + 补上的工具结果 + 新的一句
        assertEquals(listOf(AiRole.SYSTEM, AiRole.USER, AiRole.ASSISTANT, AiRole.TOOL, AiRole.USER), sent.map { it.role })
    }

    @Test fun quoteContextForModel() {
        assertEquals("你好", AiConvos.quoteContext("你好", null, "zh"))
        val zh = AiConvos.quoteContext("那周三呢", AiQuote(false, "周二有大学英语（第3-4节）。"), "zh")
        assertEquals("[用户在回复这条消息（你之前的回复）：「周二有大学英语（第3-4节）。」]\n那周三呢", zh)
        val own = AiConvos.quoteContext("改成 B303", AiQuote(true, "加一门机器学习"), "zh")
        assertTrue(own.startsWith("[用户在回复这条消息（用户自己之前发的）：「加一门机器学习」]")); assertTrue(own.endsWith("\n改成 B303"))
        val en = AiConvos.quoteContext("and Wednesday?", AiQuote(false, "English on Tuesday"), "en")
        assertEquals("[The user is replying to this message (your earlier reply): \"English on Tuesday\"]\nand Wednesday?", en)
        // 太长的引用截断
        val long = AiConvos.quoteContext("x", AiQuote(true, "字".repeat(2000)), "zh")
        assertTrue(long.length < AiConvos.QUOTE_MAX + 60); assertTrue(long.contains("…"))
        // 空引用不加
        assertEquals("x", AiConvos.quoteContext("x", AiQuote(true, "  "), "zh"))
        // 卡片被引用时用改动标题
        assertEquals("新增课程：机器学习", AiConvos.quoteText(SavedItem(AiConvos.CARD, card = card)))
    }

    @Test fun quotedMessageReachesModel() {
        val m = AiAgentTest.Scripted("周三有线性代数。")
        val a = AiAgent(m)
        val env = AiEnv(1_790_000_000_000L, TimeZone.getTimeZone("Asia/Shanghai"), listOf("chunyu" to "春雨"), { "n_$it" })
        val st = AppState(courses = listOf(Slot("c1", "高等数学", setOf(1), 480, 580)), secStarts = listOf(480), secEnds = listOf(525))
        a.send(AiConvos.quoteContext("那周三呢", AiQuote(false, "周二有大学英语。"), "zh"), st, env, "zh")
        val last = m.seen.single().last()
        assertEquals(AiRole.USER, last.role)
        assertTrue(last.text!!.contains("用户在回复这条消息") && last.text!!.contains("周二有大学英语。") && last.text!!.endsWith("那周三呢"))
    }

    @Test fun listOpsAndBadData() {
        var l = AiConvos.upsert(emptyList(), convo("a", 10))
        l = AiConvos.upsert(l, convo("b", 30))
        l = AiConvos.upsert(l, convo("a", 50))   // 更新：同 id 替换，排到最前
        assertEquals(listOf("a", "b"), l.map { it.id })
        assertEquals(listOf("b"), AiConvos.delete(l, "a").map { it.id })
        var big = emptyList<AiConvo>()
        for (k in 1..(AiConvos.MAX + 5)) big = AiConvos.upsert(big, convo("x$k", k.toLong()))
        assertEquals(AiConvos.MAX, big.size); assertEquals("x${AiConvos.MAX + 5}", big.first().id)
        // 坏数据不抛异常
        assertTrue(AiConvos.parseList(null).isEmpty())
        assertTrue(AiConvos.parseList("not json").isEmpty())
        assertTrue(AiConvos.parseList("""{"chats":[{"id":""},{"id":"n","items":[{"k":"bot","t":"only bot"}]},5]}""").isEmpty())
        val ok = AiConvos.parseList("""{"chats":[{"id":"z","u":3,"items":[{"k":"user","t":"hi"},{"k":"weird"}],"h":[{"r":"NOPE"},{"r":"USER","t":"hi"}]}]}""").single()
        assertEquals(1, ok.items.size); assertEquals("hi", ok.title); assertEquals(1, ok.history.size)
    }
}
