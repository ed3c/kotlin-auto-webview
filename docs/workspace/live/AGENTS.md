# AGENTS — Workspace Live Evidence

This file governs `docs/workspace/live/**`, Phase-1 preflight scripts/tests/schema, and the task packets for #164–#172.

## Mandatory read order

1. root `AGENTS.md`
2. root `README.md`, `CONTEXT.md`, `ARCHITECTURE.md` when present
3. `docs/workspace/AGENTS.md`
4. `docs/workspace/AUTHORITY_AND_IDENTITY.md`
5. `docs/workspace/STATE_MACHINES_AND_DAG.md`
6. `docs/evidence/workspace/README.md`
7. `receipts/workspace/federation-evidence.json`
8. this file and `docs/workspace/live/README.md`
9. `implementation-preflight.json`
10. exact owning Issue, parent PR/head/check, nearest source contract, and cross-repository issue

Prior conversation is never an exact subject.

## Roles

### Tech Lead

- selects one exact parent subject and one write lease;
- separates Git-parent, start, completion, evidence, and external-authority edges;
- freezes public/private and evidence ceilings;
- rejects placeholder credentials, file IDs, device subjects, commands, or receipts;
- emits the next-owner handoff.

### Shadow Architect Monitor

At architecture choice, first external call, first green, evidence publication, and PR update, inspect:

```text
AUTHORITY_DELTA
PRIVATE_EGRESS_DELTA
IDENTITY_DELTA
EVIDENCE_PROMOTION_DELTA
PROVIDER_ACCOUNT_DELTA
DEVICE_DELTA
USER_OUTCOME_DELTA
CLEANUP_DELTA
```

Verdicts:

- L0: observation
- L1: bounded repair
- L2: implementation block until explicit owner/receipt exists
- L3: stop; authority/privacy/evidence law violated

## Writer leases

One Worker writes one atom's paths only. Shared evidence denominator paths belong to #171 after live receipts exist. This preflight branch owns only #172 paths.

Implementation branches must use the Git parents declared in `implementation-preflight.json`; this docs branch is not their parent.

## Required Worker packet

Every new Session receives:

```text
ROLE + issue/atom
exact repository/base/head/tree/schema/receipt subjects
objective + non-goals
mandatory read order
Git parent
start dependencies
completion dependencies
evidence dependencies
write/read-only/forbidden paths
authority/data/evidence ceiling
positive oracle
planted negative controls
timeouts/retry/cleanup/residue
stable blocked/failure states
Human/external operations
output receipt and next owner
```

## Stable results

```text
PASS
FAIL
BLOCKED
NOT_EXERCISED
ABSENT
EXTERNAL_AUTHORITY_REQUIRED
STALE_SUBJECT
DENIED_BY_POLICY
DISCLOSURE_FAIL
CLEANUP_FAIL
```

## L3 stops

Stop immediately if any path:

- stores or logs credentials, tokens, cookies, private keys, private Drive/repo/customer locators, raw private source, or consented user data in public artifacts;
- runs Google authorization inside WebView;
- lets a title/URL/correlation ID become identity;
- treats connector availability as rights or destination admission;
- treats route ACK as execution or domain truth;
- reuses a receipt for another authority/subject/environment/lane;
- promotes CI/simulator/fixture to live account/device/user evidence;
- hides a failed/absent lane or shrinks L0–L7;
- writes issue/PR/Google/UI state as canonical domain verdict;
- auto-advances consent, credential, account, device, legal, store, merge, release, or production authority.

## Output contract

Each Worker returns:

```yaml
atom:
exact_parent:
exact_head:
changed_paths:
commands_and_exits:
positive_receipts:
negative_receipts:
cleanup_receipt:
maximum_claim:
remaining_lanes:
blocked_or_unknown:
human_owned_operations:
next_owner:
```

No Worker may claim merge, release, production, legal approval, platform approval, user demand, or payment without literal external evidence.
