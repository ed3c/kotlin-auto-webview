# Runtime foundation dependency admission

- Issue: #7
- Branch: `build/runtime-dependency-admission`
- Parent: `docs/agent-integration-stack-index`
- Purpose: admit exact build/runtime artifacts required by persistent memory (#8) and authenticated private-edge transport (#9)
- Status: build admission in progress; runtime features remain `NOT_IMPLEMENTED`

## Selected versions

| Component | Exact version | Official release identity | Source commit | Direct license |
|---|---:|---|---|---|
| SQLDelight | `2.3.2` | GitHub release `297609862`, tag object `605a10d37050c564d1fce644e3b9a6d543e2c643` | `b4906d9afbb183fea3592c12e7459a7bf772b1e5` | Apache-2.0, `LICENSE.txt` Git blob `8f71f43fee3f78649d238238cbde51e6d7055c82` |
| Ktor | `3.5.1` | GitHub release `346132324`, tag `3.5.1` | `5ba9d6fdf1ea9acfac5de67e7fe5a072639eac64` | Apache-2.0, `LICENSE` Git blob `68663eb9512df8d5bf99b73177d0ab65ddb2014f` |

No dynamic version, default-branch dependency, mutable installer, source copy, binary download script, or SaaS endpoint is introduced.

## Declared artifacts

### SQLDelight 2.3.2

```text
app.cash.sqldelight:runtime
app.cash.sqldelight:coroutines-extensions
app.cash.sqldelight:android-driver
app.cash.sqldelight:native-driver
app.cash.sqldelight:sqlite-driver
app.cash.sqldelight:web-worker-driver
plugin: app.cash.sqldelight
```

Target placement:

| Source set | Artifact |
|---|---|
| `commonMain` | runtime + coroutines extensions |
| `androidMain` | Android driver |
| `iosMain` | Native driver |
| `desktopMain` | SQLite/JDBC driver |
| `wasmJsMain` | Web Worker driver |

Primary-source target evidence:

- SQLDelight runtime 2.3.2 applies the default hierarchy with JS, WasmJS, and Native targets.
- Web Worker driver 2.3.2 declares both `js()` and `wasmJs()` browser targets.
- Release 2.3.2 raises the Android driver minimum to API 23; this repository uses minimum API 24.
- The Gradle plugin declares compatibility work for current Android Gradle Plugin generations.

The database is named `AppDatabase` and reserves package `dev.ed3c.autowebview.persistence.db`. No schema, migration, driver factory, or persistent record is implemented in this Stack slice.

### Ktor 3.5.1

```text
io.ktor:ktor-client-core
io.ktor:ktor-client-cio
io.ktor:ktor-client-content-negotiation
io.ktor:ktor-client-websockets
io.ktor:ktor-client-sse
io.ktor:ktor-serialization-kotlinx-json
```

The CIO engine is selected as the common baseline because official Ktor documentation lists it for JVM, Android, Native, JS, and WasmJS. Platform-specific engines may be evaluated later only through a separate task packet.

Primary-source compatibility evidence:

- Ktor 3.5.1 includes a fix for Kotlin 2.4 compiler-plugin breaking changes; this repository pins Kotlin 2.4.10.
- Ktor 3.5.1 fixes the CIO static `node:net` import that broke WasmJS browser Webpack builds.
- Ktor provides WebSocket and SSE client APIs needed by the future ordered/private stream, but this PR configures no endpoint or transport identity.

## Security and authority delta

Adding Ktor makes outbound HTTP/WebSocket/SSE code possible in later branches. This branch grants no network authority:

- no URL, host, origin, token, certificate, cookie, or credential is configured;
- no client is instantiated;
- no request is sent;
- no telemetry or analytics SDK is added;
- no ambient secret is read;
- no background service is started.

Adding SQLDelight makes local persistence possible in later branches. This branch writes no application data and defines no schema.

Future #8/#9 implementations must add origin/peer policy, lifecycle shutdown, cancellation, replay protection, bounded buffers, migration/retention controls, and secret-redaction tests.

## License and notice evidence

| Lane | SQLDelight | Ktor |
|---|---|---|
| Exact source tag/commit | `PASS` | `PASS` |
| Direct license source | `PASS` — Apache-2.0 | `PASS` — Apache-2.0 |
| Additive repository NOTICE entry | present in this branch | present in this branch |
| Maven artifact resolution | `NOT_EXERCISED` until exact-head CI | `NOT_EXERCISED` until exact-head CI |
| Transitive dependency inventory | `NOT_EXERCISED` |
| Transitive license/SBOM review | `NOT_EXERCISED` |
| Required third-party notice extraction | `NOT_EXERCISED` |
| Patent/trademark/export review | `NOT_EXERCISED` |
| Organization legal acceptance | `EXTERNAL_AUTHORITY_REQUIRED` |
| Hosted-service terms | not exercised; no service configured | not exercised; no service configured |

Direct Apache-2.0 evidence does not mean zero commercial/legal risk. This document does not accept legal terms on behalf of a person or organization.

## Build admission matrix

The owning exact-head CI must pass:

```bash
./gradlew :composeApp:allTests
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:wasmJsBrowserDistribution
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Expected proof:

- plugin and artifacts resolve from admitted repositories;
- common metadata is compatible with the repository Kotlin compiler;
- Android, iOS simulator, Desktop JVM, and WasmJS variants exist for their declared source sets;
- the existing application and tests still compile/link/package.

This does not prove database persistence, migrations, network behavior, production connectivity, physical-device behavior, package-size budget, security approval, legal approval, or store readiness.

## Negative controls

Review/evals reject:

- a dynamic selector, snapshot, default branch, or `latest`;
- moving a platform driver into an incompatible source set;
- adding an endpoint, credential, telemetry SDK, or feature implementation to this dependency-only PR;
- claiming direct-license review completes transitive/legal review;
- claiming successful resolution proves runtime correctness;
- changing project `LICENSE` meaning or removing attribution;
- adding SQLDelight/Ktor source code copied from upstream.

## Remaining gates

Before this record can be used as release/legal evidence:

1. capture exact resolved artifact and transitive dependency identities;
2. generate or inspect an SBOM using a repository-admitted tool;
3. determine transitive notice obligations;
4. measure package-size impact;
5. receive organization legal acceptance outside Agent authority.

Until then, this PR may establish build compatibility but not complete legal or release admission.
