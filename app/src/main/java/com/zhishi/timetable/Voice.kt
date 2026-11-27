package com.zhishi.timetable

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 离线语音输入：sherpa-onnx（k2-fsa，Apache-2.0）流式中文识别，声音只在手机上转成文字，不上传、不存录音。
 * 模型不打进安装包，第一次用时下载（AsrSpec / AsrInstaller，Asr.kt）到 filesDir/asr/model。
 * 只带 arm64-v8a / armeabi-v7a 的 so；别的架构（或 so 加载失败）engineOk = false，界面上说明用不了。
 */
object Voice {
    private const val TAG = "ZhishiVoice"
    private const val RATE = 16000
    /** 一次最多录 60 秒，到了自动停（字留在输入框）。 */
    private const val MAX_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())

    // ---- 界面状态（Compose） ----
    /** 正在录（手指按着）。 */
    val recording = mutableStateOf(false)
    /** 松手后还在把最后一点声音识别完。 */
    val finishing = mutableStateOf(false)
    /** 上滑过了：松开就取消。 */
    val armed = mutableStateOf(false)
    /** 这次说的话（已识别的几段 + 正在说的）。 */
    val live = mutableStateOf("")
    /** 开始录时输入框里原来的字：识别出来的接在它后面，取消就还原成它。 */
    var prefix = ""
    /** 要弹的框：model（下载模型）/ perm（说明后申请麦克风）/ blocked（权限被永久拒绝，去设置）。 */
    val dialog = mutableStateOf<String?>(null)
    /** 模型下载状态。 */
    val dl = mutableStateOf(AsrDl(AsrDl.Phase.IDLE))
    /** 每取消一次加 1：输入框还原成 prefix。 */
    val cancelled = mutableStateOf(0)
    /** 模型装好 / 删掉了：界面重读。 */
    val ver = mutableStateOf(0)

    val active: Boolean get() = recording.value || finishing.value

    /** sherpa-onnx 的 so 能不能加载（x86 等没带 so 的机器上是 false）。 */
    val engineOk: Boolean by lazy {
        try { System.loadLibrary("sherpa-onnx-jni"); true } catch (t: Throwable) { Log.w(TAG, "no native asr: $t"); false }
    }

    fun installer(c: Context) = AsrInstaller(File(c.filesDir, "asr"), HttpFetch)
    fun installed(c: Context): Boolean = installer(c).installed()

    // ---- 下载 ----
    @Volatile private var cancelDl = false

    /** 在后台下载（已经在下就什么也不做）。主地址失败依次换镜像，校验 sha256 后解包。 */
    fun download(c: Context, fetch: AsrFetch = HttpFetch, urls: List<String> = AsrSpec.urls(), done: (AsrDl) -> Unit = {}) {
        if (dl.value.busy) return
        cancelDl = false
        val app = c.applicationContext
        dl.value = AsrDl(AsrDl.Phase.DOWNLOADING, 0, AsrSpec.SIZE)
        Thread {
            val r = try {
                AsrInstaller(File(app.filesDir, "asr"), fetch).run(urls, { s -> main.post { dl.value = s } }, { cancelDl })
            } catch (t: Throwable) { Log.w(TAG, "download: $t"); AsrDl(AsrDl.Phase.FAILED, error = AsrDl.Err.UNPACK) }
            main.post { dl.value = r; if (r.phase == AsrDl.Phase.READY) ver.value++; done(r) }
        }.apply { isDaemon = true; name = "asr-download" }.start()
    }

    fun cancelDownload() { cancelDl = true }

    /** 删掉模型（「AI 设置」里）。 */
    fun delete(c: Context) {
        stop(commit = false)
        synchronized(this) { rec?.release(); rec = null }
        installer(c).delete()
        dl.value = AsrDl(AsrDl.Phase.IDLE)
        ver.value++
    }

    /** 用 HttpURLConnection 下载（跟随跳转：GitHub release 会跳到对象存储）。 */
    object HttpFetch : AsrFetch {
        override fun open(url: String): AsrBody {
            var u = url
            repeat(6) {
                val con = URL(u).openConnection() as HttpURLConnection
                con.connectTimeout = 10_000; con.readTimeout = 20_000
                con.instanceFollowRedirects = false
                con.setRequestProperty("User-Agent", "ZhishiAI")
                val rc = con.responseCode
                if (rc in 300..399) {
                    val loc = con.getHeaderField("Location") ?: throw IOException("redirect without location")
                    con.disconnect(); u = URL(URL(u), loc).toString(); return@repeat
                }
                if (rc != 200) { con.disconnect(); throw IOException("HTTP $rc") }
                return AsrBody(con.contentLengthLong, con.inputStream)
            }
            throw IOException("too many redirects")
        }
    }

    // ---- 权限 ----
    fun granted(c: Context) = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun prefs(c: Context) = c.getSharedPreferences("voice", Context.MODE_PRIVATE)
    fun markAsked(c: Context) = prefs(c).edit().putBoolean("asked", true).apply()
    fun perm(c: Context): MicPerm = VoiceFlow.perm(granted(c), prefs(c).getBoolean("asked", false),
        (c as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ?: false)

    // ---- 识别 ----
    private var rec: OnlineRecognizer? = null

    @Synchronized
    fun recognizer(c: Context): OnlineRecognizer {
        rec?.let { return it }
        val d = installer(c).modelDir
        val cfg = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = File(d, AsrSpec.MODEL).absolutePath),
                tokens = File(d, AsrSpec.TOKENS).absolutePath, numThreads = 2),
            // 停顿后切一段（长句子不会越拖越慢），段与段拼起来
            endpointConfig = EndpointConfig(rule1 = EndpointRule(false, 2.4f, 0f), rule2 = EndpointRule(true, 1.2f, 0f), rule3 = EndpointRule(false, 0f, 20f)),
            enableEndpoint = true,
        )
        return OnlineRecognizer(null, cfg).also { rec = it }
    }

    private class Session(val c: Context, val onError: (String) -> Unit) {
        @Volatile var stop = false
        @Volatile var cancel = false

        @SuppressLint("MissingPermission")
        fun run() {
            var ar: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                // 4 秒的缓冲：第一次加载模型（不到 1 秒）时说的话也不会丢
                val size = maxOf(min, RATE * 2 * 4)
                for (src in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                    val a = try { AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size) } catch (e: Exception) { null }
                    if (a != null && a.state == AudioRecord.STATE_INITIALIZED) { ar = a; break }
                    a?.release()
                }
                val r0 = ar ?: return fail(tr("麦克风打不开，可能正被别的应用占用"))
                r0.startRecording()
                if (r0.recordingState != AudioRecord.RECORDSTATE_RECORDING) return fail(tr("麦克风打不开，可能正被别的应用占用"))
                val r = try { recognizer(c) } catch (t: Throwable) { Log.w(TAG, "load: $t"); return fail(tr("语音模型加载失败，可以在「AI 设置」里删掉后重新下载")) }
                val st = r.createStream()
                val buf = ShortArray(RATE / 10)
                val segs = ArrayList<String>()
                var last = ""
                val t0 = SystemClock.elapsedRealtime()
                fun push(cur: String) {
                    val t = VoiceFlow.spoken(segs, cur)
                    if (t != last) { last = t; main.post { if (!cancel) live.value = t } }
                }
                while (!stop && !cancel) {
                    if (SystemClock.elapsedRealtime() - t0 > MAX_MS) break
                    val n = r0.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    st.acceptWaveform(FloatArray(n) { buf[it] / 32768f }, RATE)
                    while (r.isReady(st)) r.decode(st)
                    val cur = r.getResult(st).text
                    if (r.isEndpoint(st)) { if (cur.isNotBlank()) segs += cur.trim(); r.reset(st); push("") } else push(cur)
                }
                r0.stop()
                if (!cancel) {
                    // 尾巴补 0.3 秒静音，把最后几个字也吐出来
                    st.acceptWaveform(FloatArray(RATE * 3 / 10), RATE)
                    st.inputFinished()
                    while (r.isReady(st)) r.decode(st)
                    push(r.getResult(st).text)
                }
                st.release()
            } catch (t: Throwable) {
                Log.w(TAG, "session: $t")
                fail(tr("语音识别出错了，再试一次"))
            } finally {
                try { ar?.release() } catch (e: Exception) {}
                main.post { if (session === this) { session = null; recording.value = false; finishing.value = false; armed.value = false } }
            }
        }

        private fun fail(msg: String) { main.post { if (!cancel) onError(msg) } }
    }

    private var session: Session? = null

    /** 开始录（调用前已确认有模型、有权限）。 */
    fun start(c: Context, onError: (String) -> Unit) {
        if (session != null) return
        live.value = ""; armed.value = false
        recording.value = true; finishing.value = false
        val s = Session(c.applicationContext, onError)
        session = s
        Thread { s.run() }.apply { isDaemon = true; name = "asr-record" }.start()
    }

    /** 松手：commit = 留下识别的字（再等最后一点识别完），否则取消。 */
    fun stop(commit: Boolean) {
        val s = session
        if (s == null) { recording.value = false; finishing.value = false; armed.value = false; return }
        recording.value = false
        if (commit) { finishing.value = true; s.stop = true }
        else { s.cancel = true; finishing.value = false; armed.value = false; live.value = ""; cancelled.value++ }
    }

    /** 调试钩子用：把一个 16kHz 单声道 16 位 wav 喂给识别器，返回文字。 */
    fun decodeWav(c: Context, f: File): String {
        val b = f.readBytes()
        // 找 data 块（跳过 RIFF 头里的其他块）
        var p = 12; var start = 44; var len = b.size - 44
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII)
            val sz = (b[p + 4].toInt() and 255) or ((b[p + 5].toInt() and 255) shl 8) or ((b[p + 6].toInt() and 255) shl 16) or ((b[p + 7].toInt() and 255) shl 24)
            if (id == "data") { start = p + 8; len = minOf(sz, b.size - start); break }
            p += 8 + sz
        }
        val n = len / 2
        val samples = FloatArray(n) { val lo = b[start + 2 * it].toInt() and 255; val hi = b[start + 2 * it + 1].toInt(); ((hi shl 8) or lo) / 32768f }
        val r = recognizer(c)
        val st = r.createStream()
        val segs = ArrayList<String>()
        var i = 0
        while (i < n) {
            val e = minOf(n, i + RATE / 10)
            st.acceptWaveform(samples.copyOfRange(i, e), RATE); i = e
            while (r.isReady(st)) r.decode(st)
            if (r.isEndpoint(st)) { r.getResult(st).text.takeIf { it.isNotBlank() }?.let { segs += it.trim() }; r.reset(st) }
        }
        st.acceptWaveform(FloatArray(RATE * 3 / 10), RATE); st.inputFinished()
        while (r.isReady(st)) r.decode(st)
        val out = VoiceFlow.spoken(segs, r.getResult(st).text)
        st.release()
        return out
    }
}

/** 下载失败给人话。 */
fun asrErrorText(e: AsrDl.Err?): String = when (e) {
    AsrDl.Err.CHECKSUM -> tr("下载的文件校验不通过（可能不完整），已删除，请重试")
    AsrDl.Err.UNPACK -> tr("解压失败，请重试；手机存储快满时也会这样")
    AsrDl.Err.SPACE -> tr("手机存储空间不够，至少要留出 {0}", AsrSpec.mb(AsrSpec.SIZE + AsrSpec.UNPACKED + 16_000_000))
    AsrDl.Err.CANCELLED -> tr("已取消下载")
    else -> tr("下载失败：主地址和镜像都没连上，换个网络再试")
}

/** 下载进度的一行字。 */
fun asrProgressText(s: AsrDl): String = when (s.phase) {
    AsrDl.Phase.DOWNLOADING -> tr("已下载 {0} / {1}", AsrSpec.mb(s.done), AsrSpec.mb(s.total)) + if (s.source > 0) tr("（镜像）") else ""
    AsrDl.Phase.VERIFYING -> tr("正在校验…")
    AsrDl.Phase.UNPACKING -> tr("正在解压…")
    AsrDl.Phase.READY -> tr("语音模型已就绪")
    AsrDl.Phase.FAILED -> asrErrorText(s.error)
    AsrDl.Phase.IDLE -> ""
}

/**
 * 输入框旁边的麦克风：按住说话、松开结束、上滑取消；识别出的字实时写进输入框（不自动发送）。
 * 第一次用先弹下载模型的框，再说明并申请麦克风权限。
 */
@Composable
fun MicButton(enabled: Boolean, toast: (String) -> Unit, inputNow: () -> String) {
    val c = LocalContext.current
    val cancelPx = with(LocalDensity.current) { 72.dp.toPx() }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Voice.markAsked(c)
        if (ok) toast(tr("可以了，按住麦克风说话"))
        else if (Voice.perm(c) == MicPerm.BLOCKED) Voice.dialog.value = "blocked"
        else toast(tr("没有麦克风权限，用不了语音输入"))
    }
    val toastNow by rememberUpdatedState(toast)
    val input by rememberUpdatedState(inputNow)
    val on = Voice.recording.value
    val armed = Voice.armed.value
    val pulse = rememberInfiniteTransition(label = "mic").animateFloat(1f, 1.14f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "p")
    val fill = when { armed -> C.ClayDeep.copy(alpha = 0.9f); on -> C.Clay.copy(alpha = 0.85f); else -> null }
    Box(Modifier.size(42.dp).semantics { contentDescription = tr("按住说话") }
        .graphicsLayer { val k = if (on) pulse.value else 1f; scaleX = k; scaleY = k }
        .liquidGlass(CircleShape, tint = C.Clay, fill = fill, shadow = 0.dp)
        .pointerInput(enabled) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val t0 = SystemClock.elapsedRealtime()
                val go = if (!enabled) VoiceFlow.Press.BUSY else VoiceFlow.press(Voice.engineOk, Voice.installed(c), Voice.dl.value.busy, Voice.perm(c), Voice.active)
                when (go) {
                    VoiceFlow.Press.START -> { Voice.prefix = input(); Voice.start(c) { toastNow(it) } }
                    VoiceFlow.Press.NEED_MODEL -> Voice.dialog.value = "model"
                    VoiceFlow.Press.EXPLAIN_PERMISSION -> Voice.dialog.value = "perm"
                    VoiceFlow.Press.PERMISSION_BLOCKED -> Voice.dialog.value = "blocked"
                    VoiceFlow.Press.UNSUPPORTED -> toastNow(tr("这台手机用不了离线语音输入"))
                    VoiceFlow.Press.BUSY -> {}
                }
                var isArmed = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    if (go == VoiceFlow.Press.START) {
                        isArmed = VoiceFlow.cancelArmed(ch.position.y - down.position.y, cancelPx)
                        if (Voice.armed.value != isArmed) Voice.armed.value = isArmed
                    }
                    ch.consume()
                }
                if (go == VoiceFlow.Press.START) {
                    when (VoiceFlow.release(SystemClock.elapsedRealtime() - t0, isArmed, Voice.live.value)) {
                        VoiceFlow.Release.COMMIT -> Voice.stop(commit = true)
                        VoiceFlow.Release.CANCEL -> Voice.stop(commit = false)
                        VoiceFlow.Release.TOO_SHORT -> { Voice.stop(commit = false); toastNow(tr("按住麦克风说话，松开结束")) }
                    }
                }
            }
        }, contentAlignment = Alignment.Center) { MicGlyph(if (fill != null) C.OnClay else C.Ink) }

    when (Voice.dialog.value) {
        "model" -> VoiceModelDialog(onClose = { Voice.dialog.value = null }, onReady = {
            Voice.dialog.value = null
            if (Voice.perm(c) == MicPerm.GRANTED) toast(tr("语音模型已就绪，按住麦克风说话")) else Voice.dialog.value = "perm"
        })
        "perm" -> VoiceAskDialog(tr("使用麦克风"), tr("按住麦克风说话时，知时 AI 要用麦克风录音。声音只在这台手机上离线转成文字，不会上传，也不保存录音。"), tr("继续"),
            onYes = { Voice.dialog.value = null; ask.launch(Manifest.permission.RECORD_AUDIO) }, onNo = { Voice.dialog.value = null })
        "blocked" -> VoiceAskDialog(tr("麦克风权限被关掉了"), tr("要用语音输入，请在系统设置里给知时 AI 打开「麦克风」权限。"), tr("去设置"),
            onYes = { Voice.dialog.value = null; openAppSettings(c) }, onNo = { Voice.dialog.value = null })
    }
}

/** 录音时输入框上面的提示条：正在听 / 松开取消。 */
@Composable
fun VoiceBar() {
    if (!Voice.active) return
    val armed = Voice.armed.value
    val t = when { armed -> tr("松开手指，取消这次输入"); Voice.finishing.value -> tr("正在识别…"); else -> tr("正在听…松开结束，上滑取消") }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (armed) C.ClayDeep.copy(alpha = 0.92f) else C.Ink.copy(alpha = 0.86f)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (armed) C.Cream else C.ClayPale))
        Text(t, fontSize = 13.sp, color = C.Cream, modifier = Modifier.padding(start = 10.dp).weight(1f),
            style = androidx.compose.material3.LocalTextStyle.current.copy(shadow = null))
    }
}

/** 下载模型的小框：大小、「下载」、进度；失败可重试；下载时可以收起（后台继续）或取消。 */
@Composable
fun VoiceModelDialog(onClose: () -> Unit, onReady: () -> Unit) {
    val c = LocalContext.current
    val s = Voice.dl.value
    LaunchedEffect(s.phase) { if (s.phase == AsrDl.Phase.READY && Voice.installed(c)) onReady() }
    AlertDialog(onDismissRequest = onClose, containerColor = Color.Transparent, tonalElevation = 0.dp, shape = DialogShape, modifier = dialogGlass(),
        title = { Text(tr("下载语音模型"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Ink) },
        text = {
            Column {
                Text(tr("语音输入在手机上离线识别，声音不会上传。第一次用要下载中文语音模型（{0}，解压后约 {1}），之后不用联网。建议连上 Wi-Fi 再下载。",
                    AsrSpec.mb(AsrSpec.SIZE), AsrSpec.mb(AsrSpec.UNPACKED)), fontSize = 14.sp, color = C.Soft, lineHeight = 20.sp)
                if (s.busy) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(progress = { if (s.phase == AsrDl.Phase.DOWNLOADING) s.fraction else 1f }, color = C.Clay, trackColor = C.Track,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                }
                if (s.phase != AsrDl.Phase.IDLE && s.phase != AsrDl.Phase.READY)
                    Text(asrProgressText(s), fontSize = 13.sp, color = if (s.phase == AsrDl.Phase.FAILED) C.ClayDeep else C.Soft, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            if (s.busy) DialogText(tr("收起"), strong = true, onClose)
            else DialogText(if (s.phase == AsrDl.Phase.FAILED) tr("重试") else tr("下载"), strong = true) { Voice.download(c) }
        },
        dismissButton = { if (s.busy) DialogText(tr("取消下载"), strong = false) { Voice.cancelDownload() } else DialogText(tr("取消"), strong = false, onClose) })
}

@Composable
private fun VoiceAskDialog(title: String, body: String, yes: String, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(onDismissRequest = onNo, containerColor = Color.Transparent, tonalElevation = 0.dp, shape = DialogShape, modifier = dialogGlass(),
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Ink) },
        text = { Text(body, fontSize = 14.sp, color = C.Soft, lineHeight = 20.sp) },
        confirmButton = { DialogText(yes, strong = true, onYes) },
        dismissButton = { DialogText(tr("取消"), strong = false, onNo) })
}

@Composable
private fun DialogText(t: String, strong: Boolean, onClick: () -> Unit) {
    Text(t, fontSize = 15.sp, color = if (strong) C.ClayDeep else C.Soft, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp))
}

/** 「AI 设置」里的一块：语音模型的状态、下载、删除。 */
@Composable
fun VoiceSettings() {
    val c = LocalContext.current
    val s = Voice.dl.value
    val has = remember(Voice.ver.value, s.phase) { Voice.installed(c) }
    val bytes = remember(Voice.ver.value, has) { if (has) Voice.installer(c).bytes() else 0L }
    var confirm by remember { mutableStateOf(false) }
    SectionTitle(tr("语音输入（离线）"), tr("AI 助手输入框旁边的麦克风：按住说话、松开结束、上滑取消。在手机上识别，不联网、不上传。"))
    val status = when {
        !Voice.engineOk -> tr("这台手机用不了离线语音输入")
        has -> tr("语音模型已下载（{0}）", AsrSpec.mb(bytes))
        s.busy || s.phase == AsrDl.Phase.FAILED -> asrProgressText(s)
        else -> tr("语音模型还没下载（{0}）", AsrSpec.mb(AsrSpec.SIZE))
    }
    Text(status, fontSize = 13.sp, color = if (s.phase == AsrDl.Phase.FAILED && !has) C.ClayDeep else C.Soft, lineHeight = 18.sp, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    if (s.busy) LinearProgressIndicator(progress = { if (s.phase == AsrDl.Phase.DOWNLOADING) s.fraction else 1f }, color = C.Clay, trackColor = C.Track,
        modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
    Spacer(Modifier.height(10.dp))
    if (Voice.engineOk) when {
        has -> GhostButton(tr("删除语音模型"), Modifier.fillMaxWidth()) { confirm = true }
        s.busy -> GhostButton(tr("取消下载"), Modifier.fillMaxWidth()) { Voice.cancelDownload() }
        else -> GhostButton(tr("下载语音模型（{0}）", AsrSpec.mb(AsrSpec.SIZE)), Modifier.fillMaxWidth()) { Voice.download(c) }
    }
    if (confirm) ConfirmDialog(tr("删除语音模型？"), tr("删掉后语音输入要重新下载模型（{0}）才能用。", AsrSpec.mb(AsrSpec.SIZE)), tr("删除"),
        onYes = { confirm = false; Voice.delete(c) }, onNo = { confirm = false })
}

/** 麦克风小图标：话筒 + 托架 + 底座。 */
@Composable
fun MicGlyph(col: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width; val sw = w * 0.09f
        drawRoundRect(col, Offset(w * 0.36f, w * 0.1f), Size(w * 0.28f, w * 0.48f), CornerRadius(w * 0.14f), style = Stroke(sw))
        drawArc(col, 0f, 180f, false, Offset(w * 0.22f, w * 0.26f), Size(w * 0.56f, w * 0.5f), style = Stroke(sw))
        drawLine(col, Offset(w * 0.5f, w * 0.76f), Offset(w * 0.5f, w * 0.9f), sw)
        drawLine(col, Offset(w * 0.34f, w * 0.9f), Offset(w * 0.66f, w * 0.9f), sw)
    }
}
