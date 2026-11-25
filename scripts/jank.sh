#!/usr/bin/env bash
# Frame stats for a scripted scroll of the home screen (release build, screen unlocked).
#   bash scripts/jank.sh <serial>        e.g. 192.168.1.47:38447 or emulator-5590
set -euo pipefail
SERIAL="${1:?serial}"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
ADB="$SDK/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB=adb
a() { "$ADB" -s "$SERIAL" "$@"; }

a shell input keyevent KEYCODE_WAKEUP
a shell am force-stop com.synrg.harbor
a shell am start -W -n com.synrg.harbor/.MainActivity | grep TotalTime
sleep 6
a shell dumpsys gfxinfo com.synrg.harbor reset > /dev/null
for _ in 1 2 3 4; do a shell input swipe 540 1900 540 500 250; sleep 0.6; done
for _ in 1 2 3; do a shell input swipe 900 1300 150 1300 200; sleep 0.6; done
for _ in 1 2 3 4; do a shell input swipe 540 500 540 1900 250; sleep 0.6; done
a shell dumpsys gfxinfo com.synrg.harbor | grep -E 'Total frames rendered|Janky frames:|50th percentile|90th percentile|99th percentile|Number Slow UI thread|Number Slow issue draw commands'
