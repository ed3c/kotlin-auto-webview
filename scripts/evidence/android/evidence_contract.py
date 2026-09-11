#!/usr/bin/env python3
"""Fail-closed public evidence contract for kotlin-auto-webview Android automation.

This module is intentionally standard-library only. It validates exact source/runtime subjects,
evidence-lane identity, public-disclosure constraints, negative-control presence, cleanup truth and
Human/local authority boundaries. It never executes a device command.
"""

from __future__ import annotations

import copy
import json
import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[3]
BINDINGS_PATH = ROOT / "integrations" / "opendroid" / "fixtures" / "android-device-evidence-bindings.json"

SCHEMA = "kotlin-auto-webview/android-device-evidence-receipt/v1"
SHA40 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")

ALLOWED_STATES = {
    "PASS",
    "FAIL",
    "ABSENT",
    "NOT_EXERCISED",
    "SKIPPED_BY_POLICY",
    "NOT_IMPLEMENTED",
    "HUMAN_ADMIT_REQUIRED",
}

LANE_CARRIER = {
    "L0_STATIC_CONTRACT": "STATIC",
    "L1_LOCAL_DETERMINISTIC": "LOCAL",
    "L2_EMULATOR": "EMULATOR",
    "L3_PHYSICAL_DEVICE": "PHYSICAL_DEVICE",
    "L4_PRIVILEGED_DEVICE": "PRIVILEGED_DEVICE",
    "L5_STORE_POLICY": "STORE_POLICY",
    "L6_HUMAN_ADMIT": "HUMAN",
}

MAXIMUM_CLAIM = {
    "L0_STATIC_CONTRACT": "STATIC_CONTRACT_AND_PACKAGE_ONLY",
    "L1_LOCAL_DETERMINISTIC": "LOCAL_DETERMINISTIC_ONLY",
    "L2_EMULATOR": "ANDROID_EMULATOR_FIXTURE_ONLY",
    "L3_PHYSICAL_DEVICE": "ANDROID_PHYSICAL_DEVICE_FIXTURE_ONLY",
    "L4_PRIVILEGED_DEVICE": "ANDROID_PRIVILEGED_DEVICE_FIXTURE_ONLY",
    "L5_STORE_POLICY": "STORE_POLICY_EXTERNAL_ONLY",
    "L6_HUMAN_ADMIT": "HUMAN_ADMIT_ONLY",
}

FORBIDDEN_PUBLIC_KEY_FRAGMENTS = {
    "device_serial",
    "serial_number",
    "raw_dom",
    "raw_ui",
    "raw_text",
    "screenshot",
    "target_token",
    "token_value",
    "credential_value",
    "secret_value",
    "private_path",
    "private_endpoint",
}

ABSOLUTE_PRIVATE_PATH = re.compile(r"(?:^|[\s\"'])(?:/home/|/Users/|[A-Za-z]:\\\\Users\\\\)")


class EvidenceContractError(ValueError):
    pass


def load_bindings() -> dict[str, Any]:
    try:
        value = json.loads(BINDINGS_PATH.read_text(encoding="utf-8"))
    except Exception as exc:  # pragma: no cover - surfaced as contract failure
        raise EvidenceContractError(f"cannot read bindings: {exc}") from exc
    if not isinstance(value, dict):
        raise EvidenceContractError("bindings must be a JSON object")
    validate_bindings(value)
    return value


def validate_bindings(bindings: dict[str, Any]) -> None:
    if bindings.get("schema") != "kotlin-auto-webview/android-device-evidence-bindings/v1":
        raise EvidenceContractError("bindings schema mismatch")
    if bindings.get("repository") != "ed3c/kotlin-auto-webview" or bindings.get("issue") != 74:
        raise EvidenceContractError("bindings repository/issue mismatch")

    git_parent = require_object(bindings, "git_parent")
    require_sha40(git_parent.get("head"), "git parent head")
    require_sha40(git_parent.get("tree"), "git parent tree")
    if git_parent.get("issue") != 71:
        raise EvidenceContractError("#74 git parent must remain #71")

    convergence = require_object(bindings, "source_convergence")
    require_sha40(convergence.get("commit"), "convergence commit")
    require_sha40(convergence.get("tree"), "convergence tree")
    if convergence.get("relation") != "process-convergence-snapshot":
        raise EvidenceContractError("source convergence relation mismatch")
    parents = convergence.get("process_parents")
    if not isinstance(parents, list) or [p.get("issue") for p in parents] != [72, 73]:
        raise EvidenceContractError("process parents must be exact #72/#73 sibling selections")
    for parent in parents:
        require_sha40(parent.get("head"), "process parent head")
        require_sha40(parent.get("tree"), "process parent tree")

    integrated = require_object(bindings, "integrated_runtime")
    if integrated.get("issue") != 70:
        raise EvidenceContractError("integrated runtime issue mismatch")
    require_sha40(integrated.get("head"), "integrated runtime head")
    require_sha40(integrated.get("tree"), "integrated runtime tree")

    runtime_env = require_object(bindings, "runtime_env")
    if runtime_env.get("repository") != "ed3c/runtime-env" or runtime_env.get("issue") != 62:
        raise EvidenceContractError("runtime-env identity mismatch")
    require_sha40(runtime_env.get("head"), "runtime-env head")
    require_sha40(runtime_env.get("tree"), "runtime-env tree")
    if runtime_env.get("state") not in {
        "PREPARATION_COMPLETE_WITH_GREEN_CONTRACT_RECEIPT",
        "RUNTIME_CONTRACT_COMPLETE",
    }:
        raise EvidenceContractError("runtime-env state is outside the admitted contract states")

    distribution = require_object(bindings, "distribution")
    if distribution.get("shizuku_operation") != "NOT_IMPLEMENTED":
        raise EvidenceContractError("#73 did not admit a Shizuku operation")
    if bindings.get("emulator_api_allowlist") != [24, 28, 33, 36]:
        raise EvidenceContractError("emulator API allowlist drift")
    if bindings.get("evidence_lanes") != list(LANE_CARRIER):
        raise EvidenceContractError("evidence lane registry drift")


def expected_subjects(bindings: dict[str, Any]) -> dict[str, str]:
    convergence = bindings["source_convergence"]
    play_safe, enterprise = convergence["process_parents"]
    runtime_env = bindings["runtime_env"]
    integrated = bindings["integrated_runtime"]
    return {
        "source_convergence_commit": convergence["commit"],
        "source_convergence_tree": convergence["tree"],
        "play_safe_head": play_safe["head"],
        "play_safe_tree": play_safe["tree"],
        "enterprise_head": enterprise["head"],
        "enterprise_tree": enterprise["tree"],
        "integrated_runtime_head": integrated["head"],
        "integrated_runtime_tree": integrated["tree"],
        "runtime_env_head": runtime_env["head"],
        "runtime_env_tree": runtime_env["tree"],
    }


def validate_receipt(receipt: dict[str, Any], bindings: dict[str, Any] | None = None) -> None:
    if bindings is None:
        bindings = load_bindings()
    if receipt.get("schema") != SCHEMA:
        raise EvidenceContractError("receipt schema mismatch")
    if receipt.get("repository") != "ed3c/kotlin-auto-webview":
        raise EvidenceContractError("receipt repository mismatch")

    state = receipt.get("state")
    if state not in ALLOWED_STATES:
        raise EvidenceContractError("invalid evidence state")
    lane = receipt.get("lane")
    if lane not in LANE_CARRIER:
        raise EvidenceContractError("invalid evidence lane")

    carrier = require_object(receipt, "carrier")
    if carrier.get("class") != LANE_CARRIER[lane]:
        raise EvidenceContractError("carrier class cannot satisfy a different evidence lane")
    api = carrier.get("api_level")
    if lane == "L2_EMULATOR" and api not in bindings["emulator_api_allowlist"]:
        raise EvidenceContractError("emulator API is not in the admitted matrix")
    if lane != "L2_EMULATOR" and api is not None and not isinstance(api, int):
        raise EvidenceContractError("carrier api_level must be integer or null")

    subjects = require_object(receipt, "subjects")
    expected = expected_subjects(bindings)
    for key, value in expected.items():
        if subjects.get(key) != value:
            raise EvidenceContractError(f"stale/wrong subject: {key}")
    require_sha40(subjects.get("evidence_head"), "evidence head")
    require_sha40(subjects.get("evidence_tree"), "evidence tree")

    commands = receipt.get("commands")
    if not isinstance(commands, list) or not commands:
        raise EvidenceContractError("receipt must bind at least one exact command")
    for command in commands:
        if not isinstance(command, dict):
            raise EvidenceContractError("command receipt must be an object")
        if not bounded_identifier(command.get("id")):
            raise EvidenceContractError("command id is not bounded")
        argv = command.get("argv")
        if not isinstance(argv, list) or not argv or not all(isinstance(v, str) and 0 < len(v) <= 512 for v in argv):
            raise EvidenceContractError("command argv is not exact/bounded")
        if command.get("cwd") != ".":
            raise EvidenceContractError("public command cwd must remain repository-relative")
        timeout_seconds = command.get("timeout_seconds")
        if not isinstance(timeout_seconds, int) or not 1 <= timeout_seconds <= 1800:
            raise EvidenceContractError("command timeout is outside the bounded contract")
        if not isinstance(command.get("exit"), int):
            raise EvidenceContractError("command exit must be recorded")

    assertions = receipt.get("assertions")
    if not isinstance(assertions, list) or not assertions:
        raise EvidenceContractError("receipt requires explicit assertions")
    if state == "PASS" and any(item.get("state") != "PASS" for item in assertions if isinstance(item, dict)):
        raise EvidenceContractError("PASS receipt contains a non-PASS claimed assertion")

    controls = receipt.get("negative_controls")
    if not isinstance(controls, list) or not controls:
        raise EvidenceContractError("PASS denominator requires planted negative controls")
    for control in controls:
        if not isinstance(control, dict) or control.get("expected") != "REJECTED" or control.get("observed") != "REJECTED":
            raise EvidenceContractError("negative control was not observed rejecting")

    accessibility = require_object(receipt, "accessibility")
    if lane in {"L0_STATIC_CONTRACT", "L1_LOCAL_DETERMINISTIC", "L2_EMULATOR"}:
        if accessibility.get("user_enabled") not in {"NOT_EXERCISED", "ABSENT", "HUMAN_ADMIT_REQUIRED"}:
            raise EvidenceContractError("automated lane cannot claim Human Accessibility enablement")
        if accessibility.get("connected") not in {"NOT_EXERCISED", "ABSENT", "HUMAN_ADMIT_REQUIRED"}:
            raise EvidenceContractError("automated lane cannot launder Accessibility connection")

    shizuku = require_object(receipt, "shizuku")
    if shizuku.get("operation") != "NOT_IMPLEMENTED":
        raise EvidenceContractError("Shizuku operation must remain NOT_IMPLEMENTED for selected #73")

    if lane != "L5_STORE_POLICY" and receipt.get("store_policy") != "HUMAN_ADMIT_REQUIRED":
        raise EvidenceContractError("non-store lane cannot claim Play/store approval")
    if receipt.get("local_handoff_execution") != "NOT_EXERCISED":
        raise EvidenceContractError("queue/readiness validation cannot claim Local Handoff execution")

    cleanup = require_object(receipt, "cleanup")
    if state == "PASS" and cleanup.get("state") != "PASS":
        raise EvidenceContractError("PASS receipt requires residue/cleanup PASS")

    if receipt.get("maximum_claim") != MAXIMUM_CLAIM[lane]:
        raise EvidenceContractError("maximum claim does not match literal evidence lane")

    validate_public_disclosure(receipt)


def validate_public_disclosure(value: Any, path: str = "$") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            lower = str(key).lower()
            if any(fragment in lower for fragment in FORBIDDEN_PUBLIC_KEY_FRAGMENTS):
                raise EvidenceContractError(f"forbidden public receipt field at {path}.{key}")
            validate_public_disclosure(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            validate_public_disclosure(child, f"{path}[{index}]")
    elif isinstance(value, str):
        if ABSOLUTE_PRIVATE_PATH.search(value):
            raise EvidenceContractError(f"private absolute path disclosed at {path}")
        if len(value) > 4096:
            raise EvidenceContractError(f"unbounded public string at {path}")


def require_object(parent: dict[str, Any], key: str) -> dict[str, Any]:
    value = parent.get(key)
    if not isinstance(value, dict):
        raise EvidenceContractError(f"{key} must be an object")
    return value


def require_sha40(value: Any, field: str) -> None:
    if not isinstance(value, str) or SHA40.fullmatch(value) is None:
        raise EvidenceContractError(f"{field} must be exact SHA-1")


def require_sha256(value: Any, field: str) -> None:
    if not isinstance(value, str) or SHA256.fullmatch(value) is None:
        raise EvidenceContractError(f"{field} must be SHA-256")


def bounded_identifier(value: Any) -> bool:
    return isinstance(value, str) and 1 <= len(value) <= 128 and re.fullmatch(r"[A-Za-z0-9._:-]+", value) is not None


def valid_fixture(bindings: dict[str, Any]) -> dict[str, Any]:
    subjects = expected_subjects(bindings)
    subjects.update({
        "evidence_head": "1" * 40,
        "evidence_tree": "2" * 40,
    })
    return {
        "schema": SCHEMA,
        "repository": "ed3c/kotlin-auto-webview",
        "state": "PASS",
        "lane": "L0_STATIC_CONTRACT",
        "subjects": subjects,
        "carrier": {"class": "STATIC", "api_level": None, "image": None},
        "commands": [
            {
                "id": "evidence-contract-self-test",
                "argv": ["python3", "scripts/evidence/android/evidence_contract.py"],
                "cwd": ".",
                "timeout_seconds": 30,
                "exit": 0,
            }
        ],
        "assertions": [{"id": "schema-bound", "state": "PASS"}],
        "negative_controls": [{"id": "wrong-lane", "expected": "REJECTED", "observed": "REJECTED"}],
        "accessibility": {
            "declaration": "ENTERPRISE_ONLY_VERIFIED",
            "user_enabled": "NOT_EXERCISED",
            "connected": "NOT_EXERCISED",
        },
        "shizuku": {"operation": "NOT_IMPLEMENTED"},
        "store_policy": "HUMAN_ADMIT_REQUIRED",
        "local_handoff_execution": "NOT_EXERCISED",
        "cleanup": {"state": "PASS", "residue": "NONE_CREATED"},
        "maximum_claim": "STATIC_CONTRACT_AND_PACKAGE_ONLY",
    }


def expect_rejected(name: str, receipt: dict[str, Any], bindings: dict[str, Any]) -> None:
    try:
        validate_receipt(receipt, bindings)
    except EvidenceContractError:
        return
    raise EvidenceContractError(f"negative control did not reject: {name}")


def self_test() -> None:
    bindings = load_bindings()
    base = valid_fixture(bindings)
    validate_receipt(base, bindings)

    mutated = copy.deepcopy(base)
    mutated["subjects"]["play_safe_head"] = "f" * 40
    expect_rejected("stale-source-subject", mutated, bindings)

    mutated = copy.deepcopy(base)
    mutated["lane"] = "L3_PHYSICAL_DEVICE"
    expect_rejected("emulator-or-static-lane-laundering", mutated, bindings)

    mutated = copy.deepcopy(base)
    mutated["device_serial"] = "forbidden"
    expect_rejected("device-serial-disclosure", mutated, bindings)

    mutated = copy.deepcopy(base)
    mutated["negative_controls"] = []
    expect_rejected("missing-negative-denominator", mutated, bindings)

    mutated = copy.deepcopy(base)
    mutated["shizuku"]["operation"] = "PASS"
    expect_rejected("shizuku-presence-as-operation", mutated, bindings)

    mutated = copy.deepcopy(base)
    mutated["local_handoff_execution"] = "EXECUTED"
    expect_rejected("queue-validation-as-execution", mutated, bindings)

    mutated = copy.deepcopy(base)
    mutated["store_policy"] = "PASS"
    expect_rejected("package-as-store-policy", mutated, bindings)

    mutated = copy.deepcopy(base)
    mutated["accessibility"]["user_enabled"] = "ENABLED"
    expect_rejected("declaration-as-user-enabled", mutated, bindings)

    mutated = copy.deepcopy(base)
    mutated["maximum_claim"] = "ANDROID_PHYSICAL_DEVICE_FIXTURE_ONLY"
    expect_rejected("cross-lane-maximum-claim", mutated, bindings)

    mutated = copy.deepcopy(base)
    mutated["commands"][0]["cwd"] = "/home/runner/work/private"
    expect_rejected("private-cwd-disclosure", mutated, bindings)

    print("android evidence contract self-test: PASS")


if __name__ == "__main__":
    self_test()
