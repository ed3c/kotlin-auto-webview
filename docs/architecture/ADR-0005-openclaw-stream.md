# ADR-0005: Authenticated private-edge OpenClaw stream

- Status: proposed
- Issue: #9
- Parent: `build/runtime-dependency-admission`
- Scope: portable authenticated stream contract and deterministic in-memory admission only

## Decision

The mobile/browser shell accepts remote OpenClaw input only through a paired-peer session. A successful socket connection is insufficient. Every `StreamChunk` must reassert peer identity, origin, key identity, sequence, expiry, replay token, and optional page-context fingerprint before it can enter the bounded local buffer.

Remote data can represent only `PROJECTION_CANDIDATE`, `TYPED_ACTION_PROPOSAL`, or `HEARTBEAT`. This contract does not execute actions and does not bypass capability policy, dispatcher state, or HITL.

## State machine

```text
DISCONNECTED
    |
    v
CONNECTED_TRANSPORT
    |
    v
PAIRING_CHECK --fail--> REJECTED
    |
    v
PAIRED
    |
    v
CHUNK_AUTH -> ORDER -> EXPIRY -> REPLAY -> CONTEXT -> BACKPRESSURE
    |                                               |
    +-------------------- fail --------------------> REJECTED
                                                    |
                                                    v
                                                ACCEPTED
                                                    |
                                                    v
                                              BOUNDED_BUFFER
```

## Invariants

1. `peerId`, `origin`, and `keyId` must match an unexpired paired peer.
2. Per-stream sequence numbers are strictly increasing.
3. Replay tokens are rejected even when reused on a different stream.
4. Future-issued and expired chunks fail closed.
5. Context-bound chunks require an exact active-context fingerprint match.
6. The local buffer has a fixed maximum and rejects overflow instead of silently dropping older admitted evidence.
7. Disconnect clears volatile buffered data and paired identity.
8. Cancellation remains cancellation; the stream pump always closes the transport in `finally`.
9. No credential or key material is emitted by this contract into logs, results, or audit receipts.
10. Admission never grants browser/native execution authority.

## Reconnect policy

Reconnect performs pairing admission again. It does not restore paired authority merely because a transport reconnected. Sequence/replay state remains session-owned to prevent a transient disconnect from automatically turning old material into new evidence.

## Backpressure

`maximumBufferedChunks` is explicit and positive. When full, the session rejects new chunks with `BUFFER_FULL`. Future Ktor adapters may add bounded channels and retry/jitter, but cannot weaken this admission rule.

## Evidence boundary

A green exact-head matrix proves common-code pairing checks, ordering, replay rejection, expiry, context pruning, bounded buffering, cancellation preservation, and serialization compatibility. It does not prove TLS, Noise, mTLS, Secure Enclave/Keystore custody, real WebSocket/SSE behavior, LAN discovery, NAT traversal, physical-device reconnect timing, private-node deployment, store review, merge, or production security.

## Negative controls

- Anonymous peers fail closed.
- Wrong origin or key identity fails closed.
- Successful transport connection cannot be relabeled as paired identity.
- Old sequence, reused replay token, expired chunk, and stale context cannot enter the local buffer.
- Remote payloads cannot call capability executors or mutate dispatcher authority from this layer.
- No endpoint, credential, certificate, token, or production key is committed in this branch.
