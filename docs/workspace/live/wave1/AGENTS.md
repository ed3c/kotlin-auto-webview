# Wave-1 live implementation Agent instructions

These instructions scope `docs/workspace/live/wave1/**` and the first
implementation wave #165–#168. Repository-root and nearer implementation
`AGENTS.md` files remain authoritative.

## Mandatory read order

1. root `AGENTS.md`;
2. `docs/workspace/AGENTS.md`;
3. `docs/workspace/live/AGENTS.md`;
4. `docs/evidence/workspace/README.md`;
5. this `README.md`;
6. `contract-lock.json`;
7. the exact owning Issue: #165, #166, #167, or #168;
8. every exact source file and external contract named by the selected lane;
9. current PR/head/check state immediately before writing.

Prior conversation text cannot replace any item in this route.

## Tech Lead role

The Tech Lead:

- selects exactly one lane and one Git parent;
- binds the exact commit, tree, blob, symbol, issue, and path lease;
- separates code-ready, external-capability-ready, and live-evidence-ready;
- preserves missing work as explicit state;
- defines positive, negative, timeout, cancellation, cleanup, and disclosure
  oracles;
- emits the next handoff without granting merge/release authority.

## Shadow Architect MONITOR

Monitor these deltas continuously:

```text
IDENTITY_DELTA
AUTHORITY_DELTA
CREDENTIAL_DELTA
PRIVATE_EGRESS_DELTA
LIVE_EVIDENCE_DELTA
CROSS_REPO_CONTRACT_DELTA
RECEIPT_DELTA
CLEANUP_DELTA
```

### L3 stop conditions

Stop the lane when any of the following occurs:

- a mutable branch, path, title, URL, request ID, or chat statement replaces an
  exact commit/tree/blob/subject identity;
- credential, token, cookie, account email, private file/repository locator, or
  customer data enters source, logs, artifacts, or public receipts;
- an existing transport or authority is duplicated rather than consumed;
- fixture, schema, CI, ACK, simulator, or issue state is promoted to live
  evidence;
- KAW writes Bettor Gate/ledger state or rewrites a domain verdict;
- Google access is treated as content ownership or publication permission;
- a receipt reference is treated as receipt content;
- one lane's receipt is reused for another lane, authority, subject, account,
  device, or environment;
- cleanup/residue is absent or unknown but PASS is claimed.

## Writer leases

Each implementation issue owns only the paths declared in its Issue and in
`contract-lock.json`. The Wave-1 lock branch owns only:

```text
docs/workspace/live/wave1/**
schemas/workspace/live-wave1*.json
scripts/ci/check-workspace-live-wave1.py
tests/docs/workspace/live/wave1/**
.github/workflows/workspace-live-wave1.yml
```

Do not edit root indexes, W0–W6 implementation bytes, LICENSE, NOTICE, or another
lane's path lease.

## Worker result packet

Every Worker must return:

```yaml
atom:
issue:
repository:
base_commit:
base_tree:
head_commit:
head_tree:
consumed_blobs:
changed_paths:
commands_and_exits:
positive_receipts:
negative_or_mutation_receipts:
external_capabilities:
live_subject:
maximum_claim:
cleanup:
remaining_non_pass:
next_owner:
```

`live_subject` remains `NOT_EXERCISED` until an exact external capability and
literal operation receipt exist.

## Human and external authority

The Agent must not create, reveal, or approve:

- GitHub App/token installation or private-repository scope;
- Google account selection, OAuth consent, scopes, organization policy, or test
  document ownership;
- Bettor deployment, provider spend, or Worker runtime admission;
- domain private-source access or semantic dispute resolution;
- physical-device trust;
- user recruitment/consent;
- merge, release, store, legal, or production decisions.
