package com.zhishi.timetable

/*
 * AI 设置的存取（纯 Kotlin，有单测）：键值存储和加密都是接口，安卓那层用 SharedPreferences + Android Keystore。
 * 这是「自带接口」专用的；以后换托管后端时连同 AiSettingsScreen 一起去掉即可。
 */

/** 最小的键值存储。 */
interface AiKv {
    fun get(k: String): String?
    fun put(k: String, v: String?)
}

/** 密钥加密。encrypt 失败返回 null（存储就退回不加密，并记 enc = false）。 */
interface AiCipher {
    fun encrypt(plain: String): String?
    fun decrypt(stored: String): String?
}

class AiSettingsStore(private val kv: AiKv, private val cipher: AiCipher) {
    companion object {
        const val K_BASE = "base"; const val K_MODEL = "model"; const val K_VISION = "vision"; const val K_PRESET = "preset"
        const val K_KEY = "key_enc"; const val K_PLAIN = "key_plain"
    }
    fun load(): AiSettings {
        val key = kv.get(K_KEY)?.let { cipher.decrypt(it) } ?: kv.get(K_PLAIN)?.let { String(java.util.Base64.getDecoder().decode(it)) } ?: ""
        return AiSettings(kv.get(K_BASE).orEmpty(), key, kv.get(K_MODEL).orEmpty(), kv.get(K_VISION).orEmpty(), kv.get(K_PRESET) ?: AiPresets.CUSTOM)
    }
    /** 存下来；返回密钥是不是加密存的（没填密钥算 true）。 */
    fun save(s: AiSettings): Boolean {
        kv.put(K_BASE, s.base.trim()); kv.put(K_MODEL, s.model.trim()); kv.put(K_VISION, s.visionModel.trim()); kv.put(K_PRESET, s.preset)
        val k = s.key.trim()
        if (k.isEmpty()) { kv.put(K_KEY, null); kv.put(K_PLAIN, null); return true }
        val enc = cipher.encrypt(k)
        return if (enc != null) { kv.put(K_KEY, enc); kv.put(K_PLAIN, null); true }
        else { kv.put(K_KEY, null); kv.put(K_PLAIN, java.util.Base64.getEncoder().encodeToString(k.toByteArray())); false }
    }
    fun encrypted(): Boolean = kv.get(K_PLAIN) == null
    fun clear() { for (k in listOf(K_BASE, K_MODEL, K_VISION, K_PRESET, K_KEY, K_PLAIN)) kv.put(k, null) }
}

/** 密钥在界面上的样子：只露最后 4 位。 */
fun maskKey(k: String): String = if (k.isBlank()) "" else if (k.length <= 8) "••••" else "••••••" + k.takeLast(4)

/** 出错时给用户看的话（按错误种类，不直接甩英文报错）。visionHint = 这次带了图片、又没单独设看图模型。 */
fun aiErrorText(e: Throwable, visionHint: Boolean = false): String = when (e) {
    is AiError.NotConfigured -> tr("还没设置 AI 接口：去「AI 设置」填好接口地址、密钥和模型")
    is AiError.Auth -> tr("密钥不对或没有权限（{0}），检查一下「AI 设置」里的 API Key", e.code)
    is AiError.RateLimit -> tr("请求太频繁或额度用完了，稍后再试")
    is AiError.Network -> tr("连不上 AI 接口，检查网络和接口地址")
    is AiError.NotFound -> tr("接口地址或模型名不对（404），检查「AI 设置」")
    is AiError.NoVision -> tr("这个模型看不了图片：在「AI 设置」里填一个能看图的「看图模型」")
    is AiError.Server -> when {
        e.code == 503 || Regex("upstream_rejected|暂时不可用|unavailable|overloaded", RegexOption.IGNORE_CASE).containsMatchIn(e.detail) -> tr("模型暂时不可用，稍后重试或换个模型")
        else -> tr("AI 接口出错了（{0}），稍后再试", e.code) + (if (visionHint) "\n" + tr("如果对话模型看不了图片，请在「AI 设置」里单独填一个「看图模型」") else "")
    }
    is AiError.BadResponse -> tr("AI 的回复读不懂，再试一次")
    else -> tr("出错了：{0}", e.message ?: e.javaClass.simpleName)
}
