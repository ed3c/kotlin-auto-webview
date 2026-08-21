# ADR-025: Read-only Capability Workspace surface

Status: proposed / W5 implementation

Issue: #125

## Decision

W5 introduces the first Capability Workspace UI as a read-only Compose surface backed by explicit snapshot and route-proposal ports. The UI renders owner/domain verdicts; it does not become an authority for evidence, qualification, GitHub state, Google projection state, legal approval, provider credentials, merge, release, or runtime execution.

The Git parent is W2 because W5 consumes the GitHub WorkGraph subject shapes. W4 remains a completion/process dependency: W5 consumes routing only through a provider-neutral port using the W0 `RouteRequest` / `RouteDecision` boundary. The concrete W4 router is not copied into the W5 branch.

## Read model

```text
owner/domain repositories
+ W1 local projection
+ W2 GitHub WorkGraph
+ projection/evidence aggregators
        ↓
CapabilityWorkspaceSnapshotSource
        ↓
CapabilityWorkspaceController
        ↓
CapabilityWorkspaceUiState
        ↓
CapabilityWorkspaceScreen
```

The snapshot contains already-classified subjects, typed edges, evidence ceilings, freshness, Skill qualification state, projections, blocker codes, and available route actions. W5 does not infer a stronger closure state from these inputs.

## Sections

The surface supports the required views:

```text
Subjects
Graph
Work
Evidence
Skills
Experiments
Projections
Routes
```

Work, Evidence, Skills and Experiments are filtered projections of exact subject kinds. Projection cards are explicitly labeled `Projection (not authority)`.

## Routing law

Route buttons call only:

```text
RouteRequest
→ CapabilityWorkspaceRoutePort
→ RouteDecision
```

A route result is displayed as a proposal decision. The W0 contract prevents `executionAuthorityGranted=true`.

Routes are disabled when any required subject is stale, blocked, missing, `NOT_QUALIFIED`, or when the workspace is offline/cached. Destination/provider execution remains outside W5.

## Privacy boundary

Two display realms exist:

- `PublicSafe`: private authority labels, versions/digests and external locators are hidden.
- `AuthorizedLocal`: private locators may be displayed inside the local authorized workspace.

Any state intended for public export must pass through `toPublicState()`, which removes snapshot identity, private owner labels, private locators/digests, destination labels, and all route interactivity.

```text
AUTHORIZED_LOCAL_VIEW
!= PUBLIC_EXPORT

PRIVATE_REF_VISIBLE_LOCALLY
!= PUBLIC_LOGGABLE
```

This is a UI privacy boundary, not a replacement for repository/provider authorization.

## Offline and process recreation

A cached snapshot may be rendered while offline, but every route action is disabled. The selected section is serializable and can be restored after process recreation; a reload always re-derives the visible data from the current source result.

## Fail-closed blockers

The controller renders bounded machine blocker codes and disables action paths rather than guessing around them. Covered cases include:

- stale subject;
- missing receipt;
- `NOT_QUALIFIED` Skill;
- external-authority requirement;
- projection conflict;
- missing projection subject;
- offline cached state;
- unavailable source;
- private reference redaction.

## Scope boundary

W5 does not:

- modify GitHub issues/PRs/repository settings;
- mutate Google Docs/Sheets;
- store OAuth/provider credentials;
- decide evidence class, qualification, legal rights, or source truth;
- execute Bettor/domain work;
- merge, release, publish, purchase, or change provider configuration;
- prove user or paid outcomes;
- wire the new surface into the production `App.kt` navigation.

The last item is deliberate: W5 proves the reusable read-only surface and controller semantics against exact fixtures before a later integration atom changes the existing browser shell.

## Evidence ceiling

A W5 PASS proves read-only workspace/controller/UI semantics and privacy/route-proposal gates against exact fixtures plus cross-platform compile/test CI. It does not prove live provider integration, production navigation, private-repository authorization, user outcome, payment outcome, merge, or release.
