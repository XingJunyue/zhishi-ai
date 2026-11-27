package com.zhishi.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「连接 AI 智能体（MCP）」页的状态（进程内；测试钩子也用它把刚建好的令牌摆出来截图）。 */
object McpUi {
    /** 刚建好的令牌：只显示这一次。 */
    val created = mutableStateOf<CloudSync.NewToken?>(null)
    val tokens = mutableStateOf<List<CloudSync.AgentToken>?>(null)
    val log = mutableStateOf<List<CloudSync.LogEntry>?>(null)
    val ver = mutableStateOf(0)
}

/**
 * 「我的 → AI → 连接 AI 智能体（MCP）」：打开云同步（默认关）、添加 / 撤销智能体令牌（MCP 地址、令牌、带令牌的链接只显示一次，带复制和警告）、
 * 通用的 MCP 客户端接入说明（不点名具体产品）、智能体改动记录（可以撤销）、停止同步 / 删除云端数据。
 */
@Composable
fun McpScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val ver = CloudSync.ver.value
    val on = remember(ver) { CloudSync.enabled(c) }
    var busy by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var askName by remember { mutableStateOf(false) }
    var askOff by remember { mutableStateOf(false) }
    var askRevoke by remember { mutableStateOf<CloudSync.AgentToken?>(null) }
    fun copy(t: String) { clip.setText(AnnotatedString(t)); toast = tr("已复制") }
    fun io(label: String, f: () -> Unit) {
        busy = label; err = null
        scope.launch {
            val e = withContext(Dispatchers.IO) { try { f(); null } catch (x: Throwable) { CloudSync.errText(x) } }
            busy = null; err = e; McpUi.ver.value++
        }
    }
    // 打开时、改了之后：读令牌列表和智能体改动记录
    LaunchedEffect(on, McpUi.ver.value, UndoCenter.agentBatch.value) {
        if (!on) { McpUi.tokens.value = null; McpUi.log.value = null; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try { McpUi.tokens.value = CloudSync.tokens(c).filter { it.revoked == 0L } } catch (x: Throwable) {}
            try { McpUi.log.value = CloudSync.agentLog(c, 0, 50) } catch (x: Throwable) {}
        }
    }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(2000); toast = null } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Note(tr("让支持 MCP 的 AI 智能体读写你的课表和待办：只有打开下面的「云同步」后，课表、待办和学期、节次、皮肤、小部件和显示设置才会同步到知时 AI 的同步服务器（mcp.xingjunyue.top）；关着时不上传任何数据。"), warm = true)

        SectionTitle(tr("云同步"), tr("打开后会注册一个匿名同步账号（不要邮箱和密码），在手机和服务器之间同步；智能体的改动会同步回手机，可以撤销。"))
        SoftCard(Modifier.fillMaxWidth(), pad = 14.dp) {
            Toggle(tr("云同步"), if (on) syncLine(c) else tr("关着：数据只在这台手机上"), on) { want ->
                if (want) io(tr("正在打开同步，上传课表和待办…")) { CloudSync.enable(c) } else askOff = true
            }
            if (on) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton(tr("立即同步")) { io(tr("正在同步…")) { if (CloudSync.syncBlocking(c) == null) throw IllegalStateException(CloudSync.lastError.value ?: "") } }
            }
            if (on && !CloudSync.tokenEncrypted(c)) Text(tr("这台手机的密钥库不可用，同步令牌没有加密保存"), fontSize = 11.sp, color = C.ClayDeep, modifier = Modifier.padding(top = 6.dp))
        }
        busy?.let { Text(it, fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 10.dp, start = 4.dp)) }
        err?.let { Note(it, warm = true) }
        toast?.let { Text(it, fontSize = 12.sp, color = C.SageInk, modifier = Modifier.padding(top = 6.dp, start = 4.dp)) }

        if (on) {
            // 刚建好的令牌：只显示这一次
            McpUi.created.value?.let { t -> NewTokenCard(t, ::copy) { McpUi.created.value = null } }

            SectionTitle(tr("智能体令牌"), tr("每个智能体单独一个令牌，不用了随时撤销，撤销后它马上就连不上。"))
            PrimaryButton(tr("添加智能体")) { askName = true }
            val list = McpUi.tokens.value
            if (list == null) Text(tr("正在读取…"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
            else if (list.isEmpty()) Text(tr("还没有智能体"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
            else SoftCard(Modifier.fillMaxWidth().padding(top = 10.dp), pad = 8.dp) {
                for (t in list) Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name + "  ·  …" + t.hint, fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tr("创建于 {0}", clockText(t.created)) + " · " + (if (t.lastUsed > 0) tr("最近使用 {0}", clockText(t.lastUsed)) else tr("还没用过")),
                            fontSize = 11.sp, color = C.Faint)
                    }
                    Text(tr("撤销"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { askRevoke = t }.padding(8.dp))
                }
            }

            SectionTitle(tr("怎么接入"), null)
            SoftCard(Modifier.fillMaxWidth(), pad = 14.dp) {
                for (l in listOf(
                    tr("1. 点「添加智能体」，给它起个名字，复制出现的 MCP 地址和令牌。"),
                    tr("2. 在任何支持 MCP 的 AI 智能体或客户端里，添加一个远程 MCP 服务器（Streamable HTTP）：地址填 MCP 地址，认证方式选 Bearer Token，填上令牌。"),
                    tr("3. 只能填一个网址、填不了令牌的客户端，直接粘贴「带令牌的链接」。"),
                    tr("4. 然后就可以跟它说「看看我明天的课」「帮我加个周五交的待办」。它改的东西会同步回手机，在下面的记录里能看到、能撤销。"),
                )) Text(l, fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 3.dp))
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(CloudSync.MCP_URL, fontSize = 12.sp, color = C.Ink, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    CopyChip { copy(CloudSync.MCP_URL) }
                }
            }

            SectionTitle(tr("智能体改动记录"), tr("智能体通过 MCP 做的每一处改动；点「撤销」打开撤销列表，可以只撤销其中几项。"))
            val log = McpUi.log.value
            if (log == null) Text(tr("正在读取…"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(start = 4.dp))
            else if (log.isEmpty()) Text(tr("还没有智能体改过东西"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(start = 4.dp))
            else SoftCard(Modifier.fillMaxWidth(), pad = 8.dp) {
                val st = remember(log) { AiStateIO.read(c) }
                for (e in log.take(30)) {
                    val u = remember(e) { CloudSync.entryOf(e, st) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(u.change.title, fontSize = 13.sp, color = if (e.undone) C.Faint else C.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textDecoration = if (e.undone) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                            Text(e.who + " · " + e.tool + " · " + clockText(e.at) + (if (e.undone) " · " + tr("已撤销") else ""), fontSize = 11.sp, color = C.Faint, maxLines = 1)
                        }
                        if (!e.undone) Text(tr("撤销"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                val open = log.filter { !it.undone }
                                val b = CloudSync.batchOf(c, open, tr("AI 智能体改了 {0} 处", open.size))
                                UndoCenter.open(b, setOf(open.sortedBy { it.seq }.indexOfFirst { it.seq == e.seq }))
                            }.padding(8.dp))
                    }
                }
            }

            SectionTitle(tr("停止同步"), tr("同步令牌只存在这台手机上，没有密码可以找回：删掉 App 或清除数据后，这个同步账号就找不回了（手机上的课表和待办不受影响）。"))
            GhostButton(tr("停止同步…"), Modifier.fillMaxWidth()) { askOff = true }
        }
        Spacer(Modifier.height(80.dp))
    }

    if (askName) NameDialog(onDone = { name ->
        askName = false
        io(tr("正在创建令牌…")) { McpUi.created.value = CloudSync.createToken(c, name) }
    }, onCancel = { askName = false })
    askRevoke?.let { t ->
        ConfirmDialog(tr("撤销「{0}」的令牌？", t.name), tr("用这个令牌的智能体会马上连不上；它之前做的改动不受影响。"), tr("撤销"),
            onYes = { askRevoke = null; io(tr("正在撤销…")) { CloudSync.revokeToken(c, t.id) } }, onNo = { askRevoke = null })
    }
    if (askOff) StopDialog(onStop = { del -> askOff = false; McpUi.created.value = null
        io(if (del) tr("正在删除云端数据…") else tr("正在停止同步…")) { CloudSync.disable(c, del) } }, onCancel = { askOff = false })
}

@Composable
private fun syncLine(c: android.content.Context): String {
    val e = CloudSync.lastError.value
    return when {
        CloudSync.busy.value -> tr("正在同步…")
        e != null -> e
        CloudSync.lastAt.value > 0 -> tr("已开启 · 上次同步 {0}", clockText(CloudSync.lastAt.value))
        else -> tr("已开启")
    }
}

/** 刚建好的令牌：MCP 地址 / 令牌 / 带令牌的链接，各带复制；警告；「我已保存」收起（之后再也看不到令牌）。 */
@Composable
private fun NewTokenCard(t: CloudSync.NewToken, copy: (String) -> Unit, onDone: () -> Unit) {
    SectionTitle(tr("「{0}」的连接信息", t.name), tr("令牌只显示这一次，现在就复制到智能体里。"))
    SoftCard(Modifier.fillMaxWidth(), pad = 14.dp) {
        for ((label, v) in listOf(tr("MCP 地址") to t.mcpUrl, tr("令牌（Bearer Token）") to t.token, tr("带令牌的链接（只能填网址的客户端用）") to t.urlWithToken)) {
            Text(label, fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(v, fontSize = 12.sp, color = C.Ink, fontFamily = FontFamily.Monospace, lineHeight = 17.sp, modifier = Modifier.weight(1f).padding(vertical = 3.dp))
                CopyChip { copy(v) }
            }
        }
        Text(tr("拿到这个链接的人可以读写你的课表和待办，只发给你信任的 AI；随时可以在这里撤销。"), fontSize = 12.sp, color = C.ClayDeep, lineHeight = 18.sp,
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.ClayPale.copy(alpha = 0.6f)).padding(10.dp))
        Text(tr("我已保存"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.End).padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { onDone() }.padding(8.dp))
    }
}

@Composable
private fun CopyChip(onClick: () -> Unit) {
    Text(tr("复制"), fontSize = 12.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).background(C.Clay.copy(alpha = 0.12f)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
private fun NameDialog(onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onCancel, containerColor = Color.Transparent, tonalElevation = 0.dp, shape = DialogShape, modifier = dialogGlass(),
        title = { Text(tr("添加智能体"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Ink) },
        text = {
            Column {
                Text(tr("给它起个名字，方便以后认出是谁改的。"), fontSize = 13.sp, color = C.Soft)
                Box(Modifier.padding(top = 10.dp).fillMaxWidth().liquidGlass(RoundedCornerShape(16.dp), fill = Glass.field(), shadow = 0.dp).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (name.isEmpty()) Text(tr("比如：我的 AI 助手"), fontSize = 14.sp, color = C.Faint)
                    BasicTextField(name, { name = it.take(40) }, singleLine = true, cursorBrush = SolidColor(C.Clay), textStyle = TextStyle(fontSize = 14.sp, color = C.Ink), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { Text(tr("创建"), fontSize = 15.sp, color = if (name.isNotBlank()) C.ClayDeep else C.Faint, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = name.isNotBlank()) { onDone(name.trim()) }.padding(horizontal = 12.dp, vertical = 8.dp)) },
        dismissButton = { Text(tr("取消"), fontSize = 15.sp, color = C.Soft,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onCancel() }.padding(horizontal = 12.dp, vertical = 8.dp)) })
}

@Composable
private fun StopDialog(onStop: (Boolean) -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, containerColor = Color.Transparent, tonalElevation = 0.dp, shape = DialogShape, modifier = dialogGlass(),
        title = { Text(tr("停止同步？"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Ink) },
        text = { Text(tr("停止后不再上传，智能体也看不到之后的改动。也可以顺便删掉服务器上的同步账号、全部数据和所有智能体令牌（不能恢复）；手机上的课表和待办不受影响。"),
            fontSize = 14.sp, color = C.Soft, lineHeight = 20.sp) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Text(tr("停止同步"), fontSize = 15.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onStop(false) }.padding(horizontal = 12.dp, vertical = 8.dp))
                Text(tr("停止并删除云端数据"), fontSize = 15.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onStop(true) }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        },
        dismissButton = { Text(tr("取消"), fontSize = 15.sp, color = C.Soft,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onCancel() }.padding(horizontal = 12.dp, vertical = 8.dp)) })
}
