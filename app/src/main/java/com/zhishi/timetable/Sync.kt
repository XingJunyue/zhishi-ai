package com.zhishi.timetable

import org.json.JSONArray
import org.json.JSONObject

/*
 * 云同步（给 MCP 智能体用）的纯逻辑，不碰 Android，有单测 SyncTest。协议见 zhishi-cloud 的 docs/API.md：
 *  - 每样东西是一条 item：course / todo / settings(term、periods、profile)，data 用 Kotlin 属性名（不是 Slot.json() 的短键）；
 *  - 本机给每条记一个 updatedAt（侧表 SyncLedger.entries，不改备份格式）+ 改过还没推上去的放进 outbox；删掉的变成墓碑（deleted）推上去；
 *  - 不在每个改动的地方打点：存储每次写完都 scan 一遍「现在的数据」和侧表里记的指纹比，新的 / 变了的 / 不见了的才记时间、进 outbox，
 *    所以手动改、AI 改、撤销、备份恢复、拖动排序全都自动算上；
 *  - 推：POST /v1/sync（带 since 顺便拉），服务器按 updated_at 后写的赢；被拒的（服务器那份更新）直接换成服务器的；
 *  - 拉：比本机这条新的才收下；本机还没推上去、更新的那条留着，推上去会赢。
 */

/** 一条同步记录（服务器的 item）。 */
data class SyncItem(val kind: String, val id: String, val updatedAt: Long, val deleted: Boolean, val data: JSONObject,
                    val version: Long = 0L, val source: String = "app") {
    val key get() = SyncData.key(kind, id)
    val byAgent get() = source.startsWith("agent:")
    fun json(): JSONObject = JSONObject().put("kind", kind).put("id", id).put("updated_at", updatedAt).put("deleted", deleted).put("data", data)
    companion object {
        fun of(j: JSONObject?): SyncItem? {
            j ?: return null
            val k = j.optString("kind"); val id = j.optString("id")
            if (k.isEmpty() || id.isEmpty()) return null
            val del = j.opt("deleted").let { it == true || it == 1 || it == "true" }
            return SyncItem(k, id, j.optLong("updated_at"), del, j.optJSONObject("data") ?: JSONObject(), j.optLong("version"), j.optString("source", "app"))
        }
        fun list(a: JSONArray?): List<SyncItem> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { of(a.optJSONObject(it)) }
    }
}

/** 本机数据 ↔ 同步的 data（字段名和 API.md 一致）。 */
object SyncData {
    const val COURSE = "course"; const val TODO = "todo"; const val SETTINGS = "settings"
    const val TERM = "term"; const val PERIODS = "periods"; const val PROFILE = "profile"
    fun key(kind: String, id: String) = "$kind:$id"
    fun kindOf(key: String) = key.substringBefore(':')
    fun idOf(key: String) = key.substringAfter(':')

    private fun ints(a: JSONArray?, r: IntRange): Set<Int> =
        if (a == null) emptySet() else (0 until a.length()).mapNotNull { a.opt(it).toString().toDoubleOrNull()?.toInt()?.takeIf { v -> v in r } }.toSet()
    private fun intList(a: JSONArray?): List<Int>? =
        if (a == null) null else (0 until a.length()).map { a.opt(it).toString().toDoubleOrNull()?.toInt() ?: return null }

    fun course(s: Slot): JSONObject = JSONObject().put("id", s.id).put("name", s.name).put("days", JSONArray(s.days.sorted())).put("from", s.from).put("to", s.to)
        .put("level", s.level).put("ssid", s.ssid).put("kind", "course").put("room", s.room).put("teacher", s.teacher)
        .put("weeks", JSONArray(s.weeks.sorted())).put("color", s.color).put("note", s.note).put("credit", s.credit)
    fun slot(id: String, d: JSONObject): Slot = Slot(id, d.optString("name"), ints(d.optJSONArray("days"), 1..7),
        d.optInt("from").coerceIn(0, 1439), d.optInt("to").coerceIn(0, 1439), d.optInt("level", 1).coerceIn(0, 2), d.optString("ssid", ""), "course",
        d.optString("room", ""), d.optString("teacher", ""), ints(d.optJSONArray("weeks"), 1..60), d.optInt("color", -1), d.optString("note", ""), d.optString("credit", ""))

    fun todo(t: Todo): JSONObject = JSONObject().put("id", t.id).put("title", t.title).put("note", t.note).put("due", t.due).put("allDay", t.allDay)
        .put("remind", t.remind).put("before", t.before).put("course", t.course).put("done", t.done).put("doneAt", t.doneAt)
        .put("created", t.created).put("pin", t.pin).put("order", t.order)
    fun todoOf(id: String, d: JSONObject): Todo = Todo(id, d.optString("title"), d.optString("note", ""), d.optLong("due", -1L), d.optBoolean("remind", false),
        d.optString("course", ""), d.optBoolean("done", false), d.optLong("doneAt", 0L), d.optLong("created", 0L), d.optBoolean("allDay", false),
        d.optInt("before", 0).coerceIn(0, 7 * 1440), d.optBoolean("pin", false), d.optInt("order", 0).coerceAtLeast(0))

    fun term(start: Long, weeks: Int): JSONObject = JSONObject().put("termStart", start).put("totalWeeks", weeks)
    fun periods(starts: List<Int>, ends: List<Int>, len: Int): JSONObject = JSONObject().put("secStarts", JSONArray(starts)).put("secEnds", JSONArray(ends)).put("secLen", len)
    fun profile(tz: String): JSONObject = JSONObject().put("tz", tz)

    /** 指纹：同一份数据（本机按固定顺序生成）得到同一个值。 */
    fun hash(d: JSONObject): Int = d.toString().hashCode()

    /** 拉下来的设置项（profile 不写进手机：手机自己的时区为准）规整成本机会生成的样子，用来记指纹。 */
    fun canonProfile(d: JSONObject): JSONObject = profile(d.optString("tz"))

    /** periods 读出来：三样都在、数量对得上才算数。 */
    fun periodsOf(d: JSONObject): Triple<List<Int>, List<Int>, Int>? {
        val st = intList(d.optJSONArray("secStarts"))?.takeIf { l -> l.isNotEmpty() && l.all { it in 0..1439 } } ?: return null
        val en = intList(d.optJSONArray("secEnds"))?.takeIf { it.size == st.size } ?: st.map { it + d.optInt("secLen", 45) }
        return Triple(st, en.map { it.coerceIn(0, 1439) }, d.optInt("secLen", 45).coerceIn(5, 300))
    }
}

/** 同步关心的那部分本机数据（一份快照）。 */
data class SyncLocal(
    val courses: List<Slot>, val todos: List<Todo>,
    val termStart: Long, val totalWeeks: Int,
    val secStarts: List<Int>, val secEnds: List<Int>, val secLen: Int,
    val tz: String,
) {
    /** key → data（课、待办按列表顺序，最后三项设置）。同一个 id 出现两次只算第一条。 */
    fun snapshot(): LinkedHashMap<String, JSONObject> {
        val m = LinkedHashMap<String, JSONObject>()
        for (s in courses) if (s.kind == "course") m.putIfAbsent(SyncData.key(SyncData.COURSE, s.id), SyncData.course(s))
        for (t in todos) m.putIfAbsent(SyncData.key(SyncData.TODO, t.id), SyncData.todo(t))
        m[SyncData.key(SyncData.SETTINGS, SyncData.TERM)] = SyncData.term(termStart, totalWeeks)
        m[SyncData.key(SyncData.SETTINGS, SyncData.PERIODS)] = SyncData.periods(secStarts, secEnds, secLen)
        m[SyncData.key(SyncData.SETTINGS, SyncData.PROFILE)] = SyncData.profile(tz)
        return m
    }

    /** 把收下的几条写进快照：有就原位替换，没有就加在最后；删除就拿掉。profile 不改手机。标题为空的待办不收（本机存不下）。 */
    fun with(items: List<SyncItem>): SyncLocal {
        var cs = courses; var ts = todos; var r = this
        for (it in items) when (it.kind) {
            SyncData.COURSE -> cs = upsert(cs, it.id, if (it.deleted) null else SyncData.slot(it.id, it.data)) { s -> s.id }
            SyncData.TODO -> ts = upsert(ts, it.id, if (it.deleted) null else SyncData.todoOf(it.id, it.data).takeIf { t -> t.title.isNotBlank() }) { t -> t.id }
            SyncData.SETTINGS -> if (!it.deleted) when (it.id) {
                SyncData.TERM -> r = r.copy(termStart = it.data.optLong("termStart", -1L).let { v -> if (v < 0) -1L else v }, totalWeeks = it.data.optInt("totalWeeks", 20).coerceIn(1, 30))
                SyncData.PERIODS -> SyncData.periodsOf(it.data)?.let { (a, b, l) -> r = r.copy(secStarts = a, secEnds = b, secLen = l) }
            }
        }
        return r.copy(courses = cs, todos = ts)
    }

    private fun <T> upsert(l: List<T>, id: String, v: T?, idOf: (T) -> String): List<T> {
        val i = l.indexOfFirst { idOf(it) == id }
        return when {
            v == null -> if (i < 0) l else l.filter { idOf(it) != id }
            i < 0 -> l + v
            else -> l.toMutableList().also { it[i] = v }
        }
    }
}

/**
 * 侧表：每条记录本机的 updatedAt、上次见到的指纹、是不是已删（墓碑）；outbox = 还没推上去的；cursor = 拉到哪个版本了。
 * 不是线程安全的：调用方（CloudSync）加锁、并且在主线程里改。
 */
class SyncLedger {
    data class Entry(val u: Long, val h: Int, val d: Boolean)
    val entries = LinkedHashMap<String, Entry>()
    val outbox = LinkedHashSet<String>()
    var cursor = 0L

    /** 本机时间可能比服务器慢：新的时间至少比这条上次的大 1，保证推上去能赢过刚拉下来的那份。 */
    private fun next(e: Entry?, now: Long) = if (e == null) now else maxOf(now, e.u + 1)

    /** 拿现在的数据和侧表比：新的 / 变了的 / 不见了的记上时间、进 outbox。返回这次记上的 key。 */
    fun scan(snap: Map<String, JSONObject>, now: Long): Set<String> {
        val changed = LinkedHashSet<String>()
        for ((k, d) in snap) {
            val h = SyncData.hash(d); val e = entries[k]
            if (e == null || e.d || e.h != h) { entries[k] = Entry(next(e, now), h, false); outbox += k; changed += k }
        }
        for ((k, e) in entries.entries.toList()) if (!e.d && k !in snap && SyncData.kindOf(k) != SyncData.SETTINGS) {
            entries[k] = Entry(next(e, now), 0, true); outbox += k; changed += k
        }
        return changed
    }

    /** 第一次打开同步：全部当成刚改的（时间 = now），整份推上去。 */
    fun snapshotAll(snap: Map<String, JSONObject>, now: Long) {
        entries.clear(); outbox.clear(); cursor = 0L
        for ((k, d) in snap) { entries[k] = Entry(now, SyncData.hash(d), false); outbox += k }
    }

    /** outbox 里的变成要推的记录（不见了的 = 墓碑）。 */
    fun pending(snap: Map<String, JSONObject>): List<SyncItem> = outbox.mapNotNull { k ->
        val e = entries[k] ?: return@mapNotNull null
        val d = snap[k]
        SyncItem(SyncData.kindOf(k), SyncData.idOf(k), e.u, d == null, d ?: JSONObject())
    }

    /** 推完：没被拒、推的时候到现在又没改过的，从 outbox 拿掉。 */
    fun pushed(sent: List<SyncItem>, rejected: List<SyncItem>) {
        val rej = rejected.map { it.key }.toSet()
        for (s in sent) { val e = entries[s.key]; if (s.key !in rej && e != null && e.u == s.updatedAt) outbox.remove(s.key) }
    }

    /** 拉下来的（force = 推的时候被拒、服务器那份更新的）：比本机新的才收下，从 outbox 拿掉。返回收下的（同一个 key 只留最后一条）。 */
    fun incoming(items: List<SyncItem>, force: Boolean): List<SyncItem> {
        val out = LinkedHashMap<String, SyncItem>()
        for (it in items) {
            val e = entries[it.key]
            val cur = out[it.key]?.updatedAt ?: e?.u
            if (!force && cur != null && it.updatedAt <= cur) continue
            out.remove(it.key); out[it.key] = it; outbox.remove(it.key)
        }
        return out.values.toList()
    }

    /** 收下的写进本机以后：按写完的数据记指纹和服务器的时间（profile 记服务器那份的指纹：和手机时区不一样时下次会推上去）。 */
    fun settle(snap: Map<String, JSONObject>, taken: List<SyncItem>) {
        for (it in taken) {
            val h = when {
                it.deleted -> 0
                it.kind == SyncData.SETTINGS && it.id == SyncData.PROFILE -> SyncData.hash(SyncData.canonProfile(it.data))
                else -> snap[it.key]?.let { d -> SyncData.hash(d) } ?: 0
            }
            entries[it.key] = Entry(it.updatedAt, h, it.deleted || snap[it.key] == null && it.kind != SyncData.SETTINGS)
        }
    }

    fun toJson(): JSONObject = JSONObject().put("c", cursor)
        .put("e", JSONObject().also { o -> entries.forEach { (k, e) -> o.put(k, JSONArray().put(e.u).put(e.h).put(if (e.d) 1 else 0)) } })
        .put("o", JSONArray(outbox.toList()))

    companion object {
        fun of(s: String?): SyncLedger {
            val l = SyncLedger()
            if (s.isNullOrBlank()) return l
            try {
                val j = JSONObject(s)
                l.cursor = j.optLong("c")
                j.optJSONObject("e")?.let { o -> o.keys().forEach { k -> o.optJSONArray(k)?.let { a -> l.entries[k] = Entry(a.optLong(0), a.optInt(1), a.optInt(2) == 1) } } }
                j.optJSONArray("o")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let { l.outbox += it } }
            } catch (x: Exception) {}
            return l
        }
    }
}

/** 推的回复 / 拉的一页。 */
class SyncPull(val changes: List<SyncItem>, val next: Long, val more: Boolean)
class SyncPush(val rejected: List<SyncItem>, val pull: SyncPull?)

/** 网络那一层（安卓那边用 HttpURLConnection；单测换成假的服务器）。 */
interface SyncNet {
    fun push(changes: List<SyncItem>, since: Long?): SyncPush
    fun pull(since: Long): SyncPull
}

/** 本机存储那一层：读出快照、把新快照写回去（只写变了的部分；写的时候不要再 scan）。 */
interface SyncStore {
    fun read(): SyncLocal
    fun write(old: SyncLocal, new: SyncLocal, taken: List<SyncItem>)
}

object SyncEngine {
    const val MAX_PUSH = 500
    /** 一次同步的结果：推了几条、被拒几条、收下了哪些（含智能体改的）。 */
    class Outcome(val pushed: Int, val rejected: Int, val taken: List<SyncItem>) { val byAgent get() = taken.filter { it.byAgent } }

    /**
     * 跑一次：scan → 推 outbox（分批，最后一批带 since 顺便拉）→ 被拒的换成服务器的 → 拉到底，比本机新的写进本机。
     * locked：改侧表和写存储的那几段在里面跑（安卓那边 = 主线程 + 锁）；网络在调用线程。
     */
    fun run(ledger: SyncLedger, store: SyncStore, net: SyncNet, now: () -> Long, locked: (() -> Unit) -> Unit = { it() }): Outcome {
        var sent: List<SyncItem> = emptyList()
        locked { val snap = store.read().snapshot(); ledger.scan(snap, now()); sent = ledger.pending(snap) }
        val taken = ArrayList<SyncItem>()
        var rejected = 0
        fun take(items: List<SyncItem>, force: Boolean) {
            if (items.isEmpty()) return
            locked {
                val t = ledger.incoming(items, force)
                if (t.isNotEmpty()) {
                    val old = store.read(); val new = old.with(t)
                    store.write(old, new, t)
                    ledger.settle(store.read().snapshot(), t)
                    taken += t
                }
            }
        }
        var pull: SyncPull? = null
        if (sent.isEmpty()) pull = net.pull(ledger.cursor)
        else {
            val chunks = sent.chunked(MAX_PUSH)
            for ((i, ch) in chunks.withIndex()) {
                val r = net.push(ch, if (i == chunks.lastIndex) ledger.cursor else null)
                locked { ledger.pushed(ch, r.rejected) }
                rejected += r.rejected.size
                take(r.rejected, true)
                if (r.pull != null) pull = r.pull
            }
            if (pull == null) pull = net.pull(ledger.cursor)
        }
        var guard = 0
        while (pull != null && guard++ < 200) {
            val p: SyncPull = pull
            take(p.changes, false)
            locked { ledger.cursor = maxOf(ledger.cursor, p.next) }
            pull = if (p.more) net.pull(ledger.cursor) else null
        }
        return Outcome(sent.size, rejected, taken)
    }
}
