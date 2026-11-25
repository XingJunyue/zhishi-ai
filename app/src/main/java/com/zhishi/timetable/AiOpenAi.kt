package com.zhishi.timetable

import org.json.JSONArray
import org.json.JSONObject

/*
 * 用户自带接口（BYO）的实现：任何 OpenAI 兼容的 /chat/completions（官方、各家云、中转站）。
 * 只用标准字段：messages（文字 + image_url 的 data URL）、tools / tool_calls、temperature、max_tokens。
 * 不依赖任何一家的私有功能（不用 response_format、不用流式）。传输层 AiHttp 可以替换，单测里换成假的。
 * 纯 Kotlin，不碰 Android。
 */

/** 用户在「AI 设置」里填的东西。visionModel 留空 = 用对话模型看图。 */
data class AiSettings(
    val base: String = "", val key: String = "", val model: String = "", val visionModel: String = "", val preset: String = AiPresets.CUSTOM,
) {
    val ready get() = base.isNotBlank() && key.isNotBlank() && model.isNotBlank()
    val visionModelOrChat get() = visionModel.trim().ifBlank { model.trim() }
}

/** 预设只帮忙填接口地址；模型名一律自己填（或从 /models 里挑）。 */
object AiPresets {
    const val CUSTOM = "custom"
    class Preset(val id: String, val name: String, val base: String)
    val ALL = listOf(
        Preset("bailian", "阿里云百炼 / 通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        Preset("deepseek", "DeepSeek", "https://api.deepseek.com"),
        Preset("ark", "豆包 / 火山方舟", "https://ark.cn-beijing.volces.com/api/v3"),
        Preset("openai", "OpenAI", "https://api.openai.com/v1"),
        Preset(CUSTOM, "自定义 / 中转站", ""),
    )
    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: ALL.last()
}

/** 一次 HTTP 的结果。 */
class HttpResp(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

/** 传输层：单测里换成假的；出网失败抛 java.io.IOException。 */
interface AiHttp {
    fun send(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpResp
}

/** 默认传输：HttpURLConnection（JVM 自带，安卓上也一样）。 */
object UrlConnHttp : AiHttp {
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpResp {
        val con = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            con.requestMethod = method
            con.connectTimeout = minOf(timeoutMs, 15_000); con.readTimeout = timeoutMs
            for ((k, v) in headers) con.setRequestProperty(k, v)
            if (body != null) { con.doOutput = true; con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            val code = con.responseCode
            val stream = if (code >= 400) con.errorStream else con.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val h = con.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull() ?: "" }
            return HttpResp(code, text, h)
        } finally { con.disconnect() }
    }
}

/** 接口地址的容错（纯函数，有单测）。 */
object AiUrl {
    /** 去空白、补 https://、去掉末尾的 /、/chat/completions、/models。 */
    fun normalize(raw: String): String {
        var s = raw.trim().replace("\\s".toRegex(), "")
        if (s.isEmpty()) return ""
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s)) s = "https://$s"
        s = s.trimEnd('/')
        for (tail in listOf("/chat/completions", "/completions", "/models", "/chat")) if (s.endsWith(tail, ignoreCase = true)) s = s.dropLast(tail.length).trimEnd('/')
        return s
    }
    /** 路径最后一段是 v1 / v3 / v1beta 这类版本号：就是 API 根。 */
    fun hasVersion(base: String): Boolean {
        val path = base.substringAfter("://").substringAfter('/', "")
        val last = path.trimEnd('/').substringAfterLast('/')
        return Regex("^v\\d+[a-z0-9]*$", RegexOption.IGNORE_CASE).matches(last)
    }
    /** 要试的 API 根：写了版本号就只用它；没写就先试补上 /v1，不行再用原样。 */
    fun candidates(raw: String): List<String> {
        val b = normalize(raw); if (b.isEmpty()) return emptyList()
        return if (hasVersion(b)) listOf(b) else listOf("$b/v1", b)
    }
}

/** 请求和回复的 JSON 映射（纯函数，有单测）。 */
object AiWire {
    fun dataUrl(i: AiImage) = "data:${i.mime};base64,${i.base64}"

    fun message(m: AiMessage): JSONObject {
        val j = JSONObject().put("role", when (m.role) { AiRole.SYSTEM -> "system"; AiRole.USER -> "user"; AiRole.ASSISTANT -> "assistant"; AiRole.TOOL -> "tool" })
        when {
            m.role == AiRole.TOOL -> j.put("tool_call_id", m.toolCallId ?: "").put("content", m.text ?: "")
            m.images.isNotEmpty() -> {
                val a = JSONArray()
                if (!m.text.isNullOrEmpty()) a.put(JSONObject().put("type", "text").put("text", m.text))
                for (i in m.images) a.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl(i))))
                j.put("content", a)
            }
            // 有的中转站不收 content = null：没有文字时给空串
            else -> j.put("content", m.text ?: "")
        }
        if (m.role == AiRole.ASSISTANT && m.toolCalls.isNotEmpty()) {
            val a = JSONArray()
            for (t in m.toolCalls) a.put(JSONObject().put("id", t.id).put("type", "function")
                .put("function", JSONObject().put("name", t.name).put("arguments", t.arguments)))
            j.put("tool_calls", a)
        }
        return j
    }

    fun request(model: String, messages: List<AiMessage>, tools: List<AiTool>, temperature: Double = 0.2, maxTokens: Int? = null): JSONObject {
        val j = JSONObject().put("model", model).put("stream", false).put("temperature", temperature)
        j.put("messages", JSONArray().also { a -> messages.forEach { a.put(message(it)) } })
        if (maxTokens != null) j.put("max_tokens", maxTokens)
        if (tools.isNotEmpty()) {
            j.put("tools", JSONArray().also { a -> tools.forEach { t ->
                a.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters)))
            } })
            j.put("tool_choice", "auto")
        }
        return j
    }

    private fun text(content: Any?): String? = when (content) {
        null, JSONObject.NULL -> null
        is String -> content
        is JSONArray -> (0 until content.length()).mapNotNull { k -> content.optJSONObject(k)?.let { p -> if (p.optString("type", "text") == "text") p.optString("text") else null } }.joinToString("")
        else -> content.toString()
    }

    private fun args(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "{}"
        is String -> v.ifBlank { "{}" }
        else -> v.toString()      // 有的中转站直接给对象
    }

    /** 读回复：标准 JSON；也认被强制成流式（data: …）的回复；没有 tool_calls 时认 <tool_call>{…}</tool_call> 写在正文里的。 */
    fun reply(body: String): AiReply {
        val t = body.trim()
        if (t.startsWith("data:")) return sse(t)
        val j = try { JSONObject(t) } catch (x: Exception) { throw AiError.BadResponse(t.take(200)) }
        j.optJSONObject("error")?.let { e -> throw AiError.Server(e.optInt("code", 0), e.optString("message", e.toString()).take(300)) }
        val ch = j.optJSONArray("choices")?.optJSONObject(0) ?: throw AiError.BadResponse(t.take(200))
        val m = ch.optJSONObject("message") ?: ch.optJSONObject("delta") ?: JSONObject()
        val calls = ArrayList<AiToolCall>()
        m.optJSONArray("tool_calls")?.let { a ->
            for (k in 0 until a.length()) {
                val c = a.optJSONObject(k) ?: continue
                val f = c.optJSONObject("function") ?: continue
                val name = f.optString("name"); if (name.isBlank()) continue
                calls += AiToolCall(c.optString("id").ifBlank { "call_${k}_${name}" }, name, args(f.opt("arguments")))
            }
        }
        m.optJSONObject("function_call")?.let { f -> if (calls.isEmpty() && f.optString("name").isNotBlank()) calls += AiToolCall("call_fc", f.optString("name"), args(f.opt("arguments"))) }
        var txt = text(m.opt("content"))
        if (calls.isEmpty() && txt != null && txt.contains("<tool_call>")) {
            val (rest, inline) = inlineCalls(txt); txt = rest; calls += inline
        }
        return AiReply(txt?.trim()?.ifEmpty { null }, calls, ch.optString("finish_reason").ifBlank { null })
    }

    /** 正文里的 <tool_call>{"name":…,"arguments":{…}}</tool_call>。 */
    fun inlineCalls(s: String): Pair<String, List<AiToolCall>> {
        val out = ArrayList<AiToolCall>()
        val re = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
        re.findAll(s).forEachIndexed { k, m ->
            try {
                val j = JSONObject(m.groupValues[1])
                val name = j.optString("name"); if (name.isNotBlank()) out += AiToolCall("call_inline_$k", name, args(j.opt("arguments") ?: j.opt("parameters")))
            } catch (x: Exception) {}
        }
        return re.replace(s, "").trim() to out
    }

    /** 流式回复拼回来（有的中转站不管 stream=false 一律流式）。 */
    private fun sse(body: String): AiReply {
        val sb = StringBuilder()
        val calls = sortedMapOf<Int, Triple<String, String, StringBuilder>>()
        var finish: String? = null
        for (line in body.lines()) {
            val l = line.trim(); if (!l.startsWith("data:")) continue
            val d = l.removePrefix("data:").trim(); if (d == "[DONE]" || d.isEmpty()) continue
            val j = try { JSONObject(d) } catch (x: Exception) { continue }
            val ch = j.optJSONArray("choices")?.optJSONObject(0) ?: continue
            ch.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let { finish = it }
            val delta = ch.optJSONObject("delta") ?: ch.optJSONObject("message") ?: continue
            text(delta.opt("content"))?.let { sb.append(it) }
            delta.optJSONArray("tool_calls")?.let { a -> for (k in 0 until a.length()) {
                val c = a.optJSONObject(k) ?: continue
                val idx = c.optInt("index", k)
                val f = c.optJSONObject("function")
                val cur = calls[idx]
                val id = c.optString("id").ifBlank { cur?.first ?: "call_$idx" }
                val name = f?.optString("name")?.ifBlank { null } ?: cur?.second ?: ""
                val buf = cur?.third ?: StringBuilder()
                f?.opt("arguments")?.let { v -> if (v is String) buf.append(v) else buf.append(v.toString()) }
                calls[idx] = Triple(id, name, buf)
            } }
        }
        return AiReply(sb.toString().trim().ifEmpty { null }, calls.values.filter { it.second.isNotBlank() }.map { AiToolCall(it.first, it.second, it.third.toString().ifBlank { "{}" }) }, finish)
    }

    /** 错误回复里的那句话。 */
    fun errorText(body: String): String = try {
        val j = JSONObject(body.trim())
        (j.optJSONObject("error")?.optString("message") ?: j.optString("message", "")).ifBlank { body }.take(300)
    } catch (x: Exception) { body.take(300) }

    /** /models 的回复 → 模型 id（OpenAI 格式 data[].id；有的直接给数组）。 */
    fun models(body: String): List<String> {
        val t = body.trim()
        val a = try { if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("data") ?: JSONArray() } catch (x: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull { k -> a.opt(k).let { v -> if (v is JSONObject) v.optString("id").ifBlank { null } else (v as? String) } }.distinct().sorted()
    }
}

/** 看图测试用的小图：白底黑框里写着「OK」（64×32 PNG）。 */
const val AI_TEST_PNG = "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAIAAAAt/+nTAAABVklEQVR42u2YsaqDMBSGTe1e5zxCHUSzWDLa9+jo83QQBCnBxWeo2LV1EDL2BVy6pUtJ6XLuEMgVh8uFKldv8085yXK+cH7O4SAAsOashTVzGYC/1rIbIIRmkXTXt8sf3qap3i8bDxgAA2AA/gMAY4wQstlsCCF5nqtLx3HUoW1b3/dvt9tYTUGrF/5Sx+ORUiqEAAAhBKW0qioAWK1WACClpJTWdQ0DqZ/z+wBRFF0uFx2ez+ftdqsBdrtdlmUwnIYHwBhLKXUopcQYK4D9fh/HMQyqXpKLMWpSdfvX65UkyVilP6CJ1+s151yHnHPXdS3Lsm27aZrH45Gm6biT3ZslVJYlpfR+v2sTn04n7YG2bTHG1+t1uh4AgMPh4Pt+GIZBEDDG1KUCAICiKDzPez6fYwCg7vyMEJrFON1N0nRiA2AADMCE1ioz2qx8J2x2owbgwwG+AOlKlqB+3jDhAAAAAElFTkSuQmCC"

/**
 * OpenAI 兼容接口的 AiClient。
 * 地址容错：先试 <地址>/v1，404 再试原样（记住能用的那个）。429 / 5xx 等一下重试一次。
 */
class OpenAiCompatClient(
    private val s: AiSettings,
    private val http: AiHttp = UrlConnHttp,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val timeoutMs: Int = 90_000,
) : AiClient {
    companion object {
        /** 记住每个地址试出来能用的 API 根（进程内）。 */
        private val roots = java.util.concurrent.ConcurrentHashMap<String, String>()
        fun forget() = roots.clear()
    }

    private fun check() {
        if (s.base.isBlank()) throw AiError.NotConfigured("base")
        if (s.key.isBlank()) throw AiError.NotConfigured("key")
        if (s.model.isBlank()) throw AiError.NotConfigured("model")
    }

    private fun headers() = mapOf("Authorization" to "Bearer ${s.key.trim()}", "Content-Type" to "application/json; charset=utf-8",
        "Accept" to "application/json", "User-Agent" to "zhishi-ai")

    /** 发到 <根>/<path>：依次试候选的根，404 / 405 / 拿到网页（不是 JSON）就换下一个。 */
    private fun call(method: String, path: String, body: String?, hasImages: Boolean = false): String {
        val all = AiUrl.candidates(s.base)
        val known = roots[s.base]
        val order = if (known != null) listOf(known) + (all - known) else all
        var last: AiError? = null
        for (root in order) {
            val r = sendWithRetry(method, "$root/$path", body)
            val html = r.body.trimStart().startsWith("<")
            if (r.code == 404 || r.code == 405 || (r.code in 200..299 && html)) { last = AiError.NotFound(AiWire.errorText(r.body)); continue }
            if (r.code in 200..299) { roots[s.base] = root; return r.body }
            roots[s.base] = root
            throw map(r, hasImages)
        }
        throw last ?: AiError.NotFound(s.base)
    }

    private fun sendWithRetry(method: String, url: String, body: String?): HttpResp {
        var tries = 0
        while (true) {
            val r = try { http.send(method, url, headers(), body, timeoutMs) }
            catch (x: java.net.SocketTimeoutException) { throw AiError.Network("timeout") }
            catch (x: java.io.IOException) { throw AiError.Network(x.message ?: x.javaClass.simpleName) }
            val retry = (r.code == 429 || r.code in 500..599) && tries < 1
            if (!retry) return r
            tries++
            val wait = r.headers["retry-after"]?.trim()?.toIntOrNull()?.coerceIn(1, 5) ?: 2
            sleep(wait * 1000L)
        }
    }

    private fun map(r: HttpResp, hasImages: Boolean): AiError {
        val msg = AiWire.errorText(r.body)
        val visionWords = Regex("image|vision|multimodal|多模态|图片|图像|image_url|not support.*(content|type)", RegexOption.IGNORE_CASE)
        return when {
            r.code == 401 || r.code == 403 -> AiError.Auth(r.code, msg)
            r.code == 429 -> AiError.RateLimit(msg, r.headers["retry-after"]?.trim()?.toIntOrNull())
            hasImages && r.code in 400..599 && visionWords.containsMatchIn(msg) -> AiError.NoVision(msg)
            r.code == 404 -> AiError.NotFound(msg)
            else -> AiError.Server(r.code, msg)
        }
    }

    override fun chat(messages: List<AiMessage>, tools: List<AiTool>): AiReply {
        check()
        val imgs = messages.any { it.images.isNotEmpty() }
        val body = AiWire.request(s.model.trim(), messages, tools).toString()
        return AiWire.reply(call("POST", "chat/completions", body, imgs))
    }

    override fun vision(system: String, prompt: String, images: List<AiImage>): String {
        check()
        val msgs = listOf(AiMessage(AiRole.SYSTEM, system), AiMessage(AiRole.USER, prompt, images))
        val body = AiWire.request(s.visionModelOrChat, msgs, emptyList(), temperature = 0.0, maxTokens = 4096).toString()
        val r = try { AiWire.reply(call("POST", "chat/completions", body, true)) }
        catch (e: AiError.Server) { if (Regex("image|vision|multimodal|图片|图像", RegexOption.IGNORE_CASE).containsMatchIn(e.detail)) throw AiError.NoVision(e.detail) else throw e }
        return r.text ?: throw AiError.BadResponse("empty")
    }

    override fun test(): String {
        check()
        val r = chat(listOf(AiMessage(AiRole.SYSTEM, "You are a connectivity check. Reply with the single word OK."), AiMessage(AiRole.USER, "ping")))
        return r.text ?: "OK"
    }

    /** 看图模型能不能看图：让它读出小图里的字（读不出 OK 的多半是假装看到）。返回 true = 读对了。 */
    fun testVision(): Boolean {
        val t = vision("Read the text in the image. Reply with only that text.", "What word is written in the box?", listOf(AiImage("image/png", AI_TEST_PNG)))
        return t.contains("OK", ignoreCase = true)
    }

    override fun models(): List<String> {
        if (s.base.isBlank()) throw AiError.NotConfigured("base")
        if (s.key.isBlank()) throw AiError.NotConfigured("key")
        return AiWire.models(call("GET", "models", null))
    }
}
