# L3 Google transport evidence

This stage implements the missing Google Docs REST adapter behind W3's existing `GoogleProjectionTransport`. It does not run OAuth, choose an account, create a file, or claim a live projection.

## Implemented path

```text
external GoogleDocsAccessCapability
→ exact docs.googleapis.com endpoint
→ documents.get
→ file/revision/structure/envelope parsing
→ second transport-side pre-write read
→ documents.batchUpdate
   + atomic delete/insert
   + writeControl.requiredRevisionId
→ W3 mandatory read-back saga
```

The adapter hashes rendered content in common Kotlin, blocks arbitrary or corrupt document bodies, maps provider failures to bounded reason codes, propagates cancellation, and never serializes access tokens.

## Sheets boundary

The current W3 binding carries a file ID and expected revision. Google Docs exposes an opaque `revisionId` and `requiredRevisionId` write control. The Sheets values and batchUpdate APIs do not expose the same revision precondition through this contract. A read-then-write sequence would leave a race that could overwrite collaborator changes.

Therefore:

```text
GoogleProjectionKind.SHEET
→ GOOGLE_SHEETS_CONDITIONAL_WRITE_UNSUPPORTED
→ BLOCKED
```

A future Sheets adapter needs a separately admitted concurrency contract; it cannot reuse the Docs PASS.

## Evidence ceiling

```text
L3_GOOGLE_DOCS_TRANSPORT_CODE_READY  PASS after exact-head CI
LIVE_GOOGLE_PROJECTION_ACCOUNT       NOT_EXERCISED
LIVE_DOCS_FILE                       NOT_EXERCISED
LIVE_SHEETS_FILE                     NOT_EXERCISED
OAUTH / ACCOUNT / SCOPES / DLP       EXTERNAL_AUTHORITY_REQUIRED
CONTENT / PUBLICATION RIGHTS         EXTERNAL_AUTHORITY_REQUIRED
```
