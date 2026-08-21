# OpenDroid integration admission

This directory is the D0 source/policy admission layer for issue #65. OpenDroid is a pinned Apache-2.0 upstream reference and behavioral canary. It is not a runtime dependency, remote authority, or wholesale vendored subsystem.

## Read order

1. `../../../integrations/opendroid/upstream.lock.json`
2. `../../../integrations/opendroid/source-ledger.json`
3. `../../../integrations/opendroid/capability-map.yaml`
4. `../../../integrations/opendroid/policy-profile-matrix.yaml`
5. `AGENTS.md`
6. `SOURCE_ADMISSION.md`
7. `CAPABILITY_MATRIX.md`
8. `POLICY_PROFILES.md`
9. `THREAT_MODEL_DELTA.md`

## Authority boundary

```text
pinned upstream observation
  -> machine-readable admission decision
  -> typed KMP contract owner
  -> local product policy
  -> local dispatcher/HITL
  -> exact target revalidation
  -> Android adapter
  -> deterministic postcondition verifier
  -> NONE | APPLIED | UNKNOWN effect ledger
```

No model/MCP/network text may become selector, coordinate, URL, shell command, package wildcard, intent, or privileged native command authority. Platform callback success is dispatch evidence only.

## Current phase status

D0 preparation artifacts are present on `docs/opendroid-source-admission`. They establish source pins, provenance mode, capability decisions, distribution ceilings, negative controls, and downstream owners. They do **not** prove completeness against every upstream action family until the D0 validation worker runs the completeness and mutation gates on the exact branch head.

Root `README.md`, root `AGENTS.md`, traceability indexes, and Local Handoff queue remain owned by issue #75 and must not be edited here.
