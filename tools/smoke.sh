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
  go settings; shot "09-picker-$mode" 3
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
tapExact AI; shot 12-ai-sheet-half 3
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -n $P/$A --ez aisheet true --ez aifull true > /dev/null; shot 12-ai-sheet-full 3
adb shell input keyevent KEYCODE_BACK; sleep 1
go todo; sleep 2
adb shell am start -n $P/$A --ez aisheet true --es aitab todo > /dev/null; shot 12-ai-sheet-todo 3
adb shell input keyevent KEYCODE_BACK; sleep 1
# 对话 → 确认卡片 → 点「确认」→ 后面的课表页马上有这节课 → 撤销
go table:0; sleep 1
adb logcat -c
adb shell am start -n $P/$A --ez aireset true --es aisay "周三第5-6节加一门机器学习" > /dev/null
aiwait "AISAY DONE"; shot 12-ai-card 2
adb logcat -d -s ZhishiTest | grep "AISAY" | tee shots/ai.txt
grep -q "AISAY CARD 新增课程：机器学习" shots/ai.txt || { echo "AI add_course card missing"; fail=1; }
tapExact 确认; sleep 3; shot 12-ai-applied 2
adb shell am start -n $P/$A --ez aistate true > /dev/null; sleep 2
adb logcat -d -s ZhishiTest | grep "AISTATE" | tail -1 | tee -a shots/ai.txt | grep -q "机器学习@3" || { echo "AI course not applied"; fail=1; }
adb shell input keyevent KEYCODE_BACK; sleep 2; shot 12-ai-after-table 2
adb logcat -c
adb shell am start -n $P/$A --ez aiundo true > /dev/null; sleep 2
adb logcat -d -s ZhishiTest | grep "AISTATE\|AIUNDO" | tee -a shots/ai.txt | grep "AISTATE" | grep -q "机器学习" && { echo "AI undo did not remove the course"; fail=1; }
# 待办（带截止和提醒）、换皮肤：走同一条确认路径
adb logcat -c
adb shell am start -n $P/$A --es aisay "加个待办" --es aitab todo > /dev/null; aiwait "AISAY DONE"; shot 12-ai-todo-card 2
adb shell am start -n $P/$A --ez aiconfirm true > /dev/null; sleep 2
adb logcat -d -s ZhishiTest | grep "AISAY\|AISTATE\|AICONFIRM" | tee -a shots/ai.txt | grep "AISTATE" | grep -q "交线代作业" || { echo "AI todo not applied"; fail=1; }
adb shell input keyevent KEYCODE_BACK; go todo; shot 12-ai-after-todo 3
adb logcat -c
adb shell am start -n $P/$A --es aisay "换个皮肤" > /dev/null; aiwait "AISAY DONE"; shot 12-ai-skin-card 2
adb shell am start -n $P/$A --ez aiconfirm true > /dev/null; sleep 3; shot 12-ai-skin-applied 2
adb logcat -d -s ZhishiTest | grep "AISTATE" | tee -a shots/ai.txt | grep -q "skin=yehang" || { echo "AI skin not applied"; fail=1; }
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -n $P/$A --es skin chunyu --ei mode 1 > /dev/null; sleep 1
# 参数错了（不存在的课）：不出卡片，结果回给模型，模型再回一句话
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
echo "smoke fail=$fail"
exit $fail
