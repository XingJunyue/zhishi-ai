package com.zhishi.timetable

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 教程的一页：标题、说明，open = 「去看看」打开哪一页（同 Nav.open 的写法，比如 "table:1"）。 */
data class TourPage(val mark: String, val title: String, val body: String, val open: String? = null)

/**
 * 应用内教程：
 *  - 首次安装：完整的新手引导（FIRST），可跳过。
 *  - 每次更新：这个版本的新功能（WHATS_NEW），可跳过。记下上次看过的 versionCode 和看过的条目 id。
 *  - 设置里「新手教程」随时重看。
 *
 * 以后每个版本：在 WHATS_NEW 最前面加一条 Release（id 起一个新的、以后不要改），文案六种语言都补上。
 * 规则见仓库根目录 AGENTS.md。
 */
object Tour {
    class Release(val id: String, val pages: List<TourPage>)

    val FIRST = listOf(
        TourPage("知", "欢迎来到知时 AI", "好雨知时节。知时 AI 是知时的 AI 版：一张安静的课表，导入一次，每天打开就知道现在、下一节上什么；再加上一个能帮你动手的 AI 助手。随时可以跳过，之后在「我的」里能重看。"),
        TourPage("智", "AI 助手", "点底部导航条的 ✦ 打开 AI 助手：用一句话加课、改教室、记待办、换皮肤，也能问「明天有什么课」。改动马上生效，改完会列出改了什么，可以全部或只撤销其中几项。先在「我的 → AI 设置」填好你自己的 AI 接口。", "ai_settings"),
        TourPage("导", "从教务导入", "课表右上角点「＋」→「从教务导入」，输入学校名就能填好教务网址和类型，登录后点「导入课表」。也支持日历订阅链接、Excel / CSV、AI 识别截图。", "table:1"),
        TourPage("期", "开学日期", "导入后在「学期与显示」里确认开学日期——第几周、本周有没有这门课都靠它。", "table:5"),
        TourPage("课", "点格子加课，点课块修改", "左右滑切换周。课块上可以标出「重要 / 普通 / 水课」，只是提醒自己的标签，不想要可以关掉。", "table:0"),
        TourPage("办", "待办和日历", "底部「待办」记作业、考试和要交的东西，可以关联某门课、到点提醒；「日历」按月看哪天有课、哪天有待办。", "todo"),
        TourPage("件", "桌面小部件", "4×2 和 2×2 两种：正在上的课、剩余时间和下一节，一眼就看到。在「我的 → 桌面小部件」里一键添加。", "widget"),
        TourPage("皮", "六套插画皮肤", "春雨、宣纸、青瓷、暮山、夜航、樱，每套都有浅色和深色插画，桌面小部件跟着一起换。在「我的」里选。", "settings"),
    )

    /** 新版本在最前面加。id 一旦发出去就不要改（用来记「看过没有」）。 */
    val WHATS_NEW = listOf(
        // 一个版本一条：ai-8 之后的离线语音、MCP 同步、AI 直接生效 + 自定义撤回、待办小部件即时刷新、AI 帮我找课表合在一起（voice / sync 两条没有发到正式版）
        Release("jwai", listOf(
            TourPage("言", "离线语音输入", "AI 助手输入框旁边多了一个麦克风：按住说话，说的话实时变成文字写进输入框，松开结束、上滑取消，改好了再发送。第一次用要下载一个中文语音模型（约 21 MB），之后在手机上离线识别，声音不上传、不保存。模型可以在「我的 → AI 设置」里删除。", "ai_settings"),
            TourPage("联", "连接 AI 智能体（MCP）", "「我的 → AI → 连接 AI 智能体（MCP）」：打开云同步后，任何支持 MCP 的 AI 智能体都能用你给它的令牌读写课表和待办，它改的东西会同步回手机。默认关着，关着时不上传任何数据；令牌随时可以撤销。", "me_mcp"),
            TourPage("撤", "AI 改完直接生效，想撤哪项撤哪项", "AI 助手不再让你一项项确认：说完就改好，最后弹一次「已改好 N 处 · 撤销」。点「撤销」能看到每一处改动的前后对比，勾选要撤的几项，或者全部撤销。智能体同步下来的改动也一样可以撤销。"),
            TourPage("快", "待办小部件立刻更新", "加、改、完成、删除待办（包括 AI、智能体同步、撤销和通知上的「完成」）以后，桌面上的待办小部件马上就变，不用再等。"),
            TourPage("找", "AI 帮我找课表", "从教务导入没成功、或者没找到课表时，点「AI 帮我找课表」：你自己在网页里登录，AI 帮你点到课表页面、读出课表（需要时截图给看图模型看），再进导入预览选「覆盖」或「追加」。网页内容只发给你在「AI 设置」里填的服务商；AI 不能输入任何东西，也不会碰密码框，最多 10 步，随时可以停止。", "table:1"),
        )),
        Release("aichats", listOf(
            TourPage("聊", "AI 对话记录和回复某一条", "和 AI 的每段对话都会存在手机上：在「我的 → AI 对话记录」里点开以前的对话就能接着聊，不要的可以删掉或全部清空。对话里长按或往右滑任意一条消息（你自己的或 AI 的）→「回复」，AI 会知道你说的是哪一条。", "ai_history"),
            TourPage("简", "「我的」更清爽了", "「我的」重新整理成 课表、待办、外观、AI、通用 几组，皮肤和轮换、语言、备份、提醒诊断这些不常改的设置收进了二级页面；AI 助手的 ✦ 挪到了底部导航条正中间。", "me"),
        )),
        Release("ai1", listOf(
            TourPage("智", "知时 AI", "这是知时的 AI 版，可以和知时同时安装：数据、设置、提醒和小部件互不影响。在「我的 → AI 设置」填上你自己的 OpenAI 兼容接口（各家云服务或中转站都行）就能用；截图和对话只发给你填的服务商。", "ai_settings"),
            TourPage("问", "AI 助手", "底部导航条的 ✦ 打开半屏的 AI 助手，往上拖可以全屏。它能加课、改课、挪课、删课，设开学日期和每节课时间，加待办（截止时间、提醒）、完成、置顶、删除，换皮肤、设皮肤轮换和收藏，调小部件外观和显示设置，也能回答课表和待办的问题。所有改动都先出确认卡片，点「确认」才生效，最后一批可以撤销。", "table:0"),
            TourPage("识", "AI 识别截图", "「＋」→「AI 识别截图」（或在 AI 助手里附图）：把课表截图发给你设置的看图模型，认出课名、老师、教室、星期、节次、周次和单双周，进导入预览后选「覆盖」或「追加」。原来的本机文字识别已经去掉。", "table:8"),
        )),
        Release("pinrotate", listOf(
            TourPage("顶", "待办可以置顶、自定义顺序", "编辑待办时打开「置顶」，或者点待办页右上角的排序按钮：点图钉置顶、按住右边的把手上下拖动调整同一组里的顺序。置顶的排在最上面，其余仍按已过期、今天、即将分组，组里按你排的顺序，新加的排在组里最前面。桌面待办小部件的顺序和置顶标记与待办页完全一致。", "todo"),
            TourPage("轮", "皮肤自动轮换", "「我的 → 皮肤」里新增「皮肤轮换」：可以在全部皮肤里随机换，也可以只在收藏的皮肤里轮流换（点缩略图上的 ♡ 收藏）；每次打开、每天或每周换一次，默认每天，桌面小部件一起换。不想要就选「关闭」。", "settings"),
            TourPage("修", "待办小部件更准，桌面更流畅", "修复待办小部件和待办页对不上的问题：数量、没设截止时间的待办和顺序现在都和待办页一致，也不会再出现重复的一行。小部件只在内容、样式或大小真的变了时才重画，并且把连续的刷新合成一次，添加小部件后桌面不再卡顿。", "widget"),
            TourPage("分", "课表和待办小部件分开设置外观", "「我的 → 桌面小部件」里，课表小部件和待办小部件各有一套外观设置：样式（液态玻璃 / 经典）、玻璃颜色（跟随壁纸 / 跟随皮肤）、透明度和深浅（跟随 App / 浅色 / 深色）。改哪一种就只按它自己的设置重画，两种小部件都会及时更新。升级后待办小部件先沿用原来的设置，外观不变。", "widget"),
        )),
        Release("todoscroll", listOf(
            TourPage("滑", "待办小部件可以滑动", "桌面上的待办小部件 4×2 和 2×2 现在可以在小部件里上下滑动，看到所有没做完的待办：过期的在最前，再是今天、之后和没设截止的，每条都带状态标签和关联课程的颜色。点任意一条打开待办页。样式、透明度、皮肤和深色模式都不变。", "widget"),
        )),
        Release("todowidget", listOf(
            TourPage("办", "待办小部件", "新增两个待办小部件 4×2 和 2×2：最近要做的一条在上面，下面是接下来的，过期的排最前。样式、透明度、皮肤和深色模式都和课表小部件一样，添加、完成、删除待办后马上更新，点一下打开待办。在「我的 → 桌面小部件」里预览和添加。", "widget"),
        )),
        Release("skins21", listOf(
            TourPage("皮", "十二套新皮肤", "在「设置 → 皮肤」里新增十二套：银杏、松雪、荷风、竹影、幽兰、青绿、寒梅、柳月、桂香、海棠、檐雨、归雁。每套都有浅色和深色插画，桌面小部件一起换色。", "settings"),
            TourPage("醒", "提醒更清爽", "待办提醒的编辑页去掉了调试用的测试按钮，只留提醒开关、提前时间和提醒时刻。", "todo"),
        )),
        Release("widget19", listOf(
            TourPage("时", "小部件时间不再卡住", "桌面小部件上的「x 分钟后」「还剩」以前常常停着不走，要点进 App 才更新：安卓系统会把不精确的刷新攒着拖很久，各家省电策略还会再拖。现在每分钟按时刷新（息屏时不刷，省电）。如果从后台划掉知时后又停了，在系统设置里允许知时自启动 / 后台活动、电池选不限制。待办的测试通知改成 1 分钟后从后台发出，锁屏也能验证提醒通不通。", "widget"),
        )),
        Release("notify18", listOf(
            TourPage("醒", "待办提醒准时了", "之前在小米等手机上，待办到点常常没有通知：系统默认不给精确闹钟，提醒被省电拖延甚至丢掉。现在改成精确提醒，从后台划掉知时后再打开或开机，错过的提醒会补发。开提醒时可以「发一条测试通知」，收不到就按提示打开自启动、省电选无限制。", "todo"),
        )),
        Release("glass3", listOf(
            TourPage("透", "小部件透明度可以拉满", "「我的 → 桌面小部件」里的透明度上限从 90% 提到 100%：拉满时小部件的底和里面的「下一节」小块保持一致，都和 App 底部导航栏里选中的玻璃滑块一样。亮边、水光和边缘那圈光也照这颗滑块重新做了，整体更像液态玻璃。", "widget"),
        )),
        Release("fix17", listOf(
            TourPage("修", "修复导入课表和从小部件进入时闪退", "今天的课都上完以后，打开 App、点桌面小部件进入，或者导入课表后回到课表页，会直接闪退，而且每次打开都崩，直到清除数据。现在修好了，不用清数据，课表和待办都还在。液态玻璃效果在个别手机上出错时也会自动换回普通画法，不再影响使用。", "table:0"),
        )),
        Release("ink", listOf(
            TourPage("书", "新的书法图标", "桌面图标换成了书法「知时」：宣纸底、墨色字，右下一枚「雨」字朱印（好雨知时节）。安卓 13 及以上开启主题图标时也会跟着系统配色。"),
            TourPage("办", "待办新插图", "待办和课表的空白页换成了一笔水墨圆相，跟着皮肤配色，浅色、深色都好看。", "todo"),
            TourPage("件", "小部件跟随壁纸、透明度可调", "液态玻璃小部件默认「跟随壁纸」：按壁纸的主色自动选深浅、色调和文字颜色，不再像一块灰板；在「我的 → 桌面小部件」里还能拖动「透明度」，也可以改回跟随皮肤。（小部件看不到身后壁纸的像素，这里是配色，不是真的折射。）", "widget"),
        )),
        Release("glass2", listOf(
            TourPage("玻", "全新液态玻璃导航栏和按键", "底部导航栏变成真正的液态玻璃：后面的内容清清楚楚透出来，边缘会折射、有水光；选中的那颗玻璃水滴会滑过去、按住时鼓起来。按钮、切换和小标签也是一样的玻璃，按下去像水滴。（需要安卓 13 及以上，更早的系统保留原来的样子。）", "table:0"),
            TourPage("件", "小部件文字更清晰", "桌面小部件按真实尺寸和屏幕密度绘制，去掉了发虚的字晕；玻璃底稍微实一点，亮边更清楚，在各种壁纸上都读得清。还可以在「我的 → 桌面小部件」里换回改版前的「经典」样式，两种都跟着皮肤换色。", "widget"),
            TourPage("办", "待办时间更精细", "截止时间可以精确到分钟（时、分滚轮），也可以设成全天；有「1 小时后、今晚、明天、下周一、下次上课前」等快捷时间；提醒可以提前 5 分钟到 1 天。过期的待办单独放在「已过期」里。", "todo"),
            TourPage("课", "课表页不再反复弹出导入", "以前从「我的」点过「导入课表」后，每次回到课表页都会再弹一次导入菜单，现在修好了。已经有课时不会再自动弹出。", "table:0"),
        )),
        Release("glass", listOf(
            TourPage("玻", "液态玻璃界面与小部件", "卡片、底部导航、按钮和弹窗都换成了清透的液态玻璃：不模糊，皮肤插画清清楚楚透出来，边上一圈水光。桌面小部件也是透明玻璃，跟着皮肤染色。", "settings"),
            TourPage("导", "修复导入页按钮被导航栏遮挡", "从教务导入时，底部的提示和「导入课表」按钮不会再被导航栏挡住：导入页打开时导航栏自动收起。", "table:1"),
        )),
        Release("redesign", listOf(
            TourPage("新", "全新的样子", "六套皮肤都换成了整屏插画，浅色深色各一张；课表、底部导航和应用图标也都重新设计了。在「我的」里换皮肤。", "settings"),
            TourPage("办", "新增待办", "底部「待办」记作业、考试和要交的东西：可以设截止时间、关联某门课、到点提醒。会一起放进课表备份。", "todo"),
            TourPage("历", "新增日历", "底部「日历」按月看哪天有课、哪天有待办，点一天就列出来。", "calendar"),
            TourPage("件", "小部件跟着新皮肤", "桌面小部件换成新皮肤的颜色，还带一层淡淡的插画；换皮肤马上一起变。", "widget"),
        )),
        Release("skins", listOf(
            TourPage("皮", "六套皮肤", "在「设置 → 皮肤」里选：春雨、宣纸、青瓷、暮山、夜航、樱。每套都有浅色和深色，可以跟随系统。", "settings"),
            TourPage("件", "小部件跟着换色", "换了皮肤或深浅色，桌面上的课表小部件马上一起变，排版和内容不变。", "widget"),
        )),
    )

    /** 正在显示哪一套："first" / "new" / null。 */
    val show = mutableStateOf<String?>(null)

    private fun p(c: Context) = c.getSharedPreferences("tour", Context.MODE_PRIVATE)

    // ---------------- 纯逻辑（单测） ----------------

    /** 第一次跑到有教程的版本：没走过引导的是新装（要看完整引导），走过的是老用户升级（只看新功能）。 */
    fun isFreshInstall(hasTourState: Boolean, guided: Boolean) = !hasTourState && !guided

    /** 升级后要看的新功能：versionCode 比上次看过的大，且还有没看过的条目。 */
    fun pending(lastCode: Int, cur: Int, seen: Set<String>, all: List<Release> = WHATS_NEW): List<Release> =
        if (cur > lastCode) all.filter { it.id !in seen } else emptyList()

    // ---------------- 存储 ----------------

    /** 在 MainActivity.onCreate 里调用一次：第一次装好时记下「新装」，并把已有的新功能都当看过。 */
    fun init(c: Context) {
        val pr = p(c)
        if (pr.contains("code")) return
        val fresh = isFreshInstall(false, false)
        pr.edit().putInt("code", if (fresh) BuildConfig.VERSION_CODE else 0)
            .putBoolean("firstPending", fresh)
            .putStringSet("seen", if (fresh) WHATS_NEW.map { it.id }.toSet() else emptySet()).apply()
    }

    /** 冒烟测试 / 示例数据：全部当看过，不弹。 */
    fun skipAll(c: Context) {
        p(c).edit().putInt("code", BuildConfig.VERSION_CODE).putBoolean("firstPending", false)
            .putStringSet("seen", WHATS_NEW.map { it.id }.toSet()).apply()
    }

    /** 该弹什么就弹什么（在主界面出现时调用）。 */
    fun check(c: Context) {
        if (show.value != null) return
        val pr = p(c)
        if (!pr.contains("code")) init(c)
        if (pr.getBoolean("firstPending", false)) { show.value = "first"; return }
        val seen = pr.getStringSet("seen", emptySet()) ?: emptySet()
        if (pending(pr.getInt("code", 0), BuildConfig.VERSION_CODE, seen).isNotEmpty()) show.value = "new"
        else if (pr.getInt("code", 0) < BuildConfig.VERSION_CODE) pr.edit().putInt("code", BuildConfig.VERSION_CODE).apply()
    }

    fun pages(c: Context, which: String): List<TourPage> = when (which) {
        "first" -> FIRST
        "new" -> {
            val seen = p(c).getStringSet("seen", emptySet()) ?: emptySet()
            WHATS_NEW.filter { it.id !in seen }.flatMap { it.pages }.ifEmpty { WHATS_NEW.firstOrNull()?.pages ?: emptyList() }
        }
        else -> emptyList()
    }

    /** 看完或跳过：都算看过。 */
    fun done(c: Context, which: String) {
        val e = p(c).edit().putInt("code", BuildConfig.VERSION_CODE)
        if (which == "first") e.putBoolean("firstPending", false)
        e.putStringSet("seen", WHATS_NEW.map { it.id }.toSet()).apply()
        show.value = null
    }
}

/** 盖在主界面上的教程卡片：翻页时内容左右滑动，指示点跟着伸缩。 */
@Composable
fun TourHost() {
    val which = Tour.show.value
    val c = LocalContext.current
    AnimatedVisibility(which != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
        val w = which ?: return@AnimatedVisibility
        val pages = remember(w) { Tour.pages(c, w) }
        if (pages.isEmpty()) { LaunchedEffect(w) { Tour.done(c, w) }; return@AnimatedVisibility }
        var i by remember(w) { mutableStateOf(0) }
        BackHandler { if (i > 0) i-- else Tour.done(c, w) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(22.dp).widthIn(max = 420.dp).fillMaxWidth().liquidGlass(RoundedCornerShape(32.dp), Glass.THICK, shadow = 18.dp)) {
                // 顶部的雨色渐变，放大号的字
                Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(C.ClayPale.copy(alpha = 0.7f), Color.Transparent)))) {
                    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (w == "new") tr("这个版本的新功能") else tr("新手教程"), fontSize = 12.sp, color = C.Soft, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                        Text(tr("跳过"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { Tour.done(c, w) }.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                    AnimatedContent(i, transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(tween(300)) { it / 3 * dir } + fadeIn(tween(300))) togetherWith (slideOutHorizontally(tween(240)) { -it / 3 * dir } + fadeOut(tween(200)))
                    }, modifier = Modifier.align(Alignment.Center).padding(top = 18.dp), label = "mark") { k ->
                        val pg = pages[k.coerceIn(0, pages.size - 1)]
                        // 第一页（欢迎）用应用图标，其余页是主色圆里一个字（非中日韩界面也用图标）
                        if (k == 0 || !Tr.cjk) AppIcon(78.dp)
                        else Box(Modifier.size(78.dp).clip(CircleShape).background(C.Clay), contentAlignment = Alignment.Center) {
                            Text(pg.mark, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = C.OnClay)
                        }
                    }
                }
                AnimatedContent(i, transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(300)) { it / 4 * dir } + fadeIn(tween(300))) togetherWith (slideOutHorizontally(tween(240)) { -it / 4 * dir } + fadeOut(tween(200)))
                }, label = "page") { k ->
                    val pg = pages[k.coerceIn(0, pages.size - 1)]
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                        Text(tr(pg.title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.Ink, letterSpacing = 0.5.sp)
                        Text(tr(pg.body), fontSize = 14.sp, color = C.Soft, lineHeight = 22.sp, modifier = Modifier.padding(top = 10.dp).heightIn(min = 88.dp))
                        pg.open?.let { o ->
                            Text(tr("去看看 ›"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).clickable { Tour.done(c, w); Nav.open.value = o }.padding(vertical = 6.dp, horizontal = 2.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 18.dp, top = 14.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pages.indices.forEach { k ->
                            val wd by animateDpAsState(if (k == i) 20.dp else 7.dp, label = "dot")
                            Box(Modifier.size(wd, 7.dp).clip(CircleShape).background(if (k == i) C.Clay else C.Track))
                        }
                    }
                    if (i > 0) Text(tr("上一步"), fontSize = 14.sp, color = C.Soft, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { i-- }.padding(horizontal = 12.dp, vertical = 10.dp))
                    val last = i == pages.size - 1
                    Text(if (last) tr("开始使用") else tr("下一步"), fontSize = 14.sp, color = C.OnClay, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        style = androidx.compose.material3.LocalTextStyle.current.copy(shadow = null),
                        modifier = Modifier.glassButton(CircleShape, tint = C.Clay, fill = C.Clay.copy(alpha = 0.88f), shadow = 0.dp) { if (last) Tour.done(c, w) else i++ }.padding(horizontal = 20.dp, vertical = 11.dp))
                }
            }
        }
    }
}
