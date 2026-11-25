package com.zhishi.timetable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

/**
 * 日历：一个月的格子，有课的日子下面一个主色小点，有待办的一个暖色小点；
 * 点某一天，下面列出那天的课和待办。左右箭头切换月份。
 */
@Composable
fun CalendarScreen(onOpenTodo: (Todo) -> Unit = {}) {
    val c = LocalContext.current
    val courses = remember(AiData.ver.value) { Term.desired(c).filter { it.kind == "course" } }
    val pal = remember(courses) { coursePalette(courses) }
    val term = remember(AiData.ver.value) { Term.termStart(c) }
    val total = remember(AiData.ver.value) { Term.totalWeeks(c) }
    val tv = TodoStore.ver.value
    val todos = remember(tv) { TodoStore.all(c) }
    val today = Term.todayEpoch()
    var month by remember { mutableStateOf(YearMonth.from(LocalDate.ofEpochDay(today))) }
    var sel by remember { mutableStateOf(today) }
    val todoDays = remember(todos) { todos.mapNotNull { Todos.dayOf(it) }.toSet() }
    var edit by remember { mutableStateOf<Todo?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 110.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("日历"), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                Text(monthTitle(month), fontSize = 14.sp, color = C.Soft, modifier = Modifier.padding(top = 2.dp))
            }
            RoundBtn("‹") { month = month.minusMonths(1) }
            if (month != YearMonth.from(LocalDate.ofEpochDay(today)) || sel != today) Text(tr("今天"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp).glassButton(RoundedCornerShape(14.dp), shadow = 3.dp) {
                    month = YearMonth.from(LocalDate.ofEpochDay(today)); sel = today }.padding(horizontal = 12.dp, vertical = 8.dp))
            RoundBtn("›") { month = month.plusMonths(1) }
        }
        // 月历卡片
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth().liquidGlass(RoundedCornerShape(24.dp)).padding(horizontal = 8.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                for (d in 1..7) Text(dayShort(d), fontSize = 13.sp, color = if (d >= 6) C.Faint else C.Soft, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
            AnimatedContent(month, transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 * dir } + fadeOut())
            }, label = "month") { m ->
                val first = m.atDay(1).toEpochDay()
                val start = Term.mondayOf(first)
                val rows = ((first - start) + m.lengthOfMonth() + 6) / 7
                Column(Modifier.padding(top = 6.dp)) {
                    for (r in 0 until rows) Row(Modifier.fillMaxWidth()) {
                        for (k in 0 until 7) {
                            val day = start + r * 7 + k
                            val inMonth = LocalDate.ofEpochDay(day).monthValue == m.monthValue
                            val hasClass = remember(day, courses) { Term.classesOn(courses, term, total, day).isNotEmpty() }
                            DayCell(LocalDate.ofEpochDay(day).dayOfMonth, inMonth, day == today, day == sel, hasClass, day in todoDays,
                                Modifier.weight(1f)) { sel = day; if (!inMonth) month = YearMonth.from(LocalDate.ofEpochDay(day)) }
                        }
                    }
                }
            }
            Row(Modifier.padding(start = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(C.Clay, 6.dp); Text(" " + tr("有课"), fontSize = 11.sp, color = C.Faint)
                Spacer(Modifier.width(14.dp))
                Dot(TodoDot, 6.dp); Text(" " + tr("有待办"), fontSize = 11.sp, color = C.Faint)
            }
        }
        // 选中那天
        val d = LocalDate.ofEpochDay(sel)
        val wk = if (term >= 0) Term.weekOf(term, sel) else null
        Text(dayTitle(sel) + (if (wk != null && wk in 1..total) "  · " + tr("第 {0} 周", wk) else ""),
            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(start = 22.dp, top = 6.dp, bottom = 6.dp))
        val cls = remember(sel, courses) { Term.classesOn(courses, term, total, sel) }
        val tds = remember(sel, todos) { Todos.on(todos, sel) }
        if (cls.isEmpty() && tds.isEmpty()) Text(tr("这天没有课，也没有待办"), fontSize = 13.sp, color = C.Soft,
            modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth().liquidGlass(RoundedCornerShape(18.dp), shadow = 3.dp).padding(16.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            for (s in cls) {
                val (bg, fg) = courseTone(colorOf(s, pal), C.dark, C.Card)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).liquidGlass(RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(52.dp)) {
                        Text(Term.clock(s.from), fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
                        Text(Term.clock(s.to), fontSize = 12.sp, color = C.Faint)
                    }
                    Box(Modifier.width(4.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(if (C.dark) fg.copy(alpha = 0.7f) else bg))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(s.name, fontSize = 15.sp, color = C.Ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOf(s.room, s.teacher).filter { it.isNotBlank() }.joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = C.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (tds.isNotEmpty()) Text(tr("待办"), fontSize = 13.sp, color = C.Soft, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp))
            val now = System.currentTimeMillis()
            for (t in tds) TodoRow(t, now, onToggle = { TodoStore.toggle(c, t) }, onOpen = { edit = t }, onDelete = null)
        }
    }
    edit?.let { t -> key(t.id) { TodoSheet(t, false, onSave = { TodoStore.put(c, it); edit = null }, onDelete = { TodoStore.remove(c, t.id); edit = null }, onClose = { edit = null }) } }
}

@Composable
private fun RoundBtn(t: String, onClick: () -> Unit) {
    Box(Modifier.padding(start = 6.dp).size(38.dp).glassButton(CircleShape) { onClick() },
        contentAlignment = Alignment.Center) { Text(t, fontSize = 22.sp, color = C.Ink, lineHeight = 22.sp) }
}

@Composable
private fun DayCell(n: Int, inMonth: Boolean, today: Boolean, sel: Boolean, cls: Boolean, todo: Boolean, m: Modifier, onClick: () -> Unit) {
    Column(m.padding(2.dp).clip(RoundedCornerShape(14.dp))
        .background(when { sel -> C.Clay; today -> C.ClayPale.copy(alpha = 0.9f); else -> Color.Transparent })
        .clickable { onClick() }.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$n", fontSize = 15.sp, fontWeight = if (today || sel) FontWeight.Bold else FontWeight.Normal,
            color = when { sel -> C.OnClay; !inMonth -> C.Faint.copy(alpha = 0.6f); today -> C.ClayDeep; else -> C.Ink })
        Row(Modifier.height(8.dp).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (cls) Dot(if (sel) C.OnClay else C.Clay.copy(alpha = if (inMonth) 1f else 0.4f), 5.dp)
            if (todo) Dot(if (sel) C.OnClay.copy(alpha = 0.8f) else TodoDot.copy(alpha = if (inMonth) 1f else 0.4f), 5.dp)
        }
    }
}

fun monthTitle(m: YearMonth): String = if (Tr.cjk) tr("{0}年{1}月", m.year, m.monthValue)
    else m.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, Tr.locale()).replaceFirstChar { it.uppercase() } + " " + m.year

fun dayTitle(day: Long): String {
    val d = LocalDate.ofEpochDay(day)
    return if (Tr.cjk) tr("{0}月{1}日", d.monthValue, d.dayOfMonth) + " " + dayLabel(d.dayOfWeek.value)
    else "${dayLabel(d.dayOfWeek.value)}, ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, Tr.locale())} ${d.dayOfMonth}"
}
