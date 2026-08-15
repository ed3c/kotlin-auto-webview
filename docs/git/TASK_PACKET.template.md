# Stacked PR task packet template

Copy this body into a GitHub issue before creating an implementation branch. Missing required fields are `ABSENT` and block Worker execution.

## Identity

```yaml
issue_id: <NUMBER>
parent_issue_id: <NUMBER_OR_NONE>
goal: <ONE_TESTABLE_OUTCOME>
non_goals:
  - <EXPLICIT_NON_GOAL>
```

## Stack placement

```yaml
base_branch: <PR_BASE>
parent_branch: <GIT_TOWN_PARENT>
head_branch: <UNIQUE_BRANCH>
stack_class: foundation | child | sibling | convergence | release | hotfix
dependencies:
  - issue: <NUMBER>
parallel_safe_siblings:
  - <BRANCH_OR_NONE>
```

`base_branch` and `parent_branch` must match unless a repository-specific reviewed exception explains the difference. Do not infer parentage from branch names.

## Path lease

```yaml
allowed_paths:
  - <EXACT_PATH_OR_GLOB>
excluded_paths:
  - <EXPLICIT_SHARED_OR_SENSITIVE_PATH>
shared_index_owner: <ISSUE_NUMBER_OR_NONE>
```

Independent Workers need disjoint path leases. Root READMEs, `AGENTS.md`, aggregate diagrams, and `docs/TRACEABILITY.md` normally belong to issue #14 after the documentation foundation.

## Required evals

List fixed/typed commands and assertions; do not expose arbitrary trailing shell.

```yaml
required_evals:
  commands:
    - <COMMAND>
  assertions:
    - <TESTABLE_ASSERTION>
```

Common full matrix when shared contracts/build configuration change:

```bash
./gradlew :composeApp:allTests
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:wasmJsBrowserDistribution
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64  # macOS
```

## Negative or mutation controls

Each load-bearing claim needs a control that turns red when the guard is removed.

```yaml
negative_or_mutation_controls:
  - mutation: <GUARD_REMOVED_OR_BAD_INPUT>
    expected: <FAIL_OR_BLOCKED_STATE>
```

Examples:

- remove sensitive-input filtering;
- use an unregistered capability;
- replay an old OpenClaw sequence;
- use a stale DOM fingerprint;
- overlap a sibling path lease;
- replace `--no-push` with `--push`;
- reuse an old-SHA verification receipt;
- report a simulator artifact as store evidence.

## Evidence boundary

```yaml
evidence_boundary:
  proves:
    - <WHAT_THIS_TASK_CAN_PROVE>
  does_not_prove:
    - <WHAT_REMAINS_A_SEPARATE_LANE>
```

Do not collapse implementation, local verification, publication, remote ancestry, CI, store/device evidence, and Human Admit.

## Cleanup and rollback

```yaml
cleanup_contract:
  preserve_blocked_worktree: true
  remove_only_owned_temporary_state: true
  residue_check: required
rollback_subject: <IMMUTABLE_SHA_OR_TAG>
```

Rollback is drift-aware. Workers do not run `git town undo` automatically.

## Human-owned operations

```yaml
human_owned_operations:
  - semantic_conflict_resolution
  - git_town_continue_skip_undo_ship
  - merge_or_merge_queue_admission
  - legal_or_license_acceptance
  - permissions_credentials_secrets_or_signing
  - store_submission_or_release_promotion
  - production_deployment_or_destructive_rollback
```

## Publication authorization

```yaml
publication:
  allowed: false
  intent: initial-pr | ready-for-review | batched-repair | NONE
  exact_head_required: true
  gate_required: true
```

Publication remains false until the repository gate is implemented and admitted.

## Acceptance checklist

- [ ] Goal is one reviewable outcome.
- [ ] Non-goals prevent scope growth.
- [ ] Parent/base/head and dependency graph are explicit and acyclic.
- [ ] Path lease is disjoint from active siblings.
- [ ] Evals were designed before implementation.
- [ ] Negative controls can expose hollow success.
- [ ] Evidence boundary separates build, CI, store, and production claims.
- [ ] Cleanup and immutable rollback subject exist.
- [ ] Human-owned operations remain human-owned.
- [ ] No credential, token, cookie, browser profile, device session, signing material, or secret value appears.
