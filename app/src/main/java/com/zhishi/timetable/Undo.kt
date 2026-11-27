package com.zhishi.timetable

/*
 * 「自定义撤回」的纯逻辑（不碰 Android，单测 UndoTest）：
 *  - AI 助手一轮里的改动马上生效，事后一次弹出「已改好 N 处 · 撤销」；点开是每一项改动（前 → 后）的勾选列表，可以只撤销其中几项；
 *  - 每项改动记下它动过的「东西」（key：course:<id>、todo:<id>、设置块 part:TERM 等）改之前 / 之后的值；
 *  - 依赖：同一样东西后面又被改过，撤销前面那项就得连后面的一起撤（比如先加一门课、再改它的教室：撤销「加课」= 连「改教室」一起撤）；
 *    勾上一项会自动勾上后面动过同一样东西的，取消一项会自动取消前面动过同一样东西的；
 *  - 撤销 = 把这样东西恢复成「最早被撤销的那一项」之前的样子；撤销之后它又被别处改过（现在的值不是我们留下的值）就不动，记成冲突；
 *  - 智能体（MCP）的改动也用同一个列表和同一套依赖规则，撤销走服务器的 agent-log undo。
 */

/** 撤销列表里的一行。keys = 它动过的东西；undone = 已经撤销过；ref = 智能体改动的 agent-log seq（AI 助手的为 0）。 */
data class UndoEntry(val change: AiChange, val keys: Set<String>, val undone: Boolean = false, val ref: Long = 0L, val at: Long = 0L, val who: String = "")

object UndoDeps {
    private fun share(a: UndoEntry, b: UndoEntry) = a.keys.any { it in b.keys }

    /** 点了第 i 项：on = 勾上（连同后面动过同一样东西的，一路传下去），否则取消（连同前面动过同一样东西的）。已撤销的不能勾。 */
    fun toggle(e: List<UndoEntry>, sel: Set<Int>, i: Int, on: Boolean): Set<Int> {
        if (i !in e.indices || e[i].undone) return sel
        val out = sel.toMutableSet()
        val seen = HashSet<Int>()
        val todo = ArrayDeque(listOf(i))
        while (todo.isNotEmpty()) {
            val k = todo.removeFirst()
            if (!seen.add(k) || e[k].undone) continue
            if (on) out += k else out -= k
            val range = if (on) (k + 1 until e.size) else (0 until k)
            for (j in range) if (share(e[j], e[k])) todo += j
        }
        return out
    }

    /** 让一个勾选集合满足依赖（只会多勾，不会少勾）：用于「全部撤销」以外直接给出的集合。 */
    fun close(e: List<UndoEntry>, sel: Set<Int>): Set<Int> = sel.fold(sel) { acc, i -> toggle(e, acc, i, true) }

    /** 没撤销的全部。 */
    fun all(e: List<UndoEntry>): Set<Int> = e.indices.filter { !e[it].undone }.toSet()
}

/** AI 改的一项：改动说明 + 它动过的每样东西改之前 / 之后的值（Slot / Todo / 设置块的值，null = 不存在）。 */
class AiEdit(val change: AiChange, val before: Map<String, Any?>, val after: Map<String, Any?>) {
    val keys: Set<String> get() = before.keys
}

/** 应用状态按「一样一样的东西」读写。 */
object Entities {
    private val PARTS = StatePart.values().filter { it != StatePart.COURSES && it != StatePart.TODOS }
    fun courseKey(id: String) = "course:$id"
    fun todoKey(id: String) = "todo:$id"
    fun partKey(p: StatePart) = "part:" + p.name

    /** 两份状态之间变了的东西。 */
    fun diffKeys(a: AppState, b: AppState): Set<String> {
        val out = LinkedHashSet<String>()
        val ca = a.courses.associateBy { it.id }; val cb = b.courses.associateBy { it.id }
        for (id in (ca.keys + cb.keys)) if (ca[id] != cb[id]) out += courseKey(id)
        val ta = a.todos.associateBy { it.id }; val tb = b.todos.associateBy { it.id }
        for (id in (ta.keys + tb.keys)) if (ta[id] != tb[id]) out += todoKey(id)
        for (p in PARTS) if (AiState.part(a, p) != AiState.part(b, p)) out += partKey(p)
        return out
    }

    fun get(s: AppState, key: String): Any? = when {
        key.startsWith("course:") -> key.removePrefix("course:").let { id -> s.courses.firstOrNull { it.id == id } }
        key.startsWith("todo:") -> key.removePrefix("todo:").let { id -> s.todos.firstOrNull { it.id == id } }
        else -> AiState.part(s, StatePart.valueOf(key.removePrefix("part:")))
    }

    /** 写回一样东西：v = null 就删掉；原来没有的按 hint（改之前的状态）里的位置插回去。 */
    @Suppress("UNCHECKED_CAST")
    fun set(s: AppState, key: String, v: Any?, hint: AppState? = null): AppState = when {
        key.startsWith("course:") -> s.copy(courses = put(s.courses, key.removePrefix("course:"), v as Slot?, hint?.courses) { it.id })
        key.startsWith("todo:") -> s.copy(todos = put(s.todos, key.removePrefix("todo:"), v as Todo?, hint?.todos) { it.id })
        else -> when (StatePart.valueOf(key.removePrefix("part:"))) {
            StatePart.TERM -> (v as Pair<Long, Int>).let { s.copy(termStart = it.first, totalWeeks = it.second) }
            StatePart.SECS -> (v as Pair<List<Int>, List<Int>>).let { s.copy(secStarts = it.first, secEnds = it.second) }
            StatePart.SKIN -> (v as Pair<String, Int>).let { s.copy(skin = it.first, mode = it.second) }
            StatePart.ROTATION -> (v as Triple<Int, Int, List<String>>).let { s.copy(rotMode = it.first, rotEvery = it.second, favs = it.third) }
            StatePart.WIDGET_COURSE -> s.copy(widgetCourse = v as WidgetLook)
            StatePart.WIDGET_TODO -> s.copy(widgetTodo = v as WidgetLook)
            StatePart.DISPLAY -> (v as List<Boolean>).let { s.copy(showWeekend = it[0], showOff = it[1], showLevel = it[2], widgetLevel = it[3]) }
            else -> s
        }
    }

    private fun <T> put(l: List<T>, id: String, v: T?, hint: List<T>?, idOf: (T) -> String): List<T> {
        val i = l.indexOfFirst { idOf(it) == id }
        if (v == null) return if (i < 0) l else l.filterIndexed { k, _ -> k != i }
        if (i >= 0) return l.toMutableList().also { it[i] = v }
        val at = hint?.indexOfFirst { idOf(it) == id } ?: -1
        return if (at < 0 || at >= l.size) l + v else l.toMutableList().also { it.add(at, v) }
    }

    /** 一项改动（a → b）记成 AiEdit。 */
    fun edit(change: AiChange, a: AppState, b: AppState): AiEdit {
        val keys = diffKeys(a, b)
        return AiEdit(change, keys.associateWith { get(a, it) }, keys.associateWith { get(b, it) })
    }

    /** 把一批改动（每项的 after）写到 current 上：只动这批改过的东西，别处这期间的改动不受影响。 */
    fun applyOn(current: AppState, edits: List<AiEdit>, hint: AppState): AppState {
        var s = current
        for (e in edits) for ((k, v) in e.after) s = set(s, k, v, hint)
        return s
    }

    /** 每样东西在这批改动之后应该是什么（最后一项动它的 after）。 */
    fun finalValues(edits: List<AiEdit>): MutableMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        for (e in edits) for ((k, v) in e.after) m[k] = v
        return m
    }

    class UndoOut(val state: AppState, val done: Set<Int>, val conflicts: Set<String>)

    /**
     * 撤销所选（会先按依赖补齐）：每样东西恢复成最早被撤销的那项之前的值。
     * expected = 每样东西现在「应该」是什么（这批改动 / 上次撤销后我们留下的值），撤销成功后会更新它；
     * 现在的值和 expected 不一样（之后又被改过）就不动这样东西，用到它的那几项也不算撤销。
     */
    fun undo(edits: List<AiEdit>, undone: Set<Int>, selected: Set<Int>, current: AppState, hint: AppState, expected: MutableMap<String, Any?>): UndoOut {
        val entries = edits.mapIndexed { i, e -> UndoEntry(e.change, e.keys, i in undone) }
        var sel = UndoDeps.close(entries, selected.filter { it !in undone }.toSet())
        // 之后又被改过的东西不动：动过它的项不撤，前面跟它们有依赖的也不撤
        val conflicts = LinkedHashSet<String>()
        for (i in sel) for (k in edits[i].keys) if (get(current, k) != expected[k]) conflicts += k
        for (i in sel.toList()) if (edits[i].keys.any { it in conflicts }) sel = UndoDeps.toggle(entries, sel, i, false)
        val target = LinkedHashMap<String, Any?>()
        for (i in sel.sorted()) for (k in edits[i].keys) if (k !in target) target[k] = edits[i].before[k]
        var s = current
        for ((k, v) in target) { s = set(s, k, v, hint); expected[k] = v }
        val done = sel
        return UndoOut(s, done, conflicts)
    }
}
