# ADR-0006 — Typed native capability contracts

- Status: Accepted for contract implementation
- Issue: #10
- Branch: `feat/native-capability-contracts`
- Parent: `docs/agent-integration-stack-index`
- Evidence level: deterministic common-code contract and tests only

## Context

The source architecture evolves from an Observer into a Toolmaker that can expose camera, location, device state, and deep-link operations. It also requires Human-in-the-loop interception for sensitive actions, dynamic mapping from operating-system permissions to available tools, and local policy as the final authority.

The existing runtime already has a generic `CapabilityRegistry`, `AgentAction`, and `LocalDispatcher`. It does not yet have typed native requests/results or a platform-neutral contract that prevents an unvalidated request from being handed directly to Android or iOS APIs.

## Decision

Add a common-code Toolmaker boundary with five independent concepts:

1. `NativeToolDescriptor` describes one capability, its operation, permissions, maximum risk, and audit category.
2. `NativeToolRequest` and `NativeToolResult` are sealed, serializable request/result families. Raw model text is not an execution contract.
3. `NativeToolPolicySnapshot` carries the current enabled set, granted permissions, platform availability, and deep-link scheme allowlist.
4. `NativeCapabilityRegistry.evaluate` is the only admission function. It fails closed on unknown, disabled, mismatched, unavailable, unpermitted, invalid, or over-risk requests.
5. `NativeToolExecutor` accepts only an `AdmittedNativeToolCall`, keeping platform execution behind a successful policy decision. Medium, high, and destructive requests still require the existing dispatcher/HITL lane before any future executor is called.

Discovery is read-only. Listing descriptors cannot enable a capability or change policy.

## State machine

```text
REQUESTED
├── capability absent                 -> DENIED_UNKNOWN
├── operation mismatch               -> DENIED_OPERATION_MISMATCH
├── disabled                         -> DENIED_DISABLED
├── platform availability unknown    -> DENIED_AVAILABILITY_UNKNOWN
├── unsupported                      -> DENIED_UNSUPPORTED
├── temporarily unavailable          -> DENIED_TEMPORARILY_UNAVAILABLE
├── invalid typed input              -> DENIED_INVALID_INPUT
├── deep-link scheme not allowlisted -> DENIED_SCHEME
├── permission missing               -> DENIED_PERMISSION
├── risk above descriptor ceiling    -> DENIED_RISK
├── READ_ONLY / LOW                   -> READY
└── MEDIUM / HIGH / DESTRUCTIVE       -> REQUIRES_CONFIRMATION
```

`READY` and `REQUIRES_CONFIRMATION` are admission results, not proof of platform execution. A future executor owns `EXECUTING -> SUCCEEDED | FAILED | CANCELLED | TIMED_OUT`.

## Permission rules

- Camera capture requires `CAMERA`.
- Approximate location requires `LOCATION_APPROXIMATE`.
- Precise location requires `LOCATION_PRECISE`; approximate permission is not silently upgraded.
- Device-state reads expose only bounded, non-secret fields and require no permission in the contract baseline.
- Deep links require an explicit valid URI scheme and a repository/user allowlist entry.

Platform implementations may impose stricter requirements. They may not weaken these common rules.

## Invariants

- `INV-TOOL-001`: every tool has a unique descriptor and non-empty audit category.
- `INV-TOOL-002`: descriptor discovery never changes enablement or permission state.
- `INV-TOOL-003`: common code contains no Android/iOS API invocation.
- `INV-TOOL-004`: request operation and capability descriptor must agree.
- `INV-TOOL-005`: unknown platform availability is denied rather than assumed available.
- `INV-TOOL-006`: risk may be narrowed by policy but never exceed the descriptor ceiling.
- `INV-TOOL-007`: state-changing native actions remain behind dispatcher/HITL.
- `INV-TOOL-008`: deep-link execution is scheme-allowlisted and explicit.

## Negative controls

The common test suite must turn red when any of these guards is removed:

- unknown capability denial;
- deny-by-default enablement;
- availability-unknown denial;
- permission checks, including precise-location separation;
- operation/descriptor matching;
- maximum-risk ceiling;
- deep-link scheme allowlist;
- descriptor audit category and unique ID;
- typed request/result serialization.

## Non-goals

This ADR does not implement:

- Android or iOS `actual` adapters;
- camera/location permission prompts;
- a deep-link launcher;
- App Attest or Play Integrity;
- physical-device behavior;
- MCP tool registration or discovery mutation;
- execution, cancellation, timeout, or audit persistence.

Those remain separate Stack slices. Passing common tests proves only the portable Toolmaker contract.
