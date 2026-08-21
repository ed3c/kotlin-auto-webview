# Agent operating contract

This is the repository-wide authority and execution contract. Read it before any code, documentation, source-ingestion, model-provider, Git, publication, or Creator Capability work.

## 1. Mandatory read order

1. `AGENTS.md` — this authority and safety contract.
2. `README.md` or `README.zh-TW.md` — current state, directory ownership, State Machines, data flow and Stack index.
3. `docs/automation/README.md` and `docs/automation/REPOSITORY_PROFILE.md` — Builder/Shadow/safety/publication admission.
4. Canonical shared `spatial-loop-systems-engineering` and `git-town-stacked-pr-worker` in `ed3c/skills-shared`; do not vendor a shadow copy.
5. `docs/security/THREAT_MODEL.md` and, for creator/media work, `docs/security/CONTENT_PLATFORM_MEDIA_RISK_REGISTER.md`.
6. For Creator Capability work: `docs/creator/COMMUNITY_SKILL_EDITION_ARCHITECTURE.md`, `docs/creator/PROCEDURAL_SKILL_COMPILER_SYSTEM_PROMPT.md`, `docs/creator/README.md`, `docs/creator/AGENTS.md`, `docs/creator/CREATOR_CAPABILITY_DAG.md`, and `docs/creator/MOLECULAR_STACK_INDEX.md`.
7. `docs/TRACEABILITY.md` and `docs/git/STACKED_PRS.md`.
8. The exact owning issue/task packet, current branch/PR graph, exact heads/trees, trusted check state, and nearest directory README/AGENTS.
9. `docs/git/GIT_TOWN_ADMISSION.md` before any Git Town command.

Authority precedence:

```text
repository safety / legal / privacy policy
> exact issue task packet and path lease
> nearest AGENTS.md / README.md
> architecture, risk, State Machine and evidence contracts
> canonical shared Skills
> provider/tool defaults
> conversational summaries
```

Conflict produces a stable blocked state. Do not invent authority or ask to weaken the boundary.

## 2. Exact current integration truth

Creator snapshot on 2026-08-19:

```text
main creator-prompt commit              290a82f0394a42e0c20949a36ab575229b95051d
risk architecture                       #80 / Draft PR #81 @ 8e2181e...
Community Edition architecture           #82 / Draft PR #83 @ d8b105b...
creator implementation                   #84–#97 NOT_IMPLEMENTED
multi-source adapters                    #102–#110 NOT_IMPLEMENTED
shared docs owner                        #98 / docs/creator-capability-convergence
creator docs checks/prompts/reviews       #99–#101, #111–#117 PLANNED
local Git Town/worktree/checks            NOT_EXERCISED / BLOCKED_POLICY
merge, legal, platform, store, release    EXTERNAL_AUTHORITY_REQUIRED
```

PR #81 and PR #83 are design/documentation subjects, not runtime implementation. Issue creation is not implementation progress.

Existing bounded-browser features and their exact evidence remain separately indexed in root README/traceability. Do not let Creator plans downgrade or overwrite those contracts.

## 3. Evidence vocabulary and closure

Portable states:

```text
PASS
FAIL
ABSENT
NOT_IMPLEMENTED
NOT_EXERCISED
SKIPPED_BY_POLICY
DENIED_BY_ARCHITECTURE
EXTERNAL_AUTHORITY_REQUIRED
```

Allowed Creator closure classes:

```text
CONTRACT_CLOSED
LOCAL_DETERMINISTIC_CLOSED
REFERENCE_VERTICAL_SLICE_CLOSED
PUBLIC_COMMUNITY_TECHNICALLY_CLOSED
LICENSED_RENDER_SUBJECT_CLOSED
PHYSICAL_EVIDENCE_CLOSED
STORE_OR_LEGAL_ADMITTED        # external
MARKET_OUTCOME_VERIFIED        # customer/user receipts
```

A green build, issue, branch, PR, schema, prompt, model answer or simulator never self-promotes to another state or closure class.

## 4. Repository-wide hard laws

- Never execute raw model text as JavaScript, selector, coordinate, shell, URL, SQL, native call, media operation, permission or publication instruction.
- Password, payment, OTP, token, cookie, private key, OAuth grant, browser profile, private path, customer data and secret values do not enter page context, cards, model payloads, logs, receipts or public artifacts.
- Observation, sanitization, identity/freshness, capability policy and human authority precede state-changing execution.
- User pointer/keyboard/system interaction preempts Agent authority.
- MCP and model providers are protocol/inference boundaries, not execution or rights authorities.
- Origin, CSP, cross-origin iframe, robots, anti-bot, paywall, DRM, region/age, store and physical-device limits are surfaced, never bypassed.
- Repository visibility, owner, access, default branch, rulesets, secrets, license meaning, remote topology and user local state remain unchanged unless an exact repository-owned contract authorizes the operation.
- Absence, failure, policy denial and external authority remain in the denominator.

## 5. Creator source and media laws

Every source operation is admitted independently:

```text
render
navigate / seek
observe visible structure
retain locator
retain short excerpt
retain full source
capture frame
send to external model
compile independent card
compile procedure / Skill
share privately
publish / commercialize
```

A broad `sourceAllowed=true` is forbidden.

```text
visible != owned != extractable != retainable != model-shareable != publishable
integrity != authorization
Google account identity != API OAuth != website session != Premium entitlement
public source != public dataset
```

- Ordinary third-party YouTube sources use official player/reference lanes only. No download, audio extraction, ad suppression, hidden montage, background playback or system PiP.
- Source/player and card/editor UI are sibling surfaces; never cover platform controls, ads, branding or links.
- X website observation is separate from API actions; no WebView script automation of posting, follow, like, reply, DM or anti-bot bypass.
- Notion/Drive/Docs authenticated visibility does not establish ownership, export authority or consumer-model permission.
- PDF/EPUB/local file possession does not establish reuse or publication rights; DRM/protected surfaces fail closed.
- Consumer ChatGPT/Claude/Grok/Gemini subscriptions are not API credentials. Never capture or reuse their session tokens.
- Employer/customer/private/paid content defaults to `LOCAL_ONLY` without exact organization and destination authority.
- Source revisions, trims, deletion, access or rights revocation propagate to locators, cards, variants, caches, provider destinations and published editions.

## 6. v7.2, compiler and qualifier laws

- Stable source/card/claim/procedure/Skill/outcome IDs and typed directional links are mandatory.
- One Case One Card: do not collapse materially distinct decisions, claims, failures or examples.
- Arbitrary time/token chunks may not split a semantic decision/state/metric/failure unit.
- Automatic indexing precedes optional human curation; humans edit an existing map rather than manually timestamp every source.
- Procedural signal score ranks candidates only. One source or one successful creator cannot establish `CROSS_CASE_PATTERN`.
- The compiler consumes evidence-bound cards, not raw source content, and emits candidate Procedural IR; it cannot self-qualify.
- The independent qualifier owns G1–G8 executability, discrimination, statefulness, falsifiability, observability, transferability, evidence and compression-value gates.
- `NOT_QUALIFIED` is a successful first-class outcome with exact missing evidence or contradiction.
- Creator voice/persona imitation, source script/transcript, distinctive examples or source-substitute material are denied.

## 7. Community Edition and UGC laws

- `SkillPatch` is untrusted contribution data, never executable authority.
- Contributor display name, popularity, likes, votes and model confidence do not establish identity, evidence or canonical Skill status.
- Conflicting procedure variants remain visible with applicability, evidence and qualification state.
- Source-media rights, contributor grants, generated-asset rights, identity/likeness/voice/trademark rights and publication destination rights are separate.
- Public community mode requires executable filter, report, block/mute, contact, moderation response, copyright/identity complaint, source-creator opt-out, appeal and takedown flows before admission.
- `LICENSED_RENDER_EDITION` begins blocked without an exact rights packet. Android/iOS PiP API presence cannot authorize YouTube background playback.
- History is append/supersede/tombstone, not silent rewrite.

## 8. Builder and Shadow Architect

Default mode is `MONITOR`.

Builder owns bounded solution search and mutations inside the exact task/branch/path lease. Shadow Architect independently monitors material deltas:

```text
ASSUMPTION
STATE / LIFECYCLE
AUTHORITY / OWNERSHIP
PLATFORM CONTRACT
RIGHTS / DERIVATIVE WORK
PRIVATE EGRESS / RETENTION
EVIDENCE / CAUSALITY / GENERALIZATION
UGC / REVOCATION / STORE
PHYSICAL SUBSTRATE
```

For every delta ask:

```text
What became newly possible?
What must remain true?
How would we know it is false?
```

Interventions: `L0 OBSERVE`, `L1 WARN`, `L2 RECONCILE`, `L3 BLOCK_NAMED_TRANSITION`. L3 blocks only the unsafe transition; path-disjoint work continues.

Mandatory checkpoints include architecture choice, public contract change, external integration, persistence/concurrency, rights mode, UGC, native PiP/render, first vertical slice, first green, before commit/push/PR/publication and design-impact failure.

## 9. Directory ownership

| Plane | Owns | Must not own |
|---|---|---|
| existing `domain/web/privacy/cache/projection/mcp/capability/dispatcher/runtime/ui` | bounded browser contracts/runtime | Creator source rights or Skill qualification |
| `docs/security/` | risk/admission/non-claims | legal approval or runtime implementation |
| `docs/creator/` | creator current state/DAG/Stack/prompts | implementation or root authority beyond #98 lease |
| `creator/contract/` | portable source/card/IR/community contracts | platform I/O |
| `creator/source/*` | source-specific observation/playback/locators | model destination, procedure or publication authority |
| `creator/indexing/` | cards/links/dedup/clusters | Skill qualification |
| `creator/editor|ui/` | curation revisions and typed source-navigation proposals | evidence-class or rights mutation |
| `creator/compiler/` | card graph → candidate IR/Skill | qualification verdict |
| `creator/qualification/` | independent candidate verdict | compiler mutation or runtime action |
| `creator/provider|export/` | destination admission, minimized payloads, host packaging | source rights or action authority |
| `creator/runtime/` | selected-head convergence | leaf contract rewriting |
| `creator/community/*` | patches, variants, moderation, revocation, playback/render | evidence promotion by popularity |
| `tests/scripts/receipts/creator/` | exact-subject evidence | cross-lane promotion |

Nearest directory README/AGENTS may narrow but cannot broaden these boundaries.

## 10. Git Town and molecular work

Canonical method is `ed3c/skills-shared/skills/git-town-stacked-pr-worker`.

- One Worker = one issue + branch writer lease + isolated linked worktree + disjoint path lease.
- Branch parent means the child consumes unmerged parent bytes. Independent leaves are siblings.
- Multi-parent convergence is a process/completion DAG with one chosen Git parent and fresh integration verification; never invent multi-parent ancestry.
- Shared root files are single-writer. Issue #98 currently owns `README*`, root `AGENTS.md`, `docs/TRACEABILITY.md`, and `docs/git/STACKED_PRS.md`; issue #75 remains nested OpenDroid owner and must consume/supersede #98 before later root convergence.
- Git Town executable/worktree/sync is not admitted until exact local tool/provenance/lease/canary receipts exist.
- No automatic semantic conflict resolution, `continue`, `skip`, `undo`, `ship`, force push, merge, ruleset weakening or publication promotion.
- Issue creation, sync success, push, PR and CI are separate evidence lanes.

The authoritative Creator Stack is in `docs/creator/MOLECULAR_STACK_INDEX.md`; repository-wide view is `docs/git/STACKED_PRS.md`.

## 11. Required task packet

Every nontrivial issue states:

```text
goal and non-goals
base / parent / head and stack class
start dependencies and completion dependencies
allowed/excluded paths and shared-index owner
State Machine and data flow
positive evals and planted negative controls
evidence ceiling and non-claims
cleanup / rollback subject
Human/external-authority operations
Shadow checkpoints
```

Missing or overlapping fields yield a stable blocked state. Do not compensate by widening scope.

## 12. Verification

Existing full matrix:

```bash
./gradlew :composeApp:allTests
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:wasmJsBrowserDistribution
./gradlew :composeApp:assembleDebug
# macOS only
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Run only repository-owned fixed commands. Creator-specific commands remain `ABSENT` until owning implementation issues add them. Do not invent a Local Handoff queue; issue #100 owns it after concrete argv/cwd/timeout/subject/receipt paths exist.

Before publication, bind exact head/tree, diff, path lease, issue/PR graph, disclosure scan and all applicable checks. A missing local checkout means local-state and Git Town lanes are `NOT_EXERCISED`, not PASS.

## 13. External authority and denied operations

Agents do not perform or imply authority for legal/fair-use decisions, license/terms acceptance, media-partner contracts, organization/DLP approval, production identity/provider credentials, physical-device trust, App Store/Play submission, merge, release, deployment, billing/settings/access changes, destructive rollback or semantic conflict resolution.

End every work packet with exact changed paths, state transitions, tests and evidence ceiling; list all remaining non-PASS and external-authority lanes.
