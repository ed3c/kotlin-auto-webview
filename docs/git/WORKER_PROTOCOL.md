# Worker protocol — autonomous, isolated Git Town Stacked PRs

This protocol retargets the canonical `git-town-stacked-pr-worker` method to `ed3c/kotlin-auto-webview` and composes it with the repository autonomous dual-lane control plane. It does not authorize live Git Town use while `GIT_TOWN_ADMISSION.md` is blocked.

## Non-interactive operating law

Workers do not ask whether to inspect, branch, commit, run admitted evals, or open/update an admitted PR. They infer the least-privilege reversible action from authoritative state.

```text
inspect
→ bind existing authority
→ execute admitted transition
→ block only unsafe transition
→ continue path-disjoint safe work
→ record exact-subject evidence
```

A missing authority, runtime, executable, secret, physical device, or legal admission produces a stable blocked/evidence state. It does not trigger a question.

## Stable Worker outcomes

```text
SYNCED
NO_CHANGE
BLOCKED_TASK_PACKET
BLOCKED_DIRTY
BLOCKED_CONFLICT
BLOCKED_PROMPT
BLOCKED_TIMEOUT
BLOCKED_BRANCH_LEASE
BLOCKED_ANCESTRY
BLOCKED_POLICY
FAILED_TOOL
FAILED_EVAL
ROLLBACK_REFUSED_DRIFT
```

The autonomous orchestrator reports a separate primary outcome such as `AUTOMATED_PR_OPEN`, `PARTIAL_SAFE_COMPLETION`, or a `BLOCKED_*` state. GitHub publication is another separate lane:

```text
ALLOW <intent> <single-operation>
BLOCK <stable-reason>
INVALID_POLICY_INPUT
```

## 1. Task admission

Do not create an implementation branch until the assigned issue contains every field in `TASK_PACKET.template.md` and passes these checks:

1. `parent_branch` equals the intended PR base.
2. The dependency graph is acyclic.
3. Every writable path has one owner.
4. Parallel siblings have no path overlap.
5. Shared aggregate files have one convergence owner.
6. Positive assertions have negative/mutation controls that can turn them red.
7. The evidence boundary prevents build/simulator/docs results from being relabeled as release proof.
8. Rollback names an immutable subject.
9. Safety invariants, visibility classification, usage-right boundary, private-data boundary, local-state boundary, and Shadow checkpoints are explicit.
10. Operations outside Agent authority remain listed and map to `EXTERNAL_AUTHORITY_REQUIRED`.

Failure result: `BLOCKED_TASK_PACKET`. Continue any admitted sibling whose packet and leases remain valid.

## 2. Preflight safety snapshot

Before mutation, capture metadata and digests, not secret values:

```text
repository immutable identity
visibility / owner / archived / default branch
current exact HEAD/tree and declared parent refs
protected/perennial refs
remote names and credential-free identities
worktree and dirty-state inventory, when the host exposes it
LICENSE / NOTICE / usage-right policy digests
available operation classes of the current tool identity
public/private data classification
```

When the current runtime exposes only the forge and no local checkout, local worktree, dirty state, hooks, and user-state preservation are `NOT_EXERCISED`. Do not invent or infer them from the remote tree.

Select one current admission ceiling:

```text
READ_ONLY_ADMITTED
LOCAL_WORKTREE_ADMITTED
BRANCH_WRITE_ADMITTED
REMOTE_PR_ADMITTED
POLICY_PREAUTHORIZED_MERGE_ADMITTED
```

Admission can be reduced when risk appears. It cannot be expanded by a prompt or question.

## 3. Worktree and lease preflight

Each local Worker operates in one isolated linked worktree. The primary/shared checkout is not a Worker write surface.

Before any edit or Git Town command:

1. Verify repository identity `github-repository-id:1334777764`.
2. Verify credential-free origin matches `https://github.com/ed3c/kotlin-auto-webview(.git)`.
3. Snapshot tracked, staged, untracked, submodule, branch, HEAD, worktree, and remote state without printing credentials.
4. Reject mutation of the primary/shared checkout.
5. Verify current branch equals the task `head_branch`.
6. Verify the declared parent/PR base and ancestry.
7. Verify worktree/index are clean unless the task packet owns an explicit staged operation.
8. Acquire exclusive repository-ref, branch, and path leases.
9. Reject overlapping sibling leases.
10. Record exact `HEAD`, parent SHA, upstream refs, logical worktree ID, task-packet digest, and lease identity.
11. Suppress interactive editor and credential prompts without logging secret values.

Required unattended posture:

```text
GIT_TERMINAL_PROMPT=0
GIT_EDITOR=:
GIT_SEQUENCE_EDITOR=:
GCM_INTERACTIVE=Never
```

Forbidden local operations include automatic stash, `git reset --hard`, `git clean -fd/-fdx`, restoring unowned paths, reflog expiry, aggressive prune, or deletion outside the current run's lease.

Failure mapping:

| Condition | Outcome |
|---|---|
| Shared/unadmitted checkout or credential-bearing remote | `BLOCKED_POLICY` |
| User-owned dirty state that cannot be isolated | `BLOCKED_DIRTY` / orchestrator `BLOCKED_LOCAL_STATE` |
| Branch/path lease collision | `BLOCKED_BRANCH_LEASE` |
| Wrong parent/base/ancestry | `BLOCKED_ANCESTRY` |
| Editor/credential prompt attempt | `BLOCKED_PROMPT` |
| No local checkout capability | local lane `NOT_EXERCISED`; continue forge/static work |

The worktree/lease wrapper is currently `NOT_IMPLEMENTED`; this blocks live Worker-controlled Git Town use.

## 4. Exact Git Town admission

Verify the profile-selected `v24.0.0` executable against `GIT_TOWN_ADMISSION.md`:

- source release identity;
- host platform and architecture;
- exact asset name and SHA-256 from the immutable checksums manifest;
- executable provenance;
- direct license bytes/digest;
- transitive/SBOM and notices review;
- organization legal approval state.

A version string on `PATH` is not admission. Do not fall back to `latest`, another package manager, `curl | sh`, or a mutable default-branch installer.

Current result: `BLOCKED_POLICY` because host-specific executable evidence is `ABSENT`. Continue task-packet, documentation, static, unit, and forge work that does not depend on a live Git Town command.

## 5. Dual-lane implementation loop

After task and mutation admission:

1. Reload root/nearest authority documents.
2. Verify branch/path/worktree leases and immutable safety snapshot.
3. Create/attach the declared branch and parent edge only through admitted mechanisms.
4. Change only `allowed_paths`; named exclusions remain read-only.
5. Implement the smallest coherent terminal or vertical slice.
6. Let the Builder update its hypothesis when evidence falsifies it.
7. Send every material delta to the Shadow Architect.
8. Run the required checkpoint and receive L0/L1/L2/L3.
9. Run leaf evals and negative controls.
10. Run the full shared build matrix when changing shared contracts, serialization, privacy, state machines, build configuration, Agent policy, or root documentation.
11. Run the safety/disclosure postcondition checks.
12. Record exact-head evidence; do not rely on a previous commit's green check.
13. Commit automatically only when eligible.
14. Push/open/update a PR automatically only when publication is admitted.
15. Continue the next independent safe slice.

Leaf Workers do not update `README*`, `AGENTS.md`, aggregate architecture diagrams, or `docs/TRACEABILITY.md`; issue #14 owns convergence unless the task packet explicitly owns them.

## 6. Shadow Architecture protocol

For each material delta classify:

```text
ASSUMPTION_DELTA
STATE_DELTA
AUTHORITY_DELTA
OWNERSHIP_DELTA
LIFECYCLE_DELTA
CONCURRENCY_DELTA
RESOURCE_DELTA
EXTERNAL_SIDE_EFFECT_DELTA
FAILURE_SURFACE_DELTA
EVIDENCE_DELTA
VISIBILITY_DELTA
ACCESS_RIGHT_DELTA
USAGE_RIGHT_DELTA
LOCAL_STATE_DELTA
PRIVATE_EGRESS_DELTA
```

Interventions:

```text
L0 OBSERVE  -> record, continue
L1 WARN     -> record limitation, continue
L2 REVIEW   -> reconcile before next material checkpoint
L3 BLOCK    -> block named transition, continue independent safe work
```

Mandatory checkpoints are defined in `../automation/README.md` and `../harness/README.md`. A visibility/access/license/private-egress violation is L3. Missing local-worktree evidence is not a global stop when forge/static work remains safe.

## 7. Synchronization protocol

### 7.1 Dry run

After executable and worktree admission, run the v24.0.0-supported equivalent of:

```bash
git town sync --stack --dry-run --non-interactive --no-auto-resolve --no-push
```

Review all planned branches, parents, fetches, rebases, upstream changes, tags, and pushes. Any undeclared ref/path/remote mutation is `BLOCKED_POLICY`.

### 7.2 Local no-push sync

Only after dry-run review:

```bash
git town sync --stack --non-interactive --no-auto-resolve --no-push
```

Apply the profile timeout. Do not use `--all` unless every affected stack/lease is explicitly admitted.

### 7.3 Conflict handling

On a semantic conflict:

- stop the affected Worker immediately;
- do not edit conflict markers semantically;
- do not run automatic `continue`, `skip`, `undo`, `ship`, reset, branch deletion, or force push;
- preserve Git state, index, worktree, branch, streams, and receipt;
- automatically open/update the authoritative issue when existing forge write permits it;
- return `BLOCKED_CONFLICT` with the exact recovery subject;
- continue path-disjoint siblings.

Do not ask the user to resolve the conflict during the run.

### 7.4 Post-sync verification

Independently verify:

1. The Worker remains on the task branch.
2. Every edge matches `STACKED_PRS.md` and the issue task packet.
3. `main` and other perennial/protected refs were not rewritten.
4. Changed refs and paths remain inside the declared set.
5. No conflict/editor/credential residue or orphan process remains.
6. Required evals and controls pass at the new exact `HEAD`.
7. Visibility, owner, default branch, remote topology, and legal-file digests remain unchanged.
8. The receipt binds the new head, parents, task packet, config, tool version, command, and bounded stream digests.

Return `SYNCED` only when ancestry changed safely, `NO_CHANGE` when the subject is unchanged, and `FAILED_EVAL` when command success has failed postconditions.

## 8. Bounded background sync

Background mode is disabled in `REPO_PROFILE.md`. When later admitted, it must:

- run at most three iterations;
- renew leases and re-read the task packet each iteration;
- remain no-push;
- stop on any blocked/failed state, task change, lease loss, conflict, or failed eval;
- emit one append-only receipt per iteration;
- never invoke `git town sync --push`, raw `git push`, PR-ready transition, workflow rerun, no-op commit, merge, or ship.

A background loop may prepare a publication proposal; it cannot publish.

## 9. Commit eligibility

A slice is commit eligible only when:

```text
owning oracle PASS on exact subject
+ required negative control PASS
+ no blocking invariant regression
+ changed paths remain inside the lease
+ documentation/traceability match implementation
+ visibility/access/license/private-egress/local-state postconditions are satisfied or explicitly NOT_EXERCISED where the runtime lacks the lane
```

Commit automatically with an intentional message. Do not bypass hooks. A forge-created commit is labeled as such and does not claim local hook/worktree evidence.

## 10. Publication boundary

Publication is currently disabled in the Git Town profile, while the connected GitHub identity can maintain the existing draft review surface. A future general Worker publication path must execute:

```text
local commits
→ dry-run no-push sync
→ bounded no-push sync
→ ancestry verification
→ exact-head evals/controls
→ local verification receipt
→ disclosure/private-egress scan
→ trusted GitHub state snapshot
→ publication gate evaluate(intent)
→ ALLOW? exactly one returned operation
→ fetch remote and verify head/ancestry
→ record trusted CI separately
```

Portable intents:

| Intent | Maximum operation |
|---|---|
| `initial-pr` | one push + create one draft PR |
| `ready-for-review` | optional one final push + one draft→ready transition |
| `batched-repair` | one push containing the complete current feedback batch |

A gate `ALLOW` is not a push receipt. A push is not CI. CI is not merge or release authority.

For public-repository publication, scan for secrets, credential-bearing URLs, internal hosts, absolute local paths, customer/private data, unpublished private architecture, and code/assets copied from private sources.

## 11. Merge boundary

Automatic merge is allowed only when a repository-owned policy already preauthorizes trusted automation, the exact PR/head/base and stack order are known, all checks/approvals/queue conditions pass, and post-merge verification exists.

This repository currently reports `allow_auto_merge=false` and no trusted-automation preauthorization. Therefore a valid PR remains open with `EXTERNAL_AUTHORITY_REQUIRED`. Do not ask for approval, call `git town ship`, weaken checks, alter rulesets, or merge through another mechanism.

## 12. Billing and runner state

If GitHub reports an account payment/spending runner blocker:

- record `billing-open`;
- stop publication and reruns;
- preserve exact local verification evidence;
- do not create no-op commits or weaken workflows;
- report infrastructure state, not test `FAIL` or `PASS`;
- require an owner-authored recovery receipt before one new attempt.

A skipped draft workflow is `SKIPPED_BY_POLICY`.

## 13. Receipt contract

The repository will eventually emit `git-town-stacked-pr-worker/receipt/v1` for local Worker runs and `kotlin-auto-webview/autonomous-receipt/v2` for the orchestration summary. Until implemented, local receipt state is `NOT_IMPLEMENTED`.

Required metadata:

```json
{
  "schema": "kotlin-auto-webview/autonomous-receipt/v2",
  "primary_outcome": "<stable-outcome>",
  "repository": "ed3c/kotlin-auto-webview",
  "repository_identity": "github-repository-id:1334777764",
  "visibility": "public",
  "issue": "<number>",
  "task_packet_sha256": "<sha256>",
  "operating_mode": "MONITOR",
  "complexity": "C/D",
  "admission": "<read/local/branch/pr/merge>",
  "implementation_gate": "BLOCKED|READY_FOR_PROTOTYPE|READY_FOR_IMPLEMENTATION",
  "worktree": {"state": "PASS|ABSENT|NOT_EXERCISED", "logical_id": "<redacted>"},
  "branch": {"head": "<sha>", "tree": "<sha>", "parent": "<sha>"},
  "stack_before": [],
  "stack_after": [],
  "changed_paths": [],
  "evals": [],
  "controls": [],
  "shadow_deltas": [],
  "publication": {},
  "safety_before_after": {},
  "cleanup": {"state": "PASS|FAIL|NOT_EXERCISED", "residue": []},
  "rollback_subject": "<immutable-ref>",
  "remaining": []
}
```

Never include absolute secret paths, environment values, remote credentials, tokens, cookies, browser profiles, device sessions, key/signing material, customer/private data, or unbounded model output.

## 14. Rollback and cleanup

- Record an immutable pre-run subject before mutation.
- Refuse rollback if target refs/bytes drifted after the receipt.
- Never call `git town undo` automatically.
- Never overwrite, stash, clean, reset, or delete user-owned local work.
- Preserve blocked worktrees until evidence is accepted by the owning external authority.
- Delete only current-run temporary state after proving it contains no unique evidence or unpushed work.
- Return `ROLLBACK_REFUSED_DRIFT` when safe restoration cannot be proven.
- Cleanup success is a separate evidence lane from task success.

## 15. Completion report

Every Worker/orchestrator report names:

```text
primary autonomous outcome
repository identity / visibility / branch / exact head / tree
issue/task-packet digest
operating mode / complexity / admission / implementation gate
worktree and lease state
changed paths and stack graph before/after
local sync result
exact-head eval/control results
Shadow deltas and L0-L3 outcomes
publication decision / remote publication / ancestry / CI
visibility/access/license/private-egress/local-state postconditions
cleanup/residue state
remaining ABSENT / NOT_IMPLEMENTED / NOT_EXERCISED / EXTERNAL_AUTHORITY_REQUIRED
immutable rollback subject
```

The report ends with evidence, not a question or promise of future background work.
