# ADR-0023: Bounded request-scoped SSE responses

- Status: proposed
- Issue: #54
- Depends on: #29 (portable bridge), #33 (listener)
- Dependency delta: none

## Context

The bridge required `Accept` to contain both `application/json` and `text/event-stream`, but always
answered with JSON. A client whose MCP implementation expects an event stream had no admitted path,
and there was no framing, budget, or cancellation contract to reason about if one were added.

## Decision

Add exactly one new response mode: a **request-scoped** event stream that belongs to one POST and
ends with it.

```text
JSON_SINGLE_RESPONSE        default; unchanged
SSE_REQUEST_SCOPED_RESPONSE opt-in per endpoint; one stream per POST; Connection: close
```

Explicitly **not** added: a standing `GET` event stream, a protocol-level `Mcp-Session-Id`, server
push outside a request, or resumability. The existing rejection of session identifiers stands.

### Negotiation is ordinary content negotiation

Both media types are already mandatory in `Accept`, so presence cannot discriminate. Preference
does: the SSE mode is chosen only when `q(text/event-stream) > q(application/json)` **and** the
endpoint enables it. Equal weights, absent weights, and a JSON-preferring client all keep JSON.
The mode is never read from the JSON-RPC body.

### Budgeting is by construction

The full event list is materialised and measured against `maxResponseBodyBytes` and `maxSseEvents`
*before* the first byte is written. A stream that would exceed either budget is refused with
`SSE_BUDGET_EXCEEDED` rather than started and truncated — a truncated event stream is
indistinguishable from a disconnect, and that ambiguity is exactly what a caller must not have.

Because nothing is produced lazily, a stalled consumer cannot make the producer accumulate state:
the backpressure ceiling is the response budget, enforced up front.

### Disconnection

The listener writes and flushes one event at a time. A write failure means the consumer is gone, so
the remaining events are abandoned; nothing is buffered, retried, or resumed. A cancellation before
the response is produced still yields the existing `CANCELLED_OR_TIMED_OUT` receipt with an
`UNKNOWN` side-effect state when the gateway had already been invoked.

## Invariants

### `INV-MCP-SSE-001` — the stream cannot outlive the request

- Enforcement: no GET route, `Connection: close`, and the events are written inside the exchange.

### `INV-MCP-SSE-002` — SSE is opt-in twice

- Enforcement: the endpoint must enable it *and* the client must rank it higher.
- Negative control: an SSE-preferring client against a disabled endpoint receives JSON.

### `INV-MCP-SSE-003` — a budget failure is a refusal, not a truncation

- Enforcement: budgets are checked on the materialised list before any write.
- Negative control: a 16-byte response budget yields HTTP 500 `SSE_BUDGET_EXCEEDED` with no events.

### `INV-MCP-SSE-004` — framing is exact

- Enforcement: `id:`, `event:`, one `data:` line per line of payload, terminating blank line; a
  carriage return in the payload is rejected at construction.

## Verification

Positive controls: SSE chosen only when preferred and enabled; exactly one terminating `message`
event carrying the JSON-RPC response; correct framing including multi-line payloads; notifications
still answered with `202` and no body.

Negative controls: endpoint disabled; equal preference; JSON preferred; no q-values; response
budget exceeded.

## Evidence boundary

```yaml
request_scoped_sse_response: PASS_OR_FAIL
sse_backpressure_and_budgeting: PASS_OR_FAIL
sse_framing: PASS_OR_FAIL
sse_disconnect_cancellation: PARTIAL
unbounded_get_event_stream: DENIED_BY_ARCHITECTURE
sse_resumability_or_last_event_id: NOT_IMPLEMENTED
```

`sse_disconnect_cancellation` is partial and deliberately labelled so: the write loop abandons the
remainder of a stream when the consumer disappears, which is what this mode can observe. Cancelling
in-flight gateway work on disconnect would require the gateway call itself to be tied to the
socket's liveness, which this slice does not do.
