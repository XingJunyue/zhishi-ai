package com.zhishi.timetable

import org.json.JSONArray
import org.json.JSONObject

/*
 * AI 助手的对话逻辑（纯 Kotlin，不碰界面和存储）：
 *  - 每次发送都把当前的课表、待办、设置作为上下文放进系统提示；
 *  - 模型调工具改东西：AiTools.plan 在快照上算出新状态，马上交给 apply（安卓那层写进存储），把「已应用」回给模型，
 *    模型再接着说一句（或者接着改）；一轮里所有改动最后合成一批（Step.Applied），界面弹一次「已改好 N 处 · 撤销」；
 *  - 打开页面这种不改数据的动作直接执行；参数错了把原因回给模型让它重来（最多几轮）。
 */
class AiAgent(var client: AiClient, private val maxRounds: Int = 4) {
    /** 对话里的一步（界面按这个画）。 */
    sealed class Step {
        class Text(val text: String) : Step()
        /** 这一轮已经写进去的全部改动（按先后），failed = 参数不对没执行的工具调用数。 */
        class Applied(val edits: List<AiEdit>, val failed: Int) : Step()
        class Nav(val dest: String) : Step()
        class Failed(val error: Throwable) : Step()
    }

    /** 不含系统提示的历史。 */
    val history = ArrayList<AiMessage>()

    fun reset() { history.clear() }

    /** 打开一段存下来的对话：换上它的历史（先由 AiConvos.repair 整理过：每个工具调用都有结果）。 */
    fun restore(h: List<AiMessage>) { reset(); history.addAll(AiConvos.repair(h)) }

    /** 系统提示：规则 + 当前数据。 */
    fun system(s: AppState, env: AiEnv, lang: String): String {
        val today = Todos.dayOfMs(env.now, env.tz)
        val week = if (s.termStart >= 0) AiTools.weekOf(s.termStart, today) else null
        val wd = Math.floorMod(today + 3, 7L).toInt() + 1
        val ctx = JSONObject()
        ctx.put("now", AiTools.ymd(today) + " " + AiTools.clock(Todos.minuteOf(env.now, env.tz)) + " (weekday $wd)")
        ctx.put("term", JSONObject().put("week1_monday", if (s.termStart >= 0) AiTools.ymd(s.termStart) else "not set").put("current_week", week ?: JSONObject.NULL).put("total_weeks", s.totalWeeks))
        ctx.put("periods", JSONArray().also { a -> s.secStarts.forEachIndexed { i, st -> a.put("${i + 1}: ${AiTools.clock(st)}-${AiTools.clock(s.secEnds.getOrElse(i) { st + 45 })}") } })
        ctx.put("courses", JSONArray().also { a -> s.courses.forEach { c ->
            val sp = AiTools.secSpan(s.secStarts, s.secEnds, c.from, c.to)
            a.put(JSONObject().put("id", c.id).put("name", c.name).put("weekdays", JSONArray(c.days.sorted()))
                .put("periods", if (sp != null) "${sp.first}-${sp.second}" else "").put("time", AiTools.clock(c.from) + "-" + AiTools.clock(c.to))
                .put("weeks", if (c.weeks.isEmpty()) "all" else c.weeks.sorted().joinToString(",")).put("room", c.room).put("teacher", c.teacher)
                .put("level", listOf("easy", "normal", "important").getOrElse(c.level) { "normal" }).also { j -> if (c.note.isNotBlank()) j.put("note", c.note) })
        } })
        ctx.put("todos", JSONArray().also { a -> s.todos.sortedWith(compareBy({ it.done }, { if (it.due < 0) Long.MAX_VALUE else it.due })).take(80).forEach { t ->
            a.put(JSONObject().put("id", t.id).put("title", t.title).put("due", if (t.due < 0) "" else AiTools.dueTxtPlain(t, env.tz))
                .put("done", t.done).put("pinned", t.pin).put("remind", if (t.remind) "${t.before} min before" else "off")
                .also { j -> if (t.course.isNotBlank()) j.put("course", t.course); if (t.note.isNotBlank()) j.put("note", t.note.take(200)) })
        } })
        ctx.put("skins", JSONArray().also { a -> env.skins.forEach { a.put("${it.first} (${it.second})") } })
        ctx.put("settings", JSONObject().put("skin", s.skin).put("mode", listOf("system", "light", "dark")[s.mode.coerceIn(0, 2)])
            .put("skin_rotation", listOf("off", "random", "favorites")[s.rotMode.coerceIn(0, 2)]).put("rotation_every", listOf("launch", "daily", "weekly")[s.rotEvery.coerceIn(0, 2)])
            .put("favorite_skins", JSONArray(s.favs))
            .put("timetable_widget", look(s.widgetCourse)).put("todo_widget", look(s.widgetTodo))
            .put("show_weekend", s.showWeekend).put("show_other_week_courses", s.showOff).put("show_level_tags", s.showLevel).put("show_level_tags_in_widget", s.widgetLevel))
        val langName = mapOf("zh" to "简体中文", "en" to "English", "fr" to "Français", "ja" to "日本語", "ko" to "한국어", "de" to "Deutsch", "es" to "Español")[lang] ?: "the user's language"
        return """You are the assistant inside 知时 AI (Zhishi AI), a student timetable and to-do app.
Answer in $langName, briefly and warmly, as plain text (no Markdown: no **, no #, no tables; simple "- " lists are fine).
- To change anything (courses, term, period times, to-dos, skins, widgets, settings) you MUST call the tools; you cannot change data any other way.
- Tool calls take effect immediately. After all your tool calls the app shows the user one undo prompt for the whole batch, so do not ask the user to confirm; just make the change and then say in one short sentence what you changed.
- Only say something was changed if you called a tool for it and its result says "applied". Never answer a change request with text alone.
- Use ids from the context below. Weekday 1=Monday … 7=Sunday. Dates are local time. Resolve relative dates ("tomorrow", "next Friday") from "now".
- Batch several changes in one reply when the user asks for several things.
- For questions about the timetable or to-dos, answer from the context; do not call tools.
- If something is ambiguous (e.g. two courses with the same name), ask a short question instead of guessing.
- Changing the language is not possible from chat; you can open the "me" page instead.
Current app data (JSON):
$ctx"""
    }

    private fun look(w: WidgetLook) = JSONObject().put("style", w.style).put("glass_color", if (w.tint == WidgetStyleIds.SKIN) "skin" else "wallpaper")
        .put("transparency", w.clear).put("shade", listOf("follow_app", "light", "dark")[w.dmode.coerceIn(0, 2)])

    /** 历史别太长：只留最近的，并且从一条用户消息开始（不能把工具调用和它的结果拆开）。 */
    private fun trimmed(): List<AiMessage> {
        if (history.size <= 40) return history
        var k = history.size - 40
        while (k < history.size && history[k].role != AiRole.USER) k++
        return history.subList(k, history.size)
    }

    /**
     * 用户发了一句话：跑到模型给出文字或打开页面为止。
     * apply：把一批改动写进 App（安卓那层从当前真实状态出发，只动这批改过的东西），返回写完以后的状态；null = 写入失败。
     */
    fun send(text: String, state: AppState, env: AiEnv, lang: String, apply: (AiPlan) -> AppState? = { it.after }): List<Step> {
        history += AiMessage(AiRole.USER, text)
        return loop(state, env, lang, apply)
    }

    /** 上一次失败了（网络等）：不加新消息，直接再跑一次。 */
    fun retry(state: AppState, env: AiEnv, lang: String, apply: (AiPlan) -> AppState? = { it.after }): List<Step> {
        if (history.isEmpty() || history.last().role == AiRole.ASSISTANT) return emptyList()
        return loop(state, env, lang, apply)
    }

    private fun loop(state0: AppState, env: AiEnv, lang: String, apply: (AiPlan) -> AppState?): List<Step> {
        val out = ArrayList<Step>()
        val edits = ArrayList<AiEdit>()
        var failed = 0
        var state = state0
        var nudged = false
        fun done(): List<Step> { if (edits.isNotEmpty()) out += Step.Applied(edits.toList(), failed); return out }
        repeat(maxRounds) {
            val reply = try { client.chat(listOf(AiMessage(AiRole.SYSTEM, system(state, env, lang))) + trimmed(), AiTools.TOOLS) }
            catch (e: Throwable) {
                // 失败的这一轮不留在历史里（用户消息保留，可以重试）；这一轮之前已经改好的照样算一批
                out += Step.Failed(e); return done()
            }
            history += AiMessage(AiRole.ASSISTANT, reply.text, toolCalls = reply.toolCalls)
            if (reply.toolCalls.isEmpty()) {
                // 模型嘴上说「改好了 / 请确认」却一个工具都没调：提醒它一次，让它真的调用
                if (!nudged && edits.isEmpty() && claimsChange(reply.text)) {
                    nudged = true
                    history += AiMessage(AiRole.USER, NUDGE)
                    return@repeat
                }
                out += Step.Text(reply.text ?: if (edits.isNotEmpty()) tr("改好了。") else "…"); return done()
            }
            val plan = AiTools.plan(state, reply.toolCalls, env)
            plan.nav?.let { out += Step.Nav(it) }
            val after = if (plan.hasChanges) (try { apply(plan) } catch (e: Throwable) { null }) else null
            for (r in plan.results) {
                val msg = when {
                    !r.ok -> r.message
                    r.name == AiTools.OPEN_PAGE -> r.message
                    !plan.hasChanges -> "ok: " + r.message
                    after != null -> "applied: " + r.message + " (already saved; the user can undo it from the undo prompt)"
                    else -> "error: saving failed on the device; nothing was changed"
                }
                history += AiMessage(AiRole.TOOL, msg, toolCallId = r.callId)
            }
            failed += plan.results.count { !it.ok }
            if (after != null) { state = after; edits += plan.edits }
            // 只打开了页面：模型的话（没有就「已打开」）直接显示
            if (!plan.hasChanges && plan.results.all { it.ok }) {
                reply.text?.let { t -> out += Step.Text(t) }
                if (reply.text == null && plan.nav != null) out += Step.Text(tr("已打开"))
                return done()
            }
        }
        if (edits.isEmpty()) out += Step.Text(tr("这次没能整理出可以执行的操作，换个说法试试？"))
        return done()
    }

    companion object {
        const val NUDGE = "[app note] You said you changed something but did not call any tool, so nothing was changed. Call the needed tool(s) now."
        private val CLAIM = Regex("确认卡片|点击确认|点确认|确认后生效|确认后就|已为你准备|已经为你准备|为你准备好|准备好了|请确认|" +
            "已(经)?(为你|帮你|给你|替你)?(把[^，。,.!?！？]{0,16})?(添加|加上|加好|加了|改好|改了|修改|改成|删除|删掉|删了|设置|设好|设成|置顶|完成|切换|换成|换好|换了|更新|移到|挪到|隐藏|显示|关闭|关掉|打开|开启)|" +
            "confirmation card|tap confirm|press confirm|once you confirm|ready for (your )?confirmation|" +
            "I('ve| have) (added|changed|updated|deleted|removed|set|moved|pinned|switched|created)|確認カード|확인 카드", RegexOption.IGNORE_CASE)
        /** 回复文字像是在说「改好了 / 请确认」（纯函数，有单测）。以问号结尾的是在反问用户（「是哪门课？」），不算。 */
        fun claimsChange(t: String?) = t != null && CLAIM.containsMatchIn(t) && !t.trimEnd().endsWith("？") && !t.trimEnd().endsWith("?")
    }

    /** 对话里附了课表截图：识别结果记进历史（模型以后问到能接着聊）。 */
    fun noteRecognition(n: Int, names: List<String>) {
        history += AiMessage(AiRole.USER, "[I attached a timetable screenshot]")
        history += AiMessage(AiRole.ASSISTANT, "Recognised $n class meetings (${names.distinct().take(20).joinToString()}); the app opened its import preview so the user can choose replace or append.")
    }

    /** 撤销了一些改动：告诉模型（titles = 撤销了哪几项）。 */
    fun noteUndo(titles: List<String> = emptyList()) {
        history += AiMessage(AiRole.USER, if (titles.isEmpty()) "[I undid the last applied changes]" else "[I undid these changes: " + titles.joinToString("; ").take(800) + "]")
        history += AiMessage(AiRole.ASSISTANT, "OK, those changes were undone.")
    }
}
