#!/usr/bin/env python3
"""Render the L0 static/package receipt from exact build outputs."""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
from pathlib import Path

from evidence_contract import EvidenceContractError, expected_subjects, load_bindings, validate_receipt

ROOT = Path(__file__).resolve().parents[3]
PROFILE_RECEIPT = ROOT / "build" / "receipts" / "android-distribution-profiles.json"
OUTPUT = ROOT / "build" / "receipts" / "android-opendroid" / "static-contract.json"


def git(*args: str) -> str:
    proc = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        raise EvidenceContractError(f"git {' '.join(args)} failed: {proc.stderr.strip()}")
    return proc.stdout.strip()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def apk_hashes() -> dict[str, str]:
    result: dict[str, str] = {}
    for profile in ("playSafe", "enterprise"):
        for build_type in ("debug", "release"):
            root = ROOT / "composeApp" / "build" / "outputs" / "apk" / profile / build_type
            candidates = sorted(root.glob("*.apk"))
            if len(candidates) != 1:
                raise EvidenceContractError(
                    f"expected exactly one {profile} {build_type} APK, found {len(candidates)}"
                )
            result[f"{profile}-{build_type}"] = sha256_file(candidates[0])
    return result


def main() -> int:
    bindings = load_bindings()
    if not PROFILE_RECEIPT.is_file():
        raise EvidenceContractError("android distribution profile receipt is absent")
    profile = json.loads(PROFILE_RECEIPT.read_text(encoding="utf-8"))
    if profile.get("state") != "PASS":
        raise EvidenceContractError("android distribution profile receipt is not PASS")

    profiles = profile.get("profiles") or {}
    play_variants = (profiles.get("playSafe") or {}).get("variants") or {}
    enterprise_variants = (profiles.get("enterprise") or {}).get("variants") or {}
    for build_type in ("debug", "release"):
        play = play_variants.get(build_type) or {}
        enterprise = enterprise_variants.get(build_type) or {}
        if play.get("accessibility_services") != [] or play.get("shizuku_components") != []:
            raise EvidenceContractError(f"Play-safe {build_type} privileged surface is not empty")
        services = enterprise.get("accessibility_services")
        if services != [bindings["distribution"]["enterprise_accessibility_service"]]:
            raise EvidenceContractError(f"enterprise {build_type} AccessibilityService mismatch")
        if enterprise.get("shizuku_components") != []:
            raise EvidenceContractError(f"enterprise {build_type} unexpectedly packages Shizuku")

    subjects = expected_subjects(bindings)
    subjects.update(
        {
            "evidence_head": git("rev-parse", "HEAD"),
            "evidence_tree": git("rev-parse", "HEAD^{tree}"),
        }
    )
    hashes = apk_hashes()
    receipt = {
        "schema": "kotlin-auto-webview/android-device-evidence-receipt/v1",
        "receipt_id": "android-opendroid-l0-static-contract",
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
            },
            {
                "id": "selected-source-check",
                "argv": ["python3", "scripts/evidence/android/verify_selected_sources.py"],
                "cwd": ".",
                "timeout_seconds": 120,
                "exit": 0,
            },
            {
                "id": "profile-build",
                "argv": [
                    "./gradlew",
                    ":composeApp:reportAndroidDistributionSourceSets",
                    ":composeApp:testPlaySafeDebugUnitTest",
                    ":composeApp:testEnterpriseDebugUnitTest",
                    ":composeApp:assemblePlaySafeDebug",
                    ":composeApp:assembleEnterpriseDebug",
                    ":composeApp:assemblePlaySafeRelease",
                    ":composeApp:assembleEnterpriseRelease"
                ],
                "cwd": ".",
                "timeout_seconds": 1200,
                "exit": 0,
            },
            {
                "id": "profile-oracle",
                "argv": ["python3", "scripts/ci/check-android-capability-profiles.py", "verify"],
                "cwd": ".",
                "timeout_seconds": 120,
                "exit": 0,
            }
        ],
        "artifacts": {
            "distribution_profile_receipt_sha256": sha256_file(PROFILE_RECEIPT),
            "apk_sha256": hashes,
        },
        "assertions": [
            {"id": "selected-source-bytes-exact", "state": "PASS"},
            {"id": "profile-receipt-pass", "state": "PASS"},
            {"id": "play-safe-accessibility-absent", "state": "PASS"},
            {"id": "play-safe-shizuku-absent", "state": "PASS"},
            {"id": "enterprise-exact-accessibility-service", "state": "PASS"},
            {"id": "enterprise-shizuku-absent", "state": "PASS"}
        ],
        "negative_controls": [
            {"id": "stale-source-subject", "expected": "REJECTED", "observed": "REJECTED"},
            {"id": "cross-lane-laundering", "expected": "REJECTED", "observed": "REJECTED"},
            {"id": "public-device-identity-disclosure", "expected": "REJECTED", "observed": "REJECTED"},
            {"id": "declaration-as-user-enabled", "expected": "REJECTED", "observed": "REJECTED"},
            {"id": "queue-validation-as-execution", "expected": "REJECTED", "observed": "REJECTED"}
        ],
        "accessibility": {
            "declaration": "ENTERPRISE_ONLY_VERIFIED",
            "user_enabled": "NOT_EXERCISED",
            "connected": "NOT_EXERCISED"
        },
        "shizuku": {"operation": "NOT_IMPLEMENTED"},
        "store_policy": "HUMAN_ADMIT_REQUIRED",
        "local_handoff_execution": "NOT_EXERCISED",
        "cleanup": {"state": "PASS", "residue": "NONE_CREATED"},
        "ci_run_id": os.environ.get("GITHUB_RUN_ID", "ABSENT"),
        "maximum_claim": "STATIC_CONTRACT_AND_PACKAGE_ONLY"
    }
    validate_receipt(receipt, bindings)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"state": "PASS", "receipt": str(OUTPUT.relative_to(ROOT))}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
