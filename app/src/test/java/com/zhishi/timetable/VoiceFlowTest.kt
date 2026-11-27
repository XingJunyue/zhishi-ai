package com.zhishi.timetable

import org.junit.Assert.*
import org.junit.Test

/** 按住说话的流程：要模型 → 要权限 → 录；上滑取消；识别文字拼进输入框。 */
class VoiceFlowTest {
    private fun press(engine: Boolean = true, model: Boolean = true, dl: Boolean = false, perm: MicPerm = MicPerm.GRANTED, busy: Boolean = false) =
        VoiceFlow.press(engine, model, dl, perm, busy)

    @Test fun pressDecisions() {
        assertEquals(VoiceFlow.Press.START, press())
        // 先要模型（下载不需要麦克风权限），再要权限
        assertEquals(VoiceFlow.Press.NEED_MODEL, press(model = false, perm = MicPerm.ASK))
        assertEquals(VoiceFlow.Press.NEED_MODEL, press(dl = true))
        assertEquals(VoiceFlow.Press.EXPLAIN_PERMISSION, press(perm = MicPerm.ASK))
        assertEquals(VoiceFlow.Press.PERMISSION_BLOCKED, press(perm = MicPerm.BLOCKED))
        // 这台手机用不了离线识别（没有对应的 so）：不弹下载框，直接说明
        assertEquals(VoiceFlow.Press.UNSUPPORTED, press(engine = false, model = false))
        // AI 正在回复时也能说（只是填输入框）；已经在录就不重复开始
        assertEquals(VoiceFlow.Press.BUSY, press(busy = true))
    }

    @Test fun permissionStates() {
        assertEquals(MicPerm.GRANTED, VoiceFlow.perm(granted = true, askedBefore = true, rationale = false))
        // 第一次：还没问过
        assertEquals(MicPerm.ASK, VoiceFlow.perm(granted = false, askedBefore = false, rationale = false))
        // 拒绝过一次，系统还会给说明：可以再问
        assertEquals(MicPerm.ASK, VoiceFlow.perm(granted = false, askedBefore = true, rationale = true))
        // 拒绝且不再询问：只能去设置
        assertEquals(MicPerm.BLOCKED, VoiceFlow.perm(granted = false, askedBefore = true, rationale = false))
    }

    @Test fun slideUpToCancel() {
        assertFalse(VoiceFlow.cancelArmed(0f, 120f))
        assertFalse(VoiceFlow.cancelArmed(-100f, 120f))
        assertTrue(VoiceFlow.cancelArmed(-121f, 120f))
        assertFalse(VoiceFlow.cancelArmed(300f, 120f)) // 往下滑不算
        assertEquals(VoiceFlow.Release.CANCEL, VoiceFlow.release(5000, cancelArmed = true, spoken = "你好"))
        assertEquals(VoiceFlow.Release.COMMIT, VoiceFlow.release(5000, cancelArmed = false, spoken = "你好"))
        assertEquals(VoiceFlow.Release.TOO_SHORT, VoiceFlow.release(120, cancelArmed = false, spoken = ""))
        assertEquals(VoiceFlow.Release.COMMIT, VoiceFlow.release(120, cancelArmed = false, spoken = "嗯"))
        assertEquals(VoiceFlow.Release.COMMIT, VoiceFlow.release(2000, cancelArmed = false, spoken = ""))
    }

    @Test fun textMerge() {
        assertEquals("明天下午三点开会", VoiceFlow.spoken(listOf("明天下午", "三点"), "开会"))
        assertEquals("明天", VoiceFlow.spoken(listOf(" 明天 ", ""), " "))
        assertEquals("hello world", VoiceFlow.spoken(listOf("hello"), "world"))
        assertEquals("加一门机器学习", VoiceFlow.merge("", "加一门机器学习"))
        assertEquals("周三加一门机器学习", VoiceFlow.merge("周三", " 加一门机器学习"))
        assertEquals("add AI", VoiceFlow.merge("add", "AI"))
        assertEquals("原来的字", VoiceFlow.merge("原来的字", "  "))
    }
}
