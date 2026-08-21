# L5 public domain-authority evidence

This lane validates one public, synthetic receipt produced by
`ed3c/truth-verify-loop` without importing its evidence or recomputing its
verdict.

## Runtime flow

```text
tracked producer selector
→ discover exactly one open producer PR
→ bind exact producer commit and tree
→ fetch receipt by exact commit
→ bind Git blob and raw-content SHA-256
→ require producer receipt + repository workflows on the same head
→ validate authority / lane / subject / policy / verdict / disclosure
→ preserve the domain verdict
→ emit a sanitized KAW L5 receipt
```

The branch name is only a discovery selector. It is not persisted as the
receipt identity. The emitted artifact records the exact commit, tree, blob,
content digest, producer PR number, and exact producer workflow run IDs.

## Authority boundary

KAW may validate and display:

```text
repository / commit / tree / blob / receipt digest
claim id / claim digest
verdict enum / closure digest / freshness
receipt environment / evidence ceiling
```

KAW must not:

```text
re-run the domain closure algorithm
upgrade or suppress the verdict
import raw source or raw evidence
turn a receipt reference into evidence content
claim user, paid, merge, release, or production outcomes
```

## Evidence ceiling

A PASS satisfies only L5 for one exact public synthetic receipt and one hosted
runner time window. L3 Google, L4 Bettor, L6 physical-device, L7 user, paid,
merge, release, and private-source lanes remain separate.
