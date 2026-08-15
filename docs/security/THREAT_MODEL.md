# Threat model

## Protected assets

- Page text and selection
- Authentication/session material
- Device capabilities and local data
- MCP tool authority
- Semantic cache and audit history

## Primary threats and controls

| Threat | Control in this repository | Remaining work |
|---|---|---|
| Prompt injection asks model to click/pay/delete | typed capabilities, deny-by-default registry, risk ceiling, HITL | signed policy bundles and executor sandbox |
| Password/payment values leak through DOM | JS excludes sensitive inputs; Kotlin redacts again | site-specific privacy tests |
| User and agent race for UI authority | pointer input preempts dispatcher | platform-level gesture telemetry and cancellation token |
| Malicious/untrusted MCP peer | no default network listener; server factory only | mutual authentication, origin allowlist, rate limits |
| Context replay/stale projection | cache id + timestamp + relevance | expiry policy and L2 sequence numbers |
| Arbitrary website blocks injection or iframe | explicit failure/fallback; no bypass | first-party site adapters |
| Supply-chain compromise | pinned dependency versions and checksum-pinned Gradle distribution | dependency verification metadata, SBOM, signed releases |

## Non-goals

This MVP does not attempt to bypass CSP, anti-bot controls, site permissions, App Store policy, or browser origin security.
