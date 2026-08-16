#!/usr/bin/env python3
"""Fail when a tracked file contains a NUL byte without being declared binary.

A stray NUL turns a source file binary as far as git is concerned. Nothing breaks, nothing fails to
compile, and no test notices — the only symptom is that every diff of that file reads
`Bin 16425 -> 16814 bytes` instead of showing the change, so the file silently stops being
reviewable.

That is what happened to `McpProductionAuthentication.kt`: a separator written as a raw NUL instead
of an escape. The compiled behaviour was identical and correct; the reviewability was gone from the
moment it landed, and the only signal it ever produced was the `Bin` marker in a commit stat.

The allowlist is deliberately fail-closed: a genuinely binary file must be declared here, so adding
one is a decision someone makes rather than a side effect nobody sees.
"""

from __future__ import annotations

import argparse
import fnmatch
import pathlib
import subprocess
import sys
import tempfile

# Tracked paths that are legitimately binary. Keep this list short and specific: a broad glob would
# quietly re-admit the class of defect this check exists to catch.
DECLARED_BINARY = (
    "composeApp/src/commonMain/sqldelight/migrations/*.db",
)

EXIT_OK = 0
EXIT_PROBLEM = 1
EXIT_USAGE = 64
EXIT_ABSENT = 65


def is_declared_binary(path: str) -> bool:
    return any(fnmatch.fnmatch(path, pattern) for pattern in DECLARED_BINARY)


def tracked_files(repository: pathlib.Path) -> list[str] | None:
    result = subprocess.run(
        ["git", "-C", str(repository), "ls-files", "-z"],
        capture_output=True,
    )
    if result.returncode != 0:
        return None
    return [name for name in result.stdout.decode("utf-8", "surrogateescape").split("\0") if name]


def offending_files(repository: pathlib.Path) -> tuple[int, list[str]]:
    names = tracked_files(repository)
    if names is None:
        return EXIT_ABSENT, [f'{repository}: not a git repository']
    if not names:
        # An empty tracked set means the check inspected nothing; reporting that as clean would be
        # the same hollow pass this repository keeps guarding against.
        return EXIT_ABSENT, [f"{repository}: no tracked files to inspect"]

    problems: list[str] = []
    for name in names:
        path = repository / name
        if not path.is_file():
            continue  # a submodule or a path removed from the working tree
        try:
            data = path.read_bytes()
        except OSError as error:
            problems.append(f"{name}: cannot be read ({error})")
            continue
        offset = data.find(b"\x00")
        if offset < 0:
            continue
        if is_declared_binary(name):
            continue
        context = data[max(0, offset - 40):offset].decode("utf-8", "replace").splitlines()
        trailing = context[-1] if context else ""
        problems.append(
            f"{name}: NUL byte at offset {offset}, after {trailing!r} — "
            "write it as an escape, or declare the file binary in DECLARED_BINARY"
        )

    return (EXIT_PROBLEM if problems else EXIT_OK), problems


def selftest() -> int:
    failures = 0

    def expect(label: str, expected: int, repository: pathlib.Path, *needles: str) -> None:
        nonlocal failures
        status, problems = offending_files(repository)
        rendered = "\n".join(problems)
        if status != expected:
            print(f"selftest FAIL: {label} expected exit {expected}, got {status}", file=sys.stderr)
            print(rendered, file=sys.stderr)
            failures += 1
            return
        for needle in needles:
            if needle not in rendered:
                print(f"selftest FAIL: {label} output does not name {needle}", file=sys.stderr)
                print(rendered, file=sys.stderr)
                failures += 1
                return
        print(f"selftest ok: {label}")

    def repository(root: pathlib.Path, files: dict[str, bytes]) -> pathlib.Path:
        root.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "-C", str(root), "init", "-q"], check=True)
        for name, content in files.items():
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
        subprocess.run(["git", "-C", str(root), "add", "-A"], check=True)
        return root

    with tempfile.TemporaryDirectory() as workspace:
        base = pathlib.Path(workspace)

        expect(
            "a clean tree passes",
            EXIT_OK,
            repository(base / "clean", {"Source.kt": b'val separator = "a\\u0000b"\n'}),
        )

        # The exact defect this check exists for: a separator written as a raw byte.
        expect(
            "a NUL in a source file is reported with its offset",
            EXIT_PROBLEM,
            repository(base / "raw-nul", {"Source.kt": b'val separator = "a\x00b"\n'}),
            "Source.kt",
            "NUL byte at offset",
        )

        # A declared binary stays allowed, or the check would be unusable here.
        expect(
            "a declared binary file is allowed",
            EXIT_OK,
            repository(
                base / "declared",
                {"composeApp/src/commonMain/sqldelight/migrations/1.db": b"SQLite\x00\x00"},
            ),
        )

        # An undeclared binary must be a decision, not a silent addition.
        expect(
            "an undeclared binary file is reported",
            EXIT_PROBLEM,
            repository(base / "undeclared", {"assets/logo.png": b"\x89PNG\x00\x00"}),
            "assets/logo.png",
        )

        expect("a non-repository is not reported as clean", EXIT_ABSENT, base / "absent", "not a git repository")

    if failures:
        print(f"selftest: {failures} check(s) failed", file=sys.stderr)
        return EXIT_PROBLEM
    print("selftest: all checks passed")
    return EXIT_OK


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("repository", nargs="?", default=".")
    parser.add_argument("--selftest", action="store_true", help="prove this check goes red")
    arguments = parser.parse_args(argv)

    if arguments.selftest:
        return selftest()

    status, problems = offending_files(pathlib.Path(arguments.repository))
    for problem in problems:
        print(problem, file=sys.stderr)
    if status == EXIT_OK:
        print("text-source check: no tracked file carries an undeclared NUL byte")
    return status


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        sys.exit(EXIT_USAGE)
