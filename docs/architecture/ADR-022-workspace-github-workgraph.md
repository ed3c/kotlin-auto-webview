# ADR-022: Read-only GitHub WorkGraph adapter

Status: Proposed for W2 implementation (#122)

## Context

W0 defines stable federation identities and W1 provides a durable KAW-local subject/edge projection. The workspace still needs a bounded adapter that can read GitHub repository, issue, pull-request, commit and check-run metadata and project it into the local graph without turning URLs, branch names, issue state or CI labels into a competing authority.

The adapter must preserve several distinctions:

```text
ISSUE_CLOSED != IMPLEMENTATION_OR_RUNTIME_CLOSED
PR_MERGED != USER_OR_PAID_OUTCOME
CHECK_SUCCESS != TECHNICAL_EVIDENCE_UNLESS_SHA_IS_EXACT
BRANCH_NAME != IMMUTABLE_REVISION
GITHUB_URL != STABLE_IDENTITY
LOCAL_PROJECTION != GITHUB_CANONICAL_STATE
```

## Decision

Add a read-only GitHub WorkGraph plane under `workspace/github` with three parts:

1. `GitHubRestMetadataSource`
   - accepts an already-created `HttpClient` and an ephemeral token provider;
   - admits only `https://api.github.com`;
   - reads repository, issue, pull-request, explicit commit and check-run metadata;
   - uses bounded check-run pagination;
   - converts HTTP, decode, rate-limit and unavailable states into typed failure reasons;
   - never persists credentials or exposes repository mutation methods.

2. `GitHubWorkGraphMapper`
   - binds stable GitHub REST database IDs for repository/issue/PR/check subjects and exact SHA for commit subjects;
   - canonicalizes web URLs from the admitted repository slug rather than trusting an arbitrary response URL;
   - maps issue/PR/commit/check state into W0 `SubjectRef`, `ExternalRef` and `TypedEdge` contracts;
   - collapses identical aliases but rejects conflicting identities or two stable IDs for the same issue/PR number;
   - marks successful checks as `TECHNICAL` only when `check.headSha == currentPullRequest.headSha`;
   - keeps stale, pending, failed and orphaned checks at `SOURCE_ONLY` and does not emit a PR technical-evidence edge;
   - preserves a deleted branch as a warning while retaining exact immutable SHA evidence.

3. `GitHubWorkGraphAdapter`
   - reads one exact request snapshot;
   - rejects repository or observation-sequence mismatch before local writes;
   - writes subjects before edges through the W1 registry sink;
   - returns partial write counts if a monotonic/local identity gate rejects a later write, so a caller can replay the same idempotent request;
   - owns no scheduler, retry daemon, checkout, merge or repository-setting authority.

## Stable identities

```text
repository  GHREPO:<GitHub REST repository id>
issue       GHISSUE:<GitHub REST issue id>
pull request GHPR:<GitHub REST pull-request id>
commit      GHCOMMIT:<40-character SHA>
check       GHCHECK:<GitHub REST check-run id>
```

URLs remain `ExternalRef` projections. Branch names are display/routing metadata only; exact commit SHA controls technical-evidence freshness.

## Typed edges

```text
issue / PR / commit  --DERIVED_FROM--> repository
PR                    --DEPENDS_ON----> base commit
PR                    --EVIDENCED_BY--> head commit
PR                    --IMPLEMENTS----> explicitly linked issue
check                 --DERIVED_FROM--> checked commit
PR                    --EVIDENCED_BY--> successful exact-head check only
```

An issue link is explicit request metadata; W2 does not infer causal or closure relationships from title text, labels or model output.

## Privacy

Private repository subjects inherit `PRIVATE` visibility and `CONFIDENTIAL` data class. Public summaries for a private repository expose only aggregate counts, subject kinds and evidence-state classes. They omit repository owner/name, URLs, REST IDs, commit SHAs, revisions and digests.

No private repository identifier is committed in fixtures; tests use synthetic placeholders only.

## Failure behavior

The adapter fails closed for:

- unavailable, unauthorized, rate-limited or removed repositories/resources;
- response identity mismatch;
- issue endpoints that are actually pull-request aliases;
- malformed or unknown payloads;
- conflicting duplicate stable IDs;
- one issue/PR number resolving to multiple stable IDs;
- observation sequence or requested repository mismatch;
- W1 subject/edge monotonicity or identity rejection.

No failed read is converted into an empty successful graph.

## Non-goals

W2 does not implement:

- issue, PR, branch, check, settings, access, ruleset, secret or merge mutation;
- private credential storage or automatic account discovery;
- local checkout, Git Town, build execution or runtime verification;
- GitHub webhook/background refresh scheduling;
- Google Docs/Sheets projection;
- Bettor/domain routing;
- canonical conflict resolution;
- user, payment, market, legal, Store or production evidence.

## Verification ceiling

A green W2 slice proves deterministic GitHub payload decoding, stable identity and typed-edge mapping, exact-head check classification, privacy-safe public summaries, fail-closed unavailable/alias/conflict behavior, and restartable writes into the exercised W1 local projection interface.

It does not prove live authenticated private-repository access, webhook freshness, checkout/build behavior, merge authority, deployment, or product outcome.
