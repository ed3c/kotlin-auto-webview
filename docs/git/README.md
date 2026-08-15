# Git and Stacked-PR governance

This directory is the repository-owned binding for the shared [`git-town-stacked-pr-worker`](https://github.com/ed3c/skills-shared/tree/main/skills/git-town-stacked-pr-worker) method. It does not copy or fork the canonical Skill.

## Authority split

```text
skills-shared
  owns: portable Git Town method, outcome vocabulary, publication boundaries

kotlin-auto-webview
  owns: repository profile, branch graph, path leases, task packets,
        evals, CI, static config, receipts, release evidence

host/runtime
  owns: executable installation, OS/architecture selection, checksum,
        provenance, SBOM, credentials, worktree and lease storage

human/trusted operator
  owns: semantic conflict resolution, legal acceptance, merge/ship,
        permission changes, release promotion, production rollback
```

Git Town synchronizes branch ancestry. It does not prove implementation correctness, review approval, GitHub publication, store readiness, or production safety.

## Current status

| Item | State |
|---|---|
| Static `.git-town.toml` for selected Git Town v24.0.0 | present |
| Repository profile | present |
| Planned stack graph and path leases | present |
| Task-packet and PR templates | present |
| Exact host binary checksum/provenance | `ABSENT` |
| Transitive/SBOM/legal admission | `NOT_EXERCISED` / `ABSENT` |
| Isolated worktree/lease wrapper | `NOT_IMPLEMENTED` |
| Live dry-run/no-push sync | `NOT_EXERCISED` |
| Conflict canary | `NOT_EXERCISED` |
| Exact-HEAD publication gate/receipts | `NOT_IMPLEMENTED` |

Documentation/configuration adoption is not live-tool admission. Until `GIT_TOWN_ADMISSION.md` is complete, Worker execution is `BLOCKED_POLICY`.

## Files

| File | Purpose |
|---|---|
| [`REPO_PROFILE.md`](REPO_PROFILE.md) | Exact repository identity, policies, evidence states, and required task fields |
| [`STACKED_PRS.md`](STACKED_PRS.md) | Actual/planned branch graph, issues, path leases, evals, and merge order |
| [`WORKER_PROTOCOL.md`](WORKER_PROTOCOL.md) | Isolated worktree, lease, sync, conflict, publication, and completion algorithm |
| [`GIT_TOWN_ADMISSION.md`](GIT_TOWN_ADMISSION.md) | Selected release and missing host/legal evidence that blocks live use |
| [`TASK_PACKET.template.md`](TASK_PACKET.template.md) | Copyable eval-first issue/Worker contract |

## Mandatory read order for Git work

1. Root `AGENTS.md`.
2. Root README and architecture/traceability documents.
3. This README and `REPO_PROFILE.md`.
4. `STACKED_PRS.md`.
5. `WORKER_PROTOCOL.md` and `../harness/README.md`.
6. Assigned issue/task packet and nearest writable-path README.
7. Current branch/PR graph.
8. `GIT_TOWN_ADMISSION.md`.
9. Shared Skill and publication policy.

A missing required input is `ABSENT`; do not infer it from a branch name, issue title, package manifest, or another repository.

## Repository laws

- One Worker owns one linked worktree, one branch lease, and one disjoint path lease.
- Independent work is sibling branches. Serial stacks exist only for real interface/data/build dependencies.
- Root READMEs, `AGENTS.md`, aggregate diagrams, and `docs/TRACEABILITY.md` have one convergence owner.
- Background work is bounded and no-push.
- Semantic conflicts stop the Worker and preserve the worktree.
- No raw force push, merge, `git town ship`, permission widening, secret mutation, store submission, or production action is delegated to a Worker.
- Receipts contain metadata and digests, never tokens, cookies, browser profiles, device sessions, key material, environment values, or unbounded model output.

## Adoption completion boundary

Live adoption is complete only after all of the following are proven with subject-bound evidence:

1. Shared Skill resolves without a local shadow.
2. Repository profile has no unresolved required field.
3. Exact Git Town executable, checksum, provenance, license, transitive/SBOM, notices, and legal state are admitted.
4. Isolated worktree and branch/path lease controls fail closed.
5. Dry-run and actual sync are non-interactive, no-auto-resolve, bounded, and no-push.
6. A planted semantic conflict stops without automatic resolution.
7. Exact-head local verification and machine-readable receipts exist.
8. Publication uses the admitted gate and post-push ancestry verification.
9. Human Admit remains required for merge and promotion.
10. Rollback names an immutable subject and refuses drift.

Unrun lanes remain `NOT_EXERCISED`.
