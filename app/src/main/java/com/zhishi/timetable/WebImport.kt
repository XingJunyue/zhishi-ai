package com.zhishi.timetable

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 从教务网站导入：先选学校 / 教务类型，再在内置浏览器里登录，点「导入课表」。
 * 抓取在网页里用 JS 做（带着登录后的 Cookie，从页面自己的域名发请求），结果交给 Jw 里的解析函数。
 */
private const val NEU = "https://jwxt.neu.edu.cn/"
private const val NEU_VPN = "https://webvpn.neu.edu.cn/"

private fun prefs(c: Context) = c.getSharedPreferences("jwimport", Context.MODE_PRIVATE)

@Volatile private var schoolCache: List<School>? = null
/** assets/schools.tsv（开源学校列表，见 Schools.kt / THIRD_PARTY_NOTICES.md）。 */
fun schoolsOf(c: Context): List<School> = schoolCache ?: try {
    Schools.parse(c.assets.open("schools.tsv").bufferedReader().use { it.readText() }).also { schoolCache = it }
} catch (x: Exception) { emptyList() }

private class Bridge(val cb: (String) -> Unit, val onStep: (Int, String) -> Unit) {
    @JavascriptInterface fun post(s: String) { Handler(Looper.getMainLooper()).post { cb(s) } }
    @JavascriptInterface fun step(t: Int, s: String) { Handler(Looper.getMainLooper()).post { onStep(t, s) } }
}

/** 导入失败：哪一步、给用户看的话、可复制的诊断信息。 */
private class JwFail(val code: String, val msg: String, val diag: String)

private fun failMsg(code: String, step: String, err: String): String = when (code) {
    "login" -> tr("登录未完成：请先在网页里登录（二次认证也要做完），再点导入。")
    "notfound" -> tr("没找到课表：教务系统没有返回课表数据。可以在网页里打开「我的课表」再点导入，或者换一个教务类型。")
    "empty" -> tr("解析为空：拿到了数据，但没认出课程。可以换一个教务类型试试，或者复制诊断信息发给开发者。")
    "network" -> tr("请求失败（{0}）：教务系统没有响应。检查网络（有的学校要校园网或学校的 VPN），再点重试。", JwOutcome.scrub(err, 40))
    "timeout" -> tr("超时：卡在「{0}」这一步。点重试；还不行就复制诊断信息。", tr(JwOutcome.stepLabel(step)))
    "nojwapp" -> tr("找不到教务系统的地址。用 WebVPN 时，请先在网页里打开「我的课表」页面再点导入。")
    else -> tr("导入失败：{0}", JwOutcome.scrub(err, 120))
}

@Composable
fun JwImportScreen(onClose: () -> Unit, onParsed: (List<JwCourse>) -> Unit) {
    val c = LocalContext.current
    // kind：neu / zf / qz …；null = 还在选
    var kind by remember { mutableStateOf(TimetableTest.webKind) }
    var url by remember { mutableStateOf(TimetableTest.webUrl ?: "") }
    // 整屏流程：底部导航条收起来，否则会盖住底部的「导入课表」
    HideBottomBar()
    val k0 = kind
    if (k0 == null) {
        JwPicker(onClose) { k, u -> kind = k; url = u }
    } else {
        BackHandler { kind = null; TimetableTest.webKind = null; TimetableTest.webUrl = null }
        JwWeb(k0, url, onBack = { kind = null; TimetableTest.webKind = null; TimetableTest.webUrl = null }, onParsed = onParsed,
            onKind = { kind = it })
    }
}

/** 搜索词是不是在找东北大学（金智专用入口）。空搜索不算——不默认东大。 */
internal fun neuHit(t0: String): Boolean {
    val t = t0.trim().lowercase()
    if (t.isEmpty() || t.contains("秦皇岛") || t.contains("neuq")) return false
    return "东北大学".startsWith(t) || (t.length >= 3 && "东北大学".contains(t)) || t.startsWith("东北大学") || listOf("neu", "东大", "金智", "northeastern").any { it.startsWith(t) || t.startsWith(it) }
}

/** 上次选过的学校（只有用户自己选过才有；新装什么都不预选）。 */
private data class LastJw(val name: String, val kind: String, val url: String)
private fun lastJw(c: Context): LastJw? {
    val p = prefs(c); val k = p.getString("last_kind", null) ?: return null; val u = p.getString("last_url", null) ?: return null
    return LastJw(p.getString("last_name", "") ?: "", k, u)
}
private fun rememberJw(c: Context, name: String, kind: String, url: String) =
    prefs(c).edit().putString("last_name", name).putString("last_kind", kind).putString("last_url", url).apply()

@Composable
private fun JwPicker(onClose: () -> Unit, onPick0: (String, String) -> Unit) {
    val c = LocalContext.current
    var q by remember { mutableStateOf(TimetableTest.query ?: "") }
    val schools = remember { schoolsOf(c) }
    val found = remember(q) { Schools.search(schools, q).filter { !(it.name == "东北大学") } }
    val last = remember { lastJw(c) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (x: Exception) {} }
    // 选了就记下来，下次打开放在「上次用的」；手动选类型时名字留空
    val onPick: (String, String, String) -> Unit = { name, k, u -> rememberJw(c, name, k, u); onPick0(k, u) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 28.sp, color = C.Ink, modifier = Modifier.clip(CircleShape).clickable { onClose() }.padding(horizontal = 14.dp))
            Text(tr("从教务导入"), fontSize = 22.sp, fontFamily = Serif, fontWeight = FontWeight.Bold, color = C.Ink, letterSpacing = 0.5.sp)
        }
        Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().liquidGlass(CircleShape, shadow = 4.dp).padding(horizontal = 18.dp, vertical = 13.dp)) {
            if (q.isEmpty()) Text(tr("搜索学校或教务类型"), fontSize = 14.sp, color = C.Faint)
            BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, color = C.Ink), modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            val t = q.trim().lowercase()
            fun hit(vararg s: String) = t.isEmpty() || s.any { it.lowercase().contains(t) }
            // 上次用的（只在用户选过之后才有）
            if (t.isEmpty() && last != null) {
                val kn = if (last.kind == "neu") tr("金智新教务 · jwxt.neu.edu.cn · 统一身份认证登录") else (Jw.KINDS.firstOrNull { it.id == last.kind }?.name?.let { tr(it) } ?: last.kind)
                Text(tr("上次用的"), fontSize = 13.sp, color = C.Faint, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 4.dp))
                Row(Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(22.dp)).clickable { onPick(last.name, last.kind, last.url) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(last.name.ifEmpty { android.net.Uri.parse(last.url).host ?: last.url }.let { if (it == "东北大学") tr(it) else it }, fontSize = 15.sp, color = C.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(kn + " · " + (android.net.Uri.parse(last.url).host ?: last.url), fontSize = 11.sp, color = C.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("›", fontSize = 18.sp, color = C.Faint)
                }
            }
            // 东北大学（金智）只在搜到时出现，不默认放在最上面
            if (neuHit(t)) {
                Text(tr("学校"), fontSize = 13.sp, color = C.Faint, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 4.dp))
                Column(Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(22.dp)).padding(16.dp)) {
                    Text(tr("东北大学"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                    Text(tr("金智新教务 · jwxt.neu.edu.cn · 统一身份认证登录"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 2.dp))
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(tr("校园网直连"), true, Modifier.weight(1f)) { onPick("东北大学", "neu", NEU) }
                        Pill(tr("校外 WebVPN"), false, Modifier.weight(1f)) { onPick("东北大学", "neu", NEU_VPN) }
                    }
                }
            }
            if (t.isEmpty() && schools.isNotEmpty()) Text(tr("输入学校名搜索，已收录 {0} 所学校的教务入口", schools.map { it.name }.distinct().size), fontSize = 12.sp, color = C.Faint,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp))
            if (found.isNotEmpty()) {
                Text(tr("搜到的学校"), fontSize = 13.sp, color = C.Faint, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp, start = 4.dp))
                Text(tr("学校列表来自开源项目，网址和类型不一定准；进去后可以改网址、点上方类型名换解析方式。"), fontSize = 11.sp, color = C.Faint, lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
                Column(Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(22.dp))) {
                    found.forEachIndexed { i, sc ->
                        if (i > 0) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(C.Track.copy(alpha = 0.7f)))
                        val kk = Schools.kindOf(sc)
                        val kn = Jw.KINDS.firstOrNull { it.id == kk }?.name ?: kk
                        Row(Modifier.fillMaxWidth().clickable { onPick(sc.name, kk, sc.url) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(sc.name, fontSize = 15.sp, color = C.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (sc.src == "s") Text(tr("有人适配过"), fontSize = 10.sp, color = C.SageInk,
                                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(C.Sand).padding(horizontal = 5.dp, vertical = 1.dp))
                                }
                                Text(tr(kn) + " · " + (android.net.Uri.parse(sc.url).host ?: sc.url), fontSize = 11.sp, color = C.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                // 手动核对过的学校带一句说明（网络要求、用哪个密码），数据本身是中文
                                if (sc.src == "m" && sc.note.isNotEmpty()) Text(sc.note, fontSize = 11.sp, color = C.Soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text("›", fontSize = 18.sp, color = C.Faint)
                        }
                    }
                }
            }
            val kinds = Jw.KINDS.filter { hit(it.name, it.note, it.id) }
            if (kinds.isNotEmpty()) {
                Text(tr("通用教务类型"), fontSize = 13.sp, color = C.Faint, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp, start = 4.dp))
                Text(tr("学校不在上面？选你们教务系统的类型，再输入教务网址。登录页底部或网址里一般能看出是哪家。"), fontSize = 11.sp, color = C.Faint, lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
                Column(Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(22.dp))) {
                    kinds.forEachIndexed { i, k ->
                        if (i > 0) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(C.Track.copy(alpha = 0.7f)))
                        Row(Modifier.fillMaxWidth().clickable { onPick("", k.id, prefs(c).getString("url_" + k.id, "") ?: "") }.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tr(k.name), fontSize = 15.sp, color = C.Ink)
                                    if (k.exp) Text(tr("实验性"), fontSize = 10.sp, color = C.Soft,
                                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(C.Sand).padding(horizontal = 5.dp, vertical = 1.dp))
                                }
                                if (k.note.isNotEmpty()) Text(tr(k.note), fontSize = 11.sp, color = C.Faint)
                            }
                            Text("›", fontSize = 18.sp, color = C.Faint)
                        }
                    }
                }
            }
            Text(tr("账号密码只在学校的网页里输入，知时不保存也不上传；课表在手机上解析。"), fontSize = 11.sp, color = C.Faint, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 14.dp, bottom = 30.dp, start = 4.dp))
        }
    }
}

@Composable
private fun Pill(t: String, main: Boolean, m: Modifier, onClick: () -> Unit) {
    Box(m.clip(RoundedCornerShape(12.dp)).background(if (main) C.Clay else C.Sand).clickable { onClick() }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(t, fontSize = 13.sp, color = if (main) C.OnClay else C.Ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun JwWeb(kind: String, start: String, onBack: () -> Unit, onParsed: (List<JwCourse>) -> Unit, onKind: (String) -> Unit = {}) {
    var pickKind by remember { mutableStateOf(false) }
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var addr by remember { mutableStateOf(start) }
    var progress by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<JwFail?>(null) }
    var tok by remember { mutableStateOf(0) }          // 每次导入一个编号；超时或重试后，旧脚本晚到的结果直接丢掉
    var stepNow by remember { mutableStateOf("start") }
    var lastArg by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }   // 页面还在加载时点了导入：加载完再自动导入
    var loading by remember { mutableStateOf(false) }   // onPageStarted 到 onPageFinished 之间（跳转、WebVPN 重定向中）
    var campuses by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var wv by remember { mutableStateOf<WebView?>(null) }
    // AI 帮我找课表：正在跑的那一次（null = 没在跑）、跑完的提示、两个对话框
    var webPage by remember { mutableStateOf<JwWebPage?>(null) }
    var ai by remember { mutableStateOf<JwAiRun?>(null) }
    var aiMsg by remember { mutableStateOf<String?>(null) }
    var askConsent by remember { mutableStateOf(false) }
    var askSetup by remember { mutableStateOf(false) }
    // 电脑版网页：有的统一认证 / 二次认证页面在手机版模板下显示不出来，换成电脑浏览器的标识再加载
    var desktop by remember { mutableStateOf(prefs(c).getBoolean("desktop", false)) }
    val kname = if (kind == "neu") tr("东北大学") else Jw.KINDS.firstOrNull { it.id == kind }?.name?.let { tr(it) } ?: kind
    val vpn = addr.contains("webvpn.neu.edu.cn")

    fun go(u0: String) {
        val u = u0.trim().let { if (it.isEmpty() || it.contains("://")) it else "https://$it" }
        if (u.isEmpty()) return
        addr = u; err = null; busy = false; pending = false; tok++
        if (kind != "neu") prefs(c).edit().putString("url_$kind", u).apply()
        if (prefs(c).getString("last_kind", null) == kind) prefs(c).edit().putString("last_url", u).apply()
        wv?.loadUrl(u)
    }
    val jsKind = if (kind == "neu") "wisedu" else kind
    fun hostOf(u: String) = try { android.net.Uri.parse(u).host ?: "" } catch (x: Exception) { "" }
    fun fail(code: String, e: String, j: JSONObject?) {
        busy = false; pending = false
        val d = j?.optJSONObject("diag")
        val ua = wv?.settings?.userAgentString ?: ""
        val text = JwOutcome.diagText(BuildConfig.VERSION_NAME + if (BuildConfig.PREVIEW) " preview" else "", jsKind, hostOf(addr), stepNow, code, e, d,
            j?.optJSONArray("html")?.length() ?: 0, j?.optString("json", "")?.isNotBlank() == true, JwOutcome.uaLabel(ua, desktop),
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm Z", java.util.Locale.US).format(java.util.Date()))
        err = JwFail(code, failMsg(code, stepNow, e), text)
        if (BuildConfig.DEBUG) android.util.Log.i("ZhishiTest", "JW fail $code step=$stepNow err=$e")
    }
    fun grab(arg: String?) {
        val w = wv ?: return
        if (busy) return                                   // 连点只算一次
        lastArg = arg; err = null; aiMsg = null; busy = true; stepNow = "start"
        val t = ++tok
        if (loading && progress < 100) { pending = true; stepNow = "page" }   // 等页面加载完（onPageFinished 里接着导）
        else w.evaluateJavascript(jwScript(jsKind, arg, t), null)
        // 看门狗：90 秒没有结果就报超时，按钮恢复可点
        scope.launch {
            kotlinx.coroutines.delay(90_000)
            if (busy && tok == t) { tok++; fail("timeout", "timeout", null) }
        }
    }
    fun startAi() {
        if (ai != null) return
        if (!Ai.ready(c)) { askSetup = true; return }
        if (!JwAiConsent.ok(c)) { askConsent = true; return }
        val page = webPage ?: return
        err = null; aiMsg = null; busy = false; pending = false; tok++
        val run = JwAiRun(); ai = run
        jwAiLog("START url=${hostOf(addr)}")
        val client = Ai.client(c); val per = AiVision.periods(c); val total = Term.totalWeeks(c); val lang = Tr.lang
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                JwAgent(client, page, per, total, lang = lang).run({ st ->
                    Handler(Looper.getMainLooper()).post { if (ai === run) { run.steps.add(st); jwAiLog("STEP ${st.n} ${st.tool} ${st.label}${if (st.refused) " refused" else ""}") } }
                }, { run.stop.get() })
            }
            if (ai === run) {
                ai = null
                jwAiLog("END ${out.javaClass.simpleName}" + if (out is JwAgent.Outcome.Found) " n=${out.courses.size} steps=${out.steps}" else "")
                when (out) {
                    is JwAgent.Outcome.Found -> { CookieManager.getInstance().flush(); onParsed(out.courses) }
                    else -> aiMsg = jwAiOutcomeText(out)
                }
            }
        }
    }
    val handle: (String) -> Unit = handle@{ s ->
        val j = try { JSONObject(s) } catch (x: Exception) { JSONObject().put("ok", false).put("err", "bad result") }
        if (j.optInt("token", -1) != tok || !busy) return@handle      // 过期的结果
        val d = j.optJSONObject("diag")
        d?.optString("step")?.takeIf { it.isNotEmpty() }?.let { stepNow = it }
        if (!j.optBoolean("ok")) { val e = j.optString("err"); fail(JwOutcome.failStep(false, e, d, 0) ?: "error", e, j) }
        else if (j.optString("need") == "campus") {
            busy = false
            val a = j.optJSONArray("campuses")
            campuses = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }.map { it.optString("id") to it.optString("name") }
        } else scope.launch {
            stepNow = "parse"
            val r = withContext(Dispatchers.Default) { try { Jw.parse(jsKind, s) } catch (x: Exception) { emptyList() } }
            if (BuildConfig.DEBUG) android.util.Log.i("ZhishiTest", "JW parsed ${r.size} kind=$kind")
            val code = JwOutcome.failStep(true, "", d, r.size)
            if (code != null) fail(code, d?.optString("err").orEmpty(), j)
            else { busy = false; CookieManager.getInstance().flush(); onParsed(r) }
        }
    }
    val onResult = rememberUpdatedState(handle)
    BackHandler { val w = wv; if (w != null && w.canGoBack()) w.goBack() else onBack() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 28.sp, color = C.Ink, modifier = Modifier.clip(CircleShape).clickable { onBack() }.padding(horizontal = 14.dp))
            Box(Modifier.weight(1f).liquidGlass(CircleShape, shadow = 0.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (addr.isEmpty()) Text(tr("输入你们学校的教务网址"), fontSize = 13.sp, color = C.Faint, maxLines = 1)
                BasicTextField(addr, { addr = it }, singleLine = true, textStyle = TextStyle(fontSize = 13.sp, color = C.Ink),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go(addr) }), modifier = Modifier.fillMaxWidth())
            }
            Text(tr("前往"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(10.dp)).clickable { go(addr) }.padding(horizontal = 8.dp, vertical = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(kname + if (kind == "neu") "" else " ▾", fontSize = 12.sp, color = C.Soft, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = kind != "neu") { pickKind = true }.padding(horizontal = 4.dp, vertical = 3.dp))
            if (kind == "neu") {
                Spacer(Modifier.width(8.dp))
                Text(if (vpn) tr("WebVPN 已开") else tr("切到 WebVPN（校外）"), fontSize = 11.sp, color = if (vpn) C.OnClay else C.Ink, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (vpn) C.Sage else C.Sand).clickable { go(if (vpn) NEU else NEU_VPN) }.padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(if (desktop) tr("电脑版 ✓") else tr("电脑版"), fontSize = 12.sp, color = if (desktop) C.ClayDeep else C.Soft,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    desktop = !desktop; prefs(c).edit().putBoolean("desktop", desktop).apply()
                    wv?.let { w -> w.settings.userAgentString = jwUserAgent(WebSettings.getDefaultUserAgent(w.context), desktop); w.reload() }
                }.padding(6.dp))
            Text(tr("刷新"), fontSize = 12.sp, color = C.Soft, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { wv?.reload() }.padding(6.dp))
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxSize(), color = C.Clay, trackColor = Color.Transparent)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    setupJwWebView(this, desktop)
                    addJavascriptInterface(Bridge({ onResult.value(it) }, { t, st -> if (t == tok) stepNow = st }), "ZhishiJw")
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { addr = url; loading = true; webPage?.started() }
                        override fun onPageFinished(view: WebView, url: String) {
                            addr = url; loading = false; webPage?.finished(); CookieManager.getInstance().flush()
                            if (pending && busy) { pending = false; view.evaluateJavascript(jwScript(jsKind, lastArg, tok), null) }
                            if (TimetableTest.autoImport) { TimetableTest.autoImport = false; grab(null) }
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean = !jwWebScheme(req.url)
                    }
                    webChromeClient = JwChrome(ctx, this) { progress = it }
                    wv = this
                    webPage = JwWebPage(this)
                    if (BuildConfig.HOOKS) TimetableTest.jwWeb = java.lang.ref.WeakReference(this)
                    if (start.isNotBlank()) loadUrl(start)
                }
            }, onRelease = { it.stopLoading(); it.destroy() }, modifier = Modifier.fillMaxSize())
            if (start.isBlank() && progress == 0) Column(Modifier.fillMaxSize().background(C.Cream).padding(28.dp)) {
                Text(tr("在上面输入教务系统的网址"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                Text(tr("比如 jwxt.xxx.edu.cn。登录后直接点下面的「导入课表」；读不到时再打开「我的课表」页面试试。"), fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
            }
            // 底部：提示 + 导入按钮
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp).navigationBarsPadding()) {
                err?.let { e ->
                    Column(Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(20.dp), Glass.THICK, shadow = 6.dp).padding(14.dp)) {
                        Text(e.msg, fontSize = 12.sp, color = C.ClayDeep, lineHeight = 17.sp)
                        // 直接导入不行：让用户自己的 AI 帮忙找课表页面（JwAgent）
                        Text(tr("AI 帮我找课表"), fontSize = 13.sp, color = C.OnClay, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, style = LocalTextStyle.current.copy(shadow = null),
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.ClayDeep).clickable { startAi() }.padding(vertical = 9.dp))
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("重试"), fontSize = 12.sp, color = C.OnClay, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Clay).clickable { grab(lastArg) }.padding(horizontal = 12.dp, vertical = 6.dp))
                            Text(tr("复制诊断信息"), fontSize = 12.sp, color = C.Ink,
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Sand).clickable {
                                    val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("zhishi-jw", e.diag))
                                    android.widget.Toast.makeText(c, tr("已复制，可以发给开发者"), android.widget.Toast.LENGTH_SHORT).show()
                                }.padding(horizontal = 12.dp, vertical = 6.dp))
                            Spacer(Modifier.weight(1f))
                            Text("×", fontSize = 16.sp, color = C.Faint, modifier = Modifier.clip(CircleShape).clickable { err = null }.padding(horizontal = 8.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                aiMsg?.let { m ->
                    Column(Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(20.dp), Glass.THICK, shadow = 6.dp).padding(14.dp)) {
                        Text(m, fontSize = 12.sp, color = C.ClayDeep, lineHeight = 17.sp)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("让 AI 再找一次"), fontSize = 12.sp, color = C.OnClay, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Clay).clickable { startAi() }.padding(horizontal = 12.dp, vertical = 6.dp))
                            Spacer(Modifier.weight(1f))
                            Text("×", fontSize = 16.sp, color = C.Faint, modifier = Modifier.clip(CircleShape).clickable { aiMsg = null }.padding(horizontal = 8.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                val run = ai
                if (run != null) JwAiProgress(run) { run.stop.set(true); ai = null; aiMsg = tr("已停止，网页还在原来的地方。"); jwAiLog("STOP") }
                else Row(Modifier.fillMaxWidth().liquidGlass(CircleShape, Glass.THICK, shadow = 10.dp).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (busy) (if (pending) tr("页面还在加载，加载完自动导入…") else tr("正在{0}…", tr(JwOutcome.stepLabel(stepNow)))) else if (kind in setOf("generic", "cf", "nr", "umooc")) tr("登录后打开课表页面，再点导入") else tr("登录后直接点导入，不用打开课表页面"), fontSize = 12.sp, color = C.Soft, lineHeight = 16.sp, modifier = Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.glassButton(CircleShape, tint = C.Clay, fill = if (busy) C.Track.copy(alpha = 0.7f) else C.Clay.copy(alpha = 0.9f), shadow = 0.dp, enabled = !busy) { grab(null) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = C.Clay, strokeWidth = 2.dp)
                        else Text(tr("导入课表"), fontSize = 15.sp, color = C.OnClay, fontWeight = FontWeight.Bold, style = LocalTextStyle.current.copy(shadow = null))
                    }
                }
            }
        }
    }
    if (askConsent) AlertDialog(onDismissRequest = { askConsent = false }, containerColor = Color.Transparent, tonalElevation = 0.dp, shape = DialogShape, modifier = dialogGlass(),
        title = { Text(tr("AI 帮我找课表"), color = C.Ink) },
        text = {
            Text(tr("请你自己先在网页里登录。之后 AI 会读取当前网页的文字、表格和截图，发给你在「AI 设置」里填的服务商（{0}），由它帮你点到课表页面、读出课表，再进导入预览。AI 不能输入任何东西，也不会碰密码框，账号密码不会发出去。最多 {1} 步，随时可以停止。",
                hostOf(Ai.settings(c).base).ifEmpty { tr("你自己的服务商") }, JwAgent.MAX_STEPS), fontSize = 13.sp, color = C.Soft, lineHeight = 19.sp)
        },
        confirmButton = { TextButton({ askConsent = false; JwAiConsent.accept(c); startAi() }) { Text(tr("同意并开始"), color = C.ClayDeep, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton({ askConsent = false }) { Text(tr("取消"), color = C.Soft) } })
    if (askSetup) AlertDialog(onDismissRequest = { askSetup = false }, containerColor = Color.Transparent, tonalElevation = 0.dp, shape = DialogShape, modifier = dialogGlass(),
        title = { Text(tr("还没设置 AI"), color = C.Ink) },
        text = { Text(tr("先在「我的 → AI 设置」里填好你自己的接口，再回来用「AI 帮我找课表」。"), fontSize = 13.sp, color = C.Soft, lineHeight = 19.sp) },
        confirmButton = { TextButton({ askSetup = false; Nav.open.value = "ai_settings" }) { Text(tr("去设置"), color = C.ClayDeep, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton({ askSetup = false }) { Text(tr("取消"), color = C.Soft) } })
    if (pickKind) AlertDialog(onDismissRequest = { pickKind = false }, containerColor = Color.Transparent, tonalElevation = 0.dp, shape = DialogShape, modifier = dialogGlass(),
        title = { Text(tr("换教务类型"), color = C.Ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(tr("导入不出来时换一个试试，网页和登录状态不变"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(bottom = 8.dp))
                for (k in Jw.KINDS) Text(tr(k.name) + if (k.exp) " · " + tr("实验性") else "", fontSize = 15.sp,
                    color = if (k.id == kind) C.ClayDeep else C.Ink, fontWeight = if (k.id == kind) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { pickKind = false; err = null; busy = false; tok++; onKind(k.id) }.padding(vertical = 11.dp, horizontal = 4.dp))
            }
        },
        confirmButton = {}, dismissButton = { TextButton({ pickKind = false }) { Text(tr("取消"), color = C.Soft) } })
    campuses?.let { cs ->
        AlertDialog(onDismissRequest = { campuses = null }, containerColor = Color.Transparent, tonalElevation = 0.dp, shape = DialogShape, modifier = dialogGlass(),
            title = { Text(tr("选择校区"), color = C.Ink) },
            text = {
                Column {
                    Text(tr("这学期你在好几个校区有课，选要导入的那个"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(bottom = 8.dp))
                    for ((id, n) in cs) Text(n, fontSize = 15.sp, color = C.Ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable { campuses = null; grab(id) }.padding(vertical = 12.dp, horizontal = 4.dp))
                }
            },
            confirmButton = {}, dismissButton = { TextButton({ campuses = null }) { Text(tr("取消"), color = C.Soft) } })
    }
}


/** 一次 AI 找课表：进度（每一步）和停止标记。 */
class JwAiRun {
    val steps = mutableStateListOf<JwAgent.Step>()
    val stop = java.util.concurrent.atomic.AtomicBoolean(false)
}

private fun jwAiLog(s: String) { if (BuildConfig.HOOKS) android.util.Log.i("ZhishiTest", "JWAI $s") }

/** 进度里的一行。 */
internal fun jwAiStepText(s: JwAgent.Step): String = when {
    s.refused && s.tool == "click" -> tr("已拦下：AI 不能碰输入框和密码框")
    s.refused -> tr("已拦下：AI 只能读页面和点链接，不能输入")
    s.tool == "read_page" -> tr("读取页面")
    s.tool == "click" -> tr("点击「{0}」", s.label)
    s.tool == "back" -> tr("返回上一页")
    s.tool == "scroll" -> if (s.label == "up") tr("向上滚动") else tr("向下滚动")
    s.tool == "screenshot" -> tr("截图给看图模型看")
    s.tool == "submit_timetable" -> tr("整理出 {0} 节课", s.label)
    else -> s.tool
}

internal fun jwAiOutcomeText(o: JwAgent.Outcome): String = when (o) {
    is JwAgent.Outcome.NeedLogin -> tr("AI 看到的还是登录页：请先在网页里登录（二次认证也要做完），再让 AI 找。") + if (o.message.isNotBlank()) "\n" + o.message.take(120) else ""
    is JwAgent.Outcome.NotFound -> tr("AI 没找到课表。可以自己在网页里打开课表页面，再让 AI 读一次。") + if (o.reason.isNotBlank() && o.reason != "empty") "\n" + o.reason.take(160) else ""
    is JwAgent.Outcome.StepLimit -> tr("AI 走了 {0} 步还没找到课表。可以自己先打开课表页面，再让 AI 读一次。", JwAgent.MAX_STEPS)
    is JwAgent.Outcome.Failed -> aiErrorText(o.error)
    is JwAgent.Outcome.Stopped -> tr("已停止，网页还在原来的地方。")
    is JwAgent.Outcome.Found -> ""
}

@Composable
private fun JwAiProgress(run: JwAiRun, onStop: () -> Unit) {
    val last = run.steps.lastOrNull()
    Column(Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(22.dp), Glass.THICK, shadow = 10.dp).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), color = C.Clay, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("AI 正在找课表"), fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
                Text(tr("第 {0} / {1} 步", last?.n ?: 0, JwAgent.MAX_STEPS), fontSize = 11.sp, color = C.Faint)
            }
            Box(Modifier.glassButton(CircleShape, tint = C.Clay, fill = C.Sand, shadow = 0.dp) { onStop() }.padding(horizontal = 16.dp, vertical = 9.dp)) {
                Text(tr("停止"), fontSize = 13.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
            }
        }
        val shown = run.steps.takeLast(4)
        if (shown.isEmpty()) Text(tr("AI 在看网页…"), fontSize = 12.sp, color = C.Soft, modifier = Modifier.padding(top = 6.dp))
        shown.forEachIndexed { i, st ->
            val now = i == shown.size - 1
            Text((if (st.refused) "⛔ " else if (now) "› " else "✓ ") + jwAiStepText(st), fontSize = 12.sp, color = if (st.refused) C.ClayDeep else if (now) C.Ink else C.Soft,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Text(tr("网页内容只发给你在「AI 设置」里填的服务商；AI 不能输入任何东西。"), fontSize = 10.sp, color = C.Faint, lineHeight = 14.sp, modifier = Modifier.padding(top = 6.dp, end = 8.dp))
    }
}
