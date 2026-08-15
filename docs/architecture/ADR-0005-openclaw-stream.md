# ADR-0005 — Typed authenticated private-edge OpenClaw stream contract

- Status: Accepted for portable stream admission
- Issue: #9
- Branch: `feat/openclaw-stream-contract`
- Parent: `build/runtime-dependency-admission`
- Evidence level: deterministic common-code pairing, ordering, replay, privacy, cancellation, and buffer tests

## Context

The source architecture positions KMP as an immediate L1 sensory buffer and a private OpenClaw node as a deeper L2 memory source. It proposes persistent streaming of semantically relevant cache material back to the current mobile page. A transport connection alone cannot authorize remote material to enter projection or action-proposal lanes.

The first vertical slice established peer/origin/key checks, monotonic sequence checks, expiry, replay tokens, context matching, bounded buffering, and a transport pump. Shadow review found additional reachable invalid states:

- raw string payloads could carry unbounded or sensitive data;
- merely increasing sequence numbers allowed gaps and silent missing chunks;
- key changes inside one session epoch were ambiguous;
- disconnected sessions lacked an explicit terminal state;
- stream identities, replay history, and cancelled streams could grow or reset without a declared rule;
- remote action proposals lacked a capability allowlist and risk ceiling.

## Decision

Use one typed, epoch-bound admission contract. The common layer accepts only:

```text
ProjectionCandidatePayload
TypedActionProposalPayload
HeartbeatPayload
```

A remote payload remains evidence or a proposal. It never mutates the capability registry, dispatcher, WebView, native APIs, or persistent cache directly.

## Pairing state machine

```text
DISCONNECTED
├── valid peer/origin/key/epoch/time -> PAIRED
├── invalid pairing                 -> DISCONNECTED
└── close                           -> CLOSED

PAIRED
├── disconnect                      -> DISCONNECTED
├── same peer/origin/epoch/key      -> PAIRED (reconnect; sequence/cancel state preserved)
├── higher epoch + allowed key      -> PAIRED (key rotation; stream state reset)
├── same/lower epoch with key drift -> pairing rejected
├── pairing expiry                  -> DISCONNECTED
└── close                           -> CLOSED

CLOSED is terminal.
```

A new key is accepted only with a strictly higher `sessionEpoch`. This makes key rotation and replay-state reset one explicit transition.

## Chunk admission state machine

```text
RECEIVED
→ SESSION_OPEN
→ PAIRED
→ PEER / ORIGIN / KEY / EPOCH MATCH
→ IDENTIFIER AND TIME BOUNDS
→ DETERMINISTIC REPLAY TOKEN
→ CONTEXT RULE
→ TYPED PAYLOAD / PRIVACY / CAPABILITY / RISK POLICY
→ STREAM NOT CANCELLED
→ TRACKED-STREAM BUDGET
→ CONTIGUOUS SEQUENCE
→ BUFFER CAPACITY
→ ACCEPTED
```

Every failed check produces one stable `StreamRejectionReason`; no state is advanced before all checks pass.

## Ordering and replay

A stream begins at sequence `0`. Each next chunk must be exactly `last + 1`.

```text
sequence <= last -> OLD_SEQUENCE
sequence > last + 1 -> SEQUENCE_GAP
```

`replayToken` is a deterministic identity over:

```text
peer ID | session epoch | stream ID | sequence | payload kind
```

This portable token is not a cryptographic MAC. It prevents token reuse across a different declared subject and gives a future authenticated transport one exact identity to sign. TLS/Noise/mTLS proof remains outside this common contract.

## Context rule

Projection and typed-action payloads require a non-empty `contextFingerprint` that exactly matches the active local page. Heartbeats must be context-free.

A remote similarity score, cache ID, or action risk cannot override context mismatch.

## Typed payload rules

### Projection candidate

May contain only:

```text
cache ID
bounded sanitized summary
0..1 relevance hint
bounded tags
```

It contains no DOM selector, coordinate, full page body, credential, or execution authority.

### Typed action proposal

Contains an existing `AgentAction`, but admission additionally requires:

- capability ID in `allowedRemoteCapabilityIds`;
- risk at or below `maximumRemoteActionRisk`;
- bounded parameter count, keys, and values;
- no sensitive parameter key/value or secret-like description.

Acceptance means only “eligible to enter the local proposal pipeline.” Capability policy, dispatcher state, HITL, page freshness, and the bounded executor still decide whether anything can happen.

### Heartbeat

Contains a bounded nonce and send time. It cannot carry a page fingerprint or action.

## Resource envelope

`PairingPolicy` and the session define explicit bounds for:

```text
payload characters
tracked streams
chunk age
chunk lifetime
buffered chunks
tag count and length
action parameter count and length
identifier/token lengths
```

When full, the buffer rejects the new chunk. It never silently evicts already admitted evidence. A cancelled stream is remembered for the current epoch and its buffered chunks are removed.

## Privacy boundary

Admission rejects:

- sensitive action parameter keys such as password, token, authorization, cookie, payment, or session;
- bearer/token/password assignments;
- payment-card-like number sequences;
- private-key blocks;
- over-budget payloads or control characters.

Portable rejection results contain enum reasons only. They never contain the payload, credential, key material, endpoint secrets, or raw exception text.

## Transport pump

`OpenClawStreamPump` owns only transport lifecycle:

```text
connect
→ receive one typed chunk at a time
→ session.admit
→ report admission
→ close in finally
```

Cancellation remains cancellation, and the transport is still closed. A successful `connect()` is not paired identity.

## Reconnect policy

`ReconnectPolicy` provides deterministic bounded exponential delay with bounded jitter and maximum attempts. It does not perform retries by itself and grants no pairing authority.

## Invariants

- `INV-EDGE-001`: active peer, exact origin, key ID, and session epoch must match every chunk.
- `INV-EDGE-002`: key rotation requires a strictly higher epoch.
- `INV-EDGE-003`: sequence is contiguous per stream; no silent gap is accepted.
- `INV-EDGE-004`: replay identity is bound to peer/epoch/stream/sequence/payload kind.
- `INV-EDGE-005`: non-heartbeat data requires the exact current context fingerprint.
- `INV-EDGE-006`: remote payloads are typed, bounded, and secret-filtered.
- `INV-EDGE-007`: remote actions are proposals limited by capability allowlist and risk ceiling.
- `INV-EDGE-008`: tracked streams and buffer growth are bounded.
- `INV-EDGE-009`: disconnect clears volatile buffer but same-epoch reconnect preserves ordering and cancellation state.
- `INV-EDGE-010`: higher-epoch rotation resets stream state; `CLOSED` is terminal.
- `INV-EDGE-011`: cancellation is propagated and transport close runs in `finally`.
- `INV-EDGE-012`: no remote chunk grants browser/native execution authority.

## Negative controls

The common tests must turn red if a mutation:

- accepts an anonymous, wrong-origin, wrong-key, wrong-epoch, or expired peer/chunk;
- changes keys without increasing epoch;
- accepts a sequence gap, duplicate, or cross-subject replay token;
- permits non-heartbeat data without an exact page fingerprint;
- accepts raw/unbounded/sensitive projection or action payloads;
- admits a remote capability or risk not explicitly allowed;
- silently evicts an admitted chunk on overflow;
- forgets cancellation/sequence state during same-epoch reconnect;
- fails to reset state on higher-epoch rotation;
- allows pairing after `CLOSED`;
- swallows coroutine cancellation or leaves the transport open;
- interprets accepted action proposal as capability/HITL/executor authority.

## Evidence boundary

A green exact-head matrix can prove portable pairing, typed admission, ordering, replay identity, expiry, context pruning, payload/privacy policy, bounded resources, reconnect state, cancellation, and serialization.

It cannot prove:

- TLS, Noise, mTLS, certificate pinning, or cryptographic possession of a private key;
- Secure Enclave/Keystore custody or key rotation on physical devices;
- real Ktor WebSocket/SSE behavior, LAN discovery, NAT traversal, network jitter, or reconnect timing;
- Android/iOS-to-private-node pairing;
- OpenClaw server deployment, cache persistence, projection rendering, store review, merge, security/legal acceptance, or production readiness.

Those remain `NOT_IMPLEMENTED`, `NOT_EXERCISED`, or `EXTERNAL_AUTHORITY_REQUIRED` until their owning slices emit subject-bound evidence.
