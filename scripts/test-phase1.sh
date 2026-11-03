#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to an emulator serial}"
[[ "$ANDROID_SERIAL" == emulator-* ]] || { echo 'This integration script is emulator-only.' >&2; exit 1; }
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$ANDROID_HOME/platform-tools/adb"
cd "$PROJECT_DIR"
"$ADB" -s "$ANDROID_SERIAL" shell svc wifi enable
"$ADB" -s "$ANDROID_SERIAL" shell svc data enable
"$ADB" -s "$ANDROID_SERIAL" shell cmd overlay enable --user current com.android.internal.systemui.navbar.gestural
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/phase1-test-server.py &
fixture_pid=$!
trap 'mkdir -p app/build/reports/androidTests/connected; "$ADB" -s "$ANDROID_SERIAL" logcat -d -s AndroidRuntime:E TestRunner:I BlockAdsPhase3:I > app/build/reports/androidTests/connected/test-logcat.txt 2>/dev/null || true; kill "$fixture_pid" 2>/dev/null || true; "$ADB" -s "$ANDROID_SERIAL" shell svc wifi enable >/dev/null 2>&1 || true' EXIT
python3 - <<'PY'
import urllib.request,time
for attempt in range(50):
    try:
        assert urllib.request.urlopen('http://127.0.0.1:18080/small',timeout=1).read() == b'blockads-phase1'
        break
    except Exception:
        if attempt==49: raise
        time.sleep(.1)
PY
kill -0 "$fixture_pid" || { echo 'Fixture ports 18080/18081 are already occupied.' >&2; exit 1; }
"$ADB" -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s "$ANDROID_SERIAL" shell appops set com.utc.color.blockads ACTIVATE_VPN allow
"$ADB" -s "$ANDROID_SERIAL" shell pm grant com.utc.color.blockads android.permission.POST_NOTIFICATIONS
api_level="$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
if (( api_level >= 37 )); then
    "$ADB" -s "$ANDROID_SERIAL" shell pm grant com.utc.color.blockads android.permission.ACCESS_LOCAL_NETWORK
fi
./gradlew :app:connectedDebugAndroidTest "$@"
