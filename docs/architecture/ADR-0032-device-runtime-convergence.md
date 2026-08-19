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
2. resolve the canonical action against the capability catalog, including exact capability ID, action kind and audit category;
3. enforce the compiled distribution profile before capability policy;
4. evaluate capability/risk/permission/verifier/confirmation policy;
5. validate exact human confirmation where required;
6. validate and freeze the exact workflow revision, digest, node, risk, confirmation and idempotency subject;
7. read Local Dispatcher/runtime authority **before observation**; user interaction, screen lock, service/platform loss, subject/profile/policy/workflow movement or capability revocation cancels with `NONE` before precondition capture or target resolution;
8. capture deterministic precondition evidence;
9. resolve one exact short-lived target token or fail on zero/many/stale;
10. re-read authority immediately before dispatch and revalidate user priority, screen/platform state, profile, policy version, workflow revision, subject, capability enablement, proposal/confirmation time and target-token identity;
11. create a typed `DeviceActionCommand` and an exact dispatch-admission binding;
12. treat the platform callback as dispatch evidence only;
13. run the registered deterministic verifier and commit `NONE | APPLIED | UNKNOWN` through the effect ledger;
14. emit a workflow receipt only from verifier/postcondition evidence; `UNKNOWN` therefore blocks dependents and retry in the K1 admission/recovery contracts;
15. commit a sanitized audit record containing identities and effect state, never raw payload/user text.

The existing `LocalDispatcher` is mandatory rather than advisory: `DeviceAutomationRuntime` accepts a `LocalDispatcherDeviceRuntimeAuthoritySource`, not an arbitrary authority source. The wrapper composes the existing dispatcher with a delegate that supplies environment/subject/profile/policy facts. `READY` admits an unoccupied runtime boundary, `EXECUTING` preserves a device action already admitted through the existing confirmation flow, `OBSERVING_USER` asserts user preemption, and `PROPOSING`, `WAITING_FOR_CONFIRMATION`, `SUSPENDED`, or any other non-admitted mode removes platform availability. Common tests drive the real `LocalDispatcher.dispatch()` transitions so X1 does not replace it with a parallel dispatcher state machine.

The two authority reads are intentionally separate. The first prevents automation from observing/targeting while authority is already stale or human-owned. The second catches changes that occur after observation/target resolution but before dispatch. Platform adapters must still fail closed if cancellation arrives during dispatch before an effect begins; `NotDispatched` and `FailureBeforeEffect` preserve `NONE`, while an uncertain post-dispatch failure is `UNKNOWN` and requires reconciliation.

## Effect truth

A platform callback, Accessibility API boolean, intent launch or future privileged Binder connection is never proof of application. The effect ledger enters `VERIFYING` after dispatch and can reach `TERMINAL_APPLIED` only with a matching `VerificationVerdictCode.APPLIED`. Inconclusive, contradictory, stale, lost-observer and unknown-plan cases terminate as `UNKNOWN` and require reconciliation.

Pre-dispatch invalidation does not create a false terminal effect record: the first authority gate occurs before observation; precondition evidence is then held in memory until the final authority gate passes. Only after that second gate is the effect record opened and the precondition/dispatch-admission sequence committed. User/package/window/generation/screen/platform/profile/policy/workflow/capability/token invalidation therefore terminates with `NONE` before dispatch. After possible dispatch, uncertainty is never rewritten to `NONE`.

## Negative controls

Common tests mechanically prove:

- policy denial occurs before precondition capture, target resolution and dispatch;
- capability audit-category drift is rejected during canonical ownership binding;
- MCP/remote/model ingress cannot bypass policy;
- runtime profile widening is rejected before targeting;
- missing or expired confirmation does not reach observation/targeting;
- workflow risk drift is rejected before precondition capture;
- user interaction already active preempts before observation or target resolution;
- user interaction introduced after resolution is caught by the final gate before dispatch;
- package/window/generation, screen lock, service/platform loss, profile, policy, workflow and capability movement fail closed before observation when already present;
- target-token expiry and prebound token-digest disagreement fail closed after resolution but before dispatch;
- stale workflow authority binding fails before precondition capture;
- the real Local Dispatcher keeps `WAITING_FOR_CONFIRMATION`, `PROPOSING`, `SUSPENDED`, and `OBSERVING_USER` fail closed while confirmed `EXECUTING` is admitted;
- an admitted platform operation that reports `NotDispatched` terminates `NONE` without a workflow completion receipt;
- platform callback success plus inconclusive verification yields `UNKNOWN`, never `APPLIED`;
- UNKNOWN verifier receipts block dependent completion and idempotent retry;
- ambiguous targets terminate before dispatch;
- convergence source identity has an exact selected-head/tree negative control.

## Android instrumentation ownership

Issue #70 deliberately excludes `.github/workflows/ci.yml`, while its acceptance criteria require an Android instrumentation lane on the exact integrated head. Therefore instrumentation is an evidence dependency, not a reason to widen the X1 source lease. After the source head is frozen and the full Common/Desktop/Web/Android/iOS source matrix passes, a dedicated evidence child must bind that exact source head/tree, add only test/CI harness bytes, run managed-emulator runtime fixtures, emit an exact-head receipt, and preserve `NOT_EXERCISED` for live AccessibilityService/device-side effects. Its bytes are evidence and must not be silently imported into the product source head.

## Evidence ceiling

The Stage-4 source/CI claim is limited to common/KMP runtime ordering, exact contract integration and deterministic fixture semantics. A later exact-head Android instrumentation receipt may raise only the test lane to managed-emulator fixture evidence. Neither claim proves:

- production AccessibilityService declaration, enablement or liveness;
- Shizuku/root/shell authority;
- arbitrary third-party application behavior;
- physical-device behavior or irreversible side effects;
- Google Play/accessibility-tool eligibility;
- merge, signing, release or production rollout.
