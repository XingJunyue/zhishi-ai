#!/usr/bin/env bash
# 待办小部件刷新延迟：把 4×2 待办小部件放到桌面，改待办（加 / 改名 / 完成），量从改动到桌面取到新行（TodoRows.getViewAt）的时间。
set -u
P=com.zhishi.timetable.ai
A=com.zhishi.timetable.MainActivity
R=com.zhishi.timetable.TestHookReceiver
mkdir -p shots
shot() { sleep "${2:-0}"; adb exec-out screencap -p > "shots/$1.png"; }
bc() { adb shell am broadcast -n $P/$R "$@" > /dev/null; }
tapRe() {  # 按正则（不分大小写）找文字点
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  local b; b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -iE "text=\"$1" | head -1 | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p')
  if [ -n "$b" ]; then local x1 y1 x2 y2; read -r x1 y1 x2 y2 <<< "$b"; adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); return 0; fi; return 1
}
waitlog() {  # $1 = 正则，$2 = 秒
  for i in $(seq 1 $(( ${2:-20} * 4 ))); do adb logcat -d -s ZhishiTest | grep -qE "$1" && return 0; sleep 0.25; done; return 1
}
adb wait-for-device
for i in $(seq 1 90); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 2; done
adb shell settings put global hide_error_dialogs 1
adb shell svc power stayon true
sleep 15
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
adb shell input keyevent KEYCODE_WAKEUP
adb install -r -g out/test-debug.apk || exit 1
adb shell am start -n $P/$A --ez seed true --ez notour true; sleep 8
adb shell input keyevent KEYCODE_HOME; sleep 3
adb logcat -c
# 放小部件要在前台请求（后台请求桌面不理）
adb shell am start -n $P/$A --ez wpin true > /dev/null; sleep 5; shot pin-dialog
adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml > shots/pin-ui.xml
tapRe "Add to home screen" || tapRe "ADD AUTOMATICALLY" || tapRe "ADD" || tapRe "Add" || echo "pin button not found"
sleep 5; adb shell input keyevent KEYCODE_HOME; sleep 4; shot widget-pinned
adb logcat -d -s ZhishiTest > shots/pin-log.txt; cat shots/pin-log.txt | grep -E "WPIN|WLAT" | head
adb shell dumpsys appwidget > shots/appwidget.txt; grep -E "TodoWidget|id=" shots/appwidget.txt | head -20
measure() {  # $1 = 标签, $2.. = 广播参数；量到第一次「row」/「has=false」
  local tag=$1; shift; local want=$1; shift
  adb logcat -c
  bc "$@"
  shot "$tag-1s" 1; shot "$tag-3s" 2
  if waitlog "$want" 30; then :; else echo "$tag: timeout"; fi
  shot "$tag-end" 0
  adb logcat -d -s ZhishiTest | grep WLAT | tee "shots/$tag.txt"
  echo "RESULT $tag $(grep -E "$want" "shots/$tag.txt" | head -1)"
}
sleep 10
measure addA "WLAT row" --es wlat "LAT-A1"
sleep 3
measure addB "WLAT row" --es wlat "LAT-A2"
sleep 30   # 让桌面的列表连接空闲断开（RemoteViewsAdapter 5 秒后解绑）
measure addCold "WLAT row" --es wlat "LAT-A3"
sleep 3
measure edit "WLAT row" --es wlatedit "LAT-E1"
sleep 3
measure done "has=false" --es wlatdone "LAT-A2"
sleep 3
# 在 App 里加，过 2 秒回到桌面
adb logcat -c
adb shell am start -n $P/$A --es open todo --es wlat "LAT-B1" > /dev/null; sleep 3
adb logcat -d -s ZhishiTest | grep WLAT | tee shots/inapp-before-home.txt
bc --es wlatmark "LAT-B1"; adb shell input keyevent KEYCODE_HOME
shot inapp-1s 1; shot inapp-3s 2
waitlog "WLAT row" 30 || echo "inapp: timeout"
adb logcat -d -s ZhishiTest | grep WLAT | tee shots/inapp.txt
echo "RESULT inapp $(grep 'WLAT row' shots/inapp.txt | head -1)"
shot inapp-end 2
adb logcat -d > shots/logcat.txt
grep RESULT -h shots/*.txt 2>/dev/null; true
