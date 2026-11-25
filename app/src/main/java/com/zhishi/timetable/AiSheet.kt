package com.zhishi.timetable

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 对话里的一项。 */
sealed class ChatItem {
    val key = "k" + System.nanoTime()
    class User(val text: String, val images: Int = 0) : ChatItem()
    class Bot(val text: String) : ChatItem()
    class Card(val plan: AiPlan) : ChatItem() { val st = mutableStateOf(0) }
    class Error(val text: String, val retry: Boolean) : ChatItem()
    class Info(val text: String) : ChatItem()
    companion object { const val PENDING = 0; const val APPLIED = 1; const val CANCELLED = 2; const val UNDONE = 3 }
}

/** AI 助手的状态（进程内，收起面板不丢）。 */
object AiChat {
    val open = mutableStateOf(false)
    /** 从哪个标签页打开的：决定空状态的建议。 */
    val from = mutableStateOf("table")
    val items = mutableStateListOf<ChatItem>()
    val busy = mutableStateOf<String?>(null)
    val toast = mutableStateOf<String?>(null)
    /** 提示条上带不带「撤销」。 */
    val toastUndo = mutableStateOf(false)
    /** 测试钩子：打开后要展开到多高（"full" / "half"）。 */
    val wantFull = mutableStateOf(false)
    /** 等着导入预览的识别结果。 */
    val recognized = mutableStateOf<List<JwCourse>?>(null)
    private var agent: AiAgent? = null
    fun agent(c: android.content.Context): AiAgent = (agent ?: AiAgent(Ai.client(c)).also { agent = it }).also { it.client = Ai.client(c) }
    fun reset() { items.clear(); agent?.reset(); toast.value = null }
    fun show(tab: String) { from.value = tab; open.value = true }

    /** 发一句话（在协程里调）。 */
    suspend fun say(c: android.content.Context, text: String) {
        val t = text.trim(); if (t.isEmpty() || busy.value != null) return
        cancelPending(c)
        items += ChatItem.User(t)
        run(c) { a, st -> a.send(t, st, aiEnv(), Tr.lang) }
    }
    suspend fun retry(c: android.content.Context) {
        if (busy.value != null) return
        items.removeAll { it is ChatItem.Error }
        run(c) { a, st -> a.retry(st, aiEnv(), Tr.lang) }
    }
    private suspend fun run(c: android.content.Context, f: (AiAgent, AppState) -> List<AiAgent.Step>) {
        if (!Ai.ready(c)) { items += ChatItem.Error(aiErrorText(AiError.NotConfigured("all")), false); return }
        busy.value = tr("正在想…")
        val a = agent(c)
        val steps = withContext(Dispatchers.IO) { try { f(a, AiStateIO.read(c)) } catch (e: Throwable) { listOf(AiAgent.Step.Failed(e)) } }
        busy.value = null
        for (s in steps) when (s) {
            is AiAgent.Step.Text -> items += ChatItem.Bot(plain(s.text))
            is AiAgent.Step.Pending -> { s.text?.let { items += ChatItem.Bot(plain(it)) }; items += ChatItem.Card(s.plan) }
            is AiAgent.Step.Nav -> { Nav.open.value = s.dest; open.value = false }
            is AiAgent.Step.Failed -> items += ChatItem.Error(aiErrorText(s.error), true)
        }
    }
    /** 有没确认的卡片：用户直接发了新消息，就当取消。 */
    private fun cancelPending(c: android.content.Context) {
        for (i in items) if (i is ChatItem.Card && i.st.value == ChatItem.PENDING) i.st.value = ChatItem.CANCELLED
        agent?.let { if (it.hasPending) it.resolve(false, null) }
    }
    fun confirm(c: android.content.Context, card: ChatItem.Card): Boolean {
        val ok = try { AiUndo.apply(c, card.plan); true } catch (e: Throwable) { android.util.Log.w("ZhishiAi", "apply failed", e); false }
        agent(c).resolve(ok, if (ok) null else "saving failed on the device")
        card.st.value = if (ok) ChatItem.APPLIED else ChatItem.CANCELLED
        toast.value = if (ok) tr("已应用 {0} 项更改", card.plan.changes.size) else tr("保存失败"); toastUndo.value = ok
        return ok
    }
    fun cancel(c: android.content.Context, card: ChatItem.Card) { card.st.value = ChatItem.CANCELLED; agent(c).resolve(false, null) }
    fun undo(c: android.content.Context) {
        val conflicts = try { AiUndo.undo(c) } catch (e: Throwable) { -1 }
        for (i in items) if (i is ChatItem.Card && i.st.value == ChatItem.APPLIED) i.st.value = ChatItem.UNDONE
        agent?.noteUndo()
        toastUndo.value = false
        toast.value = when { conflicts < 0 -> tr("撤销失败"); conflicts > 0 -> tr("已撤销；有 {0} 处在那之后又改过，没动", conflicts); else -> tr("已撤销") }
    }
    /** 回复里的 Markdown 粗体、标题符号去掉（界面是纯文字）。 */
    fun plain(t: String) = t.replace("**", "").replace(Regex("(?m)^#{1,6}\\s*"), "").trim()

    /** 截图识别（导入菜单和对话里附图共用）。 */
    suspend fun recognize(c: android.content.Context, uris: List<Uri>, inChat: Boolean): String? {
        if (!Ai.ready(c)) return aiErrorText(AiError.NotConfigured("all"))
        busy.value = tr("正在识别课表…")
        if (inChat) items += ChatItem.User(tr("[课表截图 ×{0}]", uris.size), uris.size)
        val r = withContext(Dispatchers.IO) {
            try {
                val imgs = uris.take(6).mapNotNull { AiImages.load(c, it) }
                if (imgs.isEmpty()) Result.failure(IllegalStateException(tr("图片打不开"))) else Result.success(AiVision.recognize(c, imgs))
            } catch (e: Throwable) { Result.failure(e) }
        }
        busy.value = null
        val err = r.exceptionOrNull()?.let { if (it is IllegalStateException) it.message else aiErrorText(it, !Ai.hasVisionModel(c)) }
        val out = r.getOrNull()
        if (out != null && out.courses.isEmpty()) return tr("没认出课程，换一张更清楚、带星期表头的截图试试").also { if (inChat) items += ChatItem.Error(it, false) }
        if (err != null) { if (inChat) items += ChatItem.Error(err, false); return err }
        out!!
        if (inChat) {
            agent(c).noteRecognition(out.courses.size, out.courses.map { it.name })
            items += ChatItem.Info(tr("认出 {0} 节课，在导入预览里选「覆盖」或「追加」", out.courses.size) + if (out.dropped > 0) "；" + tr("{0} 条不完整的已跳过", out.dropped) else "")
        }
        recognized.value = out.courses
        return null
    }
}

/** 底部导航条里 AI 入口的图标：两颗四角星（闪光）。 */
@Composable
fun SparkleIcon(tint: Color, filled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        fun star(cx: Float, cy: Float, r: Float): Path = Path().apply {
            val k = r * 0.28f
            moveTo(cx, cy - r); quadraticBezierTo(cx + k * 0.4f, cy - k * 0.4f, cx + r, cy)
            quadraticBezierTo(cx + k * 0.4f, cy + k * 0.4f, cx, cy + r); quadraticBezierTo(cx - k * 0.4f, cy + k * 0.4f, cx - r, cy)
            quadraticBezierTo(cx - k * 0.4f, cy - k * 0.4f, cx, cy - r); close()
        }
        val big = star(w * 0.42f, w * 0.56f, w * 0.36f); val small = star(w * 0.80f, w * 0.22f, w * 0.17f)
        if (filled) { drawPath(big, tint); drawPath(small, tint) }
        else { val st = Stroke(w * 0.075f); drawPath(big, tint, style = st); drawPath(small, tint, style = st) }
    }
}

/**
 * AI 助手：从底部导航条的 ✦ 打开的半屏面板（可以往上拖到全屏、往下拖收起），
 * 后面的课表 / 待办页面不变暗，确认的改动马上能在后面看到。
 */
@Composable
fun AiSheetHost() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val open = AiChat.open.value
    BackHandler(enabled = open) { AiChat.open.value = false }
    // 导入预览（识别出的课）
    AiChat.recognized.value?.let { r ->
        ImportPreview(r, onSave = { replace -> applyImport(c, r, replace); AiData.ver.value++; AiChat.recognized.value = null
            AiChat.toastUndo.value = false; AiChat.toast.value = tr("已导入 {0} 节课", r.size) }, onClose = { AiChat.recognized.value = null })
    }
    if (!open) return
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val density = LocalDensity.current
        val maxH = maxHeight
        val half = maxH * 0.56f; val full = maxH * 0.94f
        val h = remember { Animatable(0f) }
        val target = if (AiChat.wantFull.value) full else half
        LaunchedEffect(Unit) { h.snapTo(with(density) { (half * 0.3f).toPx() }); h.animateTo(with(density) { target.toPx() }, spring(dampingRatio = 0.85f, stiffness = 380f)) }
        LaunchedEffect(AiChat.wantFull.value) { if (h.value > 0f) h.animateTo(with(density) { (if (AiChat.wantFull.value) full else half).toPx() }, spring(dampingRatio = 0.85f, stiffness = 380f)) }
        val drag = rememberDraggableState { d -> scope.launch { h.snapTo((h.value - d).coerceIn(with(density) { 120.dp.toPx() }, with(density) { full.toPx() })) } }
        fun settle(v: Float) = scope.launch {
            val hp = with(density) { half.toPx() }; val fp = with(density) { full.toPx() }
            when {
                h.value < hp * 0.6f || v > 2500f -> { AiChat.open.value = false; AiChat.wantFull.value = false }
                v < -1200f || h.value > (hp + fp) / 2 -> { AiChat.wantFull.value = true; h.animateTo(fp, spring(dampingRatio = 0.85f, stiffness = 380f)) }
                else -> { AiChat.wantFull.value = false; h.animateTo(hp, spring(dampingRatio = 0.85f, stiffness = 380f)) }
            }
        }
        val sheetH: Dp = with(density) { h.value.toDp() }.coerceAtMost(maxH)
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sheetH).liquidGlass(SheetShape, Glass.THICK, shadow = 14.dp)) {
            // 头部：拖动条 + 标题 + 新对话 / 设置 / 收起（整块都能拖）
            Column(Modifier.fillMaxWidth().draggable(drag, Orientation.Vertical, onDragStopped = { v -> settle(v) })) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).background(C.Faint.copy(alpha = 0.45f), RoundedCornerShape(2.dp)))
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    SparkleIcon(C.ClayDeep, true, Modifier.size(18.dp))
                    Text(tr("AI 助手"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    HeadLink(tr("新对话")) { AiChat.reset() }
                    HeadLink(tr("设置")) { AiChat.open.value = false; Nav.open.value = "ai_settings" }
                    HeadLink("⌄") { AiChat.open.value = false; AiChat.wantFull.value = false }
                }
            }
            AiChatBody(Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeadLink(t: String, onClick: () -> Unit) {
    Text(t, fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(horizontal = 8.dp, vertical = 6.dp))
}

@Composable
private fun AiChatBody(modifier: Modifier) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val items = AiChat.items
    val list = rememberLazyListState()
    val ready = remember(Ai.ver.value) { Ai.ready(c) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        if (uris.isNotEmpty()) scope.launch { AiChat.recognize(c, uris, inChat = true) }
    }
    fun attach() = pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    // 撤销条出现 / 消失时列表变矮 / 变高：重新滚到底，免得最后一张卡片的底部（撤销）被截掉
    LaunchedEffect(items.size, AiChat.busy.value, AiChat.toast.value != null) { if (items.isNotEmpty()) { delay(50); list.animateScrollToItem(items.size) } }
    LaunchedEffect(AiChat.toast.value) { if (AiChat.toast.value != null) { delay(6000); AiChat.toast.value = null } }

    Column(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (items.isEmpty()) item { Welcome(ready, onChip = { t, send -> if (send) scope.launch { AiChat.say(c, t) } else input = t }, onShot = { attach() }) }
            items(items, key = { it.key }) { it0 -> ChatRow(it0, onRetry = { scope.launch { AiChat.retry(c) } },
                onConfirm = { card ->
                    if (AiChat.confirm(c, card) && Build.VERSION.SDK_INT >= 33 && card.plan.after.todos.any { it.remind && !it.done } && !TodoAlarm.canNotify(c))
                        perm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }, onCancel = { AiChat.cancel(c, it) }, onUndo = { AiChat.undo(c) }) }
            AiChat.busy.value?.let { b -> item { Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), color = C.Clay, strokeWidth = 2.dp); Text("  $b", fontSize = 13.sp, color = C.Soft) } } }
        }
        // 撤销条
        AiChat.toast.value?.let { t ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Ink.copy(alpha = 0.86f)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(t, fontSize = 13.sp, color = C.Cream, modifier = Modifier.weight(1f), style = androidx.compose.material3.LocalTextStyle.current.copy(shadow = null))
                if (AiUndo.last.value != null && AiChat.toastUndo.value)
                    Text(tr("撤销"), fontSize = 13.sp, color = C.ClayPale, fontWeight = FontWeight.Bold, style = androidx.compose.material3.LocalTextStyle.current.copy(shadow = null),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { AiChat.undo(c) }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        // 输入：附图 + 文字 + 发送
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).glassButton(CircleShape, shadow = 0.dp) { attach() }, contentAlignment = Alignment.Center) { ImageGlyph(C.Ink) }
            Box(Modifier.weight(1f).padding(horizontal = 8.dp).liquidGlass(RoundedCornerShape(21.dp), fill = Glass.field(), shadow = 0.dp).padding(horizontal = 14.dp, vertical = 11.dp)) {
                if (input.isEmpty()) Text(if (ready) tr("说说要改什么，或者问问课表") else tr("先在「设置」里填好 AI 接口"), fontSize = 14.sp, color = C.Faint, maxLines = 1)
                BasicTextField(input, { input = it }, maxLines = 4, cursorBrush = SolidColor(C.Clay), textStyle = TextStyle(fontSize = 14.sp, color = C.Ink), modifier = Modifier.fillMaxWidth())
            }
            val can = input.isNotBlank() && AiChat.busy.value == null
            Box(Modifier.size(42.dp).glassButton(CircleShape, tint = C.Clay, fill = if (can) C.Clay.copy(alpha = 0.85f) else C.Track.copy(alpha = 0.6f), shadow = 0.dp, enabled = can) {
                val t = input; input = ""; scope.launch { AiChat.say(c, t) }
            }, contentAlignment = Alignment.Center) { Text("↑", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (can) C.OnClay else C.Faint, style = androidx.compose.material3.LocalTextStyle.current.copy(shadow = null)) }
        }
    }
}

/** 空状态：一句介绍 + 按当前页面给的建议。 */
@Composable
private fun Welcome(ready: Boolean, onChip: (String, Boolean) -> Unit, onShot: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(tr("我可以帮你改课表、记待办、换皮肤和调设置，也能回答课表上的问题。每次改动都会先给你看前后对比，点「确认」才生效。"),
            fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp)
        if (!ready) Note(tr("还没设置 AI 接口：点右上角「设置」，填好接口地址、API Key 和模型。"), warm = true)
        val todo = AiChat.from.value == "todo"
        // (文字, 直接发送?)
        val chips = if (todo) listOf(tr("加个待办：") to false, tr("今天有哪些待办要做？") to true, tr("把最近要交的待办置顶") to true, tr("这周有哪些待办快到期了？") to true)
            else listOf(tr("加一节课：") to false, tr("明天有什么课？") to true, tr("加个待办：") to false, tr("换个皮肤") to true)
        Spacer(Modifier.height(10.dp))
        FlowRowLite {
            for ((t, send) in chips) AiChip(t.trimEnd('：', ':')) { onChip(t, send) }
            AiChip(tr("识别截图")) { onShot() }
        }
    }
}

@Composable
private fun AiChip(t: String, onClick: () -> Unit) {
    Text(t, fontSize = 13.sp, color = C.ClayDeep, modifier = Modifier.padding(end = 6.dp, top = 6.dp)
        .glassButton(CircleShape, tint = C.Clay, shadow = 0.dp) { onClick() }.padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun ChatRow(it: ChatItem, onRetry: () -> Unit, onConfirm: (ChatItem.Card) -> Unit, onCancel: (ChatItem.Card) -> Unit, onUndo: () -> Unit) {
    when (it) {
        is ChatItem.User -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            Text(it.text, fontSize = 14.sp, color = C.OnClay, lineHeight = 20.sp, style = androidx.compose.material3.LocalTextStyle.current.copy(shadow = null),
                modifier = Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).background(C.Clay.copy(alpha = 0.88f)).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        is ChatItem.Bot -> Text(it.text, fontSize = 14.sp, color = C.Ink, lineHeight = 21.sp,
            modifier = Modifier.padding(vertical = 4.dp).widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)).background(C.Card.copy(alpha = 0.7f)).padding(horizontal = 12.dp, vertical = 8.dp))
        is ChatItem.Info -> Text(it.text, fontSize = 12.sp, color = C.SageInk, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 4.dp))
        is ChatItem.Error -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(C.ClayPale).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(it.text, fontSize = 13.sp, color = C.ClayDeep, lineHeight = 19.sp, modifier = Modifier.weight(1f))
            if (it.retry) Text(tr("重试"), fontSize = 13.sp, color = C.Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onRetry() }.padding(6.dp))
        }
        is ChatItem.Card -> ConfirmCard(it, onConfirm, onCancel, onUndo)
    }
}

/** 确认卡片：每项改动的前后对比（旧值划掉、新值加粗），确认 / 取消；应用后可撤销最后一批。 */
@Composable
private fun ConfirmCard(card: ChatItem.Card, onConfirm: (ChatItem.Card) -> Unit, onCancel: (ChatItem.Card) -> Unit, onUndo: () -> Unit) {
    val st = card.st.value
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).liquidGlass(RoundedCornerShape(20.dp), tint = C.Clay, shadow = 0.dp).padding(14.dp)) {
        Text(when (st) { ChatItem.APPLIED -> tr("已应用"); ChatItem.CANCELLED -> tr("已取消"); ChatItem.UNDONE -> tr("已撤销"); else -> tr("确认这些更改？") },
            fontSize = 12.sp, color = if (st == ChatItem.PENDING) C.ClayDeep else C.Faint, fontWeight = FontWeight.SemiBold)
        for (ch in card.plan.changes) {
            Text(ch.title, fontSize = 15.sp, color = C.Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            for (l in ch.lines) Row(Modifier.padding(top = 3.dp)) {
                Text(l.label, fontSize = 12.sp, color = C.Faint, modifier = Modifier.width(84.dp))
                Column(Modifier.weight(1f)) {
                    if (l.before != null) Text(l.before, fontSize = 13.sp, color = C.Soft, textDecoration = if (st == ChatItem.CANCELLED) null else TextDecoration.LineThrough)
                    if (l.after != null) Text((if (l.before != null) "→ " else "+ ") + l.after, fontSize = 13.sp, color = C.Ink, fontWeight = FontWeight.Medium)
                }
            }
        }
        val failed = card.plan.results.filter { !it.ok }
        if (failed.isNotEmpty()) Text(tr("{0} 项没法执行，已跳过", failed.size), fontSize = 11.sp, color = C.Faint, modifier = Modifier.padding(top = 6.dp))
        if (st == ChatItem.PENDING) Row(Modifier.padding(top = 12.dp)) {
            PrimaryButton(tr("确认"), Modifier.weight(1f)) { onConfirm(card) }
            Spacer(Modifier.width(10.dp))
            GhostButton(tr("取消"), Modifier.weight(0.6f)) { onCancel(card) }
        }
        if (st == ChatItem.APPLIED && AiUndo.last.value != null && card === AiChat.items.lastOrNull { it is ChatItem.Card && it.st.value == ChatItem.APPLIED })
            Text(tr("撤销"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { onUndo() }.padding(6.dp))
    }
}

/** 附图按钮的小图标：一张照片（框 + 山 + 太阳）。 */
@Composable
private fun ImageGlyph(col: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width; val sw = w * 0.09f
        drawRoundRect(col, Offset(w * 0.1f, w * 0.18f), androidx.compose.ui.geometry.Size(w * 0.8f, w * 0.64f), androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = Stroke(sw))
        val p = Path().apply { moveTo(w * 0.18f, w * 0.74f); lineTo(w * 0.42f, w * 0.46f); lineTo(w * 0.58f, w * 0.62f); lineTo(w * 0.68f, w * 0.52f); lineTo(w * 0.84f, w * 0.74f) }
        drawPath(p, col, style = Stroke(sw))
        drawCircle(col, w * 0.07f, Offset(w * 0.66f, w * 0.34f))
    }
}
