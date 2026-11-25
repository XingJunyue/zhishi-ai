package com.zhishi.timetable

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

// ---------------- 配色 ----------------

/** 课块配色：浅底 + 同色系深字，对比度够。 */
val COURSE_COLORS = listOf(
    Color(0xFFF9D5DD) to Color(0xFF86283F), Color(0xFFFCE0CC) to Color(0xFF85400F), Color(0xFFFAEAC0) to Color(0xFF72540A),
    Color(0xFFE2EFC6) to Color(0xFF465E14), Color(0xFFD1EDDA) to Color(0xFF215C37), Color(0xFFCBEBE7) to Color(0xFF1B5A55),
    Color(0xFFD2E6F7) to Color(0xFF1C4F7A), Color(0xFFDADFF9) to Color(0xFF303C85), Color(0xFFE5D8F6) to Color(0xFF513085),
    Color(0xFFF2D6EE) to Color(0xFF752A6C), Color(0xFFEDE2D0) to Color(0xFF5A4527), Color(0xFFDCE4EB) to Color(0xFF364858)
)

/** 每门课一个颜色：按课名哈希，撞色时往后挪，同一张课表里尽量不重复。 */
fun coursePalette(courses: List<Slot>): Map<String, Pair<Color, Color>> {
    val out = HashMap<String, Pair<Color, Color>>()
    val used = HashSet<Int>()
    for (n in courses.map { it.name.trim() }.distinct().sorted()) {
        var h = Math.floorMod(n.hashCode(), COURSE_COLORS.size)
        var k = 0
        while (h in used && k < COURSE_COLORS.size) { h = (h + 1) % COURSE_COLORS.size; k++ }
        used += h; if (used.size >= COURSE_COLORS.size) used.clear()
        out[n] = COURSE_COLORS[h]
    }
    return out
}
internal fun colorOf(s: Slot, pal: Map<String, Pair<Color, Color>>) =
    if (s.color in COURSE_COLORS.indices) COURSE_COLORS[s.color] else pal[s.name.trim()] ?: COURSE_COLORS[Math.floorMod(s.name.trim().hashCode(), COURSE_COLORS.size)]

/** 小部件用：这门课的（浅底, 深字）颜色，和课表里一致。 */
fun courseColorArgb(s: Slot, all: List<Slot>): Pair<Int, Int> = colorOf(s, coursePalette(all)).let { it.first.toArgb() to it.second.toArgb() }

private fun md(epochDay: Long): Pair<Int, Int> { val d = LocalDate.ofEpochDay(epochDay); return d.monthValue to d.dayOfMonth }
private fun ymd(epochDay: Long): String { val d = LocalDate.ofEpochDay(epochDay); return "${d.year}/${d.monthValue}/${d.dayOfMonth}" }
fun dateText(epochDay: Long): String {
    val d = LocalDate.ofEpochDay(epochDay)
    return if (Tr.cjk) tr("{0}年{1}月{2}日", d.year, d.monthValue, d.dayOfMonth) + " " + dayLabel(d.dayOfWeek.value)
    else "${dayLabel(d.dayOfWeek.value)}, ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, Tr.locale())} ${d.dayOfMonth}, ${d.year}"
}

/** 调试版自动化测试用：直接打开某个网页导入。 */
object TimetableTest {
    @Volatile var webUrl: String? = null
    @Volatile var webKind: String? = null
    @Volatile var autoImport = false
    /** 教务导入页搜索框预填（冒烟测试截学校搜索用）。 */
    @Volatile var query: String? = null
}

/** 「导入课表」自动弹出的规则。 */
object ImportPrompt {
    /** 本次启动已经自动弹过了。 */
    @Volatile var shownThisLaunch = false
    private fun p(c: android.content.Context) = c.getSharedPreferences("table", android.content.Context.MODE_PRIVATE)
    fun imported(c: android.content.Context) = p(c).getBoolean("imported", false)
    fun markImported(c: android.content.Context) { if (!imported(c)) p(c).edit().putBoolean("imported", true).apply() }
    /** 纯函数（单测）：只有课表是空的、从没导入过、这次启动还没弹过，才自动弹。 */
    fun shouldAuto(hasCourses: Boolean, everImported: Boolean, shown: Boolean) = !hasCourses && !everImported && !shown
}

private class Block(val s: Slot, val d: Int, val a: Int, val b: Int, val on: Boolean)

// ---------------- 课表整页 ----------------

/**
 * 课表：整页的一周格子，左右滑切换周，点空格加课，点课块改。
 * 右上角：添加、导入、分享备份、更多（学期与显示、每节课时间）。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var ver by remember { mutableStateOf(0) }
    val all = remember(ver, AiData.ver.value) { Term.desired(c) }
    val courses = remember(all) { all.filter { it.kind == "course" } }
    val pal = remember(courses) { coursePalette(courses) }
    val starts = remember(ver, AiData.ver.value) { Term.secStarts(c) }
    val ends = remember(ver, AiData.ver.value) { Term.secEnds(c) }
    val term = remember(ver, AiData.ver.value) { Term.termStart(c) }
    val total = remember(ver, AiData.ver.value) { Term.totalWeeks(c) }
    val showOff = remember(ver, AiData.ver.value) { Term.showOff(c) }
    val nowWeek = remember(ver, AiData.ver.value) { Term.weekNow(c) }
    val weekend = remember(ver, courses, AiData.ver.value) { Term.showWeekend(c) || courses.any { s -> s.days.any { it >= 6 } } }
    val today = Term.todayEpoch()
    fun save(l: List<Slot>) { Term.setDesired(c, l); ver++ }

    // 弹出的东西
    var edit by remember { mutableStateOf<Pair<Slot, Int?>?>(null) }      // 正在改的课 + 点的是星期几（新加的为 null）
    var editNew by remember { mutableStateOf(false) }
    var showTerm by remember { mutableStateOf(false) }
    var termPicker by remember { mutableStateOf(false) }
    var showSecs by remember { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<List<JwCourse>, Boolean>?>(null) }   // 课程 + 导完要不要确认开学日期
    var confirmTerm by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<List<Slot>?>(null) }
    var shot by remember { mutableStateOf<Uri?>(null) }
    var pickCal by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }
    var icsLink by remember { mutableStateOf(false) }
    var backup by remember { mutableStateOf<Term.Backup?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun closeAll() { edit = null; showTerm = false; termPicker = false; showSecs = false; importMenu = false; web = false; preview = null; review = null; backup = null; confirmTerm = false }
    fun addAt(d: Int, sec: Int) {
        val n = starts.size
        val (f, t) = Term.secRange(c, sec, minOf(sec + 1, n))
        edit = Slot("c" + System.currentTimeMillis(), "", setOf(d), f, t) to null; editNew = true
    }

    // 自动化测试 / 外部跳转：table:N
    LaunchedEffect(Nav.subState.value) {
        when (Nav.sub) {
            0 -> closeAll()
            1 -> { closeAll(); web = true }
            2 -> { closeAll(); showTerm = true }
            3 -> { closeAll(); addAt(1, 1) }
            4 -> { closeAll(); courses.minWithOrNull(compareBy({ it.days.minOrNull() ?: 9 }, { it.from }))?.let { edit = it to (it.days.minOrNull() ?: 1); editNew = false } }
            5 -> { closeAll(); showTerm = true; termPicker = true }
            6 -> { closeAll(); web = true }
            7 -> { closeAll(); showSecs = true }
            8 -> { closeAll(); importMenu = true }
        }
        // 用掉这次跳转：以前 sub 一直停在 8（「我的 → 导入课表」、教程里的按钮），每次切回课表页都会重新弹出导入菜单
        if (Nav.sub != 0) Nav.sub = Nav.NONE
    }
    // 还一节课都没有、也从没导入过：每次启动最多自动弹一次导入菜单（有教程在显示时不弹）
    LaunchedEffect(Unit) {
        if (ImportPrompt.shouldAuto(courses.isNotEmpty(), ImportPrompt.imported(c), ImportPrompt.shownThisLaunch) && Tour.show.value == null && !importMenu) {
            ImportPrompt.shownThisLaunch = true; importMenu = true
        }
    }

    // ----- 各种导入 -----
    suspend fun readText(u: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = c.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: return@withContext null
            decodeText(bytes)
        } catch (x: Exception) { null }
    }
    // AI 识别截图（可以一次选几张）：发给「AI 设置」里的看图模型，认出来的课进导入预览（覆盖 / 追加）
    val img = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { us ->
        if (us.isNotEmpty()) { busy = tr("正在识别课表…"); msg = null; shot = us.first()
            scope.launch { val e = AiChat.recognize(c, us, inChat = false); busy = null; if (e != null) msg = e } }
    }
    /** 日历（文件或订阅链接）读出来后：需要时按日历定开学日期和总周数，再让用户勾选要加的课。 */
    fun useIcs(r: Ics.Result?, err: String?) {
        busy = null
        when {
            r == null -> msg = err ?: tr("这个文件里没读到课程")
            r.slots.isEmpty() -> msg = if (r.deadlines > 0) tr("只读到 {0} 个截止日期，没有上下课时间的课。换成学校课表系统的订阅链接试试。", r.deadlines) else tr("这个文件里没读到课程")
            else -> {
                r.firstMonday?.let { Term.setTermStart(c, it) }
                if (r.maxWeek > Term.totalWeeks(c)) Term.setTotalWeeks(c, r.maxWeek)
                ver++
                msg = listOfNotNull(
                    r.firstMonday?.let { tr("已按日历把第 1 周设为 {0} 那周", ymd(it)) },
                    if (r.deadlines > 0) tr("跳过了 {0} 个截止日期", r.deadlines) else null
                ).joinToString("；").ifEmpty { null }
                shot = null; review = r.slots
            }
        }
    }
    fun runIcs(read: () -> String) = scope.launch {
        busy = tr("正在读取…"); msg = null
        var err: String? = null
        val r = withContext(Dispatchers.IO) {
            try { Ics.parse(read(), Term.termStart(c), Term.totalWeeks(c)) }
            catch (x: Ics.LinkDead) { err = tr("链接打不开（{0}），可能已经失效。到学校网站重新复制一次订阅链接。", x.code); null }
            catch (x: Ics.NotCalendar) { err = tr("这个链接打开的不是日历（多半是登录页）。要复制「订阅 / iCal」那个链接，不是网页地址。"); null }
            catch (x: java.io.IOException) { err = tr("连不上：{0}", x.message ?: ""); null }
            catch (x: Exception) { err = tr("读取失败：{0}", x.message ?: ""); null }
        }
        useIcs(r, err)
    }
    val ics = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) runIcs { c.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } ?: "" }
    }
    val calPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pickCal = try { Term.calendars(c) } catch (x: Exception) { emptyList() } else msg = tr("没有日历权限，读不到")
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            busy = tr("正在读取…"); msg = null
            val raw = withContext(Dispatchers.IO) { try { c.contentResolver.openInputStream(u)?.use { it.readBytes() } } catch (x: Exception) { null } }
            busy = null
            when {
                raw == null -> msg = tr("读不了这个文件")
                raw.size > 4 && raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte() -> {
                    // Workday「Export to Excel」之类的选课清单：直接认
                    val sis = withContext(Dispatchers.Default) { try { SisSheet.meets(SisSheet.xlsx(raw) ?: emptyList()) } catch (x: Exception) { emptyList() } }
                    if (sis.isNotEmpty()) useIcs(SisSheet.slots(sis, Term.termStart(c), Term.totalWeeks(c)), null)
                    else msg = tr("这是 .xlsx 文件，请在 Excel / WPS 里「另存为」CSV（逗号分隔）再导入")
                }
                else -> {
                    val text = decodeText(raw)
                    val r = withContext(Dispatchers.Default) { try { Jw.clean(Jw.csv(text)) } catch (x: Exception) { emptyList() } }
                    val sis = if (r.isEmpty()) withContext(Dispatchers.Default) { try { SisSheet.meets(SisSheet.csv(text)) } catch (x: Exception) { emptyList() } } else emptyList()
                    if (sis.isNotEmpty()) useIcs(SisSheet.slots(sis, Term.termStart(c), Term.totalWeeks(c)), null)
                    else if (r.isEmpty()) msg = tr("没读到课程。表头应为：课程名称,星期,开始节数,结束节数,老师,地点,周数") else preview = r to true
                }
            }
        }
    }
    val html = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            busy = tr("正在识别…"); msg = null
            val t = readText(u)
            val r = if (t == null) emptyList() else withContext(Dispatchers.Default) {
                try { Jw.parse("generic", org.json.JSONObject().put("html", org.json.JSONArray().put(t)).toString()) } catch (x: Exception) { emptyList() }
            }
            busy = null
            if (r.isEmpty()) msg = tr("这个网页里没找到课表。要保存「我的课表」那一页（网页，全部）") else preview = r to true
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val b = readText(u)?.let { Term.parseBackup(it) }
            if (b == null || b.courses.isEmpty() && b.starts.isEmpty() && b.todos.isNullOrEmpty()) msg = tr("这不是知时或留白的课表备份") else backup = b
        }
    }
    if (web) {
        BackHandler { web = false }
        JwImportScreen(onClose = { web = false }, onParsed = { r -> web = false; preview = r to true })
        preview?.let { (r, ask) -> ImportPreview(r, onSave = { replace -> applyImport(c, r, replace); preview = null; ver++; if (ask) confirmTerm = true }, onClose = { preview = null }) }
        return
    }

    val pages = if (term >= 0) maxOf(total, nowWeek ?: 1, 1) else 1
    val pager = rememberPagerState(initialPage = ((nowWeek ?: 1) - 1).coerceIn(0, pages - 1), pageCount = { pages })
    LaunchedEffect(term) { if (term >= 0 && nowWeek != null) pager.scrollToPage((nowWeek - 1).coerceIn(0, pages - 1)) }
    val shownWeek: Int? = if (term >= 0) pager.currentPage + 1 else null

    Column(Modifier.fillMaxSize()) {
        // ----- 顶栏：大号「第 N 周」，下面今天的星期和日期；右边加课 / 导入、学期设置 -----
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { showTerm = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(shownWeek, transitionSpec = {
                        val up = (targetState ?: 0) > (initialState ?: 0)
                        (slideInVertically { if (up) it / 2 else -it / 2 } + fadeIn()) togetherWith (slideOutVertically { if (up) -it / 2 else it / 2 } + fadeOut())
                    }, label = "week") { wk ->
                        Text(if (wk == null) tr("课表") else tr("第{0}周", wk), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Ink, maxLines = 1)
                    }
                    val tag = when {
                        shownWeek == null -> null
                        shownWeek == nowWeek -> null
                        nowWeek != null && nowWeek > total && shownWeek == pages -> tr("学期已结束")
                        else -> tr("回到本周")
                    }
                    if (tag != null) Text(tag, fontSize = 11.sp, color = C.ClayDeep, maxLines = 1, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp).glassButton(RoundedCornerShape(10.dp), shadow = 3.dp, enabled = nowWeek != null && shownWeek != nowWeek) { scope.launch { pager.animateScrollToPage(((nowWeek ?: 1) - 1).coerceIn(0, pages - 1)) } }
                            .padding(horizontal = 8.dp, vertical = 3.dp))
                }
                val td = LocalDate.ofEpochDay(today)
                Text(
                    if (term < 0) tr("点这里设置开学日期") else dayLabel(td.dayOfWeek.value) + " " + "${td.monthValue}.${td.dayOfMonth}" +
                        (if (nowWeek != null && nowWeek > total) "  · " + tr("学期已结束") else if (nowWeek != null && nowWeek < 1) "  · " + tr("还没开学") else ""),
                    fontSize = 15.sp, color = if (term < 0) C.ClayDeep else C.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            TopIcon(onClick = { importMenu = true }) { Icon(Icons.Filled.Add, tr("添加与导入"), tint = C.Ink, modifier = Modifier.size(22.dp)) }
            TopIcon(onClick = { showTerm = true }) { CalGlyph(C.Ink) }
        }
        busy?.let { Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(14.dp), color = C.Clay, strokeWidth = 2.dp); Text("  $it", fontSize = 12.sp, color = C.Soft) } }
        msg?.let { m ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(C.ClayPale).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(m, fontSize = 12.sp, color = C.ClayDeep, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                if (shot != null && review == null) Text(tr("自己填"), fontSize = 12.sp, color = C.Ink, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { msg = null; review = emptyList() }.padding(6.dp))
                Text("✕", fontSize = 13.sp, color = C.Soft, modifier = Modifier.clip(CircleShape).clickable { msg = null }.padding(6.dp))
            }
        }

        // ----- 现在 / 下一节 -----
        AnimatedVisibility(shownWeek == nowWeek || term < 0, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            NowStrip(courses, nowWeek, pal) { s, d -> edit = s to d; editNew = false }
        }

        // ----- 格子 -----
        val days = if (weekend) (1..7).toList() else (1..5).toList()
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            val week: Int? = if (term >= 0) page + 1 else null
            val monday = if (week != null) term + (week - 1) * 7L else Term.mondayOf(today)
            WeekGrid(courses, pal, days, week, monday, today, starts, ends, showOff,
                onOpen = { s, d -> edit = s to d; editNew = false }, onAdd = ::addAt,
                onImport = { importMenu = true }, onNew = { addAt(Term.weekday().coerceAtMost(if (weekend) 7 else 5), 1) })
        }
    }

    // ----- 弹窗 -----
    if (importMenu) ImportSheet(onClose = { importMenu = false }, onAdd = { importMenu = false; addAt(Term.weekday().coerceAtMost(if (weekend) 7 else 5), 1) }) { k ->
        importMenu = false
        when (k) {
            "jw" -> web = true
            "csv" -> csv.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "*/*"))
            "html" -> html.launch(arrayOf("text/html", "application/xhtml+xml", "*/*"))
            "img" -> if (!Ai.ready(c)) msg = aiErrorText(AiError.NotConfigured("all")) else img.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            "cal" -> if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
                pickCal = try { Term.calendars(c) } catch (x: Exception) { emptyList() } else calPerm.launch(Manifest.permission.READ_CALENDAR)
            "link" -> icsLink = true
            "ics" -> ics.launch(arrayOf("text/calendar", "*/*"))
            "backup" -> restore.launch(arrayOf("application/json", "text/*", "*/*"))
        }
    }
    edit?.let { (s0, d) ->
        val start = if (d != null && s0.days.size > 1) s0.copy(days = setOf(d)) else s0
        key(s0.id, d) { CourseSheet(start, isNew = editNew, onSave = { n ->
            val cur = Term.desired(c)
            val rest = cur.flatMap { if (it.id != s0.id) listOf(it) else if (d != null && it.days.size > 1) listOf(it.copy(days = it.days - d)) else emptyList() }
            val nn = if (!editNew && s0.days.size > 1) n.copy(id = "c" + System.currentTimeMillis()) else n
            save(rest + nn); edit = null
        }, onDelete = {
            val cur = Term.desired(c)
            save(cur.flatMap { if (it.id != s0.id) listOf(it) else if (d != null && it.days.size > 1) listOf(it.copy(days = it.days - d)) else emptyList() })
            edit = null
        }, onClose = { edit = null }) }
    }
    if (showTerm) TermSheet(openPicker = termPicker, onChange = { ver++ }, onClose = { showTerm = false; termPicker = false })
    if (showSecs) SecTimesSheet(onChange = { ver++ }, onClose = { showSecs = false })
    preview?.let { (r, ask) -> ImportPreview(r, onSave = { replace -> applyImport(c, r, replace); preview = null; ver++; if (ask) confirmTerm = true }, onClose = { preview = null }) }
    if (confirmTerm) TermDateDialog(title = tr("确认开学日期"), sub = tr("选第 1 周里的任意一天，用来区分单双周和非本周的课"),
        initial = if (Term.termStart(c) >= 0) Term.termStart(c) else Term.mondayOf(today),
        onPick = { Term.setTermStart(c, Term.mondayOf(it)); confirmTerm = false; ver++ }, onClose = { confirmTerm = false })
    review?.let { r -> ReviewSheet(r, shot, onAdd = { picked ->
        val cur = Term.desired(c)
        val names = picked.map { "${it.name}|${it.days}|${it.from}" }.toSet()
        save(cur.filter { "${it.name}|${it.days}|${it.from}" !in names } + picked); review = null
    }, onClose = { review = null }) }
    if (icsLink) IcsLinkSheet(onGo = { u -> icsLink = false; runIcs { Ics.fetch(u) } }, onClose = { icsLink = false })
    pickCal?.let { cals -> CalendarPick(cals, onPick = { id ->
        pickCal = null
        scope.launch {
            busy = tr("正在读取日历…")
            val r = withContext(Dispatchers.IO) { try { Term.fromCalendar(c, id) } catch (x: Exception) { null } }
            busy = null
            if (r.isNullOrEmpty()) msg = tr("这个日历接下来两周没有日程") else { shot = null; review = r }
        }
    }, onClose = { pickCal = null }) }
    backup?.let { b ->
        AlertDialog(onDismissRequest = { backup = null }, containerColor = androidx.compose.ui.graphics.Color.Transparent, tonalElevation = 0.dp, shape = DialogShape, modifier = dialogGlass(),
            title = { Text(tr("从备份恢复课表？"), color = C.Ink) },
            text = { Text(if (b.todos == null) tr("备份里有 {0} 节课。会替换现在的课表、每节课时间和开学日期。", b.courses.size)
                else tr("备份里有 {0} 节课、{1} 条待办。会替换现在的课表、待办、每节课时间和开学日期。", b.courses.size, b.todos.size), color = C.Soft, lineHeight = 20.sp) },
            confirmButton = { TextButton({ Term.applyBackup(c, b); backup = null; ver++; msg = tr("已从备份恢复") }) { Text(tr("恢复"), color = C.ClayDeep) } },
            dismissButton = { TextButton({ backup = null }) { Text(tr("取消"), color = C.Soft) } })
    }
}

/** 把导入的课存进课表：覆盖 = 换掉所有课，追加 = 加在后面。 */
fun applyImport(c: android.content.Context, r: List<JwCourse>, replace: Boolean) {
    val slots = Jw.toSlots(r, Term.secStarts(c), Term.secEnds(c), Term.totalWeeks(c))
    val cur = Term.desired(c)
    Term.setDesired(c, (if (replace) cur.filter { it.kind != "course" } else cur) + slots)
    // 导入的课超过 20 周时，总周数跟着变
    val maxW = r.flatMap { it.weeks }.maxOrNull() ?: 0
    if (maxW > Term.totalWeeks(c)) Term.setTotalWeeks(c, maxW)
}

/** 文本文件解码：先当 UTF-8，乱码再按 GBK（Excel 存的 CSV 常是 GBK）。 */
fun decodeText(b: ByteArray): String {
    val u = String(b, Charsets.UTF_8)
    if (!u.contains('\uFFFD')) return u.removePrefix("\uFEFF")
    return try { String(b, charset("GBK")) } catch (x: Exception) { u }
}

@Composable
private fun TopIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.padding(start = 8.dp).size(42.dp).glassButton(CircleShape) { onClick() }, contentAlignment = Alignment.Center) { content() }
}

/** 日历图标（右上角：学期与显示）。 */
@Composable
private fun CalGlyph(col: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val w = size.width; val sw = w * 0.08f
        val st = Stroke(sw, cap = StrokeCap.Round)
        drawRoundRect(col, androidx.compose.ui.geometry.Offset(w * 0.12f, w * 0.2f), androidx.compose.ui.geometry.Size(w * 0.76f, w * 0.68f),
            androidx.compose.ui.geometry.CornerRadius(w * 0.14f), style = st)
        drawLine(col, Offset(w * 0.12f, w * 0.4f), Offset(w * 0.88f, w * 0.4f), sw)
        drawLine(col, Offset(w * 0.33f, w * 0.1f), Offset(w * 0.33f, w * 0.27f), sw, StrokeCap.Round)
        drawLine(col, Offset(w * 0.67f, w * 0.1f), Offset(w * 0.67f, w * 0.27f), sw, StrokeCap.Round)
        for (r in 0..1) for (k in 0..2) drawCircle(col, w * 0.04f, Offset(w * (0.33f + k * 0.17f), w * (0.56f + r * 0.16f)))
    }
}

/** 加课和各种导入：底部弹出的一张卡片。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSheet(onClose: () -> Unit, onAdd: () -> Unit, onPick: (String) -> Unit) {
    GlassSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(tr("添加与导入"), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            SheetRow("＋", tr("添加课程"), tr("手动填一节课")) { onAdd() }
            HorizontalDivider(color = C.Track, modifier = Modifier.padding(vertical = 4.dp))
            // 国外的学校多半给日历订阅链接：非中文界面放在最上面
            if (!Tr.cjk) SheetRow("⟳", tr("日历订阅链接"), tr("Canvas、Moodle、学校课表系统的 iCal 链接")) { onPick("link") }
            SheetRow("教", tr("从教务导入"), tr("登录学校教务网站，一键导入")) { onPick("jw") }
            SheetRow("▤", tr("Excel / CSV 导入"), if (Tr.cjk) tr("课程名、星期、节次、老师、地点、周数") else tr("Workday 等选课系统导出的课程清单，或自己整理的表格")) { onPick("csv") }
            SheetRow("⌘", tr("HTML 导入"), tr("保存下来的教务课表网页")) { onPick("html") }
            SheetRow("✦", tr("AI 识别截图"), tr("课表截图发给你设置的 AI 识别")) { onPick("img") }
            SheetRow("◷", tr("从日历导入"), tr("系统日历里的课程日程")) { onPick("cal") }
            if (Tr.cjk) SheetRow("⟳", tr("日历订阅链接"), tr("Canvas、Moodle、学校课表系统的 iCal 链接")) { onPick("link") }
            SheetRow("◇", tr(".ics 文件"), tr("课表 App 或教务导出的日历文件")) { onPick("ics") }
            HorizontalDivider(color = C.Track, modifier = Modifier.padding(vertical = 4.dp))
            SheetRow("↺", tr("从备份导入"), tr("知时或留白导出的课表备份")) { onPick("backup") }
        }
    }
}

@Composable
private fun SheetRow(mark: String, t: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onClick() }.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(C.ClayPale), contentAlignment = Alignment.Center) {
            Text(mark, fontSize = 15.sp, color = C.ClayDeep, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(t, fontSize = 15.sp, color = C.Ink, fontWeight = FontWeight.Medium)
            Text(sub, fontSize = 12.sp, color = C.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- 现在 / 下一节 ----------------

/** 课表顶上的一条：正在上的课（还剩多久）或下一节（多久后），今天的课上完了就说一声。
 *
 * 闪退记录（build-15～17）：今天的课全部上完后，从小部件进 App、导入课表后回到课表页，首帧组合 ArrayIndexOutOfBoundsException
 * （IntStack.peek2 / ComposerImpl.endRoot）。根源是这块在 Compose 编译器 1.5.x + 运行时 1.7（BOM 2024.09）下组栈错位：
 * 最早是 inline 的 Row 里用带标签的 return 提前返回，build-17 拆成 DoneToday() 后仍是「同一个 Row 里按条件换内容 + 按条件拼 clickable」。
 * 现在的写法（build-18 重做）：
 *  - 整个函数没有任何提前 return；
 *  - 「上完啦」和「正在上 / 下一节」是两个各自完整的 composable，各有自己的 Row 和固定的 Modifier 链，不在 inline 布局里切换内容；
 *  - 两个分支用 key 隔开，状态从一种切到另一种时整组替换，不会复用对方的槽位。
 * 以后改这里也照这个规矩：inline 布局（Row / Column / Box）的内容里不写 return，不按条件拼 Modifier 链。 */
@Composable
private fun NowStrip(courses: List<Slot>, week: Int?, pal: Map<String, Pair<Color, Color>>, onOpen: (Slot, Int) -> Unit) {
    var tick by remember { mutableStateOf(0) }
    Every(30_000) { tick++ }
    val d = Term.weekday()
    val m = remember(tick) { Term.nowMin() }
    val todays = remember(courses, week, d) { courses.filter { d in it.days && it.from < it.to && it.inWeek(week) }.sortedBy { it.from } }
    val n = remember(todays, m) { if (todays.isEmpty()) null else CourseWidgets.plan(todays, m) }
    val s = n?.main
    when {
        n == null -> Unit
        s == null -> key("done") { DoneTodayStrip() }
        else -> key("next") { NextStrip(s, n.live, m, pal) { onOpen(s, d) } }
    }
}

/** 今天的课都上完了：一条静态的玻璃条，不可点。 */
@Composable
private fun DoneTodayStrip() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).liquidGlass(RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("✓", fontSize = 14.sp, color = C.SageInk, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
        Text(tr("今天的课上完啦"), fontSize = 13.sp, color = C.Soft)
    }
}

/** 正在上 / 下一节：点一下打开这节课。 */
@Composable
private fun NextStrip(s: Slot, live: Boolean, m: Int, pal: Map<String, Pair<Color, Color>>, onClick: () -> Unit) {
    val (bg, fg) = courseTone(colorOf(s, pal), C.dark, C.Card)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).liquidGlass(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiveDot(live, bg)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(if (live) tr("正在上") + " · " + Term.clock(s.from) + "–" + Term.clock(s.to) else tr("下一节") + " · " + Term.clock(s.from),
                fontSize = 11.sp, color = C.Faint, maxLines = 1)
            Text(s.name + if (s.room.isNotBlank()) "  @" + s.room else "", fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val left = if (live) s.to - m else s.from - m
        val txt = if (live) tr("还剩 {0} 分钟", left) else if (left >= 60) tr("{0} 小时后", (left + 30) / 60) else tr("{0} 分钟后", left.coerceAtLeast(1))
        Text(txt, fontSize = 12.sp, color = if (live) C.OnClay else fg, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (live) C.Clay else bg).padding(horizontal = 9.dp, vertical = 4.dp))
    }
}

/** 左边的小圆点：正在上的课会呼吸闪烁（动画放在单独的 composable 里，正在上 / 下一节切换时不会错位）。 */
@Composable
private fun LiveDot(live: Boolean, bg: Color) {
    if (live) BreathingDot() else Box(Modifier.size(9.dp).clip(CircleShape).background(bg))
}

@Composable
private fun BreathingDot() {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(9.dp).clip(CircleShape).background(C.Clay.copy(alpha = a)))
}

// ---------------- 一周的格子 ----------------

@Composable
private fun WeekGrid(
    courses: List<Slot>, pal: Map<String, Pair<Color, Color>>, days: List<Int>, week: Int?, monday: Long, today: Long,
    starts: List<Int>, ends: List<Int>, showOff: Boolean, onOpen: (Slot, Int) -> Unit, onAdd: (Int, Int) -> Unit,
    onImport: () -> Unit = {}, onNew: () -> Unit = {}
) {
    val n = starts.size.coerceAtLeast(1)
    val leftW = 48.dp
    var tick by remember { mutableStateOf(0) }
    Every(60_000) { tick++ }
    val now = remember(tick) { Term.nowMin() }
    Column(Modifier.fillMaxSize()) {
        // 表头：星期一行、日期一行；今天的日期是一颗柔和的圆角胶囊
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (Tr.cjk) tr("{0}月", md(monday).first) else LocalDate.ofEpochDay(monday).month.getDisplayName(java.time.format.TextStyle.SHORT, Tr.locale()),
                fontSize = 12.sp, color = C.Soft, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 14.sp, modifier = Modifier.width(leftW))
            for (d in days) {
                val day = monday + (d - 1)
                val isToday = day == today
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dayShort(d), fontSize = 13.sp, color = if (isToday) C.ClayDeep else C.Soft, fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                    Box(Modifier.padding(top = 4.dp).size(34.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (isToday) (if (C.dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.85f)) else Color.Transparent),
                        contentAlignment = Alignment.Center) {
                        Text("${md(day).second}", fontSize = 17.sp, color = if (isToday) C.ClayDeep else C.Ink, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = 4.dp)) {
            val bottomPad = 96.dp   // 底部导航条盖住的高度
            val rowH = maxOf(60.dp, (maxHeight - bottomPad - 8.dp) / n)
            val colW: Dp = (maxWidth - leftW - 8.dp) / days.size
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.padding(start = 4.dp, end = 4.dp, bottom = bottomPad).fillMaxWidth().height(rowH * n + 8.dp).pointerInput(days, n, colW, rowH) {
                    detectTapGestures { o ->
                        val x = with(density) { o.x.toDp() } - leftW
                        if (x < 0.dp) return@detectTapGestures
                        val di = (x / colW).toInt().coerceIn(0, days.lastIndex)
                        val si = (with(density) { o.y.toDp() } / rowH).toInt().coerceIn(0, n - 1)
                        onAdd(days[di], si + 1)
                    }
                }) {
                    // 今天这一列：一层很淡的玻璃底
                    val ti = days.indexOfFirst { monday + (it - 1) == today }
                    if (ti >= 0) Box(Modifier.offset(x = leftW + colW * ti).width(colW).height(rowH * n).clip(RoundedCornerShape(16.dp))
                        .background((if (C.dark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.32f))))
                    // 左边：每节的上课 / 下课时间，一条细虚线时间轴，每节开头一个小圆点；正在上的那一节用主色
                    val lineX = leftW - 5.dp
                    val dash = C.Faint.copy(alpha = 0.45f)
                    androidx.compose.foundation.Canvas(Modifier.offset(x = lineX).width(1.dp).height(rowH * n)) {
                        drawLine(dash, Offset(0f, 0f), Offset(0f, size.height), 1.2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    }
                    for (i in 0 until n) {
                        val e = ends.getOrElse(i) { starts[i] + 45 }
                        val cur = ti >= 0 && now in starts[i] until e
                        Box(Modifier.offset(x = lineX - 2.5.dp, y = rowH * i + 9.dp).size(6.dp).clip(CircleShape).background(if (cur) C.Clay else C.Faint.copy(alpha = 0.6f)))
                        Column(Modifier.offset(y = rowH * i).width(leftW - 10.dp).height(rowH).padding(top = 3.dp, bottom = 4.dp), horizontalAlignment = Alignment.End) {
                            Text(Term.clock(starts[i]).padStart(5, '0'), fontSize = 12.sp, color = if (cur) C.ClayDeep else C.Soft, fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 14.sp, maxLines = 1)
                            Spacer(Modifier.weight(1f))
                            Text(Term.clock(e).padStart(5, '0'), fontSize = 11.sp, color = if (cur) C.ClayDeep else C.Faint, lineHeight = 13.sp, maxLines = 1)
                        }
                    }
                    // 课块：本周的在上；非本周的只在空位上淡淡显示
                    val cur = ArrayList<Block>(); val off = ArrayList<Block>()
                    for (s in courses) {
                        val (a, b) = Term.secSpan(starts, ends, s.from, s.to)
                        val on = s.inWeek(week)
                        for (d in s.days) if (d in days) (if (on) cur else off) += Block(s, d, a, b, on)
                    }
                    val shown = cur.distinctBy { listOf(it.d, it.a, it.b, it.s.name) } +
                        (if (showOff) off.filter { o -> cur.none { it.d == o.d && it.a <= o.b && o.a <= it.b } }.distinctBy { Triple(it.d, it.a, it.b) } else emptyList())
                    for (k in shown) {
                        val live = ti >= 0 && k.on && days.indexOf(k.d) == ti && now in k.s.from until k.s.to
                        CourseBlock(k, colorOf(k.s, pal), live,
                            Modifier.offset(x = leftW + colW * days.indexOf(k.d) + 2.dp, y = rowH * (k.a - 1) + 2.dp)
                                .size(colW - 4.dp, rowH * (k.b - k.a + 1) - 4.dp)) { onOpen(k.s, k.d) }
                    }
                    // 现在的时间线
                    if (ti >= 0) {
                        val y = nowY(now, starts, ends, rowH)
                        if (y != null) {
                            Box(Modifier.offset(x = leftW + colW * ti - 4.dp, y = y - 4.dp).size(8.dp).clip(CircleShape).background(C.ClayDeep))
                            Box(Modifier.offset(x = leftW + colW * ti, y = y - 1.dp).width(colW).height(2.dp).clip(RoundedCornerShape(1.dp)).background(C.ClayDeep))
                        }
                    }
                }
            }
            if (courses.isEmpty()) EmptyTable(Modifier.align(Alignment.Center), onImport, onNew)
        }
    }
}

/** 还没有课时：一笔水墨圆相里三道短横，下面两个大按钮。 */
@Composable
private fun EmptyTable(m: Modifier, onImport: () -> Unit, onNew: () -> Unit) {
    Column(m.padding(horizontal = 28.dp).fillMaxWidth().liquidGlass(RoundedCornerShape(28.dp), Glass.BAR).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        InkArt(InkKind.TABLE, size = 128.dp)
        Text(tr("还没有课"), fontSize = 20.sp, fontFamily = Serif, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 6.dp))
        Text(tr("从教务网站、截图或文件导入；也可以直接点格子加课。"), fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(16.dp))
        PrimaryButton(tr("导入课表")) { onImport() }
        Spacer(Modifier.height(8.dp))
        GhostButton(tr("手动添加"), Modifier.fillMaxWidth()) { onNew() }
    }
}

/** 现在这一刻在格子里的高度；不在上课时间范围内返回 null。 */
private fun nowY(now: Int, starts: List<Int>, ends: List<Int>, rowH: Dp): Dp? {
    if (starts.isEmpty() || now < starts.first() || now > (ends.lastOrNull() ?: starts.last() + 45)) return null
    for (i in starts.indices) {
        val s = starts[i]; val e = ends.getOrElse(i) { s + 45 }
        if (now in s until e) return rowH * i + rowH * ((now - s).toFloat() / (e - s).coerceAtLeast(1))
        val next = starts.getOrNull(i + 1)
        if (next != null && now in e until next) return rowH * (i + 1)
    }
    return null
}

/** 课块里能放下几行：课名优先，其次教室，最后老师；放不下整行的就不放，避免半截字。 */
private class Fit(val name: Int, val room: Int, val teacher: Int)

@Composable
private fun CourseBlock(k: Block, col0: Pair<Color, Color>, live: Boolean, m: Modifier, onClick: () -> Unit) {
    val (bg, fg) = courseTone(col0, C.dark, C.Card)
    val c0 = LocalContext.current
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current
    // 出现时轻轻浮上来
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
    val shape = RoundedCornerShape(14.dp)
    // 正在上的课加一圈细细的同色描边
    Box(m.graphicsLayer { alpha = appear.value; translationY = (1f - appear.value) * 12f }
        // 液态玻璃课块：保留课程颜色，但半透（插画透出来），加亮边和顶部水光
        .liquidGlass(shape, tint = fg, fill = if (k.on) bg.copy(alpha = if (C.dark) 0.64f else 0.68f) else Glass.fill(Glass.CLEAR), shadow = if (k.on) 3.dp else 0.dp)
        .then(if (live) Modifier.border(1.5.dp, fg.copy(alpha = 0.6f), shape) else Modifier)
        .clickable { onClick() }) {
        val tc = if (k.on) fg else C.Faint
        val showLvl = Term.showLevel(c0)
        val lvl = k.s.level.coerceIn(0, 2)
        val tag: String? = when {
            !k.on -> if (Tr.cjk) tr("非本周") else tr("非本周（短）")
            showLvl -> Slot.LEVELS[lvl]
            else -> null
        }
        val sName = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold)
        val sRoom = TextStyle(fontSize = 11.sp, lineHeight = 13.sp)
        val sTeach = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 7.dp, end = 5.dp, top = 7.dp, bottom = 6.dp)) {
            val wPx = constraints.maxWidth; val hPx = constraints.maxHeight
            val room = k.s.room.trim()
            val fit = remember(k.s.name, room, k.s.teacher, wPx, hPx, tag) {
                val cons = androidx.compose.ui.unit.Constraints(maxWidth = wPx.coerceAtLeast(1))
                fun lines(t: String, st: TextStyle) = if (t.isBlank()) 0 else tm.measure(androidx.compose.ui.text.AnnotatedString(t), st, constraints = cons).lineCount
                fun h(t: String, st: TextStyle, n: Int) = if (n <= 0 || t.isBlank()) 0 else
                    tm.measure(androidx.compose.ui.text.AnnotatedString(t), st, overflow = TextOverflow.Ellipsis, maxLines = n, constraints = cons).size.height
                val gap = with(density) { 3.dp.toPx() }
                val avail = hPx - (if (tag != null) with(density) { 17.dp.toPx() } else 0f)
                val nL = lines(k.s.name, sName); val rL = minOf(lines(room, sRoom), 3); val tL = minOf(lines(k.s.teacher, sTeach), 2)
                fun cost(n: Int, r: Int, t: Int) = h(k.s.name, sName, n) + (if (r > 0) gap + h(room, sRoom, r) else 0f) + (if (t > 0) gap + h(k.s.teacher, sTeach, t) else 0f)
                var n = minOf(nL, 2).coerceAtLeast(1); var r = 0; var t = 0
                while (n > 1 && cost(n, 0, 0) > avail) n--
                if (rL > 0 && cost(n, 1, 0) <= avail) r = 1
                while (n < nL && cost(n + 1, r, 0) <= avail) n++
                while (r in 1 until rL && cost(n, r + 1, 0) <= avail) r++
                while (t < tL && cost(n, r, t + 1) <= avail) t++
                Fit(n, r, t)
            }
            Column(Modifier.fillMaxSize()) {
                Text(k.s.name, style = sName, color = tc, maxLines = fit.name, overflow = TextOverflow.Ellipsis)
                if (fit.room > 0) Text(room, style = sRoom, color = tc.copy(alpha = 0.88f), maxLines = fit.room, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                if (fit.teacher > 0) Text(k.s.teacher, style = sTeach, color = tc.copy(alpha = 0.72f), maxLines = fit.teacher, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                Spacer(Modifier.weight(1f))
                if (tag != null) Text(tag, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                    color = if (k.on) Color(LevelTag.fg(lvl, C.dark)) else C.Soft, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (k.on) Color(LevelTag.bg(lvl, C.dark)).copy(alpha = 0.92f) else C.Card).padding(horizontal = 5.dp, vertical = 2.dp))
            }
        }
    }
}

// ---------------- 加课 / 改课 ----------------

@Composable
private fun CField(value: String, hint: String, max: Int = 30, big: Boolean = false, onChange: (String) -> Unit) {
    Box(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Sand).padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, fontSize = if (big) 16.sp else 14.sp, color = C.Faint)
        BasicTextField(value, { onChange(it.take(max)) }, singleLine = true,
            textStyle = TextStyle(fontSize = if (big) 16.sp else 14.sp, color = C.Ink, fontWeight = if (big) FontWeight.SemiBold else FontWeight.Normal), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WChip(t: String, on: Boolean, onClick: () -> Unit) {
    Text(t, fontSize = 12.sp, color = if (on) C.OnClay else C.Ink, maxLines = 1,
        modifier = Modifier.padding(top = 6.dp, end = 6.dp).clip(RoundedCornerShape(10.dp)).background(if (on) C.Clay else C.Sand).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun Label(t: String, value: String? = null) {
    Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(t, fontSize = 13.sp, color = C.Soft, modifier = Modifier.weight(1f))
        if (value != null) Text(value, fontSize = 13.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
    }
}

/** 加课、改课：课名、教室、老师、星期、第几节、周次、上课时的等级、颜色。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSheet(start: Slot, isNew: Boolean = start.name.isEmpty(), onSave: (Slot) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    val c = LocalContext.current
    var s by remember { mutableStateOf(start) }
    val total = remember { Term.totalWeeks(c) }
    val starts = remember { Term.secStarts(c) }
    val ends = remember { Term.secEnds(c) }
    var askDel by remember { mutableStateOf(false) }
    GlassSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 20.dp).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isNew) tr("添加课程") else tr("编辑课程"), fontSize = 21.sp, fontFamily = Serif, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.weight(1f))
                val ok = s.name.isNotBlank() && s.days.isNotEmpty() && s.from != s.to
                Text(tr("保存"), fontSize = 15.sp, color = if (ok) C.OnClay else C.Faint, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (ok) C.Clay else C.Sand).clickable(enabled = ok) {
                        onSave(s.copy(name = s.name.trim(), room = s.room.trim(), teacher = s.teacher.trim()))
                    }.padding(horizontal = 18.dp, vertical = 8.dp))
            }
            CField(s.name, tr("课名"), big = true) { s = s.copy(name = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { CField(s.room, tr("教室（选填）"), 30) { s = s.copy(room = it) } }
                Box(Modifier.weight(1f)) { CField(s.teacher, tr("老师（选填）"), 30) { s = s.copy(teacher = it) } }
            }

            Label(tr("星期"))
            Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (x in 1..7) {
                    val on = x in s.days
                    Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) C.Clay else C.Sand).clickable { s = s.copy(days = setOf(x)) },
                        contentAlignment = Alignment.Center) { Text(dayShort(x), fontSize = 13.sp, color = if (on) C.OnClay else C.Soft, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
                }
            }

            val (a, b) = Term.secSpan(starts, ends, s.from, s.to)
            fun setSec(x: Int, y: Int) { val (f, t) = Term.secRange(c, x, y); s = s.copy(from = f, to = t) }
            Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("节次"), fontSize = 13.sp, color = C.Soft)
                    Text("${Term.clock(s.from)} – ${Term.clock(s.to)}", fontSize = 12.sp, color = C.Faint)
                }
                Stepper(a, 1, starts.size) { setSec(it, maxOf(it, b)) }
                Text(" – ", fontSize = 14.sp, color = C.Soft)
                Stepper(b, a, starts.size) { setSec(a, it) }
            }

            Label(tr("周次"), Slot.weeksText(s.weeks))
            val all = (1..total).toSet()
            val shown = if (s.weeks.isEmpty()) all else s.weeks
            fun setW(w: Set<Int>) { s = s.copy(weeks = if (w == all || w.isEmpty()) emptySet() else w) }
            FlowRowLite {
                WChip(tr("全部"), s.weeks.isEmpty()) { setW(all) }
                WChip(tr("单周"), s.weeks.isNotEmpty() && s.weeks == all.filter { it % 2 == 1 }.toSet()) { setW(all.filter { it % 2 == 1 }.toSet()) }
                WChip(tr("双周"), s.weeks.isNotEmpty() && s.weeks == all.filter { it % 2 == 0 }.toSet()) { setW(all.filter { it % 2 == 0 }.toSet()) }
                WChip(tr("前半学期"), s.weeks == (1..total / 2).toSet()) { setW((1..total / 2).toSet()) }
                WChip(tr("后半学期"), s.weeks == (total / 2 + 1..total).toSet()) { setW((total / 2 + 1..total).toSet()) }
            }
            FlowRowLite {
                for (w in 1..total) {
                    val on = w in shown
                    Box(Modifier.padding(top = 6.dp, end = 6.dp).size(36.dp).clip(CircleShape).background(if (on) C.Clay else C.Sand)
                        .clickable { setW(if (on) shown - w else shown + w) }, contentAlignment = Alignment.Center) {
                        Text("$w", fontSize = 12.sp, color = if (on) C.OnClay else C.Soft)
                    }
                }
            }

            Label(tr("标签"))
            Spacer(Modifier.height(8.dp))
            Segments(Slot.LEVELS, s.level) { s = s.copy(level = it) }
            Text(tr("只是给自己看的标签，显示在课块和小部件上"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 6.dp))

            Label(tr("颜色"))
            FlowRowLite {
                Box(Modifier.padding(top = 8.dp, end = 8.dp).size(30.dp).clip(CircleShape).background(C.Sand)
                    .border(2.dp, if (s.color < 0) C.Ink else Color.Transparent, CircleShape).clickable { s = s.copy(color = -1) }, contentAlignment = Alignment.Center) {
                    Text(tr("自动"), fontSize = 9.sp, color = C.Soft, maxLines = 1)
                }
                COURSE_COLORS.forEachIndexed { i, pair ->
                    val (bg, fg) = courseTone(pair, C.dark, C.Card)
                    Box(Modifier.padding(top = 8.dp, end = 8.dp).size(30.dp).clip(CircleShape).background(bg)
                        .border(2.dp, if (s.color == i) fg else Color.Transparent, CircleShape).clickable { s = s.copy(color = i) })
                }
            }
            Spacer(Modifier.height(10.dp))
            if (!isNew) Text(if (askDel) tr("再点一次确认删除") else tr("删除这节课"), fontSize = 14.sp, color = if (askDel) C.ClayDeep else C.Soft, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Sand.copy(alpha = 0.6f))
                    .clickable { if (askDel) onDelete() else askDel = true }.padding(vertical = 12.dp))
        }
    }
}

// ---------------- 学期与显示 ----------------

/** 日期选择：按界面语言显示。返回选中那天的 epoch 天。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermDateDialog(title: String, sub: String, initial: Long, onPick: (Long) -> Unit, onClose: () -> Unit) {
    val conf = LocalConfiguration.current
    val loc = remember(conf) { android.content.res.Configuration(conf).apply { setLocale(Tr.locale()) } }
    CompositionLocalProvider(LocalConfiguration provides loc) {
        val st = rememberDatePickerState(initialSelectedDateMillis = initial * 86_400_000L)
        DatePickerDialog(onDismissRequest = onClose, shape = DialogShape, tonalElevation = 0.dp, modifier = dialogGlass(),
            colors = DatePickerDefaults.colors(containerColor = Color.Transparent),
            confirmButton = { TextButton({ st.selectedDateMillis?.let { onPick(Math.floorDiv(it, 86_400_000L)) } ?: onClose() }) { Text(tr("确定"), color = C.ClayDeep, fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClose) { Text(tr("取消"), color = C.Soft) } }) {
            // 对话框是单独的窗口，会重新提供 LocalConfiguration，所以在里面再按界面语言覆盖一次
            CompositionLocalProvider(LocalConfiguration provides android.content.res.Configuration(LocalConfiguration.current).apply { setLocale(Tr.locale()) }) {
            DatePicker(st, title = {
                Column(Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) {
                    Text(title, fontSize = 14.sp, color = C.Soft)
                    Text(sub, fontSize = 11.sp, color = C.Faint, lineHeight = 15.sp)
                }
            }, colors = DatePickerDefaults.colors(containerColor = Color.Transparent, selectedDayContainerColor = C.Clay, todayDateBorderColor = C.Clay, todayContentColor = C.ClayDeep,
                selectedYearContainerColor = C.Clay))
            }
        }
    }
}

/** 学期与显示：开学日期、这周第几周（快捷）、总周数、显示周末、显示非本周。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermSheet(openPicker: Boolean = false, onChange: () -> Unit, onClose: () -> Unit) {
    val c = LocalContext.current
    var term by remember { mutableStateOf(Term.termStart(c)) }
    var week by remember { mutableStateOf(Term.weekNow(c)) }
    var total by remember { mutableStateOf(Term.totalWeeks(c)) }
    var off by remember { mutableStateOf(Term.showOff(c)) }
    var wk by remember { mutableStateOf(Term.showWeekend(c)) }
    var lvA by remember { mutableStateOf(Term.showLevel(c)) }
    var lvW by remember { mutableStateOf(Term.widgetLevel(c)) }
    var pick by remember { mutableStateOf(openPicker) }
    LaunchedEffect(openPicker) { if (openPicker) pick = true }
    fun reload() { term = Term.termStart(c); week = Term.weekNow(c); onChange() }
    GlassSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(tr("学期与显示"), fontSize = 21.sp, fontFamily = Serif, fontWeight = FontWeight.Bold, color = C.Ink)
            Row(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Sand).clickable { pick = true }.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("开学日期"), fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
                    Text(if (term < 0) tr("没设置：所有课按每周都上") else tr("第 1 周从 {0} 开始", dateText(term)), fontSize = 12.sp, color = C.Faint)
                }
                Text(if (term < 0) tr("设置") else tr("修改"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("这周是第几周"), fontSize = 14.sp, color = C.Ink)
                    Text(tr("快捷设置，会反推开学日期"), fontSize = 11.sp, color = C.Faint)
                }
                if (week == null) Text(tr("设置"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { Term.setWeekNow(c, 1); reload() }.padding(6.dp))
                else Stepper(week!!, 1, 30) { Term.setWeekNow(c, it); reload() }
            }
            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("这学期一共几周"), fontSize = 14.sp, color = C.Ink, modifier = Modifier.weight(1f))
                Stepper(total, 1, 30) { total = it; Term.setTotalWeeks(c, it); onChange() }
            }
            Toggle(tr("显示周末"), tr("周末有课时总会显示"), wk) { wk = it; Term.setShowWeekend(c, it); onChange() }
            Toggle(tr("显示非本周的课"), tr("淡色显示，标「非本周」"), off) { off = it; Term.setShowOff(c, it); onChange() }
            Toggle(tr("课块上标出等级"), tr("重要、普通、水课各一种颜色"), lvA) { lvA = it; Term.setShowLevel(c, it); onChange() }
            Toggle(tr("小部件上标出等级"), tr("课表小部件里当前和下一节课的等级"), lvW) { lvW = it; Term.setWidgetLevel(c, it) }
            if (term >= 0) Text(tr("不分周次（每周都一样）"), fontSize = 13.sp, color = C.Soft,
                modifier = Modifier.padding(top = 14.dp).clip(RoundedCornerShape(10.dp)).clickable { Term.setTermStart(c, -1); reload() }.padding(6.dp))
        }
    }
    if (pick) TermDateDialog(tr("开学日期"), tr("选第 1 周里的任意一天，按那周的周一算"),
        if (term >= 0) term else Term.mondayOf(Term.todayEpoch()),
        onPick = { Term.setTermStart(c, Term.mondayOf(it)); pick = false; reload() }, onClose = { pick = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecTimesSheet(onChange: () -> Unit, onClose: () -> Unit) {
    GlassSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(tr("每节课时间"), fontSize = 21.sp, fontFamily = Serif, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            SecTimesCard(onChange)
        }
    }
}

/** 每节课的上下课时间：每节都能单独改。 */
@Composable
fun SecTimesCard(onChange: () -> Unit) {
    val c = LocalContext.current
    var secs by remember { mutableStateOf(Term.secStarts(c)) }
    var ends by remember { mutableStateOf(Term.secEnds(c)) }
    var len by remember { mutableStateOf(Term.secLen(c)) }
    var open by remember { mutableStateOf(-1) }
    fun saveS(l: List<Int>) { secs = l; Term.setSecStarts(c, l); onChange() }
    fun saveE(l: List<Int>) { ends = l; Term.setSecEnds(c, l); onChange() }
    SoftCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(tr("点某一节单独改上下课时间；截图识别也按这里换算"), fontSize = 12.sp, color = C.Faint, lineHeight = 17.sp)
        StepSlider(tr("每节课"), len, 20..180, 5, tr("分钟")) {
            len = it; Term.setSecLen(c, it); Term.resetSecEnds(c); ends = Term.secEnds(c); onChange()
        }
        secs.forEachIndexed { i, m ->
            val e = ends.getOrElse(i) { m + len }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).clickable { open = if (open == i) -1 else i }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(tr("第 {0} 节", i + 1), fontSize = 13.sp, color = C.Soft, modifier = Modifier.weight(1f))
                Text("${Term.clock(m)} – ${Term.clock(e)}", fontSize = 15.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
                Text(if (open == i) "  ˄" else "  ˅", fontSize = 13.sp, color = C.Faint)
            }
            if (open == i) {
                StepSlider(tr("上课"), m, 0..(23 * 60 + 55), 5, "", { Term.clock(it) }) { v ->
                    val d = v - m
                    saveS(secs.toMutableList().also { it[i] = v })
                    saveE(ends.toMutableList().also { if (i < it.size) it[i] = (it[i] + d).coerceAtMost(24 * 60 - 1) })
                }
                StepSlider(tr("下课"), e, (m + 5).coerceAtMost(24 * 60 - 1)..(24 * 60 - 1), 5, "", { Term.clock(it) }) { v ->
                    saveE(ends.toMutableList().also { if (i < it.size) it[i] = v })
                }
            }
        }
        Row(Modifier.padding(top = 8.dp)) {
            Text(tr("＋ 加一节"), fontSize = 13.sp, color = C.ClayDeep, modifier = Modifier.clickable {
                val s0 = (ends.lastOrNull() ?: 470) + 10
                saveS(secs + s0); saveE(ends + (s0 + len))
            }.padding(6.dp))
            if (secs.size > 1) Text(tr("减一节"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.clickable { saveS(secs.dropLast(1)); saveE(ends.dropLast(1)) }.padding(6.dp))
        }
    }
}

// ---------------- 导入预览 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPreview(found: List<JwCourse>, onSave: (Boolean) -> Unit, onClose: () -> Unit) {
    val c = LocalContext.current
    var replace by remember { mutableStateOf(1) }
    val had = remember { Term.desired(c).count { it.kind == "course" } }
    GlassSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            Text(tr("识别到 {0} 节课", found.size), fontSize = 21.sp, fontFamily = Serif, fontWeight = FontWeight.Bold, color = C.Ink)
            Text(tr("{0} 门课程。核对一下，导入后还能在课表里点开修改。", found.map { it.name }.distinct().size), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                for (k in found.sortedWith(compareBy({ it.day }, { it.start }))) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(C.Sand.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(52.dp)) {
                            Text(dayLabel(k.day), fontSize = 12.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
                            Text(tr("{0}-{1}节", k.start, k.end), fontSize = 11.sp, color = C.Faint)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(k.name, fontSize = 14.sp, color = C.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(Slot.weeksText(k.weeks), if (k.room.isNotBlank()) "@" + k.room else "", k.teacher).filter { it.isNotBlank() }.joinToString("  "),
                                fontSize = 11.sp, color = C.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (had > 0) {
                Spacer(Modifier.height(12.dp))
                Segments(listOf(tr("覆盖现有"), tr("追加")), if (replace == 1) 0 else 1) { replace = if (it == 0) 1 else 0 }
                Text(if (replace == 1) tr("换掉现在的 {0} 节课", had) else tr("保留现在的课，加在后面"), fontSize = 11.sp, color = C.Faint, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(tr("导入 {0} 节", found.size), enabled = found.isNotEmpty()) { onSave(replace == 1 || had == 0) }
        }
    }
}
