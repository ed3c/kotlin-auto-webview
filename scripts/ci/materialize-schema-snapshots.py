#!/usr/bin/env python3
"""Materialize tracked *.db.gz SQLDelight snapshots before migration verification."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import os
import sys
from pathlib import Path


def _parse_manifest(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        if not sep or not key or not value:
            raise ValueError(f"invalid manifest line in {path}: {raw!r}")
        values[key] = value
    required = {"compressed_sha256", "expanded_sha256", "expanded_size"}
    missing = required - values.keys()
    if missing:
        raise ValueError(f"{path}: missing keys {sorted(missing)}")
    return values


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def materialize_snapshot(gzip_path: Path) -> Path:
    target = gzip_path.with_suffix("")
    manifest_path = target.with_suffix(target.suffix + ".sha256")
    if not manifest_path.exists():
        raise FileNotFoundError(f"missing snapshot manifest: {manifest_path}")

    manifest = _parse_manifest(manifest_path)
    compressed = gzip_path.read_bytes()
    if _sha256(compressed) != manifest["compressed_sha256"]:
        raise ValueError(f"compressed digest mismatch: {gzip_path}")

    expanded = gzip.decompress(compressed)
    if len(expanded) != int(manifest["expanded_size"]):
        raise ValueError(
            f"expanded size mismatch for {gzip_path}: "
            f"expected {manifest['expanded_size']}, got {len(expanded)}"
        )
    if _sha256(expanded) != manifest["expanded_sha256"]:
        raise ValueError(f"expanded digest mismatch: {gzip_path}")

    if target.exists() and target.read_bytes() == expanded:
        return target

    temp = target.with_name(f".{target.name}.tmp")
    temp.write_bytes(expanded)
    os.replace(temp, target)
    return target


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("migrations", type=Path)
    args = parser.parse_args(argv)

    gzip_paths = sorted(args.migrations.glob("*.db.gz"))
    for gzip_path in gzip_paths:
        try:
            target = materialize_snapshot(gzip_path)
        except Exception as exc:  # fail closed at build boundary
            print(f"schema snapshot materialization failed: {exc}", file=sys.stderr)
            return 1
        print(f"materialized schema snapshot: {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
