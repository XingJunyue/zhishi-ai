package com.zhishi.timetable

import org.json.JSONArray
import org.json.JSONObject

/*
 * AI 助手的对话逻辑（纯 Kotlin，不碰界面和存储）：
 *  - 每次发送都把当前的课表、待办、设置作为上下文放进系统提示；
 *  - 模型要改东西时只返回提议（AiPlan），等用户在确认卡片上点「确认 / 取消」后再把结果告诉模型；
 *  - 打开页面这种不改数据的动作直接执行；参数错了把原因回给模型让它重来（最多几轮）。
 */
class AiAgent(var client: AiClient, private val maxRounds: Int = 4) {
    /** 对话里的一步（界面按这个画）。 */
    sealed class Step {
        class Text(val text: String) : Step()
        /** 等用户确认的一批改动。 */
        class Pending(val plan: AiPlan, val text: String?) : Step()
        class Nav(val dest: String) : Step()
        class Failed(val error: Throwable) : Step()
    }

    /** 不含系统提示的历史。 */
    val history = ArrayList<AiMessage>()
    /** 还没确认的那批：它的工具结果要等用户决定后才补进历史。 */
    private var pending: Pair<AiPlan, List<AiToolResult>>? = null
    val hasPending get() = pending != null

    fun reset() { history.clear(); pending = null }

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
- Tool calls are only proposals: the app shows the user a confirmation card and applies them only after the user taps Confirm. Never claim a change is already done; say you prepared it for confirmation.
- Only mention a confirmation card if you actually called a tool in the same reply. Never answer a change request with text alone.
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

    /** 用户发了一句话：跑到模型给出文字、提议或打开页面为止。 */
    fun send(text: String, state: AppState, env: AiEnv, lang: String): List<Step> {
        if (pending != null) resolve(false, null)
        history += AiMessage(AiRole.USER, text)
        return loop(state, env, lang)
    }

    /** 上一次失败了（网络等）：不加新消息，直接再跑一次。 */
    fun retry(state: AppState, env: AiEnv, lang: String): List<Step> {
        if (history.isEmpty() || history.last().role == AiRole.ASSISTANT) return emptyList()
        return loop(state, env, lang)
    }

    private fun loop(state: AppState, env: AiEnv, lang: String): List<Step> {
        val out = ArrayList<Step>()
        var nudged = false
        repeat(maxRounds) {
            val reply = try { client.chat(listOf(AiMessage(AiRole.SYSTEM, system(state, env, lang))) + trimmed(), AiTools.TOOLS) }
            catch (e: Throwable) {
                // 失败的这一轮不留在历史里（用户消息保留，可以重试）
                out += Step.Failed(e); return out
            }
            history += AiMessage(AiRole.ASSISTANT, reply.text, toolCalls = reply.toolCalls)
            if (reply.toolCalls.isEmpty()) {
                // 模型嘴上说「准备好了，请确认」却没调工具：提醒它一次，让它真的调用
                if (!nudged && claimsChange(reply.text)) {
                    nudged = true
                    history += AiMessage(AiRole.USER, NUDGE)
                    return@repeat
                }
                out += Step.Text(reply.text ?: "…"); return out
            }
            val plan = AiTools.plan(state, reply.toolCalls, env)
            plan.nav?.let { out += Step.Nav(it) }
            if (plan.hasChanges) {
                pending = plan to plan.results
                out += Step.Pending(plan, reply.text)
                return out
            }
            // 没有要确认的：把结果（打开了页面 / 参数错了）回给模型
            for (r in plan.results) history += AiMessage(AiRole.TOOL, r.message, toolCallId = r.callId)
            val allOk = plan.results.all { it.ok }
            if (allOk) { reply.text?.let { t -> out += Step.Text(t) }; if (reply.text == null && plan.nav != null) out += Step.Text(tr("已打开")); return out }
        }
        out += Step.Text(tr("这次没能整理出可以执行的操作，换个说法试试？"))
        return out
    }

    companion object {
        const val NUDGE = "[app note] You described a change but did not call any tool, so nothing can be confirmed. Call the needed tool(s) now."
        private val CLAIM = Regex("确认卡片|点击确认|点确认|确认后生效|确认后就|已为你准备|已经为你准备|为你准备好|准备好了|请确认|confirmation card|tap confirm|press confirm|once you confirm|ready for (your )?confirmation|確認カード|확인 카드", RegexOption.IGNORE_CASE)
        /** 回复文字像是在说「改动已准备好 / 请确认」（纯函数，有单测）。 */
        fun claimsChange(t: String?) = t != null && CLAIM.containsMatchIn(t)
    }

    /** 用户点了确认 / 取消（applied = 实际写入成功没有；note = 补充说明，比如撤销时的冲突）。 */
    fun resolve(accepted: Boolean, note: String?) {
        val (plan, results) = pending ?: return
        pending = null
        for (r in results) {
            val msg = when {
                !r.ok -> r.message
                r.name == AiTools.OPEN_PAGE -> r.message
                accepted -> "applied: the user confirmed. " + r.message.removePrefix("proposed (waiting for the user to confirm): ")
                else -> "cancelled: the user rejected this change; nothing was changed"
            }
            history += AiMessage(AiRole.TOOL, msg + (note?.let { " ($it)" } ?: ""), toolCallId = r.callId)
        }
    }

    /** 对话里附了课表截图：识别结果记进历史（模型以后问到能接着聊）。 */
    fun noteRecognition(n: Int, names: List<String>) {
        if (pending != null) resolve(false, null)
        history += AiMessage(AiRole.USER, "[I attached a timetable screenshot]")
        history += AiMessage(AiRole.ASSISTANT, "Recognised $n class meetings (${names.distinct().take(20).joinToString()}); the app opened its import preview so the user can choose replace or append.")
    }

    /** 撤销了上一批：告诉模型。 */
    fun noteUndo() { history += AiMessage(AiRole.USER, "[I undid the last applied changes]"); history += AiMessage(AiRole.ASSISTANT, "OK, the last changes were undone.") }
}
