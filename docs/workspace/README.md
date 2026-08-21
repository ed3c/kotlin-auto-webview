# Federated Capability Workspace

Issue: #119. Pre-implementation convergence: #127.

This plane makes `kotlin-auto-webview` the **experience and routing center** across research, work, Skills and outcomes without making its local database or UI a second source of truth.

## Product boundary

```text
Kotlin Auto WebView = browse / inspect / edit local drafts / route / display
Bettor Arena        = orchestrate Workers, Gates, retries and receipts
GitHub              = canonical work + implementation + exact-head evidence graph
Google Docs/Sheets  = human-readable projections
Domain repositories = canonical source/claim/method/qualification/runtime/outcome authorities
```

## End-to-end target

```text
article / PDF / YouTube / X / Notion / Google Drive / repository
→ admitted SourceRef + revision
→ evidence/card authority
→ claim verification
→ requirement/capability decomposition
→ technology + commercial-rights decision
→ typed RouteRequest
→ GitHub work packet / Bettor orchestration
→ implementation + exact receipt
→ Skill qualification where applicable
→ Google human projection with read-back
→ user experiment/outcome
→ revision / narrow / refute / preserve
```

No arrow inherits authority from the previous layer. A model answer cannot grant source rights, a Google edit cannot close a GitHub issue, and a PR cannot create a user-outcome claim.

## Authority owners

| Subject | Canonical owner | Workspace role |
|---|---|---|
| source bytes/access/revision | source platform or admitted source repository | resolve locator, show state |
| source cards/evidence lineage | `ai-content-notes` | project and route |
| mutable claim closure | `truth-verify-loop` | show exact verdict |
| market/technology/right decision | `ai-product-notes` | show decision and gap |
| capability routing / Prompt-to-Skill | `tech-implementation-atlas` | submit request, display trace |
| portable method | `skills-shared` | consume exact Skill identity |
| Skill runtime qualification | `Skill.md-native` | display exact verdict |
| experiment outcome | `blackbox-auto-research` or owning product | launch/read receipt |
| runtime profile | `runtime-env` | select admitted binding |
| Worker/Gate orchestration | `bettor-arena` | request/status/receipt ref |
| issue/PR/commit/check | GitHub | canonical work graph |
| architecture narrative | Google Docs | projection only |
| dashboard/status | Google Sheets | projection only |
| browsing/editing draft state | local SQLDelight | local-only projection/draft |

## Molecular implementation plan

```text
#119 Federated Capability Workspace epic
└── #120 W0 contracts
    ├── #121 W1 local graph + outbox/inbox
    ├── #122 W2 GitHub WorkGraph adapter
    ├── #123 W3 Google projection/read-back
    ├── #124 W4 federation router
    └── #125 W5 read-only Workspace UI
         └── #126 W6 cross-system evidence

#127 W7 pre-implementation docs convergence
└── #129 W8 public/private reference URL registry [this child stack]
    └── #130 registry privacy/parity CI [planned]
```

Cross-repository consumers:

```text
kotlin-auto-webview #120/#124
        ↓ immutable admitted contract only
bettor-arena #197

ai-content-notes #41 decision
        ↓ DUAL_RUN_PROJECTION_ONLY
ai-content-notes #55 note-specific Google projection

ai-content-notes #51 source registry
        ↓
ai-content-notes #56 private/full URL registry
        ↕ same stable REF-* IDs
kotlin-auto-webview #129 public/privacy-safe URL registry
```

## Planned code topology

```text
composeApp/src/commonMain/kotlin/dev/ed3c/autowebview/workspace/
├── contract/      # W0 stable federation types
├── registry/      # W1 local subject/edge projection
├── sync/          # W1 outbox/inbox/conflicts
├── github/        # W2 WorkGraph adapter
├── google/        # W3 Docs/Sheets projection adapter
├── routing/       # W4 typed authority routing
├── ui/            # W5 native workspace
└── viewmodel/     # W5 state projection

schemas/workspace/ # W0/W3/W6 public contracts
receipts/workspace/# W6 public-safe evidence only
```

These paths are **planned**, not current implementation.

## Evidence vocabulary

```text
PASS
FAIL
ABSENT
NOT_IMPLEMENTED
NOT_EXERCISED
SKIPPED_BY_POLICY
DENIED_BY_ARCHITECTURE
EXTERNAL_AUTHORITY_REQUIRED
PREIMPLEMENTATION_CLOSED
URL_INDEXED
```

`PREIMPLEMENTATION_CLOSED` means owners, contracts, path leases, DAGs, negative controls and evidence ceilings are ready. It does not mean any workspace runtime is implemented. `URL_INDEXED` means a locator is registered; it is weaker than identity/revision/read-back/rights/claim verification.

## Current decision about Google Workspace

`ai-content-notes#41` is resolved architecturally as:

```text
Git/GitHub exact artifacts = canonical persistence/work/evidence authority
Google Docs               = long-form human projection
Google Sheets             = dashboard/tabular projection
manual Google edit        = ChangeProposal only
```

Do not create a manually maintained control-plane Doc or Sheet before #123 provides file-ID/revision binding, idempotent outbox, authenticated read-back and conflict semantics. That would recreate the split-brain state this design removes.

## Reference URL registry

All material public platform documentation, policy pages, public repositories, technology candidates, canonical prompt/architecture artifacts and research URLs used by this architecture must be assigned a stable `REF-*`.

Public machine shards:

- [`reference-index.public.json`](reference-index.public.json)
- [`reference-index.public.research.json`](reference-index.public.research.json)

Human index: [`REFERENCE_INDEX.md`](REFERENCE_INDEX.md).

Private Google Docs/Sheets/Drive, private repository locators and private canonical Skill/method URLs are deliberately absent from this public repository. Their full locators live under private `ed3c/ai-content-notes#56`, while KAW retains only opaque shared `REF-*` identities.

```text
public reference → full URL in KAW
private reference → opaque REF-* in KAW → full locator in private registry
```

URL/title remains a locator, not authority. Future W0/W2/W3/source-registry implementation must bind exact revisions/digests/read-back before stronger evidence states.

## Start here

1. `docs/workspace/AGENTS.md`
2. `docs/workspace/REFERENCE_INDEX.md`
3. `docs/workspace/reference-index.public.json`
4. `docs/workspace/reference-index.public.research.json`
5. `docs/workspace/AUTHORITY_AND_IDENTITY.md`
6. `docs/workspace/STATE_MACHINES_AND_DAG.md`
7. `docs/workspace/TECHNOLOGY_ADMISSION.md`
8. `docs/workspace/CURRENT_STATE.md`
9. exact issues #119–#130 as applicable
10. destination repository's own `AGENTS.md` / README / exact receipts
