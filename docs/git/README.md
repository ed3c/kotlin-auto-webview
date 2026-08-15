# Git and Stacked-PR governance

This directory is the repository-owned binding for the canonical [`git-town-stacked-pr-worker`](https://github.com/ed3c/skills-shared/tree/main/skills/git-town-stacked-pr-worker) method. It composes with [`spatial-loop-systems-engineering`](https://github.com/ed3c/skills-shared/tree/main/skills/spatial-loop-systems-engineering) through `../automation/README.md`; it does not copy or fork either Skill.

## Authority split

```text
skills-shared
  owns: portable Shadow Architecture, evidence, Git Town, recovery,
        outcome, and publication-boundary laws

kotlin-auto-webview
  owns: repository profile, autonomous binding, branch graph, path leases,
        task packets, evals, CI, static config, receipts, and release evidence

host/runtime
  owns: local checkout, user-state snapshot, executable installation,
        OS/architecture selection, checksum, provenance, SBOM, credentials,
        worktree and lease storage, and secure invocation

pre-existing repository policy
  may own: trusted merge/queue automation when explicitly preauthorized

external authority
  owns: semantic conflict resolution, legal acceptance, settings/access/secret
        changes, store submission, production promotion, and destructive rollback
```

Git Town synchronizes branch ancestry. It does not prove implementation correctness, review approval, GitHub publication, store readiness, or production safety.

## Autonomous behavior

Workers do not ask whether to inspect, branch, commit, run admitted evals, or open/update an admitted PR. Missing authority/evidence blocks only the affected transition and independent path-disjoint work continues.

Operations outside current authority resolve to `EXTERNAL_AUTHORITY_REQUIRED` or a stable `BLOCKED_*` state. They never trigger a request to expand authority during the run.

## Current status

| Item | State |
|---|---|
| Autonomous dual-lane / Shadow binding | present in `docs/automation/` |
| Static `.git-town.toml` for selected Git Town v24.0.0 | present |
| Repository profile | present |
| Planned stack graph and path leases | present |
| Task-packet and PR templates | present |
| Exact host binary checksum/provenance | `ABSENT` |
| Transitive/SBOM/legal admission | `NOT_EXERCISED` / `ABSENT` |
| Local checkout/user dirty-state lane in current connector | `NOT_EXERCISED` |
| Isolated worktree/lease wrapper | `NOT_IMPLEMENTED` |
| Live dry-run/no-push sync | `NOT_EXERCISED` |
| Conflict canary | `NOT_EXERCISED` |
| Exact-HEAD publication gate/receipts | `NOT_IMPLEMENTED` |
| Existing draft PR maintenance through GitHub connector | admitted within current rights |
| Automatic merge preauthorization | `ABSENT` / `EXTERNAL_AUTHORITY_REQUIRED` |

Documentation/configuration adoption is not live-tool admission. Until `GIT_TOWN_ADMISSION.md` is complete, Worker execution is `BLOCKED_POLICY`.

## Files

| File | Purpose |
|---|---|
| [`../automation/README.md`](../automation/README.md) | Autonomous Builder/Shadow control plane, state machines, data flows, invariants, blocked behavior |
| [`../automation/REPOSITORY_PROFILE.md`](../automation/REPOSITORY_PROFILE.md) | Exact repository identity, immutable properties, operation admission, safety snapshot |
| [`REPO_PROFILE.md`](REPO_PROFILE.md) | Git Town repository identity, policies, evidence states, and required task fields |
| [`STACKED_PRS.md`](STACKED_PRS.md) | Actual/planned branch graph, issues, path leases, evals, and merge order |
| [`WORKER_PROTOCOL.md`](WORKER_PROTOCOL.md) | Non-interactive worktree, lease, Shadow, sync, conflict, publication, and completion algorithm |
| [`GIT_TOWN_ADMISSION.md`](GIT_TOWN_ADMISSION.md) | Selected release and missing host/legal evidence that blocks live use |
| [`TASK_PACKET.template.md`](TASK_PACKET.template.md) | Copyable eval-first, safety-bound issue/Worker contract |

## Mandatory read order for Git work

1. Root `AGENTS.md`.
2. `../automation/README.md` and `../automation/REPOSITORY_PROFILE.md`.
3. Root README and architecture/traceability/security/license documents.
4. This README and `REPO_PROFILE.md`.
5. `STACKED_PRS.md`.
6. `WORKER_PROTOCOL.md` and `../harness/README.md`.
7. Assigned issue/task packet and nearest writable-path README/AGENTS file.
8. Current branch/PR/ref graph and exact subjects.
9. `GIT_TOWN_ADMISSION.md`.
10. Canonical shared Skills and publication policy.

A missing required input is `ABSENT`; do not infer it from a branch name, issue title, package manifest, installed binary, or another repository.

## Repository laws

- One Worker owns one linked worktree, one branch lease, and one disjoint path lease.
- Independent work is sibling branches. Serial stacks exist only for real interface/data/build dependencies.
- Root READMEs, `AGENTS.md`, aggregate diagrams, and `docs/TRACEABILITY.md` have one convergence owner.
- Background work is bounded and no-push.
- Semantic conflicts stop the affected Worker, preserve the worktree, update the authoritative issue when admitted, and do not stop independent siblings.
- No raw force push, protected/perennial rewrite, remote replacement/deletion, automatic merge/ship, permission widening, secret mutation, license/usage-right change, store submission, production action, or destructive local cleanup is delegated to a Worker.
- Receipts contain metadata and digests, never credentials, tokens, cookies, browser profiles, device sessions, key/signing material, customer/private data, absolute local paths, environment values, or unbounded model output.
- Visibility, owner, default branch, access/rulesets, legal-file meaning, private-data boundary, local user state, and remote topology are immutable.
- Missing merge preauthorization leaves the PR open with `EXTERNAL_AUTHORITY_REQUIRED`.

## Adoption completion boundary

Live adoption is complete only after all of the following are proven with subject-bound evidence:

1. Both shared Skills resolve without local shadows.
2. Repository profiles have no unresolved required field for the attempted transition.
3. Exact Git Town executable, checksum, provenance, license, transitive/SBOM, notices, and legal state are admitted.
4. Isolated worktree, local user-state preservation, and branch/path lease controls fail closed.
5. Dry-run and actual sync are non-interactive, no-auto-resolve, bounded, and no-push.
6. A planted semantic conflict stops without automatic resolution while independent siblings continue.
7. Exact-head local verification and machine-readable receipts exist.
8. Publication uses the admitted gate, disclosure scan, and post-push ancestry verification.
9. Visibility/access/license/private-egress/local-state postconditions pass or remain explicitly `NOT_EXERCISED` when the runtime lacks that lane.
10. Merge occurs only under repository-owned preauthorization; otherwise it remains `EXTERNAL_AUTHORITY_REQUIRED`.
11. Rollback names an immutable subject and refuses drift.

Unrun lanes remain `NOT_EXERCISED`.
