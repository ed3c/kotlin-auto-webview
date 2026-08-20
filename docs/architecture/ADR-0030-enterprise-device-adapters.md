# ADR-0030: Bounded enterprise Accessibility adapter

Status: accepted for Stage 6 implementation on issue #73.

## Context

The portable device runtime already owns profile policy, Local Dispatcher admission, exact HITL binding, target-token freshness, deterministic postcondition verification and the NONE/APPLIED/UNKNOWN effect ledger. Android observation issue #67 already produces privacy-bounded snapshots and session-bound opaque target tokens. OpenDroid is therefore a capability reference, not an execution authority.

The first enterprise delivery must not recreate OpenDroid's direct execution, first-match lookup, coordinate gestures, generic shell/root fallback or callback-as-success semantics.

## Decision

The first `ENTERPRISE_SIDELOAD` execution leaf admits exactly one state-changing family: `enterprise.accessibility.click-exact-target` / `ui.click.exact-target`.

The common-facing descriptor is enterprise-only, `DEVICE_WIDE_ACCESSIBILITY`, `ACCESSIBILITY`, HIGH risk, USER_CONFIRMATION, and has one current independent postcondition verifier plan. Fill, select, scroll, global actions, app launch and every other action family remain NOT_IMPLEMENTED until a separate typed capability/verifier leaf admits them.

The actual `AccessibilityService` is compiled only from `androidEnterprise`. It is exported only so Android may bind it, and the binding surface is protected by `android.permission.BIND_ACCESSIBILITY_SERVICE`. Service enablement is not performed by the application and remains Human/device authority.

Execution is fail-closed:

1. A Human-owned exact package allowlist is empty by default.
2. A sanitized #67 snapshot is captured for the managed package and retained only as the current in-memory observation.
3. The common target resolver accepts only an exact `UiTarget` fingerprint from that snapshot and issues a short-lived #67 session token.
4. The token binding also freezes the sanitized accessible-name digest and structural digest because the portable fingerprint intentionally excludes accessible-name text.
5. Immediately before dispatch, the adapter recaptures the exact package/window/display/generation, validates the session token, verifies the frozen semantic/structural digests, and re-traverses the current native tree using the same #67 fingerprint material.
6. Zero, many, stale, hidden, disabled, non-clickable, sensitive or semantically changed targets do not dispatch. There is no text lookup, view-ID lookup, selector, coordinate, ancestor or nearest-clickable fallback.
7. `AccessibilityNodeInfo.performAction(ACTION_CLICK)` returning true produces only `DevicePlatformDispatchEvidence`. The common runtime's independent postcondition verifier is the only component that may later resolve the effect to APPLIED or NONE. Exceptions after invocation are UNKNOWN.

Any Accessibility event invalidates the cached observation and all execution bindings through the #67 generation/event-sequence session.

## Privileged execution boundary

Shizuku remains explicitly NOT_IMPLEMENTED in this leaf. No Shizuku dependency, UserService, process API, command string, terminal, `Runtime.exec`, `ProcessBuilder`, `sh -c`, `su`, root fallback or arbitrary intent surface is introduced.

## Distribution boundary

The enterprise capability manifest declares the exact Accessibility service and `allow_accessibility_service=true`. `allow_shizuku`, `allow_root_or_shell` and inbound mobile MCP remain false. The Play-safe source set, manifest and capability profile are untouched; inherited package checks must continue to prove that enterprise authority does not enter the Play-safe APK.

## Evidence ceiling

Static/unit/package PASS proves a bounded enterprise Accessibility click adapter exists and remains distribution-separated. It does not prove user enablement, a connected Accessibility service on a physical device, OEM timing, managed-fleet policy, Shizuku, Google Play eligibility, signing, merge, release or production readiness. Device/emulator/physical evidence belongs to issue #74.
