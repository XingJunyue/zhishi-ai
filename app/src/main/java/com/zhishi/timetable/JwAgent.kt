package com.zhishi.timetable

import org.json.JSONArray
import org.json.JSONObject

/*
 * 「AI 帮我找课表」：直接从教务导入失败 / 没找到课表时，用户自己在内置浏览器里登录好，
 * 由用户自己设置的对话模型（+ 看图模型）用几个工具操作网页，找到课表页面、读出课表，
 * 最后给出和截图识别一样的严格 JSON（AiRecognize.parse 校验 / 修补）→ 原来的导入预览（覆盖 / 追加）。
 *
 * 纯 Kotlin（不碰安卓）：网页操作走 JwPage 接口（安卓那层是 WebView，单测里是假的）。
 * 安全：模型只有 read_page / click / back / scroll / screenshot 这几个工具，**没有任何输入文字的工具**；
 * 点击前由 JwGuard 在代码里检查：密码框和所有输入框一律拒绝（WebView 那层的脚本再查一遍）；读页面时不读任何输入框的值。
 */

/** 页面上一个可以点的东西（编号从 1 开始）。kind：link / button / option / select / input / password。 */
data class JwItem(val n: Int, val kind: String, val label: String)

/** read_page 看到的页面。 */
class JwPageView(
    val url: String, val title: String, val text: String,
    val tables: List<String> = emptyList(), val items: List<JwItem> = emptyList(), val hasPassword: Boolean = false,
) {
    fun item(n: Int) = items.firstOrNull { it.n == n }

    companion object {
        /** WebView 脚本返回的 JSON → 页面。 */
        fun parse(s: String): JwPageView {
            val j = JSONObject(s)
            val items = ArrayList<JwItem>()
            j.optJSONArray("items")?.let { a -> for (k in 0 until a.length()) a.optJSONObject(k)?.let { o -> items += JwItem(o.optInt("n"), o.optString("k"), o.optString("t")) } }
            val tables = ArrayList<String>()
            j.optJSONArray("tables")?.let { a -> for (k in 0 until a.length()) tables += a.optString(k) }
            return JwPageView(j.optString("url"), j.optString("title"), j.optString("text"), tables, items,
                j.optBoolean("pw") || items.any { it.kind == "password" })
        }
    }
}

/** 内置浏览器（安卓：WebView；单测：假的）。所有方法阻塞，在后台线程调。 */
interface JwPage {
    fun read(): JwPageView
    /** 点第 n 个（read 之后的编号）：返回 "ok …" 或 "error: …" / "refused: …"。 */
    fun click(n: Int): String
    fun back(): String
    fun scroll(down: Boolean): String
    /** 当前可见区域的截图（JPEG），拿不到返回 null。 */
    fun screenshot(): AiImage?
}

/** 代码里的硬性规则：只认这几个工具；点击不能落在密码框 / 输入框上。 */
object JwGuard {
    val PAGE_TOOLS = setOf("read_page", "click", "back", "scroll", "screenshot")
    val END_TOOLS = setOf("submit_timetable", "need_login", "not_found")
    /** 不允许的原因；null = 可以执行。 */
    fun check(name: String, args: String, view: JwPageView?): String? {
        if (name !in PAGE_TOOLS && name !in END_TOOLS) return "refused: tool \"$name\" is not available. You can only read_page, click, back, scroll and screenshot; you can never type anything."
        if (name != "click") return null
        val n = JwAgent.argN(args) ?: return "error: click needs the item number n from read_page"
        val v = view ?: return "error: call read_page first to get item numbers"
        val it = v.item(n) ?: return "error: there is no item [$n] on the current page; call read_page again"
        if (it.kind == "password") return "refused: [$n] is a password field. Only the user types passwords; never touch it. If the user is not logged in yet, call need_login."
        if (it.kind == "input") return "refused: [$n] is a text field. Only the user types into form fields; you can only click links and buttons."
        return null
    }
}

class JwAgent(
    private val client: AiClient, private val page: JwPage,
    private val periods: List<Pair<Int, Int>>, private val totalWeeks: Int = 20,
    val maxSteps: Int = MAX_STEPS, private val lang: String = "zh",
) {
    companion object {
        const val MAX_STEPS = 10
        /** 截图的说明最多带回多少字给对话模型。 */
        private const val VISION_MAX = 4000

        /** click 的编号：{"n":3} / {"n":"3"} / {"index":3} / {"item":"[3]"} / "3"。 */
        fun argN(args: String): Int? {
            val t = args.trim()
            val j = try { JSONObject(t.ifEmpty { "{}" }) } catch (x: Exception) { null }
            val raw: Any? = if (j != null) listOf("n", "index", "item", "id", "number").firstNotNullOfOrNull { k -> j.opt(k)?.takeIf { it != JSONObject.NULL } } else t
            return when (raw) {
                is Number -> raw.toInt().takeIf { it > 0 }
                is String -> Regex("\\d+").find(raw)?.value?.toIntOrNull()?.takeIf { it > 0 }
                else -> null
            }
        }
        /** scroll 的方向：默认往下。 */
        fun argDown(args: String): Boolean {
            val j = try { JSONObject(args.trim().ifEmpty { "{}" }) } catch (x: Exception) { return !args.contains("up", true) }
            return !j.optString("direction", "down").trim().lowercase().startsWith("up")
        }
        fun argText(args: String, key: String): String = try { JSONObject(args.trim().ifEmpty { "{}" }).optString(key) } catch (x: Exception) { "" }

        /** 给模型看的页面（限长；没有任何输入框的值）。 */
        fun describe(v: JwPageView): String {
            val sb = StringBuilder()
            sb.append("URL: ").append(v.url.take(300)).append('\n').append("Title: ").append(v.title.take(120)).append('\n')
            if (v.hasPassword) sb.append("This page has a password field: it is probably a login page. You cannot type; if the user is not logged in, call need_login.\n")
            sb.append("Clickable items:\n")
            if (v.items.isEmpty()) sb.append("(none found)\n")
            for (it in v.items.take(150)) {
                val label = it.label.replace(Regex("\\s+"), " ").trim().take(50)
                when (it.kind) {
                    "password" -> sb.append("[${it.n}] password field (only the user types here)").append(if (label.isNotEmpty()) " $label" else "").append('\n')
                    "input" -> sb.append("[${it.n}] text field (only the user types here)").append(if (label.isNotEmpty()) " $label" else "").append('\n')
                    else -> sb.append("[${it.n}] ${it.kind}: $label\n")
                }
            }
            if (v.tables.isNotEmpty()) {
                sb.append("Tables:\n"); var left = 5000
                v.tables.forEachIndexed { i, t -> if (left > 0) { val s = t.take(left); sb.append("Table ${i + 1}:\n").append(s).append('\n'); left -= s.length } }
            }
            sb.append("Text:\n").append(v.text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim().take(3000))
            return sb.toString()
        }

        private fun o(vararg p: Pair<String, JSONObject>, required: List<String> = emptyList()) = JSONObject().put("type", "object")
            .put("properties", JSONObject().also { j -> p.forEach { (k, v) -> j.put(k, v) } }).put("required", JSONArray(required))
        private fun str(d: String) = JSONObject().put("type", "string").put("description", d)

        val TOOLS: List<AiTool> by lazy { listOf(
            AiTool("read_page", "Read the current page: URL, title, numbered clickable items (links, buttons, menu entries, also inside frames), tables and trimmed text.", o()),
            AiTool("click", "Click item [n] from the latest read_page (links, buttons, menu entries, tabs). Form fields cannot be clicked or typed into.",
                o("n" to JSONObject().put("type", "integer").put("description", "Item number from read_page"), required = listOf("n"))),
            AiTool("back", "Go back to the previous page.", o()),
            AiTool("scroll", "Scroll the page.", o("direction" to str("down or up").put("enum", JSONArray(listOf("down", "up"))))),
            AiTool("screenshot", "Take a screenshot of the visible part of the page; a vision model reads it for you (useful when the timetable is drawn as an image or the text is unclear).", o()),
            AiTool("submit_timetable", "Finish: submit every class meeting of the user's own timetable that you have read.",
                o("courses" to JSONObject().put("type", "array").put("description", "One entry per class meeting: name, teacher, room, weekday (1=Monday … 7=Sunday), start_period, end_period, start_time, end_time (HH:mm, only when no periods are shown), weeks (e.g. \"1-16\"), odd_even (all/odd/even)")
                    .put("items", JSONObject().put("type", "object")), required = listOf("courses"))),
            AiTool("need_login", "Stop: the user still has to log in (or finish two-factor authentication) themselves.", o("message" to str("Short note for the user"))),
            AiTool("not_found", "Stop: you could not find the timetable.", o("reason" to str("Short reason for the user"))),
        ) }
    }

    /** 进度：第几步、哪个工具、点了什么（给界面画进度用）。refused = 被代码拦下了。 */
    data class Step(val n: Int, val tool: String, val label: String, val refused: Boolean = false)

    sealed class Outcome {
        class Found(val courses: List<JwCourse>, val dropped: Int, val steps: Int) : Outcome()
        class NeedLogin(val message: String) : Outcome()
        class NotFound(val reason: String) : Outcome()
        object StepLimit : Outcome()
        object Stopped : Outcome()
        class Failed(val error: Throwable) : Outcome()
    }

    var steps = 0; private set
    private var view: JwPageView? = null
    /** 发给模型的全部消息（单测看）。 */
    val messages = ArrayList<AiMessage>()

    fun system(): String {
        val table = periods.mapIndexed { i, (a, b) -> "${i + 1}=${AiTools.clock(a)}-${AiTools.clock(b)}" }.joinToString(", ")
        val langName = mapOf("zh" to "简体中文", "en" to "English", "fr" to "Français", "ja" to "日本語", "ko" to "한국어", "de" to "Deutsch", "es" to "Español")[lang] ?: "the user's language"
        return """You operate an in-app browser for a student inside the timetable app 知时 AI. The student has opened their school's academic affairs (教务) website and logged in themselves.
Goal: reach the page that shows the student's OWN class timetable for the current term (often called 我的课表, 学生课表, 个人课表, 课表查询, 本学期课表, 学期课表, Timetable), read it, and call submit_timetable with every class meeting.
Rules:
- You can only use read_page, click, back, scroll and screenshot. You cannot type anything. Never try to enter, change or read usernames, passwords or verification codes; password and text fields are refused by the app.
- You have at most $maxSteps page actions in total, so be efficient: call read_page first, then click the most likely menu item. Timetables may be inside frames, under a menu such as 培养管理 / 学生服务 / 信息查询 / 选课, or need a "查询" button.
- If the page is a login page or asks for verification, call need_login. If you cannot find the timetable, call not_found with a short reason. Do not submit an empty or invented timetable.
- If the table text is unclear or the timetable is an image, call screenshot.
- submit_timetable format: one entry per class meeting (a course on Monday and Thursday = two entries); weekday 1=Monday … 7=Sunday from the column header; start_period / end_period are the period (节次) numbers, inclusive (if only clock times are shown, fill start_time/end_time as HH:mm and use 0 for periods); weeks exactly as written (e.g. "1-16", "1-8,10-16"), "" if not shown; odd_even "odd" for 单周, "even" for 双周, otherwise "all". Keep names, teachers and rooms in the original language; "" when absent. If a cell lists several courses, output all of them.
- The user's period times are: $table.
- Messages for the user (need_login / not_found) in $langName, one short sentence."""
    }

    private fun visionPrompt(): String {
        val table = periods.mapIndexed { i, (a, b) -> "${i + 1}=${AiTools.clock(a)}-${AiTools.clock(b)}" }.joinToString(", ")
        return """This is a screenshot of a page on a school's academic affairs (教务) website.
If it shows a class timetable, transcribe every class meeting as JSON {"courses":[{"name":"","teacher":"","room":"","weekday":1,"start_period":1,"end_period":2,"start_time":"","end_time":"","weeks":"1-16","odd_even":"all"}]} (weekday 1=Monday; periods from the row labels; the user's period times are $table).
Otherwise describe in 1-3 sentences what the page shows and list the visible menu items, tabs, links and buttons that might lead to the student's timetable."""
    }

    /** 跑完整个流程（阻塞，在后台线程调）。stopped() 为 true 时尽快停下。 */
    fun run(onStep: (Step) -> Unit = {}, stopped: () -> Boolean = { false }): Outcome = try { loop(onStep, stopped) } catch (x: Throwable) { if (stopped()) Outcome.Stopped else Outcome.Failed(x) }

    private fun loop(onStep: (Step) -> Unit, stopped: () -> Boolean): Outcome {
        messages.clear()
        messages += AiMessage(AiRole.SYSTEM, system())
        messages += AiMessage(AiRole.USER, "I'm logged in. Please find my timetable for this term.")
        var repairs = 0; var overLimit = false
        // 模型回合数也有上限（每回合至少一步，或者是修 JSON / 收尾）
        for (round in 0 until maxSteps + 4) {
            if (stopped()) return Outcome.Stopped
            val reply = client.chat(messages, TOOLS)
            if (stopped()) return Outcome.Stopped
            if (reply.toolCalls.isEmpty()) {
                val text = reply.text.orEmpty()
                messages += AiMessage(AiRole.ASSISTANT, text)
                val r = AiRecognize.parse(text, periods, totalWeeks)
                if (r != null && r.courses.isNotEmpty()) return found(r, onStep)
                if (repairs < 1) {
                    repairs++
                    messages += AiMessage(AiRole.USER, "Use the tools. If you have read the timetable, call submit_timetable with every class (or reply with ONLY the JSON {\"courses\":[...]}); otherwise call not_found.")
                    continue
                }
                return Outcome.NotFound(text.take(200))
            }
            messages += AiMessage(AiRole.ASSISTANT, reply.text, toolCalls = reply.toolCalls)
            var end: Outcome? = null
            for (call in reply.toolCalls) {
                if (end != null) { messages += AiMessage(AiRole.TOOL, "skipped", toolCallId = call.id); continue }
                if (stopped()) return Outcome.Stopped
                val result: String = when (call.name) {
                    "submit_timetable" -> {
                        val r = AiRecognize.parse(call.arguments, periods, totalWeeks)
                        if (r != null && r.courses.isNotEmpty()) { end = found(r, onStep); "ok" }
                        else if (repairs < 1) { repairs++; "error: no valid classes in \"courses\" (each needs name, weekday 1-7 and periods or times). Fix it and call submit_timetable again, or call not_found." }
                        else { end = Outcome.NotFound("empty"); "error: empty" }
                    }
                    "need_login" -> { end = Outcome.NeedLogin(argText(call.arguments, "message")); "ok" }
                    "not_found" -> { end = Outcome.NotFound(argText(call.arguments, "reason")); "ok" }
                    else -> {
                        val refuse = JwGuard.check(call.name, call.arguments, view)
                        if (refuse != null) {
                            onStep(Step(steps, call.name, label(call), refused = true)); refuse
                        } else if (steps >= maxSteps) {
                            if (overLimit) return Outcome.StepLimit
                            overLimit = true
                            "error: step limit reached ($maxSteps page actions). Do not call page tools any more: call submit_timetable now if you have read the timetable, otherwise call not_found."
                        } else {
                            steps++
                            onStep(Step(steps, call.name, label(call)))
                            exec(call)
                        }
                    }
                }
                messages += AiMessage(AiRole.TOOL, result, toolCallId = call.id)
            }
            end?.let { return it }
        }
        return Outcome.StepLimit
    }

    private fun found(r: AiRecognize.Result, onStep: (Step) -> Unit): Outcome {
        onStep(Step(steps, "submit_timetable", r.courses.size.toString()))
        return Outcome.Found(r.courses, r.dropped, steps)
    }

    /** 进度里显示的东西：点的是哪一项的文字（不带任何输入内容）。 */
    private fun label(call: AiToolCall): String = when (call.name) {
        "click" -> argN(call.arguments)?.let { n -> view?.item(n)?.label?.take(24) ?: "[$n]" } ?: ""
        "scroll" -> if (argDown(call.arguments)) "down" else "up"
        else -> ""
    }

    private fun exec(call: AiToolCall): String = when (call.name) {
        "read_page" -> { val v = page.read(); view = v; describe(v) }
        "click" -> {
            val n = argN(call.arguments)!!
            val r = page.click(n)
            // 点完页面可能变了：旧编号作废，要模型重新 read_page
            if (r.startsWith("ok")) { view = null; "$r. The page may have changed; call read_page to see it." } else r
        }
        "back" -> { val r = page.back(); if (r.startsWith("ok")) view = null; r }
        "scroll" -> page.scroll(argDown(call.arguments))
        "screenshot" -> {
            val img = page.screenshot()
            if (img == null) "error: screenshot failed"
            else "Screenshot (read by the vision model):\n" + client.vision("You read screenshots of web pages precisely.", visionPrompt(), listOf(img)).take(VISION_MAX)
        }
        else -> "error: unknown tool"
    }
}
