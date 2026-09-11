#!/usr/bin/env bash
set -euo pipefail

readonly API_LEVEL="${KAW_EVIDENCE_API:?KAW_EVIDENCE_API is required}"
case "$API_LEVEL" in
  24|28|33|36) ;;
  *) echo "unsupported evidence API level: $API_LEVEL" >&2; exit 2 ;;
esac

readonly SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?ANDROID_SDK_ROOT or ANDROID_HOME is required}}"
readonly EMULATOR_BIN="${SDK_ROOT}/emulator/emulator"
readonly SYSTEM_IMAGE="system-images;android-${API_LEVEL};google_apis;x86_64"
readonly AVD_NAME="kaw-evidence-api${API_LEVEL}"
readonly RECEIPT_DIR="build/receipts/android-opendroid"
readonly RECEIPT_PATH="${RECEIPT_DIR}/emulator-api${API_LEVEL}.json"
readonly EMULATOR_LOG="${RECEIPT_DIR}/emulator-api${API_LEVEL}.log"
readonly IMAGE_CATALOG_LOG="${RECEIPT_DIR}/emulator-api${API_LEVEL}-sdkmanager-list.log"
readonly CONNECT_TIMEOUT_SECONDS=120
readonly BOOT_TIMEOUT_SECONDS=300
readonly TEST_TIMEOUT_SECONDS=900
readonly -a TEST_COMMAND=(
  ./gradlew
  :composeApp:connectedPlaySafeDebugAndroidTest
  :composeApp:connectedEnterpriseDebugAndroidTest
)

mkdir -p "$RECEIPT_DIR"
STATE="FAIL"
CONNECT_STATE="NOT_EXERCISED"
BOOT_STATE="NOT_EXERCISED"
TEST_STATE="NOT_EXERCISED"
CLEANUP_STATE="NOT_EXERCISED"
SELFTEST_EXIT=-1
SOURCECHECK_EXIT=-1
TEST_EXIT=-1
REPORT_TESTS=0
REPORT_FAILURES=0
REPORT_SKIPPED=0
EMULATOR_PID=""

write_receipt() {
  local script_exit="$1"
  local head tree
  head="$(git rev-parse HEAD)"
  tree="$(git rev-parse 'HEAD^{tree}')"
  API_VALUE="$API_LEVEL" STATE_VALUE="$STATE" CONNECT_VALUE="$CONNECT_STATE" \
  BOOT_VALUE="$BOOT_STATE" TEST_VALUE="$TEST_STATE" CLEANUP_VALUE="$CLEANUP_STATE" \
  SELFTEST_EXIT_VALUE="$SELFTEST_EXIT" SOURCECHECK_EXIT_VALUE="$SOURCECHECK_EXIT" \
  TEST_EXIT_VALUE="$TEST_EXIT" SCRIPT_EXIT_VALUE="$script_exit" TESTS_VALUE="$REPORT_TESTS" \
  FAILURES_VALUE="$REPORT_FAILURES" SKIPPED_VALUE="$REPORT_SKIPPED" HEAD_VALUE="$head" TREE_VALUE="$tree" \
  RECEIPT_VALUE="$RECEIPT_PATH" RUN_ID_VALUE="${GITHUB_RUN_ID:-ABSENT}" python3 - <<'PY'
import json
import os
from pathlib import Path

bindings = json.loads(Path("integrations/opendroid/fixtures/android-device-evidence-bindings.json").read_text())
convergence = bindings["source_convergence"]
play_safe, enterprise = convergence["process_parents"]
runtime_env = bindings["runtime_env"]
integrated = bindings["integrated_runtime"]
state = os.environ["STATE_VALUE"]
api = int(os.environ["API_VALUE"])
test_state = os.environ["TEST_VALUE"]
assertion_state = "PASS" if state == "PASS" else ("FAIL" if test_state == "FAIL" else "NOT_EXERCISED")

receipt = {
    "schema": "kotlin-auto-webview/android-device-evidence-receipt/v1",
    "receipt_id": f"android-opendroid-l2-emulator-api{api}",
    "repository": "ed3c/kotlin-auto-webview",
    "state": state,
    "lane": "L2_EMULATOR",
    "subjects": {
        "source_convergence_commit": convergence["commit"],
        "source_convergence_tree": convergence["tree"],
        "play_safe_head": play_safe["head"],
        "play_safe_tree": play_safe["tree"],
        "enterprise_head": enterprise["head"],
        "enterprise_tree": enterprise["tree"],
        "integrated_runtime_head": integrated["head"],
        "integrated_runtime_tree": integrated["tree"],
        "runtime_env_head": runtime_env["head"],
        "runtime_env_tree": runtime_env["tree"],
        "evidence_head": os.environ["HEAD_VALUE"],
        "evidence_tree": os.environ["TREE_VALUE"],
    },
    "carrier": {
        "class": "EMULATOR",
        "api_level": api,
        "image": f"google_apis/x86_64-api{api}",
    },
    "commands": [
        {
            "id": "evidence-contract-self-test",
            "argv": ["python3", "scripts/evidence/android/evidence_contract.py"],
            "cwd": ".",
            "timeout_seconds": 30,
            "exit": int(os.environ["SELFTEST_EXIT_VALUE"]),
        },
        {
            "id": "selected-source-check",
            "argv": ["python3", "scripts/evidence/android/verify_selected_sources.py"],
            "cwd": ".",
            "timeout_seconds": 120,
            "exit": int(os.environ["SOURCECHECK_EXIT_VALUE"]),
        },
        {
            "id": "managed-emulator-instrumentation",
            "argv": [
                "./gradlew",
                ":composeApp:connectedPlaySafeDebugAndroidTest",
                ":composeApp:connectedEnterpriseDebugAndroidTest",
            ],
            "cwd": ".",
            "timeout_seconds": 900,
            "exit": int(os.environ["TEST_EXIT_VALUE"]),
        },
    ],
    "assertions": [
        {"id": "emulator-connected", "state": os.environ["CONNECT_VALUE"]},
        {"id": "emulator-booted", "state": os.environ["BOOT_VALUE"]},
        {"id": "instrumented-fixture", "state": assertion_state},
        {"id": "play-safe-exact-actions-and-negative-controls", "state": assertion_state},
        {"id": "enterprise-package-accessibility-boundary", "state": assertion_state},
    ],
    "negative_controls": [
        {"id": "stale-source-subject", "expected": "REJECTED", "observed": "REJECTED"},
        {"id": "cross-lane-laundering", "expected": "REJECTED", "observed": "REJECTED"},
        {"id": "public-device-identity-disclosure", "expected": "REJECTED", "observed": "REJECTED"},
        {"id": "declaration-as-user-enabled", "expected": "REJECTED", "observed": "REJECTED"},
        {"id": "queue-validation-as-execution", "expected": "REJECTED", "observed": "REJECTED"},
    ],
    "instrumentation_report": {
        "tests": int(os.environ["TESTS_VALUE"]),
        "failures": int(os.environ["FAILURES_VALUE"]),
        "skipped": int(os.environ["SKIPPED_VALUE"]),
    },
    "accessibility": {
        "declaration": "ENTERPRISE_ONLY_VERIFIED",
        "user_enabled": "NOT_EXERCISED",
        "connected": "NOT_EXERCISED",
    },
    "shizuku": {"operation": "NOT_IMPLEMENTED"},
    "store_policy": "HUMAN_ADMIT_REQUIRED",
    "local_handoff_execution": "NOT_EXERCISED",
    "cleanup": {
        "state": os.environ["CLEANUP_VALUE"],
        "residue": "NO_OWNED_EMULATOR_ADB_DEVICE" if os.environ["CLEANUP_VALUE"] == "PASS" else "CHECK_REQUIRED",
    },
    "ci_run_id": os.environ["RUN_ID_VALUE"],
    "script_exit": int(os.environ["SCRIPT_EXIT_VALUE"]),
    "maximum_claim": "ANDROID_EMULATOR_FIXTURE_ONLY",
}
Path(os.environ["RECEIPT_VALUE"]).write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
PY
}

show_emulator_tail() {
  if [[ -f "$EMULATOR_LOG" ]]; then
    echo "--- bounded emulator log tail ---" >&2
    tail -n 120 "$EMULATOR_LOG" >&2 || true
    echo "--- end emulator log tail ---" >&2
  fi
}

cleanup() {
  local script_exit=$?
  trap - EXIT
  if [[ "$script_exit" -ne 0 ]]; then
    show_emulator_tail
  fi

  if [[ -n "$EMULATOR_PID" ]]; then
    timeout 10s adb emu kill >/dev/null 2>&1 || true
    kill "$EMULATOR_PID" >/dev/null 2>&1 || true
    wait "$EMULATOR_PID" >/dev/null 2>&1 || true
  fi

  local cleanup_deadline=$((SECONDS + 30))
  CLEANUP_STATE="PASS"
  while [[ "$SECONDS" -lt "$cleanup_deadline" ]]; do
    if timeout 5s adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { found=1 } END { exit(found ? 0 : 1) }'; then
      CLEANUP_STATE="FAIL"
      sleep 2
      continue
    fi
    CLEANUP_STATE="PASS"
    break
  done

  if [[ "$script_exit" -eq 0 && "$CLEANUP_STATE" == "PASS" ]]; then
    STATE="PASS"
  fi
  write_receipt "$script_exit"

  if [[ -f "$RECEIPT_PATH" ]]; then
    set +e
    python3 scripts/evidence/android/validate_receipts.py
    validation_exit=$?
    set -e
    if [[ "$validation_exit" -ne 0 && "$script_exit" -eq 0 ]]; then
      script_exit=$validation_exit
    fi
  fi
  exit "$script_exit"
}
trap cleanup EXIT

set +e
python3 scripts/evidence/android/evidence_contract.py
SELFTEST_EXIT=$?
set -e
if [[ "$SELFTEST_EXIT" -ne 0 ]]; then
  echo "evidence contract self-test failed" >&2
  exit "$SELFTEST_EXIT"
fi

set +e
python3 scripts/evidence/android/verify_selected_sources.py
SOURCECHECK_EXIT=$?
set -e
if [[ "$SOURCECHECK_EXIT" -ne 0 ]]; then
  echo "selected source verification failed" >&2
  exit "$SOURCECHECK_EXIT"
fi

yes | sdkmanager --licenses >/dev/null 2>&1 || true

# Determine literal carrier availability before installation. A successful catalog
# lookup that does not contain the fixed image is ABSENT; catalog/network/tooling
# failures remain FAIL and are never converted into an alternate-image fallback.
set +e
sdkmanager --list >"$IMAGE_CATALOG_LOG" 2>&1
image_catalog_exit=$?
set -e
if [[ "$image_catalog_exit" -ne 0 ]]; then
  echo "Android SDK catalog lookup failed; carrier availability is unknown" >&2
  exit 26
fi
if ! grep -Fq "$SYSTEM_IMAGE" "$IMAGE_CATALOG_LOG"; then
  STATE="ABSENT"
  echo "fixed Android emulator system image is absent: ${SYSTEM_IMAGE}" >&2
  exit 27
fi

sdkmanager --install 'platform-tools' 'emulator' "$SYSTEM_IMAGE"
if [[ ! -x "$EMULATOR_BIN" ]]; then
  echo "Android emulator binary absent after fixed sdkmanager install" >&2
  exit 20
fi

export ANDROID_AVD_HOME="${RUNNER_TEMP:-/tmp}/kaw-evidence-avd-${API_LEVEL}"
rm -rf "$ANDROID_AVD_HOME"
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
    echo "Android emulator terminated before adb registration" >&2
    exit 21
  fi
  if timeout 5s adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { found=1 } END { exit(found ? 0 : 1) }'; then
    CONNECT_STATE="PASS"
    break
  fi
  sleep 2
done
if [[ "$CONNECT_STATE" != "PASS" ]]; then
  echo "Android emulator did not register within the bounded timeout" >&2
  exit 22
fi

boot_deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
while [[ "$SECONDS" -lt "$boot_deadline" ]]; do
  if ! kill -0 "$EMULATOR_PID" >/dev/null 2>&1; then
    echo "Android emulator terminated before boot completed" >&2
    exit 23
  fi
  boot_value="$(timeout 5s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [[ "$boot_value" == "1" ]]; then
    BOOT_STATE="PASS"
    break
  fi
  sleep 2
done
if [[ "$BOOT_STATE" != "PASS" ]]; then
  echo "Android emulator did not boot within the bounded timeout" >&2
  exit 24
fi

set +e
timeout --signal=TERM "${TEST_TIMEOUT_SECONDS}s" "${TEST_COMMAND[@]}"
TEST_EXIT=$?
set -e
if [[ "$TEST_EXIT" -ne 0 ]]; then
  TEST_STATE="FAIL"
  echo "Android evidence instrumentation failed with exit ${TEST_EXIT}" >&2
  exit "$TEST_EXIT"
fi
TEST_STATE="PASS"

read -r REPORT_TESTS REPORT_FAILURES REPORT_SKIPPED < <(python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

tests = failures = skipped = 0
seen = set()
for path in Path("composeApp/build/outputs/androidTest-results").rglob("*.xml"):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
    for suite in suites:
        key = (str(path), suite.attrib.get("name", ""))
        if key in seen:
            continue
        seen.add(key)
        tests += int(suite.attrib.get("tests", "0"))
        failures += int(suite.attrib.get("failures", "0")) + int(suite.attrib.get("errors", "0"))
        skipped += int(suite.attrib.get("skipped", "0"))
print(tests, failures, skipped)
PY
)
if [[ "$REPORT_TESTS" -lt 6 || "$REPORT_FAILURES" -ne 0 ]]; then
  TEST_STATE="FAIL"
  echo "instrumentation report incomplete: tests=${REPORT_TESTS} failures=${REPORT_FAILURES}" >&2
  exit 25
fi
