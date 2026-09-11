# Kotlin Auto WebView system contract v1

This is the low-change owner for system intent, invariants, and requirement identities. It is not a project wiki, current-status ledger, execution transcript, or correctness oracle. Mutable Issue, PR, branch, commit, workflow, and receipt state remains with GitHub or its nearest executable owner.

The document route is `AGENTS.md` → this file when needed → the Issue-selected executable contract/test → stop. N-class files under `docs/**` are optional projections and never extend that route.

## Purpose

### SYSTEM.PURPOSE.001

The product MUST keep WebView/WKWebView/KCEF/Web observation separate from Kotlin-owned identity, policy, state, lifecycle, privacy, and execution authority.

### SYSTEM.NON_GOALS.001

The repository MUST NOT become a general Agent OS, scheduler, credential broker, anti-bot bypass, content downloader, device-management authority, or publication authority.

## Architecture and ownership

### DOC.ROUTE.001

Repository-owned architecture context MUST use at most three nodes: `AGENTS.md`, this system contract when the Issue binds a system requirement, and the exact Issue-selected executable contract/test. A fourth mandatory architecture document is invalid.

### DOC.N_CLASS.001

Documentation, diagrams, prompts, plans, inventories, and copied status under `docs/**` MUST remain N-class: useful for navigation, but unable to satisfy acceptance or authorize a transition.

### OWNERSHIP.ONE_WRITER.001

Each durable value MUST have one canonical owner and one admitted writer or transition surface. A projection may link to that value but MUST NOT become competing truth.

| Durable value | Canonical owner | Required readback |
|---|---|---|
| stable intent and requirement IDs | `contracts/system-v1.md` | source plus document-route gate |
| goal, dependencies, lease, and lifecycle | exact GitHub Issue | provider Issue body/state |
| candidate source | Git branch/commit/tree | exact head/tree and diff |
| domain behavior | nearest code contract/test | operation, observation, and negative control |
| CI, merge, and default-branch reality | GitHub | exact-head check/event/readback |
| explanatory prompt/plan | `docs/**` | N-class only |

## Work admission

### ISSUE.ATOM.001

One schedulable Issue MUST describe one independently useful repository mutation, bind existing requirement IDs, declare an exact write boundary and dependencies, and name fixed acceptance plus planted-negative controls. Issue existence is not implementation evidence.

### EXECUTION.BOUNDED.001

Raw model output MUST NOT directly become an executable selector, coordinate, script, shell command, URL, permission, device action, or publication instruction. Typed parsing, sanitization, capability policy, current identity/freshness, and user-preemption checks precede execution.

### DATA.MINIMIZATION.001

Secrets, authentication material, private paths, customer/private content, raw sensitive page data, and side-effect payloads MUST be excluded from prompts and public evidence. Receipts record minimized identifiers, transitions, results, and cleanup rather than sensitive values.

### SOURCE.RIGHTS.001

Visibility, ownership, extraction, retention, model sharing, and publication are separate admissions. Website sessions and consumer subscriptions MUST NOT be reused as API credentials; protected or restricted surfaces fail closed.

## Evidence and authority

### AUTHORITY.NO_LAUNDERING.001

P-class guidance and N-class documentation MUST NOT gain L- or R-class authority through repetition, consensus, issue/branch/PR creation, or an unrelated passing test.

### EVIDENCE.PHYSICAL.001

A behavior claim extending beyond static repository state MUST run the nearest applicable physical oracle and bind the observation to the exact candidate head/tree. A specialized oracle is additive and never replaces baseline repository acceptance.

### EVIDENCE.DENOMINATOR.001

Failure, absence, policy denial, not-exercised lanes, external authority, cleanup, and residue MUST remain in the reported denominator. Static, emulator, physical-device, privileged-effect, store, merge, release, and production evidence MUST remain distinct.

### ANDROID.AUTHORITY.001

Android compilation or emulator PASS MUST NOT imply physical-device liveness, user Accessibility enablement, restricted-settings approval, Shizuku implementation or privilege, real side effects, signing, Play policy acceptance, store review, or production readiness.

### MERGE.HUMAN.001

Agents may prepare a branch, push an exact candidate, open or update a Draft PR, and report provider checks within an exact Issue lease. Merge, merge-queue admission without explicit preauthorization, release, signing, store submission, deployment, settings/access changes, and destructive rollback remain Human/external authority.

## Requirement evolution

### REQUIREMENT.EVOLUTION.001

Add or change a requirement only through an exact Issue atom that updates this file and a rejecting executable control in the same bounded change. Mutable implementation history belongs in N-class docs or provider records, not here.

