#!/usr/bin/env bash
set -uo pipefail

if [[ $# -eq 0 ]]; then
  echo "usage: $0 <gradle-task> [<gradle-task> ...]" >&2
  exit 64
fi

safe_name="$(printf '%s' "$*" | tr -cs '[:alnum:]_.-' '_')"
log_dir="${RUNNER_TEMP:-/tmp}/kotlin-auto-webview-gradle"
log_file="$log_dir/${safe_name}.log"
mkdir -p "$log_dir"

set +e
gradle "$@" --console=plain --stacktrace 2>&1 | tee "$log_file"
status=${PIPESTATUS[0]}
set -e

if [[ $status -ne 0 ]]; then
  python3 - "$log_file" "$PWD" <<'PY'
from __future__ import annotations

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

log_path = pathlib.Path(sys.argv[1])
repo_root = pathlib.Path(sys.argv[2])
lines = log_path.read_text(errors="replace").splitlines()
pattern = re.compile(
    r"(^e: |^error: |Unresolved reference|Cannot access class|No matching variant|"
    r"Could not resolve|Could not find|Execution failed for task|What went wrong|"
    r"Kotlin source sets|partially resolved|Compilation error|Incompatible because|"
    r"FAILURE: Build failed|Caused by:)",
    re.IGNORECASE,
)
interesting: set[int] = set()
for index, line in enumerate(lines):
    if pattern.search(line):
        interesting.update(range(max(0, index - 3), min(len(lines), index + 5)))
summary = [lines[index] for index in sorted(interesting)]

failed_tests: list[str] = []
for result_file in repo_root.glob("**/build/test-results/**/*.xml"):
    try:
        root = ET.parse(result_file).getroot()
    except ET.ParseError:
        continue
    for case in root.iter("testcase"):
        failures = list(case.findall("failure")) + list(case.findall("error"))
        if not failures:
            continue
        name = f"{case.attrib.get('classname', '')}.{case.attrib.get('name', '')}".strip(".")
        detail = failures[0].attrib.get("message") or (failures[0].text or "")
        failed_tests.append(f"FAILED TEST: {name}\n{detail[:2000]}")

selected = "\n".join(
    [*failed_tests, "", *summary, "", "--- tail ---", *lines[max(0, len(lines) - 120):]]
)
if len(selected) > 30_000:
    selected = selected[:18_000] + "\n... diagnostic output truncated ...\n" + selected[-10_000:]
chunks: list[str] = []
while selected:
    chunks.append(selected[:3_500])
    selected = selected[3_500:]
for index, chunk in enumerate(chunks, start=1):
    escaped = chunk.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::error title=Gradle failure {index}/{len(chunks)}::{escaped}")
PY
fi

exit "$status"
