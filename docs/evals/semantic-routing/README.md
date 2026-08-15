# Semantic-routing evaluation contract

- Issue: #12
- Corpus: [`corpus.json`](corpus.json)
- Corpus schema: `kotlin-auto-webview/semantic-routing-corpus/v1`
- Data classification: synthetic, public, non-sensitive
- Evidence level: deterministic common-code baseline

## Purpose

This evaluation surface separates semantic routing from cache storage and projection rendering. It defines what the lexical baseline must prove before issue #13 may evaluate an on-device embedding or SLM engine.

The source architecture proposes that local KMP code perform semantic pruning and route relevant L1/L2 cache records to the projection layer. This baseline does not accept the source's model, latency, memory, or physical-device claims as measured fact. It establishes a reproducible adapter contract and bounded fallback first.

## Fixed corpus

The checked-in corpus contains four synthetic candidates and three single-relevant-item queries:

| Query ID | Expected top result |
|---|---|
| `q-kmp-privacy` | `kmp-privacy` |
| `q-openclaw-stream` | `openclaw-stream` |
| `q-ios-release` | `ios-release` |

Acceptance for this small baseline is top-1 precision `1.0` and top-1 recall `1.0`. This corpus is a contract fixture, not a product-quality benchmark. Future corpora must add multilingual text, adversarial overlap, long documents, typo/noise, and representative product domains without introducing private user data.

## Eval index

### `EVAL-SEM-001` — deterministic order

Given identical scores, results are ordered by stable candidate ID. Input ordering must not alter the final ranking.

### `EVAL-SEM-002` — fixed-corpus relevance

Every query in `corpus.json` returns the declared top result with top-1 precision and recall of `1.0`.

### `EVAL-SEM-003` — stale-context rejection

A candidate with an expired timestamp, mismatched page fingerprint, or a page fingerprint without a current-page identity is rejected before scoring. A higher lexical score cannot revive stale evidence.

### `EVAL-SEM-004` — deterministic resource envelope

The router enforces:

```text
maximum candidate count
maximum characters per candidate
maximum total processed characters
maximum output result count
```

The test oracle inspects metrics and proves that the configured operation budget is not exceeded. It does not estimate native heap use or battery cost.

### `EVAL-SEM-005` — primary-adapter fallback

A non-cancellation failure from a future primary adapter falls back to the lexical router. Metrics record only the failure class, never exception text that may contain private endpoints or payloads. Cancellation remains cancellation and is not relabeled as fallback success.

### `EVAL-SEM-006` — duplicate and identity handling

Duplicate candidate IDs are counted and collapsed deterministically. Blank candidate IDs are invalid.

### `EVAL-SEM-007` — serialization

Requests and results round-trip through `kotlinx.serialization` with stable defaults.

## Negative and mutation controls

The suite must fail if a mutation:

- ranks ties by input order rather than stable ID;
- scores an expired or context-mismatched candidate;
- treats an unknown current fingerprint as a match;
- exceeds candidate, character, or result budgets;
- swallows cancellation as success;
- leaks exception messages into metrics;
- emits duplicate routes for one candidate ID;
- allows an invalid threshold or negative result limit;
- introduces an execution-authority field into `SemanticRoute`.

The last control is architectural: semantic routing outputs candidate IDs, scores, terms, and source only. It cannot enable a capability, change dispatcher state, execute an action, or override privacy and anchor-freshness policy.

## Reproduction

The owning common test entrypoint is:

```bash
./gradlew :composeApp:allTests
```

Shared CI also runs:

```bash
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:wasmJsBrowserDistribution
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

The full matrix must be bound to the exact branch head. An earlier green commit cannot satisfy the current subject.

## Evidence boundary

This baseline can prove:

- portable request/result contracts;
- deterministic Unicode-token lexical ranking;
- fixed-corpus output;
- stale-context fail-closed behavior;
- explicit operation budgets;
- fallback behavior that preserves cancellation and redacts failure detail.

It cannot prove:

- physical-device latency, memory, package size, thermal behavior, or battery use;
- embedding quality, multilingual product quality, or model safety;
- artifact target compatibility or dependency licensing for a future engine;
- a local model is shippable on Android or iOS;
- cache persistence, OpenClaw transport, projection rendering, action authorization, or store readiness.

Those states remain `NOT_EXERCISED` or `NOT_IMPLEMENTED` until their owning Stack slices emit subject-bound evidence.
