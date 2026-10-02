#!/usr/bin/env bash
set -euo pipefail

# Run on the CI emulator after instrumentation has finished. Start the installed
# APK in its own process, without the test APK's class loader or dependencies.
app_package=io.github.chimio.inxlocker
report_dir=app/build/reports/startup
mkdir -p "$report_dir"
adb shell am force-stop "$app_package"
adb logcat -c
adb shell am start -W -n "$app_package/.ui.activity.MainActivity" > "$report_dir/launch.txt" 2>&1
sleep 10
adb logcat -d -v threadtime > "$report_dir/logcat.txt"
adb logcat -b crash -d -v threadtime > "$report_dir/crash.txt"
adb shell dumpsys activity activities > "$report_dir/activities.txt"
cat "$report_dir/launch.txt"

if ! adb shell pidof "$app_package"; then
    echo "InxLocker exited after launch"
    cat "$report_dir/crash.txt"
    exit 1
fi

if ! grep -E 'mResumedActivity:|topResumedActivity=|mFocusedActivity:' "$report_dir/activities.txt" | grep -F "$app_package/"; then
    echo "InxLocker did not keep a resumed activity"
    cat "$report_dir/crash.txt"
    exit 1
fi
