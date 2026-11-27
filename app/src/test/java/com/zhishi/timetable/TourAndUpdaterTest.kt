package com.zhishi.timetable

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 预览版关更新器；新手教程 / 新功能指引的判断；皮肤；界面文案六种语言都有。 */
class TourAndUpdaterTest {

    @Test fun updaterOffForPreview() {
        assertTrue(Updater.enabled(preview = false))
        assertFalse(Updater.enabled(preview = true))
        assertEquals(!BuildConfig.PREVIEW, Updater.enabled())
        if (BuildConfig.PREVIEW) {
            assertNull(Updater.latest())
            assertEquals("preview", Updater.lastError)
        }
        assertEquals("XingJunyue/zhishi-ai", Updater.REPO)
        assertEquals("voice", Tour.WHATS_NEW.first().id)
    }

    @Test fun firstRunVsUpgrade() {
        assertTrue(Tour.isFreshInstall(hasTourState = false, guided = false))
        assertFalse(Tour.isFreshInstall(hasTourState = false, guided = true))
        assertFalse(Tour.isFreshInstall(hasTourState = true, guided = false))
    }

    @Test fun whatsNewByVersion() {
        val rel = listOf(Tour.Release("b", emptyList()), Tour.Release("a", emptyList()))
        assertEquals(listOf("b", "a"), Tour.pending(10, 12, emptySet(), rel).map { it.id })
        assertEquals(listOf("b"), Tour.pending(10, 12, setOf("a"), rel).map { it.id })
        assertTrue(Tour.pending(12, 12, emptySet(), rel).isEmpty())
        assertTrue(Tour.pending(10, 12, setOf("a", "b"), rel).isEmpty())
        assertEquals(Tour.WHATS_NEW.size, Tour.WHATS_NEW.map { it.id }.toSet().size)
        assertTrue(Tour.WHATS_NEW.all { it.pages.isNotEmpty() })
        assertTrue(Tour.FIRST.isNotEmpty())
        assertTrue(Tour.WHATS_NEW.any { it.id == "skins" })
        // 教程只讲课表：不再出现守护、专注这些
        val all = (Tour.FIRST + Tour.WHATS_NEW.flatMap { it.pages }).joinToString { it.title + it.body }
        for (w in listOf("守护", "专注", "拦", "白名单", "留白")) assertFalse(w, all.contains(w))
    }

    @Test fun skins() {
        assertTrue(Skins.ALL.size >= 6)
        assertEquals(Skins.ALL.size, Skins.ALL.map { it.id }.toSet().size)
        assertEquals("chunyu", Skins.byId(null).id)
        assertEquals("chunyu", Skins.byId("nope").id)
        for (s in Skins.ALL) { assertFalse(s.light.dark); assertTrue(s.dark.dark) }
        assertFalse(Skins.darkFor(1, true)); assertTrue(Skins.darkFor(2, false))
        assertTrue(Skins.darkFor(0, true)); assertFalse(Skins.darkFor(0, false))
    }

    private fun i18n(lang: String): JSONObject =
        listOf("src/main/assets/i18n/$lang.json", "app/src/main/assets/i18n/$lang.json").map { File(it) }.first { it.exists() }.readText().let { JSONObject(it) }

    private fun src(): File = listOf("src/main/java", "app/src/main/java").map { File(it) }.first { it.exists() }

    /** 源码里所有 tr("…") 文案，加上教程、皮肤、课程标签：六种语言都要有。 */
    @Test fun allStringsTranslated() {
        val lits = src().walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> Regex("\\btr\\(\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(f.readText()).map { it.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"") } }
            .filter { it.isNotBlank() && !it.contains("$") }.toSet()
        val keys = lits + (Tour.FIRST + Tour.WHATS_NEW.flatMap { it.pages }).flatMap { listOf(it.title, it.body) } +
            Skins.ALL.flatMap { listOf(it.name, it.poem) } + Skins.MODES + Jw.KINDS.flatMap { listOf(it.name, it.note) }.filter { it.isNotEmpty() }
        for (lang in listOf("en", "fr", "ja", "ko", "de", "es")) {
            val j = i18n(lang)
            val missing = keys.filter { !j.has(it) || j.optString(it).isBlank() }
            assertTrue("$lang missing ${missing.size}: $missing", missing.isEmpty())
        }
    }

    /** 界面上不出现 WakeUp 字样。 */
    @Test fun noWakeUpWording() {
        for (lang in listOf("en", "fr", "ja", "ko", "de", "es")) {
            val j = i18n(lang)
            val bad = j.keys().asSequence().filter { k -> (k + j.optString(k)).contains("wakeup", ignoreCase = true) }.toList()
            assertTrue("$lang: $bad", bad.isEmpty())
        }
        val hits = src().walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> Regex("tr\\(\"([^\"]*)\"").findAll(f.readText()).map { it.groupValues[1] } }
            .filter { it.contains("wakeup", ignoreCase = true) }.toList()
        assertTrue("$hits", hits.isEmpty())
    }
}
