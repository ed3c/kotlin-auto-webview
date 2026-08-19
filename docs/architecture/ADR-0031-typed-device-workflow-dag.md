# ADR-0031: Typed device workflow DAG and revision authority

## Status

Accepted for the K1 first vertical slice. Model planning quality and platform execution remain outside this evidence lane.

## Decision

A workflow is an immutable, digest-bound DAG of typed action templates, human checkpoints, and verification gates. It consumes the C1 `DeviceCapabilityCatalog`; semantic aliases never become executable workflow actions because canonical action lookup is exact and each action has one capability owner.

The validator rejects duplicate/dangling edges, cycles, excessive fan-out, profile mismatches, missing verification gates, wrong verifier identities, stale binding sources, and unadmitted type/taint flows. `PLAY_SAFE` frozen workflows must be human-authored. Model output may propose a future draft, but it cannot become a frozen `PLAY_SAFE` workflow by changing a field at runtime.

Runtime admission distinguishes start dependencies from completion dependencies. A `START` edge needs a readable predecessor receipt; a `COMPLETION` edge additionally rejects `UNKNOWN`. Resource leases block conflicting nodes while disjoint nodes remain independently startable. Authority tokens and receipts bind workflow ID + revision + digest, so any edit invalidates prior confirmations/tokens/results.

## Hard boundaries

- There is no `$step`/`$$step`, JSONPath-to-command, selector, coordinate, shell, URL, intent, or untyped map construction API.
- Bindings are field-to-field, type-exact, and carry explicit admitted taint classes.
- `UNKNOWN` cannot satisfy a completion edge and cannot be retried automatically.
- Failure does not imply compensation or rollback; those remain explicit future typed workflows.
- Workflow validation does not enable a capability, change the compiled distribution profile, replace HITL, or decide a postcondition.

## Evidence ceiling

A green common/KMP matrix proves deterministic workflow schema, canonical-action, DAG, revision, lease, taint and receipt semantics against fixtures. It does not prove model planning quality, Android execution, real device concurrency, compensation correctness, merge, release, or production readiness.
