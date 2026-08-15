# Web release runbook

Build the production Wasm distribution:

```bash
./gradlew :composeApp:wasmJsBrowserDistribution
```

Publish `composeApp/build/dist/wasmJs/productionExecutable` to a static host with HTTPS and correct `.wasm` MIME type. GitHub Pages deployment is defined in `.github/workflows/pages.yml`.

Arbitrary websites may reject iframe embedding through CSP or `X-Frame-Options`. The production web product should favor app-owned content, explicit `postMessage` integrations, or open external navigation rather than weakening browser security.
