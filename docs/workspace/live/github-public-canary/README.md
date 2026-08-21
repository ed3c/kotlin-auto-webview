# L2 public GitHub canary implementation preparation

## Verdict

```text
LIVE_WAVE1_IMPLEMENTATION_SURFACES_LOCKED  PASS
L2_PUBLIC_CANARY_IMPLEMENTATION_PREP_READY PASS

KOTLIN_APP_LIVE_CANARY                     NOT_EXERCISED
W1_LIVE_READBACK                           NOT_EXERCISED
L2_LIVE_GITHUB_CONNECTOR                   NOT_EXERCISED
PRIVATE_REPOSITORY_CANARY                  BLOCKED_EXTERNAL_GITHUB_SCOPE
LOCAL_HANDOFF_EXECUTION_QUEUE              ABSENT
```

This preparation closes the gap between the Wave-1 interface lock and the first safe #165 code change. It does not run the Kotlin transport.

## Why this atom exists

W2 already contains the read-only Ktor transport. The next Worker must not create a second HTTP client. It needs one exact public subject, a bounded request, a receipt contract, a disclosure gate, and a clear distinction between Git ancestry and evidence dependency.

## Exact canary

```text
repository id    1334777764
repository node  R_kgDOT48XpA
issue            #165 / 5206660666 / I_kwDOT48XpM8AAAABNldWOg
pull request     #177 / 4325936842 / PR_kwDOT48XpM8AAAABAdiOyg
head commit      a7032eaf4f5a3ffaece06d01657897ad0444344a
head tree        e49575deb635d206bf78d0070783fca5865ab5fd
```

Expected workflow subjects are pinned in `canary-contract.json`. Every check job must point to the exact head.

## Process DAG

```text
#176 / PR #177 Wave-1 lock
          ↓ evidence dependency
#178 public canary preparation
          ↓ exact packet
#165 implementation branch from W2 PR #158
          ↓
GitHubRestMetadataSource
→ GitHubWorkGraphMapper
→ W1 local registry
→ exact local read-back
→ sanitized L2 receipt
```

## Git DAG

```text
docs/workspace-live-evidence-preflight
└── docs/workspace-live-wave1-contract-lock
    └── docs/workspace-live-github-public-canary-prep

feat/workspace-local-registry
└── feat/workspace-github-workgraph
    └── feat/workspace-live-github       # future #165 implementation
```

The two graphs intentionally differ. Preparation documents do not become the implementation code parent.

## Request boundary

The future public canary is constrained to:

```text
method       GET only
origin       https://api.github.com
credential   none
repository   ed3c/kotlin-auto-webview
subjects     repo + #165 + PR #177 + exact commit + exact-head checks
pages        bounded
timeout      bounded
```

A private canary requires a separate external scope receipt and cannot reuse the public preparation result.

## Evidence boundary

The dedicated verifier performs public remote metadata read-back. That proves the preparation contract still names real, matching subjects. It does not prove that `GitHubRestMetadataSource` ran, W1 persisted the projection, or an L2 receipt exists.

## Next transition

Issue #165 may create `feat/workspace-live-github` from W2 head `3294fb2b...`, consume this preparation exact head as evidence, and implement only the missing canary/evidence paths. Local Handoff remains absent until the Kotlin test and verifier command targets exist.
