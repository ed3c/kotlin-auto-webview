#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_HEAD="${KAW_A1_SOURCE_HEAD:?KAW_A1_SOURCE_HEAD is required}"
readonly SOURCE_TREE="${KAW_A1_SOURCE_TREE:?KAW_A1_SOURCE_TREE is required}"
readonly AVD_NAME="kaw-observation-api35"
readonly SYSTEM_IMAGE="system-images;android-35;google_apis;x86_64"
readonly RECEIPT_DIR="build/receipts"
readonly RECEIPT_PATH="${RECEIPT_DIR}/android-observation-emulator.json"
readonly EMULATOR_LOG="${RUNNER_TEMP:-/tmp}/kaw-observation-emulator.log"
readonly BOOT_TIMEOUT_SECONDS=300
readonly -a TEST_COMMAND=(scripts/ci/run-gradle-with-annotations.sh :composeApp:connectedDebugAndroidTest)

mkdir -p "$RECEIPT_DIR"
STATE="FAIL"
BOOT_STATE="NOT_EXERCISED"
TEST_STATE="NOT_EXERCISED"
EMULATOR_PID=""

write_receipt() {
  local exit_code="$1"
  local head tree
  head="$(git rev-parse HEAD)"
  tree="$(git rev-parse 'HEAD^{tree}')"
  HEAD_VALUE="$head" TREE_VALUE="$tree" EXIT_VALUE="$exit_code" STATE_VALUE="$STATE" \
  BOOT_VALUE="$BOOT_STATE" TEST_VALUE="$TEST_STATE" RECEIPT_VALUE="$RECEIPT_PATH" \
  SOURCE_HEAD_VALUE="$SOURCE_HEAD" SOURCE_TREE_VALUE="$SOURCE_TREE" python3 - <<'PY'
import json
import os
from pathlib import Path

payload = {
    "schema": "kotlin-auto-webview/android-observation-emulator-receipt/v1",
    "state": os.environ["STATE_VALUE"],
    "evidence_lane": "EMULATOR_FIXTURE",
    "repository": "ed3c/kotlin-auto-webview",
    "head_commit": os.environ["HEAD_VALUE"],
    "head_tree": os.environ["TREE_VALUE"],
    "a1_source_head": os.environ["SOURCE_HEAD_VALUE"],
    "a1_source_tree": os.environ["SOURCE_TREE_VALUE"],
    "command": [
        "scripts/ci/run-gradle-with-annotations.sh",
        ":composeApp:connectedDebugAndroidTest",
    ],
    "emulator_profile": {
        "api": 35,
        "image": "google_apis/x86_64",
        "avd": "kaw-observation-api35",
    },
    "emulator_boot": os.environ["BOOT_VALUE"],
    "instrumented_fixture_tests": os.environ["TEST_VALUE"],
    "accessibility_service_liveness": "NOT_EXERCISED",
    "production_service_registration": "ABSENT",
    "device_side_effects": "NOT_EXERCISED",
    "maximum_claim": "ANDROID_OBSERVATION_EMULATOR_FIXTURE_SEMANTICS_ONLY",
    "exit_status": int(os.environ["EXIT_VALUE"]),
}
Path(os.environ["RECEIPT_VALUE"]).write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
PY
}

cleanup() {
  local exit_code=$?
  if [[ -n "$EMULATOR_PID" ]]; then
    adb emu kill >/dev/null 2>&1 || true
    kill "$EMULATOR_PID" >/dev/null 2>&1 || true
    wait "$EMULATOR_PID" >/dev/null 2>&1 || true
  fi
  if [[ "$exit_code" -eq 0 ]]; then
    STATE="PASS"
  fi
  write_receipt "$exit_code"
}
trap cleanup EXIT

# The exact PR head is checked out at depth 1. Fetch a bounded history rooted at
# that immutable HEAD, then fetch the exact frozen source object. Ancestry + tree
# identity + a 5-path allowlist jointly prove that the evidence branch did not
# rewrite A1 source while still allowing evidence-only repair commits.
head_sha="$(git rev-parse HEAD)"
git fetch --no-tags --depth=32 origin "$head_sha"
git fetch --no-tags --depth=1 origin "$SOURCE_HEAD"
if ! git merge-base --is-ancestor "$SOURCE_HEAD" HEAD; then
  echo "Frozen A1 source head is not an ancestor of the evidence candidate" >&2
  exit 20
fi
actual_source_tree="$(git show -s --format=%T "$SOURCE_HEAD")"
if [[ "$actual_source_tree" != "$SOURCE_TREE" ]]; then
  echo "A1 source tree does not match the frozen exact tree" >&2
  exit 21
fi

while IFS= read -r path; do
  case "$path" in
    .github/workflows/ci.yml|gradle/libs.versions.toml|composeApp/build.gradle.kts|composeApp/src/androidInstrumentedTest/kotlin/dev/ed3c/autowebview/device/accessibility/AccessibilityObservationInstrumentedTest.kt|scripts/ci/run-android-observation-emulator.sh)
      ;;
    *)
      echo "Evidence child modified source or an undeclared path: $path" >&2
      exit 22
      ;;
  esac
done < <(git diff --name-only "$SOURCE_HEAD" HEAD)

yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install 'platform-tools' 'emulator' "$SYSTEM_IMAGE"

export ANDROID_AVD_HOME="${RUNNER_TEMP:-/tmp}/kaw-avd-home"
mkdir -p "$ANDROID_AVD_HOME"
echo no | avdmanager create avd --force --name "$AVD_NAME" --package "$SYSTEM_IMAGE"

if [[ -e /dev/kvm ]]; then
  sudo chmod 0666 /dev/kvm
  ACCEL_ARGS=()
else
  ACCEL_ARGS=(-accel off)
fi

emulator -avd "$AVD_NAME" \
  -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -no-metrics \
  -gpu swiftshader_indirect "${ACCEL_ARGS[@]}" >"$EMULATOR_LOG" 2>&1 &
EMULATOR_PID=$!

adb wait-for-device
deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
while [[ "$SECONDS" -lt "$deadline" ]]; do
  if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    BOOT_STATE="PASS"
    break
  fi
  if ! kill -0 "$EMULATOR_PID" >/dev/null 2>&1; then
    echo "Android emulator terminated before boot" >&2
    exit 23
  fi
  sleep 2
done
if [[ "$BOOT_STATE" != "PASS" ]]; then
  echo "Android emulator did not boot within ${BOOT_TIMEOUT_SECONDS}s" >&2
  exit 24
fi

"${TEST_COMMAND[@]}"
TEST_STATE="PASS"
