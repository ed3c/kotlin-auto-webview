# Android OpenDroid Evidence Plan — Stage 7

Issue: #74  
Branch: `test/android-device-automation-evidence`  
Authority: evidence-only; no merge/release/device-permission authority.

## Read order

1. `integrations/opendroid/fixtures/android-device-evidence-bindings.json`
2. `docs/architecture/ADR-0032-android-automation-evidence.md`
3. `scripts/evidence/android/evidence_contract.py`
4. `scripts/evidence/android/verify_selected_sources.py`
5. `composeApp/src/androidInstrumentedTest/kotlin/dev/ed3c/autowebview/evidence/android/AndroidAutomationEvidenceInstrumentedTest.kt`
6. `.github/workflows/android-device-evidence.yml`

## Exact source graph

```text
#71 aad95c05…
  ├─ process parent #72 bcb79473…  (Play-safe)
  └─ process parent #73 228a9f1e…  (Enterprise)

#74 convergence snapshot 73026bcf…
  Git parent: #71
  tree: exact #72 + #73 selected-byte union
  process parents: #72 + #73

#74 evidence commits
  write lease: evidence paths only
```

The connector could not create a true multi-parent commit safely, so the repository does not fake one. Git parent and process parents are separately bound and verified.

## State machine

```text
EVIDENCE_PLAN_BOUND
→ SOURCE_SUBJECTS_VERIFIED
→ CONTRACT_NEGATIVE_CONTROLS_RED
→ L0_PACKAGE_BUILD
→ L0_RECEIPT_PASS
→ INSTRUMENTED_TEST_APKS_COMPILE
→ L2_CARRIER_PROVISIONING
   ├─ ABSENT / FAIL
   └─ EMULATOR_BOOTED
      → PLAY_SAFE_FIXTURE
      → ENTERPRISE_PACKAGE_BOUNDARY
      → CLEANUP
      → L2_RECEIPT_PASS
→ RUNTIME_ENV_EXACT_REBIND
→ FINAL_EXACT_HEAD_REPLAY
→ STAGE7_AUTOMATED_EVIDENCE_COMPLETE
```

Physical, privileged, store and Human lanes never transition automatically from this state machine.

## Evidence DAG

```text
selected #72 ─┐
              ├─ source convergence ── L0 package/static ──┐
selected #73 ─┘                                            │
                                                           ├─ #74 final evidence receipt
runtime-env#62 preparation ── fixed #74 subjects ── rebind ┤
                                                           │
L0/L1 contract controls ── managed emulator matrix ────────┘

L3 physical        = Human/local queue only
L4 privileged      = NOT_IMPLEMENTED until a typed Shizuku operation exists
L5 store policy    = Human/external
L6 merge/release   = Human/external
```

## Fixed automated commands

The checked-in automation exposes no arbitrary trailing command channel. The intended fixed product-side commands are:

```text
python3 scripts/evidence/android/evidence_contract.py
python3 scripts/evidence/android/verify_selected_sources.py
python3 scripts/evidence/android/render_static_receipt.py
python3 scripts/evidence/android/validate_receipts.py
KAW_EVIDENCE_API=<24|28|33|36> bash scripts/evidence/android/run-managed-emulator.sh
```

`KAW_EVIDENCE_API` is validated against the checked-in four-value allowlist; it cannot become caller-selected arbitrary argv.

## Receipt truth table

| Evidence | Automated state in Stage 7 | Strongest valid claim |
|---|---|---|
| selected source bytes | PASS/FAIL | exact source binding only |
| profile/APK inspection | PASS/FAIL | package separation only |
| unit/contract controls | PASS/FAIL | local deterministic semantics only |
| managed AVD | PASS/FAIL/ABSENT | exact emulator subject only |
| Play-safe WebView actions | PASS/FAIL on L2 | selected synthetic owned-WebView fixture only |
| enterprise service declaration | PASS/FAIL | component/package presence only |
| enterprise service enabled/connected | NOT_EXERCISED | Human/local required |
| enterprise real click side effect | NOT_EXERCISED | Human/local required |
| physical device | ABSENT/NOT_EXERCISED until local run | no emulator promotion |
| Shizuku operation | NOT_IMPLEMENTED | no privileged claim |
| Play/store approval | HUMAN_ADMIT_REQUIRED | external review only |
| Local Handoff execution | NOT_EXERCISED | queue validity is not execution |

## Shadow stop conditions

L3 stop immediately on any of:

- source bytes change outside #74 lease;
- evidence lane/carrier mismatch;
- public device identity, screenshot, raw UI/DOM, token, private path or credential disclosure;
- automatic AccessibilityService or restricted-settings enablement;
- generic shell/process/terminal surface;
- Shizuku operation invented without #73 typed contract;
- platform callback or package presence promoted to `APPLIED`;
- emulator receipt reused as physical/store evidence;
- cleanup residue hidden while state is PASS.
