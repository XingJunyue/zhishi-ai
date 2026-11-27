package com.zhishi.timetable

import org.junit.Assert.*
import org.junit.Test

/** 「AI 帮我找课表」：工具参数解析、步数上限、密码框拦截（代码里，不靠提示词）、JSON → 导入预览用的课程。 */
class JwAgentTest {
    private val periods = (0 until 12).map { 480 + it * 55 to 480 + it * 55 + 45 }

    private val login = JwPageView("https://jw.x.edu.cn/login", "统一身份认证", "用户名 密码 登录",
        items = listOf(JwItem(1, "input", "用户名"), JwItem(2, "password", "密码"), JwItem(3, "button", "登录")), hasPassword = true)
    private val home = JwPageView("https://jw.x.edu.cn/home", "首页", "欢迎 测试同学",
        items = listOf(JwItem(1, "link", "首页"), JwItem(2, "link", "我的课表"), JwItem(3, "link", "退出")))
    private val kb = JwPageView("https://jw.x.edu.cn/kb", "我的课表", "",
        tables = listOf(" | 星期一 | 星期二\n第1-2节 | 操作系统 王老师 B201 1-16周 | ."))

    private class FakePage(var cur: JwPageView, val next: Map<String, JwPageView> = emptyMap()) : JwPage {
        val log = ArrayList<String>()
        override fun read(): JwPageView { log += "read"; return cur }
        override fun click(n: Int): String {
            log += "click $n"
            val it = cur.item(n) ?: return "error"
            next[it.label]?.let { cur = it }
            return "ok, clicked"
        }
        override fun back(): String { log += "back"; return "ok" }
        override fun scroll(down: Boolean): String { log += "scroll $down"; return "ok" }
        override fun screenshot(): AiImage? { log += "shot"; return AiImage("image/jpeg", "AAAA") }
    }

    /** 按顺序回复的假模型。 */
    private class Script(vararg r: AiReply) : AiClient {
        val replies = ArrayDeque(r.toList()); val seen = ArrayList<List<AiMessage>>(); var visions = 0
        override fun chat(messages: List<AiMessage>, tools: List<AiTool>): AiReply { seen += messages.toList(); return replies.removeFirstOrNull() ?: AiReply("", emptyList()) }
        override fun vision(system: String, prompt: String, images: List<AiImage>): String { visions++; return "a timetable page" }
        override fun test() = "OK"
        override fun models() = emptyList<String>()
    }
    private var id = 0
    private fun call(name: String, args: String = "{}") = AiReply(null, listOf(AiToolCall("c${++id}", name, args)), "tool_calls")
    private fun text(t: String) = AiReply(t, emptyList())

    @Test fun toolArgs() {
        assertEquals(3, JwAgent.argN("{\"n\":3}"))
        assertEquals(3, JwAgent.argN("{\"n\":\"3\"}"))
        assertEquals(7, JwAgent.argN("{\"index\":7}"))
        assertEquals(12, JwAgent.argN("{\"item\":\"[12]\"}"))
        assertEquals(4, JwAgent.argN("4"))
        assertNull(JwAgent.argN("{}"))
        assertNull(JwAgent.argN("{\"n\":0}"))
        assertNull(JwAgent.argN("not json"))
        assertTrue(JwAgent.argDown("{}"))
        assertTrue(JwAgent.argDown("{\"direction\":\"down\"}"))
        assertFalse(JwAgent.argDown("{\"direction\":\"UP\"}"))
        assertEquals("先登录", JwAgent.argText("{\"message\":\"先登录\"}", "message"))
        assertEquals("", JwAgent.argText("oops", "message"))
    }

    @Test fun pageJsonAndDescription() {
        val v = JwPageView.parse("""{"url":"u","title":"t","text":"a\n\n\nb","pw":false,"tables":["x | y"],
            "items":[{"n":1,"k":"link","t":"我的课表"},{"n":2,"k":"password","t":"密码"},{"n":3,"k":"input","t":"学号"}]}""")
        assertTrue(v.hasPassword)
        assertEquals("password", v.item(2)!!.kind)
        val d = JwAgent.describe(v)
        assertTrue(d, d.contains("[1] link: 我的课表"))
        assertTrue(d, d.contains("[2] password field (only the user types here)"))
        assertTrue(d, d.contains("[3] text field"))
        assertTrue(d, d.contains("password field: it is probably a login page"))
        assertTrue(d, d.contains("Table 1:\nx | y"))
        // 很长的页面被截短
        val long = JwPageView("u", "t", "字".repeat(20000), items = (1..400).map { JwItem(it, "link", "链接$it") })
        assertTrue(JwAgent.describe(long).length < 9000)
    }

    @Test fun guardRefusesPasswordAndTypingInCode() {
        assertNotNull(JwGuard.check("click", "{\"n\":2}", login))
        assertTrue(JwGuard.check("click", "{\"n\":2}", login)!!.startsWith("refused"))
        assertTrue(JwGuard.check("click", "{\"n\":1}", login)!!.startsWith("refused"))        // 普通输入框也不行
        assertNull(JwGuard.check("click", "{\"n\":3}", login))                                 // 按钮可以
        for (t in listOf("type", "type_text", "fill", "input_text", "evaluate", "run_js", "set_value"))
            assertTrue(t, JwGuard.check(t, "{\"n\":2,\"text\":\"123456\"}", login)!!.startsWith("refused"))
        assertNotNull(JwGuard.check("click", "{\"n\":9}", login))                              // 不存在的编号
        assertNotNull(JwGuard.check("click", "{\"n\":1}", null))                               // 没读过页面
        // 走一遍：模型想点密码框、想输入 → 都被拦下，页面上一次都没点；最后要求用户自己登录
        val page = FakePage(login)
        val ai = Script(call("read_page"), call("click", "{\"n\":2}"), call("type_text", "{\"n\":2,\"text\":\"123456\"}"), call("need_login", "{\"message\":\"请先登录\"}"))
        val steps = ArrayList<JwAgent.Step>()
        val out = JwAgent(ai, page, periods).run({ steps += it })
        assertTrue(out is JwAgent.Outcome.NeedLogin)
        assertEquals("请先登录", (out as JwAgent.Outcome.NeedLogin).message)
        assertEquals(listOf("read"), page.log)
        assertEquals(2, steps.count { it.refused })
        val toolResults = ai.seen.last().filter { it.role == AiRole.TOOL }.map { it.text.orEmpty() }
        assertTrue(toolResults[1], toolResults[1].startsWith("refused: [2] is a password field"))
        assertTrue(toolResults[2], toolResults[2].startsWith("refused: tool \"type_text\""))
        // 发给模型的东西里没有任何输入值（这里根本没有输入工具），系统提示写明不能输入
        assertTrue(ai.seen.first().first().text!!.contains("You cannot type anything"))
    }

    @Test fun stepLimit() {
        val page = FakePage(home)
        val ai = Script(*Array(40) { call("read_page") })
        val agent = JwAgent(ai, page, periods)
        val out = agent.run()
        assertTrue(out is JwAgent.Outcome.StepLimit)
        assertEquals(JwAgent.MAX_STEPS, page.log.size)
        assertEquals(10, agent.steps)
        // 到上限时先告诉模型一次（让它交结果），再要页面工具就停
        assertTrue(ai.seen.last().last().text!!.startsWith("error: step limit reached"))
        // 自定义上限
        val p2 = FakePage(home)
        assertTrue(JwAgent(Script(*Array(20) { call("scroll") }), p2, periods, maxSteps = 3).run() is JwAgent.Outcome.StepLimit)
        assertEquals(3, p2.log.size)
        // 到上限后还能交结果
        val p3 = FakePage(kb)
        val sub = call("submit_timetable", """{"courses":[{"name":"操作系统","weekday":1,"start_period":1,"end_period":2,"weeks":"1-16"}]}""")
        val out3 = JwAgent(Script(call("read_page"), call("read_page"), call("read_page"), sub), p3, periods, maxSteps = 2).run()
        assertTrue(out3 is JwAgent.Outcome.Found)
    }

    @Test fun navigateScreenshotAndSubmit() {
        val page = FakePage(home, mapOf("我的课表" to kb))
        val sub = call("submit_timetable", """{"courses":[
            {"name":"操作系统","teacher":"王老师","room":"B201","weekday":1,"start_period":1,"end_period":2,"weeks":"1-16","odd_even":"all"},
            {"name":"计算机网络","weekday":"星期三","start_period":3,"end_period":4,"weeks":"1-15","odd_even":"odd"},
            {"name":"","weekday":2,"start_period":1,"end_period":2},
            {"name":"坏的","weekday":9,"start_period":1,"end_period":2}]}""")
        val ai = Script(call("read_page"), call("click", "{\"n\":2}"), call("read_page"), call("screenshot"), sub)
        val steps = ArrayList<JwAgent.Step>()
        val out = JwAgent(ai, page, periods).run({ steps += it })
        assertTrue(out.toString(), out is JwAgent.Outcome.Found)
        out as JwAgent.Outcome.Found
        assertEquals(listOf("read", "click 2", "read", "shot"), page.log)
        assertEquals(1, ai.visions)
        assertEquals(4, out.steps)
        assertEquals(2, out.courses.size); assertEquals(2, out.dropped)
        val os = out.courses[0]
        assertEquals(JwCourse("操作系统", 1, 1, 2, (1..16).toSet(), "王老师", "B201"), os)
        assertEquals(setOf(1, 3, 5, 7, 9, 11, 13, 15), out.courses[1].weeks)
        assertEquals(3, out.courses[1].day)
        assertEquals("我的课表", steps.first { it.tool == "click" }.label)
        assertEquals("submit_timetable", steps.last().tool)
    }

    @Test fun textJsonIsRepairedLikeScreenshotImport() {
        // 直接回文字：带 ``` 围栏、前面有话、末尾逗号 → 和截图识别一样的修补
        val t = "找到了：\n```json\n{\"courses\":[{\"name\":\"数据结构\",\"weekday\":2,\"start_period\":3,\"end_period\":4,\"weeks\":\"2-16\",\"odd_even\":\"even\"},]}\n```"
        val out = JwAgent(Script(call("read_page"), text(t)), FakePage(kb), periods).run()
        assertTrue(out is JwAgent.Outcome.Found)
        val c = (out as JwAgent.Outcome.Found).courses.single()
        assertEquals("数据结构", c.name); assertEquals(2, c.day); assertEquals(3, c.start); assertEquals(4, c.end)
        assertEquals((2..16 step 2).toSet(), c.weeks)
        // 不是 JSON：追问一次，第二次给出 JSON
        val ai = Script(call("read_page"), text("课表在这一页上。"), text("{\"courses\":[{\"name\":\"体育\",\"weekday\":5,\"start_time\":\"08:00\",\"end_time\":\"09:40\"}]}"))
        val out2 = JwAgent(ai, FakePage(kb), periods).run()
        assertTrue(out2 is JwAgent.Outcome.Found)
        assertEquals(1, (out2 as JwAgent.Outcome.Found).courses.single().start)
        // 空的 submit：先让它改一次，再空就算没找到（不交空课表）
        val empty = call("submit_timetable", "{\"courses\":[]}")
        val out3 = JwAgent(Script(call("read_page"), empty, call("submit_timetable", "{\"courses\":[]}")), FakePage(kb), periods).run()
        assertTrue(out3 is JwAgent.Outcome.NotFound)
        // not_found
        val out4 = JwAgent(Script(call("not_found", "{\"reason\":\"没有课表菜单\"}")), FakePage(home), periods).run()
        assertEquals("没有课表菜单", (out4 as JwAgent.Outcome.NotFound).reason)
    }

    @Test fun stopAndErrors() {
        var stop = false
        val page = FakePage(home)
        val ai = object : AiClient {
            override fun chat(messages: List<AiMessage>, tools: List<AiTool>): AiReply { stop = true; return AiReply(null, listOf(AiToolCall("x", "read_page", "{}"))) }
            override fun vision(system: String, prompt: String, images: List<AiImage>) = ""
            override fun test() = ""
            override fun models() = emptyList<String>()
        }
        assertTrue(JwAgent(ai, page, periods).run(stopped = { stop }) is JwAgent.Outcome.Stopped)
        assertTrue(page.log.isEmpty())
        val bad = object : AiClient {
            override fun chat(messages: List<AiMessage>, tools: List<AiTool>): AiReply = throw AiError.Auth(401, "bad key")
            override fun vision(system: String, prompt: String, images: List<AiImage>) = ""
            override fun test() = ""
            override fun models() = emptyList<String>()
        }
        val out = JwAgent(bad, page, periods).run()
        assertTrue((out as JwAgent.Outcome.Failed).error is AiError.Auth)
        // 工具列表里没有任何「输入」类工具
        assertEquals(setOf("read_page", "click", "back", "scroll", "screenshot", "submit_timetable", "need_login", "not_found"), JwAgent.TOOLS.map { it.name }.toSet())
    }
}
