#!/usr/bin/env python3
"""Prove the #74 evidence branch consumes frozen #72/#73 bytes without source drift."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

from evidence_contract import EvidenceContractError, load_bindings

ROOT = Path(__file__).resolve().parents[3]

ALLOWED_EVIDENCE_PREFIXES = (
    "composeApp/src/androidInstrumentedTest/",
    "composeApp/src/androidTestFixtures/",
    "integrations/opendroid/fixtures/",
    "scripts/evidence/android/",
    "tests/evidence/android/",
    "receipts/android/opendroid/",
    ".github/workflows/android-device-evidence.yml",
    "docs/evidence/android-opendroid/",
    "docs/architecture/ADR-0032-android-automation-evidence.md",
)


def git(*args: str, check: bool = True) -> str:
    proc = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if check and proc.returncode != 0:
        raise EvidenceContractError(f"git {' '.join(args)} failed: {proc.stderr.strip()}")
    return proc.stdout.strip()


def ensure_object(sha: str) -> None:
    if subprocess.run(["git", "cat-file", "-e", f"{sha}^{{commit}}"], cwd=ROOT, check=False).returncode == 0:
        return
    proc = subprocess.run(
        ["git", "fetch", "--no-tags", "--depth=1", "origin", sha],
        cwd=ROOT,
        check=False,
    )
    if proc.returncode != 0:
        raise EvidenceContractError(f"cannot fetch exact selected subject {sha}")


def main() -> int:
    bindings = load_bindings()
    convergence = bindings["source_convergence"]
    git_parent = bindings["git_parent"]

    ensure_object(git_parent["head"])
    ensure_object(convergence["commit"])
    for parent in convergence["process_parents"]:
        ensure_object(parent["head"])

    if git("show", "-s", "--format=%T", git_parent["head"]) != git_parent["tree"]:
        raise EvidenceContractError("#71 parent tree drift")
    if git("show", "-s", "--format=%T", convergence["commit"]) != convergence["tree"]:
        raise EvidenceContractError("source convergence tree drift")

    convergence_parents = git("show", "-s", "--format=%P", convergence["commit"]).split()
    if convergence_parents != [git_parent["head"]]:
        raise EvidenceContractError("convergence Git parent must remain exact #71; process parents are separate metadata")

    current_head = git("rev-parse", "HEAD")
    current_tree = git("rev-parse", "HEAD^{tree}")
    if subprocess.run(
        ["git", "merge-base", "--is-ancestor", convergence["commit"], "HEAD"],
        cwd=ROOT,
        check=False,
    ).returncode != 0:
        raise EvidenceContractError("source convergence snapshot is not an ancestor of evidence head")

    changed = [line for line in git("diff", "--name-only", convergence["commit"], "HEAD").splitlines() if line]
    for path in changed:
        if not any(path == prefix or path.startswith(prefix) for prefix in ALLOWED_EVIDENCE_PREFIXES):
            raise EvidenceContractError(f"#74 evidence branch modified undeclared/source path: {path}")

    selected_count = 0
    for parent in convergence["process_parents"]:
        if git("show", "-s", "--format=%T", parent["head"]) != parent["tree"]:
            raise EvidenceContractError(f"selected issue #{parent['issue']} tree drift")
        for path in parent["selected_paths"]:
            selected_blob = git("rev-parse", f"{parent['head']}:{path}")
            current_blob = git("rev-parse", f"HEAD:{path}")
            if selected_blob != current_blob:
                raise EvidenceContractError(
                    f"selected source byte drift issue #{parent['issue']}: {path} {current_blob} != {selected_blob}"
                )
            selected_count += 1

    summary = {
        "schema": "kotlin-auto-webview/android-device-evidence-source-check/v1",
        "state": "PASS",
        "current_head": current_head,
        "current_tree": current_tree,
        "source_convergence_commit": convergence["commit"],
        "source_convergence_tree": convergence["tree"],
        "selected_source_paths_verified": selected_count,
        "evidence_delta_paths": len(changed),
        "relation": "git-parent-71/process-parents-72-73",
    }
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
