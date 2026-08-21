# L2 public GitHub canary preparation instructions

These instructions govern `docs/workspace/live/github-public-canary/**` and the matching schema, checker, tests, and workflow owned by issue #178. Repository-root and ancestor `AGENTS.md` files remain authoritative.

## Exact subject

```text
repository       ed3c/kotlin-auto-webview
delivery issue   #165
preparation      #178
prep parent      #176 / Draft PR #177
prep base head   a7032eaf4f5a3ffaece06d01657897ad0444344a
prep base tree   e49575deb635d206bf78d0070783fca5865ab5fd

W2 code parent   PR #158
W2 head          3294fb2b4d86fef91f3f2c63e28718c490147808
transport blob   9b677be0c8bd1da0f1488acb83488677eae11a81
```

The preparation branch is a child of PR #177 because it consumes the Wave-1 lock. The future #165 implementation branch must remain a child of W2 PR #158. PR #177 and this preparation receipt are evidence dependencies, not implementation ancestry.

## Mandatory read order

1. repository-root `AGENTS.md`;
2. `docs/workspace/AGENTS.md`;
3. `docs/workspace/live/AGENTS.md`;
4. `docs/workspace/live/wave1/AGENTS.md`;
5. `docs/workspace/live/wave1/contract-lock.json`;
6. this file;
7. `README.md`;
8. `canary-contract.json`;
9. `EXECUTION_PACKET.md`;
10. issue #165, issue #178, PR #177, and current exact checks;
11. W2 `GitHubRestMetadataSource.kt`, contracts, adapter, and W1 registry at their pinned blobs.

Chat history and mutable branch names are not interface authority.

## Authority map

```text
GitHub public API                 external read-only metadata source
GitHubRestMetadataSource         existing W2 transport implementation
GitHubWorkGraphMapper/Adapter     W2 mapping and projection boundary
SqlDelightWorkspaceRegistry      W1 local projection authority
#178 contract/checker             preparation and exact-subject lock only
#165 implementation              future Kotlin canary and literal L2 receipt
GitHub token/private scope        Human/external authority
merge/release/production          Human authority
```

The checker may read public GitHub metadata to prove the preparation subject still exists. It cannot emit a `LIVE_GITHUB_CONNECTOR` receipt.

## State machine

```text
WAVE1_LOCK_VERIFIED
→ PUBLIC_CANARY_SUBJECT_BOUND
→ REMOTE_PREP_READBACK_VERIFIED
→ IMPLEMENTATION_PACKET_READY
→ L2_PUBLIC_CANARY_IMPLEMENTATION_PREP_READY

future #165 only:
IMPLEMENTATION_PACKET_READY
→ KOTLIN_CANARY_IMPLEMENTED
→ APP_TRANSPORT_EXECUTED
→ W2_MAPPING_VERIFIED
→ W1_READBACK_VERIFIED
→ SANITIZED_L2_RECEIPT
```

No preparation state may skip into the future execution states.

## Writer lease

Issue #178 may write only:

```text
docs/workspace/live/github-public-canary/**
schemas/workspace/live-github-public-canary.schema.json
scripts/ci/check-workspace-live-github-public-canary.py
tests/docs/workspace/live/github-public-canary/**
.github/workflows/workspace-live-github-public-canary.yml
```

It must not write `composeApp/src/**`, live receipts, root shared documents, W2 source, or W1 persistence.

## Hard laws

```text
EXISTING_TRANSPORT != NEW_TRANSPORT_REQUIRED
PREP_REMOTE_READBACK != APP_LIVE_CANARY
WORKFLOW_RUN != CHECK_FOR_ANOTHER_HEAD
PUBLIC_READ != PRIVATE_SCOPE
READ_ACCESS != CANONICAL_CORRECTNESS
CHECK_SUCCESS != USER_OUTCOME
NO_CREDENTIAL_PUBLIC_MODE != PRIVATE_AUTHORITY
```

Only `GET` requests to `https://api.github.com` are admitted. No mutation endpoint, token, session, cookie, private locator, email, or credential-shaped value may enter the public preparation artifacts.

## Stop conditions

Stop and report a typed blocker when:

- repository, Issue, PR, commit, tree, workflow run, job, path, blob, or symbol drifts;
- PR #177 no longer points to the exact canary head;
- any expected check belongs to a different head;
- the endpoint is not the public `api.github.com` HTTPS origin;
- a second transport is proposed;
- private scope is treated as available;
- Local Handoff is marked executable before the Kotlin target exists;
- the evidence ceiling is widened above preparation.

## Worker output

Return exact base/head/tree, changed paths, checker/test commands and exits, remote subjects read, mutations exercised, maximum claim, blocked private lane, cleanup, and next owner. Do not report L2 PASS.
