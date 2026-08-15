# Dependency admission records

This directory owns repository-specific identity, target-variant, license, notice, telemetry/network, and evidence records for dependencies added by Stack PRs.

## Laws

- Use exact immutable versions; dynamic selectors and `latest` are forbidden.
- Prefer official repositories, release tags, Maven Central, Gradle Plugin Portal, and vendor documentation.
- Record source release identity separately from resolved artifact identity.
- Direct license, transitive/SBOM review, required notices, service terms, and organization approval remain separate states.
- Compilation or dependency resolution is not runtime correctness, security approval, legal approval, or release evidence.
- A dependency cannot introduce hidden authority, telemetry, post-install execution, or ambient-secret use.
- `LICENSE` meaning is immutable. `NOTICE` may receive additive attribution only when an admitted dependency requires or benefits from it.

## Records

- [`runtime-foundation.md`](runtime-foundation.md) — SQLDelight 2.3.2 and Ktor 3.5.1 admission for issues #7, #8, and #9.

## Evidence vocabulary

```text
PASS
FAIL
ABSENT
NOT_IMPLEMENTED
NOT_EXERCISED
SKIPPED_BY_POLICY
EXTERNAL_AUTHORITY_REQUIRED
```

A direct Apache-2.0 license review may be `PASS` while transitive/SBOM or organization legal acceptance remains `NOT_EXERCISED` or `EXTERNAL_AUTHORITY_REQUIRED`.
