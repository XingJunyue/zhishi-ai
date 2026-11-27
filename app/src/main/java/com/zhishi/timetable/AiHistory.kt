package com.zhishi.timetable

import org.json.JSONArray
import org.json.JSONObject

/*
 * AI 对话记录（纯 Kotlin，不碰 Android，有单测 AiHistoryTest）：
 *  - 每段对话存两份东西：给人看的条目（SavedItem：用户 / AI / 确认卡片 / 出错 / 提示）和给模型的历史（AiMessage，去掉图片）；
 *  - 确认卡片只存改动的前后对比和最后的状态，不存能再应用的数据：打开旧对话时卡片只显示「已确认 / 已取消 / 已撤销」，不会再写一次；
 *    存的时候还没确认的卡片，读回来算「已取消」，模型历史里没有结果的工具调用补一条「已取消」（OpenAI 格式要求每个 tool_call 后面都有结果）；
 *  - 回复某一条消息（引用）：界面上带着引用，发给模型的那句话前面加一段「用户在回复这条消息：…」（quoteContext）。
 * Android 那边的读写在 AiChatStore（AiSheet.kt），存在 App 私有目录的 ai_chats.json。
 */

/** 引用：回复对话里的哪一条（fromUser = 引用的是用户自己发的）。 */
data class AiQuote(val fromUser: Boolean, val text: String)

/** 存下来的确认卡片：只有给人看的改动和最后的状态。 */
data class SavedCard(val changes: List<AiChange>, val failed: Int, val state: Int, val undone: Set<Int> = emptySet())

/** 对话里的一项（存储用）。 */
data class SavedItem(val kind: String, val text: String = "", val quote: AiQuote? = null, val images: Int = 0, val card: SavedCard? = null, val retry: Boolean = false)

/** 一段对话。 */
data class AiConvo(val id: String, val created: Long, val updated: Long, val title: String, val items: List<SavedItem>, val history: List<AiMessage>) {
    /** 消息条数：用户和 AI 的话、确认卡片（不算出错和提示）。 */
    val count get() = items.count { it.kind == AiConvos.USER || it.kind == AiConvos.BOT || it.kind == AiConvos.CARD }
}

object AiConvos {
    const val USER = "user"; const val BOT = "bot"; const val CARD = "card"; const val ERROR = "error"; const val INFO = "info"
    /** 卡片状态（和界面 ChatItem 的一样）。 */
    const val PENDING = 0; const val APPLIED = 1; const val CANCELLED = 2; const val UNDONE = 3
    /** 撤销了其中几项（undone 记着是哪几项）。2026-11 起 AI 的改动马上生效，卡片一开始就是 APPLIED；PENDING / CANCELLED 只出现在老对话里。 */
    const val PARTIAL = 4
    /** 最多存多少段对话、每段多少条、模型历史多少条。 */
    const val MAX = 60; const val MAX_ITEMS = 400; const val MAX_HISTORY = 80
    /** 引用里最多带多少字给模型。 */
    const val QUOTE_MAX = 600

    /** 标题：第一句用户的话（一行，最多 40 字）。 */
    fun title(items: List<SavedItem>): String {
        val t = items.firstOrNull { it.kind == USER && it.text.isNotBlank() }?.text ?: return ""
        val one = t.replace(Regex("\\s+"), " ").trim()
        return if (one.length > 40) one.take(40).trimEnd() + "…" else one
    }

    /** 引用的那条拿给界面和模型看的文字：确认卡片 = 改动标题。 */
    fun quoteText(it: SavedItem): String = when (it.kind) {
        CARD -> it.card?.changes?.joinToString("；") { ch -> ch.title } ?: ""
        else -> it.text
    }

    /** 发给模型的那句话：前面带上引用的消息（用户在回复哪一条）。 */
    fun quoteContext(text: String, q: AiQuote?, lang: String): String {
        if (q == null || q.text.isBlank()) return text
        val t = q.text.trim().let { if (it.length > QUOTE_MAX) it.take(QUOTE_MAX).trimEnd() + "…" else it }
        return if (lang == "zh")
            "[用户在回复这条消息（" + (if (q.fromUser) "用户自己之前发的" else "你之前的回复") + "）：「" + t + "」]\n" + text
        else
            "[The user is replying to this message (" + (if (q.fromUser) "their own earlier message" else "your earlier reply") + "): \"" + t + "\"]\n" + text
    }

    /**
     * 读回来的模型历史整理成能直接接着发的：
     * 每个 tool_call 后面都有结果（没有的补「已取消」），没有对应调用的工具结果丢掉，太长的只留最近的、并从一条用户消息开始。
     */
    fun repair(h: List<AiMessage>): List<AiMessage> {
        val out = ArrayList<AiMessage>()
        var open = LinkedHashSet<String>()
        fun close() { for (id in open) out += AiMessage(AiRole.TOOL, CANCELLED_NOTE, toolCallId = id); open = LinkedHashSet() }
        for (m in h) {
            when {
                m.role == AiRole.SYSTEM -> {}
                m.role == AiRole.TOOL -> if (m.toolCallId != null && open.remove(m.toolCallId)) out += m
                else -> {
                    close()
                    out += m.copy(images = emptyList())
                    if (m.role == AiRole.ASSISTANT) m.toolCalls.forEach { open += it.id }
                }
            }
        }
        close()
        if (out.size <= MAX_HISTORY) return out
        var k = out.size - MAX_HISTORY
        while (k < out.size && out[k].role != AiRole.USER) k++
        return out.subList(k, out.size).toList()
    }
    const val CANCELLED_NOTE = "cancelled: the conversation was closed before the user confirmed; nothing was changed"

    /** 存的时候还没确认的卡片 = 已取消（读回来不会再应用）。 */
    fun restoredState(st: Int) = if (st == PENDING) CANCELLED else st

    // —— JSON ——
    private fun quoteJson(q: AiQuote?) = q?.let { JSONObject().put("u", it.fromUser).put("t", it.text) }
    private fun quoteOf(j: JSONObject?) = j?.let { AiQuote(it.optBoolean("u"), it.optString("t")) }

    fun itemJson(i: SavedItem): JSONObject = JSONObject().put("k", i.kind).also { j ->
        if (i.text.isNotEmpty()) j.put("t", i.text)
        quoteJson(i.quote)?.let { j.put("q", it) }
        if (i.images > 0) j.put("im", i.images)
        if (i.retry) j.put("r", true)
        i.card?.let { c ->
            if (c.undone.isNotEmpty()) j.put("ud", JSONArray(c.undone.sorted()))
            j.put("st", c.state).put("f", c.failed).put("ch", JSONArray().also { a -> c.changes.forEach { ch ->
                a.put(JSONObject().put("t", ch.title).put("l", JSONArray().also { la -> ch.lines.forEach { l ->
                    la.put(JSONObject().put("n", l.label).also { o -> l.before?.let { o.put("b", it) }; l.after?.let { o.put("a", it) } }) } }))
            } })
        }
    }

    fun itemOf(j: JSONObject): SavedItem? {
        val k = j.optString("k"); if (k !in listOf(USER, BOT, CARD, ERROR, INFO)) return null
        val card = if (k == CARD) {
            val ch = j.optJSONArray("ch") ?: JSONArray()
            SavedCard((0 until ch.length()).mapNotNull { ch.optJSONObject(it) }.map { o ->
                val la = o.optJSONArray("l") ?: JSONArray()
                AiChange(o.optString("t"), (0 until la.length()).mapNotNull { la.optJSONObject(it) }.map { l ->
                    DiffLine(l.optString("n"), if (l.has("b")) l.optString("b") else null, if (l.has("a")) l.optString("a") else null) })
            }, j.optInt("f"), j.optInt("st"), j.optJSONArray("ud")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() } ?: emptySet())
        } else null
        return SavedItem(k, j.optString("t"), quoteOf(j.optJSONObject("q")), j.optInt("im"), card, j.optBoolean("r"))
    }

    fun msgJson(m: AiMessage): JSONObject = JSONObject().put("r", m.role.name).also { j ->
        m.text?.let { j.put("t", it) }
        m.toolCallId?.let { j.put("id", it) }
        if (m.toolCalls.isNotEmpty()) j.put("tc", JSONArray().also { a -> m.toolCalls.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("a", it.arguments)) } })
    }

    fun msgOf(j: JSONObject): AiMessage? {
        val role = try { AiRole.valueOf(j.optString("r")) } catch (e: Exception) { return null }
        val tc = j.optJSONArray("tc")
        return AiMessage(role, if (j.has("t")) j.optString("t") else null,
            toolCalls = if (tc == null) emptyList() else (0 until tc.length()).mapNotNull { tc.optJSONObject(it) }.map { AiToolCall(it.optString("id"), it.optString("n"), it.optString("a")) },
            toolCallId = if (j.has("id")) j.optString("id") else null)
    }

    fun toJson(c: AiConvo): JSONObject = JSONObject().put("id", c.id).put("c", c.created).put("u", c.updated).put("title", c.title)
        .put("items", JSONArray().also { a -> c.items.takeLast(MAX_ITEMS).forEach { a.put(itemJson(it)) } })
        .put("h", JSONArray().also { a -> repair(c.history).forEach { a.put(msgJson(it)) } })

    fun fromJson(j: JSONObject): AiConvo? {
        val id = j.optString("id"); if (id.isEmpty()) return null
        val ia = j.optJSONArray("items") ?: JSONArray(); val ha = j.optJSONArray("h") ?: JSONArray()
        val items = (0 until ia.length()).mapNotNull { ia.optJSONObject(it)?.let(::itemOf) }
        if (items.none { it.kind == USER }) return null
        val h = (0 until ha.length()).mapNotNull { ha.optJSONObject(it)?.let(::msgOf) }
        return AiConvo(id, j.optLong("c"), j.optLong("u"), j.optString("title").ifBlank { title(items) }, items, repair(h))
    }

    fun listJson(l: List<AiConvo>): String = JSONObject().put("v", 1).put("chats", JSONArray().also { a -> l.forEach { a.put(toJson(it)) } }).toString()

    /** 读整个文件；坏数据、读不懂的条目跳过，不抛异常。 */
    fun parseList(s: String?): List<AiConvo> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONObject(s).optJSONArray("chats") ?: return emptyList()
            sorted((0 until a.length()).mapNotNull { i -> try { a.optJSONObject(i)?.let(::fromJson) } catch (e: Exception) { null } })
        } catch (e: Exception) { emptyList() }
    }

    private fun sorted(l: List<AiConvo>) = l.distinctBy { it.id }.sortedByDescending { it.updated }.take(MAX)

    /** 新增或更新一段（同 id 替换），最新的在前，最多 MAX 段。 */
    fun upsert(l: List<AiConvo>, c: AiConvo): List<AiConvo> = sorted(listOf(c) + l.filter { it.id != c.id })
    fun delete(l: List<AiConvo>, id: String): List<AiConvo> = l.filter { it.id != id }
}
