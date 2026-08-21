#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

SHA40 = re.compile(r"^[0-9a-f]{40}$")
EXPECTED_FOUNDATION = {
    "W0": (120, 136, "fba4a7f1c7cb8ca014d5a3cfe083fd9beaea4c5c"),
    "W1": (121, 139, "286f588226be7b0f9ecb63042e3eaefd5bc77dd7"),
    "W2": (122, 158, "3294fb2b4d86fef91f3f2c63e28718c490147808"),
    "W3": (123, 160, "95754e2a7ea6a09da030da3803313fe49641b677"),
    "W4": (124, 161, "56eb824866e7e74d63a4297748c647cff738db51"),
    "W5": (125, 162, "f0e37a4f2b39dd825bfd379d42f96c29ce887f37"),
    "W6": (126, 163, "c19d4e561cb09cb1c6c96c2b0f8df0c88b7d987b"),
}
EXPECTED_ATOMS = {
    "L2-GH": (165, "L2", "READY_FOR_IMPLEMENTATION_PREP", 158),
    "L3-GOOGLE": (166, "L3", "READY_FOR_IMPLEMENTATION_PREP", 160),
    "L4-BETTOR": (167, "L4", "READY_FOR_CROSS_REPO_PREP", 161),
    "L5-DOMAIN": (168, "L5", "READY_FOR_CROSS_REPO_PREP", 161),
    "L6-DEVICE": (169, "L6", "BLOCKED_EXTERNAL_DEVICE", 162),
    "L7-USER": (170, "L7", "BLOCKED_ON_LIVE_VERTICAL_SLICE", None),
    "P1-EVIDENCE": (171, "CONVERGENCE", "BLOCKED_ON_LIVE_RECEIPTS", 163),
    "P1-PREP": (172, "PREIMPLEMENTATION", "CURRENT", 163),
}
EXPECTED_LANES = {
    "L2": "NOT_EXERCISED",
    "L3": "NOT_EXERCISED",
    "L4": "NOT_EXERCISED",
    "L5": "NOT_EXERCISED",
    "L6": "NOT_EXERCISED",
    "L7": "ABSENT",
}
EXPECTED_HARD_LAWS = {
    "fixture_is_live": False,
    "ci_is_physical_device": False,
    "route_ack_is_execution": False,
    "domain_receipt_ref_is_verdict_content": False,
    "account_access_is_content_rights": False,
    "technical_evidence_is_user_outcome": False,
    "google_projection_is_canonical_authority": False,
}
FORBIDDEN_PATH_OVERLAP = {
    ("L2-GH", "L3-GOOGLE"),
    ("L2-GH", "L4-BETTOR"),
    ("L2-GH", "L5-DOMAIN"),
    ("L3-GOOGLE", "L4-BETTOR"),
    ("L3-GOOGLE", "L5-DOMAIN"),
    ("L4-BETTOR", "L5-DOMAIN"),
}
FORBIDDEN_PUBLIC_TEXT = (
    "access_token",
    "refresh_token",
    "api_key",
    "private-owner",
    "private-repo",
    "customer-secret",
    "device-serial",
)


def load(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def verify(data: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if data.get("schema_version") != 1:
        errors.append("schema_version must be 1")
    if data.get("program") != "FEDERATED_CAPABILITY_WORKSPACE_LIVE_EVIDENCE":
        errors.append("program identity mismatch")
    if data.get("owner_issue") != 172 or data.get("parent_epic") != 164:
        errors.append("owner/parent identity mismatch")
    if data.get("void_issues_excluded") != [173, 174]:
        errors.append("connector-misfire issues must remain explicitly excluded")

    foundation = data.get("foundation")
    if not isinstance(foundation, list):
        errors.append("foundation must be a list")
        foundation = []
    by_foundation = {item.get("atom"): item for item in foundation if isinstance(item, dict)}
    if set(by_foundation) != set(EXPECTED_FOUNDATION):
        errors.append("W0-W6 foundation denominator mismatch")
    for atom, (issue, pr, sha) in EXPECTED_FOUNDATION.items():
        item = by_foundation.get(atom)
        if item is None:
            continue
        if (item.get("issue"), item.get("pr"), item.get("head_sha")) != (issue, pr, sha):
            errors.append(f"{atom}: exact foundation subject drift")
        if item.get("state") != "CLOSED_DETERMINISTIC":
            errors.append(f"{atom}: deterministic foundation state changed")
        runs = item.get("workflow_run_ids")
        if not isinstance(runs, list) or not runs or not all(isinstance(x, int) and x > 0 for x in runs):
            errors.append(f"{atom}: workflow evidence missing")

    atoms = data.get("atoms")
    if not isinstance(atoms, list):
        errors.append("atoms must be a list")
        atoms = []
    by_atom = {item.get("atom"): item for item in atoms if isinstance(item, dict)}
    if set(by_atom) != set(EXPECTED_ATOMS):
        errors.append("Phase-1 atom denominator mismatch")
    issue_ids = [item.get("issue") for item in atoms if isinstance(item, dict)]
    if len(issue_ids) != len(set(issue_ids)):
        errors.append("duplicate issue owner")

    for atom, (issue, lane, state, parent_pr) in EXPECTED_ATOMS.items():
        item = by_atom.get(atom)
        if item is None:
            continue
        if (item.get("issue"), item.get("lane"), item.get("state")) != (issue, lane, state):
            errors.append(f"{atom}: owner/lane/state drift")
        parent = item.get("git_parent")
        actual_pr = parent.get("pr") if isinstance(parent, dict) else None
        if actual_pr != parent_pr:
            errors.append(f"{atom}: false or missing Git parent")
        if isinstance(parent, dict) and not SHA40.fullmatch(str(parent.get("head_sha", ""))):
            errors.append(f"{atom}: invalid parent head")
        paths = item.get("planned_paths")
        if not isinstance(paths, list) or not paths:
            errors.append(f"{atom}: path lease absent")
        if not item.get("external_authorities"):
            errors.append(f"{atom}: external authority boundary absent")
        if not item.get("maximum_claim"):
            errors.append(f"{atom}: maximum claim absent")

    for left, right in FORBIDDEN_PATH_OVERLAP:
        left_paths = set(by_atom.get(left, {}).get("planned_paths", []))
        right_paths = set(by_atom.get(right, {}).get("planned_paths", []))
        if left_paths & right_paths:
            errors.append(f"{left}/{right}: path lease collision")

    l7 = by_atom.get("L7-USER", {})
    if not set(l7.get("completion_dependencies", [])) >= {165, 166, 167, 168}:
        errors.append("L7 must remain blocked on a live vertical slice")
    convergence = by_atom.get("P1-EVIDENCE", {})
    if set(convergence.get("completion_dependencies", [])) != {165, 166, 167, 168, 169, 170}:
        errors.append("P1 evidence denominator dependencies changed")
    if data.get("required_lanes") != EXPECTED_LANES:
        errors.append("live lane preimplementation states were promoted or denominator changed")
    if data.get("hard_laws") != EXPECTED_HARD_LAWS:
        errors.append("hard laws changed or incomplete")
    if data.get("local_handoff") != {
        "state": "ABSENT",
        "reason": "EXTERNAL_CAPABILITIES_AND_CONCRETE_COMMANDS_NOT_YET_BOUND",
    }:
        errors.append("Local Handoff must remain absent until concrete capabilities/commands exist")

    rendered = json.dumps(data, sort_keys=True).lower()
    for marker in FORBIDDEN_PUBLIC_TEXT:
        if marker in rendered:
            errors.append(f"forbidden public text: {marker}")

    return errors


def main(argv: list[str]) -> int:
    path = Path(argv[1]) if len(argv) > 1 else Path(
        "docs/workspace/live/implementation-preflight.json"
    )
    errors = verify(load(path))
    if errors:
        print("workspace live preflight: FAIL")
        for error in errors:
            print(f"- {error}")
        return 1
    print("workspace live preflight: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
