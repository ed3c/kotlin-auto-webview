# Content, Platform, Media, Rights, and Physical Risk Register

> Issue: #80  
> Parent capability: #79 creator-content procedural Skill compiler  
> Review date: 2026-08-19  
> Status: architecture and admission contract only

This document defines the fail-closed boundary for observing media or documents,
deriving v7.2 cards, sending material to an external model, compiling procedural
knowledge, rendering `SKILL.md`, and sharing or publishing derived artifacts.

It is not legal advice, a fair-use determination, acceptance of any platform
terms, a platform compliance audit, a store approval, or permission to ingest a
specific source. Those remain external-authority lanes.

## 1. Shadow Architect verdict

The primary risk is not whether a WebView can render a source. The primary risk
is silently collapsing several distinct permissions into one:

```text
visible to the user
!= owned by the user
!= authorized for automated extraction
!= authorized for retention
!= authorized for external-model transmission
!= authorized for derivative compilation
!= authorized for public or commercial distribution
```

The system must also keep these evidence lanes separate:

```text
platform/API feature available
!= platform operation permitted
!= account authorized
!= content owner authorized
!= legal use established
!= physical substrate capable
!= exact operation exercised
!= store accepted
```

A source adapter may produce a bounded observation proposal. It may not create
permission, ownership, or legal clearance.

## 2. Protected assets and affected parties

Protected assets include:

- source audiovisual bytes, captions, transcripts, text, figures, tables,
  thumbnails, layouts, scripts, examples, and distinctive expression;
- creator name, face, voice, likeness, reputation, trademarks, and brand;
- user browsing history, selections, account identity, cookies, tokens,
  subscriptions, private pages, customer content, and organization content;
- derived cards, evidence graphs, procedure candidates, `SKILL.md`, hidden evals,
  experiment receipts, and user outcomes;
- platform API data, statistical data, metadata, identifiers, rate limits,
  credentials, attestation state, and compliance status;
- employer, customer, workspace owner, publisher, platform, content owner,
  model provider, app-store reviewer, and end-user rights.

## 3. Required state vocabularies

### 3.1 Source access class

```text
PUBLIC_VISIBLE
USER_OWNED
USER_LICENSED
USER_AUTHORIZED
ORGANIZATION_AUTHORIZED
PLATFORM_API_AUTHORIZED
PARTNER_LICENSED
RESTRICTED
UNKNOWN
REVOKED
```

`PUBLIC_VISIBLE` means only that a user can presently view the source. It grants
no automatic extraction, storage, model-egress, derivative, or publication
right.

### 3.2 Expression retention class

```text
LOCATOR_ONLY
HASH_ONLY
SHORT_EXCERPT_REVIEW_ONLY
TRANSIENT_PROCESSING_ONLY
BOUNDED_PRIVATE_RETENTION
FULL_COPY_LICENSED
RETENTION_DENIED
```

### 3.3 External-model destination class

```text
LOCAL_ONLY
APPROVED_BUSINESS_API
APPROVED_ENTERPRISE_WORKSPACE
PERSONAL_CONSUMER_MODEL
UNAPPROVED_PROVIDER
UNKNOWN_DESTINATION
```

Private, employer, customer, paywalled, unpublished, licensed, or confidential
material defaults to `LOCAL_ONLY` until exact data-and-destination authority is
recorded.

### 3.4 Adapter decision

```text
ALLOW
ALLOW_WITH_REDACTION
TRANSIENT_ONLY
DEGRADE_TO_LOCATOR_ONLY
OPEN_EXTERNALLY
PLATFORM_DENIED
RIGHTS_UNKNOWN
PRIVATE_EGRESS_DENIED
AUTH_FLOW_DENIED
DRM_OR_PROTECTED_SURFACE
PHYSICAL_CAPABILITY_ABSENT
EXTERNAL_AUTHORITY_REQUIRED
```

### 3.5 Evidence state

Use the repository vocabulary without normalization:

```text
PASS
FAIL
ABSENT
NOT_IMPLEMENTED
NOT_EXERCISED
SKIPPED_BY_POLICY
EXTERNAL_AUTHORITY_REQUIRED
```

## 4. Operation-level admission matrix

Each source is evaluated per operation. A single broad `source_allowed=true`
flag is forbidden.

| Operation | Required admission | Common blocked condition |
|---|---|---|
| Render through official surface | platform route + runtime support | embed denied, region/age restriction, unsupported WebView |
| Navigate or seek | official API or normal user navigation | simulated hidden gestures, stale player/page identity |
| Observe visible structure | origin/site policy + privacy filter | cross-origin iframe, CSP, protected field, site automation prohibition |
| Store source locator | stable source ID and privacy basis | private URL, sensitive query parameters, revoked access |
| Store short excerpt | rights/retention policy + bounded purpose | paywall, confidential source, excessive or market-substituting excerpt |
| Store full transcript/media/page | explicit ownership/license | third-party public source, DRM, API storage limits |
| Capture frames/screenshots | platform + content + device permission | protected surface, FairPlay/Widevine, personal data, app policy |
| Send source content to model | exact content-and-destination authority | personal consumer model, confidential source, unknown provider retention |
| Generate independent card | source lineage + no expression leakage | paraphrase too close, unsupported inference, personal data retention |
| Compile procedure | cross-case/qualification gate | one case promoted to law, missing counterexample, unknown rights |
| Render `SKILL.md` | qualified Procedural IR | examples carry protected expression, creator style cloning |
| Share privately | recipient authority + source restrictions | organization/customer contract excludes onward sharing |
| Publish/commercialize | legal/platform/store/brand admission | unlicensed expression, misleading affiliation, source substitution |

## 5. Platform-contract risks

### 5.1 YouTube

#### Allowed product boundary

Prefer:

```text
YouTube official embedded player
+ source/video identity
+ player events and timestamps
+ independent adjacent card/graph UI
+ derived independently worded procedural artifacts
```

#### L3 block conditions

- downloading, importing, backing up, caching, or retaining YouTube audiovisual
  content as the ordinary ingestion path;
- separating or exporting audio;
- enabling background playback outside YouTube's permitted experience;
- blocking, replacing, modifying, or suppressing ads;
- obscuring player controls, branding, metadata, links, or standard rendering;
- overlaying the card UI on top of any part of the embedded player;
- interfering with cookies, playback context, HTTP Referer, app identity, or
  Media Integrity signals used by YouTube;
- requiring a survey, subscription, download, reward, or other action before a
  user may play a selected video;
- charging users merely to watch freely available YouTube content;
- treating Premium membership as permission to download, extract, or bypass
  restrictions;
- claiming embedded-player readiness when the video is private, age/region
  restricted, deleted, live-only, or embedding is disabled;
- using the captions API as a public transcript endpoint. Caption-list/download
  operations require authorization and do not create rights to third-party
  captions;
- retaining YouTube API data without the applicable refresh/deletion policy;
- creating prohibited derived metrics, channel scores, inferred monetization,
  audience composition, or financial projections from YouTube API data;
- presenting the product as an official YouTube or Google integration without
  authorization.

#### Physical and semantic limitations

- ads, age gates, consent screens, login prompts, and playback errors may
  interrupt timeline synchronization;
- translated captions, automatic captions, dubbed audio, creator edits, trims,
  live-stream transitions, and source deletion may invalidate timestamps;
- captions can be absent, inaccurate, delayed, incomplete, or semantically
  dependent on visuals;
- iframe origin isolation prevents arbitrary DOM access;
- Media Integrity or app identity proves neither account entitlement nor content
  authorization;
- Premium state is not exposed as a general public API entitlement and may not
  carry into an embedded WebView session;
- DRM/protected playback may prevent screenshots, frame capture, or visual model
  access even while playback succeeds;
- an API compliance audit, quota grant, or derived-metric amendment is a
  separate external-authority state.

### 5.2 X

X's current automation rules prohibit non-API website scripting. Therefore:

```text
normal user browsing / visible reading        potentially admissible
WebView script that automates X actions       PLATFORM_DENIED
API-based action                              separate X API admission
```

L3 block:

- scripted website automation, automated scrolling/clicking/posting/liking,
  following, messaging, or rate-limit circumvention;
- collecting private or sensitive user information from posts or sessions;
- storing or reproducing deleted/restricted content as though still public;
- bulk copying threads/articles into public datasets or Skills;
- automated replies, posts, or direct messages without an admitted API route,
  user expectation, anti-spam controls, and explicit action authority;
- using X branding or interface treatment in a way that implies official status.

Observation-only support must tolerate login walls, dynamic rendering, edited or
deleted posts, suspended accounts, region restrictions, and DOM changes. A
browser-visible X article is not an API-stable source.

### 5.3 Notion

An authenticated Notion page may belong to an employer, customer, school, or
managed workspace. Workspace owners may access private pages, disable services,
or restrict moving data into or out of the workspace. User access therefore does
not establish export, model-egress, or derivative-publication authority.

L3 block:

- treating workspace membership as content ownership;
- sending employer/customer Notion content to a personal model subscription;
- storing private block content in a public repository or cross-tenant cache;
- bypassing export restrictions, permission changes, login, or workspace policy;
- relying on DOM structure as a stable API contract;
- retaining private page URLs, tokens, member names, comments, or task data in
  public receipts.

Use a first-party Notion API/connector when exact authorized content access is
needed. Browser observation should remain bounded to active-session visible
context and cannot silently become a bulk export path.

### 5.4 Google Drive, Docs, and managed accounts

Risks include:

- employer or school ownership and admin policy;
- Data Loss Prevention, sharing, export, and third-party-app restrictions;
- revision drift, revoked links, moved/deleted files, and link-sharing changes;
- comments/suggestions or hidden metadata containing personal or confidential
  data;
- OAuth verification, sensitive/restricted scopes, token revocation, and
  deletion obligations;
- confusion between Google identity, API authorization, and Web session state.

Google OAuth must use a secure system-browser/Credential Manager route. A
developer-controlled embedded WebView is not an admitted OAuth user agent.

### 5.5 Generic Web, blogs, newsletters, and paywalls

Risks include:

- robots and terms restrictions even when technical access succeeds;
- paywall/session access that permits reading but not extraction or onward use;
- article edits, canonical URL changes, redirects, removed pages, and dynamic
  personalization;
- cookie banners and consent state altering the visible text;
- CSP, cross-origin frames, anti-bot measures, and script-heavy rendering;
- malicious prompt injection or hidden text designed to influence the Agent;
- personal data in comments, forms, account pages, URLs, or embedded widgets;
- advertising, affiliate, and sponsored-content disclosures that must not be
  stripped from evidence;
- trademark or passing-off risk if a derived workspace appears official.

A generic page adapter may observe and propose cards; it may not bypass site
controls or assume site-wide permission.

## 6. Copyright and adjacent rights

### 6.1 Idea, procedure, and expression are different

The system aims to generalize procedures, decision criteria, failure modes, and
measurement loops, not reproduce protected expression. However, writing a
procedure in different words does not automatically eliminate all risk.
Potentially protected or restricted elements include:

- scripts, narration, examples, selection and arrangement, course sequence,
  exercises, diagrams, slides, screenshots, photographs, illustrations, music,
  sound recordings, captions, translations, tables, and distinctive wording;
- the "heart" of a work even when the copied amount is short;
- a creative collection or taxonomy even when individual facts are not
  protected;
- adaptation/translation rights;
- database rights or contract restrictions in some jurisdictions;
- moral rights, attribution, and integrity rights;
- voice, face, likeness, publicity/personality, and synthetic-replica rights;
- trademarks, trade dress, titles, logos, and false endorsement;
- confidential information and trade secrets, which are not cured by copyright
  analysis.

### 6.2 Fair use/fair dealing is not a machine gate

Research, criticism, teaching, commentary, and transformation may be relevant in
some jurisdictions, but the result is fact-specific. There is no safe universal
percentage, word count, clip duration, or page count. Commercial purpose, the
creative nature of the source, amount/substantiality, and market substitution
all matter.

Therefore:

```text
FAIR_USE_ASSERTED_BY_MODEL -> forbidden
LEGAL_REVIEW_REQUIRED      -> external authority
```

### 6.3 Source leakage controls

Public Skill bundles must not contain:

- full or near-full transcripts;
- scene-by-scene substitutes for the source;
- long excerpts or high-resolution source frames;
- copied article/book sections;
- creator-specific scripts, examples, or distinctive voice;
- thumbnails, logos, cover art, or media assets without permission;
- paywalled or private-course material;
- source content sufficient to replace purchasing or viewing the original.

Prefer:

```text
stable locator
+ source identity
+ content/excerpt hash where lawful
+ independently worded observation
+ evidence class
+ procedure delta
```

## 7. Privacy, identity, and sensitive inference risks

Browsing and source-consumption history can reveal health, religion, politics,
sexuality, finances, immigration, employment, legal matters, or other sensitive
interests. A card graph may be more revealing than any single page.

Required controls:

- explicit local/private ownership of personal research history;
- least-retention and deletion/export controls;
- profile/tenant separation;
- no password, token, OTP, bank, payment, private-key, or sensitive form values in
  page context, cache, logs, MCP, cards, or receipts;
- no facial recognition, voice identification, or sensitive-person inference
  from creator media;
- no background surveillance or undisclosed watch-history tracking;
- separate user identity, OAuth API authorization, website session, Premium
  entitlement, and device attestation;
- child/minor content and account use require a separate policy and legal lane;
- user outcomes must not expose customer names, audience identities, or private
  sales/accounting data in public Skills.

## 8. External LLM and model-provider egress

Sending source material to a model provider is a new disclosure, not merely a
local transformation.

The router must evaluate:

```text
source access class
+ content owner / organization authority
+ destination product and account type
+ training/data-control setting
+ retention / abuse-monitoring policy
+ geographic/data-processing requirements
+ minimum necessary excerpt
```

Key risks:

- consumer plans may have different training defaults and data controls from
  business/API products;
- feedback actions may cause a broader conversation to be retained or used;
- screenshots, frames, browser context, and full environments may have their own
  retention controls;
- provider abuse monitoring may allow authorized human review of flagged data;
- model output may reconstruct protected expression or reveal source content;
- multi-provider routing duplicates disclosure and complicates deletion;
- a user's subscription does not grant rights to transmit third-party,
  employer, customer, or paywalled content;
- deleting the local card does not prove deletion at every provider.

Default routing:

```text
public factual source + bounded excerpt       -> policy review required
public creative source + bounded observation  -> minimize expression
private/premium source                        -> LOCAL_ONLY
employer/customer source                      -> ORGANIZATION_AUTHORIZED destination only
unknown rights or destination                 -> PRIVATE_EGRESS_DENIED
```

## 9. App-store and distribution risks

### 9.1 Apple App Store

Store review can reject an app that is primarily a repackaged website, web
clipping tool, content aggregator, collection of links, copycat, or app that uses
third-party material/services without permission. The app must provide lasting,
independent, app-like value.

The product must therefore demonstrate that the native capability editor,
source-linked evidence graph, procedure DAG, qualification, replay, private
workspace, and outcome loop are the product—not YouTube/X/Notion content itself.

Other risks:

- third-party names/logos in app name, icon, screenshots, or metadata;
- failure to give App Review full access or a demo mode;
- excessive battery, heat, storage, or background work;
- unsupported browser engines or non-WebKit behavior on Apple platforms;
- reliance on another installed app for core functionality;
- account deletion, privacy labels, data collection disclosure, subscriptions,
  and digital-content payment rules when later monetized.

### 9.2 Google Play

Risks include impersonation/misleading affiliation, deceptive feature claims,
copyright complaints, data safety disclosure mismatch, account/API abuse,
malware or unsafe WebView bridges, and a product whose store listing claims
physical, platform, Premium, or legal capabilities not actually verified.

Store metadata must not imply:

- official YouTube, Google, X, Notion, OpenAI, Anthropic, or creator affiliation;
- ad-free/Premium playback controlled by this app;
- universal content extraction;
- legal permission or copyright clearance;
- automatic login/session transfer;
- store/device/runtime support beyond exact evidence.

## 10. WebView and native security risks

L3 block:

- exposing `addJavascriptInterface` to untrusted arbitrary pages;
- evaluating raw model text as JavaScript, URL, selector, coordinate, or native
  call;
- loading unvalidated URLs or javascript URLs;
- enabling universal/file-origin access or unsafe `file://` behavior;
- allowing mixed content without an explicit reason;
- sharing cookies, profiles, cache, cards, or audit state across tenants;
- accepting page messages without origin, schema, size, sequence, and freshness
  validation;
- allowing source content to grant its own capability;
- treating an isolated JavaScript world as a sandbox or authorization boundary;
- allowing WebView downloads without MIME, size, destination, rights, and user
  admission;
- persisting sensitive page data through screenshots, recents previews, logs, or
  crash reports.

Prompt injection is source data, not authority. The source may propose a card but
cannot modify policy, tools, Skill qualification, destination, retention, or
publication state.

## 11. Physical and runtime limitations

### 11.1 Availability and source drift

A source may become:

```text
DELETED
PRIVATE
UNLISTED_BUT_LINKED
REGION_RESTRICTED
AGE_RESTRICTED
EMBED_DISABLED
LOGIN_REQUIRED
PAYWALLED
REVISED
TRIMMED
LIVE_OR_UNFINALIZED
ACCOUNT_SUSPENDED
```

Locators require revision/freshness checks. A timestamp or DOM anchor is not
permanent identity.

### 11.2 Protected media and capture

DRM-protected surfaces may play while screenshots, recording, frame extraction,
mirroring, or external video outputs return black/redacted content. FairPlay and
Widevine capability varies by device, OS, security level, renderer, route, and
content provider. The product must never treat a black frame as empty content or
attempt a bypass.

### 11.3 Caption and multimodal limits

- captions may be missing, auto-generated, mistranslated, mis-timed, or absent
  from official APIs;
- visual steps may occur between sampled frames;
- charts, fine text, cursor movement, code, animation, sign language, tone,
  music, and multi-speaker context may not survive transcript-only processing;
- dubbed tracks may not match original captions;
- a multimodal provider's analysis is an observation proposal, not source truth;
- model context limits and provider cost require caching/deduplication, but
  source-media caching may be prohibited.

### 11.4 Device fragmentation

- Android WebView features vary by WebView package/version/device;
- Media Integrity, profiles, isolated worlds, navigation APIs, passkeys, and
  other features must be probed, not assumed;
- iOS WKWebView, Android WebView, Desktop KCEF, and Wasm have non-equivalent
  cookies, storage, process, media, DRM, extension, and injection behavior;
- a desktop/simulator PASS does not establish phone/tablet, Play Store, App
  Store, DRM, Premium, background, or physical-device behavior.

### 11.5 Resource and lifecycle limits

- WebView renderer processes can crash or be killed for memory reclamation;
- destroyed WebView instances cannot be reused;
- long videos, card graphs, screenshots, embeddings, and model contexts can cause
  memory, disk, network, token, battery, thermal, and latency pressure;
- background execution, autoplay, media playback, microphone/camera, file access,
  notifications, and screen capture are OS- and policy-constrained;
- split-screen, keyboard, rotation, foldables, safe areas, accessibility,
  caption controls, and player minimum dimensions affect usable layout;
- offline mode cannot imply offline YouTube playback or retained media;
- network failures may occur after an external operation partially completed;
- clock/time-base mismatch can desynchronize cards, ads, seek state, live streams,
  and playback events.

Required lifecycle states:

```text
SOURCE_REQUESTED
-> SOURCE_ADMITTED | SOURCE_BLOCKED
-> RENDERER_STARTING
-> RENDERING | DEGRADED | EXTERNAL_APP_REQUIRED
-> OBSERVING
-> CARD_INDEX_READY
-> SUSPENDED | RENDERER_GONE | SOURCE_REVOKED
-> RESTORED | REINDEX_REQUIRED | TERMINAL_BLOCKED
```

## 12. Business-continuity and product risks

- Chrome, Gemini, YouTube, X, Notion, or OS vendors may add native page/video
  understanding, note indexing, or Agent functions;
- platform policy, pricing, quota, API availability, embed rules, and audits may
  change;
- external model quality/cost/version and subscription limits are not product
  invariants;
- depending on unauthorized scraping creates a non-durable product;
- supporting every source can dilute qualification quality and store-review
  differentiation;
- a public source graph without user outcome evidence may become a commodity;
- platform-native features can replace summarization but not the portable
  `Source -> Evidence -> Procedure -> Skill -> Outcome` graph.

The durable product boundary remains:

```text
source-independent locator and evidence contracts
+ user-owned cross-source capability graph
+ explicit rights/destination admission
+ qualified portable Skills
+ user outcome foldback
```

## 13. Source-adapter risk matrix

| Source | Default observation route | Persistent source material | Major blockers | Preferred fallback |
|---|---|---|---|---|
| YouTube | official embed/player events | locator + independent cards | no embed, policy, captions, DRM, account/session | native YouTube app + locator-only workspace |
| X | active-session reading or API | locator + independent cards | non-API automation prohibited, login/DOM drift | open externally; API only for admitted actions |
| Notion | authorized connector/API or bounded active session | block/page locator + independent cards | organization ownership/export policy | local-only/manual authorized export |
| Google Docs/Drive | scoped OAuth/connector | file/revision locator + independent cards | admin/DLP/scopes/revocation | system browser + authorized export |
| Generic Web | normal rendering/active session | URL/DOM locator + independent cards | terms, paywall, CSP, anti-bot, prompt injection | browser/custom tab/locator-only |
| PDF | user-supplied local file | page/region locator; excerpts only as admitted | scanned pages, rights, passwords, DRM | local OCR with rights gate |
| EPUB/ebook | user-supplied authorized file | chapter/CFI locator; no raw public bundle | DRM, reflow, license restrictions | open in licensed reader; manual locator |
| Private course | partner/user authorization | private evidence graph only | contract, DRM, model egress, substitution | partner integration or local-only |
| Enterprise/customer content | organization-approved connector | tenant-private graph | confidentiality, trade secret, DPA, admin | approved enterprise/API destination only |
| User-owned media | local import | as user directs within storage policy | third-party material embedded in own media | rights review per component |

## 14. Machine-readable admission contract proposal

Future source adapters should emit a data-only descriptor similar to:

```yaml
source_id: SRC-...
source_type: youtube | x | notion | web | pdf | epub | local_media
subject_revision: ...
access_class: PUBLIC_VISIBLE
owner_or_controller: UNKNOWN
platform_route: OFFICIAL_EMBED
operations:
  render: ALLOW
  observe_visible_text: ALLOW_WITH_REDACTION
  retain_locator: ALLOW
  retain_excerpt: RIGHTS_UNKNOWN
  retain_full_source: PLATFORM_DENIED
  external_model_egress: RIGHTS_UNKNOWN
  compile_independent_card: ALLOW_WITH_REDACTION
  publish_derived_skill: EXTERNAL_AUTHORITY_REQUIRED
retention:
  raw_source: RETENTION_DENIED
  api_metadata: REFRESH_OR_DELETE_REQUIRED
  derived_cards: PRIVATE_UNTIL_QUALIFIED
physical_capabilities:
  exact_device: NOT_EXERCISED
  drm_capture: NOT_EXERCISED
unknowns:
  - content-owner derivative permission
```

No model may rewrite admission fields. Only trusted policy and authorized human or
organization decisions may transition external-authority states.

## 15. L3 Shadow Architect block list

Block the named transition when any of these occur:

1. public visibility is treated as copy/retention/model/publication permission;
2. a platform integrity signal is treated as content authorization;
3. platform controls, ads, links, branding, cookies, or restrictions are
   suppressed or bypassed;
4. non-API X automation or developer-controlled embedded Google OAuth is used;
5. DRM, paywall, login, robots, CSP, anti-bot, or organizational controls are
   bypassed;
6. full source media/transcript/page content is retained without exact authority;
7. private, customer, employer, or premium content leaves an authorized realm;
8. one case becomes a universal procedural law;
9. source expression or creator identity leaks into a public Skill;
10. legal/fair-use/store/platform acceptance is self-declared;
11. YouTube API data retention/derived-metric rules are ignored;
12. store metadata implies official affiliation or unavailable capability;
13. simulator/build/contract evidence is promoted to device/DRM/store/production;
14. source revocation/deletion cannot propagate to locators, raw caches, model
    destinations, and derived artifacts;
15. an operation has no rollback, deletion, or evidence owner.

## 16. Implementation sequence

### Phase A — contract and static controls

- source-operation admission schema;
- rights, destination, retention, and physical-capability vocabularies;
- source leakage scanner for generated cards and Skills;
- negative fixtures for public-but-not-copyable, private workspace,
  employer-controlled, DRM, deleted, and revoked sources;
- explicit provider-egress profile.

### Phase B — first-party and low-risk adapters

- user-owned local text/PDF fixtures;
- ordinary public web with locator-only persistence;
- YouTube official embed with adjacent card surface and no media retention;
- external-app fallback.

### Phase C — authenticated and organization sources

- secure system-browser OAuth;
- tenant/profile separation;
- approved connectors and revocation/deletion propagation;
- enterprise/private-model destination policies.

### Phase D — multimodal and partner media

- exact rights-gated frame/slide extraction;
- protected-surface detection;
- partner licensed media workspace;
- physical-device and store evidence.

## 17. Primary-source references

Current architecture review used primary or platform-owned sources including:

- YouTube Developer Policies and compliance guide;
- YouTube Required Minimum Functionality;
- YouTube IFrame Player API and captions documentation;
- YouTube derived-metrics and API-data storage policy;
- Google OAuth 2.0 policies and native-app guidance;
- Android WebView, JS bridge, local-content security, and renderer termination
  documentation;
- X automation rules;
- Notion workspace-owner data-access documentation;
- Apple App Review Guidelines;
- Apple FairPlay/screen-capture documentation;
- Android DRM/protected-content documentation;
- U.S. Copyright Office Fair Use Index;
- model-provider privacy/data-control documentation.

Primary-source presence does not convert the resulting architecture review into
legal advice or platform approval.
