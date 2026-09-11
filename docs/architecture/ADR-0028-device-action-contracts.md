# ADR-0028: Typed portable device-action contracts

## Status

Accepted for C1 implementation. This ADR proves only portable contract and policy-ceiling semantics.

## Context

OpenDroid exposes useful capability taxonomy, accessibility observation, workflow, and verification concepts, but its Android `Context`, string parameter maps, semantic aliases, direct MCP execution, first-match targeting, and privileged shell/root surfaces cannot cross the Kotlin Multiplatform authority boundary. Source admission is pinned by issue #65 and its exact static receipt.

The existing browser executor remains authoritative for browser-only execution. C1 must not widen `AgentAction.arguments`, Android source sets, MCP authority, or platform execution.

## Decision

Create a dedicated `dev.ed3c.autowebview.device` namespace with three portable layers:

1. **contract** — typed proposals, subjects, opaque targets, sealed payloads, confirmation receipts, commands, verifier evidence, and honest `NONE | APPLIED | UNKNOWN` effect states;
2. **catalog** — bounded product capability descriptors plus a read-only OpenDroid compatibility catalog that reproduces all twelve admitted/rejected source records without turning aliases into authority;
3. **policy** — exact compiled-profile matching, capability/action/verifier admission, explicit permission and risk ceilings, PLAY_SAFE scope restrictions, and external admission for `ACCESSIBILITY_TOOL`.

There is deliberately no generic map payload, selector, coordinate, intent URI, shell command, package wildcard, Android lifecycle type, MCP execution interface, or runtime profile-widening switch.

## Invariants

- The proposal profile must equal the compiled profile; runtime data cannot widen it.
- PLAY_SAFE capability descriptors can only use own-WebView scope with no privileged execution.
- ACCESSIBILITY_TOOL cannot self-admit.
- A canonical action and verifier must be named by the capability descriptor before policy can admit a proposal.
- Confirmation binds proposal/action/capability/profile/subject/target/payload digest/policy version and freshness.
- Dispatch is an intermediate state, never success.
- A post-dispatch timeout cannot claim `NONE` without verifier evidence proving no effect.
- OpenDroid `REFERENCE_ONLY`, `DENIED_BY_ARCHITECTURE`, and `EXTERNAL_POLICY_ADMIT_REQUIRED` records remain visible data and are not executable candidates.

## Deferred ownership

- Android accessibility observation and exact target resolution: issue #67.
- Deterministic postcondition verifier/effect ledger: issue #68.
- Typed workflow DAG and revision semantics: issue #69.
- Build-profile packaging and distribution separation: issue #71.
- Android platform adapters and device evidence: issues #72–#74.

## Evidence ceiling

A green C1 test matrix proves serialization, constructor guards, policy ceilings, compatibility-catalog integrity, confirmation identity, and effect-state truthfulness only. It does not prove Android APIs, AccessibilityService lifecycle, platform side effects, emulator/physical-device behavior, Play eligibility, merge, release, or production readiness.
