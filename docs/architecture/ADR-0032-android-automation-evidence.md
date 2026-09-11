# ADR-0032: Android automation evidence convergence

Status: accepted for Stage 7 implementation on issue #74.

## Context

Stage 6 produced two independently selected sibling sources from the same #71 distribution-profile parent:

- #72 Play-safe own-WebView adapter;
- #73 bounded enterprise Accessibility exact-target click adapter.

The evidence stage must test both without rewriting either source leaf, without pretending one sibling is the Git parent of the other, and without promoting one evidence carrier into a stronger evidence class. `runtime-env#62` owns the secret-free host/runtime contract while this repository owns product fixtures, commands, assertions, receipts and evidence interpretation.

## Source convergence

Git history and process dependency are represented separately.

`test/android-device-automation-evidence` starts from a convergence snapshot whose Git parent is the selected #71 head. The snapshot tree is the exact union of the selected #72 and #73 source bytes. Machine-readable bindings retain #72 and #73 as `process_parents`; the evidence branch then changes only issue #74 paths.

The selected source paths are re-hashed against the exact #72/#73 commits on every evidence run. A source-byte change, stale subject, unexpected path or convergence-tree drift stops the evidence lane.

## Evidence lanes

The literal lanes are:

1. `L0_STATIC_CONTRACT` — source binding, receipt schema, manifest/dependency/APK inspection;
2. `L1_LOCAL_DETERMINISTIC` — unit/property/negative controls without device claims;
3. `L2_EMULATOR` — fixed managed AVD and instrumentation on an exact API/image;
4. `L3_PHYSICAL_DEVICE` — Human/local physical-device execution only;
5. `L4_PRIVILEGED_DEVICE` — Human/local privileged device and one admitted typed operation only;
6. `L5_STORE_POLICY` — external Play/store declaration/review only;
7. `L6_HUMAN_ADMIT` — semantic conflict, unknown-effect remediation, signing, merge, release and rollout.

A receipt's carrier class and maximum claim must match its literal lane. Static/package evidence cannot satisfy runtime; emulator cannot satisfy physical; physical cannot satisfy store policy; queue/readiness validation cannot satisfy execution.

## Public receipt contract

Every receipt binds the exact convergence/source/runtime-env subjects, evidence head/tree, lane/carrier, bounded repository-relative commands and timeouts, assertions, planted negative controls, cleanup state and maximum claim.

Public receipts reject device serials, screenshots, raw DOM/UI text, target tokens, fill values, credentials, private endpoints and private absolute paths. The tests may manipulate fixed synthetic fixture values, but those values are never copied into receipts.

`PASS` requires cleanup/residue PASS and observed rejecting negative controls. `FAIL`, `ABSENT`, `NOT_EXERCISED`, `NOT_IMPLEMENTED`, `SKIPPED_BY_POLICY` and `HUMAN_ADMIT_REQUIRED` remain first-class outcomes and stay in the denominator.

## Managed-emulator fixture

The repository uses only a fixed API allowlist: 24, 28, 33 and 36. Missing or incompatible system images fail/record absence rather than being silently substituted.

The instrumentation harness is framework-only so Stage 7 does not modify `composeApp/build.gradle.kts` or add production/test dependencies. It exercises a synthetic app-owned WebView page and verifies:

- fill requires the exact target plus fresh input/change evidence;
- select requires the exact selected-value change evidence;
- click accepts only the selected anchor whose existing href equals the Human-authored owned HTTPS postcondition;
- wrong destination, sensitive/disabled target, expired token and pre-dispatch cancellation fail closed;
- duplicate labels remain distinct exact fingerprints;
- shadow-root and iframe descendants are not silently traversed;
- Play-safe packages contain no AccessibilityService/Shizuku surface;
- enterprise packages contain exactly the selected BIND_ACCESSIBILITY_SERVICE-protected service;
- automated tests do not enable AccessibilityService.

The emulator lane does **not** claim enterprise Accessibility side-effect execution because service enablement, restricted-settings acceptance and managed-package selection are Human/local authority.

## Shizuku boundary

Selected #73 contains no typed Shizuku operation. Stage 7 therefore records Shizuku operation state as `NOT_IMPLEMENTED`; package presence, permission state or Binder connection cannot be promoted into operation PASS.

## runtime-env handshake

The initial evidence preparation consumes the exact green `runtime-env#62` preparation subject. After this repository checks in concrete fixed fixture/run/receipt/cleanup paths, `runtime-env#62` can replace its earlier placeholder consumer binding with this exact evidence-preparation subject and expose fixed entrypoints only. Stage 7 completion then rebinds that exact runtime-env contract before publishing final emulator receipts.

No generic trailing argv, generic `adb shell`, device selector, credential or product policy is delegated to `runtime-env`.

## Human/external authority

The following are never auto-advanced by this stage: USB trust, device unlock, Accessibility/restricted-settings enablement, managed-package selection, Shizuku grant/revoke, private test data, Google Play declarations/review, signing, semantic-conflict resolution, merge, release or production rollout.

## Evidence ceiling

A green Stage 7 automated result can prove L0/L1 and exact managed-emulator behavior for the selected artifacts and fixture subjects. It cannot prove arbitrary third-party apps, OEM/fleet timing, physical devices, Accessibility consent, Shizuku operation, Play eligibility, legal compliance, signing, merge, release or production safety.
