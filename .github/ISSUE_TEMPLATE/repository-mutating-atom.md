---
name: Repository-mutating atom
about: One independently useful change with an executable acceptance boundary
title: ''
labels: ''
assignees: ''
---

<!-- kaw-role: repository-mutating-atom -->
<!-- kaw-target: ed3c/kotlin-auto-webview -->
<!-- kaw-subject: assigned-after-creation -->
<!-- kaw-state: ready -->
<!-- kaw-depends-on: none -->
<!-- kaw-requirement: SYSTEM.PURPOSE.001 -->
<!-- kaw-write-boundary: path[, path] -->

## Physical trigger

<!-- Observable failure or measurement that makes this atom necessary. -->

## Goal

<!-- One sentence; the smallest independently useful mutation. -->

## Non-goals

- No adjacent capability is admitted by prose or inference.

## Executable boundary

```yaml
command: ["repository-owned", "fixed", "argv"]
cwd: .
timeout_seconds: 600
expected_exit: 0
readback: exact output/artifact/head/tree
positive_control: exact expected observation
planted_negative_control: exact mutation and expected rejection
```

## Evidence ceiling

```yaml
proves: [bounded claim]
does_not_prove: [device, privileged_effect, store, merge, release, production]
```

## Cleanup and rollback

```yaml
rollback_subject: exact immutable commit
cleanup: remove only current-run temporary state
residue: required
```

