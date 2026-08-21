# Community Skill Edition Architecture

> Issue: #82  
> Parent risk contract: #80 / draft PR #81  
> Parent capability: #79 v7.2 creator procedural Skill compiler  
> Status: architecture and data-contract design only  
> Review date: 2026-08-19

## 1. Product verdict

The requested experience is valuable, but the phrase **community edited video**
can describe three materially different products with different platform,
copyright, playback, store-review, and physical-runtime boundaries.

They must never be collapsed:

```text
REFERENCE_EDITION
  community-created cards, Skill patches, procedure variants and timestamps
  official source player remains authoritative
  no source-media copy or rendered derivative

OFFICIAL_CLIP_REFERENCE
  community edition references eligible YouTube Clip URLs
  each Clip remains a public 5-60 second loop from the original watch page
  YouTube and the source creator retain eligibility, access and revocation control

LICENSED_RENDER_EDITION
  actual frames, screenshots, source segments, generated interstitials and a
  newly rendered community video
  requires user ownership, CC BY/public-domain scope, or an explicit partner
  license covering the exact operation and publication destination
```

The default and first shippable product is `REFERENCE_EDITION`.

The product may look like an edited documentary, but its source truth is an
**Edit Decision List plus capability graph**, not copied audiovisual bytes.

## 2. Product thesis

A normal YouTube clip answers:

```text
Which short moment should I share?
```

A Community Skill Edition answers:

```text
What procedures did several creators infer from this source?
Where do their interpretations agree or conflict?
Which source moments support each rule?
Which Skill variant should an Agent execute for my context?
What real outcome later strengthened or refuted it?
```

The independent product value is therefore:

```text
source playback
+ v7.2 temporal cards
+ multi-author procedural interpretation
+ evidence/conflict lineage
+ portable Skill variants
+ outcome foldback
```

It is not ad suppression, background YouTube playback, a YouTube clone, a
transcript archive, or a replacement for the original source.

## 3. Core trust laws

```text
publicly viewable source
!= permission to capture frames
!= permission to re-encode segments
!= permission to publish a derivative

YouTube embed ready
!= creator permission
!= copyright clearance
!= Premium entitlement

community majority
!= evidence truth
!= Skill qualification

SkillPatch accepted as UGC
!= executable authority
!= canonical Skill release

system Picture in Picture available
!= YouTube background playback admitted
```

Every implementation must consume the operation-level admission contract in
`docs/security/CONTENT_PLATFORM_MEDIA_RISK_REGISTER.md`.

## 4. Source and edition modes

### 4.1 `REFERENCE_EDITION`

Use for an ordinary third-party YouTube video under the standard YouTube
license, or whenever exact reuse rights are absent or unknown.

Allowed artifact classes:

- video/channel/source identity;
- source URL and timestamps;
- eligible official YouTube Clip URLs supplied by users;
- independently worded v7.2 cards;
- card-to-source locators;
- procedure graphs and Skill patches;
- contributor-authored original commentary, diagrams and animations;
- source availability, embed and revision state;
- moderation and qualification receipts.

Denied artifact classes by default:

- downloaded or cached source video/audio;
- screenshots or extracted source frames;
- complete captions/transcript;
- cropped or altered thumbnails;
- re-rendered source segments;
- background or system-PiP playback of a YouTube embedded player;
- a public file that substitutes for watching the source.

### 4.2 `OFFICIAL_CLIP_REFERENCE`

This is a reference lane, not a media-reuse license.

A supplied YouTube Clip URL may be attached to a card when:

- the user created or obtained the URL through YouTube's normal Clip UI;
- the source is eligible and the channel has not disabled Clips;
- the viewer can access the original source;
- the Clip remains available;
- the product does not claim to create/manage Clips through a nonexistent public
  Clips API;
- the Clip is opened through the official YouTube surface.

The product stores:

```text
clip URL
original source ID
user-entered title/description if separately licensed
availability/freshness state
related card and SkillPatch IDs
```

It does not store the Clip's audiovisual bytes or infer that the Clip grants
permission to create another rendered derivative.

### 4.3 `LICENSED_RENDER_EDITION`

Use only when a machine-readable rights receipt binds:

```text
source identity and revision
rights owner
license or contract identity
allowed media operations
commercial/noncommercial scope
territory
term and expiry
attribution requirements
music/third-party component rights
voice/face/likeness/publicity rights
publication destinations
revocation/takedown behavior
```

Eligible examples may include:

- the uploader's own media;
- partner media licensed specifically for community remix;
- public-domain media;
- CC BY media where the entire relevant source and embedded components are
  actually reusable under the stated terms.

The presence of a Creative Commons label is a proposal, not complete proof that
all music, images, performers, trademarks, or third-party components can be
reused in every destination.

Only this mode may admit:

- source screenshots and frame grabs;
- selected source media segments;
- newly rendered community videos;
- source-derived thumbnails where the license permits;
- Android/iOS native PiP for app-controlled licensed media;
- export/upload of a new derivative subject.

## 5. Playback lanes

### 5.1 `YOUTUBE_FOREGROUND_DOCK`

The v1 player lane.

```text
┌──────────────── Community Skill Edition ────────────────┐
│                                                        │
│ ┌──── official YouTube player ────┐  ┌─ card editor ─┐ │
│ │ visible, foreground, controls   │  │ cards         │ │
│ │ and branding unobscured         │  │ contributors  │ │
│ │ minimum player size preserved   │  │ Skill variants│ │
│ └─────────────────────────────────┘  └───────────────┘ │
│                                                        │
└────────────────────────────────────────────────────────┘
```

Requirements:

- player is a sibling surface, not covered by cards, overlays, masks or frames;
- player controls, metadata, links, ads and platform rendering remain intact;
- player is visible and remains inside the foreground app;
- player viewport remains at least the current YouTube minimum;
- API Client identity, Referer, cookies and Media Integrity policy are not
  suppressed;
- one explicit user card click may request `seekTo(t_start)`;
- the product does not automatically sequence a hidden montage;
- ads, consent, age/login prompts and playback errors are observable states;
- the edition never claims the user saw a referenced segment merely because a
  seek proposal was issued.

Recommended card interaction:

```text
CARD_SELECTED
→ SOURCE_IDENTITY_REVALIDATED
→ PLAYER_READY_CHECKED
→ USER_INITIATED_SEEK
→ SEEK_OBSERVED | SEEK_FAILED
→ ACTIVE_CARD_HIGHLIGHTED
```

### 5.2 `IN_APP_SOURCE_DOCK`

A visual layout pattern, not system PiP.

The app may keep the official player in a small resizable foreground panel while
showing cards elsewhere in the same app, provided that:

- the player remains fully visible;
- controls remain usable;
- minimum size is preserved;
- no card or contributor UI overlays any part of the player;
- the app does not continue playback when minimized or closed;
- the implementation is tested against current YouTube policy and physical
  devices.

Do not label this lane `Picture in Picture` in code, product copy or store
metadata. Use `SOURCE_DOCK` or `FOREGROUND_MINI_PLAYER`.

### 5.3 `OPEN_IN_YOUTUBE_APP`

Use when:

- embedding is disabled (`101` / `150`);
- account/Premium/native playback is preferred;
- age/region/login flow requires the official app;
- a community contribution points to an official Clip URL.

The app may deep-link to a source/timestamp or Clip URL. The YouTube application
owns authentication, Premium benefits, ads, PiP and media playback.

The Capability Browser must not claim:

- continuous card/player synchronization after leaving the app;
- remote control of the YouTube app's PiP window;
- knowledge of Premium entitlement;
- reliable playback position read-back from the YouTube app;
- automatic return to the exact community editing state unless an explicit app
  callback exists and was exercised.

### 5.4 `LICENSED_NATIVE_PIP`

Only for app-controlled, admitted media bytes.

```text
LICENSED_MEDIA_READY
→ NATIVE_PLAYER_READY
→ USER_INITIATES_PIP
→ SYSTEM_PIP_ACTIVE
→ COMMUNITY_EDITOR_FOREGROUND_OR_OTHER_APP
→ PIP_STOPPED
→ PLAYER_STATE_RECONCILED
```

Android may use the platform/Jetpack/Media3 PiP lane. Apple platforms may use
AVPlayerViewController or AVPictureInPictureController. Platform API presence is
not physical-device proof.

The licensed media player and YouTube embedded player are different
capabilities, state machines and evidence lanes.

## 6. Community edition composition

### 6.1 Community edition is an Edit Decision List

A reference edition contains no source media timeline. It contains ordered
references:

```text
Edition Segment 1
  original contributor-created interstitial/card
  source reference: video @ 04:18-05:02
  play action: explicit user seek

Edition Segment 2
  original contributor diagram
  SkillPatch decision rule
  source references: video @ 12:11 and official Clip URL

Edition Segment 3
  conflict view
  contributor A invariant
  contributor B counterexample
```

The product may render contributor-created original animations and card
transitions. It may not render unlicensed source frames inside those assets.

### 6.2 `SkillPatch`

A `SkillPatch` is a contribution proposal, not a complete Skill file and not
runtime authority.

Required fields:

```yaml
patch_id:
contributor_id:
base_skill_id:
base_skill_version:
source_binding_id:
selected_card_ids: []
procedure_delta:
  add_states: []
  add_transitions: []
  add_invariants: []
  add_failure_modes: []
  add_negative_triggers: []
  remove_or_narrow_rules: []
evidence_refs: []
confounders: []
counterexamples: []
transfer_conditions: []
contribution_rights:
moderation_state:
qualification_state:
```

Forbidden patch payloads:

- source transcript or copied script;
- raw source screenshots/frames in reference mode;
- executable JavaScript, shell, selectors, URLs or native calls treated as
  authority;
- creator voice/persona imitation instructions;
- unsupported statements that the source creator endorses the edition;
- defamatory, harassing or private personal information;
- model prompts that bypass the host's canonical Skill qualification.

### 6.3 Procedure variants

Multiple contributors may create incompatible procedure variants.

The system preserves:

```text
Variant A
  applicability: small expertise-led creator
  evidence: cards A/B/C

Variant B
  applicability: established creator with owned audience
  evidence: cards B/D/E

Conflict
  A says pre-sell before production
  B says contract-first production is valid for commissioned work
```

Do not resolve the conflict by likes, contributor count, model confidence, or a
single judge result.

A community aggregate may display:

- shared mechanism intersection;
- differing transfer conditions;
- contradicting evidence;
- qualification verdict per variant;
- user outcomes per variant.

### 6.4 Community voting

Votes, stars and saves are social preference signals only.

```text
POPULAR
!= SUPPORTED
!= QUALIFIED
!= CANONICAL
```

If voting is implemented, it must remain outside evidence promotion and Skill
release gates.

## 7. Stable data identities

Required aggregate types:

```text
CommunityEdition
SourceBinding
SegmentReference
OfficialClipReference
CardSelection
SkillPatch
ProcedureVariant
ContributorIdentity
ContributionRights
ConflictRecord
ModerationDecision
RightsReceipt
SourceRevocation
EditionVersion
PublicationReceipt
```

Identity rules:

- immutable edition/version IDs;
- source identity includes platform, video ID, observed revision/fingerprint and
  access state;
- every card and patch preserves exact source/card lineage;
- edition versions append or supersede; they do not mutate publication history;
- revoked or removed contributions remain tombstoned in audit state;
- external URLs are untrusted data and require scheme/origin validation;
- contributor display names are not stable identity authority.

## 8. State machines

### 8.1 Edition lifecycle

```text
SOURCE_REGISTERED
→ RIGHTS_CLASSIFIED
→ EDITION_MODE_SELECTED
→ SOURCE_INDEXED
→ COMMUNITY_CONTRIBUTIONS_OPEN
→ PATCH_SUBMITTED
→ PATCH_VALIDATED
→ PATCH_MODERATED
→ PROCEDURE_VARIANTS_ASSEMBLED
→ EDITION_READY
   ├─ REFERENCE_NAVIGATION_READY
   ├─ OFFICIAL_CLIP_REFERENCES_READY
   └─ LICENSED_RENDER_READY
→ PRIVATE_PREVIEW
→ PUBLICATION_AUTHORIZED | PUBLICATION_BLOCKED
→ PUBLISHED
→ SOURCE_CHANGED | SOURCE_REVOKED | CONTRIBUTION_REVOKED
→ REINDEX | DEGRADE_TO_LOCATORS | PARTIAL_TAKEDOWN | FULL_TAKEDOWN
```

### 8.2 SkillPatch lifecycle

```text
DRAFT
→ SUBMITTED
→ SCHEMA_VALID
→ RIGHTS_CHECKED
→ SOURCE_LEAKAGE_CHECKED
→ MODERATION_PENDING
→ MODERATION_ALLOWED | MODERATION_DENIED
→ QUALIFICATION_PENDING
→ QUALIFIED_VARIANT | NOT_QUALIFIED
→ INCLUDED_IN_EDITION
→ SUPERSEDED | WITHDRAWN | REMOVED
```

No patch can skip rights, leakage, moderation or qualification because another
patch from the same contributor previously passed.

### 8.3 Source playback lifecycle

```text
SOURCE_BOUND
→ EMBED_PROBED
   ├─ READY
   ├─ EMBED_DENIED
   ├─ CLIENT_IDENTITY_MISSING
   ├─ LOGIN_AGE_REGION_REQUIRED
   ├─ SOURCE_UNAVAILABLE
   └─ PLAYBACK_ERROR

READY
→ CARD_SELECTED
→ SEEK_PROPOSED
→ SEEK_OBSERVED | SEEK_FAILED | AUTOPLAY_BLOCKED
→ USER_PLAYBACK
→ PAUSED | ENDED | ERROR
```

### 8.4 Revocation lifecycle

```text
ACTIVE_SOURCE
→ FRESHNESS_CHECK
→ CHANGED | PRIVATE | DELETED | EMBED_DISABLED | REGION_RESTRICTED | RIGHTS_REVOKED
→ impact analysis
→ affected cards/patches/editions identified
→ REINDEX | LOCATOR_ONLY | SOURCE_UNAVAILABLE | TAKEDOWN
→ downstream provider/cache/publication cleanup receipts
```

A reference edition must not continue playing from a cached copy after the
source becomes unavailable.

## 9. Community moderation and store admission

Public community editing makes the product a UGC service.

Before public release, require:

- terms/community standards accepted before contribution;
- automated and human-review filtering for objectionable or copied material;
- report contribution/edition/user mechanisms;
- timely moderation response states;
- block/mute abusive users;
- published support/contact information;
- copyright and identity/likeness complaint path;
- source-creator opt-out and takedown route;
- contributor appeal and immutable moderation receipt;
- rate limits, spam controls and abuse detection;
- NSFW or sensitive-content classification and default hiding where required;
- child-safety and age-gating review;
- no anonymous harassment, appearance rating or false-endorsement features.

A public edition cannot be admitted while any of these are only prose:

```text
filter
report
block
contact
moderation response
copyright takedown
source revocation
```

## 10. Copyright, creator identity and contribution rights

### 10.1 Source rights

The source and community contribution rights are independent.

```text
source media license
+ contributor commentary license
+ generated asset rights
+ SkillPatch contribution grant
+ publication destination rights
```

All must close for a licensed rendered edition.

### 10.2 Creator endorsement

Community editions must display:

```text
Community-created interpretation
Not endorsed by the source creator unless an exact endorsement receipt exists
```

Do not use the creator's name, avatar, face, voice, logo or channel branding in a
way that implies partnership, certification or approval.

### 10.3 Screenshot and frame policy

Reference mode:

```text
FRAME_CAPTURE = DENIED
```

Licensed mode:

```text
FRAME_CAPTURE
→ exact source rights
→ visual/performer/music/third-party component review
→ destination and attribution review
→ admitted capture
```

An app-store screenshot of the product UI must also avoid displaying third-party
source media unless that marketing use is separately permitted.

### 10.4 Generated visual segments

Skill-generated interstitials may contain independently created:

- diagrams;
- state machines;
- original text cards;
- contributor-recorded commentary;
- licensed icons/assets;
- abstract animations;
- charts derived from permitted data.

They must not recreate the source creator's distinctive visual identity,
character, voice, face, slides or protected assets as a substitute.

## 11. Security boundaries

### 11.1 Skill content is untrusted data

Imported `SkillPatch` content may contain prompt injection or executable-looking
text.

It must never directly become:

- JavaScript;
- DOM selector;
- URL navigation;
- shell/CLI command;
- native action;
- MCP permission;
- publication authority.

The pipeline is:

```text
SkillPatch data
→ schema validation
→ sanitization
→ policy/rights/moderation
→ Procedural IR
→ independent qualification
→ portable Skill candidate
→ host capability policy
```

### 11.2 Contributor identity

Account authentication does not prove authorship, source rights or expertise.

Preserve separate states:

```text
ACCOUNT_AUTHENTICATED
CONTRIBUTOR_PROFILE_VERIFIED
SOURCE_RIGHTS_VERIFIED
CONTRIBUTION_RIGHTS_GRANTED
CREATOR_ENDORSEMENT_VERIFIED
```

### 11.3 Private editions

Private, employer, customer, course or paywalled sources default to:

```text
LOCAL_ONLY
PRIVATE_EDITION
PUBLICATION_BLOCKED
```

Community collaboration requires organization and destination authority, not
just one viewer's access.

## 12. Cross-platform implementation map

### Common KMP core

Owns:

- manifests and stable identity;
- cards, patches, variants and conflicts;
- state reducers;
- rights/moderation/qualification status;
- playback action proposals;
- source-revocation propagation;
- serialization and deterministic validation.

Must not own:

- YouTube WebView implementation;
- AVPlayer or Media3 lifecycle;
- system PiP calls;
- OAuth/session credentials;
- store/release authority.

### Android adapter

Potential owners:

- OS WebView official YouTube embed;
- Referer and Media Integrity configuration;
- player event bridge;
- foreground source dock;
- licensed Media3/player + platform PiP;
- renderer/process recovery;
- Google Play UGC/store canaries.

### iOS adapter

Potential owners:

- WKWebView YouTube embed;
- player event bridge;
- foreground source dock;
- `allowsPictureInPictureMediaPlayback` capability probe for general HTML media,
  without treating it as YouTube policy admission;
- licensed AVPlayer/AVKit PiP;
- App Store UGC/content-rights canaries.

### Desktop/Web

Potential owners:

- editor and moderation console;
- official foreground embedded player;
- edition authoring and schema validation;
- no mobile-PiP parity claim.

## 13. User experience

### 13.1 Main views

```text
SOURCE
  official source playback and identity

TIMELINE
  chronological v7.2 cards and source locators

COMMUNITY
  contributor patches, original commentary and moderation state

PROCEDURES
  compatible/conflicting procedure variants

SKILLS
  qualification state and portable Skill rendering

OUTCOMES
  user experiments that strengthen/narrow/refute variants
```

### 13.2 Card interaction

A card displays:

- independently worded title and claim/procedure;
- contributor;
- evidence class;
- exact source locator;
- selected edition/variant;
- conflict/qualification state;
- rights/availability state;
- explicit `Play source` action.

Clicking the card does not immediately claim execution. It proposes a bounded
source action and records the observed result.

### 13.3 Community edition playback

Reference v1 uses a turn-based presentation:

```text
show original contributor-created card/interstitial
→ user chooses Play source
→ foreground official player seeks and plays normally
→ user returns to card/procedure view
→ next card remains explicit
```

Automatic continuous montage playback remains `EXTERNAL_AUTHORITY_REQUIRED`
until a YouTube compliance review or a licensed media lane establishes the exact
behavior.

## 14. Monetization boundary

Do not charge users merely to watch freely available YouTube content.

Monetizable independent value may include:

- private capability workspaces;
- automatic v7.2 indexing;
- multi-creator SkillPatch editor;
- conflict and transferability analysis;
- Skill qualification and host export;
- moderation and rights workflow;
- licensed partner edition production;
- creator analytics based on authorized/user-owned outcomes;
- enterprise/private training edition management.

Product copy must not promise:

- ad-free YouTube;
- Premium forwarding;
- automatic download or remix of any video;
- official YouTube/creator endorsement;
- copyright-safe output without exact admission.

## 15. MVP

The first vertical slice is reference-only.

```text
one public embeddable YouTube source
→ automatic v7.2 card index
→ two authenticated contributor SkillPatch variants
→ schema, rights and moderation validation
→ procedure conflict view
→ visible foreground official player
→ explicit card-to-timestamp seek
→ source deletion/embed-disable degradation
```

Excluded from MVP:

- screenshots or frames;
- copied captions/transcript;
- actual source-media rendering;
- automatic montage playback;
- system PiP;
- YouTube Clips creation API;
- public UGC launch before moderation controls are executable;
- creator payouts or revenue sharing;
- automatic publication.

## 16. Phased roadmap

### Phase 1 — Reference edition

- schemas and reducers;
- source/card/patch identities;
- official foreground dock;
- contributor variants and conflict view;
- private preview;
- deterministic source revocation;
- no media capture.

### Phase 2 — Public community

- executable moderation/report/block/contact/takedown controls;
- contributor license receipts;
- source-creator opt-out;
- UGC store evidence;
- public edition versions;
- no copied media.

### Phase 3 — Licensed partner remix

- rights manifest and media asset admission;
- frame/segment capture only for exact licensed subjects;
- rendered community edition;
- licensed native PiP;
- publication/attribution receipts;
- physical-device and store evidence.

### Phase 4 — Outcome-qualified Skill network

- creator/user experiment receipts;
- per-context procedure ranking;
- Skill preserved/narrowed/revised/refuted states;
- no popularity-to-truth promotion.

## 17. Shadow Architect ledger

Monitor:

```text
PLATFORM_CONTRACT_DELTA
MEDIA_COPY_DELTA
DERIVATIVE_WORK_DELTA
PLAYBACK_AUTHORITY_DELTA
PIP_DELTA
UGC_MODERATION_DELTA
CONTRIBUTION_LICENSE_DELTA
SOURCE_REVOCATION_DELTA
FALSE_ENDORSEMENT_DELTA
EVIDENCE_DELTA
```

L3 block when:

- a standard-license public source is promoted to frame/segment reuse;
- an embedded YouTube player enters system PiP or background playback;
- a Clip URL is treated as source-copy permission;
- cards/patches include copied source expression or unlicensed frames;
- source creator endorsement is implied;
- conflicting patches are merged by popularity and labeled verified;
- UGC publication lacks filtering/report/block/contact/takedown mechanisms;
- source deletion/revocation fails to propagate;
- a generated segment clones creator voice/face/style;
- app/store copy claims official, Premium, copyright-safe or universal remix
  capability without exact evidence;
- simulator/static/build evidence is promoted to physical PiP, DRM, store or
  legal acceptance.

## 18. Evidence boundary

This architecture can establish:

- separate product and rights modes;
- explicit playback and PiP lanes;
- reviewable state/data contracts;
- a reference-only product that adds independent value without rehosting media;
- deterministic fail-closed transitions and future implementation ownership.

It cannot establish:

- copyright/fair-use or derivative-work clearance;
- creator, music, performer, trademark or publicity permission;
- YouTube compliance approval;
- App Store or Google Play acceptance;
- physical-device PiP, Premium, DRM or embedded-session behavior;
- permission to use a specific source;
- production moderation quality;
- publication or merge authority.

## 19. Completion rule

Design completion means:

```text
architecture
+ schema
+ example manifest
+ planted invalid examples
+ reviewable Shadow blocks
```

It does not mean the product, public community, licensed media pipeline or PiP
runtime has been implemented or admitted.
