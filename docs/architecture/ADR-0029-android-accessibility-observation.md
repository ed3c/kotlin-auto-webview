# ADR-0029: Privacy-bounded Android accessibility observation

## Status

Accepted for the A1 first vertical slice. This ADR does not register or enable an `AccessibilityService` and does not authorize device actions.

## Context

Issue #67 consumes the C1 portable snapshot/target contracts. OpenDroid demonstrates the usefulness of accessibility-tree observation but also uses first-match and clickable-ancestor fallbacks that are not strong enough to carry execution authority.

## Decision

Keep Android observation as a one-way anti-corruption layer:

```text
raw Android observation
→ bounded node/depth validation
→ sensitivity classification
→ redact before portable construction
→ deterministic structural/fingerprint digests
→ immutable DeviceUiSnapshot
→ exact subject + task + generation + freshness checks
→ zero / one / many candidate resolution
→ short-lived opaque token only for the one-candidate case
```

The first slice deliberately models raw observation as Android-local data rather than retaining `AccessibilityNodeInfo`. A later platform adapter may translate a live node tree into this bounded frame, but the frame and portable snapshot have no click, gesture, text-entry, intent, shell, MCP, or coordinate execution API.

## Invariants

- Sensitive values are erased before `DeviceUiElementSnapshot` construction.
- Node count and ancestry depth are bounded; duplicate IDs, missing parents, and cycles fail closed.
- Package, window, display, task, generation, event sequence, capture time and content digest remain bound to the snapshot.
- Target resolution rejects subject/task/generation/freshness mismatch before issuing a token.
- Role/name disagreement returns no match; there is no first-match or nearest-clickable fallback.
- Tokens are injected by a process-local factory, bounded, short-lived, and are not portable action coordinates.
- No Manifest, accessibility-service XML, screenshot fallback, Shizuku, root, shell, or direct MCP authority is introduced here.

## Evidence ceiling

A green unit/build matrix proves the pure Android observation projection and exact-resolution core against deterministic fixtures. It does not prove a live AccessibilityService connection, Android lifecycle correctness, real third-party app coverage, side effects, physical-device behavior, Play eligibility, merge, release, or production privacy.
