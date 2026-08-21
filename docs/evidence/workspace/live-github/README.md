# L2 public GitHub connector evidence

This lane executes the existing production `GitHubRestMetadataSource` against one exact public subject. It does not add another GitHub client.

## Exact subject

```text
repository  ed3c/kotlin-auto-webview / 1334777764
Issue       #165 / 5206660666
PR          #177 / 4325936842
head        a7032eaf4f5a3ffaece06d01657897ad0444344a
tree        e49575deb635d206bf78d0070783fca5865ab5fd
```

The machine binding is `tests/evidence/workspace/github/public-canary-subject.json`.

## Runtime flow

```text
unauthenticated GET-only api.github.com
→ existing GitHubRestMetadataSource
→ exact repository / Issue / PR / commit / check validation
→ GitHubWorkGraphAdapter
→ SqlDelightWorkspaceRegistry
→ close and reopen database
→ exact subject and edge read-back
→ disclosure scan
→ sanitized receipt artifact
```

The dedicated `Workspace Live GitHub` workflow writes the receipt to runner-temporary storage, validates it, hashes it, and uploads the JSON, SHA-256, and immutable provenance-limit manifest as a workflow artifact. Raw response bodies, tokens, headers, cookies, emails, and private locators are never part of the receipt.

## Identity and provenance ceiling

The W2 runtime model retains stable REST numeric IDs, repository slug, canonical URLs, commit SHA, tree SHA, check-run IDs, check names, head SHA, status, and conclusion.

It does **not** retain:

- GitHub GraphQL node IDs;
- the workflow-run ID that produced a check run.

Those identities are pinned by the preceding exact remote preparation receipt but are not re-derived by the W2 runtime model. The live evidence therefore keeps these states explicit:

```text
runtime_node_id_validation         = PREP_BINDING_ONLY_W2_MODEL_ABSENT
runtime_workflow_run_id_validation = PREP_BINDING_ONLY_W2_MODEL_ABSENT
```

`source_workflow_run_ids` in the receipt is a prep-bound denominator that links the exact successful checks to their previously verified workflow runs. It is not a runtime workflow-run-ID claim.

The artifact includes `receipts/workspace/live/github/provenance-limits.json`; mutation tests reject promotion of either prep-bound identity to runtime PASS.

## Evidence ceiling

A PASS proves only:

```text
one public repository subject
one exact PR head and commit tree
one W2 transport version
one hosted runner time window
W2 mapping plus W1 durable read-back
```

It does not prove private-repository access, GitHub mutation authority, Google/Bettor/domain/device lanes, user outcomes, merge, release, or production readiness.
