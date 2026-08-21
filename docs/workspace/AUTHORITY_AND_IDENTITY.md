# Authority and identity model

## Why this exists

The federation spans GitHub, Google Drive/Docs/Sheets, Web/media sources, KAW local state and specialized repositories. A URL, filename or UI row cannot safely act as global identity or authority.

## Canonical subject model

Conceptual W0 contract:

```yaml
subject_ref:
  id: REQ-...
  kind: REQUIREMENT
  canonical_authority: github://ed3c/repo/issues/123
  version: 3
  digest: sha256:...
  data_class: PUBLIC | PRIVATE | RESTRICTED
  evidence_ceiling: CONTRACT | LOCAL | LIVE | EXTERNAL

external_refs:
  - provider: GITHUB
    external_id: repo-node-or-number
    revision: commit-sha-or-updated-version
    url: https://github.com/...
  - provider: GOOGLE_DRIVE
    external_id: file-id
    revision: drive-revision
    url: https://docs.google.com/...
```

The final Kotlin/schema representation belongs to #120; this document is architectural preparation only.

## Subject kinds

```text
SOURCE
SOURCE_REVISION
CLAIM
CARD
REQUIREMENT
CAPABILITY
TECHNOLOGY_CANDIDATE
LICENSE_DECISION
SKILL_CANDIDATE
QUALIFIED_SKILL
WORK_PACKET
ISSUE
PULL_REQUEST
COMMIT
CHECK
EVIDENCE_RECEIPT
PROJECTION
DECISION
EXPERIMENT
OUTCOME
```

## Typed edges

```text
DERIVED_FROM
SUPPORTS
REFUTES
CONTRADICTS
IMPLEMENTS
DEPENDS_ON
BLOCKED_BY
QUALIFIED_BY
EVIDENCED_BY
PROJECTED_AS
SUPERSEDES
REVOKES
OUTCOME_OF
OWNED_BY
ROUTED_TO
```

Edges carry their own provenance and cannot promote the state of either node.

## Authority rules

| Domain | Authority | Projection examples |
|---|---|---|
| Source identity/bytes | source platform/admitted source repository | KAW source card, Google Doc link |
| Evidence cards | ai-content-notes | KAW graph, Sheet row |
| Current claim verdict | truth-verify-loop | badge/status row |
| Market/technology decision | ai-product-notes | roadmap view |
| Method | skills-shared | installed Skill projection |
| Skill qualification | Skill.md-native | qualification badge |
| Orchestration | bettor-arena | task status projection |
| Runtime binding | runtime-env | profile selector |
| Experiment | blackbox-auto-research/owner | outcome view |
| Work/implementation | GitHub | KAW Work view, Sheets dashboard |
| Human narrative | Google Docs | no authority promotion |

## Google identity law

Use:

```text
file ID + revision + canonical subject digest
```

Never use:

```text
filename/title
folder position
recent-file order
human description
```

Duplicate Google files with the same title are expected and must remain distinct until exact evidence proves equivalence.

## GitHub identity law

Use repository identity plus issue/PR node/number and commit SHA/check subject where applicable. Branch names are mutable navigation, not immutable implementation evidence. A check binds only the exact checked commit.

## Private/public boundary

The public KAW repository may publish generic schemas, sanitized fixture IDs and provider-neutral capability IDs. It may not publish:

- private repository names/URLs/paths/code;
- private Google Drive file IDs/URLs/revisions;
- customer/employer source metadata;
- browser history or account identifiers;
- provider credentials/tokens/cookies/sessions;
- raw private source payloads or complaint/moderation content.

Private subjects may be represented outside public Git as local opaque handles with public-safe capability envelopes where the owning authority permits it.

## Projection model

```text
canonical subject
→ ProjectionRef
→ outbox event
→ destination write
→ authenticated read-back
→ content/subject/revision verification
→ SYNCED | RETRY | CONFLICT | BLOCKED
```

A projection stores an exact pointer back to its canonical subject. It is rebuildable and disposable. Losing a projection does not erase canonical truth.

## Change proposal model

```text
manual external edit
→ observed revision delta
→ ChangeProposal(subject, external revision, diff summary, proposer realm)
→ canonical authority review
→ ACCEPTED | REJECTED | EXTERNAL_AUTHORITY_REQUIRED
→ canonical change, if admitted
→ regenerate all projections
```

No reverse sync is direct state replication.

## Route model

```text
user intent + exact SubjectRefs
→ RouteRequest
→ deterministic owner/capability resolution
→ RouteDecision
→ destination admission
→ destination receipt reference
```

The router does not invent capabilities and cannot downgrade private-data, rights or evidence restrictions to make a route succeed.
