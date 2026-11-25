package com.zhishi.timetable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.mutableStateOf

/*
 * AI 层的安卓部分：设置存储（Keystore 加密密钥）、取客户端（接缝：Ai.client）、读图压缩、
 * 应用状态的读取 / 写入 / 撤销、截图识别。
 */

/** Android Keystore 里的 AES-GCM 密钥加密 API Key（密钥不出硬件 / 系统密钥库）。 */
object KeystoreCipher : AiCipher {
    private const val ALIAS = "zhishi_ai_api_key"
    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? java.security.KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    override fun encrypt(plain: String): String? = try {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); c.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        "g1:" + android.util.Base64.encodeToString(c.iv, android.util.Base64.NO_WRAP) + ":" + android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP)
    } catch (x: Throwable) { android.util.Log.w("ZhishiAi", "keystore encrypt failed", x); null }
    override fun decrypt(stored: String): String? = try {
        val p = stored.split(':'); require(p.size == 3 && p[0] == "g1")
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, android.util.Base64.decode(p[1], android.util.Base64.NO_WRAP)))
        String(c.doFinal(android.util.Base64.decode(p[2], android.util.Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (x: Throwable) { android.util.Log.w("ZhishiAi", "keystore decrypt failed", x); null }
}

class PrefsKv(c: Context) : AiKv {
    private val p = c.getSharedPreferences("ai", Context.MODE_PRIVATE)
    override fun get(k: String): String? = try { p.getString(k, null) } catch (x: ClassCastException) { null }
    override fun put(k: String, v: String?) { p.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply() }
}

/**
 * 接缝：App 里所有 AI 调用都从这里拿客户端。
 * 现在 = 用户在「AI 设置」里填的 OpenAI 兼容接口；以后换托管后端只改这里（和去掉 AiSettingsScreen）。
 * override 给测试钩子用（冒烟测试接本机假服务器）。
 */
object Ai {
    @Volatile var override: AiClient? = null
    /** 设置改了：界面重组（入口、提示）。 */
    val ver = mutableStateOf(0)
    fun store(c: Context) = AiSettingsStore(PrefsKv(c), KeystoreCipher)
    fun settings(c: Context): AiSettings = store(c).load()
    fun save(c: Context, s: AiSettings): Boolean { val r = store(c).save(s); OpenAiCompatClient.forget(); ver.value++; return r }
    fun ready(c: Context) = override != null || settings(c).ready
    fun client(c: Context): AiClient = override ?: OpenAiCompatClient(settings(c))
    /** 单独设了看图模型没有（没设 = 用对话模型看图）。 */
    fun hasVisionModel(c: Context) = settings(c).visionModel.isNotBlank()
}

/** 课表 / 设置被 AI 改了：课表页这类只在 ver 变时重读的界面看这个。 */
object AiData { val ver = mutableStateOf(0) }

object AiImages {
    /** 读图 → 最长边不超过 1600 的 JPEG（够看清课表字，又不至于太大）。 */
    fun load(c: Context, uri: Uri, maxSide: Int = 1600): AiImage? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(c, uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val b0 = open(c, uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        if (b0 == null) null else {
            val k = maxSide.toFloat() / maxOf(b0.width, b0.height)
            val b = if (k < 1f) Bitmap.createScaledBitmap(b0, (b0.width * k).toInt().coerceAtLeast(1), (b0.height * k).toInt().coerceAtLeast(1), true) else b0
            val out = java.io.ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, 85, out)
            AiImage("image/jpeg", android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP))
        }
    } catch (x: Throwable) { null }
    private fun open(c: Context, uri: Uri) = if (uri.scheme == "file") java.io.FileInputStream(uri.path!!) else c.contentResolver.openInputStream(uri)
}

/** AI 截图识别：每张图单独问看图模型（有的模型一次只收一张），合并去重；不是 JSON 时让对话模型修一次。 */
object AiVision {
    class Out(val courses: List<JwCourse>, val dropped: Int)
    fun periods(c: Context) = Term.secStarts(c).zip(Term.secEnds(c))
    /** 阻塞，在 IO 线程调。 */
    fun recognize(c: Context, images: List<AiImage>, client: AiClient = Ai.client(c)): Out {
        if (images.isEmpty()) throw AiError.BadResponse("no image")
        val per = periods(c); val total = Term.totalWeeks(c)
        val all = LinkedHashMap<String, JwCourse>(); var dropped = 0
        for (img in images) {
            val text = client.vision(AiRecognize.SYSTEM, AiRecognize.prompt(per), listOf(img))
            val r = AiRecognize.parse(text, per, total) ?: run {
                // 不是要的 JSON：让对话模型把它整理成 JSON（纯文字，便宜）
                val fixed = client.chat(listOf(AiMessage(AiRole.SYSTEM, AiRecognize.SYSTEM),
                    AiMessage(AiRole.USER, "Convert this timetable description into the required JSON {\"courses\":[...]} with fields name, teacher, room, weekday, start_period, end_period, start_time, end_time, weeks, odd_even. Output only JSON.\n\n" + text.take(6000))))
                AiRecognize.parse(fixed.text.orEmpty(), per, total)
            } ?: throw AiError.BadResponse(text.take(200))
            dropped += r.dropped
            for (k in r.courses) all.putIfAbsent("${k.name}|${k.day}|${k.start}|${k.end}|${k.weeks}", k)
        }
        return Out(all.values.sortedWith(compareBy({ it.day }, { it.start })), dropped)
    }
}

/** 应用状态的读写：AI 的改动在用户确认后从这里写进各自的存储（和手动改走同一套函数，小部件 / 提醒一起更新）。 */
object AiStateIO {
    fun read(c: Context): AppState {
        val look = c.getSharedPreferences("look", Context.MODE_PRIVATE)
        fun w(k: String) = WidgetLook(WidgetStyle.get(c, k), WidgetStyle.tint(c, k), WidgetStyle.clear(c, k), WidgetStyle.mode(c, k))
        return AppState(
            courses = Term.desired(c), todos = TodoStore.all(c),
            termStart = Term.termStart(c), totalWeeks = Term.totalWeeks(c), secStarts = Term.secStarts(c), secEnds = Term.secEnds(c),
            skin = Skins.byId(try { look.getString("skin", null) } catch (x: ClassCastException) { null }).id, mode = Skins.modeState.value,
            rotMode = SkinRotation.mode(c), rotEvery = SkinRotation.every(c), favs = SkinRotation.favs(c),
            widgetCourse = w(WidgetStyle.COURSE), widgetTodo = w(WidgetStyle.TODO),
            showWeekend = Term.showWeekend(c), showOff = Term.showOff(c), showLevel = Term.showLevel(c), widgetLevel = Term.widgetLevel(c),
        )
    }

    /** 把 to 里和 from 不同的块写进存储。 */
    fun write(c: Context, from: AppState, to: AppState) {
        val parts = AiState.changed(from, to)
        for (p in parts) when (p) {
            StatePart.COURSES -> Term.setDesired(c, to.courses)
            StatePart.TODOS -> {
                TodoStore.save(c, to.todos)
                val now = to.todos.associateBy { it.id }
                for (t in from.todos) if (t.id !in now) TodoAlarm.cancel(c, t.id)
                val old = from.todos.associateBy { it.id }
                for (t in to.todos) if (old[t.id] != t) TodoAlarm.sync(c, t)
            }
            StatePart.TERM -> { Term.setTermStart(c, to.termStart); Term.setTotalWeeks(c, to.totalWeeks) }
            StatePart.SECS -> { Term.setSecStarts(c, to.secStarts); Term.setSecEnds(c, to.secEnds) }
            StatePart.SKIN -> { if (from.skin != to.skin) Skins.setSkin(c, Skins.byId(to.skin)); if (from.mode != to.mode) Skins.setMode(c, to.mode) }
            StatePart.ROTATION -> {
                if (from.favs != to.favs) { c.getSharedPreferences("look", Context.MODE_PRIVATE).edit().putString("fav", to.favs.joinToString(",")).apply(); SkinRotation.ver.value++ }
                if (from.rotMode != to.rotMode) SkinRotation.setMode(c, to.rotMode)
                if (from.rotEvery != to.rotEvery) SkinRotation.setEvery(c, to.rotEvery)
            }
            StatePart.WIDGET_COURSE -> look(c, WidgetStyle.COURSE, from.widgetCourse, to.widgetCourse)
            StatePart.WIDGET_TODO -> look(c, WidgetStyle.TODO, from.widgetTodo, to.widgetTodo)
            StatePart.DISPLAY -> {
                Term.setShowWeekend(c, to.showWeekend); Term.setShowOff(c, to.showOff); Term.setShowLevel(c, to.showLevel); Term.setWidgetLevel(c, to.widgetLevel)
                CourseWidgets.safeUpdate(c)
            }
        }
        AiData.ver.value++
    }

    private fun look(c: Context, kind: String, a: WidgetLook, b: WidgetLook) {
        if (a.style != b.style) WidgetStyle.set(c, b.style, kind)
        if (a.tint != b.tint) WidgetStyle.setTint(c, b.tint, kind)
        if (a.clear != b.clear) WidgetStyle.setClear(c, b.clear, kind = kind)
        if (a.dmode != b.dmode) WidgetStyle.setMode(c, b.dmode, kind)
    }
}

/** 最后应用的一批（撤销用，进程内）。 */
object AiUndo {
    val last = mutableStateOf<AiBatch?>(null)
    /** 应用一批提议：从当前真实状态出发重新取改动的块（中间用户手动改过的别的块不受影响）。 */
    fun apply(c: Context, plan: AiPlan): AiBatch {
        val cur = AiStateIO.read(c)
        val parts = AiState.changed(plan.before, plan.after)
        val target = AiState.take(cur, plan.after, parts)
        AiStateIO.write(c, cur, target)
        return AiBatch(cur, target).also { last.value = it }
    }
    /** 撤销；返回没能撤销的块数（之后又被改过的）。 */
    fun undo(c: Context): Int {
        val b = last.value ?: return 0
        val cur = AiStateIO.read(c)
        val (n, conflicts) = b.undo(cur)
        AiStateIO.write(c, cur, n)
        last.value = null
        return conflicts.size
    }
}

/** 给 AI 的环境：皮肤列表（id + 中文名）等。 */
fun aiEnv(): AiEnv = AiEnv(skins = Skins.ALL.map { it.id to it.name })
