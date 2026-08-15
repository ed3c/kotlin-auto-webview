# Repository profile — Git Town Stacked-PR Worker

This is the repository-owned profile consumed with the canonical `git-town-stacked-pr-worker` Skill and the repository autonomous dual-lane binding. Unknown evidence is recorded explicitly and blocks only the corresponding operation.

## Identity

```yaml
schema: git-town-stacked-pr-worker/repo-profile/v1
repository:
  full_name: ed3c/kotlin-auto-webview
  immutable_identity: github-repository-id:1334777764
  visibility: public
  owner: ed3c
  default_branch: main
  perennial_branches:
    - main
  allowed_remote_name: origin
  allowed_remote_url_pattern: '^https://github\.com/ed3c/kotlin-auto-webview(?:\.git)?$'
```

Visibility, owner, default branch, access rights, rulesets, remotes, and repository settings are immutable under autonomous Agent work.

## Authority documents

```yaml
authority:
  agents: AGENTS.md
  root_readme: README.md
  autonomous_control: docs/automation/README.md
  autonomous_profile: docs/automation/REPOSITORY_PROFILE.md
  architecture: docs/architecture/README.md
  traceability: docs/TRACEABILITY.md
  security: docs/security/THREAT_MODEL.md
  git_governance: docs/git/README.md
  stack_graph: docs/git/STACKED_PRS.md
  worker_protocol: docs/git/WORKER_PROTOCOL.md
  harness: docs/harness/README.md
  path_ownership: docs/git/STACKED_PRS.md
  git_town_admission: docs/git/GIT_TOWN_ADMISSION.md
  issue_template: .github/ISSUE_TEMPLATE/stacked-pr-task.md
  pull_request_template: .github/PULL_REQUEST_TEMPLATE.md
canonical_skills:
  spatial_loop: ed3c/skills-shared@main:skills/spatial-loop-systems-engineering
  git_town_worker: ed3c/skills-shared@main:skills/git-town-stacked-pr-worker
```

A project-local copy of either canonical Skill is a governance error.

## Autonomous runtime binding

```yaml
autonomy:
  mode: FULL_AUTOMATION
  interaction_policy: NON_INTERACTIVE
  operating_mode: MONITOR
  immutable_safety_envelope: required
  blocked_transition_scope: named_transition_only
  continue_path_disjoint_work: true
  merge_only_if_repository_pre_authorizes: true
  current_merge_preauthorization: ABSENT
  merge_state: EXTERNAL_AUTHORITY_REQUIRED
```

The current GitHub connector admits branch/issue/PR maintenance within existing rights. It does not expose a local checkout, so local user-state and linked-worktree lanes remain `NOT_EXERCISED`.

## Git Town admission

```yaml
git_town:
  version: v24.0.0
  source_repository: git-town/git-town
  immutable_release: github-release-id:358702660
  release_published_at: 2026-07-23T13:48:21Z
  release_immutable: true
  checksums_manifest_sha256: 7532377166cb59dc01c74f86e3a71c54ba9567a461313a5d203a1ea99c571b24
  platform: ABSENT
  architecture: ABSENT
  executable_asset: ABSENT
  executable_sha256: ABSENT
  provenance_ref: ABSENT
  direct_license: MIT
  direct_license_ref: git-town/git-town@v24.0.0:LICENSE
  direct_license_sha256: NOT_EXERCISED
  sbom_or_transitive_review: NOT_EXERCISED
  notices_review: NOT_EXERCISED
  legal_approval: ABSENT
  executable_admission_state: ABSENT
```

The selected source release is not an admitted host executable. See `GIT_TOWN_ADMISSION.md`.

## Synchronization policy

```yaml
sync:
  feature_strategy: rebase
  perennial_strategy: ff-only
  default_scope: stack
  non_interactive: true
  auto_resolve: false
  default_push: false
  allow_all_stacks: false
  timeout_seconds: 300
  dry_run_required: true
  post_sync_ancestry_check: true
  rerun_evals_after_sync: true
```

Deviations from safe defaults: none.

```yaml
sync_evidence:
  static_config_review: PASS
  local_checkout_visible_in_current_runtime: NOT_EXERCISED
  live_dry_run: NOT_EXERCISED
  live_no_push_sync: NOT_EXERCISED
  planted_conflict: NOT_EXERCISED
  post_sync_ancestry: NOT_EXERCISED
```

## Worktree and lease policy

```yaml
workers:
  primary_checkout_mutation: denied
  linked_worktree_required: true
  worktree_root: host-owned:linked-worktrees/kotlin-auto-webview
  branch_lease_root: host-owned:leases/kotlin-auto-webview/branches
  path_lease_root: host-owned:leases/kotlin-auto-webview/paths
  repository_lease: required-for-ref-mutation
  lease_ttl_seconds: 900
  sibling_path_overlap: denied
  preserve_blocked_worktree: true
  preserve_user_uncommitted_state: strictly
  destructive_cleanup: denied
  wrapper_state: NOT_IMPLEMENTED
  live_canary: NOT_EXERCISED
```

Host-owned logical selectors are resolved by the trusted runtime and do not enter portable receipts as absolute paths.

## Receipt policy

```yaml
receipts:
  git_town_root: receipts/git-town
  git_town_schema: git-town-stacked-pr-worker/receipt/v1
  autonomous_schema: kotlin-auto-webview/autonomous-receipt/v2
  append_only: true
  max_stream_bytes: 65536
  secret_values: denied
  absolute_secret_paths: denied
  customer_or_private_data: denied
  task_packet_digest_required: true
  exact_head_and_tree_required: true
  before_after_graph_required: true
  safety_before_after_required: true
  shadow_delta_ledger_required: true
  cleanup_lane_required: true
  implementation_state: NOT_IMPLEMENTED
```

## Background policy

```yaml
background:
  enabled: false
  max_iterations: 3
  interval_seconds: 60
  no_push: true
  stop_on_blocked_state: true
  stop_on_task_packet_change: true
  stop_on_lease_loss: true
  stop_on_conflict: true
  stop_on_failed_eval: true
  continue_independent_siblings: true
```

Background execution remains disabled until executable, wrapper, lease, receipt, and conflict-canary admission is complete.

## Publication policy

```yaml
publication:
  enabled_for_general_worker: false
  existing_review_surface_maintenance: admitted_through_current_github_identity
  allowed_intents:
    - initial-pr
    - ready-for-review
    - batched-repair
  task_packet_authorization_required: true
  explicit_cli_flag: --publish
  environment_guard_name: KOTLIN_AUTO_WEBVIEW_ALLOW_PUBLISH
  environment_guard_expected_value: admitted
  allowed_remote: origin
  same_repository_and_visibility_only: true
  disclosure_scan_required: true
  protected_branch_rewrite: denied
  post_push_fetch_and_verify: true
  exact_head_gate: NOT_IMPLEMENTED
  snapshot_schema: github-actions-publish-snapshot/v1
  local_verification_schema: github-delivery-local-verification/v1
  trusted_check_name: CI
  billing_circuit_policy: fail-closed
  repository_allow_auto_merge: false
  trusted_automation_merge_preauthorization: ABSENT
  merge_state: EXTERNAL_AUTHORITY_REQUIRED
```

The non-secret guard value is not permission by itself. General Worker publication remains `BLOCKED_POLICY` until the exact-head gate is implemented and tested. An already-open governance PR may be updated through the connected GitHub identity after exact forge-head checks, without claiming a local Git Town or hook lane.

## Prompt suppression

```yaml
unattended_environment:
  GIT_TERMINAL_PROMPT: '0'
  GIT_EDITOR: ':'
  GIT_SEQUENCE_EDITOR: ':'
  GCM_INTERACTIVE: Never
```

Receipts record variable names/presence only, never secret values.

## Required task packet fields

```yaml
task_packet:
  required:
    - issue_id
    - parent_issue_id
    - goal
    - non_goals
    - base_branch
    - parent_branch
    - head_branch
    - stack_class
    - allowed_paths
    - excluded_paths
    - dependencies
    - parallel_safe_siblings
    - required_evals
    - negative_or_mutation_controls
    - evidence_boundary
    - cleanup_contract
    - rollback_subject
    - human_owned_operations
    - safety_invariants
    - visibility_classification
    - usage_rights_boundary
    - private_data_boundary
    - local_user_state_boundary
    - shadow_checkpoints
```

`human_owned_operations` remains for canonical compatibility; encountering one during an autonomous run produces `EXTERNAL_AUTHORITY_REQUIRED` without a question.

## Required eval commands

```yaml
evals:
  commands:
    - ./gradlew :composeApp:allTests
    - ./gradlew :composeApp:compileKotlinDesktop
    - ./gradlew :composeApp:wasmJsBrowserDistribution
    - ./gradlew :composeApp:assembleDebug
    - ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64  # macOS lane
  safety_postcondition_check: required
  disclosure_scan: required_before_publication
  shadow_first_green_review: required
  live_git_town_canary: NOT_EXERCISED
  conflict_canary: NOT_EXERCISED
  publication_canary: NOT_EXERCISED
```

Leaf task packets may add stricter typed commands. They cannot remove load-bearing full-matrix or safety checks.

## Forbidden paths and data

```yaml
forbidden:
  paths:
    - LICENSE
    - .env
    - .env.*
    - '**/*.keystore'
    - '**/*.jks'
    - '**/*.p12'
    - '**/*.mobileprovision'
    - '**/google-services.json'
    - '**/GoogleService-Info.plist'
    - '**/DerivedData/**'
    - '**/browser-profile/**'
  data_classes:
    - credentials
    - tokens
    - private_keys
    - signing_material
    - env_values
    - cookies
    - browser_profiles
    - device_sessions
    - host_keyrings
    - customer_private_data
    - private_repository_content
    - absolute_local_paths
    - unbounded_model_output
```

`NOTICE` may be modified only by an admitted dependency task for additive attribution required by the exact dependency; it may not remove or reinterpret existing notices.

## Immutable safety invariants

```yaml
safety:
  INV-SAFE-001_visibility: immutable
  INV-SAFE-002_owner_access_rulesets_default_branch: immutable
  INV-SAFE-003_license_usage_rights_attribution: immutable
  INV-SAFE-004_local_user_state: preserve_strictly
  INV-SAFE-005_private_data_egress: denied
  INV-SAFE-006_host_execution: least_privilege
  INV-SAFE-007_protected_history_remote_topology: immutable
```

## External-authority operations

```yaml
external_authority_required:
  - semantic_conflict_resolution
  - git_town_continue_skip_undo_ship
  - merge_or_merge_queue_admission_without_pre_authorization
  - branch_protection_ruleset_access_or_permission_change
  - legal_license_attribution_or_terms_acceptance
  - secret_credential_or_signing_setup
  - store_submission
  - release_promotion
  - production_deployment_or_data_mutation
  - destructive_or_drifted_rollback
```

The Agent does not ask for these operations during the run. It preserves evidence and continues independent safe work.

## Profile verdict

```yaml
profile_documentation: PASS
static_git_town_config: PASS
autonomous_dual_lane_binding: PASS
host_executable_admission: ABSENT
local_checkout_and_user_state_lane: NOT_EXERCISED
worker_wrapper_and_leases: NOT_IMPLEMENTED
live_sync_and_conflict_canaries: NOT_EXERCISED
publication_gate: NOT_IMPLEMENTED
forge_review_surface_maintenance: PASS
merge_preauthorization: ABSENT
worker_execution_verdict: BLOCKED_POLICY
merge_verdict: EXTERNAL_AUTHORITY_REQUIRED
```
