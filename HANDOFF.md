# HANDOFF — branch jw-ai（AI 帮我找课表）

状态（2026-11-28 00:30 CST）：开发中断，未合入 main，未发布。

- 分支 head：8c740e5（基于 sync 的 26c28a1）。
- main 已是 dad96ef（sync 的绿色 head：语音 + MCP 同步/工具对齐 + 直接生效与勾选撤销 + 小部件即时刷新），发布 run 46034011824（ai-21）。
- jw-ai CI 46025767582：build（含单测）通过，emulator 冒烟失败，原因未查。

## 功能
教务直接导入失败或没找到课表时，提供「AI 帮我找课表」：用户自己登录，AI 用用户自己的对话模型 + 看图模型，通过工具 read_page / click(n) / back / scroll / screenshot 操作 WebView，最多约 10 步。代码层面拦截密码框和任何输入。有进度、停止按钮、一次性隐私说明（页面内容发给用户自己的服务商）。输出严格 JSON，校验修补后进现有导入预览（覆盖/追加）。六种语言。

## 主要文件
- app/src/main/java/com/zhishi/timetable/JwAgent.kt、JwAi.kt、WebImport.kt、CourseGrid.kt、Tour.kt
- app/src/test/java/com/zhishi/timetable/JwAgentTest.kt
- tools/fake_jw.py、tools/fake_jw/*.html（本机假教务）、tools/fake_ai.py（假接口剧本）、tools/smoke.sh
- AGENTS.md（仓库约定）

## 下一步
1. 把 jw-ai 变基到 main（dad96ef）上，解决冲突（Updater/「新功能」条目、smoke.sh、i18n）。
2. 下载失败 run 的日志和 shots 产物，修 emulator 冒烟；同一失败循环超过两次就停。
3. 全绿后 fast-forward main（force=false），确认 main CI 成功、新 ai-N 的 APK 返回 200。
4. 「新功能」加一条：AI 帮我找课表。

## 约束
- 不碰老仓库 XingJunyue/zhishi、Cloudflare 的 gemini-relay 和 DNS。
- CI 不做真实 AI 调用；不打印 token、keystore。
- 未经用户明确同意，不改视觉。
