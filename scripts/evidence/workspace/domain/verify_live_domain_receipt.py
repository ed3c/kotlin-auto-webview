#!/usr/bin/env python3
"""Fail-closed verifier for KAW's live public domain-authority receipt."""

from __future__ import annotations

import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any

SHA40 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
EMAIL = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
SECRET_MARKERS = (
    "github_pat_", "ghp_", "gho_", "ghu_", "ghs_", "ghr_",
    "bearer ", "authorization:", "set-cookie:", "cookie:",
    "access_token", "refresh_token", "private key",
)
VERDICTS = {"SUPPORTED", "REFUTED", "CONFLICTED", "STALE", "UNVERIFIABLE"}


class DomainReceiptValidationError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise DomainReceiptValidationError(message)


def exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    require(set(value) == expected, f"{label} keys mismatch")


def obj(value: Any, label: str) -> dict[str, Any]:
    require(isinstance(value, dict), f"{label} must be an object")
    return value


def positive_int(value: Any, label: str) -> int:
    require(isinstance(value, int) and not isinstance(value, bool) and value > 0, f"{label} invalid")
    return value


def parse_time(value: Any, label: str) -> datetime:
    require(isinstance(value, str) and value, f"{label} missing")
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise DomainReceiptValidationError(f"{label} must be ISO-8601") from exc


def walk_strings(value: Any):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for child in value.values():
            yield from walk_strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk_strings(child)


def validate_receipt(
    receipt: dict[str, Any],
    *,
    expected_implementation_head: str | None = None,
) -> str:
    exact_keys(
        receipt,
        {
            "schema", "lane", "status", "maximum_claim", "producer", "execution",
            "subject", "authority", "validation", "disclosure", "evidence_boundary",
        },
        "receipt",
    )
    require(receipt["schema"] == "kaw.workspace.live-domain-receipt.v1", "schema mismatch")
    require(receipt["lane"] == "L5_LIVE_DOMAIN_AUTHORITY_RECEIPT", "lane mismatch")
    require(receipt["status"] == "PASS", "only a literal PASS receipt is admitted")
    require(
        receipt["maximum_claim"] == "EXACT_PUBLIC_DOMAIN_RECEIPT_VALIDATION",
        "maximum claim widened",
    )

    producer = obj(receipt["producer"], "producer")
    exact_keys(
        producer,
        {
            "repository_full_name", "repository_id", "pull_request_number", "commit_sha",
            "tree_sha", "receipt_path", "receipt_blob_sha", "receipt_content_sha256",
            "workflow_runs",
        },
        "producer",
    )
    require(producer["repository_full_name"] == "ed3c/truth-verify-loop", "producer mismatch")
    positive_int(producer["repository_id"], "producer.repository_id")
    positive_int(producer["pull_request_number"], "producer.pull_request_number")
    for key in ("commit_sha", "tree_sha", "receipt_blob_sha"):
        require(isinstance(producer[key], str) and SHA40.fullmatch(producer[key]), f"{key} invalid")
    require(
        producer["receipt_path"] == "receipts/kaw/public-claim-canary.json",
        "receipt path mismatch",
    )
    require(
        isinstance(producer["receipt_content_sha256"], str)
        and SHA256.fullmatch(producer["receipt_content_sha256"]),
        "receipt content digest invalid",
    )
    workflows = producer["workflow_runs"]
    require(isinstance(workflows, list) and len(workflows) == 2, "workflow denominator mismatch")
    expected_names = {"KAW Domain Receipt", "verify"}
    actual_names: set[str] = set()
    run_ids: set[int] = set()
    for index, raw in enumerate(workflows):
        workflow = obj(raw, f"workflow_runs[{index}]")
        exact_keys(workflow, {"name", "run_id", "conclusion"}, "workflow")
        require(workflow["name"] in expected_names, "unexpected producer workflow")
        require(workflow["name"] not in actual_names, "duplicate producer workflow")
        actual_names.add(workflow["name"])
        run_id = positive_int(workflow["run_id"], "producer workflow run id")
        require(run_id not in run_ids, "producer workflow run id reused")
        run_ids.add(run_id)
        require(workflow["conclusion"] == "success", "producer workflow did not pass")
    require(actual_names == expected_names, "producer workflow denominator incomplete")

    execution = obj(receipt["execution"], "execution")
    exact_keys(
        execution,
        {
            "implementation_head_sha", "workflow_run_id", "workflow_run_attempt",
            "runner_os", "started_at", "ended_at",
        },
        "execution",
    )
    head = execution["implementation_head_sha"]
    require(isinstance(head, str) and SHA40.fullmatch(head), "implementation head invalid")
    if expected_implementation_head is not None:
        require(head == expected_implementation_head, "receipt belongs to another KAW head")
    positive_int(execution["workflow_run_id"], "workflow_run_id")
    positive_int(execution["workflow_run_attempt"], "workflow_run_attempt")
    require(isinstance(execution["runner_os"], str) and execution["runner_os"], "runner_os missing")
    started = parse_time(execution["started_at"], "started_at")
    ended = parse_time(execution["ended_at"], "ended_at")
    require(ended >= started, "receipt end time precedes start time")

    subject = obj(receipt["subject"], "subject")
    exact_keys(subject, {"claim_id", "claim_digest", "receipt_id"}, "subject")
    require(subject["claim_id"] == "synthetic-sdk-release", "claim id mismatch")
    require(
        subject["claim_digest"] == "49baf11ae87da437f87de2380672fb2d7b92810b2b2ad948f278637433b06c21",
        "claim digest mismatch",
    )
    require(subject["receipt_id"] == "TVL-KAW-PUBLIC-SYNTHETIC-1", "producer receipt id mismatch")

    authority = obj(receipt["authority"], "authority")
    exact_keys(
        authority,
        {
            "owner", "environment", "verdict_state", "closed", "closure_digest",
            "source_freshness", "evidence_ceiling",
        },
        "authority",
    )
    require(authority["owner"] == "truth-verify-loop", "authority owner mismatch")
    require(authority["environment"] == "PUBLIC_SYNTHETIC_CI", "environment widened")
    require(authority["verdict_state"] in VERDICTS, "unknown domain verdict")
    require(isinstance(authority["closed"], bool), "closed must be boolean")
    require(
        isinstance(authority["closure_digest"], str) and SHA256.fullmatch(authority["closure_digest"]),
        "closure digest invalid",
    )
    require(authority["source_freshness"] in {"CURRENT", "STALE"}, "freshness invalid")
    require(authority["evidence_ceiling"] == "DOMAIN_VERDICT", "evidence ceiling widened")

    validation = obj(receipt["validation"], "validation")
    exact_keys(
        validation,
        {
            "exact_repository", "exact_commit", "exact_tree", "exact_blob",
            "exact_content_digest", "producer_workflows_exact_head", "verdict_preserved",
            "raw_source_imported", "raw_evidence_imported",
        },
        "validation",
    )
    for key in (
        "exact_repository", "exact_commit", "exact_tree", "exact_blob",
        "exact_content_digest", "producer_workflows_exact_head", "verdict_preserved",
    ):
        require(validation[key] is True, f"required validation missing: {key}")
    require(validation["raw_source_imported"] is False, "raw source imported")
    require(validation["raw_evidence_imported"] is False, "raw evidence imported")

    disclosure = obj(receipt["disclosure"], "disclosure")
    exact_keys(
        disclosure,
        {
            "credential_persisted", "authorization_header_persisted", "cookie_persisted",
            "email_persisted", "internal_reasoning_persisted", "private_locator_persisted",
            "raw_source_persisted", "raw_evidence_persisted",
        },
        "disclosure",
    )
    require(all(value is False for value in disclosure.values()), "disclosure boundary failed")

    boundary = obj(receipt["evidence_boundary"], "evidence_boundary")
    expected_boundary = {
        "l2_public_github": "PASS_SEPARATE_RECEIPT",
        "l3_google": "NOT_EXERCISED",
        "l4_bettor": "NOT_EXERCISED",
        "l5_domain_authority": "PASS",
        "l6_physical_device": "NOT_EXERCISED",
        "l7_user_outcome": "ABSENT",
        "paid_outcome": "ABSENT",
        "merge_release": "NOT_AUTHORIZED",
    }
    require(boundary == expected_boundary, "evidence boundary widened or shrunk")

    for text in walk_strings(receipt):
        lowered = text.lower()
        require(not any(marker in lowered for marker in SECRET_MARKERS), "credential-like material detected")
        require(EMAIL.search(text) is None, "email detected")
        require("private.github" not in lowered, "private locator detected")

    canonical = json.dumps(receipt, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def load(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), f"{path} must contain an object")
    return value


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("receipt", type=Path)
    parser.add_argument("--expected-implementation-head")
    args = parser.parse_args(argv)
    try:
        digest = validate_receipt(
            load(args.receipt),
            expected_implementation_head=args.expected_implementation_head,
        )
    except (OSError, json.JSONDecodeError, DomainReceiptValidationError) as exc:
        print(f"live domain receipt verification: FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"live domain receipt verification: PASS sha256={digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
