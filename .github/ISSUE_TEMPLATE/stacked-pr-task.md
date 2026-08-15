---
name: Stacked PR task packet
about: Admit one eval-first, path-leased Git Town work packet
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
  - <explicit shared/sensitive path>
shared_index_owner: <issue number or NONE>
```

## Required evals

```yaml
required_evals:
  commands:
    - <fixed typed command>
  assertions:
    - <testable assertion>
```

## Negative / mutation controls

```yaml
negative_or_mutation_controls:
  - mutation: <guard removal or hostile input>
    expected: <FAIL or stable BLOCKED state>
```

## Evidence boundary

```yaml
evidence_boundary:
  proves:
    - <what this task can prove>
  does_not_prove:
    - <separate sync/publication/CI/device/store/production lane>
```

## Cleanup and rollback

```yaml
cleanup_contract:
  preserve_blocked_worktree: true
  remove_only_owned_temporary_state: true
  residue_check: required
rollback_subject: <immutable SHA/tag>
```

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

## Publication

```yaml
publication:
  allowed: false
  intent: NONE
  exact_head_required: true
  gate_required: true
```

## Admission checklist

- [ ] Goal/non-goals are bounded.
- [ ] Parent/base/head and dependency graph are explicit and acyclic.
- [ ] Path lease is disjoint from active siblings.
- [ ] Evals and red controls exist before branch creation.
- [ ] Evidence boundary prevents hollow PASS claims.
- [ ] Cleanup and immutable rollback subject exist.
- [ ] No credential, token, key, cookie, browser profile, device session, signing material, or secret value appears.
- [ ] Human-owned operations remain human-owned.
