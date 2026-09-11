#!/usr/bin/env python3
"""Fail-closed validator for the pinned OpenDroid admission contract.

Uses only the Python standard library so the source-admission gate has no runtime
package dependency. It intentionally validates the narrow YAML subset checked in
under integrations/opendroid rather than acting as a general YAML parser.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
LOCK_PATH = ROOT / "upstream.lock.json"
MAP_PATH = ROOT / "capability-map.yaml"
POLICY_PATH = ROOT / "policy-profile-matrix.yaml"
LEDGER_PATH = ROOT / "source-ledger.json"

ALLOWED_DECISIONS = {
    "ADOPT_AS_CONTRACT",
    "ADAPT_BEHIND_POLICY",
    "REFERENCE_ONLY",
    "DENIED_BY_ARCHITECTURE",
    "EXTERNAL_POLICY_ADMIT_REQUIRED",
}
MANDATORY_CAPABILITIES = {
    "action-taxonomy",
    "action-auto-mapping",
    "accessibility-observation",
    "accessibility-action-service",
    "generic-app-automation",
    "postcondition-verification-pattern",
    "multi-step-call-flow",
    "sms-and-communications",
    "calendar-actions",
    "raw-coordinate-or-gesture-authority",
    "direct-mcp-execution",
    "privileged-shell-root-terminal",
}
MANDATORY_DENIED_CAPABILITIES = {
    "raw-coordinate-or-gesture-authority",
    "direct-mcp-execution",
    "privileged-shell-root-terminal",
}
MANDATORY_HARD_DENIALS = {
    "direct_mcp_execute_action",
    "caller_supplied_shell",
    "root_fallback",
    "generic_terminal",
    "model_generated_coordinate_authority",
    "first_match_target_authority",
    "raw_screen_data_before_privacy_filter",
}
PLAY_SAFE_FALSE_KEYS = {
    "accessibility_service",
    "device_wide_autonomous_actions",
    "shizuku",
    "root",
    "generic_shell",
    "inbound_mobile_mcp",
}


def fail(reason: str, detail: str) -> int:
    print(json.dumps({"schema": "kotlin-auto-webview/opendroid-admission-receipt/v1", "state": "FAIL", "reason": reason, "detail": detail}, sort_keys=True))
    return 2


def parse_capabilities(text: str) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    current: dict[str, str] | None = None
    for line in text.splitlines():
        match = re.match(r"^  - id: (.+)$", line)
        if match:
            if current is not None:
                rows.append(current)
            current = {"id": match.group(1).strip()}
            continue
        if current is None:
            continue
        field = re.match(r"^    ([a-z_]+): (.+)$", line)
        if field:
            current[field.group(1)] = field.group(2).strip()
    if current is not None:
        rows.append(current)
    return rows


def parse_play_safe_false_keys(text: str) -> dict[str, str]:
    block_match = re.search(r"  PLAY_SAFE:\n    compile_ceiling:\n(?P<body>(?:      .+\n)+)", text)
    if not block_match:
        return {}
    result: dict[str, str] = {}
    for line in block_match.group("body").splitlines():
        match = re.match(r"      ([a-z_]+): (.+)$", line)
        if match:
            result[match.group(1)] = match.group(2).strip()
    return result


def parse_hard_denials(text: str) -> set[str]:
    marker = "hard_denials:\n"
    if marker not in text:
        return set()
    tail = text.split(marker, 1)[1]
    values: set[str] = set()
    for line in tail.splitlines():
        match = re.match(r"^  - ([a-z0-9_]+)$", line)
        if not match:
            break
        values.add(match.group(1))
    return values


def validate(expected_commit: str | None, expected_tree: str | None) -> int:
    lock = json.loads(LOCK_PATH.read_text())
    ledger = json.loads(LEDGER_PATH.read_text())
    map_text = MAP_PATH.read_text()
    policy_text = POLICY_PATH.read_text()

    if lock.get("repository") != "yashab-cyber/opendroid":
        return fail("STALE_SOURCE_PIN", "unexpected upstream repository")
    if expected_commit and lock.get("commit") != expected_commit:
        return fail("STALE_SOURCE_PIN", "upstream commit differs from expected subject")
    if expected_tree and lock.get("tree") != expected_tree:
        return fail("STALE_SOURCE_PIN", "upstream tree differs from expected subject")
    license_info = lock.get("license", {})
    if license_info.get("spdx") != "Apache-2.0" or license_info.get("path") != "LICENSE" or not license_info.get("blob"):
        return fail("PROVENANCE_INVALID", "Apache-2.0 license subject is not fully pinned")

    inspected = {item.get("path"): item.get("blob") for item in lock.get("inspected_subjects", [])}
    if not inspected or any(not path or not blob for path, blob in inspected.items()):
        return fail("STALE_SOURCE_PIN", "inspected path/blob subjects are incomplete")

    rows = parse_capabilities(map_text)
    ids = [row.get("id") for row in rows]
    if len(ids) != len(set(ids)):
        return fail("CAPABILITY_COMPLETENESS", "duplicate capability id")
    missing = sorted(MANDATORY_CAPABILITIES - set(ids))
    if missing:
        return fail("CAPABILITY_COMPLETENESS", "missing mandatory capabilities: " + ",".join(missing))

    for row in rows:
        decision = row.get("decision")
        if decision not in ALLOWED_DECISIONS:
            return fail("CAPABILITY_DECISION_INVALID", f"{row.get('id')} has invalid decision {decision}")
        source = row.get("source")
        blob = row.get("blob")
        if source not in inspected or inspected[source] != blob:
            return fail("STALE_SOURCE_PIN", f"{row.get('id')} source/blob does not match upstream.lock.json")
        if decision in {"ADOPT_AS_CONTRACT", "ADAPT_BEHIND_POLICY", "EXTERNAL_POLICY_ADMIT_REQUIRED"}:
            required = ("contract_owner", "policy_owner", "verifier", "negative_control", "evidence_lane")
            if any(not row.get(field) for field in required):
                return fail("CAPABILITY_OWNER_INCOMPLETE", f"{row.get('id')} lacks downstream ownership/evidence fields")

    by_id = {row["id"]: row for row in rows}
    for capability_id in MANDATORY_DENIED_CAPABILITIES:
        row = by_id[capability_id]
        if row.get("decision") != "DENIED_BY_ARCHITECTURE" or row.get("profile_ceiling") != "NONE":
            return fail("FORBIDDEN_CAPABILITY_ADMITTED", capability_id)

    play_safe = parse_play_safe_false_keys(policy_text)
    for key in PLAY_SAFE_FALSE_KEYS:
        if play_safe.get(key) != "false":
            return fail("PLAY_SAFE_WIDENED", f"PLAY_SAFE.{key} must be false")

    hard_denials = parse_hard_denials(policy_text)
    missing_denials = sorted(MANDATORY_HARD_DENIALS - hard_denials)
    if missing_denials:
        return fail("HARD_DENIAL_MISSING", ",".join(missing_denials))

    if ledger.get("integration_mode") != "REFERENCE_AND_BEHAVIORAL_ADAPTATION":
        return fail("PROVENANCE_INVALID", "unexpected integration mode")
    for field in ("copied_source", "binary_dependencies", "vendored_dependencies", "modified_upstream_files"):
        if ledger.get(field) != []:
            return fail("PROVENANCE_REVIEW_REQUIRED", f"{field} is non-empty")
    rules = ledger.get("rules", {})
    if not all(rules.get(key) is True for key in ("copy_requires_explicit_entry", "modified_copy_requires_notice_review", "reference_does_not_transfer_authority_model", "moving_ref_is_not_accepted")):
        return fail("PROVENANCE_INVALID", "required provenance rule disabled")

    receipt = {
        "schema": "kotlin-auto-webview/opendroid-admission-receipt/v1",
        "state": "PASS",
        "evidence_ceiling": "STATIC_SOURCE_ADMISSION_ONLY",
        "upstream": {"repository": lock["repository"], "commit": lock["commit"], "tree": lock["tree"], "license_blob": license_info["blob"]},
        "capability_count": len(rows),
        "denied_capabilities": sorted(MANDATORY_DENIED_CAPABILITIES),
        "local_handoff_execution": "NOT_CLAIMED",
    }
    print(json.dumps(receipt, sort_keys=True))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-commit")
    parser.add_argument("--expected-tree")
    args = parser.parse_args()
    return validate(args.expected_commit, args.expected_tree)


if __name__ == "__main__":
    raise SystemExit(main())
