# Architecture, Creator Capability, automation, and delivery traceability

This index binds requirements to owners, State Machines, data flows, issues/PRs and evidence ceilings. Implementation, evidence, operation authority, market outcome and delivery state remain separate.

## Stable identifiers

```text
REQ-CRT-###  creator requirement
SM-CRT-###   creator State Machine
DF-CRT-###   creator data flow
INV-CRT-###  creator invariant
EVAL-CRT-### verifier / planted negative control
STACK-CRT-### molecular Stack atom
EV-CRT-###   exact evidence subject
```

## Current exact subjects

| Subject | Identity | State | Ceiling |
|---|---|---|---|
| v7.2 prompt | main `290a82f0394a42e0c20949a36ab575229b95051d` | materialized document | prompt/contract |
| risk register | #80 / PR #81 `8e2181e11144ae5bb349c1a0aa9b790485d60c4d` | Draft published | architecture/risk only |
| Community Edition design | #82 / PR #83 `d8b105ba1bb7be88caf9ae52eaa5bc31bf4667c9` | Draft published | architecture/schema only |
| creator docs convergence | #98 / `docs/creator-capability-convergence` | in progress | documentation only |
| implementation issues | #84–#97, #102–#110 | open/planned | no implementation evidence |
| local Git Town/checks | no exact receipt | `NOT_EXERCISED` / `BLOCKED_POLICY` | none |
| legal/platform/store/device | external | `EXTERNAL_AUTHORITY_REQUIRED` / `NOT_EXERCISED` | none |

## Creator requirements

| Requirement | Owner | SM / DF | Invariant / control | Issue / PR | Current state |
|---|---|---|---|---|---|
| `REQ-CRT-001` Every source operation is admitted independently | `docs/security`, planned `creator/contract` | `SM-CRT-001`, `DF-CRT-001` | no broad `sourceAllowed`; render≠retain≠egress≠publish | #80/PR #81, #84 | contract Draft; runtime `NOT_IMPLEMENTED` |
| `REQ-CRT-002` Source identity, revision and locator survive derivation | #84 + adapters | `SM-CRT-001`, `DF-CRT-002` | missing/stale locator mutation controls | #84, #85, #103–#110 | `NOT_IMPLEMENTED` |
| `REQ-CRT-003` YouTube uses official foreground/reference surfaces | YouTube adapter | `SM-CRT-002` | controls/ads/branding/identity intact; no media copy/PiP | #85, #95 | `NOT_IMPLEMENTED` |
| `REQ-CRT-004` Indexing is automatic and semantic | auto-indexer | `SM-CRT-003`, `DF-CRT-003` | no fixed chunk boundary; evidence edges preserved | #86 | `NOT_IMPLEMENTED` |
| `REQ-CRT-005` Human edits card graph, not raw media | editor | `SM-CRT-004` | immutable revisions; typed navigation; no evidence mutation | #87 | `NOT_IMPLEMENTED` |
| `REQ-CRT-006` Cards compile to stateful Procedural IR | compiler | `SM-CRT-005`, `DF-CRT-004` | no raw source, single-case law or hollow procedure | #88 | `NOT_IMPLEMENTED` |
| `REQ-CRT-007` Compiler cannot self-qualify | independent qualifier | `SM-CRT-006` | G1–G8 + adversarial/mutation controls | #89 | `NOT_IMPLEMENTED` |
| `REQ-CRT-008` Model destination is explicit and minimized | provider router | `SM-CRT-007`, `DF-CRT-005` | consumer subscription≠API; private defaults LOCAL_ONLY | #90 | `NOT_IMPLEMENTED` |
| `REQ-CRT-009` Core source→Skill→workspace loop converges | creator runtime | `SM-CRT-008`, `DF-CRT-006` | selected-head digests and fresh integrated tests | #91 | `NOT_IMPLEMENTED` |
| `REQ-CRT-010` Community contributions are versioned untrusted patches | community store | `SM-CRT-009`, `DF-CRT-007` | popular≠supported; append/supersede/tombstone | #92 | `NOT_IMPLEMENTED` |
| `REQ-CRT-011` Public UGC has executable safety controls | moderation | `SM-CRT-010` | report/filter/block/contact/takedown mutation tests | #93 | `NOT_IMPLEMENTED` |
| `REQ-CRT-012` Source/rights changes propagate | revocation | `SM-CRT-011`, `DF-CRT-008` | cached-source continuation and stale locator controls | #94 | `NOT_IMPLEMENTED` |
| `REQ-CRT-013` Reference Edition adds value without copying media | reference playback | `SM-CRT-012`, `DF-CRT-009` | foreground sibling player; user seek; fallback | #82/PR #83, #95 | architecture Draft; runtime `NOT_IMPLEMENTED` |
| `REQ-CRT-014` Rendered remix/native PiP is exact-rights gated | licensed render | `SM-CRT-013` | standard YouTube source and missing-rights controls | #96 | `NOT_IMPLEMENTED`, `EXTERNAL_AUTHORITY_REQUIRED` |
| `REQ-CRT-015` Every evidence lane remains literal | evidence harness | `SM-CRT-014`, `DF-CRT-010` | emulator≠device, integrity≠rights, schema≠store | #97 | `NOT_IMPLEMENTED` |
| `REQ-CRT-016` Shared docs and Stack are zero-context current | docs convergence | `SM-CRT-015`, `DF-CRT-011` | #75/#98 one-writer, issue≠implementation controls | #98, #99, #111–#117 | in progress/planned |
| `REQ-CRT-017` Local handoff is concrete, not prose | handoff queue | `SM-CRT-016` | no placeholder argv/subjects/receipts | #100 | `ABSENT`, planned |
| `REQ-CRT-018` Workers receive complete system prompts | prompt pack | `DF-CRT-012` | zero hidden context / provider-neutral task packet | #101 | planned |
| `REQ-CRT-019` Cross-media adapters preserve source-specific limits | adapter epic/registry | `SM-CRT-017`, `DF-CRT-013` | no fake parity or hidden fallback | #102–#110 | `NOT_IMPLEMENTED` |
| `REQ-CRT-020` Market success is separate from technical closure | outcome/DoD | `SM-CRT-018` | no guaranteed growth/revenue; exact USER_OUTCOME | #91, #97, #114, #115 | planned |

## State-machine index

| ID | Owner | States / responsibility | Current evidence |
|---|---|---|---|
| `SM-CRT-001` | #84 + source adapters | request → access/rights/destination → ready/degraded/blocked → revision-bound events | contract docs only |
| `SM-CRT-002` | #85 | embed/identity/Media Integrity probe → player ready/error → user seek result | `NOT_IMPLEMENTED` |
| `SM-CRT-003` | #86 | events → semantic boundaries → atomic cards → links/dedup/clusters | `NOT_IMPLEMENTED` |
| `SM-CRT-004` | #87 | render → select/merge/split/reorder → immutable editor revision → typed navigation | `NOT_IMPLEMENTED` |
| `SM-CRT-005` | #88 | cards → atoms → hidden state machine → counterfactuals → IR/candidate | prompt materialized; runtime `NOT_IMPLEMENTED` |
| `SM-CRT-006` | #89 | candidate → positive/negative/boundary/counterexample/perturbation → qualified/not | `NOT_IMPLEMENTED` |
| `SM-CRT-007` | #90 | request → destination admission → budget route → provider receipt/refusal | `NOT_IMPLEMENTED` |
| `SM-CRT-008` | #91 | source → index → edit → compile → qualify → workspace → outcome | `NOT_IMPLEMENTED` |
| `SM-CRT-009` | #92 | patch draft → schema/rights/moderation/qualification → variant/version/tombstone | `NOT_IMPLEMENTED` |
| `SM-CRT-010` | #93 | classify → allow/review/deny → report/block/appeal/takedown | `NOT_IMPLEMENTED` |
| `SM-CRT-011` | #94 | freshness → impact → reindex/degrade/takedown → cleanup receipt | `NOT_IMPLEMENTED` |
| `SM-CRT-012` | #95 | source/player → reference edition → card seek/app fallback → reconcile | `NOT_IMPLEMENTED` |
| `SM-CRT-013` | #96 | rights packet → admitted assets → render → preview/publication gate → native PiP | rights blocked / `NOT_IMPLEMENTED` |
| `SM-CRT-014` | #97 | exact subject → exercise → literal evidence lane → disagreement control | `NOT_IMPLEMENTED` |
| `SM-CRT-015` | #98/#99 | observe GitHub → reconcile docs/index → exact-head docs validation | in progress/planned |
| `SM-CRT-016` | #100 | queue absent → concrete commands → one-active-item execution → receipt/review | `ABSENT` |
| `SM-CRT-017` | #102–#110 | adapter resolution → platform probe/admission → bounded events/degrade | `NOT_IMPLEMENTED` |
| `SM-CRT-018` | #91/#115 | experiment → outcome → preserve/strengthen/narrow/revise/refute | `NOT_IMPLEMENTED` |

## Data-flow index

| ID | Source → destination | Payload / law | Owning issues |
|---|---|---|---|
| `DF-CRT-001` | source request → operation admission | source/access/rights/destination; no broad allow | #80, #84 |
| `DF-CRT-002` | adapter → indexer | sanitized events + exact locator/revision only | #85, #103–#110 |
| `DF-CRT-003` | events → card graph | semantic boundaries, stable IDs, typed evidence/contradiction links | #86 |
| `DF-CRT-004` | selected graph → compiler/qualifier | candidate IR then independent verdict; no raw source/self-qualification | #88, #89 |
| `DF-CRT-005` | minimized evidence → provider/host | destination authority, payload digest, retention policy; no consumer token reuse | #90 |
| `DF-CRT-006` | qualified Skill → creator workspace/experiment | portable host package + falsifiable metric | #91 |
| `DF-CRT-007` | contributors → Community Edition | immutable SkillPatch, rights, evidence, conflicts and versions | #92 |
| `DF-CRT-008` | source/rights change → impact graph | reindex/degrade/takedown/provider cleanup | #94 |
| `DF-CRT-009` | card/variant → official source playback | user-initiated seek or official-app fallback; no media copy | #95 |
| `DF-CRT-010` | runtime/device/provider/moderation → evidence registry | exact subject/carrier/result/ceiling; no cross-lane promotion | #97 |
| `DF-CRT-011` | GitHub state → README/AGENTS/trace/Stack | observed issue/PR/head/lease states only | #98, #99, #112 |
| `DF-CRT-012` | task graph → system prompts/local handoff | zero-context packet or exact command queue | #100, #101 |
| `DF-CRT-013` | cross-media sources → adapter registry | preserve source-specific locator, rights and platform limits | #102–#110 |

## Golden invariants

| ID | Statement | Owner / falsifier |
|---|---|---|
| `INV-CRT-001` Visible is not owned/extractable/retainable/model-shareable/publishable | #80/#84; broad-allow fixture |
| `INV-CRT-002` Integrity/identity/session/entitlement/content rights are distinct | #85/#90; identity-laundering fixture |
| `INV-CRT-003` Cards retain exact source/revision/locator lineage | #84/#86; missing/stale locator fixture |
| `INV-CRT-004` One case/score/vote/model cannot create cross-case truth | #86/#89/#92; case-to-law and popularity fixtures |
| `INV-CRT-005` Compiler and qualifier remain independent | #88/#89; shared-state/self-verdict fixture |
| `INV-CRT-006` Source expression and creator identity do not leak into public Skill | #88/#89/#93; transcript/style/false-endorsement corpus |
| `INV-CRT-007` Source/player UI remains official and unobscured in reference mode | #85/#87/#95; overlay/background/PiP mutations |
| `INV-CRT-008` Public UGC cannot launch with prose-only controls | #93; missing filter/report/block/contact/takedown fixture |
| `INV-CRT-009` Revocation propagates; cached source does not survive deletion | #94/#95; deleted-source cached-playback fixture |
| `INV-CRT-010` Licensed render is exact subject/destination/term/component bound | #96; standard-YouTube/missing-component fixture |
| `INV-CRT-011` Evidence never promotes itself across subject, carrier or lane | #97; emulator→device, schema→store, integrity→rights fixtures |
| `INV-CRT-012` One shared-index writer owns root docs | #98/#99; #75/#98 concurrent lease fixture |
| `INV-CRT-013` MVP is reference-first, not all future scope | #114; all-adapters/licensed-render-as-MVP mutation |
| `INV-CRT-014` Technical closure is not market success | #115; green-build→revenue claim fixture |

## Molecular Stack trace

| Stack | Issue | Branch (planned unless noted) | Class | Current state |
|---|---:|---|---|---|
| `STACK-CRT-D0` | #80 / PR #81 | `agent/media-rights-risk-register` | architecture | Draft published |
| `STACK-CRT-D1` | #82 / PR #83 | `agent/community-skill-edition-design` | child architecture | Draft published |
| `STACK-CRT-C0` | #84 | `feat/creator-content-contracts` | contract child | `NOT_IMPLEMENTED` |
| `STACK-CRT-A1` | #85 | `feat/youtube-source-adapter` | source child | `NOT_IMPLEMENTED` |
| `STACK-CRT-K1` | #86 | `feat/v72-auto-indexer` | sibling | `NOT_IMPLEMENTED` |
| `STACK-CRT-U1` | #87 | `feat/creator-card-editor` | sibling | `NOT_IMPLEMENTED` |
| `STACK-CRT-C1` | #88 | `feat/procedural-skill-compiler` | sibling | `NOT_IMPLEMENTED` |
| `STACK-CRT-E1` | #89 | `test/procedural-skill-qualifier` | independent qualifier | `NOT_IMPLEMENTED` |
| `STACK-CRT-P1` | #90 | `feat/creator-model-destination-router` | sibling | `NOT_IMPLEMENTED` |
| `STACK-CRT-X1` | #91 | `feat/creator-pipeline-convergence` | multi-parent process convergence | `NOT_IMPLEMENTED` |
| `STACK-CRT-C2` | #92 | `feat/community-skill-patches` | child | `NOT_IMPLEMENTED` |
| `STACK-CRT-M1` | #93 | `feat/community-edition-moderation` | child | `NOT_IMPLEMENTED` |
| `STACK-CRT-R1` | #94 | `feat/creator-source-revocation` | child | `NOT_IMPLEMENTED` |
| `STACK-CRT-A2` | #95 | `feat/community-reference-edition` | product convergence | `NOT_IMPLEMENTED` |
| `STACK-CRT-A3` | #96 | `feat/licensed-community-render` | rights-gated child | `NOT_IMPLEMENTED` / external authority |
| `STACK-CRT-E2` | #97 | `test/creator-capability-evidence` | evidence convergence | `NOT_IMPLEMENTED` |
| `STACK-CRT-D2` | #98 | `docs/creator-capability-convergence` | shared docs owner | in progress |
| `STACK-CRT-E3` | #99 | `ci/creator-docs-convergence` | docs CI child | planned |
| `STACK-CRT-H1` | #100 | future | local handoff | `ABSENT` |
| `STACK-CRT-P2` | #101 | future | prompt docs | planned |
| `STACK-CRT-SRC` | #102 | epic | cross-media source expansion | planned |
| `STACK-CRT-A4..A9` | #103–#109 | source-specific branches | source siblings | `NOT_IMPLEMENTED` |
| `STACK-CRT-X2` | #110 | `feat/creator-source-registry` | multi-parent source convergence | `NOT_IMPLEMENTED` |
| `STACK-CRT-D3` | #111–#117 | #98-owned docs or later reviews | docs/review atoms | planned |

## External-authority register

These cannot be closed by repository code alone:

- fair-use/copyright/derivative-work and contributor/media/voice/likeness/trademark rights;
- platform/API compliance, terms acceptance and app identity approvals;
- organization/DLP/customer/employer data processing authority;
- production model/provider accounts, data retention and deletion authority;
- physical device trust, Premium account state, DRM and codec behavior;
- App Store/Google Play review, signing and publication;
- merge, release, production deployment and destructive rollback;
- market demand, paid conversion, creator growth/revenue and retention outcomes.

## Documentation verification ceiling

This convergence can prove only that current GitHub requirements, issues, PRs, heads, directories, State Machines, data flows and Stack ownership are consistently indexed. It cannot prove implementation, local build/tests, Git Town execution, device/platform/legal/store acceptance or production.
