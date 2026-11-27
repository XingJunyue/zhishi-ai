package com.zhishi.timetable

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * 云同步 + MCP 智能体（安卓那层）。协议：zhishi-cloud 的 docs/API.md；合并规则在纯 Kotlin 的 Sync.kt。
 *  - 默认关着：只有用户在「我的 → AI → 连接 AI 智能体（MCP）」里打开同步，才注册一个匿名账号（没有邮箱 / 密码），
 *    把课表、待办和学期 / 节次设置推到服务器；关着时什么都不上传。
 *  - 设备令牌（zsd_）用 Android Keystore 加密存在 prefs `cloud`（和 AI 设置的 API Key 同一套 KeystoreCipher）；丢了就找不回账号（手机上的数据还在）。
 *  - 什么时候同步：打开 App / 回到前台、本机改了东西（2 秒去抖）、前台时每分钟一次、后台 WorkManager 每 15 分钟一次。
 *  - 存储每次写完（Term.setDesired / setTermStart / … / TodoStore.save）都调 localChanged：scan 一遍记上改动时间、进 outbox。
 *    拉下来写回本机的时候 muted，不会把收下的东西当成本机改动再推回去。
 *  - 拉下来的里面有智能体改的：取 agent-log，攒成一批放进 UndoCenter（底部提示「智能体改了 N 处 · 撤销」），撤销走服务器的 undo。
 */
object CloudSync {
    const val BASE = "https://mcp.xingjunyue.top"
    /** 备用地址（workers.dev，国内常被墙，只在主地址连不上时试）。 */
    const val FALLBACK = "https://zhishi-cloud.422537976xingjunyue.workers.dev"
    const val MCP_URL = "$BASE/mcp"

    /** 界面：设置 / 状态变了就 +1。 */
    val ver = mutableStateOf(0)
    /** 正在同步。 */
    val busy = mutableStateOf(false)
    /** 上次同步的结果（null = 成功）和时间。 */
    val lastError = mutableStateOf<String?>(null)
    val lastAt = mutableStateOf(0L)

    private val lock = Any()
    private val runLock = Any()
    @Volatile private var muted = false
    private var ledger: SyncLedger? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var app: Context? = null

    private fun p(c: Context) = c.getSharedPreferences("cloud", Context.MODE_PRIVATE)
    private fun str(c: Context, k: String): String? = try { p(c).getString(k, null) } catch (x: ClassCastException) { null }

    fun on(c: Context) = try { p(c).getBoolean("on", false) } catch (x: ClassCastException) { false }
    fun enabled(c: Context) = on(c) && token(c) != null
    fun userId(c: Context): String? = str(c, "uid")
    fun hasAccount(c: Context) = token(c) != null

    /** 设备令牌：Keystore 加密（不可用时退回明文，界面上说明）。 */
    fun token(c: Context): String? = str(c, "tok")?.let { s -> if (s.startsWith("zsd_")) s else KeystoreCipher.decrypt(s) }
    fun tokenEncrypted(c: Context) = str(c, "tok")?.startsWith("g1:") == true
    private fun setToken(c: Context, t: String?) {
        p(c).edit().apply { if (t == null) remove("tok") else putString("tok", KeystoreCipher.encrypt(t) ?: t) }.apply()
    }

    private fun led(c: Context): SyncLedger = ledger ?: SyncLedger.of(str(c, "ledger")).also { ledger = it }
    private fun saveLedger(c: Context) { p(c).edit().putString("ledger", led(c).toJson().toString()).apply() }
    fun pendingCount(c: Context): Int = synchronized(lock) { led(c).outbox.size }

    // ---------------- 本机存储 ----------------
    class Store(private val c: Context) : SyncStore {
        override fun read(): SyncLocal = SyncLocal(Term.desired(c).filter { it.kind == "course" }, TodoStore.all(c), Term.termStart(c), Term.totalWeeks(c),
            Term.secStarts(c), Term.secEnds(c), Term.secLen(c), TimeZone.getDefault().id)
        override fun write(old: SyncLocal, new: SyncLocal, taken: List<SyncItem>) {
            if (old.courses != new.courses) Term.setDesired(c, Term.desired(c).filter { it.kind != "course" } + new.courses)
            if (old.todos != new.todos) {
                TodoStore.save(c, new.todos)
                val now = new.todos.associateBy { it.id }; val was = old.todos.associateBy { it.id }
                for (t in old.todos) if (t.id !in now) TodoAlarm.cancel(c, t.id)
                // 智能体加的 / 改的待办开着提醒：手机上排闹钟
                for (t in new.todos) if (was[t.id] != t) TodoAlarm.sync(c, t)
            }
            if (old.termStart != new.termStart) Term.setTermStart(c, new.termStart)
            if (old.totalWeeks != new.totalWeeks) Term.setTotalWeeks(c, new.totalWeeks)
            if (old.secStarts != new.secStarts || old.secEnds != new.secEnds || old.secLen != new.secLen) {
                Term.setSecStarts(c, new.secStarts); Term.setSecLen(c, new.secLen); Term.setSecEnds(c, new.secEnds); CourseWidgets.safeUpdate(c)
            }
            AiData.ver.value++
        }
    }

    /** 主线程上跑（拿不到主线程就直接跑）：改侧表和写存储都在主线程，和界面上的改动不打架。 */
    fun <T> onMain(f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        val latch = CountDownLatch(1)
        var r: Result<T>? = null
        main.post { r = runCatching(f); latch.countDown() }
        if (!latch.await(20, TimeUnit.SECONDS)) throw IllegalStateException("main thread busy")
        return r!!.getOrThrow()
    }

    /** 存储写完的钩子（Term / TodoStore 调）：同步开着就 scan，有改动 2 秒后推。 */
    fun localChanged(c: Context) {
        if (muted) return
        try {
            if (!on(c)) return
            val changed = synchronized(lock) {
                val l = led(c)
                val ch = l.scan(Store(c).read().snapshot(), System.currentTimeMillis())
                if (ch.isNotEmpty()) saveLedger(c)
                ch
            }
            if (changed.isNotEmpty()) kick(c, 2000L)
        } catch (x: Throwable) { android.util.Log.w("ZhishiCloud", "scan failed", x) }
    }

    // ---------------- 网络 ----------------
    class CloudError(val code: Int, val kind: String, msg: String) : Exception(msg)
    @Volatile private var base: String = BASE
    /** 测试钩子：换服务器地址。 */
    @Volatile var override: String? = null

    private fun http(method: String, path: String, body: JSONObject?, auth: String?): JSONObject {
        val bases = override?.let { listOf(it) } ?: listOf(base) + listOf(BASE, FALLBACK).filter { it != base }
        var last: Throwable? = null
        for (b in bases) {
            try {
                val r = request(method, b + path, body, auth)
                base = b
                return r
            } catch (e: java.io.IOException) { last = e }
        }
        throw last ?: java.io.IOException("unreachable")
    }

    private fun request(method: String, url: String, body: JSONObject?, auth: String?): JSONObject {
        val cn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            cn.requestMethod = method; cn.connectTimeout = 12_000; cn.readTimeout = 20_000
            cn.setRequestProperty("Accept", "application/json")
            if (auth != null) cn.setRequestProperty("Authorization", "Bearer $auth")
            if (body != null) {
                cn.doOutput = true
                cn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                cn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = cn.responseCode
            val text = (if (code >= 400) cn.errorStream else cn.inputStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val j = try { if (text.isBlank()) JSONObject() else JSONObject(text) } catch (x: Exception) { JSONObject().put("raw", text.take(200)) }
            if (code !in 200..299) {
                val e = j.optJSONObject("error")
                throw CloudError(code, e?.optString("code") ?: "http_$code", e?.optString("message") ?: "HTTP $code")
            }
            return j
        } finally { cn.disconnect() }
    }

    private fun dev(c: Context) = token(c) ?: throw CloudError(401, "unauthorized", "no device token")

    class Net(private val tok: String) : SyncNet {
        override fun push(changes: List<SyncItem>, since: Long?): SyncPush {
            val body = JSONObject().put("changes", JSONArray().also { a -> changes.forEach { a.put(it.json()) } })
            if (since != null) body.put("since", since)
            val j = http("POST", "/v1/sync", body, tok)
            return SyncPush(SyncItem.list(j.optJSONArray("rejected")), j.optJSONObject("pull")?.let { pullOf(it) })
        }
        override fun pull(since: Long): SyncPull = pullOf(http("GET", "/v1/sync?since=$since&limit=500", null, tok))
        private fun pullOf(j: JSONObject) = SyncPull(SyncItem.list(j.optJSONArray("changes")), j.optLong("next_since"), j.optBoolean("more"))
    }

    // ---------------- 同步 ----------------
    /** 排一次同步（去抖：delay 内再叫只算一次）。 */
    private val runner = Runnable { val c = app ?: return@Runnable; exec.execute { syncBlocking(c) } }
    fun kick(c: Context, delay: Long = 0L) {
        if (!on(c)) return
        app = c.applicationContext ?: c
        main.removeCallbacks(runner); main.postDelayed(runner, delay)
    }

    /** 前台每分钟同步一次（智能体的改动在 App 开着时一分钟内出现）。 */
    private val poll = object : Runnable { override fun run() { app?.let { kick(it) }; main.postDelayed(this, 60_000L) } }
    fun foreground(c: Context, fg: Boolean) {
        app = c.applicationContext ?: c
        main.removeCallbacks(poll)
        if (fg && on(c)) { kick(c); main.postDelayed(poll, 60_000L) }
    }

    /** 同步一次（阻塞，后台线程调）。返回收下的改动；没开 / 失败返回 null（原因在 lastError）。 */
    fun syncBlocking(c: Context): SyncEngine.Outcome? {
        val ctx = c.applicationContext ?: c
        if (!enabled(ctx)) return null
        synchronized(runLock) {
            busy.value = true
            try {
                val o = SyncEngine.run(led(ctx), Store(ctx), Net(dev(ctx)), { System.currentTimeMillis() }) { f ->
                    onMain { synchronized(lock) { muted = true; try { f() } finally { muted = false }; saveLedger(ctx) } }
                }
                lastError.value = null; lastAt.value = System.currentTimeMillis()
                p(ctx).edit().putLong("lastAt", lastAt.value).apply()
                if (o.byAgent.isNotEmpty() || o.taken.any { it.source == "undo" }) try { agentArrived(ctx) } catch (x: Throwable) { android.util.Log.w("ZhishiCloud", "agent log failed", x) }
                android.util.Log.i("ZhishiCloud", "sync pushed=${o.pushed} rejected=${o.rejected} taken=${o.taken.size} agent=${o.byAgent.size}")
                return o
            } catch (e: Throwable) {
                lastError.value = errText(e)
                android.util.Log.w("ZhishiCloud", "sync failed: ${e.message}")
                if (e is CloudError && e.code == 401) onMain { ver.value++ }
                return null
            } finally { busy.value = false; onMain { ver.value++ } }
        }
    }

    fun errText(e: Throwable): String = when {
        e is CloudError && e.code == 401 -> tr("同步账号已失效（令牌不对或已删除）")
        e is CloudError && e.code == 429 -> tr("太频繁了，稍后再试")
        e is CloudError -> tr("服务器出错：{0}", e.message ?: e.kind)
        e is java.io.IOException -> tr("连不上同步服务器，检查一下网络")
        else -> e.message ?: e.javaClass.simpleName
    }

    /**
     * 第一次打开同步（阻塞）：注册匿名账号 → 整份推上去（每条 updated_at = 现在）→ 从 0 拉一遍、记下游标 →
     * 智能体日志从现在算起 → 排上后台定时同步。在显示 MCP 地址之前做完，智能体一来就看得到真实数据、节次时间和时区。
     */
    fun enable(c: Context) {
        val ctx = c.applicationContext ?: c
        if (token(ctx) == null) {
            val j = http("POST", "/v1/register", JSONObject().put("device_name", (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).take(60)), null)
            setToken(ctx, j.getString("device_token"))
            p(ctx).edit().putString("uid", j.optString("user_id")).apply()
        }
        onMain { synchronized(lock) { led(ctx).snapshotAll(Store(ctx).read().snapshot(), System.currentTimeMillis()); saveLedger(ctx) } }
        p(ctx).edit().putBoolean("on", true).apply()
        val o = syncBlocking(ctx)
        if (o == null) throw IllegalStateException(lastError.value ?: "sync failed")
        try { p(ctx).edit().putLong("logSeq", agentLog(ctx, 0, 1).firstOrNull()?.seq ?: 0L).apply() } catch (x: Throwable) {}
        CloudWork.schedule(ctx)
        onMain { ver.value++ }
    }

    /** 关掉同步；deleteCloud = 顺便删掉服务器上的账号和全部数据（DELETE /v1/account）。 */
    fun disable(c: Context, deleteCloud: Boolean) {
        val ctx = c.applicationContext ?: c
        p(ctx).edit().putBoolean("on", false).apply()
        CloudWork.cancel(ctx)
        main.removeCallbacks(poll); main.removeCallbacks(runner)
        if (deleteCloud) {
            token(ctx)?.let { http("DELETE", "/v1/account", null, it) }
            synchronized(lock) { ledger = SyncLedger() }
            setToken(ctx, null)
            p(ctx).edit().remove("uid").remove("ledger").remove("logSeq").remove("lastAt").apply()
            onMain { UndoCenter.agentBatch.value = null }
        }
        onMain { ver.value++ }
    }

    // ---------------- 智能体令牌 ----------------
    data class AgentToken(val id: String, val name: String, val hint: String, val created: Long, val lastUsed: Long, val revoked: Long)
    /** 新建的令牌（token / 带令牌的链接只在这一次拿得到）。 */
    data class NewToken(val id: String, val name: String, val token: String, val mcpUrl: String, val urlWithToken: String)

    private fun ms(j: JSONObject, k: String): Long = if (j.isNull(k)) 0L else j.optLong(k)
    fun tokens(c: Context): List<AgentToken> {
        val a = http("GET", "/v1/agent-tokens", null, dev(c)).optJSONArray("tokens") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { AgentToken(it.optString("id"), it.optString("name"), it.optString("hint"), ms(it, "created_at"), ms(it, "last_used_at"), ms(it, "revoked_at")) }
    }
    fun createToken(c: Context, name: String): NewToken {
        val j = http("POST", "/v1/agent-tokens", JSONObject().put("name", name.trim().take(40)), dev(c))
        val t = j.getString("token")
        return NewToken(j.optString("id"), j.optString("name"), t, j.optString("mcp_url", MCP_URL), j.optString("mcp_url_with_token", "$MCP_URL?token=$t"))
    }
    fun revokeToken(c: Context, id: String) { http("DELETE", "/v1/agent-tokens/$id", null, dev(c)) }

    // ---------------- 智能体改动日志 / 撤销 ----------------
    data class LogEntry(val seq: Long, val who: String, val tool: String, val kind: String, val itemId: String,
                        val before: JSONObject?, val after: JSONObject?, val at: Long, val undone: Boolean)

    fun agentLog(c: Context, after: Long = 0L, limit: Int = 50): List<LogEntry> {
        val a = http("GET", "/v1/agent-log?after=$after&limit=$limit", null, dev(c)).optJSONArray("entries") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            LogEntry(it.optLong("seq"), it.optString("token_name"), it.optString("tool"), it.optString("kind"), it.optString("item_id"),
                it.optJSONObject("before"), it.optJSONObject("after"), it.optLong("at"), it.optBoolean("undone"))
        }
    }

    /** 日志里的一条变成撤销列表的一行（按顺序：旧的在前）。 */
    fun entryOf(e: LogEntry, s: AppState): UndoEntry {
        fun obj(x: JSONObject?): Any? {
            if (x == null || x.optBoolean("deleted")) return null
            val d = x.optJSONObject("data") ?: return null
            return when (e.kind) { SyncData.COURSE -> SyncData.slot(e.itemId, d); SyncData.TODO -> SyncData.todoOf(e.itemId, d); else -> null }
        }
        val ch = if (e.kind == SyncData.SETTINGS) AiChange(tr("学期设置"), emptyList()) else AiTools.describe(s, obj(e.before), obj(e.after), TimeZone.getDefault())
        return UndoEntry(ch, setOf(SyncData.key(e.kind, e.itemId)), e.undone, e.seq, e.at, e.who)
    }

    /** 一批日志 → 可以撤销的一批：撤销按 seq 从新到旧调服务器的 undo，再同步一次把结果拉下来。 */
    fun batchOf(c: Context, log: List<LogEntry>, title: String): UndoBatch {
        val l = log.sortedBy { it.seq }
        val s = AiStateIO.read(c)
        val entries = l.map { entryOf(it, s) }
        return UndoBatch(title, entries, UndoBatch.AGENT, l) { ctx, sel ->
            val done = LinkedHashSet<Int>(); var err: String? = null
            for (i in sel.sortedDescending()) {
                try { http("POST", "/v1/agent-log/${l[i].seq}/undo", null, dev(ctx)); done += i }
                catch (e: CloudError) { if (e.kind == "already_undone") done += i else err = errText(e) }
                catch (e: Throwable) { err = errText(e) }
            }
            syncBlocking(ctx)
            UndoResult(done, 0, err)
        }
    }

    /** 拉下来了智能体的改动：取上次之后的日志，攒成一批放进底部提示。 */
    private fun agentArrived(c: Context) {
        val last = p(c).getLong("logSeq", 0L)
        val log = agentLog(c, last, 200).filter { !it.undone && it.seq > last }
        if (log.isEmpty()) return
        p(c).edit().putLong("logSeq", log.maxOf { it.seq }).apply()
        onMain {
            // 上一批还没撤销过的合进来（一起显示、一起撤销）
            val prev = UndoCenter.agentBatch.value
            @Suppress("UNCHECKED_CAST")
            val old = if (prev != null && prev.kind == UndoBatch.AGENT && prev.undone.value.isEmpty()) (prev.tag as? List<LogEntry>).orEmpty() else emptyList()
            val all = (old + log).distinctBy { it.seq }
            val names = all.map { it.who }.distinct()
            UndoCenter.agentBatch.value = batchOf(c, all, if (names.size == 1) tr("智能体「{0}」改了 {1} 处", names[0], all.size) else tr("AI 智能体改了 {0} 处", all.size))
        }
    }

    /** 启动时：同步开着就排上后台定时同步。 */
    fun init(c: Context) { try { if (enabled(c)) CloudWork.schedule(c) } catch (x: Throwable) {} ; lastAt.value = try { p(c).getLong("lastAt", 0L) } catch (x: Throwable) { 0L } }
}

/** 后台定时同步（WorkManager，每 15 分钟、有网络时）。 */
class CloudSyncWorker(c: Context, params: androidx.work.WorkerParameters) : androidx.work.Worker(c, params) {
    override fun doWork(): Result = try {
        if (!CloudSync.enabled(applicationContext)) Result.success()
        else if (CloudSync.syncBlocking(applicationContext) != null) Result.success() else Result.retry()
    } catch (x: Throwable) { Result.retry() }
}

object CloudWork {
    private const val NAME = "zhishi-cloud-sync"
    fun schedule(c: Context) {
        val req = androidx.work.PeriodicWorkRequest.Builder(CloudSyncWorker::class.java, 15, TimeUnit.MINUTES)
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .build()
        androidx.work.WorkManager.getInstance(c).enqueueUniquePeriodicWork(NAME, androidx.work.ExistingPeriodicWorkPolicy.KEEP, req)
    }
    fun cancel(c: Context) { androidx.work.WorkManager.getInstance(c).cancelUniqueWork(NAME) }
}
