# Agent instructions

## Read first

1. `README.md`
2. `docs/architecture/README.md`
3. `docs/TRACEABILITY.md`
4. `docs/security/THREAT_MODEL.md`

## Invariants

- Never execute raw model text as JavaScript.
- Never read values from password, payment, or secret fields.
- User pointer input always preempts agent execution.
- New agent abilities require a `CapabilityDescriptor`, policy tests, and audit behavior.
- Keep platform renderer differences explicit; do not hide unsupported WebKit/Wasm behavior behind fake success.
- MCP handlers may propose actions but must not call privileged platform APIs directly.

## Verification contract

For every change:

```bash
./gradlew :composeApp:allTests
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:wasmJsBrowserDistribution
./gradlew :composeApp:assembleDebug
```

On macOS also run:

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Add tests at the state-machine, policy, privacy, and serialization boundaries. Do not mark App Store or Play Store delivery complete without signed artifacts and device evidence.
