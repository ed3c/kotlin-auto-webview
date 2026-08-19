#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_HEAD="${KAW_X1_SOURCE_HEAD:?KAW_X1_SOURCE_HEAD is required}"
readonly SOURCE_TREE="${KAW_X1_SOURCE_TREE:?KAW_X1_SOURCE_TREE is required}"
readonly SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?ANDROID_SDK_ROOT or ANDROID_HOME is required}}"
readonly EMULATOR_BIN="${SDK_ROOT}/emulator/emulator"
readonly AVD_NAME="kaw-runtime-api35"
readonly SYSTEM_IMAGE="system-images;android-35;google_apis;x86_64"
readonly RECEIPT_DIR="build/receipts"
readonly RECEIPT_PATH="${RECEIPT_DIR}/device-runtime-emulator.json"
readonly EMULATOR_LOG="${RECEIPT_DIR}/device-runtime-emulator.log"
readonly CONNECT_TIMEOUT_SECONDS=120
readonly BOOT_TIMEOUT_SECONDS=300
readonly TEST_TIMEOUT_SECONDS=600
readonly -a TEST_COMMAND=(scripts/ci/run-gradle-with-annotations.sh :composeApp:connectedDebugAndroidTest)

mkdir -p "$RECEIPT_DIR"
STATE="FAIL"
CONNECT_STATE="NOT_EXERCISED"
BOOT_STATE="NOT_EXERCISED"
TEST_STATE="NOT_EXERCISED"
REPORT_TESTS=0
REPORT_FAILURES=0
REPORT_SKIPPED=0
EMULATOR_PID=""

write_receipt() {
  local exit_code="$1"
  local head tree
  head="$(git rev-parse HEAD)"
  tree="$(git rev-parse 'HEAD^{tree}')"
  HEAD_VALUE="$head" TREE_VALUE="$tree" EXIT_VALUE="$exit_code" STATE_VALUE="$STATE" \
  CONNECT_VALUE="$CONNECT_STATE" BOOT_VALUE="$BOOT_STATE" TEST_VALUE="$TEST_STATE" RECEIPT_VALUE="$RECEIPT_PATH" \
  SOURCE_HEAD_VALUE="$SOURCE_HEAD" SOURCE_TREE_VALUE="$SOURCE_TREE" TESTS_VALUE="$REPORT_TESTS" \
  FAILURES_VALUE="$REPORT_FAILURES" SKIPPED_VALUE="$REPORT_SKIPPED" RUN_ID_VALUE="${GITHUB_RUN_ID:-ABSENT}" python3 - <<'PY'
import json
import os
from pathlib import Path

payload = {
    "schema": "kotlin-auto-webview/device-runtime-emulator-receipt/v1",
    "state": os.environ["STATE_VALUE"],
    "evidence_lane": "ANDROID_RUNTIME_EMULATOR_FIXTURE",
    "repository": "ed3c/kotlin-auto-webview",
    "source_head": os.environ["SOURCE_HEAD_VALUE"],
    "source_tree": os.environ["SOURCE_TREE_VALUE"],
    "evidence_head": os.environ["HEAD_VALUE"],
    "evidence_tree": os.environ["TREE_VALUE"],
    "ci_run_id": os.environ["RUN_ID_VALUE"],
    "command": [
        "scripts/ci/run-gradle-with-annotations.sh",
        ":composeApp:connectedDebugAndroidTest",
    ],
    "emulator_profile": {
        "api": 35,
        "image": "google_apis/x86_64",
        "avd": "kaw-runtime-api35",
    },
    "emulator_connection": os.environ["CONNECT_VALUE"],
    "emulator_boot": os.environ["BOOT_VALUE"],
    "instrumented_fixture_tests": os.environ["TEST_VALUE"],
    "instrumentation_report": {
        "tests": int(os.environ["TESTS_VALUE"]),
        "failures": int(os.environ["FAILURES_VALUE"]),
        "skipped": int(os.environ["SKIPPED_VALUE"]),
    },
    "accessibility_service_liveness": "NOT_EXERCISED",
    "production_service_registration": "ABSENT",
    "device_side_effects": "NOT_EXERCISED",
    "maximum_claim": "ANDROID_RUNTIME_EMULATOR_FIXTURE_ONLY",
    "exit_status": int(os.environ["EXIT_VALUE"]),
}
Path(os.environ["RECEIPT_VALUE"]).write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
PY
}

show_emulator_tail() {
  if [[ -f "$EMULATOR_LOG" ]]; then
    echo "--- bounded emulator log tail ---" >&2
    tail -n 160 "$EMULATOR_LOG" >&2 || true
    echo "--- end emulator log tail ---" >&2
  fi
}

cleanup() {
  local exit_code=$?
  if [[ "$exit_code" -ne 0 ]]; then
    show_emulator_tail
  fi
  if [[ -n "$EMULATOR_PID" ]]; then
    timeout 10s adb emu kill >/dev/null 2>&1 || true
    kill "$EMULATOR_PID" >/dev/null 2>&1 || true
    wait "$EMULATOR_PID" >/dev/null 2>&1 || true
  fi
  if [[ "$exit_code" -eq 0 ]]; then
    STATE="PASS"
  fi
  write_receipt "$exit_code"
}
trap cleanup EXIT

head_sha="$(git rev-parse HEAD)"
git fetch --no-tags --depth=32 origin "$head_sha"
git fetch --no-tags --depth=1 origin "$SOURCE_HEAD"
if ! git merge-base --is-ancestor "$SOURCE_HEAD" HEAD; then
  echo "Frozen X1 source head is not an ancestor of the evidence candidate" >&2
  exit 20
fi
actual_source_tree="$(git show -s --format=%T "$SOURCE_HEAD")"
if [[ "$actual_source_tree" != "$SOURCE_TREE" ]]; then
  echo "Frozen X1 source tree does not match the exact bound tree" >&2
  exit 21
fi

while IFS= read -r path; do
  case "$path" in
    .github/workflows/ci.yml|gradle/libs.versions.toml|composeApp/build.gradle.kts|composeApp/src/androidInstrumentedTest/kotlin/dev/ed3c/autowebview/device/runtime/*|scripts/ci/run-device-runtime-emulator.sh)
      ;;
    *)
      echo "Evidence child modified source or an undeclared path: $path" >&2
      exit 22
      ;;
  esac
done < <(git diff --name-only "$SOURCE_HEAD" HEAD)

yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install 'platform-tools' 'emulator' "$SYSTEM_IMAGE"
if [[ ! -x "$EMULATOR_BIN" ]]; then
  echo "Android emulator binary is absent after sdkmanager install: ${EMULATOR_BIN}" >&2
  exit 23
fi

export ANDROID_AVD_HOME="${RUNNER_TEMP:-/tmp}/kaw-runtime-avd-home"
mkdir -p "$ANDROID_AVD_HOME"
echo no | avdmanager create avd --force --name "$AVD_NAME" --package "$SYSTEM_IMAGE"

if [[ -e /dev/kvm ]]; then
  sudo chmod 0666 /dev/kvm
  ACCEL_ARGS=()
else
  ACCEL_ARGS=(-accel off)
fi

"$EMULATOR_BIN" -avd "$AVD_NAME" \
  -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -no-metrics \
  -gpu swiftshader_indirect "${ACCEL_ARGS[@]}" >"$EMULATOR_LOG" 2>&1 &
EMULATOR_PID=$!

adb start-server >/dev/null
connect_deadline=$((SECONDS + CONNECT_TIMEOUT_SECONDS))
while [[ "$SECONDS" -lt "$connect_deadline" ]]; do
  if ! kill -0 "$EMULATOR_PID" >/dev/null 2>&1; then
    echo "Android emulator terminated before registering with adb" >&2
    exit 24
  fi
  if timeout 5s adb devices | awk 'NR > 1 && $2 == "device" { found=1 } END { exit(found ? 0 : 1) }'; then
    CONNECT_STATE="PASS"
    break
  fi
  sleep 2
done
if [[ "$CONNECT_STATE" != "PASS" ]]; then
  echo "Android emulator did not register with adb within ${CONNECT_TIMEOUT_SECONDS}s" >&2
  exit 25
fi

boot_deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
while [[ "$SECONDS" -lt "$boot_deadline" ]]; do
  if ! kill -0 "$EMULATOR_PID" >/dev/null 2>&1; then
    echo "Android emulator terminated before boot completed" >&2
    exit 26
  fi
  boot_value="$(timeout 5s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [[ "$boot_value" == "1" ]]; then
    BOOT_STATE="PASS"
    break
  fi
  sleep 2
done
if [[ "$BOOT_STATE" != "PASS" ]]; then
  echo "Android emulator did not boot within ${BOOT_TIMEOUT_SECONDS}s" >&2
  exit 27
fi

set +e
timeout --signal=TERM "${TEST_TIMEOUT_SECONDS}s" "${TEST_COMMAND[@]}"
test_exit=$?
set -e
if [[ "$test_exit" -ne 0 ]]; then
  if [[ "$test_exit" -eq 124 ]]; then
    echo "Android instrumented fixture timed out after ${TEST_TIMEOUT_SECONDS}s" >&2
  else
    echo "Android instrumented fixture failed with exit ${test_exit}" >&2
  fi
  exit "$test_exit"
fi

read -r REPORT_TESTS REPORT_FAILURES REPORT_SKIPPED < <(python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

roots = []
for path in Path("composeApp/build/outputs/androidTest-results").rglob("*.xml"):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    if root.tag in {"testsuite", "testsuites"}:
        roots.append(root)

tests = failures = skipped = 0
for root in roots:
    suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
    for suite in suites:
        tests += int(suite.attrib.get("tests", "0"))
        failures += int(suite.attrib.get("failures", "0")) + int(suite.attrib.get("errors", "0"))
        skipped += int(suite.attrib.get("skipped", "0"))
print(tests, failures, skipped)
PY
)
if [[ "$REPORT_TESTS" -le 0 || "$REPORT_FAILURES" -ne 0 ]]; then
  echo "Android instrumentation report is absent or contains failures: tests=${REPORT_TESTS} failures=${REPORT_FAILURES}" >&2
  exit 28
fi
TEST_STATE="PASS"
