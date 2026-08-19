#!/usr/bin/env python3
"""Validate KAW's public reference registry without importing private locators."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit, urlunsplit

REF_RE = re.compile(r"^REF-\d{4,}$")
REF_MENTION_RE = re.compile(r"\bREF-\d{4,}\b")
FORBIDDEN_PUBLIC_HOSTS = {"docs.google.com", "drive.google.com"}
PRIVATE_REPOSITORY_SLUGS = {
    "ed3c/skills-shared",
    "ed3c/ai-content-notes",
    "ed3c/ai-product-notes",
    "ed3c/bettor-arena",
    "ed3c/tech-implementation-atlas",
    "ed3c/runtime-env",
}
CREDENTIAL_QUERY_KEYS = {
    "access_token",
    "api_key",
    "apikey",
    "secret",
    "token",
    "password",
    "client_secret",
}
VERIFIED_FRESHNESS = {"CURRENT", "VERIFIED", "READ_BACK_VERIFIED"}


@dataclass
class RegistryResult:
    files: int = 0
    references: int = 0
    opaque_private_refs: set[str] = field(default_factory=set)
    errors: list[str] = field(default_factory=list)
    private_parity: str = "NOT_EXERCISED"

    @property
    def ok(self) -> bool:
        return not self.errors


def _walk(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from _walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk(child)


def _strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for child in value.values():
            yield from _strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from _strings(child)


def _normalized_url(url: str) -> str:
    parts = urlsplit(url)
    path = parts.path.rstrip("/") or "/"
    return urlunsplit((parts.scheme.lower(), parts.netloc.lower(), path, parts.query, ""))


def _has_revision_evidence(row: dict) -> bool:
    keys = {
        "revision",
        "revision_id",
        "commit_sha",
        "sha",
        "digest",
        "content_digest",
        "read_back_digest",
    }
    return any(row.get(key) not in (None, "", "UNKNOWN") for key in keys)


def _reference_rows(document) -> list[dict]:
    rows: list[dict] = []
    for node in _walk(document):
        ref_id = node.get("id") or node.get("ref_id")
        if isinstance(ref_id, str) and REF_RE.fullmatch(ref_id):
            if "url" in node or "external_id" in node or "title" in node:
                rows.append(node)
    return rows


def validate_registry(root: Path, private_snapshot: Path | None = None) -> RegistryResult:
    result = RegistryResult()
    paths = sorted(root.glob("reference-index.public*.json"))
    if not paths:
        result.errors.append(f"no public registry shards found under {root}")
        return result

    seen_ids: dict[str, Path] = {}
    seen_urls: dict[str, str] = {}
    all_defined_ids: set[str] = set()
    mentions: set[str] = set()

    for path in paths:
        result.files += 1
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            result.errors.append(f"{path}: invalid JSON: {exc}")
            continue

        for text in _strings(document):
            mentions.update(REF_MENTION_RE.findall(text))

        for row in _reference_rows(document):
            result.references += 1
            ref_id = row.get("id") or row.get("ref_id")
            all_defined_ids.add(ref_id)
            previous = seen_ids.get(ref_id)
            if previous is not None:
                result.errors.append(f"duplicate REF id {ref_id}: {previous} and {path}")
            else:
                seen_ids[ref_id] = path

            visibility = row.get("visibility", "PUBLIC")
            if visibility != "PUBLIC":
                result.errors.append(
                    f"{ref_id}: public registry row visibility must be PUBLIC, got {visibility!r}"
                )

            url = row.get("url")
            if url is not None:
                if not isinstance(url, str) or not url.startswith("https://"):
                    result.errors.append(f"{ref_id}: public URL must use HTTPS")
                    continue
                parts = urlsplit(url)
                host = (parts.hostname or "").lower()
                if host in FORBIDDEN_PUBLIC_HOSTS:
                    result.errors.append(
                        f"{ref_id}: private Google locator host {host} is forbidden in public registry"
                    )
                if host == "github.com":
                    slug = "/".join(parts.path.strip("/").split("/")[:2]).lower()
                    private_slugs = {item.lower() for item in PRIVATE_REPOSITORY_SLUGS}
                    if slug in private_slugs:
                        result.errors.append(f"{ref_id}: private GitHub repository URL leaked: {slug}")
                for key, _ in parse_qsl(parts.query, keep_blank_values=True):
                    if key.lower() in CREDENTIAL_QUERY_KEYS:
                        result.errors.append(
                            f"{ref_id}: credential-like query key {key!r} is forbidden"
                        )

                normalized = _normalized_url(url)
                previous_ref = seen_urls.get(normalized)
                if previous_ref is not None and previous_ref != ref_id:
                    result.errors.append(
                        f"duplicate public URL identity {normalized}: {previous_ref} and {ref_id}"
                    )
                else:
                    seen_urls[normalized] = ref_id

            freshness = row.get("freshness") or row.get("state")
            if freshness in VERIFIED_FRESHNESS and not _has_revision_evidence(row):
                result.errors.append(
                    f"{ref_id}: {freshness} requires revision/digest/read-back evidence"
                )

    opaque = {
        ref
        for ref in mentions
        if ref not in all_defined_ids and int(ref.split("-")[1]) >= 1000
    }
    result.opaque_private_refs = opaque

    if private_snapshot is not None:
        try:
            snapshot = json.loads(private_snapshot.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            result.errors.append(f"private snapshot invalid: {exc}")
        else:
            allowed = set(snapshot.get("ref_ids", []))
            missing = sorted(opaque - allowed)
            if missing:
                result.errors.append(
                    "opaque private REF ids missing from sanitized snapshot: " + ", ".join(missing)
                )
            result.private_parity = "PASS" if not missing else "FAIL"

    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("docs/workspace"))
    parser.add_argument("--private-ref-snapshot", type=Path)
    args = parser.parse_args(argv)

    result = validate_registry(args.root, args.private_ref_snapshot)
    payload = {
        "status": "PASS" if result.ok else "FAIL",
        "files": result.files,
        "references": result.references,
        "opaque_private_ref_count": len(result.opaque_private_refs),
        "private_parity": result.private_parity,
        "errors": result.errors,
    }
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0 if result.ok else 1


if __name__ == "__main__":
    sys.exit(main())
