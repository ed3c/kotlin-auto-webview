#!/usr/bin/env python3
"""Fail-closed validator for the deterministic Google Docs transport ceiling."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

SHA40 = re.compile(r"^[0-9a-f]{40}$")
SECRET_MARKERS = (
    "access_token",
    "refresh_token",
    "client_secret",
    "authorization: bearer",
    "ya29.",
)
EXPECTED_TOP_LEVEL = {
    "schema",
    "program",
    "implementation_issue",
    "delivery_issue",
    "stage_state",
    "git_parent",
    "w3_contracts",
    "implementation",
    "allowed_scopes",
    "evidence",
    "forbidden_claims",
    "secrets",
}
EXPECTED_FORBIDDEN = {
    "LIVE_GOOGLE_PROJECTION_ACCOUNT_PASS",
    "GOOGLE_ACCOUNT_OWNERSHIP",
    "CONTENT_RIGHTS_ADMITTED",
    "GOOGLE_SHEETS_CONDITIONAL_WRITE_PASS",
    "PRODUCTION_SYNC_PASS",
    "USER_OUTCOME_PASS",
    "MERGE_RELEASE_AUTHORIZED",
}
EXPECTED_SCOPES = {
    "https://www.googleapis.com/auth/documents",
    "https://www.googleapis.com/auth/drive.file",
    "https://www.googleapis.com/auth/drive",
}


class TransportLimitError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise TransportLimitError(message)


def exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    require(set(value) == expected, f"{label} keys mismatch")


def walk_strings(value: Any):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for child in value.values():
            yield from walk_strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk_strings(child)


def validate(document: dict[str, Any]) -> None:
    exact_keys(document, EXPECTED_TOP_LEVEL, "transport limits")
    require(document["schema"] == "kaw.workspace.google-docs-transport-limits.v1", "schema drift")
    require(document["implementation_issue"] == 182, "implementation owner drift")
    require(document["delivery_issue"] == 166, "delivery owner drift")
    require(document["stage_state"] == "L3_GOOGLE_DOCS_TRANSPORT_CODE_READY", "stage overclaim or drift")

    parent = document["git_parent"]
    exact_keys(parent, {"pull_request", "head_sha", "branch"}, "git parent")
    require(parent["pull_request"] == 160, "W3 parent PR drift")
    require(parent["head_sha"] == "95754e2a7ea6a09da030da3803313fe49641b677", "W3 head drift")
    require(SHA40.fullmatch(parent["head_sha"]) is not None, "W3 head is not a SHA")
    require(parent["branch"] == "feat/workspace-google-projection", "W3 branch drift")

    contracts = document["w3_contracts"]
    require(isinstance(contracts, list) and len(contracts) == 2, "W3 contract denominator changed")
    expected_contracts = {
        "composeApp/src/commonMain/kotlin/dev/ed3c/autowebview/workspace/google/GoogleProjectionContracts.kt":
            "02485d957d4b3432ba4e3310d3949c9ae6a71b7f",
        "composeApp/src/commonMain/kotlin/dev/ed3c/autowebview/workspace/google/GoogleProjectionSaga.kt":
            "37e91707786273af29b29208e2e42db9f1f13dac",
    }
    actual_contracts = {}
    for contract in contracts:
        exact_keys(contract, {"path", "blob_sha", "modified"}, "W3 contract")
        require(contract["modified"] is False, "W3 contract or saga was modified")
        require(SHA40.fullmatch(contract["blob_sha"]) is not None, "W3 blob is not exact")
        actual_contracts[contract["path"]] = contract["blob_sha"]
    require(actual_contracts == expected_contracts, "W3 contract identity drift")

    implementation = document["implementation"]
    exact_keys(
        implementation,
        {
            "transport_class",
            "executor_class",
            "capability_class",
            "api_origin",
            "oauth_ui_location",
            "docs_revision_precondition",
            "second_pre_write_read",
            "authenticated_read_back_required_by_saga",
            "rendered_digest_algorithm",
            "foreign_target_overwrite",
            "corrupt_managed_target_overwrite",
            "sheets_write_state",
        },
        "implementation",
    )
    require(implementation["transport_class"] == "GoogleDocsProjectionTransport", "transport class drift")
    require(implementation["executor_class"] == "KtorGoogleDocsApiExecutor", "executor class drift")
    require(implementation["api_origin"] == "https://docs.googleapis.com", "Google API origin widened")
    require(
        implementation["oauth_ui_location"] == "EXTERNAL_SYSTEM_BROWSER_OR_NATIVE_CREDENTIAL_MANAGER",
        "OAuth moved into app WebView",
    )
    require(
        implementation["docs_revision_precondition"] == "writeControl.requiredRevisionId",
        "Docs revision control removed",
    )
    require(implementation["second_pre_write_read"] is True, "second pre-write read removed")
    require(
        implementation["authenticated_read_back_required_by_saga"] is True,
        "read-back requirement removed",
    )
    require(implementation["rendered_digest_algorithm"] == "sha256", "digest algorithm drift")
    require(implementation["foreign_target_overwrite"] == "BLOCKED", "foreign target overwrite admitted")
    require(
        implementation["corrupt_managed_target_overwrite"] == "BLOCKED",
        "corrupt target overwrite admitted",
    )
    require(
        implementation["sheets_write_state"] == "BLOCKED_NO_PROVABLE_REVISION_PRECONDITION",
        "Sheets race was promoted",
    )

    require(set(document["allowed_scopes"]) == EXPECTED_SCOPES, "scope set widened or narrowed")
    require(len(document["allowed_scopes"]) == len(EXPECTED_SCOPES), "duplicate scope")

    evidence = document["evidence"]
    exact_keys(
        evidence,
        {
            "deterministic_kotlin_tests",
            "mutation_tests",
            "live_google_account",
            "live_docs_file",
            "live_sheet_file",
            "oauth_consent",
            "organization_dlp",
            "content_rights",
            "maximum_claim",
        },
        "evidence",
    )
    require(evidence["deterministic_kotlin_tests"] == "REQUIRED", "Kotlin test gate removed")
    require(evidence["mutation_tests"] == "REQUIRED", "mutation gate removed")
    require(evidence["live_google_account"] == "NOT_EXERCISED", "live account falsely promoted")
    require(evidence["live_docs_file"] == "NOT_EXERCISED", "live Docs file falsely promoted")
    require(evidence["live_sheet_file"] == "NOT_EXERCISED", "live Sheet falsely promoted")
    require(evidence["oauth_consent"] == "EXTERNAL_AUTHORITY_REQUIRED", "OAuth authority disappeared")
    require(evidence["organization_dlp"] == "EXTERNAL_AUTHORITY_REQUIRED", "DLP authority disappeared")
    require(evidence["content_rights"] == "EXTERNAL_AUTHORITY_REQUIRED", "content-rights authority disappeared")
    require(
        evidence["maximum_claim"] == "DETERMINISTIC_GOOGLE_DOCS_TRANSPORT_IMPLEMENTED",
        "maximum claim widened",
    )

    require(set(document["forbidden_claims"]) == EXPECTED_FORBIDDEN, "forbidden-claim denominator changed")
    require(len(document["forbidden_claims"]) == len(EXPECTED_FORBIDDEN), "duplicate forbidden claim")

    secrets = document["secrets"]
    exact_keys(
        secrets,
        {
            "access_token_in_source",
            "refresh_token_in_source",
            "client_secret_in_source",
            "account_email_in_receipt",
            "private_file_id_in_receipt",
            "response_body_in_receipt",
        },
        "secrets",
    )
    require(all(value is False for value in secrets.values()), "secret or private material admitted")

    serialized = json.dumps(document, sort_keys=True).lower()
    for marker in SECRET_MARKERS:
        if marker in serialized and marker not in {"access_token", "refresh_token", "client_secret"}:
            raise TransportLimitError(f"credential-like material found: {marker}")


def load(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    require(isinstance(value, dict), "transport limits must be an object")
    return value


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path)
    args = parser.parse_args(argv)
    try:
        validate(load(args.path))
    except (OSError, json.JSONDecodeError, TransportLimitError) as exc:
        print(f"Google transport limits: FAIL: {exc}", file=sys.stderr)
        return 1
    print("Google transport limits: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
