# Creator Capability State Machines, DAGs, and data flow

## Realm topology

```text
Source/platform realm
→ adapter observation boundary
→ Kotlin privacy/policy realm
→ v7.2 evidence/card realm
→ compiler candidate realm
→ independent qualification realm
→ creator/community runtime realm
→ outcome/evidence realm
```

No downstream realm can manufacture authority for an upstream source, rights, identity or platform operation.

## Start-readiness DAG

```text
PR #83 contract readable
└── #84 creator contracts
    ├── #85 YouTube adapter
    ├── #86 indexer
    ├── #87 editor
    ├── #88 compiler
    │   └── #89 qualifier candidate dependency
    ├── #90 provider router
    └── #102 source epic
        ├── #103 PDF
        ├── #104 EPUB
        ├── #105 Notion
        ├── #106 X
        ├── #107 Web
        ├── #108 Drive/Docs
        └── #109 local
```

## Completion-readiness DAG

```text
#84 receipt
→ selected leaves #85–#90 receipts
→ #91 fresh core convergence
→ #92 community store
→ {#93 moderation, #94 revocation, #95 reference edition}
→ #97 exact evidence convergence
→ #98/#99 current documentation and exact-head docs receipt
→ #113 independent global Shadow review
→ Human merge/release/store/legal decision

#96 licensed render is a separate rights-gated child after #95.
#102–#110 multi-source expansion is post-MVP unless a source is selected for the initial slice.
```

## Branch topology vs process DAG

Git Town parentage records only consumed unmerged bytes. #91, #95, #97 and #110 require several exact subjects but select one Git parent and rerun integrated verification. They are not octopus/multi-parent branches.

## State machines

### Source admission

```text
REQUESTED
→ IDENTITY / REVISION BOUND
→ ACCESS / RIGHTS / DESTINATION / RETENTION CLASSIFIED
→ RENDER / OBSERVE / LOCATOR / EXCERPT / MODEL / DERIVE / PUBLISH decisions
→ READY | DEGRADED | BLOCKED | EXTERNAL_AUTHORITY_REQUIRED
```

### Auto-index

```text
EVENTS
→ COARSE SEGMENTS
→ SEMANTIC BOUNDARIES
→ Q/C/D/S/P/E/F/T/R/X CARDS
→ TYPED LINKS
→ PROCEDURAL SIGNAL SCORE
→ DEDUP WITH ALL EVIDENCE EDGES
→ CANDIDATE CLUSTERS
```

### Compiler/qualifier

```text
SELECTED CARD DAG
→ PROCEDURAL ATOMS
→ HIDDEN STATE MACHINE
→ INVARIANTS
→ COUNTERFACTUALS / CONFOUNDERS
→ CROSS-CASE INTERSECTION
→ TRANSFER BOUNDARY
→ CANDIDATE IR / SKILL
→ INDEPENDENT G1–G8 QUALIFICATION
→ QUALIFIED | NOT_QUALIFIED
```

### Community

```text
PATCH DRAFT
→ SCHEMA / RIGHTS / LEAKAGE
→ MODERATION
→ QUALIFICATION
→ VARIANT / CONFLICT
→ EDITION VERSION
→ PRIVATE PREVIEW
→ PUBLIC READY | BLOCKED
→ SUPERSEDED / WITHDRAWN / REMOVED
```

### Playback

```text
SOURCE BOUND
→ EMBED / FEATURE / IDENTITY PROBED
→ READY | DENIED | LOGIN-AGE-REGION | UNAVAILABLE | ERROR
→ USER CARD SELECTED
→ SOURCE REVALIDATED
→ USER SEEK | OFFICIAL CLIP | OPEN OFFICIAL APP
→ OBSERVED RESULT | FAILED | EXTERNAL APP
```

### Revocation

```text
FRESHNESS CHECK
→ UNCHANGED | CHANGED | PRIVATE | DELETED | EMBED DISABLED | RIGHTS REVOKED
→ IMPACT GRAPH
→ REINDEX | LOCATOR ONLY | PARTIAL/FULL TAKEDOWN
→ CACHE / PROVIDER / PUBLICATION CLEANUP RECEIPTS
```

### Outcome foldback

```text
SKILL VERSION + CREATOR PROFILE + EXPERIMENT
→ LEADING / DECISIVE METRICS
→ USER_OUTCOME
→ PRESERVE | STRENGTHEN | NARROW | REVISE | REFUTE
```

## Cross-source locator contract

| Source | Locator identity |
|---|---|
| YouTube | platform/video/revision + `tStart/tEnd` + player observation state |
| PDF | file digest/revision + page + region/text/figure |
| EPUB | publication digest/revision + chapter/section/CFI |
| Notion | workspace/page/block + revision/access state |
| X | post/thread/article ID + observed revision/freshness |
| Web | origin/URL/navigation + DOM anchor/fingerprint |
| Drive/Docs | file/document ID + Drive revision + structure locator |
| Local | file digest/revision + structural/temporal locator |

## Global failure flow

```text
unknown rights/destination
→ block only egress/retention/publication; locator-only work may continue when admitted

provider unavailable/quota
→ preserve deterministic card/editor state; expose typed failure

source changed/deleted
→ invalidate stale locators; impact downstream evidence and editions

qualification failure
→ keep candidate/evidence; return exact missing gate; do not auto-publish or auto-repair authority

renderer/process death
→ restore durable editor/card state; never restore stale player/action authority
```
