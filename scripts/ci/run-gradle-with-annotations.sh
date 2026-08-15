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
  python3 - "$log_file" <<'PY'
from __future__ import annotations

import pathlib
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text(errors="replace")
lines = text.splitlines()

start = max(0, len(lines) - 220)
for index, line in enumerate(lines):
    if line.startswith("FAILURE: Build failed"):
        start = max(0, index - 80)
        break
selected = "\n".join(lines[start:])
if len(selected) > 24_000:
    selected = selected[-24_000:]

chunks: list[str] = []
while selected:
    chunks.append(selected[:3_500])
    selected = selected[3_500:]

for index, chunk in enumerate(chunks, start=1):
    escaped = (
        chunk.replace("%", "%25")
        .replace("\r", "%0D")
        .replace("\n", "%0A")
    )
    print(f"::error title=Gradle failure {index}/{len(chunks)}::{escaped}")
PY
fi

exit "$status"
