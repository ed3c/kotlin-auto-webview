# Autonomous Stacked PR task packet template

Copy this body into a GitHub issue before creating an implementation branch. Missing required fields are `ABSENT` and block only the affected Worker transition. The Agent does not ask the user to fill missing authority during a run; it continues any independent safe slice.

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

`base_branch` and `parent_branch` must match unless a repository-specific reviewed exception explains the difference. Do not infer parentage from names.

## Path lease

```yaml
allowed_paths:
  - <EXACT_PATH_OR_GLOB>
excluded_paths:
  - <EXPLICIT_SHARED_LEGAL_SECRET_OR_SENSITIVE_PATH>
shared_index_owner: <ISSUE_NUMBER_OR_NONE>
```

Independent Workers need disjoint path leases. Root READMEs, `AGENTS.md`, aggregate diagrams, and `docs/TRACEABILITY.md` normally belong to issue #14 after the documentation foundation.

## Safety and data boundaries

```yaml
safety_invariants:
  - INV-SAFE-001_repository_visibility_immutable
  - INV-SAFE-002_owner_access_rulesets_default_branch_immutable
  - INV-SAFE-003_license_usage_rights_attribution_immutable
  - INV-SAFE-004_local_user_state_preserved
  - INV-SAFE-005_private_data_egress_denied
  - INV-SAFE-006_host_execution_least_privilege
  - INV-SAFE-007_protected_history_remote_topology_preserved
visibility_classification: public | private | internal
usage_rights_boundary:
  current_license: <SPDX_OR_POLICY_REFERENCE>
  writable_legal_files: []
  dependency_admission_required: true
private_data_boundary:
  allowed_realms:
    - <REPOSITORY_OR_PRIVATE_RUNTIME>
  public_or_external_egress: denied
local_user_state_boundary:
  primary_checkout_mutation: denied
  linked_worktree_required: true
  destructive_cleanup: denied
```

For this repository, ordinary tasks keep `LICENSE` read-only. `NOTICE` is writable only by an admitted dependency task for required additive attribution.

## Required evals

List fixed/typed commands and assertions; do not expose arbitrary trailing shell.

```yaml
required_evals:
  commands:
    - <COMMAND>
  assertions:
    - <TESTABLE_ASSERTION>
  safety_postconditions:
    - visibility_owner_default_branch_unchanged
    - access_rulesets_secrets_unchanged
    - license_usage_rights_unchanged
    - remote_topology_and_perennial_refs_unchanged
    - disclosure_private_egress_scan_pass
    - local_user_state_pass_or_explicit_NOT_EXERCISED
```

Common full matrix when shared contracts/build configuration/Agent policy change:

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
  - mutation: <GUARD_REMOVED_OR_HOSTILE_INPUT>
    expected: <FAIL_OR_STABLE_BLOCKED_STATE>
```

Examples:

- remove sensitive-input filtering;
- use an unregistered capability;
- replay an old OpenClaw sequence;
- use a stale DOM fingerprint;
- overlap a sibling path lease;
- replace `--no-push` with `--push`;
- reuse an old-SHA verification receipt;
- report a simulator artifact as store evidence;
- attempt repository visibility/access/ruleset/default-branch mutation;
- change license meaning or remove attribution;
- publish private content or absolute local paths;
- claim local user-state PASS without inspecting a local checkout;
- treat missing merge preauthorization as permission or as a question;
- halt all siblings because one transition is blocked.

## Shadow Architecture checkpoints

```yaml
shadow_checkpoints:
  - ARCHITECTURE_CHOICE
  - <FIRST_VERTICAL_SLICE_IF_APPLICABLE>
  - <PERSISTENCE_OR_ASYNC_OR_EXTERNAL_INTEGRATION_IF_APPLICABLE>
  - FIRST_GREEN
  - BEFORE_COMMIT
  - BEFORE_PUSH
  - BEFORE_PR_OR_PUBLICATION
```

For each material delta record:

```yaml
shadow_delta:
  class: ASSUMPTION_DELTA | STATE_DELTA | AUTHORITY_DELTA | OWNERSHIP_DELTA | LIFECYCLE_DELTA | CONCURRENCY_DELTA | RESOURCE_DELTA | EXTERNAL_SIDE_EFFECT_DELTA | FAILURE_SURFACE_DELTA | EVIDENCE_DELTA | VISIBILITY_DELTA | ACCESS_RIGHT_DELTA | USAGE_RIGHT_DELTA | LOCAL_STATE_DELTA | PRIVATE_EGRESS_DELTA
  newly_possible: <STATE_OR_SIDE_EFFECT>
  must_remain_true: <INVARIANT>
  falsifier: <ORACLE_OR_CONTROL>
  intervention: L0 | L1 | L2 | L3
```

## Evidence boundary

```yaml
evidence_boundary:
  proves:
    - <WHAT_THIS_TASK_CAN_PROVE>
  does_not_prove:
    - <WHAT_REMAINS_A_SEPARATE_LANE>
```

Do not collapse static review, local worktree, Git Town sync, exact-head verification, publication, remote ancestry, CI, physical device, store, merge, legal, and production lanes.

## Cleanup and rollback

```yaml
cleanup_contract:
  preserve_blocked_worktree: true
  preserve_user_uncommitted_state: true
  remove_only_current_run_owned_temporary_state: true
  destructive_cleanup: denied
  residue_check: required
rollback_subject: <IMMUTABLE_SHA_OR_TAG>
```

Rollback is drift-aware. Workers do not run `git town undo` automatically and never overwrite user-owned work.

## External-authority operations

Retained under the canonical field name for compatibility:

```yaml
human_owned_operations:
  - semantic_conflict_resolution
  - git_town_continue_skip_undo_ship
  - merge_or_merge_queue_admission_without_pre_authorization
  - legal_license_attribution_or_terms_acceptance
  - branch_protection_ruleset_access_permission_or_secret_change
  - credentials_signing_or_store_submission
  - release_promotion_or_production_deployment
  - destructive_or_drifted_rollback
```

Encountering one during an autonomous run produces `EXTERNAL_AUTHORITY_REQUIRED`. The Agent does not ask for it.

## Publication authorization

```yaml
publication:
  allowed: false
  intent: initial-pr | ready-for-review | batched-repair | NONE
  exact_head_required: true
  disclosure_scan_required: true
  same_repository_and_visibility_required: true
  gate_required: true
  remote_ancestry_check_required: true
merge:
  allowed_only_if_repository_pre_authorizes: true
  current_preauthorization: ABSENT
```

General Worker publication remains false until the repository gate is implemented/admitted. An existing PR may be maintained only through the exact authority already documented by the repository profile.

## Acceptance checklist

- [ ] Goal is one reviewable outcome.
- [ ] Non-goals prevent scope growth.
- [ ] Parent/base/head and dependency graph are explicit and acyclic.
- [ ] Path lease is disjoint from active siblings.
- [ ] Visibility, usage-right, private-data, and local-state boundaries are explicit.
- [ ] Evals were designed before implementation.
- [ ] Negative controls can expose hollow success and immutable-boundary violations.
- [ ] Shadow checkpoints and falsifiers exist.
- [ ] Evidence boundary separates local, publication, CI, device, store, merge, legal, and production claims.
- [ ] Cleanup and immutable rollback subject exist.
- [ ] External-authority operations remain external and do not trigger a question.
- [ ] No credential, token, cookie, browser profile, device session, signing material, customer/private data, absolute secret path, or secret value appears.
