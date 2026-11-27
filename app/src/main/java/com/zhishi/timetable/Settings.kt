package com.zhishi.timetable

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 「我的」（2026-11 用户嫌乱，重新设计）：顶部只有图标 + 「我的」（版本号只在「关于与更新」里），
 * 下面五组 课表 / 待办 / 外观 / AI / 通用，每组一张卡片、每项一行（左边一枚淡淡的皮肤色小图标、右边当前值和 ›）；
 * 不常改的设置（皮肤和轮换、语言、备份、提醒诊断、教程）收进二级页面。
 */
@Composable
fun MeScreen(onOpen: (String) -> Unit) {
    val c = LocalContext.current
    var term by remember { mutableStateOf(false) }
    var secs by remember { mutableStateOf(false) }
    var askClean by remember { mutableStateOf(false) }
    // 测试脚本：settings:1 直接打开学期设置，settings:2 每节课时间
    LaunchedEffect(Nav.subState.value) { when (Nav.sub) { 1 -> { term = true; Nav.sub = 0 }; 2 -> { secs = true; Nav.sub = 0 } } }
    val doneN = remember(TodoStore.ver.value) { TodoStore.all(c).count { it.done } }
    val chats = remember(AiChatStore.ver.value) { AiChatStore.all(c).size }
    val ready = remember(Ai.ver.value) { Ai.ready(c) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        // 顶部：图标 + 「我的」+ 一句应用名（不放版本号）
        Row(Modifier.padding(top = 16.dp, start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(52.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(tr("我的"), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                Text(tr("知时 AI"), fontSize = 13.sp, color = C.Soft)
            }
        }

        MeGroup(tr("课表")) {
            MeRow(tr("导入课表"), { Glyph(Icons.Outlined.Add) }) { Nav.open.value = "table:8" }
            MeRow(tr("学期与显示"), { Glyph(Icons.Outlined.DateRange) }) { term = true }
            MeRow(tr("每节课时间"), { ClockGlyph() }) { secs = true }
            MeRow(tr("备份与分享"), { Glyph(Icons.Outlined.Share) }, last = true) { onOpen("me_backup") }
        }
        MeGroup(tr("待办")) {
            MeRow(tr("提醒与通知"), { Glyph(Icons.Outlined.Notifications) }, value = if (TodoAlarm.canNotify(c)) null else tr("未开启")) { onOpen("me_todo") }
            MeRow(tr("清理已完成"), { Glyph(Icons.Outlined.Delete) }, value = if (doneN > 0) tr("{0} 项", doneN) else tr("没有"), last = true) { if (doneN > 0) askClean = true }
        }
        MeGroup(tr("外观")) {
            val sk = Skins.skinState.value
            MeRow(tr("皮肤"), { SkinDot(sk) }, value = tr(sk.name) + " · " + tr(Skins.MODES[Skins.modeState.value.coerceIn(0, 2)])) { onOpen("me_skin") }
            MeRow(tr("桌面小部件"), { WidgetGlyph() }, last = true) { onOpen("widget") }
        }
        MeGroup(tr("AI")) {
            MeRow(tr("AI 助手"), { Tile { SparkleIcon(meAccent(), true, Modifier.size(17.dp)) } }) { AiChat.show("me") }
            MeRow(tr("AI 对话记录"), { BubbleGlyph() }, value = if (chats > 0) tr("{0} 段", chats) else null) { onOpen("ai_history") }
            MeRow(tr("AI 设置"), { Glyph(Icons.Outlined.Settings) }, value = if (ready) remember(Ai.ver.value) { Ai.settings(c).model } else tr("未设置")) { onOpen("ai_settings") }
            val cloud = remember(CloudSync.ver.value) { CloudSync.enabled(c) }
            MeRow(tr("连接 AI 智能体（MCP）"), { Glyph(Icons.Outlined.Refresh) }, value = if (cloud) tr("已开启") else tr("未开启"), last = true) { onOpen("me_mcp") }
        }
        MeGroup(tr("通用")) {
            val lang = remember { Tr.chosen(c) }
            MeRow(tr("语言"), { TextGlyph("文") }, value = if (lang.isEmpty()) tr("跟随系统") else Tr.NAMES.firstOrNull { it.first == lang }?.second) { onOpen("me_lang") }
            MeRow(tr("帮助与教程"), { TextGlyph("?") }) { onOpen("me_help") }
            MeRow(tr("关于与更新"), { Glyph(Icons.Outlined.Info) }, last = true) { onOpen("about") }
        }
        Spacer(Modifier.height(130.dp))
    }
    if (term) TermSheet(onChange = {}, onClose = { term = false })
    if (secs) SecTimesSheet(onChange = {}, onClose = { secs = false })
    if (askClean) ConfirmDialog(tr("清理已完成的待办？"), tr("会删掉 {0} 条已完成的待办，没完成的不动。", doneN), tr("清理"),
        onYes = { TodoStore.all(c).filter { it.done }.forEach { TodoAlarm.cancel(c, it.id) }; TodoStore.save(c, TodoStore.all(c).filter { !it.done }); askClean = false },
        onNo = { askClean = false })
}

/** 「我的」里的图标色：浅色用皮肤的深主色，深色提亮一点。 */
@Composable
fun meAccent(): Color = if (C.dark) androidx.compose.ui.graphics.lerp(C.Clay, Color.White, 0.45f) else C.ClayDeep

/** 一组：小标题 + 一张卡片。 */
@Composable
private fun MeGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.Soft, letterSpacing = 1.sp, modifier = Modifier.padding(top = 22.dp, start = 6.dp, bottom = 8.dp))
    SoftCard(Modifier.fillMaxWidth(), pad = 4.dp, content = content)
}

/** 一行：左边小图标、名字、右边当前值（可选）和 ›；行之间一道很淡的细线（从文字处开始）。 */
@Composable
private fun MeRow(t: String, icon: @Composable () -> Unit, value: String? = null, warm: Boolean = false, last: Boolean = false, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { onClick() }.padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            icon()
            Text(t, fontSize = 15.sp, color = C.Ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp).weight(1f))
            if (value != null) Text(value, fontSize = 13.sp, color = if (warm) C.ClayDeep else C.Faint, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 170.dp))
            Text("›", fontSize = 20.sp, color = C.Faint, modifier = Modifier.padding(start = 8.dp))
        }
        if (!last) Box(Modifier.padding(start = 58.dp, end = 14.dp).fillMaxWidth().height(0.6.dp).background(C.Track.copy(alpha = if (C.dark) 0.35f else 0.55f)))
    }
}

/** 小图标的底：圆角方块，淡淡染一层皮肤主色。 */
@Composable
private fun Tile(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(C.Clay.copy(alpha = if (C.dark) 0.22f else 0.13f)), contentAlignment = Alignment.Center, content = content)
}
@Composable private fun Glyph(v: androidx.compose.ui.graphics.vector.ImageVector) = Tile { Icon(v, null, tint = meAccent(), modifier = Modifier.size(18.dp)) }
@Composable private fun TextGlyph(t: String) = Tile { Text(t, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = meAccent(), style = androidx.compose.material3.LocalTextStyle.current.copy(shadow = null)) }
/** 当前皮肤的小圆缩略图。 */
@Composable private fun SkinDot(s: Skin) = Box(Modifier.size(32.dp).clip(CircleShape).background(Color((if (C.dark) s.dark else s.light).bg))) {
    val r = s.thumb(C.dark)
    if (r != 0) Image(androidx.compose.ui.res.painterResource(r), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}
@Composable private fun ClockGlyph() = Tile {
    val col = meAccent()
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val w = size.width; val st = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.1f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(col, w * 0.42f, style = st)
        drawLine(col, center, androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.24f), w * 0.1f, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(col, center, androidx.compose.ui.geometry.Offset(w * 0.7f, w * 0.6f), w * 0.1f, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
@Composable private fun WidgetGlyph() = Tile {
    val col = meAccent()
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val w = size.width; val g = w * 0.12f; val k = (w - g) / 2 - w * 0.04f; val r = androidx.compose.ui.geometry.CornerRadius(w * 0.1f)
        val st = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.09f)
        for (x in 0..1) for (y in 0..1) drawRoundRect(col, androidx.compose.ui.geometry.Offset(w * 0.04f + x * (k + g), w * 0.04f + y * (k + g)), androidx.compose.ui.geometry.Size(k, k), r, style = st)
    }
}
@Composable private fun BubbleGlyph() = Tile {
    val col = meAccent()
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val w = size.width; val st = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.09f, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val p = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(w * 0.06f, w * 0.1f, w * 0.94f, w * 0.72f, androidx.compose.ui.geometry.CornerRadius(w * 0.2f)))
        }
        drawPath(p, col, style = st)
        val tail = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.28f, w * 0.7f); lineTo(w * 0.22f, w * 0.92f); lineTo(w * 0.48f, w * 0.72f) }
        drawPath(tail, col, style = st)
        for (k in 0..2) drawCircle(col, w * 0.055f, androidx.compose.ui.geometry.Offset(w * (0.32f + 0.18f * k), w * 0.41f))
    }
}

/** 「我的 → 皮肤」：皮肤（缩略图 + ♡ 收藏）、深浅、皮肤轮换。 */
@Composable
fun MeSkinScreen() {
    val c = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Text(tr("界面和桌面小部件一起换"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 12.dp, start = 6.dp, bottom = 10.dp))
        SoftCard(Modifier.fillMaxWidth(), pad = 14.dp) { SkinPicker() }
        MeLabel(tr("深浅"))
        Segments(Skins.MODES.map { tr(it) }, Skins.modeState.value) { Skins.setMode(c, it) }
        SoftCard(Modifier.fillMaxWidth().padding(top = 18.dp), pad = 14.dp) { SkinRotationSettings() }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun MeLabel(t: String) = Text(t, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.Soft, letterSpacing = 1.sp, modifier = Modifier.padding(top = 20.dp, start = 6.dp, bottom = 8.dp))

/** 「我的 → 提醒与通知」：通知权限、1 分钟后的测试通知、系统应用设置（自启动 / 后台 / 电池）、提醒诊断。 */
@Composable
fun MeTodoScreen() {
    val c = LocalContext.current
    var ver by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var diag by remember { mutableStateOf<String?>(null) }
    val perm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ver++ }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) ver++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val can = remember(ver) { TodoAlarm.canNotify(c) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Text(tr("待办到点提醒用系统通知；收不到时按下面一项项检查。"), fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp, start = 6.dp, end = 6.dp, bottom = 10.dp))
        SoftCard(Modifier.fillMaxWidth(), pad = 4.dp) {
            MeRow(tr("通知权限"), { Glyph(Icons.Outlined.Notifications) }, value = if (can) tr("已开启") else tr("去开启"), warm = !can) {
                if (!can && android.os.Build.VERSION.SDK_INT >= 33) perm.launch(android.Manifest.permission.POST_NOTIFICATIONS) else openAppSettings(c)
            }
            MeRow(tr("1 分钟后发测试通知"), { ClockGlyph() }) {
                if (!can && android.os.Build.VERSION.SDK_INT >= 33) perm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                TodoAlarm.test(c); msg = tr("1 分钟后会收到一条测试通知，可以先回到桌面或锁屏等着")
            }
            MeRow(tr("系统应用设置"), { Glyph(Icons.Outlined.Settings) }, value = tr("自启动、后台、电池")) { openAppSettings(c) }
            MeRow(tr("提醒诊断"), { Glyph(Icons.Outlined.Info) }, last = true) { diag = TodoAlarm.report(c) }
        }
        msg?.let { Note(it) }
        diag?.let { d ->
            SoftCard(Modifier.fillMaxWidth().padding(top = 14.dp), pad = 14.dp) {
                Text(d, fontSize = 12.sp, color = C.Soft, lineHeight = 18.sp)
            }
        }
        Note(tr("不少安卓手机划掉 App 后会拦住后台提醒：在系统设置里给知时 AI 允许「自启动 / 后台活动」，电池使用选「不限制」。"))
        Spacer(Modifier.height(40.dp))
    }
}

/** 打开系统里这个 App 的设置页。 */
fun openAppSettings(c: android.content.Context) {
    try { c.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + c.packageName))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (x: Exception) {}
}

/** 「我的 → 备份与分享」：把课表备份（含待办）发出去，或存成文件。 */
@Composable
fun MeBackupScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    val saveFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { c.contentResolver.openOutputStream(u)?.use { it.write(Term.exportBackup(c).toByteArray()) }; true } catch (x: Exception) { false } }
            msg = if (ok) tr("已导出课表备份") else tr("导出失败")
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Text(tr("备份里有课表、每节课时间、开学日期和待办；在「导入课表 → 备份」里恢复。"), fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp,
            modifier = Modifier.padding(top = 12.dp, start = 6.dp, end = 6.dp, bottom = 10.dp))
        SoftCard(Modifier.fillMaxWidth(), pad = 4.dp) {
            MeRow(tr("分享课表备份"), { Glyph(Icons.Outlined.Share) }, value = tr("发给自己或同学")) { msg = shareBackup(c) }
            MeRow(tr("导出到文件"), { Glyph(Icons.Outlined.Add) }, value = ".json", last = true) { saveFile.launch("zhishi-timetable.json") }
        }
        msg?.let { Note(it) }
        Spacer(Modifier.height(40.dp))
    }
}

/** 「我的 → 语言」：跟随系统 + 六种语言，选了就重启界面。 */
@Composable
fun MeLangScreen() {
    val c = LocalContext.current
    val cur = remember { Tr.chosen(c) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(12.dp))
        SoftCard(Modifier.fillMaxWidth(), pad = 4.dp) {
            val all = listOf("" to tr("跟随系统")) + Tr.NAMES
            all.forEachIndexed { i, (code, name) ->
                val on = cur == code
                Column {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { if (!on) Tr.set(c, code) }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(name, fontSize = 15.sp, color = C.Ink, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        if (on) Text("✓", fontSize = 16.sp, color = meAccent(), fontWeight = FontWeight.Bold)
                    }
                    if (i < all.size - 1) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(0.6.dp).background(C.Track.copy(alpha = if (C.dark) 0.35f else 0.55f)))
                }
            }
        }
        Note(tr("换语言后界面会重新打开一次。"))
        Spacer(Modifier.height(40.dp))
    }
}

/** 「我的 → 帮助与教程」。 */
@Composable
fun MeHelpScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(12.dp))
        SoftCard(Modifier.fillMaxWidth(), pad = 4.dp) {
            MeRow(tr("新手教程"), { TextGlyph("知") }, value = tr("再看一遍主要功能")) { Tour.show.value = "first" }
            MeRow(tr("这个版本的新功能"), { Glyph(Icons.Outlined.Star) }, last = true) { Tour.show.value = "new" }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** 把课表备份（含待办）发出去；失败返回提示文字。 */
fun shareBackup(c: android.content.Context): String? = try {
    val dir = java.io.File(c.cacheDir, "share").apply { mkdirs() }
    val f = java.io.File(dir, "zhishi-timetable.json"); f.writeText(Term.exportBackup(c))
    val uri = androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".files", f)
    c.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/json")
        .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), tr("分享课表"))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    null
} catch (x: Exception) { tr("分享失败") }

/** 皮肤选择：每套一个圆形的插画缩略图（深浅色跟当前模式），下面是名字；选中的套一圈主色。下面一行是这套皮肤的一句话。 */
@Composable
fun SkinPicker() {
    val c = LocalContext.current
    val cur = Skins.skinState.value
    val dark = C.dark
    val favs = remember(SkinRotation.ver.value) { SkinRotation.favs(c) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (s in Skins.ALL) {
            val on = s.id == cur.id
            val p = if (dark) s.dark else s.light
            val ring by animateColorAsState(if (on) Color(p.acc) else Color.Transparent, label = "ring")
            Column(Modifier.width(52.dp).clip(RoundedCornerShape(16.dp)).clickable { Skins.setSkin(c, s) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(50.dp)) {
                    Box(Modifier.fillMaxSize().border(2.dp, ring, CircleShape).padding(3.5.dp).clip(CircleShape).background(Color(p.bg))) {
                        val r = s.thumb(dark)
                        if (r != 0) Image(androidx.compose.ui.res.painterResource(r), tr(s.name), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    // 收藏：右下角的小爱心，点一下收藏 / 取消（「收藏轮换」只在收藏的皮肤里换）
                    val fav = s.id in favs
                    Box(Modifier.align(Alignment.BottomEnd).offset(x = 1.dp, y = 1.dp).size(21.dp).clip(CircleShape)
                        .background(C.Card.copy(alpha = 0.92f)).clickable { SkinRotation.toggleFav(c, s.id) }, contentAlignment = Alignment.Center) {
                        Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (fav) tr("取消收藏") else tr("收藏"),
                            tint = if (fav) Color(0xFFE0607A) else C.Faint, modifier = Modifier.size(13.dp))
                    }
                }
                Text(tr(s.name), fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) C.Ink else C.Soft,
                    maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
    Text(tr(cur.name) + " · " + tr(cur.poem), fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

/** 小部件页：课表、待办小部件（各 4×2 / 2×2）的样子，和「添加到桌面」。 */
@Composable
fun WidgetScreen() {
    val c = LocalContext.current
    val am = remember { AppWidgetManager.getInstance(c) }
    val canPin = remember { am?.isRequestPinAppWidgetSupported == true }
    var tip by remember { mutableStateOf<String?>(null) }
    fun pin(cls: Class<*>) {
        val ok = try { am?.requestPinAppWidget(ComponentName(c, cls), null, null) == true } catch (x: Exception) { false }
        if (!ok) tip = tr("这个桌面不支持一键添加：长按桌面空白处 → 小部件 → 找到「知时 AI」")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text(tr("上面是正在上的课（课间就是最近的下一节），下面是再下一节；今天没课就是一张白卡。"),
            fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp))
        // 课表、待办小部件的外观各自设置（样式、玻璃颜色、透明度、深浅），改哪种就按哪种的设置重画
        SectionTitle(tr("课表小部件"), tr("外观和待办小部件分开设置"))
        WidgetLook(WidgetStyle.COURSE)
        WidgetCard(tr("课表 4×2"), true, canPin) { pin(CourseWidget4::class.java) }
        WidgetCard(tr("课表 2×2"), false, canPin) { pin(CourseWidget2::class.java) }
        SectionTitle(tr("待办小部件"), tr("外观和课表小部件分开设置"))
        Note(tr("待办小部件：在小部件里上下滑动就能看到全部待办；按截止时间排，过期的在最前。点任意一条打开待办，添加、完成、删除待办后马上更新。"))
        WidgetLook(WidgetStyle.TODO)
        WidgetCard(tr("待办 4×2"), true, canPin, todo = true) { pin(TodoWidget4::class.java) }
        WidgetCard(tr("待办 2×2"), false, canPin, todo = true) { pin(TodoWidget2::class.java) }
        Note(tr("桌面小部件看不到身后壁纸的像素，做不了真正的折射；这里是按壁纸配色，让玻璃和壁纸更搭。"))
        tip?.let { Note(it) }
        Note(tr("小部件上的「重要 / 普通 / 水课」标签可以在「设置 → 学期与显示」里关掉。"))
        Note(tr("小部件上的时间不走？在系统设置里给知时允许「自启动 / 后台活动」，电池使用选「不限制」：不少安卓手机划掉 App 后会停掉小部件刷新。"))
        Spacer(Modifier.height(40.dp))
    }
}

/** 一种小部件（课表 / 待办）的外观：样式、玻璃颜色、透明度、深浅。 */
@Composable
private fun WidgetLook(kind: String) {
    val c = LocalContext.current
    val sv = WidgetStyle.ver.value
    val style = remember(sv) { WidgetStyle.get(c, kind) }
    Text(tr("小部件样式"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    Segments(WidgetStyle.ALL.map { tr(it.second) }, WidgetStyle.ALL.indexOfFirst { it.first == style }.coerceAtLeast(0)) { i -> WidgetStyle.set(c, WidgetStyle.ALL[i].first, kind) }
    val tint = remember(sv) { WidgetStyle.tint(c, kind) }
    if (style == WidgetStyle.GLASS) {
        // 液态玻璃：颜色跟随壁纸 / 皮肤，透明度可调（拖动时预览跟着变，松手才写入并重画桌面上的小部件）
        Text(tr("玻璃颜色"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp))
        Segments(WidgetStyle.TINTS.map { tr(it.second) }, WidgetStyle.TINTS.indexOfFirst { it.first == tint }.coerceAtLeast(0)) { i -> WidgetStyle.setTint(c, WidgetStyle.TINTS[i].first, kind) }
        if (tint == WidgetStyle.WALL && remember(sv) { WallTint.read(c) } == null)
            Note(tr("这台手机没有提供壁纸颜色（Android 8.1 以下或动态壁纸），先按皮肤配色。"))
        val clear = remember(sv) { WidgetStyle.clear(c, kind) }
        var moved by remember { mutableStateOf(0) }
        LaunchedEffect(moved) { if (moved > 0) { kotlinx.coroutines.delay(400); CourseWidgets.safeUpdate(c) } }
        Column(Modifier.padding(top = 4.dp)) { StepSlider(tr("透明度"), clear, 0..WallTint.MAX, 5, "%") { if (it != clear) { WidgetStyle.setClear(c, it, redraw = false, kind = kind); moved++ } } }
    }
    // 深浅：跟随 App / 浅色 / 深色（跟随壁纸的玻璃按壁纸自动选深浅）
    if (style == WidgetStyle.CLASSIC || tint == WidgetStyle.SKIN) {
        val mode = remember(sv) { WidgetStyle.mode(c, kind) }
        Text(tr("深浅"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp))
        Segments(listOf(tr("跟随 App"), tr("浅色"), tr("深色")), mode) { WidgetStyle.setMode(c, it, kind) }
    } else Text(tr("跟随壁纸时，深浅按壁纸自动选"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
private fun WidgetCard(title: String, wide: Boolean, canPin: Boolean, todo: Boolean = false, onPin: () -> Unit) {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    Every(30_000) { tick++ }
    val bmp = remember(tick, Skins.skinState.value, C.dark, WidgetStyle.ver.value, WallTint.ver.value, TodoStore.ver.value) {
        try { (if (todo) CourseWidgets.previewTodo(c, wide) else CourseWidgets.preview(c, wide)).asImageBitmap() } catch (x: Exception) { null }
    }
    // 跟随壁纸时：预览底下垫一块按壁纸主色做的渐变（App 读不到壁纸本身，只能用它的颜色模拟）
    val wall = remember(WidgetStyle.ver.value, WallTint.ver.value) {
        val k = if (todo) WidgetStyle.TODO else WidgetStyle.COURSE
        if (!WidgetStyle.classic(c, k) && WidgetStyle.followWall(c, k)) WallTint.read(c) else null
    }
    SoftCard(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.weight(1f))
            if (canPin) Text(tr("添加到桌面"), fontSize = 13.sp, color = C.OnClay, fontWeight = FontWeight.SemiBold,
                style = androidx.compose.material3.LocalTextStyle.current.copy(shadow = null),
                modifier = Modifier.glassButton(CircleShape, tint = C.Clay, fill = C.Clay.copy(alpha = 0.80f), shadow = 0.dp) { onPin() }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        // 小部件本身是透明的玻璃：直接放在插画背景上预览，和放在桌面壁纸上一样
        val under = if (wall == null) Modifier else Modifier.clip(RoundedCornerShape(20.dp)).background(
            androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(wall.primary), Color(wall.secondary ?: wall.primary), Color(wall.tertiary ?: wall.secondary ?: wall.primary))))
        Box(Modifier.fillMaxWidth().padding(top = 14.dp).then(under).padding(if (wall == null) 4.dp else 12.dp), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp, null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(if (wide) 1f else 0.5f).aspectRatio(if (wide) 320f / 156f else 1f))
        }
    }
}

/** 皮肤轮换：关闭 / 全部随机 / 收藏轮换，多久换一次（每次打开 / 每天 / 每周）。界面和桌面小部件一起换。 */
@Composable
fun SkinRotationSettings() {
    val c = LocalContext.current
    val v = SkinRotation.ver.value
    val mode = remember(v) { SkinRotation.mode(c) }
    val every = remember(v) { SkinRotation.every(c) }
    val favN = remember(v) { SkinRotation.favs(c).size }
    Text(tr("皮肤轮换"), fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp, start = 4.dp))
    Text(tr("自动换皮肤，桌面小部件一起换；点缩略图上的 ♡ 收藏"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp, start = 4.dp))
    Segments(listOf(tr("关闭"), tr("全部随机"), tr("收藏轮换")), mode) { SkinRotation.setMode(c, it) }
    if (mode != SkinRotation.OFF) {
        Spacer(Modifier.height(8.dp))
        Segments(listOf(tr("每次打开"), tr("每天"), tr("每周")), every) { SkinRotation.setEvery(c, it) }
        if (mode == SkinRotation.FAV && favN < 2) Note(tr("先在上面收藏至少两套皮肤（点 ♡），才能在收藏里轮换"), warm = true)
    }
}
