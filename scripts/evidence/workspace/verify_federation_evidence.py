#!/usr/bin/env python3
"""Fail-closed verifier for the public Federated Capability Workspace evidence denominator."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

SHA40 = re.compile(r"^[0-9a-f]{40}$")
REQUIRED_LANES = {
    "L0": ("CONTRACT", "DETERMINISTIC_CONTRACT"),
    "L1": ("LOCAL_DETERMINISTIC", "DETERMINISTIC_FIXTURE"),
    "L2": ("GITHUB_CONNECTOR", "LIVE_GITHUB_CONNECTOR"),
    "L3": ("GOOGLE_PROJECTION_ACCOUNT", "LIVE_GOOGLE_ACCOUNT"),
    "L4": ("BETTOR_ROUTE_HANDOFF", "LIVE_BETTOR_HANDOFF"),
    "L5": ("DOMAIN_AUTHORITY_RECEIPT", "LIVE_DOMAIN_AUTHORITY"),
    "L6": ("PHYSICAL_DEVICE", "PHYSICAL_DEVICE"),
    "L7": ("USER_OUTCOME", "USER_OUTCOME"),
}
ALLOWED_STATUSES = {
    "PASS",
    "FAIL",
    "BLOCKED",
    "NOT_EXERCISED",
    "EXTERNAL_AUTHORITY_REQUIRED",
    "ABSENT",
}
FORBIDDEN_PUBLIC_KEYS = {
    "private_repo_url",
    "drive_file_id",
    "customer_id",
    "credential",
    "credentials",
    "token",
    "access_token",
    "refresh_token",
    "api_key",
    "cookie",
    "cookies",
    "secret",
    "source_content",
}
FORBIDDEN_PUBLIC_TEXT = (
    "private-owner",
    "private-repo",
    "customer-secret",
    "drive.google.com/file/d/private",
    "docs.google.com/document/d/private",
)
EXPECTED_HARD_LAWS = {
    "url_or_title_is_stable_identity": False,
    "stale_check_can_evidence_current_head": False,
    "google_write_ack_without_read_back_is_success": False,
    "google_manual_edit_can_change_canonical": False,
    "public_receipt_can_contain_private_identifier": False,
    "route_request_grants_execution": False,
    "cross_authority_receipt_reuse_allowed": False,
    "fixture_can_satisfy_live_lane": False,
    "ci_or_issue_can_satisfy_user_outcome": False,
}


def load_manifest(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _walk_public(value: Any, path: str, errors: list[str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key.lower() in FORBIDDEN_PUBLIC_KEYS:
                errors.append(f"public receipt contains forbidden key at {path}.{key}")
            _walk_public(child, f"{path}.{key}", errors)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _walk_public(child, f"{path}[{index}]", errors)
    elif isinstance(value, str):
        lowered = value.lower()
        for marker in FORBIDDEN_PUBLIC_TEXT:
            if marker in lowered:
                errors.append(f"public receipt contains private locator text at {path}")


def verify_manifest(manifest: dict[str, Any]) -> list[str]:
    errors: list[str] = []

    if manifest.get("schema_version") != 1:
        errors.append("schema_version must be 1")
    if manifest.get("program") != "FEDERATED_CAPABILITY_WORKSPACE":
        errors.append("program identity mismatch")
    if manifest.get("public_registry_only") is not True:
        errors.append("W6 public receipt must be public-registry-only")

    evidence_list = manifest.get("implementation_subjects")
    if not isinstance(evidence_list, list) or not evidence_list:
        errors.append("implementation_subjects must be a non-empty list")
        evidence_list = []

    evidence_by_id: dict[str, dict[str, Any]] = {}
    atoms: set[str] = set()
    for item in evidence_list:
        if not isinstance(item, dict):
            errors.append("implementation subject must be an object")
            continue
        evidence_id = item.get("evidence_id")
        if not isinstance(evidence_id, str) or not evidence_id:
            errors.append("implementation subject missing evidence_id")
            continue
        if evidence_id in evidence_by_id:
            errors.append(f"duplicate evidence id: {evidence_id}")
        evidence_by_id[evidence_id] = item

        atom = item.get("atom")
        if not isinstance(atom, str) or not atom:
            errors.append(f"{evidence_id}: missing atom")
        elif atom in atoms:
            errors.append(f"duplicate atom evidence: {atom}")
        else:
            atoms.add(atom)

        head = item.get("head_sha")
        check_head = item.get("check_head_sha")
        if not isinstance(head, str) or not SHA40.fullmatch(head):
            errors.append(f"{evidence_id}: invalid exact head SHA")
        if not isinstance(check_head, str) or not SHA40.fullmatch(check_head):
            errors.append(f"{evidence_id}: invalid check head SHA")
        elif head != check_head:
            errors.append(f"{evidence_id}: stale check head cannot evidence current subject")

        if item.get("workflow_conclusion") != "success":
            errors.append(f"{evidence_id}: technical evidence is not a successful exact-head workflow")
        if not isinstance(item.get("workflow_run_id"), int) or item["workflow_run_id"] <= 0:
            errors.append(f"{evidence_id}: workflow run id must be positive")
        if item.get("expected_receipt_authority") != item.get("receipt_authority"):
            errors.append(f"{evidence_id}: receipt authority mismatch")
        if item.get("manual_edit_can_change_canonical") is not False:
            errors.append(f"{evidence_id}: external/manual edit cannot mutate canonical truth")
        if item.get("route_request_grants_execution") is not False:
            errors.append(f"{evidence_id}: route request cannot grant execution authority")
        if item.get("atom") == "W3" and item.get("read_back_verified") is not True:
            errors.append("W3: projection fixture cannot claim success without read-back verification")

        public_receipt = item.get("public_receipt")
        if not isinstance(public_receipt, dict):
            errors.append(f"{evidence_id}: public_receipt must be an object")
        else:
            _walk_public(public_receipt, f"{evidence_id}.public_receipt", errors)

    expected_atoms = {f"W{i}" for i in range(6)}
    if atoms != expected_atoms:
        errors.append(f"implementation atom denominator mismatch: expected {sorted(expected_atoms)}, got {sorted(atoms)}")

    lanes = manifest.get("lanes")
    if not isinstance(lanes, list):
        errors.append("lanes must be a list")
        lanes = []
    lanes_by_id: dict[str, dict[str, Any]] = {}
    for lane in lanes:
        if not isinstance(lane, dict):
            errors.append("lane must be an object")
            continue
        lane_id = lane.get("lane_id")
        if lane_id in lanes_by_id:
            errors.append(f"duplicate lane: {lane_id}")
        if isinstance(lane_id, str):
            lanes_by_id[lane_id] = lane

    if set(lanes_by_id) != set(REQUIRED_LANES):
        errors.append("L0-L7 denominator must be complete with no extra lanes")

    for lane_id, (expected_name, expected_environment) in REQUIRED_LANES.items():
        lane = lanes_by_id.get(lane_id)
        if lane is None:
            continue
        if lane.get("name") != expected_name:
            errors.append(f"{lane_id}: lane name mismatch")
        if lane.get("required_environment_class") != expected_environment:
            errors.append(f"{lane_id}: required environment mismatch")
        status = lane.get("status")
        if status not in ALLOWED_STATUSES:
            errors.append(f"{lane_id}: unsupported status {status!r}")
        evidence_ids = lane.get("evidence_ids")
        if not isinstance(evidence_ids, list):
            errors.append(f"{lane_id}: evidence_ids must be a list")
            continue
        missing = [evidence_id for evidence_id in evidence_ids if evidence_id not in evidence_by_id]
        if missing:
            errors.append(f"{lane_id}: dangling evidence ids {missing}")
            continue

        if status == "PASS":
            if not evidence_ids:
                errors.append(f"{lane_id}: PASS requires exact evidence")
                continue
            for evidence_id in evidence_ids:
                evidence = evidence_by_id[evidence_id]
                if evidence.get("environment_class") != expected_environment:
                    errors.append(
                        f"{lane_id}: fixture/environment {evidence.get('environment_class')} cannot satisfy {expected_environment}"
                    )
                if expected_environment.startswith("LIVE_") and evidence.get("live_provider") is not True:
                    errors.append(f"{lane_id}: live lane requires live_provider=true")
                if expected_environment == "PHYSICAL_DEVICE" and evidence.get("physical_device") is not True:
                    errors.append("L6: simulator/CI cannot satisfy physical-device lane")
                if expected_environment == "USER_OUTCOME" and evidence.get("evidence_type") != "USER_OUTCOME":
                    errors.append("L7: CI/issue/PR evidence cannot satisfy user outcome")
        elif evidence_ids and status in {"NOT_EXERCISED", "ABSENT"}:
            errors.append(f"{lane_id}: {status} lane cannot carry evidence ids")

    hard_laws = manifest.get("hard_laws")
    if hard_laws != EXPECTED_HARD_LAWS:
        errors.append("hard_laws changed or incomplete")

    return errors


def main(argv: list[str]) -> int:
    path = Path(argv[1]) if len(argv) > 1 else Path("receipts/workspace/federation-evidence.json")
    manifest = load_manifest(path)
    errors = verify_manifest(manifest)
    if errors:
        print("workspace evidence verification: FAIL")
        for error in errors:
            print(f"- {error}")
        return 1
    print("workspace evidence verification: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
