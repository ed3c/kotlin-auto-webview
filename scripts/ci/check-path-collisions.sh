#!/usr/bin/env bash
#
# Fail when two tracked paths differ only by case.
#
# Such a pair is harmless on the case-sensitive filesystem CI runs on — both paths exist and every
# job stays green — and destructive on the case-insensitive filesystem a macOS or Windows
# contributor checks out onto, where the second path silently overwrites the first. The defect is
# therefore invisible exactly where the repository is verified, which is why it needs its own gate
# rather than a convention.
#
# Directory components are checked too: `docs/Release/a.md` and `docs/release/b.md` do not collide
# as files but merge into one directory on checkout, losing whichever arrives second.
set -uo pipefail

usage() {
  cat >&2 <<'USAGE'
usage: check-path-collisions.sh [--selftest] [<repository>]

Fails when two tracked paths differ only by case, including directory components.

  --selftest   plant collisions in throwaway repositories, prove this check reports them,
               and prove a clean tree still passes
  <repository> repository to inspect (default: current directory)
USAGE
}

# Emit one line per collision group: "<lowercased-path> <variant> <variant> ..."
find_collisions() {
  git -C "$1" ls-files | awk '
    {
      prefix = ""
      parts = split($0, part, "/")
      for (i = 1; i <= parts; i++) {
        prefix = (i == 1) ? part[i] : prefix "/" part[i]
        key = tolower(prefix)
        # Count each distinct spelling of a path once, however many files sit under it.
        if (!((key, prefix) in seen)) {
          seen[key, prefix] = 1
          variants[key] = (key in variants) ? variants[key] " " prefix : prefix
          distinct[key]++
        }
      }
    }
    END {
      for (key in distinct) {
        if (distinct[key] > 1) print key " " variants[key]
      }
    }
  ' | sort
}

report() {
  local repository="$1" collisions="" group lowered paths path

  # An absent or non-git target must not look like a clean tree. Reporting "no collisions" for a
  # directory that was never inspected is the same hollow PASS this gate exists to prevent.
  if ! git -C "$repository" rev-parse --git-dir >/dev/null 2>&1; then
    printf 'path-collision check: "%s" is not a git repository\n' "$repository" >&2
    return 65
  fi

  collisions="$(find_collisions "$repository")"

  if [[ -z "$collisions" ]]; then
    echo "path-collision check: no tracked paths differ only by case"
    return 0
  fi

  while IFS= read -r group; do
    lowered="${group%% *}"
    paths="${group#* }"
    printf 'case collision on "%s":\n' "$lowered" >&2
    for path in $paths; do
      printf '  %s\n' "$path" >&2
    done
    if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
      printf '::error title=Case-colliding paths::%s collides with %s\n' \
        "${paths%% *}" "${paths#* }"
    fi
  done <<<"$collisions"

  cat >&2 <<'REMEDY'

These paths are distinct on a case-sensitive filesystem and the same path on a case-insensitive
one. Rename one of them so the tracked spellings differ by more than case.
REMEDY
  return 1
}

# --- self test -------------------------------------------------------------------------------
#
# A case-insensitive filesystem cannot hold both spellings on disk, so the fixtures are written
# straight into the index. That is also the only way to reproduce the original defect locally.

selftest_repository() {
  local repository="$1" blob
  shift
  mkdir -p "$repository"
  git -C "$repository" init -q
  blob="$(printf 'fixture\n' | git -C "$repository" hash-object -w --stdin)"
  for path in "$@"; do
    git -C "$repository" update-index --add --cacheinfo "100644,$blob,$path"
  done
}

# Script-scoped so the EXIT trap can still see it once selftest() has returned.
SELFTEST_WORKSPACE=""
cleanup_selftest_workspace() {
  [[ -n "$SELFTEST_WORKSPACE" ]] && rm -rf "$SELFTEST_WORKSPACE"
}

selftest() {
  local workspace failures=0 output status
  workspace="$(mktemp -d "${TMPDIR:-/tmp}/path-collision-selftest.XXXXXX")" || {
    echo "selftest: could not create a temporary workspace" >&2
    return 1
  }
  SELFTEST_WORKSPACE="$workspace"
  trap cleanup_selftest_workspace EXIT

  expect() {
    local label="$1" expected_status="$2" repository="$3"
    shift 3
    output="$(report "$repository" 2>&1)"
    status=$?
    if [[ $status -ne $expected_status ]]; then
      printf 'selftest FAIL: %s expected exit %d, got %d\n' "$label" "$expected_status" "$status" >&2
      printf '%s\n' "$output" >&2
      failures=$((failures + 1))
      return
    fi
    for needle in "$@"; do
      if [[ "$output" != *"$needle"* ]]; then
        printf 'selftest FAIL: %s output does not name %s\n' "$label" "$needle" >&2
        printf '%s\n' "$output" >&2
        failures=$((failures + 1))
        return
      fi
    done
    printf 'selftest ok: %s\n' "$label"
  }

  # The exact pair this check exists because of.
  selftest_repository "$workspace/files" docs/release/WEB.md docs/release/web.md
  expect "colliding files are reported by name" 1 "$workspace/files" \
    docs/release/WEB.md docs/release/web.md

  # Distinct files whose directories merge on checkout.
  selftest_repository "$workspace/directories" a/Release/one.md a/release/two.md
  expect "colliding directories are reported" 1 "$workspace/directories" a/Release a/release

  # A tree that is fine must stay fine, or the gate is just noise.
  selftest_repository "$workspace/clean" docs/release/WEB.md docs/release/ANDROID.md
  expect "a clean tree passes" 0 "$workspace/clean" "no tracked paths differ only by case"

  # An uninspectable target must not be reported as clean. Without this case an earlier version of
  # this very selftest passed against directories that were never created.
  expect "a non-repository is not reported as clean" 65 "$workspace/absent" "not a git repository"

  if [[ $failures -ne 0 ]]; then
    printf 'selftest: %d check(s) failed\n' "$failures" >&2
    return 1
  fi
  echo "selftest: all checks passed"
  return 0
}

main() {
  case "${1:-}" in
    --selftest)
      selftest
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    -*)
      usage
      exit 64
      ;;
    *)
      report "${1:-.}"
      ;;
  esac
}

main "$@"
