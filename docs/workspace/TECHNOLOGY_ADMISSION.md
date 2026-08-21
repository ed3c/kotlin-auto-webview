# Technology admission for the Federated Capability Workspace

This is a candidate/admission map, not a dependency lock. Exact version, transitive dependencies, source provenance, legal notice obligations, target compatibility and security evidence must still be bound by the owning implementation issue.

## Immediate candidates

| Need | Candidate | Observed repository license | Intended role | Admission state |
|---|---|---|---|---|
| KMP structured local state | `sqldelight/sqldelight` | Apache-2.0 | W1 local subject graph/outbox | CANDIDATE |
| Google APIs | `googleapis/google-api-java-client` | Apache-2.0 | W3 Drive/Docs/Sheets access where platform source set permits | CANDIDATE |
| rich document parsing | `docling-project/docling` | MIT | Desktop/service PDF/DOCX/HTML extraction adapter, not KMP core | CANDIDATE_EXTERNAL_ADAPTER |
| broad metadata/text extraction | `apache/tika` | Apache-2.0 | fallback file parser/metadata service | CANDIDATE_EXTERNAL_ADAPTER |
| EPUB/ebook | `readium/kotlin-toolkit` | BSD-3-Clause | source-specific reader/locator candidate | CANDIDATE |
| browser test harness | `microsoft/playwright` | Apache-2.0 | Desktop/Web integration evidence | CANDIDATE_TEST_ONLY |
| mobile E2E | `mobile-dev-inc/Maestro` | Apache-2.0 | Android/iOS external test carrier | CANDIDATE_TEST_ONLY |
| observability | `open-telemetry/opentelemetry-java` | Apache-2.0 | correlation/latency/state telemetry; never raw private payloads | CANDIDATE |

## Scale-triggered candidates

| Need | Candidate | Observed license | Trigger |
|---|---|---|---|
| durable multi-step orchestration | `temporalio/temporal` | MIT | only when Bettor/local outbox cannot satisfy proven long-running/retry/recovery needs |
| local embedded semantic retrieval | `lancedb/lancedb` | Apache-2.0 | large local corpus with measured retrieval requirement |
| dedicated vector service | `qdrant/qdrant` | Apache-2.0 | multi-user/server scale after local/relational path is insufficient |
| external policy engine | `open-policy-agent/opa` | Apache-2.0 | only after policy surface becomes multi-service/multi-tenant and sealed Kotlin contracts become a bottleneck |

Do not introduce LanceDB and Qdrant simultaneously before a measured use case establishes the storage topology. Do not introduce Temporal just because workflows are long in diagrams; first prove recovery/idempotency requirements cannot be served by existing Bettor/LoopX plus W1 outbox.

## Rights model

Every technology candidate has independent rights dimensions:

```text
CODE_LICENSE
TRANSITIVE_DEPENDENCY_RIGHTS
MODEL_WEIGHT_RIGHTS
DATASET_RIGHTS
TRAJECTORY_RIGHTS
HOSTED_SERVICE_TERMS
PLATFORM_API_TERMS
SOURCE_CONTENT_RIGHTS
TRADEMARK / VOICE / LIKENESS / THIRD_PARTY_MEDIA
```

A permissive code license satisfies only `CODE_LICENSE` for the exact code subject. It cannot authorize hosted APIs, model weights, source articles, YouTube media, PDFs, customer data or downstream third-party assets.

## Required technology receipt

Before an implementation issue may report dependency admission, record:

```yaml
candidate:
  repository:
  exact_commit_or_release:
  source_tree_digest:
  license_spdx_or_exact_license_ref:
  license_blob_digest:
  transitive_lock_or_sbom:
  supported_targets:
  intended_usage:
  data_egress:
  notices_required:
  security_review_state:
  rollback_subject:
```

A GitHub repository metadata `license` field is discovery evidence, not the final legal receipt. The owning implementation must read the exact license file and dependency graph of the selected version.

## Architecture preferences

1. Keep KAW common code provider-neutral; platform/service adapters sit behind ports.
2. Prefer existing repository mechanisms before adding infrastructure: `bettor-arena` for orchestration, `runtime-env` for runtime contracts, `truth-verify-loop` for current-claim closure, `Skill.md-native` for Skill verification.
3. Use SQLDelight for local authoritative *projection state*, not global truth.
4. Keep Google API credentials outside committed configuration; W3 consumes an authorized execution-plane identity.
5. Use OpenTelemetry attributes only for public/sanitized subject IDs, hashes, state labels and timing. Do not emit raw source content, private URLs, user emails or provider tokens.
6. Document parser output is observation data and remains subject to the source's rights/destination admission.

## Shadow Architect rejection examples

- selecting a tool because stars/popularity imply suitability;
- treating an Apache/MIT/BSD code license as permission to ingest arbitrary content;
- adding a vector DB before a retrieval benchmark or corpus-size requirement exists;
- adding Temporal before durable-workflow failure modes are observed;
- adding OPA while one sealed local policy reducer is still simpler and testable;
- using a hosted edition whose service terms/data region are not separately admitted;
- committing dependency version without an exact lock/provenance and rollback path.
