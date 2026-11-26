package com.zhishi.timetable

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 外部要求打开某一页（小部件点进来、测试脚本）："table:1" = 课表页的第 1 个动作。 */
object Nav {
    val open = mutableStateOf<String?>(null)
    /** 已经处理过的跳转（不再触发任何动作）。 */
    const val NONE = -1
    val subState = mutableStateOf(0)
    var sub: Int get() = subState.value; set(v) { subState.value = v }
    /** 有整屏流程打开着（教务导入等）：底部导航条收起来，免得盖住页面底部的按钮。 */
    val fullScreen = mutableStateOf(0)
}

/** 整屏流程用：显示期间把底部导航条藏起来。 */
@Composable
fun HideBottomBar() {
    DisposableEffect(Unit) { Nav.fullScreen.value++; onDispose { Nav.fullScreen.value = (Nav.fullScreen.value - 1).coerceAtLeast(0) } }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        Skins.init(this)
        // 皮肤轮换「每次打开」：冷启动时换一套（转屏等重建不算）
        if (b == null) try { SkinRotation.onLaunch(this) } catch (x: Throwable) {}
        super.onCreate(b)
        if (BuildConfig.HOOKS) TestHooks.run(this, intent)
        Tour.init(this)
        TodoAlarm.rescheduleAll(this)
        setContent {
            val dark = C.dark
            val bg = C.Cream.toArgb()
            LaunchedEffect(dark, bg) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.Transparent.toArgb()) else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb()),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.Transparent.toArgb()) else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
                )
            }
            ZhishiTheme { Box(Modifier.fillMaxSize().background(C.Cream)) { App(); TourHost() } }
        }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        if (BuildConfig.HOOKS) TestHooks.run(this, i)
        i.getStringExtra("open")?.let { Nav.open.value = it }
    }

    override fun onResume() {
        super.onResume()
        Skins.sysDarkState.value = Skins.sysDark(this)
        // 皮肤轮换（每次打开 / 每天 / 每周）
        try { SkinRotation.onTick(this) } catch (x: Throwable) {}
        CourseWidgets.refresh(this)
    }
}

/** 底部四个标签页；widget / about 是从「我的」点进去的二级页。settings 是「我的」的旧名字（测试脚本、教程还在用）。 */
private val TABS = listOf("table", "todo", "calendar", "me")
private val SCREENS = TABS + listOf("widget", "about", "settings", "ai_settings", "ai_history", "ai_convo", "me_skin", "me_todo", "me_backup", "me_lang", "me_help")
/** 二级页面返回到哪一页。 */
private fun parentOf(s: String) = when (s) { "ai_convo" -> "ai_history"; else -> "me" }
private fun norm(s: String?) = when (s) { "settings" -> "me"; in SCREENS -> s; else -> null }

@Composable
fun App() {
    val ctx = LocalContext.current
    val openExtra = remember { (ctx as? android.app.Activity)?.intent?.getStringExtra("open") }
    var screen by remember {
        mutableStateOf(openExtra?.let { Nav.sub = it.substringAfter(':', "0").toIntOrNull() ?: 0; norm(it.substringBefore(':')) } ?: "table")
    }
    val req = Nav.open.value
    LaunchedEffect(req) {
        if (req != null) {
            Nav.sub = req.substringAfter(':', "0").toIntOrNull() ?: 0
            screen = norm(req.substringBefore(':')) ?: "table"
            Nav.open.value = null
        }
    }
    val sub = screen !in TABS
    BackHandler(enabled = sub) { screen = parentOf(screen) }
    BackHandler(enabled = !sub && screen != "table") { screen = "table" }
    if (Updater.enabled()) UpdatePrompt()
    LaunchedEffect(Unit) { Tour.check(ctx) }
    val artSrc = rememberGlassSource()
    val pageSrc = rememberGlassSource()
    Box(Modifier.fillMaxSize()) {
      // 整页（插画 + 页面）录下来给底部导航条折射；插画单独再录一份给页面里的按钮折射
      Box(Modifier.fillMaxSize().glassSource(pageSrc)) {
       CompositionLocalProvider(LocalGlassArt provides artSrc) {
        Backdrop(Modifier.glassSource(artSrc))
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
            label = "screen"
        ) { s ->
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                when (s) {
                    "table" -> TimetableScreen()
                    "todo" -> TodoScreen()
                    "calendar" -> CalendarScreen()
                    "me" -> MeScreen(onOpen = { screen = it })
                    "widget" -> Paged({ screen = "me" }, tr("小部件")) { WidgetScreen() }
                    "about" -> Paged({ screen = "me" }, tr("关于")) { AboutScreen() }
                    "ai_settings" -> Paged({ screen = "me" }, tr("AI 设置")) { AiSettingsScreen() }
                    "ai_history" -> Paged({ screen = "me" }, tr("AI 对话记录")) { AiHistoryScreen(onOpen = { Nav.sub = 0; screen = "ai_convo" }) }
                    "ai_convo" -> AiConvoScreen(onBack = { screen = "ai_history" })
                    "me_skin" -> Paged({ screen = "me" }, tr("皮肤")) { MeSkinScreen() }
                    "me_todo" -> Paged({ screen = "me" }, tr("提醒与通知")) { MeTodoScreen() }
                    "me_backup" -> Paged({ screen = "me" }, tr("备份与分享")) { MeBackupScreen() }
                    "me_lang" -> Paged({ screen = "me" }, tr("语言")) { MeLangScreen() }
                    "me_help" -> Paged({ screen = "me" }, tr("帮助与教程")) { MeHelpScreen() }
                }
            }
        }
       }
      }
        AnimatedVisibility(!sub && Nav.fullScreen.value == 0 && !AiChat.open.value, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(180)) + slideInVertically { it / 2 }, exit = fadeOut(tween(120)) + slideOutVertically { it / 2 }) {
            CompositionLocalProvider(LocalGlassPage provides pageSrc) { BottomBar(screen, Modifier) { if (it == "ai") AiChat.show(screen) else screen = it } }
        }
        // AI 助手：从导航条的 ✦ 打开的半屏面板；整屏流程（教务导入等）打开时不显示
        if (Nav.fullScreen.value == 0) AiSheetHost()
    }
}

/**
 * 底部液态玻璃导航条：课表 / 待办 / ✦ AI（正中间，打开 AI 面板）/ 日历 / 我的。
 * API 33+：整条是一块透镜，把后面的页面原样（不模糊）透出来，边缘折射、带色散和跟光走的亮边；
 * 选中的那个下面是一颗玻璃水滴，切换时弹簧滑过去，跑起来横向拉长、到位轻轻晃一下，按住时鼓起来变亮。
 * 更低的版本：老的半透明底 + 亮边 + 顶部水光。
 */
@Composable
private fun BottomBar(cur: String, m: Modifier, go: (String) -> Unit) {
    val items = listOf(
        Triple("table", tr("课表"), Icons.Filled.Home to Icons.Outlined.Home),
        Triple("todo", tr("待办"), Icons.Filled.CheckCircle to Icons.Outlined.CheckCircle),
        // AI 入口在正中间：不是页面，点了打开 AI 面板
        Triple("ai", tr("AI 助手"), Icons.Filled.Star to Icons.Outlined.Star),
        Triple("calendar", tr("日历"), Icons.Filled.DateRange to Icons.Outlined.DateRange),
        Triple("me", tr("我的"), Icons.Filled.AccountCircle to Icons.Outlined.AccountCircle),
    )
    val page = LocalGlassPage.current
    val lensOn = GlassLens.supported && !GlassLens.broken && page?.layer != null
    val dark = C.dark
    val idx = items.indexOfFirst { it.first == cur }.let { if (it < 0) 0 else it }
    val pos = remember { androidx.compose.animation.core.Animatable(idx.toFloat()) }
    LaunchedEffect(idx) { pos.animateTo(idx.toFloat(), androidx.compose.animation.core.spring(dampingRatio = 0.58f, stiffness = 260f)) }
    val sources = remember { List(items.size) { androidx.compose.foundation.interaction.MutableInteractionSource() } }
    val pressedStates = sources.map { it.collectIsPressedAsState() }
    val anyPressed = pressedStates.any { it.value }
    val press by androidx.compose.animation.core.animateFloatAsState(if (anyPressed) 1f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 420f), label = "press")
    // 透明的条：深浅皮肤用不同的字色 + 柔和的投影，压在任何内容上都读得清
    val ink = if (dark) Color.White.copy(alpha = 0.92f) else lerp(C.Ink, Color.Black, 0.15f)
    val sel = if (dark) lerp(C.Clay, Color.White, 0.45f) else C.ClayDeep
    val textShadow = androidx.compose.ui.graphics.Shadow(
        if (dark) Color.Black.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.95f), androidx.compose.ui.geometry.Offset(0f, 1f), 6f)
    val shape = CircleShape
    val barMod = if (lensOn) Modifier.liquidGlass(shape, fill = if (dark) Color.Black.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.08f), shadow = 10.dp,
        lens = LensSpec(cover = true, band = 16.dp, strength = 0.38f, chroma = 0.07f, spec = 1.1f, glow = 0.08f,
            tint = if (dark) lerp(C.Card, C.Clay, 0.10f).copy(alpha = 0.12f) else lerp(Color.White, C.Clay, 0.08f).copy(alpha = 0.10f)), source = page)
        else Modifier.liquidGlass(shape, Glass.BAR, fill = Glass.bar(), shadow = 10.dp)
    BoxWithConstraints(m.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth().then(barMod).padding(horizontal = 6.dp, vertical = 6.dp)) {
        val tabW = maxWidth / items.size
        // 水滴：跟着 pos 滑，速度越快横向越长、竖向越扁
        Box(Modifier.matchParentSize().wrapContentWidth(Alignment.Start)) {
            Box(Modifier.width(tabW).fillMaxHeight()
                .offset { androidx.compose.ui.unit.IntOffset((pos.value * tabW.toPx()).toInt(), 0) }
                .graphicsLayer {
                    val st = (kotlin.math.abs(pos.velocity) * 0.09f).coerceAtMost(0.42f)
                    scaleX = 1f + st + 0.09f * press; scaleY = 1f - st * 0.28f + 0.07f * press
                }
                .then(if (lensOn) Modifier.liquidGlass(shape, fill = if (dark) Color.White.copy(alpha = 0.10f) else lerp(Color.White, C.Clay, 0.10f).copy(alpha = 0.30f),
                    shadow = 3.dp, press = press,
                    lens = LensSpec(cover = true, strength = 0.32f, chroma = 0.10f, mag = 0.06f, spec = 1.3f, glow = 0.12f), source = page)
                else Modifier.liquidGlass(shape, fill = if (dark) Color.White.copy(alpha = 0.13f) else lerp(Color.White, C.Clay, 0.12f).copy(alpha = 0.85f), shadow = 0.dp, press = press)))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { i, (id, label, icons) ->
                val on = id == cur
                val tint by animateColorAsState(if (on) sel else ink, label = "tab")
                if (id == "ai") {
                    // 正中间的 ✦：一颗淡淡染了皮肤主色的小玻璃圆，里面是主色的两颗星；不带文字，不抢眼
                    Box(Modifier.weight(1f).clip(CircleShape).clickable(interactionSource = sources[i], indication = null) { go(id) }
                        .semantics { contentDescription = label }.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(46.dp).liquidGlass(CircleShape, tint = C.Clay, fill = if (dark) lerp(C.Card, C.Clay, 0.28f).copy(alpha = 0.55f) else lerp(Color.White, C.Clay, 0.16f).copy(alpha = 0.62f),
                            shadow = 2.dp, press = press * (if (pressedStates[i].value) 1f else 0f)), contentAlignment = Alignment.Center) {
                            SparkleIcon(textShadow.color.copy(alpha = 0.45f), true, Modifier.size(22.dp).offset(y = 1.dp))
                            SparkleIcon(sel, true, Modifier.size(22.dp))
                        }
                    }
                } else Column(Modifier.weight(1f).clip(CircleShape).clickable(interactionSource = sources[i], indication = null) { go(id) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        // 图标的柔和投影：同一个图标在下面错开 1px、用投影色画一遍
                        Icon(if (on) icons.first else icons.second, null, tint = textShadow.color.copy(alpha = 0.5f), modifier = Modifier.size(24.dp).offset(y = 1.dp))
                        Icon(if (on) icons.first else icons.second, null, tint = tint, modifier = Modifier.size(24.dp))
                    }
                    Text(label, fontSize = 11.sp, color = tint, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                        style = LocalTextStyle.current.copy(shadow = textShadow), modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** 二级页面：左上返回 + 粗体大标题 + 内容。 */
@Composable
fun Paged(onBack: () -> Unit, title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 18.dp, top = 12.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(start = 14.dp))
        }
        content()
    }
}
