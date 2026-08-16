#!/usr/bin/env python3
"""Assert that every committed SQLDelight schema snapshot is a readable SQLite image.

A snapshot is the schema a migration starts *from*. When one is unreadable, SQLDelight infers an
empty schema and then reports every statement in the corresponding `.sqm` as referring to something
that does not exist:

    No table found with name semantic_cache_record
    No column found with name last_accessed_at_epoch_ms
    ... one error per statement

Every message points at the migration, so the migration is what gets edited — three times, in
issue #25, while the migration was already correct. A file the schema can never satisfy looks
exactly like a schema that does not match.

This check runs before the generator so a corrupt snapshot reports itself as a corrupt file.
"""

from __future__ import annotations

import argparse
import pathlib
import sqlite3
import struct
import sys
import tempfile

DEFAULT_MIGRATIONS = pathlib.Path("composeApp/src/commonMain/sqldelight/migrations")

EXIT_OK = 0
EXIT_PROBLEM = 1
EXIT_USAGE = 64
EXIT_ABSENT = 65


HEADER_BYTES = 100
SQLITE_MAGIC = b"SQLite format 3\x00"


def truncation_problem(path: pathlib.Path) -> str | None:
    """Compare the size the header declares with the size on disk.

    `PRAGMA integrity_check` is not sufficient on its own: it happily returns `ok` for a small
    database whose trailing page was cut off, because it never needs to read that page. The
    original defect — 2994 bytes in a file whose header declared 6 x 512 — is exactly that shape,
    so the size comparison is a gate here, not a hint.

    The in-header page count is only authoritative when the change counter matches the
    version-valid-for number (SQLite file format, offsets 24 and 92). That condition is checked
    first, so a legitimate database with a stale count is never failed on this basis.
    """
    try:
        header = path.read_bytes()[:HEADER_BYTES]
    except OSError as error:
        return f"cannot be read ({error})"
    if len(header) < HEADER_BYTES or not header.startswith(SQLITE_MAGIC):
        return "is not a SQLite image (header missing or truncated)"

    page_size = struct.unpack(">H", header[16:18])[0]
    if page_size == 1:
        page_size = 65_536
    page_count = struct.unpack(">I", header[28:32])[0]
    change_counter = struct.unpack(">I", header[24:28])[0]
    version_valid_for = struct.unpack(">I", header[92:96])[0]

    if page_count == 0 or change_counter != version_valid_for:
        return None

    declared = page_size * page_count
    actual = path.stat().st_size
    if declared != actual:
        return (
            f"is truncated or padded: header declares {page_count} x {page_size} = "
            f"{declared} bytes, file holds {actual}"
        )
    return None


def check_snapshot(path: pathlib.Path) -> list[str]:
    """Return the problems found in one snapshot; empty means it is usable."""
    problems: list[str] = []

    try:
        expected_version = int(path.stem)
    except ValueError:
        problems.append(f"{path}: filename is not a schema version number")
        return problems

    # Checked before opening: a truncated image is the failure that otherwise arrives disguised as
    # a schema disagreement, and it is the one SQLite itself may not notice.
    truncation = truncation_problem(path)
    if truncation:
        problems.append(f"{path}: {truncation}")
        return problems

    try:
        connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    except sqlite3.Error as error:
        problems.append(f"{path}: cannot be opened as SQLite ({error})")
        return problems

    try:
        with connection:
            integrity = connection.execute("PRAGMA integrity_check").fetchone()
            if integrity is None or integrity[0] != "ok":
                detail = "no result" if integrity is None else integrity[0]
                problems.append(f"{path}: integrity_check reported {detail}")
            actual_version = connection.execute("PRAGMA user_version").fetchone()[0]
            if actual_version != expected_version:
                problems.append(
                    f"{path}: user_version is {actual_version}, expected {expected_version}"
                )
    except sqlite3.DatabaseError as error:
        problems.append(f"{path}: is not a readable SQLite image ({error})")
    finally:
        connection.close()

    return problems


def check_directory(migrations: pathlib.Path) -> tuple[int, list[str]]:
    if not migrations.is_dir():
        return EXIT_ABSENT, [f"{migrations}: migrations directory does not exist"]

    snapshots = sorted(migrations.glob("*.db"))
    migration_scripts = sorted(migrations.glob("*.sqm"))

    if not snapshots and not migration_scripts:
        return EXIT_ABSENT, [f"{migrations}: contains neither snapshots nor migrations"]

    problems: list[str] = []

    # A migration whose starting schema was never committed cannot be verified at all.
    snapshot_versions = {path.stem for path in snapshots}
    for script in migration_scripts:
        if script.stem not in snapshot_versions:
            problems.append(f"{script}: has no matching {script.stem}.db snapshot")

    for snapshot in snapshots:
        problems.extend(check_snapshot(snapshot))

    return (EXIT_PROBLEM if problems else EXIT_OK), problems


def selftest() -> int:
    failures = 0

    def expect(label: str, expected: int, migrations: pathlib.Path) -> None:
        nonlocal failures
        status, problems = check_directory(migrations)
        if status != expected:
            print(
                f"selftest FAIL: {label} expected exit {expected}, got {status}",
                file=sys.stderr,
            )
            for problem in problems:
                print(f"  {problem}", file=sys.stderr)
            failures += 1
            return
        print(f"selftest ok: {label}")

    def write_snapshot(directory: pathlib.Path, version: int) -> pathlib.Path:
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / f"{version}.db"
        connection = sqlite3.connect(path)
        connection.executescript(
            "CREATE TABLE fixture (id TEXT NOT NULL PRIMARY KEY);"
        )
        connection.execute(f"PRAGMA user_version = {version}")
        connection.commit()
        connection.close()
        return path

    with tempfile.TemporaryDirectory() as workspace:
        root = pathlib.Path(workspace)

        healthy = root / "healthy"
        write_snapshot(healthy, 1)
        (healthy / "1.sqm").write_text("ALTER TABLE fixture ADD COLUMN extra TEXT;\n")
        expect("a valid snapshot passes", EXIT_OK, healthy)

        # The defect this check exists for: a snapshot truncated mid-write.
        truncated = root / "truncated"
        snapshot = write_snapshot(truncated, 1)
        (truncated / "1.sqm").write_text("ALTER TABLE fixture ADD COLUMN extra TEXT;\n")
        data = snapshot.read_bytes()
        snapshot.write_bytes(data[: len(data) - 200])
        expect("a truncated snapshot is rejected", EXIT_PROBLEM, truncated)

        mislabelled = root / "mislabelled"
        write_snapshot(mislabelled, 3)
        (mislabelled / "3.db").rename(mislabelled / "1.db")
        expect("a snapshot whose user_version disagrees is rejected", EXIT_PROBLEM, mislabelled)

        orphan = root / "orphan"
        orphan.mkdir()
        (orphan / "1.sqm").write_text("ALTER TABLE fixture ADD COLUMN extra TEXT;\n")
        expect("a migration with no snapshot is rejected", EXIT_PROBLEM, orphan)

        # Absence must not read as success: an unchecked directory is not a clean one.
        expect("a missing directory is not reported as clean", EXIT_ABSENT, root / "absent")

    if failures:
        print(f"selftest: {failures} check(s) failed", file=sys.stderr)
        return EXIT_PROBLEM
    print("selftest: all checks passed")
    return EXIT_OK


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("migrations", nargs="?", default=str(DEFAULT_MIGRATIONS))
    parser.add_argument("--selftest", action="store_true", help="prove this check goes red")
    arguments = parser.parse_args(argv)

    if arguments.selftest:
        return selftest()

    status, problems = check_directory(pathlib.Path(arguments.migrations))
    for problem in problems:
        print(problem, file=sys.stderr)
    if status == EXIT_OK:
        print(f"schema-snapshot check: {arguments.migrations} snapshots are readable")
    return status


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        sys.exit(EXIT_USAGE)
