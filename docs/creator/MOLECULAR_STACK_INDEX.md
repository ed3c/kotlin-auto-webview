# Creator Capability molecular Stack index

This file is the human-readable issue/branch/path/eval index for #79/#82. Root Git governance remains in `docs/git/STACKED_PRS.md`.

## Actual design Stack

```text
main@290a82f...
└── #80 / PR #81  agent/media-rights-risk-register@8e2181e...
    └── #82 / PR #83  agent/community-skill-edition-design@d8b105b...
        └── #98  docs/creator-capability-convergence@current
```

## Molecular implementation table

| ID | Issue | Planned branch | Owned paths | Core eval / planted control | State |
|---|---:|---|---|---|---|
| C0 | #84 | `feat/creator-content-contracts` | `creator/contract`, `creator/policy`, schemas | sealed variants; reject broad allow/case→law/rights laundering | not implemented |
| A1 | #85 | `feat/youtube-source-adapter` | YouTube source platform paths | player/identity/seek; reject overlays/PiP/Premium/media copy | not implemented |
| K1 | #86 | `feat/v72-auto-indexer` | indexing | semantic cards/dedup; reject fixed chunks/lost edges | not implemented |
| U1 | #87 | `feat/creator-card-editor` | editor/UI | immutable revisions/navigation; reject player overlap/evidence mutation | not implemented |
| C1 | #88 | `feat/procedural-skill-compiler` | compiler/IR | deterministic candidate; reject raw source/self-verdict/hollow rules | not implemented |
| E1 | #89 | `test/procedural-skill-qualifier` | qualification/fixtures | G1–G8 + mutations; reject shared compiler authority | not implemented |
| P1 | #90 | `feat/creator-model-destination-router` | provider/export | destination/minimization; reject consumer token/private egress | not implemented |
| X1 | #91 | `feat/creator-pipeline-convergence` | creator runtime/e2e | boundary bypass matrix and exact digest chain | not implemented |
| C2 | #92 | `feat/community-skill-patches` | community model/store | immutable variants/conflicts/tombstones; reject votes→truth | not implemented |
| M1 | #93 | `feat/community-edition-moderation` | moderation/abuse | executable UGC paths; reject prose-only controls | not implemented |
| R1 | #94 | `feat/creator-source-revocation` | freshness/revocation | impact/takedown; reject cached playback/stale locator | not implemented |
| A2 | #95 | `feat/community-reference-edition` | reference playback/UI | foreground dock/seek/fallback; reject hidden montage/PiP | not implemented |
| A3 | #96 | `feat/licensed-community-render` | licensed render/PiP | exact rights/output; reject standard YouTube/partial rights | rights blocked |
| E2 | #97 | `test/creator-capability-evidence` | tests/scripts/receipts | literal lanes/disagreement controls | not implemented |
| D2 | #98 | `docs/creator-capability-convergence` | shared indexes | issue/PR/head/DAG/lease consistency | in progress |
| E3 | #99 | `ci/creator-docs-convergence` | docs CI | exact-head docs checks and mutations | planned |
| H1 | #100 | future | Local Handoff queue | no placeholders; one active command | absent |
| P2 | #101 | future | prompt pack | zero-context/provider-neutral | planned |

## Source adapter Stack

| ID | Issue | Source / locator | Main negative controls | State |
|---|---:|---|---|---|
| A1 | #85 | YouTube video/timestamp/player | download, hidden PiP, Premium/session/seek→view | not implemented |
| A4 | #103 | PDF page/region/text/figure | full copy, DRM, stale page, unapproved OCR/model | not implemented |
| A5 | #104 | EPUB chapter/section/CFI | DRM bypass, source substitute, stale CFI | not implemented |
| A6 | #105 | Notion workspace/page/block | cookie extraction, visibility→ownership, private egress | not implemented |
| A7 | #106 | X post/thread/article | website action automation, cookie export, stale source | not implemented |
| A8 | #107 | Web origin/navigation/DOM anchor | wildcard bridge, CSP/origin bypass, sensitive leak | not implemented |
| A9 | #108 | Drive/Docs ID/revision/structure | embedded OAuth, token leak, org/DLP bypass | not implemented |
| A10 | #109 | local digest/structure/time | path leak, possession→ownership, DRM/codec bypass | not implemented |
| X2 | #110 | source registry convergence | fake parity/wrong adapter/hidden fallback | not implemented |

## Documentation/review atoms

- #111 source-adapter matrix in #98-owned docs.
- #112 read-only current-state snapshot.
- #113 final independent Shadow global-objective review.
- #114 MVP/post-MVP/rights-gated roadmap.
- #115 closure-class/Definition of Done.
- #116 explicit product non-claims.
- #117 mutable platform/provider policy ledger.

## Merge and review order

1. Review PR #81 then PR #83; neither is runtime evidence.
2. Admit #84 contracts before implementation leaves.
3. Run path-disjoint #85/#86/#87/#88/#90; prepare #89 independently.
4. Integrate selected exact heads through #91 and rerun full tests.
5. Build #92, then parallel #93/#94; #95 consumes exact applicable subjects.
6. Keep #96 blocked until exact rights; it is not MVP.
7. #97 records literal evidence lanes.
8. #98/#99 reconcile documentation; #113 reviews global closure.
9. Human/organization owns merge, legal/platform/store/release/production.

## Path-lease law

Leaf issues exclude root/shared indexes. #98 is current single writer. #75 remains nested OpenDroid owner and cannot concurrently rewrite root files. Convergence code owners do not edit leaf paths. Overlap is `BLOCKED_BRANCH_LEASE`.
