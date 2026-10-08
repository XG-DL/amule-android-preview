#!/usr/bin/env bash
set -euo pipefail

full_apk=${1:?Pass the packaged ARM64 APK path}
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

install_apk() {
    local apk=$1 attempt
    for attempt in 1 2 3 4 5 6; do
        if timeout 10 adb shell pm path android >/dev/null 2>&1 \
                && timeout 120 adb install -r --no-streaming "$apk"; then
            return 0
        fi
        printf 'Package manager unavailable; retrying %s (attempt %s/6).\n' "$apk" "$attempt" >&2
        sleep 8
    done
    return 1
}

# The exporter tests use a local mock API. Package a test host without the
# ARM64 executables so the tests can run on the x86_64 emulator.
./gradlew --no-daemon clean assembleDebug assembleDebugAndroidTest \
    -PandroidExporterTestHost=true
install_apk app/build/outputs/apk/debug/app-debug.apk
install_apk "$test_apk"

if ! test_output=$(adb shell am instrument -w -r \
    -e class uk.xgdl.amuleprobe.CompletedDownloadExporterTest \
    uk.xgdl.amuleprobe.test/androidx.test.runner.AndroidJUnitRunner); then
    printf '%s\n' "$test_output"
    exit 1
fi
printf '%s\n' "$test_output"
if [[ "$test_output" != *'OK (5 tests)'* \
        || "$test_output" == *'FAILURES!!!'* \
        || "$test_output" != *'INSTRUMENTATION_CODE: -1'* ]]; then
    printf 'Exporter instrumentation did not report success.\n' >&2
    adb logcat -d -t 300 -s 'aMuleExportTest:E' 'AndroidRuntime:E' '*:S' >&2 || true
    exit 1
fi

# Install the real package and exercise its daemon, local API and service.
install_apk "$full_apk"
adb shell pm grant uk.xgdl.amuleprobe android.permission.POST_NOTIFICATIONS
adb shell am start -n uk.xgdl.amuleprobe/.MainActivity
smoke_log=${RUNNER_TEMP:-/tmp}/amule-ci-smoke.log
smoke_passed=false
for ((attempt = 1; attempt <= 24; attempt++)); do
    if scripts/android-smoke-test.sh >"$smoke_log" 2>&1; then
        cat "$smoke_log"
        smoke_passed=true
        break
    fi
    sleep 5
done
if [[ "$smoke_passed" != true ]]; then
    cat "$smoke_log" >&2
    adb shell run-as uk.xgdl.amuleprobe cat shared_prefs/amule_runtime.xml 2>/dev/null \
        | python3 -c 'import sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.stdin).getroot()
    error = next((item.text for item in root.findall("string") if item.get("name") == "last_error"), None)
    print("Last app startup error:", error or "(none)")
except Exception as failure:
    print("Could not read the app startup error:", failure)' >&2 || true
    exit 1
fi

# The native branch supplies an additional test that drives the packaged UI.
if [[ -f app/src/androidTest/java/uk/xgdl/amuleprobe/NativeUiSmokeTest.java ]]; then
    if ! ui_output=$(adb shell am instrument -w -r \
        -e class uk.xgdl.amuleprobe.NativeUiSmokeTest \
        uk.xgdl.amuleprobe.test/androidx.test.runner.AndroidJUnitRunner); then
        printf '%s\n' "$ui_output"
        exit 1
    fi
    printf '%s\n' "$ui_output"
    if [[ "$ui_output" != *'OK (1 test)'* \
            || "$ui_output" == *'FAILURES!!!'* \
            || "$ui_output" != *'INSTRUMENTATION_CODE: -1'* ]]; then
        printf 'Native UI instrumentation did not report success.\n' >&2
        adb logcat -d -t 300 -s 'AndroidRuntime:E' '*:S' >&2 || true
        exit 1
    fi
fi
