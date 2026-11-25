package com.zhishi.timetable

import org.json.JSONArray
import org.json.JSONObject

/*
 * AI 截图识别：把课表截图发给看图模型，要它只回严格的 JSON，这里校验 / 修补后变成 JwCourse，
 * 交给原来的导入预览（覆盖 / 追加）。纯 Kotlin：解析、校验都有单测，图片的读取压缩在安卓那层（AiAndroid.kt）。
 */
object AiRecognize {
    const val SYSTEM = "You extract class timetables from screenshots. Output ONLY one JSON object, no markdown, no explanations."

    /** 给看图模型的要求（英文写，模型最稳；课名等保持截图原文）。 */
    fun prompt(periods: List<Pair<Int, Int>>): String {
        val table = periods.mapIndexed { i, (a, b) -> "${i + 1}=${AiTools.clock(a)}-${AiTools.clock(b)}" }.joinToString(", ")
        return """Read every class in this timetable screenshot and return JSON exactly in this shape:
{"courses":[{"name":"高等数学","teacher":"张老师","room":"A101","weekday":1,"start_period":1,"end_period":2,"start_time":"","end_time":"","weeks":"1-16","odd_even":"all"}]}
Rules:
- One entry per class meeting (a course on Monday and Thursday = two entries).
- weekday: 1=Monday … 7=Sunday, read from the column header.
- start_period / end_period: the period (节次) numbers from the row labels, inclusive. If the table shows clock times instead of period numbers, fill start_time/end_time as HH:mm and leave periods as 0.
- weeks: teaching weeks exactly as written (e.g. "1-16", "1-8,10-16", "3-17"); empty string if not shown.
- odd_even: "odd" for 单周, "even" for 双周, otherwise "all".
- Keep course names, teachers and rooms in the original language and spelling; use "" when absent. Do not invent classes.
- If a cell lists two different courses (different weeks), output both.
The user's period times are: $table.
If the image is not a timetable, return {"courses":[]}."""
    }

    /** 让模型把坏 JSON 改好的那一句。 */
    const val REPAIR = "Your previous reply was not valid JSON in the required shape. Reply again with ONLY the JSON object {\"courses\":[...]}."

    class Result(val courses: List<JwCourse>, val dropped: Int)

    /** 从模型回复里抠出 JSON（可能带 ``` 围栏、前后有话）。找不到返回 null。 */
    fun extract(text: String): Any? {
        val t = AiTools.repairJson(text)
        for (cand in listOf(t, slice(t, '{', '}'), slice(t, '[', ']'))) {
            if (cand.isNullOrBlank()) continue
            val c = AiTools.repairJson(cand)
            try { return if (c.trimStart().startsWith("[")) JSONArray(c) else JSONObject(c) } catch (x: Exception) {}
        }
        return null
    }
    private fun slice(t: String, open: Char, close: Char): String? {
        val a = t.indexOf(open); val b = t.lastIndexOf(close)
        return if (a >= 0 && b > a) t.substring(a, b + 1) else null
    }

    /**
     * 校验 + 修补 → 课程。periods = 用户的每节上下课时间（只写了钟点的课按它换算成节次）。
     * 不合格的（没课名、星期不对、节次超范围）丢掉并计数；同一节重复的去重。返回 null = 根本不是要的 JSON。
     */
    fun parse(text: String, periods: List<Pair<Int, Int>>, total: Int = 20): Result? {
        val root = extract(text) ?: return null
        val arr: JSONArray = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("courses") ?: root.optJSONArray("classes") ?: root.optJSONArray("data")
                ?: (root.keys().asSequence().map { root.opt(it) }.firstOrNull { it is JSONArray } as? JSONArray) ?: return null
            else -> return null
        }
        val out = LinkedHashMap<String, JwCourse>(); var dropped = 0
        val maxP = maxOf(periods.size, 1)
        for (k in 0 until arr.length()) {
            val j = arr.optJSONObject(k) ?: run { dropped++; null } ?: continue
            val c = try { one(Args(j), periods, maxP, total) } catch (x: Exception) { null }
            if (c == null) { dropped++; continue }
            out.putIfAbsent("${c.name}|${c.day}|${c.start}|${c.end}|${c.weeks}", c)
        }
        return Result(out.values.sortedWith(compareBy({ it.day }, { it.start })), dropped)
    }

    private fun one(a: Args, periods: List<Pair<Int, Int>>, maxP: Int, total: Int): JwCourse? {
        val name = (a.str("name") ?: a.str("course") ?: a.str("course_name") ?: a.str("课程"))?.trim()?.take(30)
        if (name.isNullOrBlank()) return null
        val dayRaw = a.str("weekday") ?: a.str("day") ?: a.str("星期") ?: return null
        val day = AiTools.dayOf(dayRaw) ?: Ocr.dayOf(dayRaw) ?: return null
        var p1 = a.int("start_period") ?: a.int("start") ?: 0
        var p2 = a.int("end_period") ?: a.int("end") ?: 0
        // 「periods": "1-2"」或 [1,2] 这种写法
        if (p1 <= 0) a.str("periods")?.let { v -> Regex("\\d+").findAll(v).map { it.value.toInt() }.toList().let { l -> if (l.isNotEmpty()) { p1 = l.first(); p2 = l.last() } } }
        if (p1 <= 0) {
            // 只给了钟点：就近换成节次
            val f = AiTools.parseClock(a.str("start_time")); val t = AiTools.parseClock(a.str("end_time"))
            if (f == null || periods.isEmpty()) return null
            p1 = periods.indices.minByOrNull { kotlin.math.abs(periods[it].first - f) }!! + 1
            p2 = if (t == null) p1 else (periods.indices.minByOrNull { kotlin.math.abs(periods[it].second - t) }!! + 1)
        }
        if (p2 <= 0) p2 = p1
        if (p2 < p1) { val x = p1; p1 = p2; p2 = x }
        if (p1 < 1 || p1 > maxOf(maxP, 20)) return null
        p2 = p2.coerceAtMost(maxOf(maxP, p1, 20))
        val weeksTxt = a.str("weeks") ?: a.list("weeks")?.joinToString(",")
        val oe = a.str("odd_even") ?: a.str("oddEven")
        var w = AiTools.weeksOf(weeksTxt, oe, total)
        // weeksOf 把「每周」记成空集合；导入预览按空 = 每周显示
        if (w.any { it > 60 }) w = w.filter { it in 1..60 }.toSet()
        return JwCourse(name, day, p1, p2, w, teacher = a.str("teacher").orEmpty().take(30), room = (a.str("room") ?: a.str("location")).orEmpty().take(30))
    }
}
