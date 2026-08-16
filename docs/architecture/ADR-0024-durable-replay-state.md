# ADR-0024: Durable and multi-node semantic replay state

- Status: proposed; multi-node claim **falsified and corrected** (see Refutation)
- Issue: #53; correction: #57
- Depends on: #43 (semantic action replay identity), #41, #29
- Dependency delta: none

## Refutation, 2026-08-16 (#57)

The multi-node claim recorded below as `IMPLEMENTED_NOT_EXERCISED` was **wrong, not merely
unproven**. The first cross-process test showed four contenders all admitting the same digest.

The mechanism was defeated by POSIX record-lock ownership. `readLiveEntries` read the journal with
`Files.readAllLines(journal)`, which opens a second descriptor for the same file and closes it —
and on POSIX, closing *any* descriptor for a file releases *every* lock the process holds on it.
The lock was therefore dropped microseconds after being taken, inside the very block that appeared
to hold it.

Isolated, single-variable evidence — identical program, identical file, only a second descriptor
opened and closed inside the critical section:

```text
without the second descriptor   lock waits: 4 ms, 1218 ms, 2431 ms   (serialised)
with the second descriptor      lock waits: 23 ms, 25 ms, 29 ms      (not serialised)
```

**Why it was believed.** The code named the mechanism correctly, held the lock across the whole
read-decide-append sequence, and every existing test passed — because they were single-process,
where the in-process `Mutex` provides the exclusion the file lock was supposed to provide. Static
reading could not distinguish "holds a lock" from "holds a lock that something else silently
releases", and no test could either until a second process existed. The ADR's own caveat named the
missing evidence and still understated the risk: it treated the mechanism as correct-but-unwitnessed
rather than as an unverified claim that could be false.

**Correction.** Every journal access inside the locked block now goes through the same
`RandomAccessFile` that holds the lock. The fix is a rule about descriptors, not a change of
algorithm, so the rule is recorded next to the code — a later tidy-up back to `Files.readAllLines`
would silently reintroduce it.

## Context

#43 gave state-changing MCP proposals a canonical semantic identity that is independent of the
JSON-RPC request id, formatting, and key order. `BoundedMcpHttpReplayGuard` then suppressed
duplicates — but only inside one process's memory. A listener restart, an application restart, or a
second admitted bridge node erased or bypassed that suppression, and a replayed
`browser_propose_navigation` became indistinguishable from a first attempt.

## Decision

Add `DurableMcpHttpReplayGuard`: the same digests from #43, kept in one append-only journal, with
every decision taken while holding an exclusive OS file lock on that journal.

```text
identity      reuses semanticActionReplayKey from #43 unchanged
storage       one line per admitted digest: "<sha256-hex> <expiresAtEpochMs>"
coordination  FileChannel.lock() held across read -> decide -> append -> fsync
compaction    in place, under the same lock, above a 1 MiB threshold
failure       FAIL_CLOSED by default; DEGRADE_TO_MEMORY only when explicitly configured
```

The lock — not the read — is what makes two nodes agree: a competing node blocks until the decision
has been durably appended, so the same digest cannot be admitted twice.

### Why in-place compaction

Rewriting through a temporary file and an atomic rename would leave the held lock attached to an
unlinked inode, and a competing node would then be locking a different file. Compaction therefore
truncates and rewrites the same file while the lock is held.

### Why the default is fail-closed

A store that cannot answer is not evidence that a proposal is new. `FAIL_CLOSED` reports capacity
exhaustion, which the bridge already renders as `503`. `DEGRADE_TO_MEMORY` exists for a deployment
that would rather keep availability, and it is documented as reducing the guarantee back to the
in-memory one.

## Invariants

### `INV-MCP-REPLAY-001` — suppression survives a restart

- Statement: a digest admitted before a restart is a duplicate after it.
- Negative control: a fresh guard instance over the same journal rejects the same digest.

### `INV-MCP-REPLAY-002` — the journal is opaque

- Statement: the journal contains only digests and expiries.
- Negative control: subject, credential epoch, authority, tool name, and URL are all absent from
  the written file.

### `INV-MCP-REPLAY-003` — capacity pressure never releases a live digest

- Statement: at capacity the guard refuses new digests instead of evicting live ones.
- Negative control: after `CAPACITY_EXHAUSTED`, the first admitted digest is still a duplicate.

### `INV-MCP-REPLAY-004` — a damaged record is not a live record

- Statement: a truncated or malformed line is dropped, never treated as suppression.
- Rationale: the opposite failure would silently block legitimate proposals forever.

### `INV-MCP-REPLAY-005` — expiry is bounded

- Statement: suppression ends exactly at `admittedAt + window`.

## Verification

Positive controls: suppression across a new guard instance; independence of distinct digests;
expiry at the window boundary; opaque journal contents; bounded capacity; explicit degradation.

Negative controls: unusable store under both failure modes; malformed and truncated journal lines.

## Evidence boundary

```yaml
replay_durability_across_restart: PASS_OR_FAIL
replay_store_failure_policy: PASS_OR_FAIL
journal_opacity: PASS_OR_FAIL
multi_node_replay_coordination: IMPLEMENTED_NOT_EXERCISED
cross_host_coordination: DENIED_BY_ARCHITECTURE
```

`multi_node_replay_coordination` is implemented through the exclusive file lock but is **not**
exercised by a test: two `FileChannel` locks on one file inside a single JVM raise
`OverlappingFileLockException` rather than blocking, so genuine evidence needs a second process.
That test is not written here, and the claim must not be read as proven.

Coordination is also bounded to nodes that share one filesystem with working advisory locks. It is
deliberately not a distributed consensus mechanism, and network filesystems with unreliable locking
are outside the admitted deployment.
