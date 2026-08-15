# Git Town executable admission

## Decision

```text
selected source release: v24.0.0
live executable admission: ABSENT
Worker Git Town execution: BLOCKED_POLICY
```

The repository has a fail-closed `.git-town.toml`, but configuration presence is not executable, legal, or live-canary evidence.

## Source release record

| Field | Recorded value | State |
|---|---|---|
| Source repository | `git-town/git-town` | `PASS` source identity |
| Release tag | `v24.0.0` | `PASS` immutable selection |
| GitHub release ID | `358702660` | `PASS` source metadata |
| Published | `2026-07-23T13:48:21Z` | recorded |
| GitHub immutable flag | `true` | recorded |
| Checksums manifest asset | `checksums.txt` | recorded |
| Manifest asset digest | `sha256:7532377166cb59dc01c74f86e3a71c54ba9567a461313a5d203a1ea99c571b24` | `PASS` metadata identity |
| Direct source license | MIT, `LICENSE` at tag `v24.0.0` | recorded |

A release asset list and top-level license are inputs. They do not complete host binary, transitive, service-term, patent/trademark, export, or organization legal review.

## Missing host admission

The trusted host must fill and sign a subject-bound record for each platform/architecture that runs Git Town:

```yaml
schema: kotlin-auto-webview/git-town-admission/v1
repository_identity: github-repository-id:1334777764
git_town:
  version: v24.0.0
  release_id: 358702660
  platform: ABSENT
  architecture: ABSENT
  asset_name: ABSENT
  asset_sha256_from_manifest: ABSENT
  downloaded_bytes_sha256: ABSENT
  executable_sha256: ABSENT
  executable_path_logical_id: ABSENT
  provenance_or_package_lock: ABSENT
  direct_license_sha256: NOT_EXERCISED
  sbom_or_transitive_review: NOT_EXERCISED
  notices_review: NOT_EXERCISED
  service_terms_review: NOT_EXERCISED
  legal_approval: ABSENT
  admitted_by: ABSENT
  admitted_at: ABSENT
```

Never commit a machine-specific secret path, credentials, package-manager token, or keyring reference.

## Required verification sequence

A trusted operator must:

1. Choose one official `v24.0.0` asset matching the exact host OS and architecture.
2. Fetch the immutable checksums manifest and verify its release identity/digest.
3. Verify downloaded asset bytes against the asset SHA-256 listed in that manifest.
4. Verify the installed executable bytes and `git town --version`/equivalent exact output.
5. Record acquisition provenance or package-manager lock.
6. Review direct MIT license bytes and compute a digest.
7. Review transitive dependencies/SBOM, required notices, service/host terms, patents/trademarks/export concerns, and organization policy as separate lanes.
8. Store only metadata/digests in the repository-approved receipt location.
9. Run the static configuration validation.
10. Run live worktree/lease, dry-run, no-push sync, conflict, cleanup, and publication canaries described below.

A mismatch returns `FAILED_TOOL`; missing mandatory evidence is `ABSENT`; unrun canaries are `NOT_EXERCISED`.

## Static configuration posture

`.git-town.toml` is intentionally conservative:

- non-interactive;
- `main` is the perennial branch;
- new branches remain local;
- auto-sync disabled;
- sync does not push branches, tags, or upstream changes;
- feature/prototype synchronization uses rebase;
- perennial synchronization is `ff-only`.

The file cannot authorize a command. The repository profile, task packet, worktree/lease preflight, admitted executable, and Human/publication boundaries still apply.

## Live admission canaries

All are currently `NOT_EXERCISED`.

### Canary A — isolated no-change stack

- Create an isolated linked worktree and branch lease.
- Use a disposable branch graph with no semantic change.
- Run dry-run and actual no-push sync.
- Prove no remote ref, tag, upstream, or protected branch changed.
- Prove clean worktree and cleanup receipt.

Expected result: `NO_CHANGE`.

### Canary B — safe ancestry update

- Create a parent/child disposable stack.
- Advance the parent with a non-conflicting commit.
- Run bounded no-push sync.
- Independently verify the child ancestry changed and task evals remain green.

Expected result: `SYNCED`.

### Canary C — planted semantic conflict

- Create deterministic overlapping edits in a disposable parent/child stack.
- Run no-auto-resolve/no-push sync.
- Prove the Worker stops, preserves conflict state, and does not run continue/skip/undo/ship or semantic edits.

Expected result: `BLOCKED_CONFLICT`.

### Canary D — path/branch lease collision

- Start a second Worker with an overlapping branch or path lease.
- Prove mutation is refused before Git Town runs.

Expected result: `BLOCKED_BRANCH_LEASE`.

### Canary E — publication guards

- Replay positive and hollow exact-head snapshots offline.
- Prove missing/stale verification, wrong head, repeated feedback, draft-policy mismatch, billing-open, or missing guard blocks publication.
- Prove an allowed operation is single-shot and followed by fetched remote ancestry verification.

Expected state: `NOT_IMPLEMENTED` until the canonical publication gate is bound.

## Commands after admission

Use only the exact version-supported equivalents:

```bash
git town sync --stack --dry-run --non-interactive --no-auto-resolve --no-push
git town sync --stack --non-interactive --no-auto-resolve --no-push
```

Do not expose `git town continue`, `skip`, `undo`, `ship`, merge, force push, permission changes, or production operations to an unattended Worker.

## Admission completion boundary

Git Town execution can move from `BLOCKED_POLICY` only when:

- every mandatory host field is resolved;
- exact executable verification is `PASS`;
- static config is valid for the admitted version;
- isolated worktree and lease controls are `PASS`;
- no-push dry-run/sync and post-sync ancestry are `PASS`;
- planted conflict and mutation controls are `PASS`;
- receipts and cleanup evidence are `PASS`;
- publication is either admitted with canaries or remains explicitly disabled;
- Human Admit boundaries remain unchanged.
