# Git Town Stacked PR graph and molecular traceability

This is the repository-wide branch/issue/path/eval index for the Creator Capability Browser convergence. It separates actual remote ancestry, planned Git parents, process/completion dependencies, and evidence ceilings.

## Laws

```text
Git parent edge = child consumes unmerged parent bytes
process dependency = required subject/receipt, not necessarily Git ancestry
one writer = one active path lease
issue/branch/PR/sync/CI/merge/release are separate states
moved or integrated bytes require fresh evidence
```

Canonical procedure: `ed3c/skills-shared/skills/git-town-stacked-pr-worker`.

Live Git Town executable, linked worktrees, leases, dry-run/no-push sync, conflict canaries and publication receipts remain `ABSENT` / `NOT_EXERCISED`. This document does not authorize Git Town execution or merge.

## Actual Creator remote graph

```text
main@290a82f0394a42e0c20949a36ab575229b95051d
└── agent/media-rights-risk-register@8e2181e11144ae5bb349c1a0aa9b790485d60c4d
    issue #80 / Draft PR #81 / base main
    └── agent/community-skill-edition-design@d8b105ba1bb7be88caf9ae52eaa5bc31bf4667c9
        issue #82 / Draft PR #83 / base agent/media-rights-risk-register
        └── docs/creator-capability-convergence
            issue #98; exact moving head is GitHub branch metadata
```

| Branch | Issue / PR | Actual state | Evidence ceiling |
|---|---|---|---|
| `main` | default | exists | repository preservation subject |
| `agent/media-rights-risk-register` | #80 / PR #81 | open Draft | risk/documentation only |
| `agent/community-skill-edition-design` | #82 / PR #83 | open Draft child | architecture/schema only |
| `docs/creator-capability-convergence` | #98 | branch exists | documentation in progress |

All branches named below are planned unless later GitHub metadata proves they exist.

## Creator implementation topology

```text
#80 / PR #81  D0 risk and rights architecture
└── #82 / PR #83  D1 Community Edition architecture
    └── #84  C0 creator contracts
        ├── #85  A1 YouTube source adapter
        ├── #86  K1 v7.2 automatic indexer
        ├── #87  U1 card/graph/procedure editor
        ├── #88  C1 procedural compiler
        ├── #89  E1 independent qualifier
        ├── #90  P1 model/destination router
        └── #91  X1 core convergence
            process completion requires #85–#90 exact receipts
            └── #92  C2 Community SkillPatch store
                ├── #93  M1 UGC moderation
                ├── #94  R1 source/rights revocation
                ├── #95  A2 reference edition
                │   └── #96  A3 licensed render/native PiP [rights-gated]
                └── #97  E2 evidence convergence

#98 D2 shared docs convergence [current writer]
├── #99 E3 exact-head docs CI
├── #100 H1 Local Handoff Queue after concrete runtime commands
├── #101 P2 zero-context prompt pack
└── #111–#117 supporting index/snapshot/review/roadmap/DoD/non-claim/policy atoms
```

### Cross-media source topology

```text
#102 source-adapter epic
├── #85  YouTube timestamp/player
├── #103 PDF page/region/text/figure
├── #104 EPUB chapter/CFI/section
├── #105 Notion workspace/page/block
├── #106 X post/thread/article observation
├── #107 generic Web origin/navigation/DOM anchor
├── #108 Drive/Docs file/revision/structure
├── #109 local file digest/structural-or-temporal locator
└── #110 X2 source-registry convergence
    process completion requires every selected adapter receipt
```

## Start vs completion dependencies

- #84 starts after PR #83 contracts are readable; completion requires admitted parent contract subject.
- #85–#88/#90 are path-disjoint children of #84 and may proceed in parallel after #84 contract admission.
- #89 may prepare fixtures after #84 and candidate schema after #88; it completes only against exact compiler candidate subjects and remains independent.
- #91 has one chosen Git parent but process completion requires #85–#90 receipts and fresh integrated tests.
- #92 starts after #91 exact contract/runtime subject.
- #93/#94 are disjoint children of #92. #95 consumes #85/#87/#92/#94 process subjects; its Git parent is one selected admitted branch.
- #96 is serial after #95 and blocked until exact external media rights exist. It is not a reference-MVP dependency.
- #97 prepares evidence schemas early but completes only against selected implementation/device/provider subjects.
- #102 adapters are post-MVP siblings unless explicitly selected; #110 is their process convergence.
- #98 may document planned states, but cannot call them implemented. #99 is a true child because it consumes #98 files.
- #100 remains `ABSENT` until owning runtime issues expose exact commands and receipts.

## Molecular Stack table

| Order | ID | Issue | Planned head | Parent/base | Class | Writable owner | Current state |
|---:|---|---:|---|---|---|---|---|
| 0 | `CRT-D0` | #80 / PR #81 | `agent/media-rights-risk-register` | `main` | architecture | `docs/security/**` | Draft published |
| 1 | `CRT-D1` | #82 / PR #83 | `agent/community-skill-edition-design` | D0 | architecture child | selected `docs/creator/**` design files | Draft published |
| 2 | `CRT-C0` | #84 | `feat/creator-content-contracts` | D1 | contract child | creator contracts/policy/schemas | `NOT_IMPLEMENTED` |
| 3a | `CRT-A1` | #85 | `feat/youtube-source-adapter` | C0 | source child | YouTube adapter paths | `NOT_IMPLEMENTED` |
| 3b | `CRT-K1` | #86 | `feat/v72-auto-indexer` | C0 | sibling | indexing paths | `NOT_IMPLEMENTED` |
| 3c | `CRT-U1` | #87 | `feat/creator-card-editor` | C0 | sibling | editor/UI paths | `NOT_IMPLEMENTED` |
| 3d | `CRT-C1` | #88 | `feat/procedural-skill-compiler` | C0 | sibling | compiler paths | `NOT_IMPLEMENTED` |
| 3e | `CRT-E1` | #89 | `test/procedural-skill-qualifier` | C0 + candidate dependency | independent qualifier | qualification paths | `NOT_IMPLEMENTED` |
| 3f | `CRT-P1` | #90 | `feat/creator-model-destination-router` | C0 | sibling | provider/export paths | `NOT_IMPLEMENTED` |
| 4 | `CRT-X1` | #91 | `feat/creator-pipeline-convergence` | selected C0 child | process convergence | creator runtime/e2e paths | `NOT_IMPLEMENTED` |
| 5 | `CRT-C2` | #92 | `feat/community-skill-patches` | X1 | child | community model/store | `NOT_IMPLEMENTED` |
| 6a | `CRT-M1` | #93 | `feat/community-edition-moderation` | C2 | child | moderation/abuse | `NOT_IMPLEMENTED` |
| 6b | `CRT-R1` | #94 | `feat/creator-source-revocation` | C2 | child | freshness/revocation | `NOT_IMPLEMENTED` |
| 6c | `CRT-A2` | #95 | `feat/community-reference-edition` | C2 + process deps | product convergence | reference playback/UI | `NOT_IMPLEMENTED` |
| 7 | `CRT-A3` | #96 | `feat/licensed-community-render` | A2 | rights-gated child | licensed render/PiP | blocked / `NOT_IMPLEMENTED` |
| 7b | `CRT-E2` | #97 | `test/creator-capability-evidence` | selected implementation | evidence convergence | tests/scripts/receipts | `NOT_IMPLEMENTED` |
| D | `CRT-D2` | #98 | `docs/creator-capability-convergence` | D1 | shared docs convergence | root/creator indexes | in progress |
| D+ | `CRT-E3` | #99 | `ci/creator-docs-convergence` | D2 | true child | docs CI/receipts | planned |
| H | `CRT-H1` | #100 | future | D2 + runtime commands | local handoff | handoff docs/queue | `ABSENT` |
| P | `CRT-P2` | #101 | future | D2 | prompt docs | creator prompts | planned |
| S | `CRT-SRC` | #102 | epic | C0 | source expansion | issue routing | planned |
| S1–S7 | `CRT-A4..A9` | #103–#109 | source-specific heads | C0 | source siblings | adapter-specific paths | `NOT_IMPLEMENTED` |
| SX | `CRT-X2` | #110 | `feat/creator-source-registry` | one selected adapter | process convergence | source registry | `NOT_IMPLEMENTED` |
| DA | `CRT-D3` | #111–#117 | #98-owned or later | D2 | docs/review | exact issue leases | planned |

## Single-writer lease index

- #98 is the current writer for root `README.md`, `README.zh-TW.md`, `AGENTS.md`, `docs/TRACEABILITY.md`, and `docs/git/STACKED_PRS.md` plus current creator index files.
- #75 retains nested OpenDroid integration ownership. It must not start a concurrent root writer; later root reconciliation consumes or supersedes exact #98 head and reruns global checks.
- Runtime/source/compiler/community leaves exclude shared indexes.
- #91/#95/#110 each own only convergence code paths; they do not rewrite leaf implementations.

Overlap returns `BLOCKED_BRANCH_LEASE`.

## Eval and negative-control index

| Atom | Required proof | Planted failure |
|---|---|---|
| #84 | sealed contract/schema round trips and mode laws | broad allow, case→law, rights laundering, source leakage |
| #85 | official player/identity/seek/fallback state | overlay/ads/PiP/Premium/transcript misuse |
| #86 | semantic segmentation/stable cards/dedup | fixed chunks, lost locator/edge, contradiction collapse |
| #87 | immutable editor revisions and typed source navigation | player overlap, evidence mutation, seek→view claim |
| #88 | deterministic IR/candidate rendering | raw-source fill, self-verdict, generic hollow rule, style leak |
| #89 | G1–G8 independent qualification | no stop/oracle/negative trigger, shared compiler state |
| #90 | destination/minimization/host portability | consumer token reuse, private egress, provider authority |
| #91 | end-to-end exact digests and boundary bypass tests | skip rights, compiler self-qualify, stale cards, model→authority |
| #92 | immutable patch/version/conflict/tombstone | likes→truth, raw source payload, history rewrite |
| #93 | executable UGC controls | prose-only controls, self-moderation, model legal verdict |
| #94 | impact/reindex/takedown/cleanup | cached playback after deletion, stale locator, fake deletion receipt |
| #95 | reference edition source dock/seek/fallback | hidden montage, YouTube PiP label, cached source, seek→view |
| #96 | exact rights/render/PiP subject | standard YouTube/partial CC/voice clone/expired rights |
| #97 | literal evidence lanes and receipts | emulator→device, integrity→rights, black frame→empty, hidden absence |
| #98/#99 | issue/PR/head/DAG/lease/index consistency | issue→implementation, fake ancestry, #75/#98 overlap, omitted gates |
| #103–#110 | source-specific locator/rights/capability behavior | DRM/auth/CSP/action bypass, stale revision, fake parity |

## Publication and merge

A branch may publish only after its exact task packet, path lease, local-state snapshot, fixed evals, Shadow checkpoints, disclosure scan and remote ancestry checks are satisfied. The current connector session does not prove local checkout or Git Town execution.

Merge, reparenting after parent merge, branch deletion, legal/platform/store acceptance, release and production remain `EXTERNAL_AUTHORITY_REQUIRED`. Leave Draft PRs open rather than weakening the contract.
