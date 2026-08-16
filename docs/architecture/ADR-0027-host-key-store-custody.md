# ADR-0027: At-rest custody for the listener credential

- Status: proposed
- Issue: #60
- Depends on: #46 (credential lifecycle), #33 (listener)
- Dependency delta: none (platform CLIs, no new library)

## Context

ADR-0019 made a narrow, precise custody claim: the credential is never persisted, logged, or
rendered, and raw bytes live only inside a one-shot handle. That is real, and it is also why a
restart loses the credential — the host has to mint a new one and redistribute it to the approved
child process every time the application starts.

The gap this closes is *at rest*, without weakening any of the in-memory rules.

## Decision

Add `McpHostKeyStore`: a small store/retrieve/delete surface backed by the operating system, and an
optional dependency of `DesktopMcpCredentialLifecycle`.

```text
macOS    Keychain via /usr/bin/security, honouring an explicit keychain path
Linux    Secret Service via secret-tool
other    no integration
```

No new library is introduced. The platform CLIs are already present where the service is, and a
JNI/JNA binding would add a dependency and its licence evidence for a surface this small.

### Absence is a typed answer, never a silent downgrade

`McpHostKeyStores.resolve` returns either `Available` or `Unavailable(reason)`:

```text
UNSUPPORTED_PLATFORM   no integration exists for this operating system
TOOL_ABSENT            the platform has such a service, its client tool is not installed
SERVICE_UNREACHABLE    the tool exists but could not be used (a headless container, typically)
```

A caller that wants persistence must handle `Unavailable` itself. Nothing here quietly turns "kept
by the operating system" into "kept in this process only" — that distinction *is* the security
property, and an unstated fallback is exactly how such a property disappears. When no store is
passed, the lifecycle behaves precisely as it did before: in-process custody, and a restart requires
a fresh issuance.

### Revocation reaches at-rest custody

`revoke()` deletes the stored value. Clearing memory while leaving a restorable copy on disk would
let the next start silently un-revoke the credential — a revocation that survives only until the
process restarts is not a revocation.

### The epoch travels with the value

A restored credential verifies under the epoch it was *issued* with, and the ordinal continues past
it so a later rotation cannot reuse a retired epoch. Without this, the semantic replay keys from #43
would silently change domain across a restart: the same digest would land in a different credential
epoch and stop suppressing what it was suppressing.

### A damaged record is not a credential

An unparsable stored record is discarded rather than trusted, so the next call is a clean issuance
instead of an unexplained repeated failure.

## Invariants

### `INV-MCP-CUSTODY-001` — absence cannot be mistaken for a configured store

- Negative control: an unknown OS name reports `UNSUPPORTED_PLATFORM`; a Linux runner without
  `secret-tool` reports `TOOL_ABSENT`.

### `INV-MCP-CUSTODY-002` — revocation is not undone by a restart

- Negative control: with the at-rest delete removed, the persistence suite fails with
  "revocation must reach at-rest custody".

### `INV-MCP-CUSTODY-003` — a restored credential keeps its epoch

- Negative control: with the stored epoch discarded, the suite fails `expected:<epoch-3> but
  was:<epoch-2>`.

### `INV-MCP-CUSTODY-004` — one account holds exactly one record

- Enforcement: macOS uses `add-generic-password -U`; a rotation replaces rather than accumulating.

## Verification and its uneven reach

This is the part worth reading carefully, because coverage is not uniform:

| Path | Where it is exercised |
|---|---|
| Platform dispatch and absence reasons | every platform — `resolve` takes the OS name as a parameter |
| macOS Keychain round-trip, replacement, deletion, account isolation | local macOS only; CI runs `desktopTest` on ubuntu |
| Linux tool-absent behaviour | CI (ubuntu has no `secret-tool`) |
| Lifecycle persistence, restore, revocation, damaged record | every platform, via an in-memory store double |
| **Linux Secret Service round-trip** | **nowhere** |

Tests never touch the user's login keychain: a temporary keychain is created by path, never added to
the search list, and deleted afterwards.

## Evidence boundary

```yaml
os_backed_custody_macos: PASS
restore_across_restart: PASS
revocation_reaches_storage: PASS
absence_is_typed_and_explicit: PASS

linux_secret_service_round_trip: NOT_EXERCISED
windows_dpapi_or_credential_manager: NOT_IMPLEMENTED
hardware_backed_custody: NOT_IMPLEMENTED
```

Windows is not implemented rather than implemented-and-unverified. There is no Windows runner in
this repository's workflows, so shipping a DPAPI backend would create the appearance of support with
no way to know whether it works — the failure mode this project keeps finding elsewhere.

Hardware-backed custody (HSM, Secure Enclave, TPM) is a different class of claim and must not be
read into this one.
