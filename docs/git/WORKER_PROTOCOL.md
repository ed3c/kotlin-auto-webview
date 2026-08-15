# Worker protocol — isolated Git Town Stacked PRs

This protocol retargets the shared `git-town-stacked-pr-worker` method to `ed3c/kotlin-auto-webview`. It does not authorize live Git Town use while `GIT_TOWN_ADMISSION.md` is blocked.

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

GitHub publication is a separate decision lane:

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
9. Human-owned operations are unchanged.

Failure result: `BLOCKED_TASK_PACKET`.

## 2. Worktree and lease preflight

Each Worker operates in one isolated linked worktree. The primary/shared checkout is not a Worker write surface.

Before any edit or Git Town command:

1. Verify repository identity `github-repository-id:1334777764`.
2. Verify credential-free origin matches `https://github.com/ed3c/kotlin-auto-webview(.git)`.
3. Verify current branch equals the task `head_branch`.
4. Verify the declared parent/PR base and ancestry.
5. Verify worktree and index are clean, unless the task packet owns an explicit staged operation.
6. Acquire exclusive repository-ref, branch, and path leases.
7. Reject overlapping sibling leases.
8. Record exact `HEAD`, parent SHA, upstream refs, logical worktree ID, task-packet digest, and lease identity.
9. Suppress interactive editor and credential prompts without logging secret values.

Required unattended posture:

```text
GIT_TERMINAL_PROMPT=0
GIT_EDITOR=:
GIT_SEQUENCE_EDITOR=:
GCM_INTERACTIVE=Never
```

Failure mapping:

| Condition | Outcome |
|---|---|
| Shared/unadmitted checkout or credential-bearing remote | `BLOCKED_POLICY` |
| Dirty index/worktree | `BLOCKED_DIRTY` |
| Branch/path lease collision | `BLOCKED_BRANCH_LEASE` |
| Wrong parent/base/ancestry | `BLOCKED_ANCESTRY` |
| Editor/credential prompt attempt | `BLOCKED_PROMPT` |

The worktree/lease wrapper is currently `NOT_IMPLEMENTED`; this blocks live Worker-controlled Git Town use.

## 3. Exact Git Town admission

Verify the profile-selected `v24.0.0` executable against `GIT_TOWN_ADMISSION.md`:

- source release identity;
- host platform and architecture;
- exact asset name and SHA-256 from the immutable checksums manifest;
- executable provenance;
- direct license bytes/digest;
- transitive/SBOM and notices review;
- organization legal approval state.

A version string on `PATH` is not admission. Do not fall back to `latest` or another package manager result.

Current result: `BLOCKED_POLICY` because host-specific executable evidence is `ABSENT`.

## 4. Implementation loop

After admission and task preflight:

1. Create/attach the declared branch and parent edge.
2. Change only `allowed_paths`; named exclusions remain read-only.
3. Keep commits small and tied to one invariant or eval.
4. Run the leaf module evals and negative controls.
5. Run the full shared build matrix when changing shared contracts, serialization, privacy, state machines, build configuration, or root documentation.
6. Record exact-head evidence; do not rely on a previous commit's green check.
7. Stop on a policy, lease, ancestry, prompt, timeout, conflict, tool, or eval failure.

Leaf Workers do not update `README*`, `AGENTS.md`, aggregate architecture diagrams, or `docs/TRACEABILITY.md`; issue #14 owns convergence unless the task packet states otherwise.

## 5. Synchronization protocol

### 5.1 Dry run

Run the v24.0.0-supported equivalent of:

```bash
git town sync --stack --dry-run --non-interactive --no-auto-resolve --no-push
```

Review all planned branches, parents, fetches, rebases, upstream changes, tags, and pushes. Any undeclared ref/path/remote mutation is `BLOCKED_POLICY`.

### 5.2 Local no-push sync

Only after dry-run review:

```bash
git town sync --stack --non-interactive --no-auto-resolve --no-push
```

Apply the profile timeout. Do not use `--all` unless every affected stack/lease is explicitly admitted.

### 5.3 Conflict handling

On a semantic conflict:

- stop immediately;
- do not edit conflict markers semantically;
- do not run automatic `continue`, `skip`, `undo`, `ship`, reset, branch deletion, or force push;
- preserve Git state, worktree, branch, streams, and receipt;
- return `BLOCKED_CONFLICT` with the exact human recovery subject.

### 5.4 Post-sync verification

Independently verify:

1. The Worker remains on the task branch.
2. Every edge matches `STACKED_PRS.md` and the issue task packet.
3. `main` and other perennial/protected refs were not rewritten.
4. Changed refs and paths remain inside the declared set.
5. No conflict/editor/credential residue or orphan process remains.
6. Required evals and controls pass at the new exact `HEAD`.
7. The receipt binds the new head, parents, task packet, config, tool version, command, and bounded stream digests.

Return `SYNCED` only when ancestry changed safely, `NO_CHANGE` when the subject is unchanged, and `FAILED_EVAL` when command success has failed postconditions.

## 6. Bounded background sync

Background mode is disabled in `REPO_PROFILE.md`. When later admitted, it must:

- run at most three iterations;
- renew leases and re-read the task packet each iteration;
- remain no-push;
- stop on any blocked/failed state, task change, lease loss, conflict, or failed eval;
- emit one append-only receipt per iteration;
- never invoke `git town sync --push`, raw `git push`, PR-ready transition, workflow rerun, no-op commit, merge, or ship.

A background loop may prepare a publication proposal; it cannot publish.

## 7. Publication boundary

Publication remains `NOT_IMPLEMENTED` and disabled. A future Worker must execute this order:

```text
local commits
→ dry-run no-push sync
→ bounded no-push sync
→ ancestry verification
→ exact-head evals/controls
→ local verification receipt
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

## 8. Billing and runner state

If GitHub reports an account payment/spending runner blocker:

- record `billing-open`;
- stop publication and reruns;
- preserve exact local verification evidence;
- do not create no-op commits or weaken workflows;
- report infrastructure state, not test `FAIL` or `PASS`;
- require an owner-authored recovery receipt before one new attempt.

A skipped draft workflow is `SKIPPED_BY_POLICY`.

## 9. Receipt contract

The repository will eventually emit `git-town-stacked-pr-worker/receipt/v1` under `receipts/git-town/`. Until implemented, receipt state is `NOT_IMPLEMENTED`.

Required metadata:

```json
{
  "schema": "git-town-stacked-pr-worker/receipt/v1",
  "run_id": "<id>",
  "timestamp": "<RFC3339>",
  "repository": "ed3c/kotlin-auto-webview",
  "repository_identity": "github-repository-id:1334777764",
  "task_packet_sha256": "<sha256>",
  "git_town": {"version": "v24.0.0", "admission_state": "PASS"},
  "worktree": {"kind": "linked-isolated", "path_redacted": "<logical-id>"},
  "stack_before": [],
  "stack_after": [],
  "changed_refs": [],
  "changed_paths": [],
  "evals": [],
  "controls": [],
  "cleanup": {"state": "PASS|FAIL|NOT_EXERCISED", "residue": []},
  "result": "<stable-outcome>",
  "rollback_subject": "<immutable-ref>",
  "human_action_required": true
}
```

Never include absolute secret paths, environment values, remote credentials, tokens, cookies, browser profiles, device sessions, key material, signing material, or unbounded model output.

## 10. Rollback and cleanup

- Record an immutable pre-run subject before mutation.
- Refuse rollback if target refs/bytes drifted after the receipt.
- Never call `git town undo` automatically.
- Preserve blocked worktrees until the recovery owner accepts the evidence.
- Return `ROLLBACK_REFUSED_DRIFT` when safe restoration cannot be proven.
- Cleanup success is a separate evidence lane from task success.

## 11. Completion report

Every Worker report must name:

```text
repository identity
issue/task-packet digest
worktree and lease state
branch/parent/head
changed paths
local sync result
local eval/control results
publication decision
remote publication/ancestry state
trusted CI state
cleanup/residue state
remaining NOT_IMPLEMENTED / NOT_EXERCISED / ABSENT
rollback subject
human action required
```
