# Threat model

This document is the compact runtime-security entry point. The detailed
platform-policy, media-rights, privacy, external-model-egress, store-review, and
physical-substrate admission contract is
[`CONTENT_PLATFORM_MEDIA_RISK_REGISTER.md`](CONTENT_PLATFORM_MEDIA_RISK_REGISTER.md).

The detailed register is a fail-closed architecture contract, not legal advice,
platform approval, store acceptance, or permission to ingest a particular
source.

## Protected assets

- Page text, media, captions, transcripts, selections, figures, and source
  locators
- Authentication/session material, cookies, OAuth grants, account identity, and
  subscription state
- Device capabilities, browser profiles, local files, and organization/customer
  data
- MCP tool authority, capability policy, dispatcher state, and audit receipts
- Semantic cache, v7.2 cards, procedure graphs, Skill candidates, hidden evals,
  and user outcomes
- Creator name, face, voice, likeness, brand, copyrighted expression, and
  confidential methods
- Platform API data, quotas, compliance state, retention obligations, and app
  identity/attestation state

## Core trust laws

```text
visible != owned != extractable != retainable != model-shareable != publishable
integrity != authorization
identity != OAuth authority != website session != subscription entitlement
public source != public dataset
successful render != legal/platform/store admission
```

A source adapter can propose a bounded observation. It cannot create ownership,
permission, legal clearance, or execution authority.

## Primary threats and controls

| Threat | Control in this repository | Remaining work / evidence ceiling |
|---|---|---|
| Prompt injection asks model to click/pay/delete | typed capabilities, deny-by-default registry, risk ceiling, HITL | signed policy bundles and executor sandbox |
| Password/payment/token values leak through DOM | JS excludes sensitive inputs; Kotlin redacts again | site-specific privacy tests and destination policy |
| User and agent race for UI authority | pointer input preempts dispatcher | platform gesture telemetry and cancellation token |
| Malicious/untrusted MCP peer | no default inbound mobile listener; authenticated bounded transports | live issuer/mTLS/workload-identity evidence varies |
| Context replay/stale projection | cache/card/source identity, timestamp, fingerprint, relevance | expiry, source-revision and revocation propagation |
| Arbitrary website blocks injection or iframe access | explicit failure/fallback; no bypass | first-party adapters and locator-only fallback |
| Public source treated as permission to copy/store/send/publish | operation-level rights, retention and destination decisions | machine-readable source-admission schema |
| Single successful creator case becomes universal Skill law | cross-case evidence classes, negative triggers, Shadow promotion block | independent qualifier fixtures and user outcomes |
| Source expression or creator style leaks into cards/Skill | independent wording, locator-first evidence, anti-hollow/leakage gates | deterministic leakage scanner and adversarial corpus |
| Private/paywalled/employer/customer content leaves authorized realm | `LOCAL_ONLY` default, profile/tenant separation, destination admission | provider-specific data-processing profiles and deletion receipts |
| YouTube controls/ads/branding/context are modified | official player, sibling knowledge UI, policy-aware fallback | exact-device embed, Media Integrity and store evidence |
| YouTube media/audio or captions are downloaded without authority | no audiovisual download path; captions API not treated as public transcript source | partner/user-owned licensed source adapters |
| X website is automated through WebView scripting | observation-only browser lane; actions require an admitted API route | X API/product contract and account safety evidence |
| Google OAuth is run inside developer-controlled WebView | Credential Manager/system browser/Custom Tab only | OAuth verification and scoped connector implementation |
| Authenticated Notion/Drive content is assumed user-owned | workspace/organization ownership and export authority remain explicit | approved connector and admin/DLP policy evidence |
| DRM/protected media is captured or black frames are misread | protected-surface state; no bypass; black/redacted output is not empty evidence | physical-device FairPlay/Widevine matrix |
| WebView bridge enables cross-app scripting | origin allowlists, typed messages, sanitization, no raw model JS | isolated execution world and hostile-origin tests |
| Unsafe local-file/mixed-content configuration exposes device data | deny universal/file-origin access; prefer asset loader and HTTPS | platform configuration tests |
| Renderer crash or memory reclaim destroys long research session | durable card/audit checkpoints and renderer-gone state are required | physical low-memory recovery canaries |
| App is rejected as web clipping/content aggregator/copycat | native capability editor, evidence graph, procedure/Skill/outcome value; truthful branding | App Store/Play review is external authority |
| Store metadata claims official/Premium/legal capability | exact evidence vocabulary and non-claims | signed binaries and review submission not exercised |
| Supply-chain compromise | pinned dependencies and checksum-pinned Gradle distribution | dependency verification metadata, SBOM, signed releases |

## Source-operation boundary

Every adapter must decide separately whether it may:

```text
render
navigate/seek
observe visible structure
retain locator
retain excerpt
retain full source
capture frames
send to external model
compile independent card
compile procedure/Skill
share privately
publish/commercialize
```

A broad `source_allowed` flag is forbidden.

## Mandatory L3 blocks

Block only the named unsafe transition when any of these occur:

- platform controls, ads, links, branding, auth, DRM, paywall, robots, CSP,
  anti-bot, region/age, or organization controls would be bypassed;
- public visibility is promoted to copy, retention, external-model, derivative,
  or publication permission;
- source/API data retention or refresh obligations are absent;
- private, customer, employer, premium, or unpublished material would leave its
  authorized realm;
- a creator's wording, scripts, examples, style, face/voice identity, or media
  assets would become the product output;
- legal/fair-use, platform compliance, store acceptance, Premium entitlement,
  device integrity, or content authorization is self-declared;
- simulator/build/static evidence is promoted to physical-device, DRM, store, or
  production evidence;
- source revocation/deletion cannot propagate to caches, locators, provider
  destinations, and derived artifacts;
- no owner exists for deletion, rollback, retention, or evidence.

## Physical limitations that remain explicit

- captions may be absent, inaccurate, translated, delayed, or detached from
  essential visuals;
- videos/pages may be deleted, edited, trimmed, private, age/region restricted,
  embed-disabled, live, or login/paywall protected;
- cross-origin frames and platform DOMs may be unobservable;
- DRM can allow playback while screenshots/frame extraction are black or
  redacted;
- Android WebView, WKWebView, KCEF and Wasm do not have capability parity;
- WebView features vary by provider/version/device and require runtime probes;
- renderer death, low memory, battery, heat, network, disk, token/context, and
  background limits can interrupt indexing;
- cookie/session partitioning means browser login or Premium state may not exist
  in the embedded runtime;
- ads, consent screens, live streams, dubs and source edits can desynchronize
  timestamp cards.

## Non-goals

This repository does not attempt to bypass CSP, anti-bot controls, paywalls,
DRM, platform permissions, advertising, App Store/Google Play policy, browser
origin security, OAuth user-agent restrictions, or content rights. It does not
provide legal advice or claim that independently worded procedural abstraction
is automatically lawful.
