# Agent operating contract

This file is the small, always-loaded routing and safety kernel for `kotlin-auto-webview`.

## Document route

Repository-owned architecture context has at most three nodes:

<!-- agent-document-route
AGENTS.md
contracts/system-v1.md
issue-named executable contract/test
-->

1. Read this file for authority, safety, and routing laws.
2. Read `contracts/system-v1.md` only when the Issue names a system requirement or changes a cross-cutting boundary.
3. Let the exact GitHub Issue select the nearest executable contract or test; inspect that implementation and evidence, then stop document traversal.

`docs/**` is N-class explanation, inventory, prompt, or plan. It may help navigation but is never a mandatory hop or correctness authority. A nested `AGENTS.md` may narrow a path lease; it may not add another architecture-document hop.

## Guarantee classes

| Class | Meaning | Authority |
|---|---|---|
| P | prompt, plan, model reasoning, or review | may propose; proves nothing |
| L | deterministic local command with direct readback and a red control | may reject a candidate locally |
| R | GitHub exact-head check, protected transition, merge/event readback | may admit repository reality |
| N | prose, diagram, inventory, or unverified claim | describes; proves nothing |

P or N cannot become L or R through repetition, consensus, issue creation, a branch, a Draft PR, or an unrelated green check.

## Work unit

One schedulable Issue is one independently useful repository mutation. It names:

- one goal and explicit non-goals;
- exact base/head and dependencies;
- an allowed write boundary and excluded paths;
- the requirement IDs it serves;
- fixed commands with cwd, timeout, expected exit, and durable receipt/readback;
- positive and planted-negative controls;
- evidence ceiling, cleanup, rollback subject, and external-authority operations.

Use `.github/ISSUE_TEMPLATE/repository-mutating-atom.md`. Missing fields are `ABSENT` and block only the affected transition. Domain knowledge belongs at the nearest executable boundary, not in another design document.

## Hard execution laws

- Never execute raw model text as JavaScript, selector, coordinate, shell, URL, SQL, native call, media operation, permission, or publication instruction.
- Observe and sanitize before proposing; deterministic policy and current identity/freshness precede every state-changing action.
- Passwords, payment data, OTPs, tokens, cookies, private keys, OAuth grants, browser profiles, private paths, customer data, and secret values do not enter prompts, page context, logs, receipts, or public artifacts.
- User pointer, keyboard, and system interaction preempt Agent authority.
- Origin, CSP, cross-origin iframe, robots, anti-bot, paywall, DRM, region/age, store, Accessibility enablement, Shizuku, signing, and physical-device boundaries are surfaced, never bypassed.
- Consumer subscriptions and website sessions are not API credentials. Visible or possessed content is not automatically extractable, reusable, model-shareable, or publishable.
- Repository visibility, owner/access, default branch, rulesets, secrets, license meaning, and remote topology stay unchanged unless an exact repository contract authorizes the transition.
- One durable value has one owner and one admitted writer. Independent workers require disjoint path leases; shared root files require a single selected issue.
- Do not invent a scheduler, generic worker manager, context router, evidence ledger, or fourth document hop when an existing Issue, Git, GitHub, test, or nearest contract owns the value.

## Verification and handoff

Run only repository-owned fixed commands. For shared contracts or build configuration, the current full matrix is:

```bash
./gradlew :composeApp:allTests
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:wasmJsBrowserDistribution
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64  # macOS only
```

Before push or PR, bind the Issue, base, exact head/tree, diff, path lease, applicable gates, and disclosure scan. End the handoff with changed paths, commands/exits, positive and negative observations, cleanup/residue, evidence ceiling, and every remaining non-PASS lane.

CI or emulator PASS does not prove physical-device liveness, Accessibility enablement, privileged effects, store admission, signing, release, or production. Semantic conflict resolution, physical-device trust, credentials, legal/platform approval, merge, release, deployment, and destructive rollback remain Human/external authority.

