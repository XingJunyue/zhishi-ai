package com.zhishi.timetable

import org.json.JSONObject

/*
 * AI 层的「接缝」：App 只认这里的 AiClient 接口和与传输无关的请求 / 回复模型。
 * 现在的实现是用户自己填的 OpenAI 兼容接口（OpenAiCompatClient，AiOpenAi.kt）；
 * 以后换成自营的托管后端时，只要另写一个 AiClient 实现，并改 Ai.client() 这一处（AiConfigAndroid.kt），
 * 聊天、截图识别、工具确认 / 撤销这些上层代码都不用动。
 * 本文件是纯 Kotlin（不碰 Android），单测和沙盒里的真实接口测试直接用。
 */

enum class AiRole { SYSTEM, USER, ASSISTANT, TOOL }

/** 一张图片：mime + base64（不带 data: 前缀）。 */
class AiImage(val mime: String, val base64: String)

/** 模型要求调用的一个工具：arguments 是 JSON 文本（解析和校验在 AiTools 里做）。 */
data class AiToolCall(val id: String, val name: String, val arguments: String)

/** 一条消息。tool 消息带 toolCallId；assistant 消息可以带 toolCalls。 */
data class AiMessage(
    val role: AiRole, val text: String? = null, val images: List<AiImage> = emptyList(),
    val toolCalls: List<AiToolCall> = emptyList(), val toolCallId: String? = null,
)

/** 一个可调用的工具：名字、说明、参数的 JSON Schema。 */
class AiTool(val name: String, val description: String, val parameters: JSONObject)

/** 模型的一次回复。 */
data class AiReply(val text: String?, val toolCalls: List<AiToolCall>, val finish: String? = null)

/** 出错的种类（界面按种类给出提示，不直接把英文报错甩给用户）。 */
sealed class AiError(msg: String) : Exception(msg) {
    /** 还没设置接口（地址 / 密钥 / 模型）。 */
    class NotConfigured(val what: String) : AiError("not configured: $what")
    /** 401 / 403：密钥不对或没权限。 */
    class Auth(val code: Int, val detail: String) : AiError("auth $code $detail")
    /** 429：请求太频繁 / 额度用完。 */
    class RateLimit(val detail: String, val retryAfterSec: Int?) : AiError("rate limit $detail")
    /** 连不上、超时。 */
    class Network(val detail: String) : AiError("network $detail")
    /** 404：地址或模型名不对。 */
    class NotFound(val detail: String) : AiError("not found $detail")
    /** 模型不接受图片（文本模型收到了 image_url）。 */
    class NoVision(val detail: String) : AiError("no vision $detail")
    /** 其他 HTTP 错误。 */
    class Server(val code: Int, val detail: String) : AiError("http $code $detail")
    /** 回复不是能读懂的格式。 */
    class BadResponse(val detail: String) : AiError("bad response $detail")
}

/**
 * 唯一的 AI 提供方接口。所有调用都是阻塞的，在 IO 线程里调。
 * 换成托管后端 = 新写一个实现这个接口的类。
 */
interface AiClient {
    /** 对话 + 工具调用（tools 为空时就是普通对话）。 */
    fun chat(messages: List<AiMessage>, tools: List<AiTool> = emptyList()): AiReply
    /** 看图：把图片和要求发给看图模型，返回模型的文字回复（截图识别要求它只回 JSON）。 */
    fun vision(system: String, prompt: String, images: List<AiImage>): String
    /** 测试连接：发一句很短的话，返回模型的回复。 */
    fun test(): String
    /** 可用模型列表（接口不支持就返回空）。 */
    fun models(): List<String>
}
