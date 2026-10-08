#!/usr/bin/env bash
set -euo pipefail

full_apk=${1:?Pass the packaged ARM64 APK path}
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# The exporter tests use a local mock API. Package a test host without the
# ARM64 executables so the tests can run on the x86_64 emulator.
./gradlew --no-daemon clean assembleDebug assembleDebugAndroidTest \
    -PandroidExporterTestHost=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r "$test_apk"

if ! test_output=$(adb shell am instrument -w -r \
    -e class uk.xgdl.amuleprobe.CompletedDownloadExporterTest \
    uk.xgdl.amuleprobe.test/androidx.test.runner.AndroidJUnitRunner); then
    printf '%s\n' "$test_output"
    exit 1
fi
printf '%s\n' "$test_output"
if [[ "$test_output" != *'INSTRUMENTATION_CODE: -1'* ]]; then
    printf 'Exporter instrumentation did not report success.\n' >&2
    exit 1
fi

# Install the real package and exercise its daemon, local API and service.
adb install -r "$full_apk"
adb shell am start -n uk.xgdl.amuleprobe/.MainActivity
smoke_log=${RUNNER_TEMP:-/tmp}/amule-ci-smoke.log
for ((attempt = 1; attempt <= 24; attempt++)); do
    if scripts/android-smoke-test.sh >"$smoke_log" 2>&1; then
        cat "$smoke_log"
        exit 0
    fi
    sleep 5
done
cat "$smoke_log" >&2
exit 1
