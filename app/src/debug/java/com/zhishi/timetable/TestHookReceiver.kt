package com.zhishi.timetable

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 只在调试版：冒烟脚本用 am broadcast 触发钩子（不把 App 带到前台，桌面保持可见）。 */
class TestHookReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { try { TestHooks.run(c.applicationContext, i) } catch (x: Throwable) { android.util.Log.w("ZhishiTest", "hook failed", x) } }
}
