---
name: Autonomous Stacked PR task packet
about: Admit one eval-first, safety-bound, path-leased Git Town work packet
title: "[stack] "
labels: ""
assignees: ""
---

## Identity

```yaml
issue_id: <assigned by GitHub>
parent_issue_id: NONE
goal: <one testable outcome>
non_goals:
  - <explicit non-goal>
```

## Stack placement

```yaml
base_branch: <PR base>
parent_branch: <Git Town parent>
head_branch: <unique branch>
stack_class: foundation | child | sibling | convergence | release | hotfix
dependencies:
  - issue: <number>
parallel_safe_siblings:
  - <branch or NONE>
```

## Path lease

```yaml
allowed_paths:
  - <exact path or glob>
excluded_paths:
  - <explicit shared/legal/secret/sensitive path>
shared_index_owner: <issue number or NONE>
```

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
  current_license: <SPDX or policy reference>
  writable_legal_files: []
  dependency_admission_required: true
private_data_boundary:
  allowed_realms:
    - <repository/private runtime>
  public_or_external_egress: denied
local_user_state_boundary:
  primary_checkout_mutation: denied
  linked_worktree_required: true
  destructive_cleanup: denied
```

## Required evals

```yaml
required_evals:
  commands:
    - <fixed typed command>
  assertions:
    - <testable assertion>
  safety_postconditions:
    - visibility_owner_default_branch_unchanged
    - access_rulesets_secrets_unchanged
    - license_usage_rights_unchanged
    - remote_topology_and_perennial_refs_unchanged
    - disclosure_private_egress_scan_pass
    - local_user_state_pass_or_explicit_NOT_EXERCISED
```

## Negative / mutation controls

```yaml
negative_or_mutation_controls:
  - mutation: <guard removal or hostile input>
    expected: <FAIL or stable BLOCKED state>
```

Include controls for authority expansion, visibility/access/license/private-egress changes, old-SHA evidence, path overlap, and reporting unavailable local evidence as PASS.

## Shadow Architecture checkpoints

```yaml
shadow_checkpoints:
  - ARCHITECTURE_CHOICE
  - FIRST_GREEN
  - BEFORE_COMMIT
  - BEFORE_PUSH
  - BEFORE_PR_OR_PUBLICATION
```

Add material checkpoints for persistence, concurrency, external integration, dependency/license, or publication surfaces when applicable.

```yaml
shadow_delta:
  class: <delta class>
  newly_possible: <state/side effect>
  must_remain_true: <invariant>
  falsifier: <oracle/control>
  intervention: L0 | L1 | L2 | L3
```

## Evidence boundary

```yaml
evidence_boundary:
  proves:
    - <what this task can prove>
  does_not_prove:
    - <separate local/sync/publication/CI/device/store/merge/legal/production lane>
```

## Cleanup and rollback

```yaml
cleanup_contract:
  preserve_blocked_worktree: true
  preserve_user_uncommitted_state: true
  remove_only_current_run_owned_temporary_state: true
  destructive_cleanup: denied
  residue_check: required
rollback_subject: <immutable SHA/tag>
```

## External-authority operations

The canonical field name remains `human_owned_operations`:

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

Encountering one resolves to `EXTERNAL_AUTHORITY_REQUIRED`; do not ask for it.

## Publication and merge

```yaml
publication:
  allowed: false
  intent: NONE
  exact_head_required: true
  disclosure_scan_required: true
  same_repository_and_visibility_required: true
  gate_required: true
  remote_ancestry_check_required: true
merge:
  allowed_only_if_repository_pre_authorizes: true
  current_preauthorization: ABSENT
```

## Admission checklist

- [ ] Goal/non-goals are bounded.
- [ ] Parent/base/head and dependency graph are explicit and acyclic.
- [ ] Path lease is disjoint from active siblings.
- [ ] Safety, visibility, usage-right, private-data, and local-state boundaries are explicit.
- [ ] Evals and red controls exist before branch creation.
- [ ] Shadow checkpoints and falsifiers exist.
- [ ] Evidence boundary prevents hollow PASS claims.
- [ ] Cleanup and immutable rollback subject exist.
- [ ] No credential, token, key, cookie, browser profile, device session, signing material, private/customer data, absolute secret path, or secret value appears.
- [ ] External-authority operations remain external and do not trigger a question.
