# L2-GH future implementation packet

## Ownership

```yaml
atom: L2-GH
delivery_issue: 165
preparation_issue: 178
git_parent:
  pull_request: 158
  head_sha: 3294fb2b4d86fef91f3f2c63e28718c490147808
evidence_dependencies:
  - pull_request: 177
    head_sha: a7032eaf4f5a3ffaece06d01657897ad0444344a
  - preparation_issue: 178
implementation_branch: feat/workspace-live-github
```

## Objective

Reuse `GitHubRestMetadataSource` to exercise the exact public subject defined in `canary-contract.json`, pass the result through W2 mapping and W1 local projection, read it back, and emit a sanitized L2 receipt. Do not implement another transport.

## First safe writes

```text
composeApp/src/commonTest/kotlin/dev/ed3c/autowebview/workspace/github/live/**
composeApp/src/desktopTest/**workspace/github**
tests/evidence/workspace/github/**
scripts/evidence/workspace/github/**
receipts/workspace/live/github/**
.github/workflows/workspace-live-github.yml
docs/evidence/workspace/live-github/**
```

`composeApp/src/commonMain/.../github/live/**` is admitted only for a thin canary coordinator or receipt model that cannot be expressed in tests. It may not duplicate `GitHubRestMetadataSource`.

## Planned command targets

These are binding targets, not currently executable commands:

```text
./gradlew :composeApp:desktopTest \
  --tests 'dev.ed3c.autowebview.workspace.github.live.GitHubPublicCanaryTest'

python3 scripts/evidence/workspace/github/verify_live_github_receipt.py \
  receipts/workspace/live/github/public-canary.json
```

Local Handoff remains `ABSENT` until both paths exist on the #165 exact implementation head and a trusted runtime binds the public-network capability.

## Required flow

```text
exact no-token request
→ existing GitHubRestMetadataSource
→ exact repository/Issue/PR/commit/check validation
→ GitHubWorkGraphMapper
→ SqlDelightWorkspaceRegistry
→ exact subject/edge read-back
→ disclosure scan
→ sanitized receipt
```

## Positive oracle

- repository ID/node/full name/visibility match;
- Issue ID/node/number match;
- PR ID/node/number/head match;
- commit and tree match;
- every admitted successful check belongs to the exact head;
- W1 returns the expected subject keys and typed edges;
- receipt includes no token, email, cookie, authorization header, private locator, response body, or mutable branch-only identity.

## Negative controls

- moved PR head;
- stale successful check;
- wrong repository ID or node;
- duplicate alias/identity;
- pagination total drift;
- 403 rate limit versus permission denial;
- timeout and cancellation propagation;
- deleted resource;
- token provider unexpectedly invoked in public mode;
- non-GET method or non-`api.github.com` endpoint;
- receipt disclosure mutation;
- retry residue and temporary-file cleanup.

## Timeout and cleanup

```text
network timeout       60 seconds total
page limit            10
per page              100
retry                  bounded and receipt-visible
temporary directory   run-scoped
cleanup                remove temp response/receipt candidates
credential cleanup     none; public mode must not request a token
```

## Evidence ceiling

A successful future canary may satisfy only L2 for its exact transport version, public subject, head, time window, and environment. It cannot satisfy private access, L3–L7, canonical correctness, user outcome, merge, release, or production readiness.
