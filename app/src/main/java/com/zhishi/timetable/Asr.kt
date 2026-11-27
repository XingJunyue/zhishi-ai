package com.zhishi.timetable

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/*
 * 离线语音输入（纯 Kotlin，不依赖 Android，单测 AsrTest / VoiceFlowTest）：
 *  - AsrSpec：用哪个模型、从哪下载、校验值
 *  - AsrInstaller：下载 → 校验 sha256 → 解包到 App 私有目录的状态机（主地址失败换镜像）
 *  - VoiceFlow：按住说话的流程（先要模型、再要麦克风权限）、上滑取消、识别文字拼进输入框
 * 录音和识别本身在 Voice.kt（sherpa-onnx，k2-fsa，Apache-2.0）。
 */

/** 模型：sherpa-onnx 的流式 Zipformer CTC 中文小模型（int8），k2-fsa 发布，Apache-2.0。不打进安装包，第一次用时下载。 */
object AsrSpec {
    const val NAME = "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01"
    const val FILE = "$NAME.tar.bz2"
    /** 压缩包大小（字节）和 sha256：k2-fsa 发布的原文件，镜像是同一个文件。 */
    const val SIZE = 21_264_113L
    const val SHA256 = "b3b309f7ce4a737195fcc6963ea19b0653a7d3401580af5ae0d3e284cbb71f0b"
    /** 解出来只留识别要用的两个文件（测试音频不要），合计字节数。 */
    const val UNPACKED = 26_355_706L
    const val MODEL = "model.int8.onnx"
    const val TOKENS = "tokens.txt"
    val FILES = listOf(MODEL, TOKENS)
    /** 主地址：k2-fsa/sherpa-onnx 的 GitHub release。 */
    const val PRIMARY = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$FILE"
    /** 镜像：知时 AI 仓库自己的 release「asr-model」里放了同一个文件。 */
    const val MIRROR = "https://github.com/XingJunyue/zhishi-ai/releases/download/asr-model/$FILE"
    /** 国内加速（和应用内更新一样，只代理 github.com 的下载链接）。 */
    val PROXIES = listOf("https://ghfast.top/", "https://gh-proxy.com/")
    /** 依次尝试：主地址 → 镜像 → 加速后的镜像。 */
    fun urls(): List<String> = listOf(PRIMARY, MIRROR) + PROXIES.map { it + MIRROR }
    /** 给人看的大小，比如「21 MB」。 */
    fun mb(bytes: Long): String = "${(bytes + 500_000) / 1_000_000} MB"
}

/** 一次 HTTP 下载的响应：length < 0 = 服务器没给长度。 */
class AsrBody(val length: Long, val stream: InputStream)

/** 打开一个地址（连不上 / 非 200 抛 IOException）。Android 上是 HttpURLConnection，单测里换成假的。 */
fun interface AsrFetch { @Throws(IOException::class) fun open(url: String): AsrBody }

/** 下载状态。 */
data class AsrDl(
    val phase: Phase,
    val done: Long = 0,
    val total: Long = 0,
    /** 当前在用第几个地址（0 = 主地址）。 */
    val source: Int = 0,
    val error: Err? = null,
) {
    enum class Phase { IDLE, DOWNLOADING, VERIFYING, UNPACKING, READY, FAILED }
    enum class Err { NETWORK, CHECKSUM, UNPACK, SPACE, CANCELLED }
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    val busy: Boolean get() = phase == Phase.DOWNLOADING || phase == Phase.VERIFYING || phase == Phase.UNPACKING
}

/**
 * 下载 → 校验 → 解包。root = App 私有目录下的 asr/；模型最后在 root/model/，里面有 ok 标记（写着 sha256）。
 * 中途失败、校验不对都会删掉半截文件；解包先解到 model.tmp 再改名，不会留下半套模型。
 */
class AsrInstaller(
    val root: File,
    private val fetch: AsrFetch,
    private val sha256: String = AsrSpec.SHA256,
    private val size: Long = AsrSpec.SIZE,
    private val unpacked: Long = AsrSpec.UNPACKED,
    private val files: List<String> = AsrSpec.FILES,
    private val unpack: (File, File) -> Unit = { src, dst -> AsrUnpack.tarBz2(src, dst, files) },
    private val freeSpace: () -> Long = { root.apply { mkdirs() }.usableSpace },
) {
    val modelDir: File get() = File(root, "model")
    private val part: File get() = File(root, "download.part")
    private val stage: File get() = File(root, "model.tmp")
    private val marker: File get() = File(modelDir, "ok")

    fun installed(): Boolean = try {
        marker.isFile && marker.readText().trim() == sha256 && files.all { File(modelDir, it).let { f -> f.isFile && f.length() > 0 } }
    } catch (e: IOException) { false }

    /** 删掉模型和所有半截文件。 */
    fun delete() { modelDir.deleteRecursively(); stage.deleteRecursively(); part.delete() }

    /** 占了多少空间（字节）。 */
    fun bytes(): Long = if (modelDir.isDirectory) modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    /**
     * 跑一遍（阻塞，放后台线程）。每个阶段、每 1% 进度回调一次 onState；cancelled() 返回 true 就尽快停下。
     * 返回最后的状态（READY 或 FAILED）。
     */
    fun run(urls: List<String>, onState: (AsrDl) -> Unit = {}, cancelled: () -> Boolean = { false }): AsrDl {
        fun emit(s: AsrDl): AsrDl { onState(s); return s }
        if (installed()) return emit(AsrDl(AsrDl.Phase.READY, size, size))
        root.mkdirs()
        // 压缩包 + 解出来的文件同时在盘上，再留 16MB 余量
        if (freeSpace() < size + unpacked + 16L * 1024 * 1024) return emit(AsrDl(AsrDl.Phase.FAILED, error = AsrDl.Err.SPACE))
        var badSum = false
        for ((i, url) in urls.withIndex()) {
            if (cancelled()) return cancel()
            emit(AsrDl(AsrDl.Phase.DOWNLOADING, 0, size, i))
            part.delete()
            val got: String? = try { download(url, i, ::emit, cancelled) } catch (e: IOException) { null }
            if (got == CANCELLED) return cancel()
            if (got == null) { part.delete(); continue }
            emit(AsrDl(AsrDl.Phase.VERIFYING, size, size, i))
            if (!got.equals(sha256, ignoreCase = true)) { badSum = true; part.delete(); continue }
            emit(AsrDl(AsrDl.Phase.UNPACKING, size, size, i))
            return try {
                stage.deleteRecursively(); stage.mkdirs()
                unpack(part, stage)
                if (!files.all { File(stage, it).let { f -> f.isFile && f.length() > 0 } }) throw IOException("missing files")
                File(stage, "ok").writeText(sha256)
                modelDir.deleteRecursively()
                if (!stage.renameTo(modelDir)) throw IOException("rename")
                part.delete()
                emit(AsrDl(AsrDl.Phase.READY, size, size, i))
            } catch (e: Throwable) {
                stage.deleteRecursively(); part.delete()
                emit(AsrDl(AsrDl.Phase.FAILED, source = i, error = AsrDl.Err.UNPACK))
            }
        }
        return emit(AsrDl(AsrDl.Phase.FAILED, error = if (badSum) AsrDl.Err.CHECKSUM else AsrDl.Err.NETWORK))
    }

    private fun cancel(): AsrDl { part.delete(); stage.deleteRecursively(); return AsrDl(AsrDl.Phase.FAILED, error = AsrDl.Err.CANCELLED) }

    /** 下到 part，边下边算 sha256。返回十六进制摘要；取消返回 CANCELLED；长度不对 / 太长返回 null（当这个地址坏了）。 */
    private fun download(url: String, i: Int, emit: (AsrDl) -> AsrDl, cancelled: () -> Boolean): String? {
        val body = fetch.open(url)
        // 镜像可能给了网页或别的文件：长度对不上直接换下一个
        if (body.length >= 0 && body.length != size) { body.stream.close(); return null }
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var lastPct = -1
        body.stream.use { inp ->
            FileOutputStream(part).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled()) return CANCELLED
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (done > size) return null
                    val pct = (done * 100 / size).toInt()
                    if (pct != lastPct) { lastPct = pct; emit(AsrDl(AsrDl.Phase.DOWNLOADING, done, size, i)) }
                }
            }
        }
        if (done != size) return null
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object { const val CANCELLED = "cancelled" }
}

/** 解 .tar.bz2（Apache Commons Compress）：只取需要的文件、去掉目录层级（也就不会写到目录外面）。 */
object AsrUnpack {
    fun tarBz2(src: File, dst: File, want: List<String>) {
        dst.mkdirs()
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(BufferedInputStream(FileInputStream(src), 64 * 1024))
        ).use { tar ->
            while (true) {
                val e = tar.nextTarEntry ?: break
                if (!e.isFile) continue
                val base = e.name.substringAfterLast('/')
                if (base !in want) continue
                FileOutputStream(File(dst, base)).use { tar.copyTo(it, 64 * 1024) }
            }
        }
    }
}

/** 麦克风权限的几种情况。 */
enum class MicPerm { GRANTED, ASK, BLOCKED }

/**
 * 按住说话的流程（纯逻辑）：
 * 按下 → 引擎不可用：提示；没模型：先弹下载框；没权限：先说明再申请（被永久拒绝就引导去设置）；都有了才开始录。
 * 移动 → 往上滑超过 cancelPx 进入「松开取消」，滑回来恢复。
 * 松开 → 太短（没说话）提示「按住说话」；上滑过了取消；否则把识别出来的字留在输入框（不自动发送）。
 */
object VoiceFlow {
    enum class Press { START, NEED_MODEL, EXPLAIN_PERMISSION, PERMISSION_BLOCKED, UNSUPPORTED, BUSY }
    enum class Release { COMMIT, CANCEL, TOO_SHORT }

    /** 按住多久以内松开算「点了一下」（毫秒）。 */
    const val SHORT_MS = 350L

    fun press(engineOk: Boolean, modelReady: Boolean, downloading: Boolean, perm: MicPerm, busy: Boolean): Press = when {
        busy -> Press.BUSY
        !engineOk -> Press.UNSUPPORTED
        !modelReady || downloading -> Press.NEED_MODEL
        perm == MicPerm.GRANTED -> Press.START
        perm == MicPerm.BLOCKED -> Press.PERMISSION_BLOCKED
        else -> Press.EXPLAIN_PERMISSION
    }

    /** 没有权限时：申请过、系统又不再给说明（「拒绝且不再询问」）= 只能去设置里开。 */
    fun perm(granted: Boolean, askedBefore: Boolean, rationale: Boolean): MicPerm = when {
        granted -> MicPerm.GRANTED
        askedBefore && !rationale -> MicPerm.BLOCKED
        else -> MicPerm.ASK
    }

    /** dy = 手指相对按下点的竖直位移（向上为负）。 */
    fun cancelArmed(dy: Float, cancelPx: Float): Boolean = dy < -cancelPx

    fun release(heldMs: Long, cancelArmed: Boolean, spoken: String): Release = when {
        cancelArmed -> Release.CANCEL
        heldMs < SHORT_MS && spoken.isBlank() -> Release.TOO_SHORT
        else -> Release.COMMIT
    }

    /** 已经识别完的几段 + 正在说的这段（中文不加空格；英文单词之间补一个空格）。 */
    fun spoken(segments: List<String>, current: String): String =
        (segments + current).map { it.trim() }.filter { it.isNotEmpty() }.fold("") { acc, s -> glue(acc, s) }

    /** 识别出来的字接在输入框原有文字后面。 */
    fun merge(prefix: String, spoken: String): String = if (spoken.isBlank()) prefix else glue(prefix, spoken.trim())

    private fun glue(a: String, b: String): String {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val l = a.last(); val f = b.first()
        val space = !l.isWhitespace() && l.code < 128 && l.isLetterOrDigit() && f.code < 128 && f.isLetterOrDigit()
        return if (space) "$a $b" else a + b
    }
}
