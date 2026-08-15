# Web deployment evidence gate

- Issue: #5
- Branch: `release/web-deployment-evidence`
- Parent: `docs/agent-integration-stack-index`
- Scope: GitHub Pages workflow and deployment evidence only

## Current boundary

The Compose Wasm production distribution is already built by repository CI. This slice adds subject-bound deployment checks without changing repository Pages settings, DNS, permissions, visibility, or production promotion authority.

## State machine

```text
SOURCE_HEAD
  -> BUILD_WASM
  -> VALIDATE_ARTIFACT
  -> UPLOAD_PAGES_ARTIFACT
  -> DEPLOY_REQUESTED
  -> DEPLOYED_URL
  -> VERIFY_HTTPS
  -> VERIFY_WASM_MIME
  -> RECORD_CACHE_HEADERS
  -> WEB_EVIDENCE_READY
```

Failure of build, single-module artifact validation, deployment, HTTPS retrieval, non-empty Wasm retrieval, or `application/wasm` MIME is terminal for that workflow run.

Pages repository settings, custom DNS, immutable-cache infrastructure, browser compatibility, accessibility, same-origin/CSP behavior, and production promotion remain independent evidence gates.

## Positive evidence

The deployment workflow records:

- exact GitHub Actions source revision;
- SHA-256 for generated `index.html` and the top-level Wasm module before upload;
- GitHub Pages deployment URL from `actions/deploy-pages`;
- successful HTTPS retrieval of the deployed root;
- successful retrieval of the exact generated Wasm basename;
- `Content-Type: application/wasm`;
- observed root and Wasm `Cache-Control` headers.

## Negative controls

- HTTP deployment URLs fail.
- Missing or multiple top-level Wasm modules fail artifact admission.
- Empty Wasm bytes fail.
- Incorrect Wasm MIME fails.
- The smoke verifier refuses path-bearing or traversal-like Wasm names.
- Cache headers are observed but are not called immutable unless an explicit `REQUIRE_IMMUTABLE_CACHE=true` gate passes.
- A CI build artifact is not reported as a deployed site.
- A deployed site is not reported as browser/accessibility/CSP/store evidence.

## External authority

The following remain `EXTERNAL_AUTHORITY_REQUIRED` or `NOT_EXERCISED` under the autonomous safety envelope:

- enabling/changing Pages repository settings;
- DNS/custom-domain changes;
- repository permission changes;
- browser matrix and assistive-technology execution;
- production promotion and rollback.
