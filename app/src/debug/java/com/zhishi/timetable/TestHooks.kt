package com.zhishi.timetable

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File
import kotlinx.coroutines.launch

/**
 * 只在调试版里有：自动化测试用。
 *  --ez seed true   跳过引导，放几节示例课程
 *  --es wtint wall|skin / --ei wclear 0..100   小部件玻璃颜色来源 / 透明度
 *  --es wallpaper dark|light   把模拟器壁纸换成一张深色 / 浅色的渐变图（只有调试版有 SET_WALLPAPER）
 *  --es seedat before|between|after|ending|offday|tiny   崩溃排查：按「现在」造今天的课（第一节还没开始 / 课间 / 今天的课都上完 /
 *                    一分钟后下课 / 今天没课 / 极短的课），并放各种边界待办（全天提醒、提前量、关联到今天已没课的课程、没日期却开着提醒）
 *  --ez notodos true 清空待办（截待办空状态；再 --ez seed true 会放回示例待办）
 *  --es skin <id> / --ei mode 0|1|2   换皮肤 / 深浅色（截图用）
 *  --es wstyle glass|classic   小部件样式
 *  --ez wrows true   待办小部件的滑动列表：直接跑 TodoRows（RemoteViewsFactory）取每一行、在本进程里 inflate，结果打到 logcat（WROWS ok / FAIL），
 *                    再把待办小部件预览（示例待办 / 当前待办）存成 PNG 到 app 专属目录（wprev-*.png），冒烟测试拉下来看

 *  AI：--es aifake 地址 / --es airec 文件 / --ez aisheet / --es aisay 文字 / --ez aiconfirm / --ez aiundo（见下面）
 */
object TestHooks {
    fun run(c: Context, i: Intent?) {
        i ?: return
        i.getStringExtra("setlang")?.let { Log.i("ZhishiTest", "lang -> $it"); Tr.set(c, it); return }
        if (i.getBooleanExtra("notodos", false)) TodoStore.save(c, emptyList())
        if (i.getBooleanExtra("seed", false)) seed(c)
        i.getStringExtra("seedat")?.let { seedAt(c, it) }
        if (i.getBooleanExtra("oldformat", false)) oldFormat(c)
        // 强制更新弹窗：假装查到了版本号 N 的新版（CI 上的版本号总是最新，平时永远弹不出来）
        if (i.hasExtra("fakeneed")) c.getSharedPreferences("upd", Context.MODE_PRIVATE).edit()
            .putInt("need", i.getIntExtra("fakeneed", 0)).putString("needNotes", "测试用的更新说明").putLong("checked", System.currentTimeMillis()).apply()
        i.getStringExtra("skin")?.let { Skins.setSkin(c, Skins.byId(it)) }
        if (i.hasExtra("mode")) Skins.setMode(c, i.getIntExtra("mode", 0))
        i.getStringExtra("wtint")?.let { v -> WidgetStyle.KINDS.forEach { WidgetStyle.setTint(c, v, it) } }
        if (i.hasExtra("wclear")) WidgetStyle.KINDS.forEach { WidgetStyle.setClear(c, i.getIntExtra("wclear", WidgetStyle.CLEAR_DEFAULT), kind = it) }
        i.getStringExtra("wallpaper")?.let { wallpaper(c, it == "dark") }
        i.getStringExtra("wstyle")?.let { v -> WidgetStyle.KINDS.forEach { WidgetStyle.set(c, v, it) } }
        if (i.getBooleanExtra("wrows", false)) widgetRows(c, i.getStringExtra("wtag") ?: "")
        // 教程：--ez notour true 全部当看过（冒烟测试不被挡住）；--es tour first|new 直接弹出来截图
        if (i.getBooleanExtra("notour", false)) Tour.skipAll(c)
        i.getStringExtra("tour")?.let { Tour.show.value = it }
        // 课表网页导入：--es jwkind qz --es jwurl file:///android_asset/jwtest.html --ez jwauto true
        i.getStringExtra("jwkind")?.let { TimetableTest.webKind = it; TimetableTest.webUrl = i.getStringExtra("jwurl"); TimetableTest.autoImport = i.getBooleanExtra("jwauto", false) }
        // 教务导入页搜索框预填：--es jwq 大连
        i.getStringExtra("jwq")?.let { TimetableTest.query = it; TimetableTest.webKind = null; TimetableTest.webUrl = null }
        // AI（冒烟测试接本机假的 OpenAI 兼容服务器 tools/fake_ai.py）：
        //  --es aifake http://127.0.0.1:8766  写进「AI 设置」（故意不带 /v1，测地址容错）；--es aivision 模型名（空 = 不单独设）
        i.getStringExtra("aifake")?.let { b -> Ai.save(c, AiSettings(b, "test-key", "fake-chat", i.getStringExtra("aivision") ?: "fake-vision")); Log.i("ZhishiTest", "AI configured $b") }
        if (i.getBooleanExtra("aireset", false)) AiChat.reset()
        //  --es airec 文件名  识别 app 专属目录里的截图，结果打到 logcat（AIREC OK / ROW / FAIL）
        i.getStringExtra("airec")?.let { name ->
            val f = File(c.getExternalFilesDir(null), name)
            Thread {
                try {
                    val img = AiImages.load(c, Uri.fromFile(f)) ?: throw IllegalStateException("cannot read $f")
                    val r = AiVision.recognize(c, listOf(img))
                    Log.i("ZhishiTest", "AIREC OK ${r.courses.size}")
                    r.courses.forEach { Log.i("ZhishiTest", "AIREC ROW ${it.day}|${it.name}|${it.start}-${it.end}|${it.weeks.sorted().joinToString(",")}|${it.room}|${it.teacher}") }
                } catch (x: Throwable) { Log.i("ZhishiTest", "AIREC FAIL ${x.javaClass.simpleName}: ${x.message}") }
            }.start()
        }
        //  --ez aisheet true [--es aitab todo] [--ez aifull true]  打开 AI 面板（截图用）
        if (i.getBooleanExtra("aisheet", false)) { AiChat.wantFull.value = i.getBooleanExtra("aifull", false); AiChat.show(i.getStringExtra("aitab") ?: "table") }
        //  --es aiquote user|bot|card  回复最后一条用户的话 / AI 的话 / 确认卡片（和长按 →「回复」同一个状态）；配合 aisay 发出去
        i.getStringExtra("aiquote")?.let { k ->
            val t = if (k == "none") null else AiChat.items.lastOrNull { when (k) { "user" -> it is ChatItem.User; "card" -> it is ChatItem.Card; else -> it is ChatItem.Bot } }
            AiChat.replyTo.value = t
            if (!i.hasExtra("aisay") && k != "none" && !i.getBooleanExtra("ainosheet", false)) { AiChat.wantFull.value = i.getBooleanExtra("aifull", false); AiChat.show(i.getStringExtra("aitab") ?: "table") }
            Log.i("ZhishiTest", "AIQUOTE $k ${t?.let { AiChat.quoteOf(it)?.text?.take(60) } ?: "none"}")
        }
        //  --ez aihist true  打出对话记录（AIHIST n=… 和每段的 标题|条数|卡片状态）
        if (i.getBooleanExtra("aihist", false)) {
            val l = AiChatStore.all(c)
            Log.i("ZhishiTest", "AIHIST n=${l.size} current=${AiChat.convoId}")
            l.forEach { cv -> Log.i("ZhishiTest", "AIHIST ${cv.id}|${cv.title}|${cv.count}|cards=${cv.items.mapNotNull { it.card?.state }.joinToString(",")}|quotes=${cv.items.count { it.quote != null }}|h=${cv.history.size}") }
        }
        //  --es aisay 文字  打开面板并发出去；完成后打 AISAY DONE + 每张卡片 AISAY CARD
        //  --ez ainosheet true：不打开面板（在「AI 对话记录」整页里接着发）
        i.getStringExtra("aisay")?.let { t ->
            if (!i.getBooleanExtra("ainosheet", false)) { AiChat.wantFull.value = i.getBooleanExtra("aifull", false); AiChat.show(i.getStringExtra("aitab") ?: "table") }
            kotlinx.coroutines.MainScope().launch {
                AiChat.say(c, t)
                for (it in AiChat.items) when (it) {
                    is ChatItem.Card -> Log.i("ZhishiTest", "AISAY CARD ${it.changes.joinToString(" / ") { ch -> ch.title }} state=${it.state()} n=${it.changes.size} live=${it.batch != null}")
                    is ChatItem.Bot -> Log.i("ZhishiTest", "AISAY BOT ${it.text.take(120)}")
                    is ChatItem.User -> if (it.quote != null) Log.i("ZhishiTest", "AISAY QUOTE ${if (it.quote.fromUser) "user" else "bot"}|${it.quote.text.take(60)}|${it.text.take(60)}")
                    is ChatItem.Error -> Log.i("ZhishiTest", "AISAY ERROR ${it.text.take(200)}")
                    else -> {}
                }
                Log.i("ZhishiTest", "AISAY DONE items=${AiChat.items.size}")
            }
        }
        //  --ez aiconfirm true：AI 的改动现在马上生效，没有要确认的卡片了（老对话里的卡片也不能再应用），只打 AICONFIRM none
        if (i.getBooleanExtra("aiconfirm", false)) Log.i("ZhishiTest", "AICONFIRM none")
        //  --ez aiundo true  全部撤销最后一批（和撤销列表里「全部撤销」同一条路径）；之后打 AIUNDO + AISTATE
        if (i.getBooleanExtra("aiundo", false)) {
            val b = AiChat.lastBatch.value
            kotlinx.coroutines.MainScope().launch {
                if (b != null) { AiChat.hookUndo(c); UndoCenter.run(c, b, UndoDeps.all(b.view())) }
                Log.i("ZhishiTest", "AIUNDO ${if (b == null) "none" else UndoCenter.msg.value}"); logState(c)
            }
        }
        undoHooks(c, i)
        cloudHooks(c, i)
        if (i.getBooleanExtra("aistate", false)) logState(c)
        voice(c, i)
        widgetLatency(c, i)
    }

    /**
     * 待办小部件刷新延迟（WLAT，见 WidgetProbe）：
     *  --es wlat 标题        新加一条待办（和界面上加待办同一个 TodoStore.put），从这一刻计时
     *  --es wlatedit 新标题  把第一条没完成的待办改名；--es wlatdone 标题  把这条标成完成
     *  --es wlatmark 标题    只开始计时（比如紧接着同步 / 撤销 / 按 HOME）
     *  --ez wpin true        请求把 4×2 待办小部件放到桌面（要在前台请求；桌面弹确认框，脚本去点）
     */
    private fun widgetLatency(c: Context, i: Intent) {
        fun mark(t: String) { WidgetProbe.marker = t; WidgetProbe.t0 = System.currentTimeMillis(); Log.i("ZhishiTest", "WLAT start $t") }
        i.getStringExtra("wlatmark")?.let { mark(it) }
        i.getStringExtra("wlat")?.let { t -> mark(t); val now = System.currentTimeMillis(); TodoStore.put(c, Todo("w" + now.toString(36), t, "", now + 3 * 3_600_000L, created = now)) }
        i.getStringExtra("wlatedit")?.let { t -> val o = CourseWidgets.todoPlan(TodoStore.all(c), System.currentTimeMillis()).list.firstOrNull(); mark(t); if (o != null) TodoStore.put(c, o.copy(title = t)) }
        i.getStringExtra("wlatdone")?.let { t -> val o = TodoStore.all(c).firstOrNull { it.title == t }; mark(t); if (o != null) TodoStore.toggle(c, o) }
        if (i.getBooleanExtra("wpin", false)) {
            val m = android.appwidget.AppWidgetManager.getInstance(c)
            val ok = try { m.requestPinAppWidget(android.content.ComponentName(c, TodoWidget4::class.java), null, null) } catch (x: Throwable) { false }
            Log.i("ZhishiTest", "WPIN requested=$ok supported=${m.isRequestPinAppWidgetSupported}")
        }
    }

    /**
     * 离线语音输入：
     *  --ez asrdialog true          打开 AI 面板并弹「下载语音模型」框（截图）；--ei asrprog N 假装已下载 N MB（截进度）
     *  --es asrpartial 文字         假装正在按住说话、已经识别出这些字（截提示条和输入框）；--ez asrarmed true 上滑取消的样子；--ez asrstop true 收起
     *  --es asrinstall 文件名       用 app 专属目录里的模型包走一遍「主地址失败 → 换下一个地址 → 校验 → 解包」（主地址是本机连不上的端口），打 ASR INSTALL
     *  --es asrwav 文件名           把 16kHz 单声道 wav 喂给识别器，打 ASR TEXT / ASR FAIL
     *  --ez asrdelete true / --ez asrstate true
     */
    private fun voice(c: Context, i: Intent) {
        if (i.getBooleanExtra("asrdelete", false)) Voice.delete(c)
        if (i.hasExtra("asrprog")) Voice.dl.value = AsrDl(AsrDl.Phase.DOWNLOADING, i.getIntExtra("asrprog", 0) * 1_000_000L, AsrSpec.SIZE, 1)
        if (i.getBooleanExtra("asrdialog", false)) { AiChat.show(i.getStringExtra("aitab") ?: "table"); Voice.dialog.value = "model" }
        i.getStringExtra("asrpartial")?.let { t ->
            AiChat.show(i.getStringExtra("aitab") ?: "table")
            Voice.prefix = ""; Voice.recording.value = true; Voice.armed.value = i.getBooleanExtra("asrarmed", false); Voice.live.value = t
        }
        if (i.getBooleanExtra("asrstop", false)) Voice.stop(commit = true)
        i.getStringExtra("asrinstall")?.let { name ->
            val f = File(c.getExternalFilesDir(null), name)
            val fetch = AsrFetch { u -> if (u.startsWith("file:")) AsrBody(f.length(), f.inputStream()) else Voice.HttpFetch.open(u) }
            Voice.download(c, fetch, listOf("http://127.0.0.1:9/asr-primary-down.tar.bz2", "file://" + f.path)) { r ->
                Log.i("ZhishiTest", "ASR INSTALL ${r.phase} err=${r.error} source=${r.source} installed=${Voice.installed(c)} bytes=${Voice.installer(c).bytes()}")
            }
        }
        i.getStringExtra("asrwav")?.let { name ->
            val f = File(c.getExternalFilesDir(null), name)
            Thread {
                try { val t0 = System.currentTimeMillis(); val t = Voice.decodeWav(c, f); Log.i("ZhishiTest", "ASR TEXT $t (${System.currentTimeMillis() - t0}ms)") }
                catch (x: Throwable) { Log.i("ZhishiTest", "ASR FAIL ${x.javaClass.simpleName}: ${x.message}") }
            }.start()
        }
        if (i.getBooleanExtra("asrstate", false))
            Log.i("ZhishiTest", "ASR STATE engine=${Voice.engineOk} installed=${Voice.installed(c)} perm=${Voice.perm(c)} dl=${Voice.dl.value.phase}")
    }

    /**
     * 自定义撤回：
     *  --ez undosheet true [--es undofor agent]  打开撤销列表（AI 的最后一批 / 智能体的那一批）
     *  --es undopick 0,2   点这几行（和点勾选框一样，按依赖自动勾上 / 取消），打 UNDOSEL
     *  --ez undorun true   撤销所选，打 UNDORUN + AISTATE
     */
    private fun undoHooks(c: Context, i: Intent) {
        if (i.getBooleanExtra("undosheet", false)) {
            if (i.getStringExtra("undofor") == "agent") UndoCenter.agentBatch.value?.let { UndoCenter.open(it) } else AiChat.undo(c)
            UndoCenter.sheet.value?.let { b -> UndoCenter.sel.value = emptySet(); Log.i("ZhishiTest", "UNDOSHEET ${b.title} n=${b.entries.size} ${b.entries.joinToString(" / ") { it.change.title }}") } ?: Log.i("ZhishiTest", "UNDOSHEET none")
        }
        i.getStringExtra("undopick")?.let { v -> v.split(',').mapNotNull { it.trim().toIntOrNull() }.forEach { UndoCenter.toggle(it) }; Log.i("ZhishiTest", "UNDOSEL ${UndoCenter.sel.value.sorted()}") }
        if (i.getBooleanExtra("undorun", false)) {
            val b = UndoCenter.sheet.value
            kotlinx.coroutines.MainScope().launch {
                if (b != null) UndoCenter.run(c, b, UndoCenter.sel.value)
                Log.i("ZhishiTest", "UNDORUN ${if (b == null) "none" else "undone=${b.undone.value.sorted()} msg=${UndoCenter.msg.value}"}"); logState(c)
            }
        }
    }

    /**
     * 云同步 / MCP（接线上的服务器，冒烟测试注册一个临时用户，最后删掉）：
     *  --ez cloudon true           打开同步（注册 + 整份推上去 + 拉一遍），打 CLOUD ON / CLOUD FAIL
     *  --es cloudtoken 名字        建一个智能体令牌：令牌写进 app 专属目录的 agent.txt（冒烟脚本 adb pull 下来用 curl 调 MCP，不打进日志），
     *                              并摆到「连接 AI 智能体」页上（截图）；打 CLOUD TOKEN ok
     *  --ez cloudsync true         同步一次，打 CLOUD SYNC pushed=… taken=… agent=… banner=…
     *  --ez cloudundo true         全部撤销底部提示里那一批智能体改动（走服务器的 undo + 同步），打 CLOUD UNDO
     *  --ez clouddelete true       停止同步并删掉服务器上的账号（DELETE /v1/account），打 CLOUD DELETED
     *  --ez cloudstate true        打 CLOUD STATE
     */
    private fun cloudHooks(c: Context, i: Intent) {
        val ctx = c.applicationContext
        fun bg(tag: String, f: () -> String) = Thread { val r = try { f() } catch (x: Throwable) { "FAIL ${x.javaClass.simpleName}: ${x.message}" }; Log.i("ZhishiTest", "CLOUD $tag $r") }.start()
        if (i.getBooleanExtra("cloudon", false)) bg("ON") { CloudSync.enable(ctx); "ok uid=${CloudSync.userId(ctx)} pending=${CloudSync.pendingCount(ctx)}" }
        i.getStringExtra("cloudtoken")?.let { name -> bg("TOKEN") {
            val t = CloudSync.createToken(ctx, name)
            File(ctx.getExternalFilesDir(null), "agent.txt").writeText(t.token)
            CloudSync.onMain { McpUi.created.value = t; McpUi.ver.value++ }
            "ok id=${t.id} url=${t.mcpUrl}"
        } }
        if (i.getBooleanExtra("cloudsync", false)) bg("SYNC") {
            val o = CloudSync.syncBlocking(ctx) ?: throw IllegalStateException(CloudSync.lastError.value)
            Thread.sleep(300)
            "ok pushed=${o.pushed} taken=${o.taken.size} agent=${o.byAgent.size} banner=${UndoCenter.agentBatch.value?.let { b -> b.title + " n=" + b.entries.size } ?: "none"}"
        }
        if (i.getBooleanExtra("cloudundo", false)) {
            val b = UndoCenter.agentBatch.value
            kotlinx.coroutines.MainScope().launch {
                val r = if (b != null) UndoCenter.run(c, b, UndoDeps.all(b.view())) else null
                Log.i("ZhishiTest", "CLOUD UNDO ${if (r == null) "none" else "done=${r.done.size} err=${r.error}"}"); logState(c)
            }
        }
        if (i.getBooleanExtra("clouddelete", false)) bg("DELETED") { CloudSync.disable(ctx, true); "ok token=${CloudSync.hasAccount(ctx)}" }
        if (i.getBooleanExtra("cloudstate", false)) Log.i("ZhishiTest", "CLOUD STATE on=${CloudSync.enabled(ctx)} uid=${CloudSync.userId(ctx)} pending=${CloudSync.pendingCount(ctx)} enc=${CloudSync.tokenEncrypted(ctx)} err=${CloudSync.lastError.value}")
    }

    private fun look(w: WidgetLook) = "${w.style}/${w.tint}/${w.clear}/${w.dmode}"
    private fun logState(c: Context) {
        val s = AiStateIO.read(c)
        Log.i("ZhishiTest", "AISTATE courses=${s.courses.joinToString(",") { it.name + "@" + it.days.joinToString("") }} todos=${s.todos.joinToString(",") { it.title }} skin=${s.skin} mode=${s.mode} " +
            "rot=${s.rotMode}/${s.rotEvery}/${s.favs.joinToString("+")} wcourse=${look(s.widgetCourse)} wtodo=${look(s.widgetTodo)} " +
            "display=${listOf(s.showWeekend, s.showOff, s.showLevel, s.widgetLevel).joinToString("") { if (it) "1" else "0" }}")
    }

    private fun widgetRows(c: Context, tag: String) {
        var ok = true
        for (wide in listOf(true, false)) {
            try {
                val f = TodoRows(c, android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID, wide)
                f.onCreate(); f.onDataSetChanged()
                val n = f.getCount()
                val parent = android.widget.FrameLayout(c)
                val wPx = ((if (wide) 290 else 130) * c.resources.displayMetrics.density).toInt()
                val hs = (0 until n).map { pos ->
                    val v = f.getViewAt(pos).apply(c, parent)
                    v.measure(android.view.View.MeasureSpec.makeMeasureSpec(wPx, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
                    v.measuredHeight
                }
                Log.i("ZhishiTest", "WROWS wide=$wide n=$n heights=$hs")
                if (hs.any { it <= 0 }) ok = false
                f.onDestroy()
            } catch (x: Throwable) { ok = false; Log.i("ZhishiTest", "WROWS FAIL wide=$wide ${x.javaClass.name}: ${x.message}") }
            try {
                val tp = CourseWidgets.todoPlan(TodoStore.all(c), System.currentTimeMillis())
                val rv = CourseWidgets.todoViews(c, android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID, wide, null, tp, Term.desired(c).filter { it.kind == "course" })
                Log.i("ZhishiTest", "WMAIN wide=$wide built n=${tp.list.size}")
                try { rv.apply(c, android.widget.FrameLayout(c)); Log.i("ZhishiTest", "WMAIN wide=$wide inflated") }
                catch (x: Throwable) { Log.i("ZhishiTest", "WMAIN wide=$wide inflate skipped ${x.javaClass.name}: ${x.message}") }
            } catch (x: Throwable) { ok = false; Log.i("ZhishiTest", "WROWS FAIL main wide=$wide ${x.javaClass.name}: ${x.message}") }
            for (demo in listOf(true, false)) try {
                val b = CourseWidgets.previewTodo(c, wide, demo)
                File(c.getExternalFilesDir(null), "wprev-${if (wide) 4 else 2}-${if (demo) "demo" else "real"}$tag.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } catch (x: Throwable) { Log.i("ZhishiTest", "WPREV FAIL ${x.message}") }
        }
        Log.i("ZhishiTest", if (ok) "WROWS ok" else "WROWS FAIL")
    }

    private fun seed(c: Context) {
        Tour.skipAll(c)
        if (Term.desired(c).isEmpty()) Term.setDesired(c, listOf(
            Slot("t1", "大学英语(一)", setOf(1), 480, 580, 1, room = "机401-语音室", teacher = "宋岩"),
            Slot("t2", "高等数学①(一)", setOf(1), 600, 700, 2, room = "教210", teacher = "刘思妤"),
            Slot("t3", "高等数学①(一)", setOf(3), 480, 580, 2, room = "教103", teacher = "刘思妤"),
            Slot("t4", "工程制图基础", setOf(2), 600, 700, 1, room = "大成111", teacher = "赵群超"),
            Slot("t5", "无机化学(1)", setOf(1), 840, 940, 1, room = "逸102", teacher = "徐君莉,谢宏伟"),
            Slot("t6", "无机化学(1)", setOf(4), 840, 940, 1, room = "逸310", teacher = "徐君莉,谢宏伟", weeks = (1..16).toSet()),
            Slot("t7", "大学生心理与健康教育(一)", setOf(2), 960, 1060, 0, room = "大成305", teacher = "刘洋", weeks = (1..8).toSet()),
            Slot("t8", "习近平新时代中国特色社会主义思想概论", setOf(1), 1140, 1240, 1, room = "教305", teacher = "王老师"),
            Slot("t9", "习近平新时代中国特色社会主义思想概论", setOf(5), 960, 1060, 1, room = "教305", weeks = (1..11).toSet()),
            Slot("t10", "大学物理实验", setOf(4), 600, 700, 1, room = "实验楼", weeks = setOf(1, 3, 5, 7, 9, 11, 13, 15)),
            Slot("t11", "形势与政策", setOf(5), 1140, 1240, 0, room = "大成101", weeks = setOf(2, 4, 6, 8, 10, 12)),
            Slot("t12", "体育(一)", setOf(3), 840, 940, 1, room = "体育馆", teacher = "孙老师"),
            // 今天正在上的一节（冒烟截图里能看到「正在上」和小部件的上课中）
            Slot("t13", "线性代数", setOf(Term.weekday()), (Term.nowMin() - 20).coerceIn(0, 1380), (Term.nowMin() + 40).coerceIn(60, 1439), 2, room = "教201", teacher = "周老师")))
        if (Term.termStart(c) < 0) Term.setWeekNow(c, 12)
        // 示例待办：今天、明天、下周、没日期、一条已完成（冒烟截图待办页、日历页）
        if (TodoStore.all(c).isEmpty()) {
            val t = Term.todayEpoch(); val now = System.currentTimeMillis()
            TodoStore.save(c, listOf(
                Todo("d1", "交高数作业 第三章", "习题 3.1–3.4", Todos.at(t, 22 * 60), true, "高等数学①(一)", created = now - 5000),
                Todo("d2", "无机化学实验报告", "", Todos.at(t + 1, 18 * 60), false, "无机化学(1)", created = now - 4000),
                Todo("d3", "大学英语 Unit 5 单词测验", "", Todos.at(t + 4, 8 * 60), false, "大学英语(一)", created = now - 3000),
                Todo("d4", "借图书馆的工程制图参考书", "", -1L, false, "", created = now - 2000),
                Todo("d5", "体育课交体测表", "", Todos.at(t - 1, 12 * 60), false, "体育(一)", done = true, doneAt = now - 1000, created = now - 6000),
            ))
        }
        // 皮肤：--es skin qingci --ei mode 2
        Log.i("ZhishiTest", "seeded")
    }

    /** 一张像照片的渐变壁纸：深色 = 夜里的深蓝灰 + 一团暖色光；浅色 = 米白到浅蓝。 */
    private fun wallpaper(c: Context, dark: Boolean) {
        try {
            val w = 1080; val h = 2400
            val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(b)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val (a, z, blob) = if (dark) Triple(0xFF0B0D14.toInt(), 0xFF1C2232.toInt(), 0xFF7A5442.toInt()) else Triple(0xFFF4E9D8.toInt(), 0xFFCFE3F0.toInt(), 0xFFF2C6B4.toInt())
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), a, z, android.graphics.Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = android.graphics.RadialGradient(w * 0.5f, h * 0.42f, w * 0.55f, blob, blob and 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
            cv.drawCircle(w * 0.5f, h * 0.42f, w * 0.55f, p)
            android.app.WallpaperManager.getInstance(c).setBitmap(b, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
            Log.i("ZhishiTest", "wallpaper ${if (dark) "dark" else "light"} -> ${WallTint.read(c)}")
        } catch (x: Throwable) { Log.w("ZhishiTest", "wallpaper failed: $x") }
    }

    private fun seedAt(c: Context, mode: String) {
        Tour.skipAll(c)
        val m = Term.nowMin(); val d = Term.weekday(); val other = if (d == 7) 1 else d + 1
        fun sl(id: String, n: String, f: Int, t: Int, day: Int = d) = Slot(id, n, setOf(day), f.coerceIn(0, 1438), t.coerceIn(1, 1439), 1, room = "教101", teacher = "李老师")
        val today = when (mode) {
            "before" -> listOf(sl("a1", "高等数学", m + 90, m + 135), sl("a2", "大学英语", m + 150, m + 195))
            "between" -> listOf(sl("a1", "高等数学", m - 80, m - 35), sl("a2", "大学英语", m + 25, m + 70))
            "after" -> listOf(sl("a1", "高等数学", m - 130, m - 85), sl("a2", "大学英语", m - 47, m - 2))
            "ending" -> listOf(sl("a1", "高等数学", m - 44, m + 1))
            "tiny" -> listOf(sl("a1", "高等数学", m, m + 1), sl("a2", "线代", m + 1, m + 1))
            else -> emptyList()
        }
        Term.setDesired(c, today + listOf(sl("o1", "工程制图", 480, 580, other), sl("o2", "大学英语", 600, 700, other)))
        if (Term.termStart(c) < 0) Term.setWeekNow(c, 12)
        val now = System.currentTimeMillis(); val t = Term.todayEpoch()
        TodoStore.save(c, listOf(
            Todo("e1", "全天带提醒", "", Todos.allDayAt(t), true, "", allDay = true, before = 60, created = now),
            Todo("e2", "关联今天已没课的课程", "", Todos.at(t, (m + 5).coerceAtMost(1439)), true, "高等数学", before = 30, created = now),
            Todo("e3", "没日期却开着提醒", "", -1L, true, "", allDay = true, before = 1440, created = now),
            Todo("e4", "过期的", "", now - 3_600_000L, true, "不存在的课", created = now),
        ))
        Log.i("ZhishiTest", "seedat $mode m=$m d=$d")
    }

    /** 老版本 / 被改坏的存储：字段缺失、类型不对、未知的皮肤和样式、坏掉的节次时间。启动和各页面都不能因此崩。 */
    private fun oldFormat(c: Context) {
        Tour.skipAll(c)
        val m = Term.nowMin(); val d = Term.weekday()
        c.getSharedPreferences("term", Context.MODE_PRIVATE).edit()
            .putString("courses", """[{"id":"x1","n":"老格式课","d":[$d],"f":${(m - 60).coerceAtLeast(0)},"t":${(m - 10).coerceAtLeast(1)}},
                {"id":"x2","n":"坏字段","d":["一",$d],"f":"abc","t":null,"w":[1,"x"],"l":9},
                {"n":"没有 id","d":[$d],"f":${m + 30},"t":${m + 20}},
                "不是对象", 42, null,
                {"id":"x3","n":"","d":[0,8,-1],"f":-50,"t":5000,"k":"course"}]""")
            .putString("secs", "480,abc,,600").putString("secE", "x,1") .putInt("secLen", -5).putLong("term", Term.todayEpoch() + 400).putInt("weeks", 0)
            .apply()
        c.getSharedPreferences("todo", Context.MODE_PRIVATE).edit()
            .putString("items", """[{"id":"o1","t":"老待办","due":${System.currentTimeMillis() + 3600000},"re":true,"co":"老格式课"},
                {"id":"o2","t":"坏的","due":"明天","re":"yes","ad":1,"rb":-30}, "x", null, {"t":"没 id","due":-1,"re":true,"ad":true,"rb":99999}]""")
            .apply()
        c.getSharedPreferences("look", Context.MODE_PRIVATE).edit().putString("skin", "nosuchskin").putInt("mode", 7).apply()
        c.getSharedPreferences("widget", Context.MODE_PRIVATE).edit().putString("style", "???").putString("tint", "???").putInt("clear", 500).apply()
        Skins.init(c)
        Log.i("ZhishiTest", "oldformat written")
    }
}
