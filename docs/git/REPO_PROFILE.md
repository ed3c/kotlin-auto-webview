# Repository profile — Git Town Stacked-PR Worker

This is the repository-owned profile consumed with the shared `git-town-stacked-pr-worker` Skill. Unknown evidence is recorded explicitly and blocks the corresponding operation.

## Identity

```yaml
schema: git-town-stacked-pr-worker/repo-profile/v1
repository:
  full_name: ed3c/kotlin-auto-webview
  immutable_identity: github-repository-id:1334777764
  default_branch: main
  perennial_branches:
    - main
  allowed_remote_name: origin
  allowed_remote_url_pattern: '^https://github\.com/ed3c/kotlin-auto-webview(?:\.git)?$'
```

## Authority documents

```yaml
authority:
  agents: AGENTS.md
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
```

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

Live state:

```yaml
sync_evidence:
  static_config_review: PASS
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
  wrapper_state: NOT_IMPLEMENTED
  live_canary: NOT_EXERCISED
```

Host-owned logical selectors are not secret paths and must be resolved by the trusted runtime without entering portable receipts.

## Receipt policy

```yaml
receipts:
  root: receipts/git-town
  schema: git-town-stacked-pr-worker/receipt/v1
  append_only: true
  max_stream_bytes: 65536
  secret_values: denied
  absolute_secret_paths: denied
  task_packet_digest_required: true
  before_after_graph_required: true
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
```

Background execution remains disabled until executable, wrapper, lease, receipt, and conflict-canary admission is complete.

## Publication policy

```yaml
publication:
  enabled: false
  allowed_intents:
    - initial-pr
    - ready-for-review
    - batched-repair
  task_packet_authorization_required: true
  explicit_cli_flag: --publish
  environment_guard_name: KOTLIN_AUTO_WEBVIEW_ALLOW_PUBLISH
  environment_guard_expected_value: admitted
  allowed_remote: origin
  protected_branch_rewrite: denied
  post_push_fetch_and_verify: true
  exact_head_gate: NOT_IMPLEMENTED
  snapshot_schema: github-actions-publish-snapshot/v1
  local_verification_schema: github-delivery-local-verification/v1
  trusted_check_name: CI
  draft_pr_runner_policy: NOT_IMPLEMENTED
  billing_circuit_policy: fail-closed
```

The guard value is not a secret. Publication remains disabled and `BLOCKED_POLICY` until the exact-head gate and workflow posture are implemented and tested.

## Prompt suppression

```yaml
unattended_environment:
  GIT_TERMINAL_PROMPT: '0'
  GIT_EDITOR: ':'
  GIT_SEQUENCE_EDITOR: ':'
  GCM_INTERACTIVE: Never
```

Receipts record variable names/presence only, never values beyond repository-approved non-secret guards.

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
```

## Required eval commands

```yaml
evals:
  commands:
    - ./gradlew :composeApp:allTests
    - ./gradlew :composeApp:compileKotlinDesktop
    - ./gradlew :composeApp:wasmJsBrowserDistribution
    - ./gradlew :composeApp:assembleDebug
    - ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64  # macOS lane
  live_git_town_canary: NOT_EXERCISED
  conflict_canary: NOT_EXERCISED
  publication_canary: NOT_EXERCISED
```

Leaf task packets may add stricter typed commands. They cannot remove load-bearing full-matrix checks for shared contracts/build configuration.

## Forbidden paths and data

```yaml
forbidden:
  paths:
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
    - unbounded_model_output
```

## Human-owned operations

```yaml
human_owned:
  - semantic_conflict_resolution
  - git_town_continue_skip_undo_ship
  - merge_or_merge_queue_admission
  - branch_protection_or_permission_change
  - legal_or_license_acceptance
  - secret_credential_or_signing_setup
  - store_submission
  - release_promotion
  - production_deployment
  - destructive_or_drifted_rollback
```

## Profile verdict

```yaml
profile_documentation: PASS
static_git_town_config: PASS
host_executable_admission: ABSENT
worker_wrapper_and_leases: NOT_IMPLEMENTED
live_sync_and_conflict_canaries: NOT_EXERCISED
publication_gate: NOT_IMPLEMENTED
worker_execution_verdict: BLOCKED_POLICY
```
