# Creator Capability Browser

This directory is the local source of truth for the Creator Capability Browser and Community Skill Edition initiative. It records design inputs, exact current state, directory ownership, State Machines, data flow, molecular issues and non-claims. It does not report plans as implementation.

## Current verdict

```text
v7.2 procedural compiler prompt      MATERIALIZED_DOCUMENT
platform/media/rights contract       DRAFT_PUBLISHED PR #81
Community Edition architecture       DRAFT_PUBLISHED PR #83
creator runtime/contracts/adapters   NOT_IMPLEMENTED
public UGC moderation                NOT_IMPLEMENTED
source revocation                    NOT_IMPLEMENTED
reference edition                    NOT_IMPLEMENTED
licensed render/native PiP           NOT_IMPLEMENTED / RIGHTS_BLOCKED
physical/provider/store/legal        NOT_EXERCISED / EXTERNAL_AUTHORITY_REQUIRED
shared docs convergence              IN_PROGRESS issue #98
```

## Real-problem closure audit

| Real problem | Current subject | Closure | Owning issue |
|---|---|---|---:|
| Visible source must not become implicit copy/egress/publication authority | risk register PR #81 | contract only | #80, #84 |
| Sources need exact identity/revision/locator | issues only | open | #84, #85, #103–#110 |
| Video/document indexing must be automatic and semantic | prompt/design | open | #86 |
| Users curate cards and jump to source without media copying | architecture | open | #87, #95 |
| Procedures need state, branch, failure, oracle and transfer boundaries | prompt | open | #88 |
| Skill generation needs independent anti-hollow/leakage qualification | prompt | open | #89 |
| External models need source/destination/retention admission and cost routing | risk/prompt | open | #90 |
| Core source→Skill→experiment loop needs one integrated subject | issue only | open | #91 |
| Multiple creators need versioned patches, conflicts and grants | schema/design | open | #92 |
| Public community needs executable UGC safety | prose/schema | open | #93 |
| Source deletion/rights withdrawal must propagate | design | open | #94 |
| Reference Edition must preserve official playback and fallback | design | open | #95 |
| Actual frame/segment/render/PiP reuse requires exact rights | blocked design lane | intentionally blocked | #96 |
| Device/provider/DRM/store evidence cannot be laundered | issue only | open | #97 |
| PDF/EPUB/Notion/X/Web/Drive/local sources need distinct adapters | issues only | post-MVP open | #102–#110 |
| Root/creator docs, prompts, handoff, non-claims and policy drift must remain current | current branch/issues | in progress/planned | #98–#101, #111–#117 |

## Planned layout

```text
composeApp/src/commonMain/kotlin/dev/ed3c/autowebview/creator/
├── contract/                     # #84
├── policy/                       # #84
├── source/
│   ├── youtube/                  # #85
│   ├── pdf/                      # #103
│   ├── epub/                     # #104
│   ├── notion/                   # #105
│   ├── x/                        # #106
│   ├── web/                      # #107
│   ├── google/                   # #108
│   ├── local/                    # #109
│   └── registry/                 # #110
├── indexing/                     # #86
├── editor/ and ui/               # #87
├── compiler/                     # #88
├── qualification/                # #89
├── provider/ and export/         # #90
├── runtime/                      # #91
└── community/
    ├── model/ and store/         # #92
    ├── moderation/ and abuse/    # #93
    ├── revocation/               # #94
    ├── playback/reference/       # #95
    └── render/                   # #96

tests/scripts/receipts/creator/   # #97
```

These paths are ownership plans. They are not current code claims.

## Primary flow

```text
SOURCE_REQUESTED
→ PER_OPERATION_ADMISSION
→ SOURCE-SPECIFIC_EVENTS + LOCATOR
→ v7.2 AUTO_INDEX
→ CARD GRAPH
→ HUMAN CURATION / AUTO-SELECTION
→ PROCEDURAL IR CANDIDATE
→ INDEPENDENT QUALIFICATION
→ QUALIFIED SKILL | NOT_QUALIFIED
→ CREATOR WORKSPACE / COMMUNITY EDITION
→ USER EXPERIMENT
→ OUTCOME FOLDBACK
```

## Source capability matrix

| Source | Locator | Main limits | Issue |
|---|---|---|---:|
| YouTube | video ID + timestamp + player/revision state | official player, embed/login/age/region, captions/visual ambiguity, no media copy | #85 |
| PDF | file digest + page/region/text/figure | scanned/layout/table/DRM/rights/model egress | #103 |
| EPUB | digest + chapter/section/CFI | DRM/reflow/selection-and-arrangement/source substitution | #104 |
| Notion | workspace/page/block + revision | organization ownership, DLP/export, private fields, access revocation | #105 |
| X | post/thread/article ID + freshness | observation only; dynamic/login/protected content; no site actions | #106 |
| Generic Web | origin/URL/navigation + DOM anchor/fingerprint | CSP, cross-origin, robots, anti-bot, sensitive fields | #107 |
| Drive/Docs | file/document ID + revision + structural locator | OAuth/system browser, organization/DLP, comments/people/private content | #108 |
| Local | file digest + structural/temporal locator | picker lifecycle, ownership, codec/DRM, private data | #109 |

## Product modes

- `REFERENCE_EDITION`: first MVP; official source playback/reference plus app-owned cards, diagrams, variants and outcomes.
- `OFFICIAL_CLIP_REFERENCE`: stores official Clip URL and source relation only.
- `LICENSED_RENDER_EDITION`: exact user/partner/public-domain/fully applicable CC rights packet; rendered derivative and native PiP remain stronger subject-bound states.

## Evidence and non-claims

Architecture docs do not prove runtime. A reference edition does not prove source-media rights. A licensed render receipt does not prove every source or destination. Physical-device PASS does not prove store/legal acceptance. Market outcome requires customer/user receipts.

See:

- [`CURRENT_STATE.md`](CURRENT_STATE.md)
- [`CREATOR_CAPABILITY_DAG.md`](CREATOR_CAPABILITY_DAG.md)
- [`MOLECULAR_STACK_INDEX.md`](MOLECULAR_STACK_INDEX.md)
- [`AGENTS.md`](AGENTS.md)
- root [`docs/TRACEABILITY.md`](../TRACEABILITY.md)
- root [`docs/git/STACKED_PRS.md`](../git/STACKED_PRS.md)
