#!/usr/bin/env python3
"""Fail-closed Wave-1 implementation-surface lock verifier."""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_LOCK = ROOT / "docs/workspace/live/wave1/contract-lock.json"
SHA40 = re.compile(r"^[0-9a-f]{40}$")

EXPECTED_PARENT = {
    "issue": 172,
    "pull_request": 175,
    "head_sha": "b9049a2c1d781218b264ee82a817f69ed1580f92",
    "tree_sha": "37693d1aa5d6c95ffd7ea68714fc2dd934930abe",
    "state": "LIVE_EVIDENCE_PREIMPLEMENTATION_READY",
}
EXPECTED_REPOSITORIES = {
    "KAW-W2": ("ed3c/kotlin-auto-webview", "3294fb2b4d86fef91f3f2c63e28718c490147808", "923ed99356690020eb01f79255392d138083dc1a"),
    "KAW-W3": ("ed3c/kotlin-auto-webview", "95754e2a7ea6a09da030da3803313fe49641b677", "f9502cb346c6ac2f4a24449681bed5c86dcc836d"),
    "KAW-W4": ("ed3c/kotlin-auto-webview", "56eb824866e7e74d63a4297748c647cff738db51", "0a32759d10c4a08a1815026f9504145d2fbc7cad"),
    "KAW-W6": ("ed3c/kotlin-auto-webview", "c19d4e561cb09cb1c6c96c2b0f8df0c88b7d987b", "6278886726e5cbff5ea5a6f0c01d6581811b9077"),
    "BETTOR-MAIN": ("ed3c/bettor-arena", "65b7188ba57b0769419850db462bd92b5c834e00", "41f0ecdef0232114d9f339fbfd984e37e56f3dc5"),
    "TVL-MAIN": ("ed3c/truth-verify-loop", "f6e1b81d14d9ae7bfb0fe5f513f6f8d7322f5202", "f62e9596bef4e6ad745c11fb44dea2e56c9205f3"),
}
EXPECTED_FILES = {
    "GH-REST": ("ed3c/kotlin-auto-webview", "3294fb2b4d86fef91f3f2c63e28718c490147808", "composeApp/src/commonMain/kotlin/dev/ed3c/autowebview/workspace/github/GitHubRestMetadataSource.kt", "9b677be0c8bd1da0f1488acb83488677eae11a81"),
    "GH-CONTRACTS": ("ed3c/kotlin-auto-webview", "3294fb2b4d86fef91f3f2c63e28718c490147808", "composeApp/src/commonMain/kotlin/dev/ed3c/autowebview/workspace/github/GitHubWorkGraphContracts.kt", "60f0bc1474fe24fb4e35b814cd8a6e83214dbb7b"),
    "GH-ADAPTER": ("ed3c/kotlin-auto-webview", "3294fb2b4d86fef91f3f2c63e28718c490147808", "composeApp/src/commonMain/kotlin/dev/ed3c/autowebview/workspace/github/GitHubWorkGraphAdapter.kt", "15d512a6bd331b5ab05e4332dbd4b24db7f5f7df"),
    "GOOGLE-CONTRACTS": ("ed3c/kotlin-auto-webview", "95754e2a7ea6a09da030da3803313fe49641b677", "composeApp/src/commonMain/kotlin/dev/ed3c/autowebview/workspace/google/GoogleProjectionContracts.kt", "02485d957d4b3432ba4e3310d3949c9ae6a71b7f"),
    "GOOGLE-SAGA": ("ed3c/kotlin-auto-webview", "95754e2a7ea6a09da030da3803313fe49641b677", "composeApp/src/commonMain/kotlin/dev/ed3c/autowebview/workspace/google/GoogleProjectionSaga.kt", "37e91707786273af29b29208e2e42db9f1f13dac"),
    "FEDERATION-ROUTER": ("ed3c/kotlin-auto-webview", "56eb824866e7e74d63a4297748c647cff738db51", "composeApp/src/commonMain/kotlin/dev/ed3c/autowebview/workspace/routing/FederationRouter.kt", "d25f92d140fa1b7012345a921d52efa7af1d10e7"),
    "W6-VERIFIER": ("ed3c/kotlin-auto-webview", "c19d4e561cb09cb1c6c96c2b0f8df0c88b7d987b", "scripts/evidence/workspace/verify_federation_evidence.py", "cb7106be3a0681cd3931521bdfa165e80aaf1ede"),
    "BETTOR-GATEWAY-MANIFEST": ("ed3c/bettor-arena", "65b7188ba57b0769419850db462bd92b5c834e00", "loop_wiki/loopx-worker-gateway/contracts/manifest.json", "ed851c870b519184bf6b2ea258f291515d783271"),
    "BETTOR-WORKER-RECEIPT": ("ed3c/bettor-arena", "65b7188ba57b0769419850db462bd92b5c834e00", "loop_wiki/loopx-worker-gateway/contracts/worker-receipt.schema.json", "c9aded898d0108e550e8614b716251f3afef2cd5"),
    "TVL-SEMANTIC-RECEIPT": ("ed3c/truth-verify-loop", "f6e1b81d14d9ae7bfb0fe5f513f6f8d7322f5202", "schemas/semantic-verifier-receipt.v1.schema.json", "2c9085b66d080abcede3861812d15bdc92986d04"),
}
EXPECTED_LANES = {
    "L2-GH": (165, "L2", "CODE_PRESENT_LIVE_CANARY_ABSENT", 158),
    "L3-GOOGLE": (166, "L3", "PORT_PRESENT_LIVE_TRANSPORT_ABSENT", 160),
    "L4-BETTOR": (167, "L4", "PRODUCER_CONTRACT_PRESENT_CONSUMER_ABSENT", 161),
    "L5-DOMAIN": (168, "L5", "ROUTE_BINDING_PRESENT_RECEIPT_ADAPTER_ABSENT", 161),
}
EXPECTED_HARD_LAWS = {
    "chat_context_defines_interface": False,
    "mutable_branch_replaces_exact_commit": False,
    "path_replaces_blob_identity": False,
    "fixture_is_live": False,
    "credential_or_token_in_repository": False,
    "route_ack_is_execution": False,
    "receipt_reference_is_verdict_content": False,
    "google_account_access_is_content_right": False,
    "missing_external_consumer_is_ready": False,
}
SECRET_PATTERNS = (
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b"),
    re.compile(r"\bAIza[0-9A-Za-z_-]{20,}\b"),
    re.compile(r"\bya29\.[0-9A-Za-z._-]+\b"),
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
)
MUTABLE_URL_PARTS = ("/main/", "/master/", "/refs/heads/")


def load_lock(path: Path = DEFAULT_LOCK) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def git_blob_sha(payload: bytes) -> str:
    header = f"blob {len(payload)}\0".encode("ascii")
    return hashlib.sha1(header + payload).hexdigest()


def _walk_strings(value: Any):
    if isinstance(value, dict):
        for key, child in value.items():
            yield str(key)
            yield from _walk_strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_strings(child)
    elif isinstance(value, str):
        yield value


def _fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "kaw-wave1-contract-lock"})
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
        request.add_header("X-GitHub-Api-Version", "2022-11-28")
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def verify_lock(lock: dict[str, Any], verify_remote: bool = False) -> list[str]:
    errors: list[str] = []

    if lock.get("schema_version") != 1:
        errors.append("schema_version must be 1")
    if lock.get("program") != "FEDERATED_CAPABILITY_WORKSPACE_LIVE_WAVE1":
        errors.append("program identity mismatch")
    if lock.get("owner_issue") != 176:
        errors.append("owner issue must be #176")
    if lock.get("parent") != EXPECTED_PARENT:
        errors.append("parent exact subject drift")

    subjects = lock.get("repository_subjects")
    if not isinstance(subjects, list):
        errors.append("repository_subjects must be a list")
        subjects = []
    subject_map = {item.get("subject_id"): item for item in subjects if isinstance(item, dict)}
    if set(subject_map) != set(EXPECTED_REPOSITORIES):
        errors.append("repository subject denominator mismatch")
    for subject_id, expected in EXPECTED_REPOSITORIES.items():
        item = subject_map.get(subject_id)
        if item is None:
            continue
        actual = (item.get("repository"), item.get("commit_sha"), item.get("tree_sha"))
        if actual != expected:
            errors.append(f"{subject_id}: repository/commit/tree drift")
        if not SHA40.fullmatch(str(item.get("commit_sha", ""))):
            errors.append(f"{subject_id}: invalid commit SHA")
        if not SHA40.fullmatch(str(item.get("tree_sha", ""))):
            errors.append(f"{subject_id}: invalid tree SHA")

    files = lock.get("source_files")
    if not isinstance(files, list):
        errors.append("source_files must be a list")
        files = []
    file_map = {item.get("surface_id"): item for item in files if isinstance(item, dict)}
    if set(file_map) != set(EXPECTED_FILES):
        errors.append("source-file denominator mismatch")
    for surface_id, expected in EXPECTED_FILES.items():
        item = file_map.get(surface_id)
        if item is None:
            continue
        actual = (item.get("repository"), item.get("commit_sha"), item.get("path"), item.get("blob_sha"))
        if actual != expected:
            errors.append(f"{surface_id}: exact file subject drift")
        raw_url = item.get("raw_url")
        expected_url = f"https://raw.githubusercontent.com/{expected[0]}/{expected[1]}/{expected[2]}"
        if raw_url != expected_url:
            errors.append(f"{surface_id}: raw URL is not exact-commit bound")
        if isinstance(raw_url, str) and any(part in raw_url for part in MUTABLE_URL_PARTS):
            errors.append(f"{surface_id}: mutable raw URL forbidden")
        symbols = item.get("required_symbols")
        if not isinstance(symbols, list) or not symbols or any(not isinstance(symbol, str) or not symbol for symbol in symbols):
            errors.append(f"{surface_id}: required_symbols must be non-empty strings")

    lanes = lock.get("lanes")
    if not isinstance(lanes, list):
        errors.append("lanes must be a list")
        lanes = []
    lane_map = {item.get("atom"): item for item in lanes if isinstance(item, dict)}
    if set(lane_map) != set(EXPECTED_LANES):
        errors.append("Wave-1 lane denominator mismatch")
    all_paths: dict[str, str] = {}
    for atom, (issue, lane_id, state, parent_pr) in EXPECTED_LANES.items():
        item = lane_map.get(atom)
        if item is None:
            continue
        if (item.get("issue"), item.get("lane"), item.get("state")) != (issue, lane_id, state):
            errors.append(f"{atom}: issue/lane/state mismatch")
        parent = item.get("git_parent")
        if not isinstance(parent, dict) or parent.get("pull_request") != parent_pr:
            errors.append(f"{atom}: false or missing Git parent")
        if not isinstance(item.get("existing"), list) or not item["existing"]:
            errors.append(f"{atom}: existing surface list missing")
        if not isinstance(item.get("missing"), list) or not item["missing"]:
            errors.append(f"{atom}: missing surface list missing")
        capabilities = item.get("external_capabilities")
        if not isinstance(capabilities, list) or not capabilities:
            errors.append(f"{atom}: external capability boundary missing")
        start = item.get("start_receipt")
        if not isinstance(start, dict):
            errors.append(f"{atom}: start receipt missing")
        else:
            if start.get("code_ready") is not True:
                errors.append(f"{atom}: code preparation is not marked ready")
            if start.get("external_ready") is not False:
                errors.append(f"{atom}: external authority falsely marked ready")
            if start.get("live_evidence_ready") is not False:
                errors.append(f"{atom}: live evidence falsely marked ready")
        if item.get("maximum_claim") != "IMPLEMENTATION_PREP_ONLY":
            errors.append(f"{atom}: maximum claim widened")
        paths = item.get("first_safe_write")
        if not isinstance(paths, list) or not paths:
            errors.append(f"{atom}: first_safe_write missing")
        else:
            for path in paths:
                previous = all_paths.get(path)
                if previous is not None:
                    errors.append(f"path lease collision: {path} used by {previous} and {atom}")
                all_paths[path] = atom

    l2 = lane_map.get("L2-GH", {})
    if "Ktor read-only public api.github.com transport" not in l2.get("existing", []):
        errors.append("L2-GH: existing REST transport was lost or scheduled for duplication")
    if l2.get("start_receipt", {}).get("public_canary_ready") is not True:
        errors.append("L2-GH: public unauthenticated live canary should remain startable")
    l3 = lane_map.get("L3-GOOGLE", {})
    if "Drive/Docs/Sheets live transport" not in l3.get("missing", []):
        errors.append("L3-GOOGLE: absent live transport was hidden")
    l4 = lane_map.get("L4-BETTOR", {})
    if l4.get("external_issue") != "ed3c/bettor-arena#197":
        errors.append("L4-BETTOR: consumer owner drift")
    if "Bettor capability-workspace consumer module" not in l4.get("missing", []):
        errors.append("L4-BETTOR: absent consumer was hidden")
    l5 = lane_map.get("L5-DOMAIN", {})
    if l5.get("external_issue") != "ed3c/truth-verify-loop#47":
        errors.append("L5-DOMAIN: authority owner drift")
    if "KAW domain receipt-reference adapter" not in l5.get("missing", []):
        errors.append("L5-DOMAIN: absent KAW adapter was hidden")

    if lock.get("hard_laws") != EXPECTED_HARD_LAWS:
        errors.append("hard_laws changed or incomplete")
    if lock.get("local_handoff") != {"state": "ABSENT", "reason": "EXTERNAL_CAPABILITIES_AND_CONCRETE_COMMANDS_NOT_BOUND"}:
        errors.append("Local Handoff must remain ABSENT until concrete capabilities and commands exist")
    if lock.get("excluded_void_issues") != [173, 174]:
        errors.append("connector-misfire issue exclusion drift")
    if lock.get("stage_verdict") not in {"LIVE_WAVE1_IMPLEMENTATION_SURFACES_LOCKED_PENDING_CI", "LIVE_WAVE1_IMPLEMENTATION_SURFACES_LOCKED"}:
        errors.append("unsupported stage verdict")

    for text in _walk_strings(lock):
        for pattern in SECRET_PATTERNS:
            if pattern.search(text):
                errors.append("public contract lock contains credential-like material")
                break

    if verify_remote and not errors:
        for subject_id, (repository, commit_sha, tree_sha) in EXPECTED_REPOSITORIES.items():
            try:
                payload = json.loads(_fetch(f"https://api.github.com/repos/{repository}/git/commits/{commit_sha}"))
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
                errors.append(f"{subject_id}: remote commit read failed: {type(exc).__name__}")
                continue
            if payload.get("sha") != commit_sha or payload.get("tree", {}).get("sha") != tree_sha:
                errors.append(f"{subject_id}: remote commit/tree read-back mismatch")

        for surface_id, item in file_map.items():
            try:
                payload = _fetch(item["raw_url"])
            except (urllib.error.URLError, TimeoutError) as exc:
                errors.append(f"{surface_id}: remote file read failed: {type(exc).__name__}")
                continue
            if git_blob_sha(payload) != item.get("blob_sha"):
                errors.append(f"{surface_id}: remote Git blob digest mismatch")
                continue
            text = payload.decode("utf-8")
            for symbol in item.get("required_symbols", []):
                if symbol not in text:
                    errors.append(f"{surface_id}: required symbol absent: {symbol}")

        manifest = file_map.get("BETTOR-GATEWAY-MANIFEST")
        if manifest is not None:
            try:
                data = json.loads(_fetch(manifest["raw_url"]))
                if data.get("fixture_only") is not True or data.get("live_matrix_state") != "NOT_EXERCISED":
                    errors.append("Bettor gateway live state was falsely widened")
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
                errors.append(f"Bettor manifest semantic read failed: {type(exc).__name__}")

        receipt = file_map.get("TVL-SEMANTIC-RECEIPT")
        if receipt is not None:
            try:
                data = json.loads(_fetch(receipt["raw_url"]))
                schema_const = data.get("properties", {}).get("schema", {}).get("const")
                if schema_const != "tvl.semantic-verifier-receipt.v1":
                    errors.append("Truth Verify receipt schema identity mismatch")
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
                errors.append(f"Truth Verify schema semantic read failed: {type(exc).__name__}")

    return errors


def main(argv: list[str]) -> int:
    verify_remote = "--verify-remote" in argv
    paths = [arg for arg in argv[1:] if not arg.startswith("--")]
    path = Path(paths[0]) if paths else DEFAULT_LOCK
    try:
        lock = load_lock(path)
    except (OSError, json.JSONDecodeError) as exc:
        print(f"workspace live Wave-1 contract lock: FAIL\n- cannot read lock: {exc}")
        return 1
    errors = verify_lock(lock, verify_remote=verify_remote)
    if errors:
        print("workspace live Wave-1 contract lock: FAIL")
        for error in errors:
            print(f"- {error}")
        return 1
    print("workspace live Wave-1 contract lock: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
