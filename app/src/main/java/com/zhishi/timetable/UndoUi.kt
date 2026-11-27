package com.zhishi.timetable

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一次撤销的结果：真的撤掉了哪几项、几样东西之后又被改过没动、出错的话。 */
class UndoResult(val done: Set<Int>, val conflicts: Int, val error: String? = null)

/**
 * 可以撤销的一批改动：AI 助手的一轮（kind = AI）或同步下来的智能体改动（AGENT）。
 * run 在后台线程跑（AI 的会切回主线程写存储；智能体的调服务器的 undo 再同步）。tag = 附带的数据（智能体：日志条目）。
 */
class UndoBatch(val title: String, val entries: List<UndoEntry>, val kind: Int, val tag: Any? = null, val undoFn: (Context, Set<Int>) -> UndoResult) {
    val undone = mutableStateOf(entries.indices.filter { entries[it].undone }.toSet())
    fun view(): List<UndoEntry> = entries.mapIndexed { i, e -> if (i in undone.value) e.copy(undone = true) else e }
    val left get() = entries.size - undone.value.size
    companion object { const val AI = 0; const val AGENT = 1 }
}

object UndoCenter {
    /** 打开着的撤销列表和勾选。 */
    val sheet = mutableStateOf<UndoBatch?>(null)
    val sel = mutableStateOf<Set<Int>>(emptySet())
    val busy = mutableStateOf(false)
    val msg = mutableStateOf<String?>(null)
    /** 同步下来的智能体改动（底部提示条）。 */
    val agentBatch = mutableStateOf<UndoBatch?>(null)
    /** 撤销完了的回调（AI 面板用来更新卡片、告诉模型）。 */
    var after: ((UndoBatch, UndoResult) -> Unit)? = null

    /** 打开撤销列表：pre = 预先勾上的（按依赖补齐），默认全部没撤销的。 */
    fun open(b: UndoBatch, pre: Set<Int>? = null) {
        sheet.value = b; msg.value = null
        val v = b.view()
        sel.value = if (pre == null) UndoDeps.all(v) else UndoDeps.close(v, pre.filter { it !in b.undone.value }.toSet())
    }
    fun toggle(i: Int) {
        val b = sheet.value ?: return
        sel.value = UndoDeps.toggle(b.view(), sel.value, i, i !in sel.value)
    }

    /** 撤销所选（后台线程跑，完了回主线程更新状态）。 */
    suspend fun run(c: Context, b: UndoBatch, s: Set<Int>): UndoResult {
        busy.value = true
        val r = withContext(Dispatchers.IO) { try { b.undoFn(c, s) } catch (e: Throwable) { UndoResult(emptySet(), 0, e.message ?: e.javaClass.simpleName) } }
        busy.value = false
        b.undone.value = b.undone.value + r.done
        sel.value = UndoDeps.all(b.view())
        msg.value = when {
            r.error != null && r.done.isEmpty() -> tr("撤销失败：{0}", r.error)
            r.conflicts > 0 -> tr("已撤销 {0} 处；有 {1} 处在那之后又改过，没动", r.done.size, r.conflicts)
            r.error != null -> tr("已撤销 {0} 处；有的没撤销成：{1}", r.done.size, r.error)
            else -> tr("已撤销 {0} 处", r.done.size)
        }
        if (b.left == 0 && agentBatch.value === b) agentBatch.value = null
        after?.invoke(b, r)
        return r
    }
}

/** 自定义撤回：每项改动（前 → 后）带勾选框，「撤销所选」「全部撤销」。放在 App 最外层，哪里打开都一样。 */
@Composable
fun UndoSheetHost() {
    val b = UndoCenter.sheet.value ?: return
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val v = b.view()
    val sel = UndoCenter.sel.value
    GlassSheet(onDismissRequest = { if (!UndoCenter.busy.value) UndoCenter.sheet.value = null }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp).navigationBarsPadding()) {
            Text(b.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Text(tr("勾选要撤销的改动。后面又改过同一样东西的，会跟着一起撤销。"), fontSize = 12.sp, color = C.Soft, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
                itemsIndexed(v) { i, e -> UndoRow(e, i in sel) { if (!e.undone && !UndoCenter.busy.value) UndoCenter.toggle(i) } }
            }
            UndoCenter.msg.value?.let { Note(it, warm = true) }
            Spacer(Modifier.height(12.dp))
            val left = UndoDeps.all(v)
            Row(verticalAlignment = Alignment.CenterVertically) {
                GhostButton(tr("全部撤销"), Modifier.weight(1f)) {
                    if (left.isNotEmpty() && !UndoCenter.busy.value) scope.launch { UndoCenter.run(c, b, left) }
                }
                Spacer(Modifier.width(10.dp))
                PrimaryButton(if (sel.isEmpty()) tr("撤销所选") else tr("撤销所选（{0}）", sel.size), Modifier.weight(1f), enabled = sel.isNotEmpty() && !UndoCenter.busy.value) {
                    scope.launch { UndoCenter.run(c, b, sel) }
                }
            }
            if (left.isEmpty()) Text(tr("这一批已经全部撤销"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun UndoRow(e: UndoEntry, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(if (on) C.Clay.copy(alpha = 0.10f) else Color.Transparent)
        .clickable { onClick() }.semantics { contentDescription = "undo-row" }.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.Top) {
        // 勾选框：圆角小方块，勾上是主色实底 + ✓；已撤销的是灰的「已撤销」
        Box(Modifier.padding(top = 2.dp).size(20.dp).clip(RoundedCornerShape(6.dp))
            .background(if (e.undone) C.Track else if (on) C.Clay else C.Track.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            if (on && !e.undone) Text("✓", fontSize = 13.sp, color = C.OnClay, fontWeight = FontWeight.Bold, style = androidx.compose.material3.LocalTextStyle.current.copy(shadow = null))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(e.change.title, fontSize = 14.sp, color = if (e.undone) C.Faint else C.Ink, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (e.undone) TextDecoration.LineThrough else null)
            for (l in e.change.lines.take(4)) Row(Modifier.padding(top = 2.dp)) {
                Text(l.label + "  ", fontSize = 12.sp, color = C.Faint)
                if (l.before != null) Text(l.before, fontSize = 12.sp, color = C.Soft, textDecoration = TextDecoration.LineThrough, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (l.before != null && l.after != null) Text(" → ", fontSize = 12.sp, color = C.Faint)
                if (l.after != null) Text(l.after, fontSize = 12.sp, color = C.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            val meta = listOfNotNull(e.who.takeIf { it.isNotBlank() }, if (e.at > 0) clockText(e.at) else null, if (e.undone) tr("已撤销") else null).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, fontSize = 11.sp, color = C.Faint, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** 「11-27 18:05」（今天的只写时刻）。 */
fun clockText(ms: Long): String {
    val today = Todos.dayOfMs(System.currentTimeMillis())
    val f = if (Todos.dayOfMs(ms) == today) "HH:mm" else "MM-dd HH:mm"
    return java.text.SimpleDateFormat(f, java.util.Locale.US).format(java.util.Date(ms))
}

/** 同步下来了智能体的改动：底部一条提示「智能体「X」改了 N 处 · 撤销」，点开是撤销列表。 */
@Composable
fun AgentBanner(modifier: Modifier) {
    val b = UndoCenter.agentBatch.value ?: return
    Row(modifier.padding(horizontal = 16.dp).fillMaxWidth().liquidGlass(RoundedCornerShape(18.dp), Glass.THICK, shadow = 8.dp)
        .clickable { UndoCenter.open(b) }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        SparkleIcon(C.ClayDeep, true, Modifier.size(16.dp))
        Text(b.title, fontSize = 13.sp, color = C.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
        Text(tr("撤销"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
        Text("✕", fontSize = 13.sp, color = C.Faint, modifier = Modifier.clip(CircleShape).clickable { UndoCenter.agentBatch.value = null }.padding(6.dp))
    }
}
