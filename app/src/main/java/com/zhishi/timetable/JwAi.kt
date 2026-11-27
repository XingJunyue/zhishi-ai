package com.zhishi.timetable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/*
 * 「AI 帮我找课表」的安卓那层：JwPage 的 WebView 实现（读页面、点、返回、滚动、截图）。
 * 流程和规则在纯 Kotlin 的 JwAgent（JwAgent.kt，单测 JwAgentTest）。
 * 这里的脚本只读页面上能看到的文字、表格和可点的东西：**不读任何输入框的值**；点击前再查一遍，输入框 / 密码框一律不点。
 */

/** read_page 的脚本：给可点的东西编号（存在 window.__zsai），连同 iframe / frame（同源的）一起读。 */
internal val JW_AI_READ = """(function(){
var out={url:location.href,title:document.title,items:[],tables:[],text:'',pw:false};var list=[];window.__zsai=list;
function vis(e){try{var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return false;var s=e.ownerDocument.defaultView.getComputedStyle(e);return s.visibility!='hidden'&&s.display!='none';}catch(x){return true}}
function kind(e){var tg=e.tagName.toLowerCase(),ty=(e.getAttribute('type')||'text').toLowerCase();
 if(tg=='input'){if(ty=='password')return 'password';if(/^(button|submit|reset|image)$/.test(ty))return 'button';if(/^(checkbox|radio)$/.test(ty))return 'option';if(ty=='hidden')return '';return 'input';}
 if(tg=='textarea')return 'input';if(tg=='select')return 'select';if(tg=='a')return 'link';return 'button';}
function lab(e,k){var tg=e.tagName.toLowerCase();
 if(tg=='input'&&k=='button')return (e.value||e.getAttribute('alt')||'').trim();
 if(tg=='input'||tg=='textarea'||tg=='select')return (e.getAttribute('placeholder')||e.getAttribute('aria-label')||e.getAttribute('title')||e.getAttribute('name')||'').trim();
 var t=(e.innerText||e.textContent||'').replace(/\s+/g,' ').trim();
 if(!t){var im=e.querySelector&&e.querySelector('img[alt]');t=(e.getAttribute('title')||e.getAttribute('aria-label')||(im?im.alt:'')||'').trim();}return t;}
function walk(doc,depth){if(!doc)return;
 var els=doc.querySelectorAll('a[href],a[onclick],button,input,select,textarea,[onclick],[role=button],[role=link],[role=menuitem],[role=tab],[role=treeitem]');
 for(var i=0;i<els.length&&list.length<200;i++){var e=els[i],k=kind(e);if(!k||!vis(e))continue;if(k=='password')out.pw=true;
  var l=lab(e,k).slice(0,60);if(!l&&k!='password'&&k!='input')continue;
  if(k!='password'&&k!='input'&&l.length>40&&e.querySelector&&e.querySelector('a[href],button'))continue;
  list.push(e);out.items.push({n:list.length,k:k,t:l});}
 var ts=doc.querySelectorAll('table');
 for(var j=0;j<ts.length&&out.tables.length<6;j++){var rows=ts[j].rows,s=[];if(ts[j].querySelector('table'))continue;
  for(var r=0;r<rows.length&&r<40;r++){var cs=[];for(var c=0;c<rows[r].cells.length;c++)cs.push((rows[r].cells[c].innerText||'').replace(/\s+/g,' ').trim().slice(0,90));s.push(cs.join(' | '));}
  var tx=s.join('\n');if(tx.replace(/[|\s]/g,'').length>10)out.tables.push(tx.slice(0,3000));}
 if(doc.body)out.text+=(doc.body.innerText||'').replace(/[ \t]+/g,' ').replace(/\n\s*\n+/g,'\n').slice(0,4000)+'\n';
 if(depth<3){var fs=doc.querySelectorAll('iframe,frame');for(var f=0;f<fs.length;f++){try{walk(fs[f].contentDocument,depth+1)}catch(x){}}}}
walk(document,0);out.text=out.text.slice(0,4000);return JSON.stringify(out);})()"""

/** click 的脚本：再查一遍不是输入框 / 密码框；新窗口打开的链接改成在本页打开。 */
internal fun jwAiClick(n: Int) = """(function(n){var l=window.__zsai;if(!l||!l[n-1])return 'error: no item ['+n+'] any more; call read_page again';var e=l[n-1];
var tg=e.tagName.toLowerCase(),ty=(e.getAttribute('type')||'text').toLowerCase();
if(tg=='textarea'||(tg=='input'&&!/^(button|submit|reset|image|checkbox|radio)$/.test(ty)))return 'refused: form fields are for the user only';
try{if(tg=='a'&&e.target&&e.target!='_self')e.target='_self';}catch(x){}
try{e.scrollIntoView({block:'center'});}catch(x){}
try{e.click();}catch(x){return 'error: '+x;}return 'ok, clicked ['+n+']';})($n)"""

internal const val JW_AI_SCROLL = """(function(d){var h=window.innerHeight||600;window.scrollBy(0,d*h*0.8);
var y=Math.round(window.scrollY||0),t=Math.round(document.documentElement.scrollHeight||0);return 'ok, scrolled to '+y+' of '+t;})"""

/**
 * 一次 AI 找课表用的 WebView 封装。阻塞方法都在后台线程调，WebView 只在主线程碰。
 * started / finished 由导入页的 WebViewClient 调（判断点了之后有没有跳页、跳完没有）。
 */
class JwWebPage(private val wv: WebView) : JwPage {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var navs = 0
    @Volatile var loading = false; private set
    fun started() { navs++; loading = true }
    fun finished() { loading = false }

    private fun <T> onMain(timeoutMs: Long = 15_000, f: ((T) -> Unit) -> Unit): T? {
        val box = AtomicReference<T?>(null); val l = CountDownLatch(1)
        main.post { try { f { box.set(it); l.countDown() } } catch (x: Throwable) { l.countDown() } }
        l.await(timeoutMs, TimeUnit.MILLISECONDS)
        return box.get()
    }

    private fun js(code: String): String? = onMain<String> { done ->
        wv.evaluateJavascript(code) { r -> done(unquote(r)) }
    }

    private fun unquote(r: String?): String {
        if (r == null || r == "null") return ""
        return try { JSONArray("[$r]").optString(0) } catch (x: Exception) { r }
    }

    /** 点了 / 返回之后等一下：开始跳页就等它加载完（最多 12 秒），再给页面一点时间画出来。 */
    private fun settle(before: Int) {
        Thread.sleep(800)
        if (navs != before || loading) {
            val t0 = System.currentTimeMillis()
            while (loading && System.currentTimeMillis() - t0 < 12_000) Thread.sleep(150)
        }
        Thread.sleep(500)
    }

    override fun read(): JwPageView {
        val t0 = System.currentTimeMillis()
        while (loading && System.currentTimeMillis() - t0 < 8_000) Thread.sleep(150)
        val s = js(JW_AI_READ).orEmpty()
        return try { JwPageView.parse(s) } catch (x: Exception) { JwPageView(onMain<String> { it(wv.url ?: "") }.orEmpty(), "", "(the page could not be read)") }
    }

    override fun click(n: Int): String {
        val before = navs
        val r = js(jwAiClick(n)) ?: "error: timeout"
        if (r.startsWith("ok")) settle(before)
        return r.ifEmpty { "error: no result" }
    }

    override fun back(): String {
        val before = navs
        val went = onMain<Boolean> { done -> if (wv.canGoBack()) { wv.goBack(); done(true) } else done(false) } ?: false
        if (!went) return "error: there is no previous page"
        settle(before)
        return "ok, went back"
    }

    override fun scroll(down: Boolean): String {
        val r = js("$JW_AI_SCROLL(${if (down) 1 else -1})") ?: "error: timeout"
        Thread.sleep(400)
        return r
    }

    override fun screenshot(): AiImage? = onMain<AiImage?>(20_000) { done ->
        val w = wv.width; val h = wv.height
        if (w <= 0 || h <= 0) { done(null) } else {
            val k = minOf(1f, 1600f / maxOf(w, h))
            val bmp = Bitmap.createBitmap((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp); cv.scale(k, k); cv.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
            wv.draw(cv)
            Thread {
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                done(AiImage("image/jpeg", android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)))
            }.start()
        }
    }
}

/** 一次性说明「网页内容会发给你自己的服务商」：同意过就不再弹。 */
object JwAiConsent {
    private fun p(c: Context) = c.getSharedPreferences("jwimport", Context.MODE_PRIVATE)
    fun ok(c: Context) = p(c).getBoolean("ai_ok", false)
    fun accept(c: Context) = p(c).edit().putBoolean("ai_ok", true).apply()
    fun reset(c: Context) = p(c).edit().remove("ai_ok").apply()
}
