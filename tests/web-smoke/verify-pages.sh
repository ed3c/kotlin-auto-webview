#!/usr/bin/env bash
set -euo pipefail

page_url=${1:?usage: verify-pages.sh <page-url> <wasm-file>}
wasm_file=${2:?usage: verify-pages.sh <page-url> <wasm-file>}

case "$page_url" in
  https://*) ;;
  *) echo "FAIL: deployment URL must use HTTPS" >&2; exit 10 ;;
esac

case "$wasm_file" in
  */*|*..*) echo "FAIL: wasm file must be a basename" >&2; exit 11 ;;
  *.wasm) ;;
  *) echo "FAIL: expected a .wasm artifact basename" >&2; exit 12 ;;
esac

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

root_headers="$workdir/root.headers"
root_body="$workdir/root.html"
wasm_headers="$workdir/wasm.headers"
wasm_body="$workdir/module.wasm"

curl --fail --silent --show-error --location \
  --dump-header "$root_headers" \
  --output "$root_body" \
  "$page_url"

if ! grep -qi '<html' "$root_body"; then
  echo "FAIL: deployed root did not return HTML" >&2
  exit 20
fi

base_url=${page_url%/}/
wasm_url="${base_url}${wasm_file}"
curl --fail --silent --show-error --location \
  --dump-header "$wasm_headers" \
  --output "$wasm_body" \
  "$wasm_url"

if ! grep -Eqi '^content-type:[[:space:]]*application/wasm([;[:space:]]|$)' "$wasm_headers"; then
  echo "FAIL: deployed Wasm does not advertise application/wasm" >&2
  sed -n '/^[Cc]ontent-[Tt]ype:/p' "$wasm_headers" >&2 || true
  exit 21
fi

if [[ ! -s "$wasm_body" ]]; then
  echo "FAIL: deployed Wasm body is empty" >&2
  exit 22
fi

root_cache=$(grep -Ei '^cache-control:' "$root_headers" | tail -n1 | tr -d '\r' || true)
wasm_cache=$(grep -Ei '^cache-control:' "$wasm_headers" | tail -n1 | tr -d '\r' || true)

printf 'PASS https_root=%s\n' "$page_url"
printf 'PASS wasm_url=%s\n' "$wasm_url"
printf 'PASS wasm_content_type=application/wasm\n'
printf 'OBSERVED root_%s\n' "${root_cache:-cache-control:ABSENT}"
printf 'OBSERVED wasm_%s\n' "${wasm_cache:-cache-control:ABSENT}"

if [[ "${REQUIRE_IMMUTABLE_CACHE:-false}" == "true" ]]; then
  if ! grep -Eqi '^cache-control:.*immutable' "$wasm_headers"; then
    echo "FAIL: immutable cache policy required but not observed" >&2
    exit 23
  fi
  echo 'PASS wasm_cache_policy=immutable'
else
  echo 'NOT_EXERCISED immutable_cache_gate=false'
fi
