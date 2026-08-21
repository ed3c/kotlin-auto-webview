#!/usr/bin/env python3
"""Fail-closed validator for the public L2 GitHub connector receipt."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

SHA40 = re.compile(r"^[0-9a-f]{40}$")
EMAIL = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
SECRET_MARKERS = (
    "github_pat_", "ghp_", "gho_", "ghu_", "ghs_", "ghr_",
    "bearer ", "authorization:", "set-cookie:", "cookie:",
)


class ReceiptValidationError(ValueError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ReceiptValidationError(message)


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    actual = set(value)
    _require(actual == expected, f"{label} keys mismatch: expected={sorted(expected)} actual={sorted(actual)}")


def _object(value: Any, label: str) -> dict[str, Any]:
    _require(isinstance(value, dict), f"{label} must be an object")
    return value


def _positive_int(value: Any, label: str) -> int:
    _require(isinstance(value, int) and not isinstance(value, bool) and value > 0, f"{label} must be positive")
    return value


def _parse_time(value: Any, label: str) -> datetime:
    _require(isinstance(value, str) and value, f"{label} must be a non-empty string")
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ReceiptValidationError(f"{label} must be ISO-8601") from exc


def _walk_strings(value: Any):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for child in value.values():
            yield from _walk_strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_strings(child)


def validate_receipt(
    receipt: dict[str, Any],
    subject: dict[str, Any],
    expected_implementation_head: str | None = None,
) -> str:
    _exact_keys(
        receipt,
        {
            "schema", "lane", "status", "maximum_claim", "transport", "subject",
            "execution", "result", "disclosure", "cleanup", "evidence_boundary",
        },
        "receipt",
    )
    _require(receipt["schema"] == "kaw.workspace.live-github-receipt.v1", "receipt schema mismatch")
    _require(receipt["lane"] == "L2_LIVE_GITHUB_CONNECTOR", "receipt lane mismatch")
    _require(receipt["status"] == "PASS", "only a literal PASS receipt is admitted")
    _require(
        receipt["maximum_claim"] == "PUBLIC_EXACT_SUBJECT_READ_AND_LOCAL_PROJECTION",
        "maximum claim widened or changed",
    )

    expected_transport = _object(subject["transport"], "subject.transport")
    transport = _object(receipt["transport"], "transport")
    _exact_keys(
        transport,
        {"class", "source_commit", "source_blob", "credential_mode", "credential_provider_bound"},
        "transport",
    )
    _require(transport["class"] == expected_transport["class"] == "GitHubRestMetadataSource", "transport class mismatch")
    _require(transport["source_commit"] == expected_transport["source_commit"], "transport commit drift")
    _require(transport["source_blob"] == expected_transport["source_blob"], "transport blob drift")
    _require(transport["credential_mode"] == "NONE", "public canary must use credential mode NONE")
    _require(transport["credential_provider_bound"] is False, "credential provider must not be bound")
    _require(SHA40.fullmatch(transport["source_commit"]) is not None, "transport commit must be a SHA")
    _require(SHA40.fullmatch(transport["source_blob"]) is not None, "transport blob must be a Git SHA")

    expected_repository = _object(subject["repository"], "subject.repository")
    expected_issue = _object(subject["issue"], "subject.issue")
    expected_pr = _object(subject["pull_request"], "subject.pull_request")
    expected_commit = _object(subject["commit"], "subject.commit")
    actual_subject = _object(receipt["subject"], "subject")
    _exact_keys(
        actual_subject,
        {
            "repository_full_name", "repository_id", "repository_node_id",
            "issue_number", "issue_id", "issue_node_id",
            "pull_request_number", "pull_request_id", "pull_request_node_id",
            "head_sha", "tree_sha", "runtime_node_id_validation",
        },
        "subject",
    )
    expected_values = {
        "repository_full_name": expected_repository["full_name"],
        "repository_id": expected_repository["id"],
        "repository_node_id": expected_repository["node_id"],
        "issue_number": expected_issue["number"],
        "issue_id": expected_issue["id"],
        "issue_node_id": expected_issue["node_id"],
        "pull_request_number": expected_pr["number"],
        "pull_request_id": expected_pr["id"],
        "pull_request_node_id": expected_pr["node_id"],
        "head_sha": expected_pr["head_sha"],
        "tree_sha": expected_commit["tree_sha"],
        "runtime_node_id_validation": "PREP_BINDING_ONLY_W2_MODEL_ABSENT",
    }
    for key, expected in expected_values.items():
        _require(actual_subject[key] == expected, f"subject binding mismatch: {key}")

    execution = _object(receipt["execution"], "execution")
    _exact_keys(
        execution,
        {
            "implementation_head_sha", "workflow_run_id", "workflow_run_attempt",
            "runner_os", "started_at", "ended_at", "observation_sequence",
        },
        "execution",
    )
    implementation_head = execution["implementation_head_sha"]
    _require(isinstance(implementation_head, str) and SHA40.fullmatch(implementation_head) is not None, "implementation head must be exact")
    if expected_implementation_head is not None:
        _require(implementation_head == expected_implementation_head, "receipt does not belong to the expected implementation head")
    _positive_int(execution["workflow_run_id"], "workflow_run_id")
    _positive_int(execution["workflow_run_attempt"], "workflow_run_attempt")
    _require(isinstance(execution["runner_os"], str) and execution["runner_os"], "runner_os missing")
    started = _parse_time(execution["started_at"], "started_at")
    ended = _parse_time(execution["ended_at"], "ended_at")
    _require(ended >= started, "receipt end time precedes start time")
    _require(isinstance(execution["observation_sequence"], int) and execution["observation_sequence"] >= 0, "observation sequence invalid")

    result = _object(receipt["result"], "result")
    _exact_keys(
        result,
        {
            "subjects_applied", "edges_applied", "active_subjects_after_reopen",
            "w1_subject_read_back", "w1_edge_read_back", "all_returned_checks_exact_head",
            "exact_successful_check_ids", "exact_successful_check_names", "source_workflow_run_ids",
        },
        "result",
    )
    _require(_positive_int(result["subjects_applied"], "subjects_applied") >= 9, "too few subjects applied")
    _positive_int(result["edges_applied"], "edges_applied")
    _require(result["active_subjects_after_reopen"] == result["subjects_applied"], "W1 reopen denominator mismatch")
    _require(result["w1_subject_read_back"] is True, "W1 subject read-back missing")
    _require(result["w1_edge_read_back"] is True, "W1 edge read-back missing")
    _require(result["all_returned_checks_exact_head"] is True, "stale check was admitted")

    expected_checks = subject["expected_checks"]
    expected_check_ids = sorted(check["id"] for check in expected_checks)
    expected_check_names = sorted(check["name"] for check in expected_checks)
    expected_run_ids = sorted({check["run_id"] for check in expected_checks})
    _require(result["exact_successful_check_ids"] == expected_check_ids, "exact check denominator mismatch")
    _require(sorted(result["exact_successful_check_names"]) == expected_check_names, "exact check names mismatch")
    _require(result["source_workflow_run_ids"] == expected_run_ids, "source workflow run denominator mismatch")

    disclosure = _object(receipt["disclosure"], "disclosure")
    _exact_keys(
        disclosure,
        {
            "authorization_header_persisted", "token_persisted", "cookie_persisted",
            "email_persisted", "response_body_persisted", "private_locator_persisted",
        },
        "disclosure",
    )
    _require(all(value is False for value in disclosure.values()), "receipt disclosure boundary failed")

    cleanup = _object(receipt["cleanup"], "cleanup")
    _exact_keys(
        cleanup,
        {"temporary_database_removed", "credential_cleanup_required", "temporary_response_files_persisted"},
        "cleanup",
    )
    _require(cleanup["temporary_database_removed"] is True, "temporary database residue remains")
    _require(cleanup["credential_cleanup_required"] is False, "public mode unexpectedly required credential cleanup")
    _require(cleanup["temporary_response_files_persisted"] is False, "temporary response residue remains")

    boundary = _object(receipt["evidence_boundary"], "evidence_boundary")
    _exact_keys(
        boundary,
        {"public_repository", "private_repository", "github_mutation", "merge_release", "l3_to_l6", "l7_user_outcome"},
        "evidence_boundary",
    )
    _require(boundary == {
        "public_repository": "PASS",
        "private_repository": "NOT_EXERCISED",
        "github_mutation": "NOT_IMPLEMENTED",
        "merge_release": "NOT_AUTHORIZED",
        "l3_to_l6": "NOT_EXERCISED",
        "l7_user_outcome": "ABSENT",
    }, "evidence boundary was widened")

    for text in _walk_strings(receipt):
        lowered = text.lower()
        _require(not any(marker in lowered for marker in SECRET_MARKERS), "credential-like material detected")
        _require(EMAIL.search(text) is None, "email address detected in public receipt")
        _require("private.github" not in lowered, "private locator detected")

    canonical = json.dumps(receipt, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def _load(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    _require(isinstance(value, dict), f"{path} must contain a JSON object")
    return value


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("receipt", type=Path)
    parser.add_argument("subject", type=Path)
    parser.add_argument("--expected-implementation-head")
    args = parser.parse_args(argv)
    try:
        digest = validate_receipt(
            _load(args.receipt),
            _load(args.subject),
            expected_implementation_head=args.expected_implementation_head,
        )
    except (OSError, json.JSONDecodeError, ReceiptValidationError) as exc:
        print(f"live GitHub receipt verification: FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"live GitHub receipt verification: PASS sha256={digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
