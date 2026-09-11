# ADR-0033: Android distribution profiles are compile/package-time capability ceilings

## Status

Accepted as the Stage-5 C2 source contract for issue #71. This ADR describes source/build behavior only; merge, signing, Google Play declarations/review, accessibility-tool eligibility, release and production rollout remain outside the claim.

## Exact parent subject

C2 is a true child of the selected X1 source, not of the X1 evidence branch:

- parent issue: #70;
- parent source head: `b70ea4544b56e4ec34b11a467808814913c27dc7`;
- parent source tree: `f110894117a38ab0ad7c4d5c65299d2ef8c0949b`;
- parent source PR: #151 (Draft/unmerged).

Issue #152 / PR #153 remain completion evidence for X1 and are deliberately not imported into the C2 product tree.

## Decision

The repository has exactly one distribution-profile enum: common `DistributionProfile`. Android does not introduce a competing profile model.

AGP creates exactly two distributable product flavors under dimension `distribution`:

- `playSafe` → `DistributionProfile.PLAY_SAFE` → application id `dev.ed3c.autowebview`;
- `enterprise` → `DistributionProfile.ENTERPRISE_SIDELOAD` → application id `dev.ed3c.autowebview.enterprise`.

No `accessibilityTool` Android product flavor or release task exists. The common enum value may remain as a contract state, but an Android distributable artifact cannot select it without a later external Human/legal/store admission decision and a new bounded architecture revision.

Each flavor writes `DISTRIBUTION_PROFILE_ID` into AGP-generated `BuildConfig`. `AndroidCompiledDistributionProfile` immediately maps that immutable build constant to the existing common enum and rejects unknown or accessibility-tool values. It exposes no setter and reads no intent, preference, environment variable, network value, MCP payload, model output or remote configuration.

The enterprise package identity is deliberately different from the Play-review candidate so operator error cannot silently substitute the stronger artifact.

## Packaged capability contract

Each flavor owns `assets/capability-profile.json`. The JSON is a package-time contract, not a runtime permission grant. CI requires the packaged asset to match the checked-in source contract and cross-checks it against:

- AGP output metadata / application id;
- the merged debug and release manifests;
- exported component surface;
- debug and release runtime classpaths;
- packaged debug and release APK contents;
- explicit release task surface;
- APK SHA-256 for all four app variants.

The capability contract distinguishes two permission classes:

1. `allowed_permissions`: product capability permissions. At C2 this remains exactly `android.permission.INTERNET` for both profiles.
2. `allowed_internal_signature_permissions`: app-scoped implementation permissions contributed by dependencies. AndroidX Core contributes `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; the oracle requires that exact application-id-qualified name and requires the merged `<permission>` declaration to remain `protectionLevel="signature"`.

The second class is not authority widening and may not be used as a general extra-permission allowlist. Any unexpected permission or broader protection level fails the package oracle.

AndroidX ProfileInstaller contributes an exported DUMP-protected `ProfileInstallReceiver`. This product does not need that externally addressable receiver, so the app manifest removes it with manifest-merger `tools:node="remove"`. CI therefore preserves the product invariant that the only exported component at C2 is the launcher activity. AndroidX Startup's provider may remain because it is not exported.

The Play-safe hard boundary cannot be widened by editing the asset alone. The checker independently denies broad permissions, AccessibilityService declarations, unexpected exported components, Shizuku dependency/package markers and runtime profile override inputs.

At this stage both profiles still package only the existing product INTERNET capability and launcher activity. Enterprise Accessibility/Shizuku implementation is owned by later issue #73; C2 merely creates the compile/package boundary that later child must remain inside.

## Release selection

Generic `assembleRelease` and `bundleRelease` are fail-closed during Gradle configuration because they would aggregate artifacts with different authority ceilings. Operators and CI must use explicit tasks:

- `assemblePlaySafeRelease` / `bundlePlaySafeRelease`;
- `assembleEnterpriseRelease` / `bundleEnterpriseRelease`.

Debug variants are explicitly built and hashed for evidence but are not treated as release-authority decisions.

## Mechanical evidence

The existing `android` CI check keeps its check identity but becomes the C2 package oracle. On an exact PR head it:

1. runs planted checker controls;
2. asks AGP to report the resolved product-flavor/source-set graph;
3. runs Play-safe and enterprise Android unit tests;
4. builds Play-safe debug/release and enterprise debug/release APKs;
5. records the generated task surface and all four runtime classpaths;
6. proves generic `assembleRelease` and `bundleRelease` fail before release execution;
7. validates profile source, packaged capability contracts, manifest permissions/protection levels/components, dependency markers, application ids and APK names for every variant;
8. writes `android-distribution-profiles-receipt/v1` with exact parent/head/tree and SHA-256 for all four APKs.

Planted negative controls include broad Play-safe permission, AccessibilityService declaration, Shizuku dependency, runtime profile override input, non-signature internal permission, unexpected exported library receiver and an `accessibilityTool` distributable flavor. A later Shadow review must treat a green build without the machine-readable receipt as insufficient.

## Preserved invariants

- Play-safe retains the primary application id, `allowBackup=false`, `usesCleartextTraffic=false` and INTERNET-only product capability posture.
- AndroidX internal receiver permission remains application-scoped and signature-only; it is recorded separately from product capability permissions.
- The unnecessary exported ProfileInstaller receiver is removed from the merged app surface.
- Enterprise has a visibly separate package identity.
- Every debug/release variant packages a capability manifest and has an exact APK SHA-256 receipt.
- No new dependency or license surface is introduced by C2.
- No AccessibilityService, Shizuku, root, raw shell, terminal or inbound mobile MCP authority is added by C2.
- Successful packaging is not Google Play eligibility or release evidence.
- User/HITL/runtime authority semantics from X1 are unchanged; this issue changes the build/package ceiling only.

## Evidence ceiling

A C2 PASS may prove exact-head Android compile/package separation, declared capability ceilings, explicit artifact identity and deterministic package inspection. It does not prove:

- Google Play review or approval;
- accessibility-tool purpose/eligibility;
- live AccessibilityService behavior;
- Shizuku grants or privileged execution;
- physical-device safety;
- signing credentials;
- merge, release or production rollout.
