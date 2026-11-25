package com.zhishi.timetable

import org.junit.Assert.*
import org.junit.Test

/** AI 设置的保存 / 读取：密钥加密存、加密不可用时的退路、清除；出错提示。 */
class AiSettingsTest {
    class MapKv : AiKv { val m = HashMap<String, String>(); override fun get(k: String) = m[k]; override fun put(k: String, v: String?) { if (v == null) m.remove(k) else m[k] = v } }
    /** 假的加密：倒过来再加前缀（真机上是 Android Keystore 的 AES-GCM）。 */
    class Rev : AiCipher { override fun encrypt(plain: String) = "x:" + plain.reversed(); override fun decrypt(stored: String) = if (stored.startsWith("x:")) stored.drop(2).reversed() else null }
    class Broken : AiCipher { override fun encrypt(plain: String): String? = null; override fun decrypt(stored: String): String? = null }

    @Test fun roundTripAndKeyNotPlain() {
        val kv = MapKv(); val s = AiSettingsStore(kv, Rev())
        assertEquals(AiSettings(), s.load())
        assertFalse(s.load().ready)
        val v = AiSettings(" https://relay.example.com/v1 ", " sk-abcdef123456 ", "deepseek-chat", "", "custom")
        assertTrue(s.save(v))
        val back = s.load()
        assertEquals("https://relay.example.com/v1", back.base); assertEquals("sk-abcdef123456", back.key)
        assertEquals("deepseek-chat", back.model); assertEquals("", back.visionModel); assertTrue(back.ready)
        assertFalse(kv.m.values.any { it.contains("sk-abcdef123456") })
        assertTrue(s.encrypted())
        // 一份新的 store（重启 App）读到同样的东西
        assertEquals(back, AiSettingsStore(kv, Rev()).load())
        s.save(back.copy(key = "")); assertEquals("", s.load().key)
        s.clear(); assertTrue(kv.m.isEmpty())
    }

    @Test fun fallbackWhenKeystoreUnavailable() {
        val kv = MapKv(); val s = AiSettingsStore(kv, Broken())
        assertFalse(s.save(AiSettings("b", "sk-1", "m")))
        assertFalse(s.encrypted())
        assertEquals("sk-1", s.load().key)
        assertFalse(kv.m.values.any { it == "sk-1" })
    }

    @Test fun presetsOnlyPrefillBase() {
        assertEquals("", AiPresets.byId(AiPresets.CUSTOM).base)
        assertEquals(AiPresets.CUSTOM, AiPresets.byId("nope").id)
        assertTrue(AiPresets.ALL.filter { it.id != AiPresets.CUSTOM }.all { it.base.startsWith("https://") })
        // 不内置任何中转站
        assertTrue(AiPresets.ALL.none { it.base.contains("fanfan") })
    }

    @Test fun maskAndErrors() {
        assertEquals("••••••3456", maskKey("sk-abcdef123456")); assertEquals("", maskKey("")); assertEquals("••••", maskKey("abc"))
        assertEquals(tr("请求太频繁或额度用完了，稍后再试"), aiErrorText(AiError.RateLimit("x", null)))
        assertTrue(aiErrorText(AiError.Server(500, "upstream error"), visionHint = true).contains(tr("如果对话模型看不了图片，请在「AI 设置」里单独填一个「看图模型」")))
        assertEquals(tr("模型暂时不可用，稍后重试或换个模型"), aiErrorText(AiError.Server(503, "x")))
        for (e in listOf(AiError.NotConfigured("k"), AiError.Auth(401, ""), AiError.Network(""), AiError.NotFound(""), AiError.NoVision(""), AiError.BadResponse(""), RuntimeException("x")))
            assertTrue(aiErrorText(e).isNotBlank())
    }
}
