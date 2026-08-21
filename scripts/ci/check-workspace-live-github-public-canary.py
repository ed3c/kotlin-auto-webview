#!/usr/bin/env python3
"""Fail-closed verifier for the L2 public GitHub canary implementation preparation."""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = ROOT / "docs/workspace/live/github-public-canary/canary-contract.json"
SCHEMA_PATH = ROOT / "schemas/workspace/live-github-public-canary.schema.json"

FORBIDDEN_VALUE_PATTERNS = (
    re.compile(r"github_pat_[A-Za-z0-9_]+"),
    re.compile(r"gh[pousr]_[A-Za-z0-9]+"),
    re.compile(r"Bearer\s+", re.IGNORECASE),
    re.compile(r"Authorization\s*:", re.IGNORECASE),
    re.compile(r"session[_-]?cookie", re.IGNORECASE),
    re.compile(r"refresh[_-]?token", re.IGNORECASE),
)
EXPECTED_PARENT = {
    "issue": 176,
    "pull_request": 177,
    "head_sha": "a7032eaf4f5a3ffaece06d01657897ad0444344a",
    "tree_sha": "e49575deb635d206bf78d0070783fca5865ab5fd",
    "relation": "EVIDENCE_DEPENDENCY",
}
EXPECTED_GIT_PARENT = {
    "pull_request": 158,
    "head_sha": "3294fb2b4d86fef91f3f2c63e28718c490147808",
    "tree_sha": "923ed99356690020eb01f79255392d138083dc1a",
    "branch": "feat/workspace-github-workgraph",
}
EXPECTED_REPOSITORY = {
    "full_name": "ed3c/kotlin-auto-webview",
    "id": 1334777764,
    "node_id": "R_kgDOT48XpA",
    "visibility": "public",
    "default_branch": "main",
}
EXPECTED_ISSUE = {
    "number": 165,
    "id": 5206660666,
    "node_id": "I_kwDOT48XpM8AAAABNldWOg",
    "state": "open",
}
EXPECTED_PR = {
    "number": 177,
    "id": 4325936842,
    "node_id": "PR_kwDOT48XpM8AAAABAdiOyg",
    "state": "open",
    "draft": True,
    "head_sha": "a7032eaf4f5a3ffaece06d01657897ad0444344a",
    "base_sha": "b9049a2c1d781218b264ee82a817f69ed1580f92",
}
EXPECTED_COMMIT = {
    "sha": "a7032eaf4f5a3ffaece06d01657897ad0444344a",
    "tree_sha": "e49575deb635d206bf78d0070783fca5865ab5fd",
}
EXPECTED_RUNS = {
    32409680210: ("Workspace Live Wave-1", "success"),
    32409680188: ("Workspace Live Preflight", "success"),
    32409680201: ("CI", "success"),
}
EXPECTED_JOBS = {
    96556935225: ("contract-lock", 32409680210, "success"),
    96556941355: ("preflight", 32409680188, "success"),
    96556935271: ("common-web-desktop", 32409680201, "success"),
    96556935495: ("android", 32409680201, "success"),
    96556935695: ("ios", 32409680201, "success"),
}
EXPECTED_TRANSPORT_FILES = {
    "composeApp/src/commonMain/kotlin/dev/ed3c/autowebview/workspace/github/GitHubRestMetadataSource.kt": (
        "3294fb2b4d86fef91f3f2c63e28718c490147808",
        "9b677be0c8bd1da0f1488acb83488677eae11a81",
        {"GitHubTokenProvider", "GitHubApiEndpoint", "GitHubRestMetadataSource", "GitHubMetadataSource"},
    ),
    "composeApp/src/commonMain/kotlin/dev/ed3c/autowebview/workspace/github/GitHubWorkGraphContracts.kt": (
        "3294fb2b4d86fef91f3f2c63e28718c490147808",
        "60f0bc1474fe24fb4e35b814cd8a6e83214dbb7b",
        {"GitHubWorkGraphRequest", "GitHubWorkGraphSnapshot", "GitHubReadResult", "GitHubMetadataSource"},
    ),
    "composeApp/src/commonMain/kotlin/dev/ed3c/autowebview/workspace/github/GitHubWorkGraphAdapter.kt": (
        "3294fb2b4d86fef91f3f2c63e28718c490147808",
        "15d512a6bd331b5ab05e4332dbd4b24db7f5f7df",
        {"GitHubWorkGraphAdapter", "GitHubWorkGraphMapper"},
    ),
}


class ContractError(RuntimeError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


def _load(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _walk_values(value: Any):
    if isinstance(value, dict):
        for child in value.values():
            yield from _walk_values(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_values(child)
    elif isinstance(value, str):
        yield value


def validate_model(contract: dict[str, Any], schema: dict[str, Any]) -> None:
    _require(schema.get("$schema") == "https://json-schema.org/draft/2020-12/schema", "schema draft drift")
    _require(schema.get("additionalProperties") is False, "root schema must fail closed")
    _require(contract.get("schema_version") == 1, "schema version drift")
    _require(contract.get("program") == "FEDERATED_CAPABILITY_WORKSPACE_L2_PUBLIC_GITHUB_CANARY_PREP", "program drift")
    _require(contract.get("owner_issue") == 178, "owner issue drift")
    _require(contract.get("delivery_issue") == 165, "delivery issue drift")
    _require(contract.get("stage_state") == "L2_PUBLIC_CANARY_IMPLEMENTATION_PREP_READY", "stage state drift")
    _require(contract.get("parent") == EXPECTED_PARENT, "preparation parent drift")
    _require(contract.get("implementation_git_parent") == EXPECTED_GIT_PARENT, "implementation Git parent drift")

    transport = contract.get("transport_binding", {})
    _require(transport.get("reuse_required") is True, "existing transport must be reused")
    _require(transport.get("new_transport_allowed") is False, "duplicate transport is forbidden")
    _require(transport.get("class") == "GitHubRestMetadataSource", "transport class drift")
    files = transport.get("source_files", [])
    _require(len(files) == len(EXPECTED_TRANSPORT_FILES), "transport file denominator drift")
    by_path = {entry.get("path"): entry for entry in files}
    _require(set(by_path) == set(EXPECTED_TRANSPORT_FILES), "transport paths drift")
    for path, (commit_sha, blob_sha, symbols) in EXPECTED_TRANSPORT_FILES.items():
        entry = by_path[path]
        _require(entry.get("commit_sha") == commit_sha, f"transport commit drift: {path}")
        _require(entry.get("blob_sha") == blob_sha, f"transport blob drift: {path}")
        _require(set(entry.get("required_symbols", [])) == symbols, f"required symbols drift: {path}")

    subject = contract.get("canary_subject", {})
    _require(subject.get("repository") == EXPECTED_REPOSITORY, "repository subject drift")
    _require(subject.get("issue") == EXPECTED_ISSUE, "Issue subject drift")
    _require(subject.get("pull_request") == EXPECTED_PR, "PR subject drift")
    _require(subject.get("commit") == EXPECTED_COMMIT, "commit subject drift")

    runs = {entry.get("id"): entry for entry in subject.get("workflow_runs", [])}
    _require(set(runs) == set(EXPECTED_RUNS), "workflow run denominator drift")
    for run_id, (name, conclusion) in EXPECTED_RUNS.items():
        entry = runs[run_id]
        _require(entry.get("name") == name, f"workflow name drift: {run_id}")
        _require(entry.get("conclusion") == conclusion, f"workflow conclusion drift: {run_id}")
        _require(entry.get("head_sha") == EXPECTED_COMMIT["sha"], f"workflow head drift: {run_id}")

    jobs = {entry.get("id"): entry for entry in subject.get("check_jobs", [])}
    _require(set(jobs) == set(EXPECTED_JOBS), "check job denominator drift")
    for job_id, (name, run_id, conclusion) in EXPECTED_JOBS.items():
        entry = jobs[job_id]
        _require(entry.get("name") == name, f"job name drift: {job_id}")
        _require(entry.get("run_id") == run_id, f"job run drift: {job_id}")
        _require(entry.get("conclusion") == conclusion, f"job conclusion drift: {job_id}")
        _require(entry.get("head_sha") == EXPECTED_COMMIT["sha"], f"stale check head: {job_id}")

    request = contract.get("request_contract", {})
    _require(request.get("origin") == "https://api.github.com", "GitHub origin drift")
    _require(request.get("allowed_methods") == ["GET"], "only GET is admitted")
    _require(request.get("credential_mode") == "NONE", "public canary must be credential-free")
    _require(request.get("token_provider_must_not_be_called") is True, "public mode must not call token provider")
    _require(request.get("max_pages") == 10, "page bound drift")
    _require(request.get("per_page") == 100, "page size drift")
    _require(request.get("total_timeout_seconds") == 60, "timeout drift")
    for resource in request.get("resources", []):
        parsed = urllib.parse.urlparse("https://api.github.com" + resource)
        _require(parsed.scheme == "https" and parsed.netloc == "api.github.com", "resource endpoint drift")
        _require(resource.startswith("/repos/ed3c/kotlin-auto-webview"), "resource escaped repository")
        _require(".." not in resource, "path traversal is forbidden")

    packet = contract.get("implementation_packet", {})
    _require(packet.get("branch") == "feat/workspace-live-github", "implementation branch drift")
    _require(packet.get("base_branch") == "feat/workspace-github-workgraph", "implementation base branch drift")
    _require(packet.get("base_head_sha") == EXPECTED_GIT_PARENT["head_sha"], "implementation base head drift")
    safe = packet.get("first_safe_write", [])
    _require(safe and all(path.startswith((
        "composeApp/src/commonTest/",
        "composeApp/src/desktopTest/",
        "tests/evidence/workspace/github/",
        "scripts/evidence/workspace/github/",
        "receipts/workspace/live/github/",
        ".github/workflows/workspace-live-github.yml",
        "docs/evidence/workspace/live-github/",
    )) for path in safe), "first-safe-write escaped lease")
    _require("GitHubRestMetadataSource.kt" in "\n".join(packet.get("forbidden_write", [])), "existing transport write guard missing")

    start = contract.get("start_receipt", {})
    _require(start.get("interface_locked") is True, "interface must be locked")
    _require(start.get("public_subject_bound") is True, "public subject must be bound")
    _require(start.get("code_ready") is True, "code readiness drift")
    _require(start.get("public_canary_implementation_ready") is True, "implementation prep must be ready")
    _require(start.get("public_canary_executed") is False, "public canary cannot be pre-executed")
    _require(start.get("private_canary_ready") is False, "private canary requires external authority")
    _require(start.get("live_evidence_ready") is False, "L2 live evidence cannot be pre-promoted")

    capabilities = {entry.get("id"): entry for entry in contract.get("external_capabilities", [])}
    _require(capabilities.get("PUBLIC_GITHUB_HTTPS", {}).get("credential_required") is False, "public network capability drift")
    _require(capabilities.get("GITHUB_PRIVATE_REPOSITORY_SCOPE", {}).get("state") == "EXTERNAL_AUTHORITY_REQUIRED", "private scope boundary drift")

    handoff = contract.get("local_handoff", {})
    _require(handoff.get("state") == "ABSENT", "Local Handoff cannot be executable before code exists")
    _require(handoff.get("placeholder_commands_allowed") is False, "placeholder commands are forbidden")

    boundary = contract.get("evidence_boundary", {})
    _require(boundary.get("prep_remote_readback") == "PREPARATION_ONLY", "prep readback ceiling drift")
    for key in ("kotlin_app_live_canary", "w1_live_readback", "l2_live_evidence", "private_access"):
        _require(boundary.get(key) == "NOT_EXERCISED", f"{key} cannot be promoted")
    _require(boundary.get("maximum_claim") == "IMPLEMENTATION_PREP_ONLY", "maximum claim widened")
    _require(contract.get("excluded_issue_numbers") == [173, 174], "void issue exclusion drift")

    for text in _walk_values(contract):
        for pattern in FORBIDDEN_VALUE_PATTERNS:
            _require(pattern.search(text) is None, "credential-like material detected")
        _require("private-repo" not in text.lower(), "private locator detected")


def _get_json(path: str, timeout: int = 20) -> dict[str, Any]:
    url = "https://api.github.com" + path
    parsed = urllib.parse.urlparse(url)
    _require(parsed.scheme == "https" and parsed.netloc == "api.github.com", "remote origin not admitted")
    request = urllib.request.Request(
        url,
        method="GET",
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "kotlin-auto-webview-prep-verifier",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            _require(response.status == 200, f"unexpected HTTP status {response.status}: {path}")
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise ContractError(f"remote read failed {exc.code}: {path}") from exc
    except urllib.error.URLError as exc:
        raise ContractError(f"remote read failed: {path}") from exc


def verify_remote(contract: dict[str, Any]) -> None:
    repo = _get_json("/repos/ed3c/kotlin-auto-webview")
    _require(repo.get("id") == EXPECTED_REPOSITORY["id"], "remote repository ID drift")
    _require(repo.get("node_id") == EXPECTED_REPOSITORY["node_id"], "remote repository node drift")
    _require(repo.get("full_name") == EXPECTED_REPOSITORY["full_name"], "remote repository name drift")
    _require(repo.get("private") is False, "remote repository is not public")
    _require(repo.get("default_branch") == EXPECTED_REPOSITORY["default_branch"], "remote default branch drift")

    issue = _get_json("/repos/ed3c/kotlin-auto-webview/issues/165")
    _require(issue.get("id") == EXPECTED_ISSUE["id"], "remote Issue ID drift")
    _require(issue.get("node_id") == EXPECTED_ISSUE["node_id"], "remote Issue node drift")
    _require(issue.get("number") == EXPECTED_ISSUE["number"], "remote Issue number drift")
    _require(issue.get("state") == EXPECTED_ISSUE["state"], "remote Issue state drift")

    pr = _get_json("/repos/ed3c/kotlin-auto-webview/pulls/177")
    _require(pr.get("id") == EXPECTED_PR["id"], "remote PR ID drift")
    _require(pr.get("node_id") == EXPECTED_PR["node_id"], "remote PR node drift")
    _require(pr.get("number") == EXPECTED_PR["number"], "remote PR number drift")
    _require(pr.get("state") == EXPECTED_PR["state"], "remote PR state drift")
    _require(pr.get("draft") is EXPECTED_PR["draft"], "remote PR draft drift")
    _require(pr.get("head", {}).get("sha") == EXPECTED_PR["head_sha"], "remote PR head drift")
    _require(pr.get("base", {}).get("sha") == EXPECTED_PR["base_sha"], "remote PR base drift")

    commit = _get_json("/repos/ed3c/kotlin-auto-webview/git/commits/" + EXPECTED_COMMIT["sha"])
    _require(commit.get("sha") == EXPECTED_COMMIT["sha"], "remote commit drift")
    _require(commit.get("tree", {}).get("sha") == EXPECTED_COMMIT["tree_sha"], "remote tree drift")

    for run_id, (name, conclusion) in EXPECTED_RUNS.items():
        run = _get_json(f"/repos/ed3c/kotlin-auto-webview/actions/runs/{run_id}")
        _require(run.get("name") == name, f"remote workflow name drift: {run_id}")
        _require(run.get("head_sha") == EXPECTED_COMMIT["sha"], f"remote workflow head drift: {run_id}")
        _require(run.get("status") == "completed", f"remote workflow incomplete: {run_id}")
        _require(run.get("conclusion") == conclusion, f"remote workflow conclusion drift: {run_id}")
        jobs = _get_json(f"/repos/ed3c/kotlin-auto-webview/actions/runs/{run_id}/jobs?per_page=100")
        remote_jobs = {entry.get("id"): entry for entry in jobs.get("jobs", [])}
        for job_id, (job_name, expected_run, job_conclusion) in EXPECTED_JOBS.items():
            if expected_run != run_id:
                continue
            _require(job_id in remote_jobs, f"remote check job missing: {job_id}")
            job = remote_jobs[job_id]
            _require(job.get("name") == job_name, f"remote check job name drift: {job_id}")
            _require(job.get("head_sha") == EXPECTED_COMMIT["sha"], f"remote stale check: {job_id}")
            _require(job.get("status") == "completed", f"remote check incomplete: {job_id}")
            _require(job.get("conclusion") == job_conclusion, f"remote check conclusion drift: {job_id}")

    for entry in contract["transport_binding"]["source_files"]:
        commit_sha = entry["commit_sha"]
        path = entry["path"]
        contents = _get_json(f"/repos/ed3c/kotlin-auto-webview/contents/{urllib.parse.quote(path)}?ref={commit_sha}")
        _require(contents.get("sha") == entry["blob_sha"], f"remote source blob drift: {path}")
        raw_request = urllib.request.Request(
            contents["download_url"],
            method="GET",
            headers={"User-Agent": "kotlin-auto-webview-prep-verifier"},
        )
        with urllib.request.urlopen(raw_request, timeout=20) as response:
            raw = response.read().decode("utf-8")
        for symbol in entry["required_symbols"]:
            _require(symbol in raw, f"remote required symbol missing: {path}: {symbol}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify-remote", action="store_true")
    args = parser.parse_args(argv)
    try:
        contract = _load(CONTRACT_PATH)
        schema = _load(SCHEMA_PATH)
        validate_model(contract, schema)
        if args.verify_remote:
            verify_remote(contract)
    except (ContractError, json.JSONDecodeError, OSError, ValueError) as exc:
        print(f"workspace live GitHub public canary prep: FAIL: {exc}", file=sys.stderr)
        return 1
    print("workspace live GitHub public canary prep: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
