# ADR-0026: JWKS retrieval, bounded caching, and key retirement

- Status: proposed
- Issue: #59
- Depends on: #48 (verification profiles), #47 (transport)
- Dependency delta: none

## Context

#48 verified access tokens but deliberately left key distribution behind the `McpJwtKeySource`
interface, so verification logic would not own network policy. The cost of leaving it there is that
every deployment re-solves retrieval, caching, refresh, and failure semantics — and those are
precisely the places where an implementation that looks reasonable stops being safe.

Three failure modes matter more than the happy path:

```text
an unknown `kid` triggering a fetch      -> a caller chooses when we call the issuer
a failed fetch falling back to a cache   -> "cannot check" quietly becomes "accept"
a retired key still verifying            -> revocation never reaches this process
```

## Decision

`McpJwksKeySource` retrieves the issuer's JWKS over HTTPS and answers `publicKey(kid, algorithm)`
from a bounded cache.

```text
transport    HTTPS only; a plaintext endpoint is refused at construction
budget       document size ceiling, request timeout, no redirects
freshness    keys serve only while inside the admitted cache lifetime
refresh      at most one attempt per minimum refresh interval, whatever the reason
failure      stale cache + unreachable issuer = reject, never accept
retirement   a key absent from the current document stops verifying after a refresh
duplicates   first `kid` declaration wins
```

### Why the refresh is rate-bounded rather than on-demand

A cache miss is the obvious moment to refetch, and that is exactly the problem: the `kid` comes
from the token, so the caller chooses it. Refetching on every miss would let an attacker send
invented key ids and drive one outbound request per attempt against the issuer, from an endpoint
that has not authenticated anybody yet. The cooldown applies to *all* refresh reasons, so a miss is
never cheaper for an attacker than the clock allows.

### Why a failed fetch cannot extend trust

A cached key is served while the cache is inside its admitted lifetime — that is what the lifetime
means. Once stale, an unreachable issuer produces `null`, and the verifier rejects. The alternative
(serve the last known key until the issuer returns) converts an availability problem into an
authentication decision, and does so silently.

### Why the lookup blocks

`McpJwtKeySource` is synchronous, so a refresh blocks the calling worker for at most the request
timeout. That is bounded on purpose: an unbounded wait here would be a denial-of-service surface on
the listener's small worker pool.

## Invariants

### `INV-MCP-JWKS-001` — plaintext key distribution is impossible

- Enforcement: constructor requires an `https` scheme.
- Negative control: an `http://` endpoint throws.

### `INV-MCP-JWKS-002` — an attacker-chosen `kid` cannot amplify requests

- Enforcement: one refresh attempt per `minimumRefreshIntervalMillis`, for every reason.
- Negative control: fifty invented key ids inside the cooldown produce zero extra requests, and
  exactly one more once the cooldown elapses.

### `INV-MCP-JWKS-003` — expiry is a rejection, not a fallback

- Enforcement: a failed retrieval returns a cached key only while the cache is fresh.
- Negative control: with the issuer failing, a key served inside the lifetime is refused once past
  it.

### `INV-MCP-JWKS-004` — retirement propagates

- Enforcement: the cache is replaced wholesale by each successful retrieval.
- Negative control: after rotation, the withdrawn `kid` no longer resolves.

### `INV-MCP-JWKS-005` — a key is usable only for its own algorithm

- Enforcement: `McpJwkParser.publicKey` is shared with the DPoP path and requires `kty` to match
  the requested algorithm, so an RSA key cannot be handed back for `ES256`.

## Verification

Positive controls: a retrieved key verifies a real signature; a fresh cache does not refetch; an
expired cache does; a rotated key is picked up; a cached key is served during a short outage.

Negative controls: plaintext endpoint; oversized document; unreachable issuer past the lifetime;
fifty invented key ids; wrong algorithm; duplicate `kid` where an appended entry must not displace
the first.

Evidence is a real TLS endpoint with a certificate generated at test time by the JDK's own
`keytool`; nothing is committed.

## Evidence boundary

```yaml
jwks_retrieval: PASS
bounded_caching_and_refresh_rate: PASS
key_retirement: PASS
fail_closed_on_unreachable_issuer: PASS

live_authorization_server_integration: NOT_EXERCISED
ocsp_or_certificate_revocation_lists: NOT_IMPLEMENTED
jwks_endpoint_discovery_via_oidc_metadata: NOT_IMPLEMENTED
```

The endpoint URL is supplied by the host rather than discovered from OIDC metadata: discovery adds
a second network dependency and its own trust decisions, and nothing yet needs it. Certificate
revocation for the TLS connection itself remains delegated to the JDK trust manager the host
configures, unchanged from ADR-0022.
