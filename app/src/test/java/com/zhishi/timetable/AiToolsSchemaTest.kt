package com.zhishi.timetable

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * tools/ai-tools.json 是 App 里 AI 助手的工具定义（AiTools.TOOLS）和皮肤列表的导出，MCP 服务器（zhishi-cloud）用同一份：
 * 改了工具或皮肤却没更新这个文件，这里就失败，免得两边对不上。
 * 重新生成：把 AiTools.schemaJson(Skins.ALL.map { it.id to it.name }).toString(2) 写进 tools/ai-tools.json，再拷到 zhishi-cloud/src/app-tools.json。
 */
class AiToolsSchemaTest {
    private fun file(): File = listOf(File("../tools/ai-tools.json"), File("tools/ai-tools.json")).first { it.exists() }

    @Test fun exportedSchemaMatchesTheApp() {
        val want = AiTools.schemaJson(Skins.ALL.map { it.id to it.name })
        val have = JSONObject(file().readText())
        assertTrue("tools/ai-tools.json is out of date: regenerate it from AiTools.schemaJson (see the comment above)", want.similar(have))
    }

    @Test fun everyMutatingToolIsSharedWithMcp() {
        val names = AiTools.TOOLS.map { it.name }.toSet()
        assertEquals(names - AiTools.LOCAL_ONLY, AiTools.MUTATING)
        assertEquals(setOf(AiTools.OPEN_PAGE), AiTools.LOCAL_ONLY)
    }
}
