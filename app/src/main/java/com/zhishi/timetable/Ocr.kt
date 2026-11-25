package com.zhishi.timetable

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 课表文字的解析工具（星期、节次、钟点、时间段、课名 / 教室 / 老师 / 周次）。
 * 知时 AI 版去掉了本机 ML Kit 识别（截图识别改由 AI 看图模型做，见 AiRecognize.kt）；
 * 这里只剩纯解析函数：教务 / CSV / 选课清单导入还在用 dayOf、rangeOf、timeOf，parse 保留给单测。
 * 原来的思路：
 * 思路：先找表头「周一…周日 / Mon…Sun / 月…日」定出每一列，再找左边的节次数字定出每一行，
 * 然后把格子里的文字按列归到星期几、按上下位置换算成第几节到第几节。
 * 中文识别器同时认拉丁字母（英法德西）；日文、韩文各用自己的识别器，三个都试，取认出最多的那个。
 */
object Ocr {
    /** 一行字和它在图上的位置。 */
    class R(val left: Int, val top: Int, val right: Int, val bottom: Int)
    class L(val t: String, val r: R, val parts: List<L> = emptyList()) {
        val cx get() = (r.left + r.right) / 2f; val cy get() = (r.top + r.bottom) / 2f
        val w get() = r.right - r.left
    }

    private val DAY_CN = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)
    private val DAY_JA = mapOf('月' to 1, '火' to 2, '水' to 3, '木' to 4, '金' to 5, '土' to 6, '日' to 7)
    private val DAY_KO = mapOf('월' to 1, '화' to 2, '수' to 3, '목' to 4, '금' to 5, '토' to 6, '일' to 7)
    /** 拉丁语系星期名：完整词和常见缩写（英、法、德、西）。 */
    private val DAY_LAT: List<Pair<String, Int>> = listOf(
        "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4, "friday" to 5, "saturday" to 6, "sunday" to 7,
        "lundi" to 1, "mardi" to 2, "mercredi" to 3, "jeudi" to 4, "vendredi" to 5, "samedi" to 6, "dimanche" to 7,
        "montag" to 1, "dienstag" to 2, "mittwoch" to 3, "donnerstag" to 4, "freitag" to 5, "samstag" to 6, "sonntag" to 7, "sonnabend" to 6,
        "lunes" to 1, "martes" to 2, "miércoles" to 3, "miercoles" to 3, "jueves" to 4, "viernes" to 5, "sábado" to 6, "sabado" to 6, "domingo" to 7,
        "mon" to 1, "tue" to 2, "tues" to 2, "wed" to 3, "thu" to 4, "thur" to 4, "thurs" to 4, "fri" to 5, "sat" to 6, "sun" to 7,
        "lun" to 1, "mar" to 2, "mer" to 3, "jeu" to 4, "ven" to 5, "sam" to 6, "dim" to 7,
        "mo" to 1, "di" to 2, "mi" to 3, "do" to 4, "fr" to 5, "sa" to 6, "so" to 7,
        "mié" to 3, "mie" to 3, "jue" to 4, "vie" to 5, "sáb" to 6, "sab" to 6, "dom" to 7,
        "lu" to 1, "ma" to 2, "me" to 3, "je" to 4, "ve" to 5, "ju" to 4, "vi" to 5, "di" to 7
    )
    private val LAT_MAP: Map<String, Int> = LinkedHashMap<String, Int>().apply {
        // 西语 di 不是星期；德语 di = 周二，先放的优先
        for ((k, v) in DAY_LAT) if (k !in this) put(k, v)
    }

    fun dayOf(t0: String): Int? {
        val s = t0.trim().replace(" ", "").trim('(', ')', '（', '）', '[', ']')
        if (s.isEmpty()) return null
        Regex("^(?:周|星期|礼拜|週)([一二三四五六日天])").find(s)?.let { return DAY_CN[it.groupValues[1][0]] }
        // 「一」很细，常被认成横杠
        if (Regex("^(?:周|星期|礼拜)[-—–_~]$").matches(s)) return 1
        if (s.length == 1 && s[0] in DAY_CN && s[0] != '日') return DAY_CN[s[0]]
        // 日文：月曜日 / 月曜 / (月) / 月
        Regex("^([月火水木金土日])(?:曜日?|曜)?$").find(s)?.let { return DAY_JA[it.groupValues[1][0]] }
        // 韩文：월요일 / 월
        Regex("^([월화수목금토일])(?:요일)?$").find(s)?.let { return DAY_KO[it.groupValues[1][0]] }
        // 拉丁字母：取开头的字母部分，整词或缩写，后面可以跟点和日期
        val m = Regex("^([A-Za-zÀ-ÿ]+)\\.?(?:[\\d/.\\-]*)$").find(s) ?: return null
        return LAT_MAP[m.groupValues[1].lowercase()]
    }

    fun secOf(t0: String): Int? {
        val s = t0.trim().replace(" ", "").lowercase()
        if (':' in s || '：' in s || s.length > 12) return null
        Regex("^第?(\\d{1,2})(?:节|節|限|時限|時間目|교시|\\.|st|nd|rd|th|e|ème|ª|º|\\.?stunde|\\.?std\\.?)?$").find(s)?.let {
            return it.groupValues[1].toIntOrNull()?.takeIf { n -> n in 1..16 }
        }
        Regex("^(?:period|p|lesson|cours|clase|stunde|std\\.?|h)(\\d{1,2})$").find(s)?.let { return it.groupValues[1].toIntOrNull()?.takeIf { n -> n in 1..16 } }
        Regex("^第?([一二三四五六七八九十]{1,3})(?:节|節|限)$").find(s)?.let { return cn(it.groupValues[1]) }
        return null
    }
    /** 时间轴上的钟点：8:00、08:00、9am、9 AM、13.00、1 PM → 分钟。 */
    fun timeOf(t0: String): Int? {
        val s = t0.trim().lowercase().replace("：", ":").replace(" ", "").replace(".", ":").replace("a:m:", "am").replace("p:m:", "pm")
        val m = Regex("^(\\d{1,2})(?::(\\d{2}))?(am|pm|a|p|h|noon)?$").find(s) ?: return null
        var h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3].let { if (it == "a") "am" else if (it == "p" || it == "noon") "pm" else it }
        if (m.groupValues[2].isEmpty() && ap.isEmpty()) return null      // 光一个数字是节次，不是钟点
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (h !in 0..23 || mi !in 0..59) return null
        return h * 60 + mi
    }

    /** 格子里写着的时间段：9:00-10:50、9:00am – 10:50am、14.15–15.45。 */
    private val RANGE = Regex("(\\d{1,2}[:.：]\\d{2}\\s*(?:[ap]\\.?m?\\.?)?)\\s*[-~～–—]\\s*(\\d{1,2}[:.：]\\d{2}\\s*(?:[ap]\\.?m?\\.?)?)(?![A-Za-z])", RegexOption.IGNORE_CASE)
    fun rangeOf(t: String): Pair<Int, Int>? {
        val m = RANGE.find(t) ?: return null
        var a = timeOf(m.groupValues[1]) ?: return null
        val bRaw = m.groupValues[2]
        var b = timeOf(bRaw) ?: return null
        // 「1:00-2:50pm」：只有后面写了 pm
        if (Regex("p", RegexOption.IGNORE_CASE).containsMatchIn(bRaw) && !Regex("[ap]", RegexOption.IGNORE_CASE).containsMatchIn(m.groupValues[1]) && a + 12 * 60 < b) a += 12 * 60
        if (b <= a || b - a > 6 * 60) return null
        return a to b
    }

    private fun cn(s: String): Int? {
        val d = "一二三四五六七八九"
        return when {
            s == "十" -> 10
            s.startsWith("十") -> 10 + d.indexOf(s[1]) + 1
            s.length == 1 -> d.indexOf(s[0]) + 1
            else -> null
        }
    }

    /** 纯计算，方便单元测试：sec 把「第 a 到 b 节」换成分钟。 */
    fun parse(all0: List<L>, imgH: Int, sec: (Int, Int) -> Pair<Int, Int>): List<Slot> {
        // 表头被认成一整行（「周一 周二 周三」）时拆回一个个词
        val all = all0.flatMap { l -> if (l.parts.size >= 2 && l.parts.count { dayOf(it.t) != null } >= 2) l.parts else listOf(l) }
        // 表头：每个星期几只留最上面那个；要求它们大致在同一高度
        val cand = all.mapNotNull { l -> dayOf(l.t)?.let { it to l } }
        if (cand.isEmpty()) throw IllegalStateException("no header")
        val rowY = cand.groupBy { (it.second.cy / 40).toInt() }.maxBy { e -> e.value.map { it.first }.toSet().size }.value.first().second.cy
        val heads = cand.filter { abs(it.second.cy - rowY) < 60 }.groupBy { it.first }.mapValues { e -> e.value.minBy { it.second.r.top }.second }
        if (heads.size < 3) throw IllegalStateException("no header")
        // 有的表头字没认出来：按已认出的几列等距补齐
        val found = heads.mapValues { it.value.cx }
        val n = found.size.toFloat()
        val md = found.keys.sum() / n; val mx = found.values.sum() / n
        val slope = found.entries.sumOf { ((it.key - md) * (it.value - mx)).toDouble() } / found.keys.sumOf { ((it - md) * (it - md)).toDouble() }
        val lastDay = if ((found.keys.maxOrNull() ?: 5) >= 6) 7 else 5
        val hx = (1..lastDay).associateWith { d -> found[d] ?: (mx + slope * (d - md)).toFloat() }
        val sortedX = hx.values.sorted()
        val colW = (sortedX.last() - sortedX.first()) / (sortedX.size - 1).coerceAtLeast(1)
        val headBottom = heads.values.maxOf { it.r.bottom }
        val leftEdge = sortedX.first() - colW * 0.6f

        // 节次：表格左侧的 1、2、3…
        val secs = all.filter { it.cx < leftEdge && it.r.top > headBottom - 4 }.mapNotNull { l -> secOf(l.t)?.let { it to l.cy } }
            .groupBy { it.first }.mapValues { e -> e.value.minBy { it.second }.second }
        // 拟合 y = a + b * 节次
        val a: Float; val b: Float
        if (secs.size >= 3) {
            val xs = secs.keys.map { it.toFloat() }; val ys = secs.values.toList()
            val mxs = xs.average().toFloat(); val my = ys.average().toFloat()
            val sxy = xs.indices.sumOf { ((xs[it] - mxs) * (ys[it] - my)).toDouble() }.toFloat()
            val sxx = xs.sumOf { ((it - mxs) * (it - mxs)).toDouble() }.toFloat()
            b = if (sxx > 0) sxy / sxx else (imgH - headBottom) / 12f
            a = my - b * mxs
        } else {
            b = (imgH - headBottom) / 12f
            a = headBottom - b / 2f
        }
        if (b <= 2f && secs.size >= 3) throw IllegalStateException("bad row height")
        // 国外的课表左边是钟点（8:00、9 AM…）而不是节次：按钟点拟合 y = ta + tb * 分钟
        val clocks = all.filter { it.cx < leftEdge && it.r.top > headBottom - 4 }.mapNotNull { l -> timeOf(l.t)?.let { it to l.cy } }
            .groupBy { it.first }.mapValues { e -> e.value.minBy { it.second }.second }
        val byClock = clocks.size >= 3 && clocks.size >= secs.size
        var ta = 0f; var tb = 0f
        if (byClock) {
            val xs = clocks.keys.map { it.toFloat() }; val ys = clocks.values.toList()
            val mxs = xs.average().toFloat(); val my = ys.average().toFloat()
            val sxy = xs.indices.sumOf { ((xs[it] - mxs) * (ys[it] - my)).toDouble() }.toFloat()
            val sxx = xs.sumOf { ((it - mxs) * (it - mxs)).toDouble() }.toFloat()
            tb = if (sxx > 0) sxy / sxx else 0f
            ta = my - tb * mxs
            if (tb <= 0.05f) throw IllegalStateException("bad time axis")
        } else if (b <= 2f) throw IllegalStateException("bad row height")
        // 一格的高度：按节次是一节，按钟点算一小时
        val unit = if (byClock) tb * 60f else b
        fun snap(m: Float) = ((m / 5f).roundToInt() * 5).coerceIn(0, 24 * 60)

        // 一行字横跨两列（相邻两格的字连在一起被认成一行）：拆成单词再分
        val pieces = all.flatMap { l -> if (l.w > colW * 1.15f && l.parts.size >= 2) l.parts else listOf(l) }
        val timeLike = Regex("^[\\d\\s/.\\-~–—:：月日周週주()（）]+$")
        val body = pieces.filter { it.r.top > headBottom - 2 && it.cx > leftEdge && dayOf(it.t) == null && secOf(it.t) == null && it.t.isNotBlank() &&
            (!timeLike.matches(it.t.trim()) || WEEKS.containsMatchIn(it.t) || rangeOf(it.t) != null) && (!byClock || timeOf(it.t) == null) }
        val byDay = HashMap<Int, MutableList<L>>()
        for (l in body) {
            val (d, x) = hx.minBy { abs(it.value - l.cx) }
            if (abs(x - l.cx) > colW * 0.6f) continue
            byDay.getOrPut(d) { ArrayList() } += l
        }
        val maxSec = (secs.keys.maxOrNull() ?: 12).coerceAtLeast(4)
        val out = ArrayList<Slot>()
        for ((d, ls) in byDay) {
            // 同一格里的字：上下挨着的合成一格；同一高度左右并排的也算一格
            val cells = ArrayList<MutableList<L>>()
            for (l in ls.sortedWith(compareBy({ it.r.top }, { it.r.left }))) {
                val last = cells.lastOrNull()
                if (last != null && l.r.top - last.maxOf { it.r.bottom } < unit * 0.35f) last += l else cells += mutableListOf(l)
            }
            val parsed = cells.mapNotNull { cell ->
                val rows0 = cell.groupBy { (it.cy / (unit * 0.3f).coerceAtLeast(8f)).toInt() }.toSortedMap().values
                    .map { r -> r.sortedBy { it.r.left }.joinToString(" ") { it.t } }
                val written = rows0.firstNotNullOfOrNull { rangeOf(it) }
                val rows = rows0.map { r -> RANGE.replace(r, "").trim() }.filter { it.isNotEmpty() }
                val name = nameOf(rows) ?: return@mapNotNull null
                val top = cell.minOf { it.r.top }.toFloat(); val bot = cell.maxOf { it.r.bottom }.toFloat()
                if (byClock || written != null) {
                    // 有写明的时间就用写明的；没有就按格子在时间轴上的位置
                    val f = written?.first ?: snap((top - ta) / tb)
                    val t = written?.second ?: snap((bot - ta) / tb).coerceAtLeast(f + 30)
                    return@mapNotNull Cell(name, -f, -t, extras(rows))
                }
                val s1 = ((top - a) / b + 0.5f).roundToInt().coerceIn(1, maxSec)
                val s2 = ((bot - a) / b - 0.5f).roundToInt().coerceIn(s1, maxSec)
                Cell(name, s1, s2, extras(rows))
            }
            parsed.forEachIndexed { i, (name, s1, s2raw, ex) ->
                if (s1 <= 0) {   // 按钟点：s1、s2 存的是负的分钟
                    // 文字只占格子上半截：没写明结束时间、下一节又没挡着时，至少按 50 分钟算
                    val f = -s1; var t = -s2raw
                    val nx = parsed.getOrNull(i + 1)?.let { if (it.s1 <= 0) -it.s1 else null }
                    if (t - f < 50) t = minOf(f + 50, nx ?: (f + 50))
                    out += Slot("o$d-$f-${name.hashCode()}", name, setOf(d), f, t, room = ex.first, teacher = ex.second, weeks = ex.third)
                    return@forEachIndexed
                }
                // 文字通常只占格子上半截：没有别的课挡着时，按最常见的两节连上
                val next = parsed.getOrNull(i + 1)?.s1 ?: (maxSec + 1)
                val s2 = maxOf(s2raw, minOf(s1 + 1, next - 1, maxSec)).coerceAtLeast(s1)
                val (f, t) = sec(s1, s2)
                out += Slot("o$d-$s1-${name.hashCode()}", name, setOf(d), f, t, room = ex.first, teacher = ex.second, weeks = ex.third)
            }
        }
        // 同一天同名、时间挨着的并成一节
        return out.groupBy { it.name to it.days }.flatMap { (_, l) ->
            val s = l.sortedBy { it.from }; val m = ArrayList<Slot>()
            for (x in s) { val y = m.lastOrNull(); if (y != null && x.from <= y.to + 15) m[m.lastIndex] = y.copy(to = maxOf(y.to, x.to)) else m += x }
            m
        }.sortedWith(compareBy({ it.days.first() }, { it.from }))
    }

    data class Cell(val name: String, val s1: Int, val s2: Int, val ex: Triple<String, String, Set<Int>>)

    private val WEEKS = Regex("(\\d+\\s*[-~～–]\\s*\\d+|\\d+(?:\\s*[,，、]\\s*\\d+)*)\\s*(周|週|주|(?i)weeks?|wk|semaines?|wochen?|semanas?)|[单双單雙]周")

    /** 格子里除课名外的东西：@教室、老师、周次。 */
    fun extras(rows: List<String>): Triple<String, String, Set<Int>> {
        var room = ""; var teacher = ""; var weeks = emptySet<Int>()
        val all = rows.joinToString(" ")
        WEEKS.find(all)?.let { m -> weeks = Slot.parseWeeks(m.value + (if (Regex("[单單]周|\\(单\\)|（单）").containsMatchIn(all)) " 单" else if (Regex("[双雙]周|\\(双\\)|（双）").containsMatchIn(all)) " 双" else "")) }
        val ri = rows.indexOfFirst { val t = it.trim(); t.startsWith("@") || t.startsWith("＠") || (t.indexOf('@') > 0) }
        if (ri >= 0) {
            val t = rows[ri].trim(); val k = t.indexOfAny(charArrayOf('@', '＠'))
            room = t.substring(k + 1).trim().trim('|', '｜', '丨').trim()
            // 教室常被折成两行（「@机」「401-语音室」）：下一行像教室号就接上
            val nx = rows.getOrNull(ri + 1)?.trim()
            var ti = ri + 1
            if (nx != null && room.length <= 3 && Regex("^[A-Za-z]?\\d").containsMatchIn(nx)) { room += nx; ti++ }
            val tc = rows.getOrNull(ti)?.trim()?.trim('|', '｜', '丨')?.trim()
            if (tc != null && tc.length in 2..20 && !tc.any { it.isDigit() } && !WEEKS.containsMatchIn(tc)) teacher = tc
        }
        return Triple(room.take(20), teacher.take(20), weeks)
    }

    /** 教室、老师、周次这些不是课名。 */
    private val NOISE = Regex(
        "\\d+[-~～]\\d+(周|週|주)|第?\\d+(-\\d+)?(节|節|限)|^\\d+(周|週|주)|[A-Za-z]?\\d{3,}|教室|楼|室\\s*\\d*$|義室|老师|教师|单周|双周|\\(|（|" +
            "(?i)\\b(room|rm\\.?|hall|bldg|building|prof\\.?|dr\\.|teacher|weeks?|wk|salle|bât|amphi|raum|hörsaal|gebäude|aula|sala|edificio|semanas?|semaines?|wochen?)\\b|" +
            "講義室|号館|号室|先生|강의실|교실|호관|교수|\\d+\\s*교시|(?i)period\\s*\\d+"
    )

    private val CODE = Regex("^[A-Z]{2,5}[ -]?\\d{3,4}[A-Z]?\\b")

    /** 从格子里的几行字里挑出课名：去掉教室、老师、周次这些。 */
    fun nameOf(ls: List<String>): String? {
        val parts = ArrayList<String>()
        for (raw0 in ls) {
            val raw = raw0.trim().let { r -> val i = r.indexOfAny(charArrayOf('@', '＠')); if (i > 0) r.substring(0, i) else r }
            // 表格竖线常被认成「|」「丨」「l」，粘在课名两头
            var t = raw.trim().trimStart('@', '＠').trim('|', '｜', '丨', '¦', '[', ']', '【', '】', ' ')
            // 课名和教室挤在同一行（如「線形代数 講義室3」）：从教室那里截断
            // 国外常见的课程代码（CS 101、MATH 2410、ECON1001）是课名的一部分，不是教室号
            val code = CODE.find(t)?.value ?: ""
            val rest = t.substring(code.length)
            NOISE.find(rest)?.let { m -> if (code.length + m.range.first >= 2 && !raw0.trim().startsWith("@")) t = t.substring(0, code.length + m.range.first).trim() }
            if (t.isEmpty()) continue
            val noise = raw0.trim().startsWith("@") || raw0.trim().startsWith("＠") || NOISE.containsMatchIn(t.substring(minOf(code.length, t.length)))
            if (noise) { if (parts.isNotEmpty()) break else continue }
            parts += t
            if (parts.size == 5) break
        }
        // 中日韩字直接连上，拉丁字母之间留空格
        val n = parts.fold("") { acc, p ->
            if (acc.isEmpty()) p
            else if (acc.last().isLetter() && acc.last().code < 0x2E80 && p.first().isLetter() && p.first().code < 0x2E80) "$acc $p" else acc + p
        }.replace(Regex("\\s+"), " ").replace(Regex("(?<=[\\u3040-\\u30FF\\u4E00-\\u9FFF]) (?=[\\u3040-\\u30FF\\u4E00-\\u9FFF])"), "").trim()
            // 中日韩课名后面粘着的零碎字母数字（多半是教室号认错）去掉
            .replace(Regex("(?<=[\\uAC00-\\uD7AF])[A-Za-z0-9]{3,5}$"), "").take(30)
        return n.takeIf { it.length >= 2 }
    }
}
