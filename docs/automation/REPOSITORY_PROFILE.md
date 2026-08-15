# Repository autonomous integration profile

This file resolves repository-specific values for the autonomous dual-lane binding. It is not a credential file, a local-worktree receipt, or permission to mutate repository settings.

## Identity and immutable properties

```yaml
schema: kotlin-auto-webview/autonomy-profile/v2
repository:
  full_name: ed3c/kotlin-auto-webview
  immutable_identity: github-repository-id:1334777764
  visibility: public
  owner: ed3c
  archived: false
  default_branch: main
  clone_identity: https://github.com/ed3c/kotlin-auto-webview.git
immutable:
  repository_visibility: true
  repository_owner: true
  default_branch_identity: true
  access_rights: true
  branch_protection_and_rulesets: true
  secret_configuration: true
  license_and_usage_rights: true
  remote_topology: true
  private_data_egress: denied
  production_actions: denied
```

Snapshot subject before the v2 binding update:

```yaml
refs:
  main: e447dea815d63e89afd6acf58845f222bee07b6f
  foundation: a449fac24b8ee602b3c36ae60e972fe25f35c516
  governance_pr_head_before_v2_binding: d5f9aa76da8e2d481baa9843ed7fc4c4bba2949f
legal_files:
  LICENSE_blob: d645695673349e3947e8e5ae42332d0ac3164cd7
  NOTICE_blob: 0fd68de4e4d417eb316ec82973dafa41a636d0fb
```

These hashes are preservation subjects, not legal interpretation.

## Runtime policy

```yaml
runtime:
  autonomy_mode: FULL_AUTOMATION
  interaction_policy: NON_INTERACTIVE
  operating_mode: MONITOR
  forge: GITHUB
  local_mutation: AUTO_WITHIN_ISOLATED_WORKTREE
  branch_mutation: AUTO_WITHIN_EXISTING_RIGHTS
  issue_mutation: AUTO_WITHIN_EXISTING_RIGHTS
  publication: AUTO_WITHIN_EXISTING_RIGHTS
  merge: AUTO_ONLY_IF_PREAUTHORIZED_BY_REPOSITORY_POLICY
  production_actions: DENY_DIRECT_AGENT_ACTION
```

## Current authority and evidence

The connected GitHub identity reports repository read/write/admin operation classes. This profile does not treat those API permissions as permission to change visibility, access, rulesets, secrets, legal state, or default branch.

```yaml
authority:
  forge_repository_read: PASS
  forge_branch_write: PASS
  forge_issue_pr_write: PASS
  local_checkout_visibility: NOT_EXERCISED
  isolated_linked_worktree: NOT_EXERCISED
  local_user_dirty_state_snapshot: NOT_EXERCISED
  live_git_town: BLOCKED_POLICY
  publication_gate: NOT_IMPLEMENTED
  automatic_merge_policy:
    repository_allow_auto_merge: false
    trusted_automation_preauthorization: ABSENT
    state: EXTERNAL_AUTHORITY_REQUIRED
  store_release_production: denied
mutation_admission: REMOTE_PR_ADMITTED
```

`REMOTE_PR_ADMITTED` authorizes review-surface publication inside this same public repository when exact-head, base, disclosure, and rollback gates pass. It does not authorize merge or release.

## Canonical authority documents

```yaml
authority_documents:
  agents: AGENTS.md
  root_readme: README.md
  automation: docs/automation/README.md
  architecture: docs/architecture/README.md
  traceability: docs/TRACEABILITY.md
  security: docs/security/THREAT_MODEL.md
  git_profile: docs/git/REPO_PROFILE.md
  stack_graph: docs/git/STACKED_PRS.md
  worker_protocol: docs/git/WORKER_PROTOCOL.md
  harness: docs/harness/README.md
  git_town_admission: docs/git/GIT_TOWN_ADMISSION.md
  issue_template: .github/ISSUE_TEMPLATE/stacked-pr-task.md
  pr_template: .github/PULL_REQUEST_TEMPLATE.md
canonical_skills:
  spatial_loop: ed3c/skills-shared@main:skills/spatial-loop-systems-engineering
  git_town_worker: ed3c/skills-shared@main:skills/git-town-stacked-pr-worker
```

No project-local copy of either shared Skill is admitted.

## Source-material classification

The source PDFs are treated as intent and candidate architecture, not runtime truth.

```yaml
source_claims:
  native_platform_renderers:
    classification: DESIGN_PROPOSAL_WITH_IMPLEMENTED_SUPPORT
    repository_evidence: PR #1 platform targets and CI
  mobile_webview_no_chrome_extension:
    classification: REQUIREMENT_CONSTRAINT
    repository_response: controlled observer injection and JS bridge
  kmp_l1_openclaw_l2:
    classification: DESIGN_PROPOSAL
    implementation: L1 baseline implemented; L2 NOT_IMPLEMENTED
  dom_fingerprint_geometry_projection:
    classification: DESIGN_PROPOSAL_WITH_MVP_IMPLEMENTATION
    evidence: projection and serialization tests
  observer_toolmaker_orchestrator:
    classification: DESIGN_PROPOSAL
    implementation: Observer foundation implemented; Toolmaker/Executor/Orchestrator incomplete
```

Unverified market, performance, model, product, legal, or vendor claims from the source remain `EXTERNAL_CLAIM` or `UNKNOWN` until independently verified.

## Stable autonomous outcomes

```text
AUTOMATED_MERGED
AUTOMATED_PR_OPEN
AUTOMATED_PUSHED
AUTOMATED_LOCAL_COMPLETE
READ_ONLY_COMPLETE
PARTIAL_SAFE_COMPLETION
BLOCKED_LOCAL_STATE
BLOCKED_POLICY
BLOCKED_AUTHORITY
BLOCKED_SECURITY
BLOCKED_VISIBILITY
BLOCKED_ACCESS_RIGHTS
BLOCKED_USAGE_RIGHTS
BLOCKED_PRIVATE_EGRESS
BLOCKED_CONFLICT
BLOCKED_DESTRUCTIVE_TRANSITION
FAILED_TOOL
FAILED_EVAL
```

For this repository, `AUTOMATED_MERGED` is unavailable until a repository-owned policy explicitly preauthorizes trusted automation and exact merge gates pass.

## Current Stack PR subject

```yaml
stack:
  issue: 6
  pull_request: 15
  base: feat/kmp-agent-browser-foundation
  head: docs/agent-integration-stack-index
  rollback_subject: a449fac24b8ee602b3c36ae60e972fe25f35c516
  class: foundation_child
  owned_paths:
    - .git-town.toml
    - AGENTS.md
    - README.md
    - README.zh-TW.md
    - docs/automation/**
    - docs/git/**
    - docs/harness/**
    - docs/TRACEABILITY.md
    - .github/ISSUE_TEMPLATE/**
    - .github/PULL_REQUEST_TEMPLATE.md
  excluded_paths:
    - composeApp/src/**
    - iosApp/**
    - LICENSE
    - NOTICE
    - signing_or_secret_material
```

## Postcondition requirements

Before reporting a published documentation head as admitted, compare before/after and require:

```yaml
postconditions:
  visibility: unchanged
  owner: unchanged
  default_branch: unchanged
  license_blob: unchanged
  notice_blob: unchanged
  main_ref: unchanged
  foundation_ref: unchanged
  remote_topology: unchanged
  access_or_ruleset_mutation: none
  secrets_or_signing_mutation: none
  private_data_egress: none_observed
  local_user_state: NOT_EXERCISED_when_no_local_checkout
  governance_branch: fast_forward_only
  exact_head_ci: required
```

A `NOT_EXERCISED` local lane is reported explicitly; it is never rewritten as `PASS`.
