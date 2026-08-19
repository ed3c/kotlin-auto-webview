# ADR-0032: Device runtime convergence and authority ordering

## Status

Accepted as the Stage-4 convergence contract for issue #70. This ADR describes source-level behavior only; merge, release, live AccessibilityService, physical-device and store-policy evidence remain outside this claim.

## Exact convergence subjects

The runtime branch starts from multi-parent commit `699a473f1df08e6f333f679c800c6f4fa95a7fe7`, whose tree is `309c68a52b1276ba574a0d285abbc20712a3261f` and whose parents are the selected source leaves:

- A1 Android observation: `b1c96e129009adc4c83b98a3728a9f1a39850025` / tree `90f1a0ca98b5d1e35928abb61be19fe8eae96d9b`;
- E1 verification/effect ledger: `bb5ee8a3973f17990c7e4e7ec99f1475d0b5256d` / tree `820e905bd69cff327112d3b379bd5ba72381e7f4`;
- K1 workflow DAG: `b2bfb31e0be94190f05b605b36301f9a670a7af6` / tree `52fc776e15f7cf3b28a0bb303840ba3b22904c66`.

The A1 evidence child `75e01b21f92c33b5e424fef9aff157954e05c997` is a completion receipt subject only. Its test/CI bytes are intentionally not imported into the product convergence tree.

## Authority order

`DeviceAutomationRuntime` is the only product-owned convergence path. It enforces this monotonic order:

1. receive a typed proposal; local, MCP, remote and model origins remain proposal-only;
2. resolve the canonical action against the capability catalog;
3. enforce the compiled distribution profile before capability policy;
4. evaluate capability/risk/permission/verifier/confirmation policy;
5. validate exact human confirmation where required;
6. validate and freeze the exact workflow revision, digest, node, confirmation and idempotency subject;
7. capture deterministic precondition evidence;
8. resolve one exact short-lived target token or fail on zero/many/stale;
9. revalidate user priority, screen/platform state, profile, policy version, workflow revision, subject, capability enablement, proposal/confirmation time and target-token identity;
10. create a typed `DeviceActionCommand` and an exact dispatch-admission binding;
11. treat the platform callback as dispatch evidence only;
12. run the registered deterministic verifier and commit `NONE | APPLIED | UNKNOWN` through the effect ledger;
13. emit a workflow receipt only from verifier/postcondition evidence; `UNKNOWN` therefore blocks dependents and retry in the K1 admission/recovery contracts;
14. commit a sanitized audit record containing identities and effect state, never raw payload/user text.

The existing `LocalDispatcher` can be composed through `LocalDispatcherDeviceRuntimeAuthoritySource`. Only `READY` admits the device runtime; `OBSERVING_USER` sets explicit user preemption and every other non-ready mode removes platform availability. This preserves the existing temporal rule that user activity outranks automation without giving the older browser dispatcher device execution authority.

## Effect truth

A platform callback, Accessibility API boolean, intent launch or future privileged Binder connection is never proof of application. The effect ledger enters `VERIFYING` after dispatch and can reach `TERMINAL_APPLIED` only with a matching `VerificationVerdictCode.APPLIED`. Inconclusive, contradictory, stale, lost-observer and unknown-plan cases terminate as `UNKNOWN` and require reconciliation.

Pre-dispatch invalidation does not create a false terminal effect record: precondition evidence is held in memory until final authority revalidation passes. Only then is the effect record opened and the precondition/dispatch-admission sequence committed. User/package/window/generation/profile/policy/workflow/capability/token invalidation therefore terminates with `NONE` before dispatch.

## Negative controls

Common tests mechanically prove:

- policy denial occurs before precondition capture, target resolution and dispatch;
- MCP/remote/model ingress cannot bypass policy;
- runtime profile widening is rejected before targeting;
- missing or stale confirmation does not reach targeting;
- user interaction after target resolution preempts before dispatch;
- subject, policy, workflow and capability changes fail closed before dispatch;
- stale workflow authority binding fails before precondition capture;
- platform callback success plus inconclusive verification yields `UNKNOWN`, never `APPLIED`;
- UNKNOWN verifier receipts block dependent completion and idempotent retry;
- ambiguous targets terminate before dispatch;
- convergence source identity has an exact selected-head/tree negative control.

## Evidence ceiling

The Stage-4 source/CI claim is limited to common/KMP runtime ordering, exact contract integration and deterministic fixture semantics. It does not claim:

- production AccessibilityService declaration, enablement or liveness;
- Shizuku/root/shell authority;
- arbitrary third-party application behavior;
- physical-device behavior or irreversible side effects;
- Google Play/accessibility-tool eligibility;
- merge, signing, release or production rollout.
