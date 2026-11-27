#!/usr/bin/env bash
# 模拟器冒烟测试：装上知时 AI，课表、导入、设置、皮肤、小部件预览、AI 设置 / 面板 / 确认卡片等页面都打开截图，AI 部分接本机假接口，确认没有闪退。
set -u
P=com.zhishi.timetable.ai
mkdir -p shots
fail=0
anr() { adb shell dumpsys window 2>/dev/null | grep -qi "not responding"; }
shot() {
  sleep "${2:-3}"
  # 系统的「xx 没有响应」对话框：关掉再截
  if anr; then echo "ANR dialog before $1, closing"; adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; sleep 2; fi
  adb exec-out screencap -p > "shots/$1.png"
}
A=com.zhishi.timetable.MainActivity
go() { adb shell am start -n $P/$A --es open "$@" > /dev/null; }
# 按屏幕上的文字点（uiautomator 找控件位置），找不到就什么也不做
tapText() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  local b; b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -F "text=\"$1" | head -1 | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p')
  if [ -n "$b" ]; then local x1 y1 x2 y2; read -r x1 y1 x2 y2 <<< "$b"; adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); else echo "tapText: '$1' not found"; fi
}
# 按完全相同的文字点（tapText 是前缀匹配）
tapExact() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  local b; b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -F "text=\"$1\"" | head -1 | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p')
  if [ -n "$b" ]; then local x1 y1 x2 y2; read -r x1 y1 x2 y2 <<< "$b"; adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); else echo "tapExact: '$1' not found"; fi
}
# 按 content-desc 点（只有图标没有文字的按钮，比如导航条正中间的 ✦）
tapDesc() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  local b; b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -F "content-desc=\"$1\"" | head -1 | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p')
  if [ -n "$b" ]; then local x1 y1 x2 y2; read -r x1 y1 x2 y2 <<< "$b"; adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); else echo "tapDesc: '$1' not found"; fi
}
# 某段文字的中心坐标（找不到输出空）
textXY() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -F "text=\"$1" | head -1 | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p' | { read -r x1 y1 x2 y2 && echo "$(( (x1+x2)/2 )) $(( (y1+y2)/2 ))"; }
}
onScreen() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -qF "$1"; }
crashed() { adb logcat -d -b crash | grep -q "$P" ; }

# 等系统完全起来，关掉错误对话框（模拟器慢，桌面常报「没有响应」盖住截图）
adb wait-for-device
for i in $(seq 1 90); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 2; done
adb shell settings put global hide_error_dialogs 1
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
sleep 20
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
adb shell input keyevent KEYCODE_WAKEUP

adb logcat -c
adb install -r -g out/test-debug.apk || { echo "INSTALL FAILED"; exit 1; }
adb shell am start -n $P/com.zhishi.timetable.MainActivity --ez seed true; sleep 6
# 中文截图要用中文界面（模拟器系统语言是英文）
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es setlang zh; sleep 10
# 换语言会重启进程：等它真正起来再截（第一次冷启动在模拟器上很慢）
for i in $(seq 1 20); do adb shell pidof $P > /dev/null && break; sleep 1; done
adb shell am start -n $P/com.zhishi.timetable.MainActivity > /dev/null; sleep 6
adb shell am start -n $P/com.zhishi.timetable.MainActivity; shot 01-home 6

# 底部导航：点「待办」「日历」「我的」「课表」（按屏幕文字点，确认导航条能用）
tapText 待办; shot 02-nav-todo 3
tapText 日历; shot 02-nav-calendar 3
tapText 我的; shot 02-nav-me 3
# 「我的」改版：顶部（不显示版本号）、滑到底；底部导航条正中间的 ✦
shot 13-me-top 1
onScreen "版本 ai-" && { echo "version shown in 我的 header"; fail=1; }
adb shell input swipe 540 1900 540 400 400; shot 13-me-scrolled 2
adb shell input swipe 540 1900 540 400 400; shot 13-me-bottom 2
for s in me_skin me_todo me_backup me_lang me_help ai_history; do go "$s"; shot "13-$s" 3; done
go me; sleep 1
tapText 课表; shot 02-nav-table 3
# 我的页里点进去的小部件页、关于页；我的页往下滑
for s in widget settings about; do
  go "$s"; shot "02-$s" 4
  case "$s" in settings|widget) adb shell input swipe 540 1900 540 500 400; shot "02-$s-b" 2;; esac
done
go settings:1; shot 02-settings-term 3
go settings:0; sleep 1
go settings:2; shot 02-settings-secs 3
go table:0; sleep 1

# 待办、日历
go todo; shot 03-todo 4
go todo:1; shot 03-todo-add 3
adb shell input keyevent KEYCODE_BACK; sleep 1
go todo:2; shot 03-todo-edit 3
adb shell input keyevent KEYCODE_BACK; sleep 1
go calendar; shot 03-calendar 4
adb shell input swipe 540 1900 540 900 400; shot 03-calendar-b 2
go table:0; sleep 1

# 皮肤：每套浅色、深色都截课表和小部件预览；再截皮肤选择（我的页）
for mode in 1 2; do
  for sk in chunyu xuanzhi qingci mushan yehang ying; do
    tag=$sk; [ $mode = 2 ] && tag=$sk-dark
    adb shell am start -n $P/com.zhishi.timetable.MainActivity --es skin $sk --ei mode $mode > /dev/null; go table:0; shot "09-skin-$tag" 3
    go widget; shot "09-widget-$tag" 3
  done
  go me_skin; shot "09-picker-$mode" 3
  go me; shot "09-me-$mode" 3
  go todo; shot "09-todo-$mode" 3
  go calendar; shot "09-calendar-$mode" 3
done
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es skin chunyu --ei mode 1 > /dev/null
go table:0; sleep 1
# 小部件样式：切到「经典」截一张，再切回「液态玻璃」
go widget; sleep 3; tapText 经典; shot 09-widget-classic 3
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es skin yehang --ei mode 2 > /dev/null; go widget; shot 09-widget-classic-yehang-dark 3
tapText 液态玻璃; sleep 2
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es skin chunyu --ei mode 1 > /dev/null
# build-16：待办空状态（水墨插画）在春雨 / 夜航 / 樱 / 暮山浅、深色下各截一张，再放回示例待办
for sk in chunyu yehang ying mushan; do for mode in 1 2; do
  adb shell am start -n $P/com.zhishi.timetable.MainActivity --ez notodos true --es skin $sk --ei mode $mode > /dev/null; go todo; shot "10-todo-empty-$sk-$mode" 3
done; done
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es skin chunyu --ei mode 1 > /dev/null; go table:0; shot 10-table-icon 2
adb shell am start -n $P/com.zhishi.timetable.MainActivity --ez seed true > /dev/null; sleep 2
# build-16：小部件跟随壁纸 / 跟随皮肤 / 透明度；换深色、浅色壁纸看跟随效果
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es wallpaper dark > /dev/null; sleep 4; go widget; shot 10-widget-wall-dark 3
adb shell input swipe 540 1900 540 700 400; shot 10-widget-wall-dark-b 2
adb shell am start -n $P/com.zhishi.timetable.MainActivity --ei wclear 90 > /dev/null; go widget; shot 10-widget-wall-dark-clear90 3
adb shell am start -n $P/com.zhishi.timetable.MainActivity --ei wclear 0 > /dev/null; go widget; shot 10-widget-wall-dark-clear0 3
adb shell am start -n $P/com.zhishi.timetable.MainActivity --ei wclear 65 --es wtint skin --es skin yehang --ei mode 2 > /dev/null; go widget; shot 10-widget-tint-skin-yehang-dark 3
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es wtint wall --es wallpaper light > /dev/null; sleep 4; go widget; shot 10-widget-wall-light 3
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es skin chunyu --ei mode 1 > /dev/null; sleep 1
adb logcat -d -s ZhishiTest | grep wallpaper | tail -3
# 从「我的」点「导入课表」后再回课表页：导入菜单不应该再弹出来（build-13 的问题）
go table:8; sleep 3; adb shell input keyevent KEYCODE_BACK; sleep 1; tapText 待办; sleep 2; tapText 课表; shot 09-table-no-reimport 3
# 液态玻璃透镜：着色器编译 / 绘制失败会记在 ZhishiGlass
adb logcat -d -s ZhishiGlass > shots/glass.txt; cat shots/glass.txt | tail -5
# 待办小部件的滑动列表：直接跑 RemoteViewsFactory（TodoRows）取每一行并 inflate；待办小部件预览存成 PNG 拉下来
adb shell am start -n $P/com.zhishi.timetable.MainActivity --ez seed true --ez wrows true > /dev/null; sleep 5
adb logcat -d -s ZhishiTest | grep "WROWS\|WMAIN\|WPREV" | tee shots/wrows.txt
grep -q "WROWS ok" shots/wrows.txt || { echo "todo widget rows failed"; fail=1; }
# 深色（夜航）液态玻璃、经典样式下也各存一份预览，最后换回春雨浅色 + 液态玻璃
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es skin yehang --ei mode 2 --ez wrows true --es wtag -yehang-dark > /dev/null; sleep 4
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es wstyle classic --ez wrows true --es wtag -classic-yehang-dark > /dev/null; sleep 4
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es wstyle glass --es skin chunyu --ei mode 1 > /dev/null; sleep 2
for f in wprev-4-demo wprev-2-demo wprev-4-real wprev-2-real; do for t in "" -yehang-dark -classic-yehang-dark; do adb pull /sdcard/Android/data/$P/files/$f$t.png shots/$f$t.png > /dev/null 2>&1 || true; done; done
go table:0; sleep 1

# 应用图标：回桌面，打开应用列表
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell input swipe 540 1900 540 400 300; shot 10-launcher 3
adb shell input keyevent KEYCODE_HOME; sleep 1

# 课表整页：本周、滑到下周、点课块、加课、改课、学期设置、开学日期、每节时间、导入菜单、教务列表、网页导入
go table; shot 07-table 6
adb shell input swipe 900 1500 150 1500 250; shot 07-table-next 3
adb shell input swipe 150 1500 900 1500 250; sleep 2
adb shell input tap 175 500; shot 07-tap-course 3
adb shell input keyevent KEYCODE_BACK; sleep 1
go table:3; shot 07-add 3
go table:4; shot 07-edit 3
adb shell input swipe 540 1900 540 700 400; shot 07-edit-b 2
go table:2; shot 07-term 3
go table:5; shot 07-term-date 3
go table:7; shot 07-secs 3
go table:8; shot 07-import-menu 3
go table:1; shot 07-import-list 3
go table:0; sleep 1
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open table:6 --es jwkind qz --es jwurl file:///android_asset/jwtest.html > /dev/null; shot 07-web 8
go table:0; sleep 1
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open table:6 --es jwkind qz --es jwurl file:///android_asset/jwtest.html --ez jwauto true > /dev/null; shot 07-web-preview 10
adb logcat -d | grep -i "chromium\|ZhishiJw" | tail -20 > shots/web-log.txt
# 树维 eams：首页是没有菜单的空架子，「导入课表」自己请求课表接口（本机假服务器 + 手写样例页面，不是真实学校）
python3 tools/fake_eams.py 8765 2> shots/fake-eams.log &
FE=$!
sleep 1
adb reverse tcp:8765 tcp:8765
go table:0; sleep 1
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open table:6 --es jwkind eams --es jwurl http://127.0.0.1:8765/eams/home.action > /dev/null; shot 07-eams-home 8
go table:0; sleep 1
adb logcat -c
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open table:6 --es jwkind eams --es jwurl http://127.0.0.1:8765/eams/home.action --ez jwauto true > /dev/null
for i in $(seq 1 20); do sleep 2; adb logcat -d -s ZhishiTest | grep -q "JW " && break; done
shot 07-eams-import 3
adb logcat -d -s ZhishiTest ZhishiWeb > shots/eams.txt
cat shots/eams.txt | grep "JW " || true
grep -q "JW parsed 2 kind=eams" shots/eams.txt || { echo "EAMS fetch import failed"; cat shots/fake-eams.log; fail=1; }
# 导入失败时：显示卡在哪一步 + 重试 + 复制诊断信息（故意选错类型：强智接口在假服务器上不存在）
go table:0; sleep 1
adb logcat -c
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open table:6 --es jwkind qz --es jwurl http://127.0.0.1:8765/eams/home.action --ez jwauto true > /dev/null
for i in $(seq 1 20); do sleep 2; adb logcat -d -s ZhishiTest | grep -q "JW " && break; done
shot 07-jw-fail 3
adb logcat -d -s ZhishiTest | grep "JW " | tee -a shots/eams.txt
adb logcat -d -s ZhishiTest | grep -q "JW fail notfound" || { echo "expected 'notfound' failure card"; fail=1; }
kill $FE 2>/dev/null
# 教务导入：按学校名搜索（预填搜索词）
go table:0; sleep 1
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open table:6 --es jwq 大连 > /dev/null; shot 07-jw-search 5
go table:0; sleep 1
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open table:6 --es jwq 东北林业 > /dev/null; shot 07-jw-search-2 5
tapText 东北林业大学; shot 07-jw-search-pick 8
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es jwq "" > /dev/null
go table:0; sleep 1
# 新手教程 / 新功能指引（弹出来截图，再跳过）
go table:0; sleep 2
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es tour first > /dev/null; shot 08-tour-first-1 3
for k in 2 3 4 5 6 7; do tapText 下一步; shot 08-tour-first-$k 2; done
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es tour new > /dev/null; shot 08-tour-new 3
adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_BACK; sleep 1
go settings; shot 08-settings-tour 4
go table:0; sleep 1

# AI（本机假的 OpenAI 兼容接口 tools/fake_ai.py，不是真模型；真实服务商只在沙盒里另外测过）
python3 tools/fake_ai.py 8766 2> shots/fake-ai.log &
FA=$!
sleep 1
adb reverse tcp:8766 tcp:8766
aiwait() {  # 等 logcat 里出现某个标记
  for i in $(seq 1 30); do sleep 1; adb logcat -d -s ZhishiTest | grep -q "$1" && return 0; done; echo "timeout waiting for $1"; return 1
}
# 前后对比用的截图：课表页、待办页（还没打开 AI 面板）
adb shell am start -n $P/$A --ez seed true > /dev/null; go table:0; shot 12-before-table 3
go todo; shot 12-before-todo 3
go me; adb shell input swipe 540 1700 540 900 300; shot 12-me-ai-section 2
go ai_settings; shot 12-ai-settings-empty 3
# 没设置时打开面板：提示先去设置
go table:0; sleep 1
adb shell am start -n $P/$A --ez aisheet true > /dev/null; shot 12-ai-sheet-unconfigured 3
adb shell input keyevent KEYCODE_BACK; sleep 1
# 写入假接口（地址故意不带 /v1），设置页「测试连接」「获取模型列表」
adb logcat -c
adb shell am start -n $P/$A --es aifake http://127.0.0.1:8766 > /dev/null; sleep 1
go ai_settings; shot 12-ai-settings 3
tapExact 测试连接; sleep 5; shot 12-ai-settings-test 2
adb shell input swipe 540 1500 540 700 300; shot 12-ai-settings-test-b 2
tapExact 从接口获取模型列表; sleep 4; shot 12-ai-settings-models 2
go table:0; sleep 1
# 点导航条上的 ✦（AI）打开半屏面板；拉满；从待办页打开（待办的建议）
tapDesc "AI 助手"; shot 12-ai-sheet-half 3
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -n $P/$A --ez aisheet true --ez aifull true > /dev/null; shot 12-ai-sheet-full 3
adb shell input keyevent KEYCODE_BACK; sleep 1
go todo; sleep 2
adb shell am start -n $P/$A --ez aisheet true --es aitab todo > /dev/null; shot 12-ai-sheet-todo 3
adb shell input keyevent KEYCODE_BACK; sleep 1
# 对话 → 改动马上生效（不再有确认卡片）：卡片「已改好 1 处」+ 提示条「已改好 1 处 · 撤销」→ 后面的课表页马上有这节课 → 全部撤销
go table:0; sleep 1
adb logcat -c
adb shell am start -n $P/$A --ez aireset true --es aisay "周三第5-6节加一门机器学习" > /dev/null
aiwait "AISAY DONE"; shot 12-ai-applied 2
adb logcat -d -s ZhishiTest | grep "AISAY" | tee shots/ai.txt
grep -q "AISAY CARD 新增课程：机器学习 state=1 n=1 live=true" shots/ai.txt || { echo "AI add_course not applied immediately"; fail=1; }
onScreen "确认这些更改" && { echo "confirmation card still shown"; fail=1; }
onScreen "已改好 1 处" || { echo "undo prompt missing"; fail=1; }
adb shell am start -n $P/$A --ez aistate true > /dev/null; sleep 2
adb logcat -d -s ZhishiTest | grep "AISTATE" | tail -1 | tee -a shots/ai.txt | grep -q "机器学习@3" || { echo "AI course not applied"; fail=1; }
adb shell input keyevent KEYCODE_BACK; sleep 2; shot 12-ai-after-table 2
adb logcat -c
adb shell am start -n $P/$A --ez aiundo true > /dev/null; aiwait "AIUNDO"; sleep 1
adb logcat -d -s ZhishiTest | grep "AISTATE\|AIUNDO" | tee -a shots/ai.txt | grep "AISTATE" | grep -q "机器学习" && { echo "AI undo did not remove the course"; fail=1; }
# 待办（带截止和提醒）、换皮肤：同样马上生效
adb logcat -c
adb shell am start -n $P/$A --es aisay "加个待办" --es aitab todo > /dev/null; aiwait "AISAY DONE"; shot 12-ai-todo-card 2
adb shell am start -n $P/$A --ez aistate true > /dev/null; sleep 2
adb logcat -d -s ZhishiTest | grep "AISAY\|AISTATE" | tee -a shots/ai.txt | grep "AISTATE" | grep -q "交线代作业" || { echo "AI todo not applied"; fail=1; }
adb shell input keyevent KEYCODE_BACK; go todo; shot 12-ai-after-todo 3
adb logcat -c
adb shell am start -n $P/$A --es aisay "换个皮肤" > /dev/null; aiwait "AISAY DONE"; sleep 2; shot 12-ai-skin-applied 2
adb shell am start -n $P/$A --ez aistate true > /dev/null; sleep 2
adb logcat -d -s ZhishiTest | grep "AISTATE" | tee -a shots/ai.txt | grep -q "skin=yehang" || { echo "AI skin not applied"; fail=1; }
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -n $P/$A --es skin chunyu --ei mode 1 > /dev/null; sleep 1
# 参数错了（不存在的课）：什么都没改，结果回给模型，模型再回一句话
adb logcat -c
adb shell am start -n $P/$A --es aisay "删掉那门课" > /dev/null; aiwait "AISAY DONE"
adb logcat -d -s ZhishiTest | grep "AISAY" | tee -a shots/ai.txt | grep -q "AISAY BOT 好的" || { echo "AI tool-error round trip failed"; fail=1; }
# 问答（不改数据）
adb logcat -c
adb shell am start -n $P/$A --es aisay "周二有什么课" > /dev/null; aiwait "AISAY DONE"; shot 12-ai-qa 2
adb logcat -d -s ZhishiTest | grep "AISAY BOT" | tee -a shots/ai.txt | grep -q "大学英语" || { echo "AI QA failed"; fail=1; }
adb shell input keyevent KEYCODE_BACK; sleep 1
# AI 识别截图（假接口回固定的 JSON；这里测的是读图、请求、解析、周次 / 单双周）
adb shell mkdir -p /sdcard/Android/data/$P/files
adb push out/tt-ai.png /sdcard/Android/data/$P/files/t-ai.png > /dev/null
adb logcat -c
adb shell am start -n $P/$A --es airec t-ai.png > /dev/null; aiwait "AIREC \(OK\|FAIL\)"
adb logcat -d -s ZhishiTest | grep "AIREC" | tee shots/airec.txt
for want in "AIREC OK 4" "1|大学物理|5-6|1,3,5,7,9,11,13,15|" "2|大学英语|3-4|2,4,6,8,10,12,14,16|" "3|线性代数|1-2|1,2,3,4,5,6,7,8|"; do
  grep -qF "$want" shots/airec.txt || { echo "AI recognition missing: $want"; fail=1; }
done
# 回复某一条（引用）：回复 AI 的话、回复自己的话；引用要真的发给模型（假接口把引用原文回出来）
adb shell am start -n $P/$A --es aiquote bot > /dev/null; shot 13-ai-quote-bar 3
adb logcat -c
adb shell am start -n $P/$A --es aiquote bot --es aisay "那周三呢" > /dev/null; aiwait "AISAY DONE"; shot 13-ai-quote-bot 2
adb logcat -d -s ZhishiTest | grep "AISAY\|AIQUOTE" | tee shots/ai-quote.txt
grep -q "AISAY QUOTE bot|周二有大学英语" shots/ai-quote.txt || { echo "quote (bot) not shown on the message"; fail=1; }
grep -q "AISAY BOT 收到，你在回复我之前的回复：「周二有大学英语" shots/ai-quote.txt || { echo "quote (bot) not sent to the model"; fail=1; }
adb logcat -c
adb shell am start -n $P/$A --es aiquote user --es aisay "再确认一下" > /dev/null; aiwait "AISAY DONE"; shot 13-ai-quote-user 2
adb logcat -d -s ZhishiTest | grep "AISAY\|AIQUOTE" | tee -a shots/ai-quote.txt | grep -q "AISAY BOT 收到，你在回复你自己之前说的：「那周三呢" || { echo "quote (user) not sent to the model"; fail=1; }
# 真手势：长按一条消息 → 菜单「回复 / 复制」→ 点「回复」出引用条；往右滑一条 AI 的消息也出引用条
xy=$(textXY "再确认一下")
if [ -n "$xy" ]; then read -r x y <<< "$xy"; adb shell input swipe $x $y $x $y 900; shot 13-ai-longpress-menu 2
  tapExact 回复; shot 13-ai-reply-menu 2
  onScreen "回复我自己" || { echo "long-press reply bar missing"; fail=1; }
else echo "message bubble not found for long-press"; fail=1; fi
adb shell am start -n $P/$A --es aiquote none > /dev/null; sleep 1
xy=$(textXY "收到，你在回复你自己")
if [ -n "$xy" ]; then read -r x y <<< "$xy"; adb shell input swipe 120 $y 520 $y 350; shot 13-ai-swipe-reply 2
  onScreen "回复 AI" || { echo "swipe reply bar missing"; fail=1; }
else echo "bot bubble not found for swipe"; fail=1; fi
adb shell am start -n $P/$A --es aiquote none > /dev/null; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1
# 第二段对话，然后看对话记录；杀掉进程再打开，记录还在；打开旧对话：卡片只显示最后的状态（不能再确认），能接着聊、能回复
adb logcat -c
adb shell am start -n $P/$A --ez aireset true --es aisay "周二有什么课" > /dev/null; aiwait "AISAY DONE"
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am force-stop $P; sleep 1
adb logcat -c
adb shell am start -n $P/$A --ez aihist true > /dev/null; sleep 6
adb logcat -d -s ZhishiTest | grep "AIHIST" | tee shots/ai-hist.txt
grep -q "AIHIST n=2" shots/ai-hist.txt || { echo "chat history not persisted across restart (want 2 chats)"; fail=1; }
grep -q "|cards=.*3" shots/ai-hist.txt || { echo "undone card state not persisted"; fail=1; }
grep -q "quotes=2" shots/ai-hist.txt || { echo "quotes not persisted"; fail=1; }
go ai_history; shot 13-ai-history 3
go ai_convo:2; shot 13-ai-convo-old 4
adb shell input swipe 540 700 540 1900 300; shot 13-ai-convo-old-top 2
adb shell input swipe 540 700 540 1900 300; shot 13-ai-convo-old-top2 2
adb logcat -c
adb shell am start -n $P/$A --ez aiconfirm true > /dev/null; sleep 2
adb logcat -d -s ZhishiTest | grep "AICONFIRM" | tee -a shots/ai-hist.txt | grep -q "AICONFIRM none" || { echo "old card could be confirmed again"; fail=1; }
adb logcat -c
adb shell am start -n $P/$A --ez ainosheet true --es aiquote bot --es aisay "接着聊" > /dev/null; aiwait "AISAY DONE"; sleep 1; shot 13-ai-convo-continued 2
adb logcat -d -s ZhishiTest | grep "AISAY" | tee -a shots/ai-hist.txt | grep -q "AISAY BOT 收到，你在回复我之前的回复：「收到，你在回复你自己" || { echo "continuing an old chat failed"; fail=1; }
adb shell am start -n $P/$A --es aiquote none > /dev/null
go table:0; sleep 2
tapDesc "AI 助手"; shot 13-ai-sheet-open 3
adb shell input keyevent KEYCODE_BACK; sleep 1
# 自定义撤回：一轮里 加课 → 改它的教室（第二轮，用返回的 id）→ 加待办；弹一次「已改好 3 处 · 撤销」；
# 打开撤销列表，只勾「加课」→ 依赖：「改教室」自动一起勾上；撤销所选 → 课没了，待办还在
adb logcat -c
adb shell am start -n $P/$A --ez aireset true --es aisay "周三第5-6节加一门机器学习，顺便记个预习" > /dev/null
aiwait "AISAY DONE"; sleep 1; shot 15-undo-prompt 1
adb logcat -d -s ZhishiTest | grep "AISAY" | tee shots/undo.txt
grep -q "AISAY CARD 新增课程：机器学习 / 修改课程：机器学习 / 新增待办：预习机器学习 state=1 n=3" shots/undo.txt || { echo "multi-change batch missing"; fail=1; }
onScreen "已改好 3 处" || { echo "undo prompt (3) missing"; fail=1; }
adb shell am start -n $P/$A --ez undosheet true > /dev/null; sleep 3; shot 15-undo-list 1
adb shell am start -n $P/$A --es undopick 0 > /dev/null; sleep 2; shot 15-undo-selective 1
adb shell am start -n $P/$A --ez undorun true > /dev/null; aiwait "UNDORUN"; sleep 2; shot 15-undo-done 1
adb logcat -d -s ZhishiTest | grep "UNDO\|AISTATE" | tee -a shots/undo.txt
grep -q "UNDOSEL \[0, 1\]" shots/undo.txt || { echo "undo dependency (add → edit) not auto-selected"; fail=1; }
grep -q "UNDORUN undone=\[0, 1\]" shots/undo.txt || { echo "selective undo failed"; fail=1; }
grep "AISTATE" shots/undo.txt | tail -1 | grep -q "机器学习@" && { echo "selective undo left the course"; fail=1; }
grep "AISTATE" shots/undo.txt | tail -1 | grep -q "预习机器学习" || { echo "selective undo removed the unselected todo"; fail=1; }
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 1

# ---------- 待办小部件：放到桌面，量刷新延迟（WLAT：从改动到桌面取到新行） ----------
R=com.zhishi.timetable.TestHookReceiver
bc() { adb shell am broadcast -n $P/$R "$@" > /dev/null; }
tapRe() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  local b; b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -iE "text=\"$1" | head -1 | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p')
  if [ -n "$b" ]; then local x1 y1 x2 y2; read -r x1 y1 x2 y2 <<< "$b"; adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); return 0; fi; return 1
}
wwait() { for i in $(seq 1 $(( ${2:-20} * 4 ))); do adb logcat -d -s ZhishiTest | grep -qE "$1" && return 0; sleep 0.25; done; return 1; }
adb shell am start -n $P/$A --ez wpin true > /dev/null; sleep 5; shot 16-widget-pin-dialog 0
tapRe "Add to home screen" || tapRe "ADD AUTOMATICALLY" || tapRe "ADD" || tapRe "Add" || echo "pin button not found"
sleep 5; adb shell input keyevent KEYCODE_HOME; sleep 4; shot 16-widget-on-home 0
adb shell dumpsys appwidget | grep -q "TodoWidget4" || echo "todo widget provider not listed"
wlat() {  # $1 标签  $2 等什么  其余 = 广播参数（桌面在前台）
  local tag=$1; local want=$2; shift 2
  adb logcat -c; bc "$@"; shot "16-wlat-$tag" 1
  wwait "$want" 20 || echo "wlat $tag: timeout"
  adb logcat -d -s ZhishiTest | grep WLAT | tee "shots/wlat-$tag.txt" | sed 's/^.*WLAT/WLAT/'
  echo "WLAT RESULT $tag: $(grep -E "$want" "shots/wlat-$tag.txt" | head -1 | grep -oE '\+[0-9]+ms')" | tee -a shots/wlat.txt
}
sleep 5
wlat add "WLAT row" --es wlat "LAT-A1"
sleep 3; wlat add2 "WLAT row" --es wlat "LAT-A2"
sleep 30; wlat addcold "WLAT row" --es wlat "LAT-A3"
sleep 3; wlat edit "WLAT row" --es wlatedit "LAT-E1"
sleep 3; wlat done "has=false" --es wlatdone "LAT-A2"
# 在 App 里加，回到桌面
adb logcat -c
adb shell am start -n $P/$A --es open todo --es wlat "LAT-B1" > /dev/null; sleep 3
bc --es wlatmark "LAT-B1"; adb shell input keyevent KEYCODE_HOME; shot 16-wlat-inapp 1
wwait "WLAT row" 20 || echo "wlat inapp: timeout"
adb logcat -d -s ZhishiTest | grep WLAT | tee shots/wlat-inapp.txt | sed 's/^.*WLAT/WLAT/'
echo "WLAT RESULT inapp(from HOME): $(grep 'WLAT row' shots/wlat-inapp.txt | tail -1 | grep -oE '\+[0-9]+ms')" | tee -a shots/wlat.txt

# ---------- MCP 端到端（线上的 https://mcp.xingjunyue.top；注册一个临时用户，最后删掉） ----------
MCP=https://mcp.xingjunyue.top/mcp
adb logcat -c
adb shell am start -n $P/$A --ez cloudon true > /dev/null
aiwait "CLOUD ON" || true; sleep 1
adb logcat -d -s ZhishiTest | grep "CLOUD" | tee shots/mcp.txt
if grep -q "CLOUD ON ok" shots/mcp.txt; then
  adb logcat -c
  adb shell am start -n $P/$A --es cloudtoken "'CI 智能体'" > /dev/null; aiwait "CLOUD TOKEN"
  adb logcat -d -s ZhishiTest | grep "CLOUD" | tee -a shots/mcp.txt
  adb pull /sdcard/Android/data/$P/files/agent.txt out/agent.txt > /dev/null 2>&1
  TOK=$(cat out/agent.txt 2>/dev/null)
  go me_mcp; shot 15-mcp-page 4
  onScreen "带令牌的链接" || { echo "MCP page: token reveal missing"; fail=1; }
  adb shell input swipe 540 1900 540 600 400; shot 15-mcp-page-b 2
  adb shell input swipe 540 1900 540 600 400; shot 15-mcp-page-c 2
  rpc() { curl -s -X POST "$MCP" -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-06-18' -d "$1"; }
  DUE="$(date -u -d tomorrow +%F) 18:00"
  # 先回桌面（App 不在前台就不会自己每分钟同步，下面那次同步才是第一次拉到智能体的改动）
  adb shell input keyevent KEYCODE_HOME; sleep 2
  rpc '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"zhishi-ci","version":"1"}}}' > shots/mcp-init.json
  python3 -c "import json;d=json.load(open('shots/mcp-init.json'));assert d['result']['serverInfo']['name']=='zhishi-cloud';print('MCP init ok',d['result']['protocolVersion'])" || { echo "MCP initialize failed"; cat shots/mcp-init.json; fail=1; }
  rpc '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null
  rpc '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"add_todo","arguments":{"title":"MCP 智能体加的待办","due":"'"$DUE"'","remind":true,"remind_before_minutes":30}}}' > shots/mcp-add-todo.json
  rpc '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"add_course","arguments":{"name":"MCP 课程","weekdays":[2],"start_period":9,"end_period":10,"room":"云端101"}}}' > shots/mcp-add-course.json
  TID=$(python3 -c "import json;d=json.load(open('shots/mcp-add-todo.json'));assert not d['result'].get('isError');print(d['result']['structuredContent']['todo']['id'])") || { echo "MCP add_todo failed"; cat shots/mcp-add-todo.json; fail=1; }
  CID=$(python3 -c "import json;d=json.load(open('shots/mcp-add-course.json'));assert not d['result'].get('isError');print(d['result']['structuredContent']['course']['id'])") || { echo "MCP add_course failed"; cat shots/mcp-add-course.json; fail=1; }
  echo "MCP added todo=$TID course=$CID" | tee -a shots/mcp.txt
  # 桌面在前台时同步（和后台 / 前台轮询一样的路径）：量小部件多久出现智能体加的待办
  adb logcat -c
  # adb shell 把参数拼成一行给手机上的 sh：带空格的标题要再包一层引号，不然只传过去「MCP」
  bc --es wlatmark "'MCP 智能体加的待办'"; bc --ez cloudsync true
  # 它的截止是明天，排在今天那几条后面、不在小部件可见的前几行里：桌面不会为它取行（WLAT row），
  # 所以看小部件列表工厂读到的快照里有没有它（WLAT load … has=true，就是桌面这次要显示的那份列表）
  WMCP='WLAT (row|load .* has=true)'
  wwait "CLOUD SYNC" 30; wwait "$WMCP" 15 || echo "MCP todo not fetched by the widget"
  shot 15-mcp-widget 2
  adb logcat -d -s ZhishiTest | grep "CLOUD\|WLAT" | tee -a shots/mcp.txt
  grep -q "WLAT start MCP 智能体加的待办" shots/mcp.txt || { echo "widget marker not set to the full title"; fail=1; }
  echo "WLAT RESULT mcp-pull(sync+widget): $(grep -E "$WMCP" shots/mcp.txt | head -1 | grep -oE '\+[0-9]+ms')" | tee -a shots/wlat.txt
  grep -q "CLOUD SYNC ok .*agent=2 banner=.*n=2" shots/mcp.txt || { echo "agent changes not pulled"; fail=1; }
  grep -qE "$WMCP" shots/mcp.txt || { echo "agent todo not in widget"; fail=1; }
  adb logcat -c
  adb shell am start -n $P/$A --ez aistate true > /dev/null; sleep 2
  adb logcat -d -s ZhishiTest | grep "AISTATE" | tail -1 | tee -a shots/mcp.txt > shots/mcp-state.txt
  grep -q "MCP 智能体加的待办" shots/mcp-state.txt || { echo "agent todo not in app"; fail=1; }
  grep -q "MCP 课程@2" shots/mcp-state.txt || { echo "agent course not in app"; fail=1; }
  go todo; shot 15-mcp-todo-list 3
  onScreen "MCP 智能体加的待办" || { echo "agent todo not in the to-do list"; fail=1; }
  shot 15-mcp-banner 0
  # 撤销智能体的改动（底部提示 → 撤销列表 → 全部撤销）：服务器上删掉，手机上也没了
  adb shell am start -n $P/$A --ez undosheet true --es undofor agent > /dev/null; sleep 3; shot 15-mcp-undo-list 1
  adb shell input keyevent KEYCODE_BACK; sleep 1
  adb logcat -c
  adb shell am start -n $P/$A --ez cloudundo true > /dev/null; aiwait "CLOUD UNDO"; sleep 2
  adb logcat -d -s ZhishiTest | grep "CLOUD\|AISTATE" | tee -a shots/mcp.txt
  grep -q "CLOUD UNDO done=2 err=null" shots/mcp.txt || { echo "agent undo failed"; fail=1; }
  grep "AISTATE" shots/mcp.txt | tail -1 | grep -q "MCP 智能体加的待办\|MCP 课程" && { echo "agent changes still in app after undo"; fail=1; }
  rpc '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_todos","arguments":{"status":"all"}}}' > shots/mcp-after-undo-todos.json
  rpc '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"list_courses","arguments":{}}}' > shots/mcp-after-undo-courses.json
  python3 - "$TID" "$CID" <<'PYEOF' || { echo "undo not reflected on the server"; fail=1; }
import json, sys
t = json.load(open('shots/mcp-after-undo-todos.json'))['result']['structuredContent']
c = json.load(open('shots/mcp-after-undo-courses.json'))['result']['structuredContent']
ids = [x['id'] for x in t['todos']]; cids = [x['id'] for x in c.get('courses', [])]
assert sys.argv[1] not in ids, ids
assert sys.argv[2] not in cids, cids
print('server after undo: todos', len(ids), 'courses', len(cids), '(agent items gone)')
PYEOF
  go table:0; sleep 1
  # 最后删掉这个临时用户：令牌随之失效
  adb logcat -c
  adb shell am start -n $P/$A --ez clouddelete true > /dev/null; aiwait "CLOUD DELETED"
  adb logcat -d -s ZhishiTest | grep "CLOUD" | tee -a shots/mcp.txt | grep -q "CLOUD DELETED ok token=false" || { echo "account delete failed"; fail=1; }
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$MCP" -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -H 'Accept: application/json' -d '{"jsonrpc":"2.0","id":9,"method":"tools/list"}')
  echo "MCP after account delete: HTTP $code" | tee -a shots/mcp.txt
  [ "$code" = "401" ] || { echo "token still works after DELETE /v1/account"; fail=1; }
  rm -f out/agent.txt
else
  echo "cloud enable failed"; fail=1
fi
# 离线语音输入（sherpa-onnx）：输入框旁边的麦克风、没模型时按一下弹「下载语音模型」框（这里不点下载）、下载进度（假的）、
# 按住说话的样子（假的识别文字）、上滑取消的样子、松开后字留在输入框里（不自动发送）
adb shell am start -n $P/$A --ez aireset true --ez asrdelete true --ez asrstate true > /dev/null; sleep 2
adb shell am start -n $P/$A --ez aisheet true > /dev/null; shot 14-voice-sheet-mic 3
onScreen 'content-desc="按住说话"' || { echo "mic button missing"; fail=1; }
tapDesc "按住说话"; shot 14-voice-download-dialog 3
onScreen "下载语音模型" || { echo "voice download dialog missing"; fail=1; }
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -n $P/$A --ei asrprog 9 --ez asrdialog true > /dev/null; shot 14-voice-download-progress 3
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -n $P/$A --ez asrdelete true > /dev/null; sleep 1
adb shell am start -n $P/$A --es asrpartial "明天下午三点交线代作业" > /dev/null; shot 14-voice-recording 3
adb shell am start -n $P/$A --es asrpartial "明天下午三点交线代作业" --ez asrarmed true > /dev/null; shot 14-voice-cancel-armed 2
adb shell am start -n $P/$A --ez asrstop true > /dev/null; shot 14-voice-text-in-input 2
onScreen "明天下午三点交线代作业" || { echo "voice text not in input box"; fail=1; }
adb logcat -d -s ZhishiTest | grep "ASR STATE" | tail -1
# 真模型（21MB，CI 上下载很快）：App 里走「主地址连不上 → 换下一个地址 → 校验 sha256 → 解包」，再用模型自带的测试音频识别一遍
asrwait() { for i in $(seq 1 ${2:-90}); do sleep 1; adb logcat -d -s ZhishiTest | grep -q "$1" && return 0; done; echo "timeout waiting for $1"; return 1; }
asrtest() {  # $1 = 标签（debug / qa）
  adb shell mkdir -p /sdcard/Android/data/$P/files; adb push out/asr.tar.bz2 /sdcard/Android/data/$P/files/asr.tar.bz2 > /dev/null; adb push out/asr0.wav /sdcard/Android/data/$P/files/asr0.wav > /dev/null
  adb shell am start -n $P/$A --es asrinstall asr.tar.bz2 > /dev/null; asrwait "ASR INSTALL" 120
  adb logcat -d -s ZhishiTest | grep "ASR INSTALL" | tee -a shots/asr-$1.txt | grep -q "ASR INSTALL READY err=null source=1 installed=true" || { echo "[$1] voice model install failed"; fail=1; }
  adb shell am start -n $P/$A --es asrwav asr0.wav > /dev/null; asrwait "ASR \(TEXT\|FAIL\)" 60
  adb logcat -d -s ZhishiTest | grep "ASR TEXT\|ASR FAIL" | tee -a shots/asr-$1.txt | grep -q "ASR TEXT .*介绍.*研究" || { echo "[$1] voice recognition failed"; fail=1; }
}
if curl -sfL --retry 3 -o out/asr.tar.bz2 https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01.tar.bz2 \
   && python3 -c "import tarfile;t=tarfile.open('out/asr.tar.bz2');m=[m for m in t.getmembers() if m.name.endswith('test_wavs/0.wav')][0];open('out/asr0.wav','wb').write(t.extractfile(m).read())"; then
  asrtest debug
  adb shell input keyevent KEYCODE_BACK; sleep 1   # 先收起 AI 面板（不然盖在设置页上面）
  go ai_settings; sleep 2; adb shell input swipe 540 1900 540 300 400; adb shell input swipe 540 1900 540 300 400; shot 14-voice-settings-installed 2
  onScreen "删除语音模型" || { echo "voice settings: delete button missing"; fail=1; }
  go table:0; sleep 1
else echo "voice model download skipped (network)"; HAVE_ASR=no; fi
# ---------- AI 帮我找课表（本机假教务 tools/fake_jw.py + fake_ai.py 里的剧本；不是真学校、也不是真模型） ----------
# 登录页上直接导入失败 → 出「AI 帮我找课表」→ 一次性说明 → AI 想点密码框、想输入都被代码拦下 → 让用户自己登录；
# 用户登录（钩子假装用户自己填的）→ 再导入失败 → AI：读页 → 点错 → 返回 → 滚动 → 点「我的课表」→ 读 → 截图 → 严格 JSON（带围栏 / 末尾逗号）→ 导入预览 → 导入；
# 再跑一次中途点「停止」。
python3 tools/fake_jw.py 8767 2> shots/fake-jw.log &
FJ=$!
sleep 1; adb reverse tcp:8767 tcp:8767
jwwait() { for i in $(seq 1 ${2:-90}); do sleep 1; adb logcat -d -s ZhishiTest | grep -q "$1" && return 0; done; echo "timeout waiting for $1"; return 1; }
jwdirect() {  # 点「导入课表」（直接导入），等它失败
  adb logcat -c; tapExact 导入课表
  jwwait "JW fail\|JW parsed" 40 || true
  adb logcat -d -s ZhishiTest | grep "JW " | tee -a shots/jwai.txt
}
adb shell am start -n $P/$A --es aifake http://127.0.0.1:8766 --ez jwaireset true > /dev/null; sleep 1
go table:0; sleep 1
adb shell am start -n $P/$A --es open table:6 --es jwkind qz --es jwurl http://127.0.0.1:8767/login.html > /dev/null; shot 17-jwai-login 8
jwdirect; shot 17-jwai-direct-fail 1
onScreen "AI 帮我找课表" || { echo "JWAI: offer missing after direct import failed"; fail=1; }
tapText "AI 帮我找课表"; shot 17-jwai-consent 2
onScreen "同意并开始" || { echo "JWAI: one-time consent notice missing"; fail=1; }
adb logcat -c
tapExact 同意并开始
jwwait "JWAI END" 60; shot 17-jwai-needlogin 2
adb logcat -d -s ZhishiTest | grep "JWAI" | tee -a shots/jwai.txt > shots/jwai-a.txt
grep -q "JWAI STEP 1 read_page" shots/jwai-a.txt || { echo "JWAI: read_page step missing"; fail=1; }
grep -q "JWAI STEP 1 click .*refused" shots/jwai-a.txt || { echo "JWAI: click on the password field was not refused"; fail=1; }
grep -q "JWAI STEP 1 type_text .*refused" shots/jwai-a.txt || { echo "JWAI: typing tool was not refused"; fail=1; }
grep -q "JWAI END NeedLogin" shots/jwai-a.txt || { echo "JWAI: expected NeedLogin on the login page"; fail=1; }
grep -q "home.html" shots/fake-jw.log && { echo "JWAI: page left the login screen without the user"; fail=1; }
onScreen "请先在网页里登录" || { echo "JWAI: need-login message missing"; fail=1; }
# 用户自己登录（钩子在网页里填学号密码点登录，代表用户的操作；AI 没有任何输入工具）
adb logcat -c
adb shell am broadcast -n $P/com.zhishi.timetable.TestHookReceiver --ez jwfakelogin true > /dev/null; jwwait "JWAI fakelogin ok" 10; sleep 4
shot 17-jwai-home 1
jwdirect; shot 17-jwai-direct-fail-2 1
tapText "AI 帮我找课表"; sleep 1
onScreen "同意并开始" && { echo "JWAI: consent notice shown twice"; fail=1; tapExact 同意并开始; }
sleep 9; shot 17-jwai-progress 0
onScreen "AI 正在找课表" || { echo "JWAI: progress panel missing"; fail=1; }
onScreen "停止" || { echo "JWAI: stop button missing"; fail=1; }
jwwait "JWAI END" 90; sleep 2; shot 17-jwai-preview 1
adb logcat -d -s ZhishiTest | grep "JWAI" | tee -a shots/jwai.txt > shots/jwai-b.txt
for want in "STEP 2 click 通知公告" "STEP 4 back" "STEP 5 scroll down" "STEP 7 click 我的课表" "STEP 9 screenshot" "END Found n=6 steps=9"; do
  grep -qF "JWAI $want" shots/jwai-b.txt || { echo "JWAI: missing '$want'"; fail=1; }
done
grep -q "kb.html" shots/fake-jw.log || { echo "JWAI: timetable page never opened"; fail=1; }
onScreen "识别到 6 节课" || { echo "JWAI: import preview missing"; fail=1; }
onScreen "计算机网络" || { echo "JWAI: preview content missing"; fail=1; }
tapText "导入 6 节"; sleep 3; shot 17-jwai-imported 1
adb logcat -c; adb shell am start -n $P/$A --ez aistate true > /dev/null; sleep 2
adb logcat -d -s ZhishiTest | grep "AISTATE" | tail -1 | tee -a shots/jwai.txt | grep -q "操作系统@1" || { echo "JWAI: imported courses not in the timetable"; fail=1; }
adb shell input keyevent KEYCODE_BACK; sleep 1
# 停止：再开一次，跑到一半点「停止」
go table:0; sleep 1
adb shell am start -n $P/$A --es open table:6 --es jwkind qz --es jwurl http://127.0.0.1:8767/home.html > /dev/null; sleep 8
jwdirect
adb logcat -c; tapText "AI 帮我找课表"; sleep 5; tapExact 停止; sleep 2; shot 17-jwai-stopped 1
adb logcat -d -s ZhishiTest | grep "JWAI" | tee -a shots/jwai.txt | grep -q "JWAI STOP" || { echo "JWAI: stop did not work"; fail=1; }
onScreen "已停止" || { echo "JWAI: stopped message missing"; fail=1; }
sleep 4; adb logcat -d -s ZhishiTest | grep -q "JWAI END" && { echo "JWAI: run kept reporting after stop"; fail=1; }
kill $FJ 2>/dev/null
go table:0; sleep 1
go table:8; shot 12-import-menu-ai 3
go table:0; sleep 1
kill $FA 2>/dev/null

# 其他语言下的主要页面
for lang in en de ja; do
  adb shell am start -n $P/com.zhishi.timetable.MainActivity --es setlang $lang; sleep 5
  adb shell am start -n $P/com.zhishi.timetable.MainActivity; shot "06-$lang-home" 5
  for s in table:8 table:1 settings todo calendar widget about; do adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open "$s"; shot "06-$lang-${s/:/-}" 3; go table:0; sleep 1; done
  adb shell am start -n $P/com.zhishi.timetable.MainActivity --es tour first > /dev/null; shot "06-$lang-tour" 3
  adb shell input keyevent KEYCODE_BACK; sleep 1
done
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es setlang zh; sleep 4

if crashed; then echo "CRASH (debug)"; adb logcat -d -b crash | tee shots/crash-debug.txt; fail=1; fi

# 正式版（混淆后）能不能正常启动
adb uninstall $P
adb logcat -c
adb install -r -g out/test-release.apk || { echo "INSTALL RELEASE FAILED"; exit 1; }
adb shell am start -n $P/com.zhishi.timetable.MainActivity; shot 05-release-start 6
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open settings; shot 05-release-settings 3
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open widget; shot 05-release-widget 3
if crashed; then echo "CRASH (release)"; adb logcat -d -b crash | tee shots/crash-release.txt; fail=1; fi

adb logcat -d > shots/logcat.txt
# 崩溃排查（build-17）：混淆后的 qa 包上按各种时刻 / 老数据 / 强制更新弹窗冷启动、走两个导入入口
QUICK=1 bash tools/crashhunt.sh out/test-qa.apk qa || fail=1
# 混淆后的 qa 包上再跑一遍离线语音（JNI 按名字读 sherpa-onnx 的类，keep 规则不对会在这里崩）
if [ "${HAVE_ASR:-yes}" = yes ]; then
  adb install -r -g out/test-qa.apk > /dev/null; adb logcat -c
  adb shell am start -n $P/$A --ez seed true > /dev/null; sleep 6
  asrtest qa
  if adb logcat -d -b crash | grep -q "$P"; then echo "CRASH (qa voice)"; adb logcat -d -b crash | tee shots/crash-qa-voice.txt; fail=1; fi
fi
echo "smoke fail=$fail"
exit $fail
