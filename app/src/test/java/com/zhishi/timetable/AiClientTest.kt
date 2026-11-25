package com.zhishi.timetable

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** OpenAI 兼容客户端：地址容错、请求 / 回复格式、出错分类、重试（HTTP 层换成假的，CI 上没有真密钥）。 */
class AiClientTest {
    class Fake(val handler: (String, String, String?) -> HttpResp) : AiHttp {
        val urls = ArrayList<String>(); val bodies = ArrayList<String?>(); val auth = ArrayList<String?>()
        override fun send(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpResp {
            urls += url; bodies += body; auth += headers["Authorization"]; return handler(method, url, body)
        }
    }
    private fun ok(content: String) = HttpResp(200, """{"choices":[{"message":{"role":"assistant","content":${JSONObject.quote(content)}},"finish_reason":"stop"}]}""")
    private val s = AiSettings("https://relay.example.com", "sk-test", "m-chat", "m-vision")

    @Test fun urlNormalize() {
        assertEquals("https://a.com/v1", AiUrl.normalize(" https://a.com/v1/ "))
        assertEquals("https://a.com/v1", AiUrl.normalize("https://a.com/v1/chat/completions"))
        assertEquals("https://a.com", AiUrl.normalize("a.com/"))
        assertEquals(listOf("https://a.com/v1"), AiUrl.candidates("https://a.com/v1"))
        assertEquals(listOf("https://a.com/v1", "https://a.com"), AiUrl.candidates("https://a.com"))
        assertEquals(listOf("https://ark.cn-beijing.volces.com/api/v3"), AiUrl.candidates("https://ark.cn-beijing.volces.com/api/v3/"))
        assertEquals(listOf("https://x.com/openai/v1", "https://x.com/openai"), AiUrl.candidates("x.com/openai"))
        assertTrue(AiUrl.candidates("  ").isEmpty())
    }

    @Test fun fallsBackWhenV1Is404AndRemembers() {
        OpenAiCompatClient.forget()
        val f = Fake { _, url, _ -> if (url.contains("/v1/")) HttpResp(404, "<html>nope</html>") else ok("你好") }
        val c = OpenAiCompatClient(s, f, sleep = {})
        assertEquals("你好", c.chat(listOf(AiMessage(AiRole.USER, "hi"))).text)
        assertEquals(listOf("https://relay.example.com/v1/chat/completions", "https://relay.example.com/chat/completions"), f.urls)
        c.chat(listOf(AiMessage(AiRole.USER, "again")))
        assertEquals("https://relay.example.com/chat/completions", f.urls.last())
        assertEquals("Bearer sk-test", f.auth.first())
        OpenAiCompatClient.forget()
    }

    @Test fun requestShape() {
        OpenAiCompatClient.forget()
        val f = Fake { _, _, _ -> ok("ok") }
        val c = OpenAiCompatClient(s.copy(base = "https://a.com/v1"), f, sleep = {})
        c.chat(listOf(AiMessage(AiRole.SYSTEM, "sys"), AiMessage(AiRole.USER, "加课"),
            AiMessage(AiRole.ASSISTANT, null, toolCalls = listOf(AiToolCall("c1", "add_course", "{}"))), AiMessage(AiRole.TOOL, "done", toolCallId = "c1")), AiTools.TOOLS)
        val j = JSONObject(f.bodies.last()!!)
        assertEquals("m-chat", j.getString("model")); assertFalse(j.getBoolean("stream"))
        assertEquals("auto", j.getString("tool_choice"))
        assertTrue(j.getJSONArray("tools").length() >= 15)
        val msgs = j.getJSONArray("messages")
        assertEquals("", msgs.getJSONObject(2).getString("content"))
        assertEquals("add_course", msgs.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("c1", msgs.getJSONObject(3).getString("tool_call_id"))
        c.vision("sys", "read", listOf(AiImage("image/png", "AAAA")))
        val v = JSONObject(f.bodies.last()!!)
        assertEquals("m-vision", v.getString("model"))
        val part = v.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1)
        assertEquals("data:image/png;base64,AAAA", part.getJSONObject("image_url").getString("url"))
        assertFalse(v.has("tools"))
    }

    @Test fun visionFallsBackToChatModel() {
        assertEquals("m", AiSettings("b", "k", "m", "").visionModelOrChat)
        assertEquals("v", AiSettings("b", "k", "m", " v ").visionModelOrChat)
    }

    @Test fun toolCallsParsing() {
        val r = AiWire.reply("""{"choices":[{"message":{"content":null,"tool_calls":[
            {"id":"a","type":"function","function":{"name":"add_todo","arguments":"{\"title\":\"x\"}"}},
            {"type":"function","function":{"name":"set_skin","arguments":{"skin":"ying"}}}]},"finish_reason":"tool_calls"}]}""")
        assertNull(r.text)
        assertEquals(listOf("add_todo", "set_skin"), r.toolCalls.map { it.name })
        assertEquals("a", r.toolCalls[0].id); assertTrue(r.toolCalls[1].id.isNotBlank())
        assertEquals("ying", JSONObject(r.toolCalls[1].arguments).getString("skin"))
    }

    @Test fun contentPartsAndInlineToolCall() {
        assertEquals("ab", AiWire.reply("""{"choices":[{"message":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}]}""").text)
        val r = AiWire.reply("""{"choices":[{"message":{"content":"好的<tool_call>{\"name\":\"pin_todo\",\"arguments\":{\"id\":\"t1\"}}</tool_call>"}}]}""")
        assertEquals("好的", r.text); assertEquals("pin_todo", r.toolCalls.single().name)
    }

    @Test fun sseReplyAssembled() {
        val body = """data: {"choices":[{"delta":{"content":"你"}}]}
data: {"choices":[{"delta":{"content":"好","tool_calls":[{"index":0,"id":"x","function":{"name":"open_page","arguments":"{\"pa"}}]}}]}
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ge\":\"todo\"}"}}]},"finish_reason":"tool_calls"}]}
data: [DONE]"""
        val r = AiWire.reply(body)
        assertEquals("你好", r.text); assertEquals("open_page", r.toolCalls.single().name)
        assertEquals("todo", JSONObject(r.toolCalls.single().arguments).getString("page"))
    }

    @Test fun errorsAreClassified() {
        fun err(code: Int, body: String, img: Boolean = false): Throwable {
            OpenAiCompatClient.forget()
            val c = OpenAiCompatClient(s.copy(base = "https://a.com/v1"), Fake { _, _, _ -> HttpResp(code, body) }, sleep = {})
            return try { if (img) c.vision("s", "p", listOf(AiImage("image/png", "A"))) else c.chat(listOf(AiMessage(AiRole.USER, "x"))); fail("no error"); Exception() } catch (e: AiError) { e }
        }
        assertTrue(err(401, """{"error":{"message":"Invalid token"}}""") is AiError.Auth)
        assertTrue(err(429, """{"error":{"message":"slow down"}}""") is AiError.RateLimit)
        val u = err(503, """{"error":{"message":"当前模型暂时不可用","code":"upstream_rejected"}}""")
        assertTrue(u is AiError.Server && u.code == 503)
        assertEquals(tr("模型暂时不可用，稍后重试或换个模型"), aiErrorText(u))
        assertTrue(err(400, """{"error":{"message":"This model does not support image input"}}""", img = true) is AiError.NoVision)
        assertTrue(err(400, """{"error":{"message":"bad"}}""") is AiError.Server)
        assertTrue(err(200, """{"error":{"message":"quota"}}""") is AiError.Server)
        assertTrue(err(200, "not json") is AiError.BadResponse)
        val n = try { OpenAiCompatClient(AiSettings("", "k", "m")).chat(emptyList()); null } catch (e: AiError) { e }
        assertTrue(n is AiError.NotConfigured)
        val net = try { OpenAiCompatClient(s, Fake { _, _, _ -> throw java.io.IOException("down") }, sleep = {}).chat(emptyList()); null } catch (e: AiError) { e }
        assertTrue(net is AiError.Network)
    }

    @Test fun retriesOnceOn5xxAnd429() {
        OpenAiCompatClient.forget()
        var n = 0; val waits = ArrayList<Long>()
        val f = Fake { _, _, _ -> n++; if (n == 1) HttpResp(500, """{"error":{"message":"upstream error: do request failed"}}""") else ok("好") }
        assertEquals("好", OpenAiCompatClient(s.copy(base = "https://a.com/v1"), f, sleep = { waits += it }).chat(listOf(AiMessage(AiRole.USER, "x"))).text)
        assertEquals(2, n); assertEquals(1, waits.size)
        n = 0
        val g = Fake { _, _, _ -> n++; HttpResp(429, "{}", mapOf("retry-after" to "3")) }
        try { OpenAiCompatClient(s.copy(base = "https://a.com/v1"), g, sleep = { waits += it }).chat(listOf(AiMessage(AiRole.USER, "x"))); fail() } catch (e: AiError.RateLimit) {}
        assertEquals(2, n); assertEquals(3000L, waits.last())
    }

    @Test fun modelsList() {
        assertEquals(listOf("a", "b"), AiWire.models("""{"data":[{"id":"b"},{"id":"a"},{"id":"a"}]}"""))
        assertEquals(listOf("x"), AiWire.models("""["x"]"""))
        assertTrue(AiWire.models("oops").isEmpty())
        OpenAiCompatClient.forget()
        val f = Fake { m, url, _ -> if (m == "GET" && url.endsWith("/v1/models")) HttpResp(200, """{"data":[{"id":"glm"}]}""") else HttpResp(404, "") }
        assertEquals(listOf("glm"), OpenAiCompatClient(AiSettings("https://a.com", "k", ""), f, sleep = {}).models())
    }

    @Test fun visionTestNeedsTheRightWord() {
        OpenAiCompatClient.forget()
        assertTrue(OpenAiCompatClient(s.copy(base = "https://a.com/v1"), Fake { _, _, _ -> ok("OK") }, sleep = {}).testVision())
        assertFalse(OpenAiCompatClient(s.copy(base = "https://a.com/v1"), Fake { _, _, _ -> ok("一张白色的图片") }, sleep = {}).testVision())
    }
}
