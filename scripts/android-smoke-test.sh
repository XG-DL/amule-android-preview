#!/usr/bin/env bash
set -euo pipefail

package_name=uk.xgdl.amuleprobe
adb_bin=${ADB:-adb}
serial=${ANDROID_SERIAL:-}

if [[ -z "$serial" ]]; then
	mapfile -t devices < <("$adb_bin" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
	if [[ ${#devices[@]} -ne 1 ]]; then
		printf 'Expected one authorised Android device; found %s. Set ANDROID_SERIAL to choose one.\n' "${#devices[@]}" >&2
		exit 2
	fi
	serial=${devices[0]}
fi

adb_device() {
	"$adb_bin" -s "$serial" "$@"
}

if [[ "$(adb_device get-state)" != device ]]; then
	printf 'Android device %s is not ready. Unlock it and authorise USB debugging.\n' "$serial" >&2
	exit 2
fi

processes=$(adb_device shell 'ps -A -o ARGS 2>/dev/null || ps -A')
if ! grep -q 'libamuled\.so' <<<"$processes"; then
	printf 'aMule daemon process is not running.\n' >&2
	exit 1
fi
if ! grep -q 'libamuleapi\.so' <<<"$processes"; then
	printf 'aMule Web API process is not running.\n' >&2
	exit 1
fi

services=$(adb_device shell dumpsys activity services "$package_name")
if ! grep -q 'isForeground=true' <<<"$services"; then
	printf 'Android foreground service is not active.\n' >&2
	exit 1
fi

local_port=$(adb_device forward tcp:0 tcp:4713)
forward_spec="tcp:$local_port"
cleanup() {
	adb_device forward --remove "$forward_spec" >/dev/null 2>&1 || true
}
trap cleanup EXIT

python3 - "$adb_bin" "$serial" "$package_name" "$local_port" "${AMULE_EXPECT_SHARED:-}" <<'PY'
import json
from collections import Counter
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

adb, serial, package, port, expected_name = sys.argv[1:]
stored = subprocess.run(
    [adb, "-s", serial, "shell", "run-as", package, "cat",
     "shared_prefs/amule_runtime.xml"],
    check=True, capture_output=True, text=True,
).stdout
root = ET.fromstring(stored)
password = next(
    item.text for item in root.findall("string")
    if item.attrib.get("name") == "admin_password"
)
base = f"http://127.0.0.1:{port}/api/v1/"

def call(path, *, method="GET", token=None, body=None, accept=None):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if accept:
        headers["Accept"] = accept
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    request = urllib.request.Request(base + path, data=data,
                                     headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=15) as response:
        payload = response.read()
        return json.loads(payload) if payload else {}

login = call("auth/login", method="POST", body={"password": password},
             accept="application/jwt")
token = login.get("token")
if not token:
    raise SystemExit("Local API login did not return a session token.")

try:
    status = call("status", token=token)
    downloads = call("downloads?status=all&limit=1000000000", token=token).get("downloads")
    shared = call("shared?limit=1000000000", token=token).get("shared")
    directories = call("share_directories?limit=1000000000", token=token).get("directories")
    if not isinstance(downloads, list) or not isinstance(shared, list):
        raise SystemExit("Local API returned an unexpected downloads/shared response.")
    if not isinstance(directories, list):
        raise SystemExit("Local API returned an unexpected shared-directories response.")
    status_counts = Counter(item.get("status", "unknown") for item in downloads)
    active_sources = sum(
        item.get("sources", {}).get("transferring", 0)
        for item in downloads if item.get("status") == "downloading"
    )
    transferred = sum(item.get("transferred_bytes", 0) for item in downloads)
    if expected_name and not any(
        expected_name.casefold() in item.get("name", "").casefold()
        for item in shared
    ):
        raise SystemExit("The requested test file was not found in aMule's shared list.")
    print("PASS: Android daemon, API, and foreground service are running.")
    print(f"PASS: Local API returned status, {len(downloads)} download rows, "
          f"{len(shared)} shared-file rows, and {len(directories)} shared directories.")
    print(f"Download activity: {dict(status_counts)}; {active_sources} sources transferring; "
          f"{transferred} transferred bytes recorded.")
    if expected_name:
        print("PASS: Requested test file appears in aMule's shared list.")
finally:
    try:
        call("auth/logout", method="POST", token=token)
    except (urllib.error.URLError, TimeoutError):
        pass
PY
