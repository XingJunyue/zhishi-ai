#!/usr/bin/env bash
# 崩溃排查（build-17 起，每次 CI 都跑）：在混淆后的 qa 包（R8 和正式版一样，带测试钩子）和 debug 包上，
# 按「现在」造出各种时刻的课表（第一节前 / 课间 / 今天的课都上完 / 一分钟后下课 / 今天没课 / 极短的课）和边界待办，
# 每种都：冷启动（和点桌面小部件同一个 intent）→ 课表页「＋」→「从教务导入」→ 返回 →「我的 → 导入课表」→ 每种导入方式点一遍 → 小部件页 → 待办页，
# 每一步后检查进程还活着、crash 日志里没有 FATAL EXCEPTION。用法：bash tools/crashhunt.sh <apk> <标签>
set -u
P=com.zhishi.timetable.ai
adb() { timeout 30 "$(type -P adb)" "$@"; }   # 任何一步卡住都不能拖死整个测试
APK=$1; TAG=${2:-qa}
mkdir -p shots
bad=0
ui() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml | tr '>' '\n'; }
tapAttr() {  # tapAttr text|content-desc 文字
  local b; b=$(ui | grep -F "$1=\"$2" | head -1 | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p')
  if [ -n "$b" ]; then local x1 y1 x2 y2; read -r x1 y1 x2 y2 <<< "$b"; adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); return 0; fi
  echo "  [$TAG] not found: $2"; return 1
}
fatal() { adb logcat -d -b crash 2>/dev/null | grep -q "FATAL EXCEPTION" || adb logcat -d 2>/dev/null | grep -A2 "FATAL EXCEPTION" | grep -q "$P"; }
check() {   # check 步骤名：进程不在了或者有 FATAL 就记下来
  sleep "${2:-2}"
  local alive=1; adb shell pidof $P > /dev/null || alive=0
  if fatal || [ $alive = 0 ]; then
    echo "!! [$TAG] CRASH at $1 (alive=$alive)"; bad=1
    { echo "=== $TAG / $1 (alive=$alive)"; adb logcat -d -b crash; adb logcat -d | grep -B2 -A40 "FATAL EXCEPTION"; } >> shots/crashhunt-$TAG.txt
    adb exec-out screencap -p > "shots/11-$TAG-crash-${1//[^a-zA-Z0-9_-]/_}.png"
    adb logcat -c; adb logcat -b crash -c 2>/dev/null
    return 1
  fi
  return 0
}
widgetStart() { adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open table -f 0x14000000 > /dev/null; }  # NEW_TASK | CLEAR_TOP，和小部件的 PendingIntent 一样
back() { adb shell input keyevent KEYCODE_BACK; sleep 1; }
# 模拟器是英文系统：先把 App 切成中文（setlang 会让进程自己退出重启，所以单独一步、不算崩溃），下面按中文文字点按钮
zh() { adb shell am start -n $P/com.zhishi.timetable.MainActivity --es setlang zh > /dev/null; sleep 4; adb shell am force-stop $P; adb logcat -c; adb logcat -b crash -c 2>/dev/null; }

adb uninstall $P > /dev/null 2>&1
timeout 180 "$(type -P adb)" install -r -g "$APK" > /dev/null || { echo "[$TAG] install failed"; exit 1; }
adb logcat -c; adb logcat -b crash -c 2>/dev/null
echo "== crashhunt $TAG"
zh
# 强制更新弹窗（查到新版时每次启动都弹，平时 CI 上弹不出来）：带课冷启动、点「更新」、返回
adb shell am start -n $P/com.zhishi.timetable.MainActivity --es seedat after > /dev/null; sleep 3
adb shell am force-stop $P; adb shell am start -n $P/com.zhishi.timetable.MainActivity --ei fakeneed 9999 > /dev/null; sleep 2
adb shell am force-stop $P; widgetStart; check update-cold 5; adb exec-out screencap -p > "shots/11-$TAG-update.png"
tapAttr text 更新 && { check update-tap 3; adb exec-out screencap -p > "shots/11-$TAG-update-tap.png"; back; check update-back 3; }
adb shell am force-stop $P; widgetStart; check update-cold2 5
adb shell pm clear $P > /dev/null; zh
MODES="after before between ending offday tiny old none"; OPTS=(从教务导入 "Excel / CSV 导入" "HTML 导入" "AI 识别截图" 从日历导入 日历订阅链接 ".ics 文件" 从备份导入)
# QUICK=1（每次发版的冒烟测试里用）：只跑最容易出事的几种（今天的课上完 / 正好下课 / 老数据 / 空），导入方式只点两种，省时间
if [ "${QUICK:-0}" = 1 ]; then MODES="after ending old none"; OPTS=(从教务导入 "Excel / CSV 导入"); fi
for mode in $MODES; do
  adb shell am force-stop $P
  if [ $mode = none ]; then adb shell pm clear $P > /dev/null; zh; adb shell am start -n $P/com.zhishi.timetable.MainActivity --ez notour true > /dev/null; sleep 4
  elif [ $mode = old ]; then adb shell am start -n $P/com.zhishi.timetable.MainActivity --ez oldformat true > /dev/null; sleep 4
  else adb shell am start -n $P/com.zhishi.timetable.MainActivity --es seedat $mode > /dev/null; sleep 4; fi
  # 冷启动两次（第二次模拟「清数据前一直崩」）
  for k in 1 2; do adb shell am force-stop $P; widgetStart; check "$mode-cold$k" 5 || continue; done
  adb exec-out screencap -p > "shots/11-$TAG-$mode-home.png"
  # 课表页「＋」→ 导入菜单 →「从教务导入」
  tapAttr content-desc 添加与导入 || tapAttr text 导入课表; check "$mode-menu" 2
  tapAttr text 从教务导入 && { check "$mode-jw" 4; adb exec-out screencap -p > "shots/11-$TAG-$mode-jw.png"; back; check "$mode-jw-back" 2; }
  # 「我的 → 导入课表」，每种导入方式点一遍（文件选择器打开后返回）
  for opt in "${OPTS[@]}"; do
    adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open me > /dev/null; sleep 2
    tapAttr text 导入课表 > /dev/null || { adb shell input swipe 540 1700 540 800 300; sleep 1; tapAttr text 导入课表; }
    check "$mode-me-import" 2 || break
    tapAttr text "$opt" > /dev/null || { adb shell input swipe 540 2000 540 1200 300; sleep 1; tapAttr text "$opt"; }
    check "$mode-opt-$opt" 3
    back; back; check "$mode-opt-$opt-back" 1
  done
  adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open widget > /dev/null; check "$mode-widget" 3
  adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open todo > /dev/null; check "$mode-todo" 2
  adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open calendar > /dev/null; check "$mode-calendar" 2
  # 正好下课：开着 App 等到这节课结束（课表页、现在条、小部件预览都要从「上课中」变成「上完」）
  if [ $mode = ending ]; then widgetStart; sleep 75; check "ending-transition" 1; adb shell am start -n $P/com.zhishi.timetable.MainActivity --es open widget > /dev/null; sleep 40; check "ending-transition-widget" 1; fi
done
echo "[$TAG] crashhunt bad=$bad"
exit $bad
