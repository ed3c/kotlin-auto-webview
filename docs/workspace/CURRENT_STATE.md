# Federated Capability Workspace current state

Review date: 2026-08-19. Tech Lead/Shadow preparation owner: #127.

## Exact observed planning subjects

```text
kotlin-auto-webview #119  workspace epic
#120 W0 contracts
#121 W1 local registry/outbox
#122 W2 GitHub WorkGraph
#123 W3 Google projection/read-back
#124 W4 federation router
#125 W5 read-only Workspace UI
#126 W6 cross-system evidence
#127 W7 preimplementation docs convergence

bettor-arena #197       blocked consumer route owner
ai-content-notes #41    owner decision = DUAL_RUN_PROJECTION_ONLY
ai-content-notes #55    note-specific Google projection implementation owner
```

Parent Creator documentation branch: `docs/creator-capability-convergence` / Draft PR #118. Workspace prep branch: `docs/federated-capability-workspace-prep`.

## Materialized in this phase

- one generic federation epic and W0-W7 molecular issue owners;
- path leases, start dependencies, State Machines, negative controls and evidence ceilings for each atom;
- explicit cross-repository Bettor consumer issue that is blocked until an immutable W0 contract exists;
- explicit `ai-content-notes` decision that Git/GitHub is canonical and Google is projection-only;
- note-specific projection implementation issue #55;
- workspace authority/identity model, DAG, technology admission and Agent read route under `docs/workspace/**`.

## Not implemented

- W0 Kotlin/JSON contracts;
- SQLDelight subject graph/outbox/inbox;
- GitHub WorkGraph runtime adapter;
- Google Drive/Docs/Sheets writer/read-back adapter;
- federation router runtime or Bettor handoff;
- read-only Workspace UI;
- workspace evidence workflow/receipts;
- private-repository federation;
- live Google/Bettor/provider account execution.

## Intentionally not created

No manually maintained Google Doc or Google Sheet was created as a new control-plane source. W3 must first provide exact file-ID/revision binding, idempotent outbox, authenticated read-back and conflict semantics. Creating a manual dashboard before that would establish the split-brain condition this architecture is designed to prevent.

## Current Shadow Architect verdict

```text
GLOBAL_AUTHORITY_MODEL          EXPLICIT
CROSS_REPO_IDENTITY_MODEL       EXPLICIT_AT_ARCHITECTURE_LEVEL
MOLECULAR_OWNERSHIP             COMPLETE_FOR_W0_W7
GOOGLE_AUTHORITY_DECISION       RESOLVED_PROJECTION_ONLY
BETTOR_CONSUMER_OWNER           EXISTS_BLOCKED_ON_W0
ROOT_SHARED_FILE_LEASE          PRESERVED_UNDER_#98
IMPLEMENTATION                  NOT_IMPLEMENTED
LIVE_CONNECTOR_EVIDENCE         NOT_EXERCISED
PRIVATE_REPO_EVIDENCE           NOT_EXERCISED
MARKET/USER_OUTCOME             ABSENT
PREIMPLEMENTATION               READY_FOR_DOC_PR
GLOBAL_RUNTIME_CLOSED_LOOP      NOT_CLOSED
```

## L3 blocks remaining

- Bettor #197 may not implement from draft/conversational W0 bytes; it waits for an immutable admitted #120 subject.
- Google writes may not be called `SYNCED` without read-back and subject/revision verification.
- private repository/file identifiers may not enter the public KAW repository.
- no domain verdict may be recalculated or overwritten by KAW UI/router.
- technology candidates remain unadmitted until exact version/license/dependency/runtime evidence exists.
- merge, release, provider credentials, organization authorization, legal/platform/store decisions remain external authority.

## Next safe implementation sequence

```text
1. #120 W0 generic federation contracts
2. #121 W1 local graph + durable outbox/inbox
3. parallel, if exact W0 is admitted:
   - #122 GitHub WorkGraph
   - #124 federation router contract/runtime
4. #123 Google projection after W1 durability
5. #125 read-only Workspace UI
6. #126 exact cross-system evidence
7. activate bettor-arena #197 only from an immutable W0 contract
8. later root docs convergence under the active shared-index owner
```

Creator #84 remains independently load-bearing for Creator-specific source/card/Procedural IR contracts. W0 must remain generic and must not duplicate Creator semantics.

## Phase-0 completion criterion

This prep phase may be marked `PREIMPLEMENTATION_CLOSED` when its Draft PR exists, its exact branch changes are path-contained under `docs/workspace/**`, normal repository CI is recorded literally, and no missing implementation requirement lacks an issue owner. `PREIMPLEMENTATION_CLOSED` is not an implementation or production claim.
