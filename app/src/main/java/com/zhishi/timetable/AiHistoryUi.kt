package com.zhishi.timetable

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** AI 对话记录的读写：App 私有目录的 ai_chats.json（格式和整理规则在 AiConvos，AiHistory.kt）。 */
object AiChatStore {
    val ver = mutableStateOf(0)
    private var cache: List<AiConvo>? = null
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private fun file(c: Context) = File(c.applicationContext.filesDir, "ai_chats.json")

    @Synchronized fun all(c: Context): List<AiConvo> = cache ?: AiConvos.parseList(
        try { file(c).takeIf { it.exists() }?.readText() } catch (e: Exception) { null }).also { cache = it }
    fun get(c: Context, id: String) = all(c).firstOrNull { it.id == id }
    fun put(c: Context, cv: AiConvo) = set(c, AiConvos.upsert(all(c), cv))
    fun delete(c: Context, id: String) = set(c, AiConvos.delete(all(c), id))
    fun clear(c: Context) = set(c, emptyList())

    @Synchronized private fun set(c: Context, l: List<AiConvo>) {
        cache = l; ver.value++
        val s = AiConvos.listJson(l); val f = file(c)
        // 先写临时文件再改名：写到一半被杀掉也不会把原来的记录写坏
        io.execute { try { val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(s); if (!tmp.renameTo(f)) { f.writeText(s); tmp.delete() } }
            catch (e: Exception) { android.util.Log.w("ZhishiAi", "write chats failed", e) } }
    }
}

/** 对话的时间：今天 7:54 / 昨天 22:10 / 11月20日 9:00（和待办同一套说法）。 */
fun convoTime(ms: Long): String = dueText(ms)

/** 「我的 → AI 对话记录」：以前的每段对话（标题 = 第一句话、时间、几条消息），点开接着聊；单条删除、全部清空。 */
@Composable
fun AiHistoryScreen(onOpen: () -> Unit) {
    val c = LocalContext.current
    val v = AiChatStore.ver.value
    val list = remember(v) { AiChatStore.all(c) }
    var askClear by remember { mutableStateOf(false) }
    var askDel by remember { mutableStateOf<AiConvo?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (list.isEmpty()) tr("对话会自动保存在这台手机上") else tr("共 {0} 段对话，只存在这台手机上", list.size),
                fontSize = 13.sp, color = C.Soft, modifier = Modifier.weight(1f))
            if (list.isNotEmpty()) Text(tr("清空"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { askClear = true }.padding(horizontal = 8.dp, vertical = 6.dp))
        }
        if (list.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SparkleIcon(C.Faint, false, Modifier.size(40.dp))
                Text(tr("还没有和 AI 的对话"), fontSize = 15.sp, color = C.Ink, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp))
                Text(tr("点底部导航条中间的 ✦ 开始聊"), fontSize = 13.sp, color = C.Faint, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(20.dp))
                PrimaryButton(tr("开始新对话"), Modifier.widthIn(max = 220.dp)) { AiChat.reset(); onOpen() }
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 40.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).glassButton(RoundedCornerShape(18.dp), tint = C.Clay, shadow = 0.dp) { AiChat.reset(); onOpen() }
                    .padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    SparkleIcon(C.ClayDeep, true, Modifier.size(16.dp))
                    Text(tr("开始新对话"), fontSize = 15.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
                }
            }
            items(list, key = { it.id }) { cv ->
                val cur = cv.id == AiChat.convoId && AiChat.items.isNotEmpty()
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).liquidGlass(RoundedCornerShape(20.dp), shadow = 0.dp)
                    .clickable { if (AiChat.load(c, cv.id)) onOpen() }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cv.title.ifBlank { tr("新对话") }, fontSize = 15.sp, color = C.Ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (cur) Text(tr("当前"), fontSize = 11.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(end = 6.dp).clip(CircleShape).background(C.Clay.copy(alpha = 0.16f)).padding(horizontal = 7.dp, vertical = 1.dp))
                            Text(convoTime(cv.updated) + " · " + tr("{0} 条消息", cv.count), fontSize = 12.sp, color = C.Faint, maxLines = 1)
                        }
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { askDel = cv }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Delete, tr("删除"), tint = C.Faint, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
    }
    askDel?.let { cv ->
        ConfirmDialog(tr("删除这段对话？"), cv.title, tr("删除"), onYes = { AiChatStore.delete(c, cv.id); AiChat.forget(cv.id); askDel = null }, onNo = { askDel = null })
    }
    if (askClear) ConfirmDialog(tr("清空全部对话记录？"), tr("删掉后没法恢复；课表和待办不受影响。"), tr("清空"),
        onYes = { AiChatStore.clear(c); AiChat.reset(); askClear = false }, onNo = { askClear = false })
}

/** 一段对话整页打开：和面板里一样的对话，可以接着聊、回复任意一条。 */
@Composable
fun AiConvoScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    // 测试脚本：ai_convo:N 打开对话记录里第 N 段（1 = 最新）
    LaunchedEffect(Nav.subState.value) { val n = Nav.sub; if (n >= 1) { AiChatStore.all(c).getOrNull(n - 1)?.let { AiChat.load(c, it.id) }; Nav.sub = 0 } }
    val title = AiConvos.title(AiChat.items.map { ChatItem.saved(it) }).ifBlank { tr("新对话") }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp).weight(1f))
        }
        AiChatBody(Modifier.weight(1f), inSheet = false)
    }
}

/** 确认对话框（删除 / 清空这类没法撤回的操作）。 */
@Composable
fun ConfirmDialog(title: String, body: String, yes: String, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(onDismissRequest = onNo, containerColor = Color.Transparent, tonalElevation = 0.dp, shape = DialogShape, modifier = dialogGlass(),
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Ink) },
        text = { if (body.isNotBlank()) Text(body, fontSize = 14.sp, color = C.Soft, lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) },
        confirmButton = { Text(yes, fontSize = 15.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onYes() }.padding(horizontal = 12.dp, vertical = 8.dp)) },
        dismissButton = { Text(tr("取消"), fontSize = 15.sp, color = C.Soft,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onNo() }.padding(horizontal = 12.dp, vertical = 8.dp)) })
}
