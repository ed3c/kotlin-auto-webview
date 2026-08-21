# Local Handoff boundary

```text
LOCAL_HANDOFF_EXECUTION_QUEUE = ABSENT
reason = EXTERNAL_CAPABILITIES_AND_CONCRETE_COMMANDS_NOT_YET_BOUND
```

A prose plan or valid schema is not an executable queue.

## Queue creation prerequisites

Every item needs:

```text
one exact active item
argv as an array
cwd
timeout
repository/head/tree/artifact
external capability ID
account/device/carrier class
credential reference outside repository
preconditions
expected receipt path/schema
negative control
cleanup command/receipt
blocked successor
Human-owned transitions
```

No placeholder, mutable URL, title, account email, file name, device serial, hidden local path, or chat context may satisfy an identity field.

## Expected sequence

```text
1. select exact deterministic parent and sync/read-only checkout
2. bind external capability without printing its secret
3. run disclosure/precondition check
4. run one bounded lane operation
5. read back provider/device/domain result
6. validate exact receipt and cleanup
7. update only that lane
8. independent Shadow review
9. unblock next item or preserve stable non-PASS
```

## Parallelism

L2, L3, L4, L5, and L6 may be prepared in parallel because their path leases and external authorities are separate. Only one operation per account/device/receipt subject may be active. L7 starts after a user-facing live vertical slice. #171 is the sole denominator convergence owner.

## Never auto-advance

```text
credential creation or scope approval
Google/GitHub account selection
private repository/file access
organization/DLP approval
Bettor/provider deployment or budget
physical-device trust/unlock/signing
user recruitment/consent/compensation
legal/store terms
merge/release/production promotion
```

## Queue emission rule

Create `LOCAL_HANDOFF_EXECUTION_QUEUE.json` only after all selected commands and external capability references are concrete and pass a mutation test that rejects placeholders. Until then, keep the queue absent and record the reason above.
