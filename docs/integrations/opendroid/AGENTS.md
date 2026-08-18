# Agent contract — OpenDroid D0

Scope: `integrations/opendroid/**`, `docs/integrations/opendroid/**`, and issue #65 allowed paths only.

## Mandatory behavior

- Bind repository/base/head/tree and upstream commit/tree before work.
- Treat OpenDroid as reference-only unless `source-ledger.json` explicitly records copied/modified code.
- Do not write product Kotlin, Android manifests, root README/AGENTS, LICENSE, NOTICE, or shared traceability indexes in D0.
- Preserve proposal-only MCP, local capability policy, HITL, exact target revalidation, user preemption, and `NONE | APPLIED | UNKNOWN` effect truth.
- Reject direct MCP action authority, generic shell/root/terminal, first-match target authority, unbounded coordinates, and raw screen data before privacy filtering.
- `PLAY_SAFE` may not package device-wide Accessibility execution or Shizuku.
- `ACCESSIBILITY_TOOL` remains disabled until external Human/policy admission exists.

## Shadow checkpoints

Run independent read-only review at architecture choice, upstream/license change, first green, and before PR/publication. L3 findings stop the branch. Worker self-report is not independent evidence.

## Completion evidence

D0 can only claim source/policy admission for the exact pinned upstream subject. It cannot claim Android implementation, emulator/physical-device behavior, Play eligibility, merge, release, or production readiness.
