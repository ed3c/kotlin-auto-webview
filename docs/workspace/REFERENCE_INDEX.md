# Capability Workspace Reference URL Index

Owner: `ed3c/kotlin-auto-webview#129`  
Machine index: [`reference-index.public.json`](reference-index.public.json)  
Private/full locator registry: `ed3c/ai-content-notes#56` / parent `#51`

## Purpose

This is the public, privacy-safe URL/provenance index for important websites, official platform documents, public repositories, technology candidates and research sources used by the Federated Capability Workspace.

```text
URL / repository / official document
→ stable REF-* identity
→ role + authority class
→ issue / requirement usage
→ future revision/digest read-back
→ claim / requirement / capability / implementation / evidence trace
```

A URL is a locator, **not** proof of claim truth, current freshness, legal rights, implementation or runtime behavior.

## Privacy split

`kotlin-auto-webview` is public. Therefore:

```text
PUBLIC URL
→ full URL may appear here

PRIVATE GOOGLE DOC/SHEET/DRIVE OR PRIVATE REPOSITORY
→ full URL stays in private ai-content-notes registry
→ this repository stores only an opaque REF-* ID
```

Never put Drive file IDs, private repository URLs, customer/private source URLs, credentials, OAuth/session tokens or signed bearer URLs into this public registry.

## Public ed3c repositories

| REF | Source | URL | Role |
|---|---|---|---|
| REF-0001 | kotlin-auto-webview | https://github.com/ed3c/kotlin-auto-webview | Experience / routing center |
| REF-0002 | truth-verify-loop | https://github.com/ed3c/truth-verify-loop | Claim verification |
| REF-0003 | Skill.md-native | https://github.com/ed3c/Skill.md-native | Independent Skill qualification |
| REF-0004 | blackbox-auto-research | https://github.com/ed3c/blackbox-auto-research | Experiment/outcome evidence |
| REF-0005 | agent-skills-repo | https://github.com/ed3c/agent-skills-repo | Skill research/qualification history |
| REF-0006 | paid-content-create | https://github.com/ed3c/paid-content-create | Creator outcome/product lane |
| REF-0007 | openwiki-source-anchoring | https://github.com/ed3c/openwiki-source-anchoring | Source anchoring reference |

Private authority repositories are indexed as `REF-1101`–`REF-1106` only in this public file; their real URLs are stored in the private registry.

## Android / WebView official references

| REF | Source | URL |
|---|---|---|
| REF-0101 | WebViewCompat | https://developer.android.com/reference/androidx/webkit/WebViewCompat |
| REF-0102 | WebViewFeature | https://developer.android.com/reference/androidx/webkit/WebViewFeature |
| REF-0103 | WebSettingsCompat | https://developer.android.com/reference/androidx/webkit/WebSettingsCompat |
| REF-0104 | WebView Media Integrity config | https://developer.android.com/reference/androidx/webkit/WebViewMediaIntegrityApiStatusConfig |
| REF-0105 | NavigationListener | https://developer.android.com/reference/androidx/webkit/NavigationListener |
| REF-0106 | WebView Profile | https://developer.android.com/reference/androidx/webkit/Profile |
| REF-0107 | Jetpack WebKit overview | https://developer.android.com/develop/ui/views/layout/webapps/jetpack-webkit-overview |
| REF-0108 | WebView renderer termination | https://developer.android.com/develop/ui/views/layout/webapps/handle-termination |
| REF-0109 | Android Picture in Picture | https://developer.android.com/develop/ui/compose/system/picture-in-picture |
| REF-0110 | Jetpack PDF viewer | https://developer.android.com/develop/ui/views/layout/pdf/pdf-viewer |
| REF-0111 | Sign in with Google / Credential Manager | https://developer.android.com/identity/sign-in/credential-manager-siwg |
| REF-0112 | JavaScriptExecutionWorld | https://developer.android.com/reference/androidx/webkit/JavaScriptExecutionWorld |
| REF-0113 | WebView Media Integrity design announcement | https://android-developers.googleblog.com/2023/11/increasing-trust-for-embedded-media.html |

## YouTube / Google identity / Google Workspace

| REF | Source | URL |
|---|---|---|
| REF-0201 | YouTube IFrame Player API | https://developers.google.com/youtube/iframe_api_reference |
| REF-0202 | YouTube API Services Developer Policies Guide | https://developers.google.com/youtube/terms/developer-policies-guide |
| REF-0203 | YouTube Data API | https://developers.google.com/youtube/v3/docs |
| REF-0204 | YouTube captions.list | https://developers.google.com/youtube/v3/docs/captions/list |
| REF-0205 | YouTube API authentication | https://developers.google.com/youtube/documentation/authentication |
| REF-0206 | YouTube Clips | https://support.google.com/youtube/answer/10332730 |
| REF-0207 | YouTube Creative Commons | https://support.google.com/youtube/answer/2797468 |
| REF-0208 | Premium troubleshooting on Android | https://support.google.com/youtube/answer/7437519?co=GENIE.Platform%3DAndroid&hl=en |
| REF-0209 | YouTube Premium benefits | https://support.google.com/youtube/answer/6308116?hl=en |
| REF-0210 | OAuth 2.0 for native apps | https://developers.google.com/identity/protocols/oauth2/native-app |
| REF-0211 | Google Docs batchUpdate | https://developers.google.com/workspace/docs/api/how-tos/batch |

## Apple / X / Notion / model-provider / copyright references

| REF | Source | URL |
|---|---|---|
| REF-0301 | WKWebView | https://developer.apple.com/documentation/webkit/wkwebview |
| REF-0302 | App Store Review Guidelines | https://developer.apple.com/app-store/review/guidelines/ |
| REF-0303 | UIScreen isCaptured | https://developer.apple.com/documentation/uikit/uiscreen/iscaptured |
| REF-0304 | AVKit Picture in Picture | https://developer.apple.com/documentation/avkit/adopting_picture_in_picture_in_a_custom_player |
| REF-0401 | X automation rules | https://help.x.com/en/rules-and-policies/x-automation |
| REF-0402 | Notion workspace-owner data access | https://www.notion.com/help/data-accessible-by-your-workspace-owner |
| REF-0403 | OpenAI data-use policy | https://openai.com/policies/how-your-data-is-used-to-improve-model-performance/ |
| REF-0404 | Gemini API usage policies | https://ai.google.dev/gemini-api/docs/usage-policies |
| REF-0405 | Anthropic model-training data policy | https://privacy.anthropic.com/en/articles/7996868-is-my-data-used-for-model-training |
| REF-0406 | U.S. Copyright Office fair use | https://www.copyright.gov/fair-use/more-info.html |

## Technology candidates

These are candidates only. The URL index does not admit them into production. Exact version, source digest, direct and transitive license, model/data/service/content terms, target-platform support and runtime evidence remain separate gates.

| REF | Repository | URL | Intended role |
|---|---|---|---|
| REF-0501 | Temporal | https://github.com/temporalio/temporal | Durable workflows |
| REF-0502 | Docling | https://github.com/docling-project/docling | Rich document parsing |
| REF-0503 | Apache Tika | https://github.com/apache/tika | Metadata/text fallback |
| REF-0504 | Readium Kotlin Toolkit | https://github.com/readium/kotlin-toolkit | EPUB/ebook adapter |
| REF-0505 | SQLDelight | https://github.com/sqldelight/sqldelight | KMP local structured state |
| REF-0506 | LanceDB | https://github.com/lancedb/lancedb | Embedded retrieval candidate |
| REF-0507 | Qdrant | https://github.com/qdrant/qdrant | Dedicated vector service candidate |
| REF-0508 | Open Policy Agent | https://github.com/open-policy-agent/opa | External policy-engine candidate |
| REF-0509 | OpenTelemetry Java | https://github.com/open-telemetry/opentelemetry-java | Trace/receipt correlation |
| REF-0510 | Playwright | https://github.com/microsoft/playwright | Browser E2E harness |
| REF-0511 | Maestro | https://github.com/mobile-dev-inc/Maestro | Mobile E2E harness |
| REF-0512 | Google API Java Client | https://github.com/googleapis/google-api-java-client | Google API integration candidate |
| REF-0513 | PostgreSQL | https://github.com/postgres/postgres | Central relational graph candidate |
| REF-0514 | pgvector | https://github.com/pgvector/pgvector | Postgres semantic retrieval candidate |

## Agent Skills research references

| REF | Source | URL |
|---|---|---|
| REF-0601 | Agent Skills for Large Language Models survey | https://arxiv.org/abs/2602.12670 |
| REF-0602 | Agent Skills specification site | https://agentskills.io/home |
| REF-0603 | Microsoft Agent Framework Skills | https://learn.microsoft.com/en-us/agent-framework/agents/skills |
| REF-0604 | Google Antigravity Agent Skills | https://antigravity.google/docs/skills |
| REF-0605 | Addy Osmani agent-skills | https://github.com/addyosmani/agent-skills |
| REF-0606 | Vercel Labs agent-skills | https://github.com/vercel-labs/agent-skills |
| REF-0607 | Cisco AI Defense skill-scanner | https://github.com/cisco-ai-defense/skill-scanner |
| REF-0608 | Snyk agent-scan | https://github.com/snyk/agent-scan |

## Private reference bindings

The following identities intentionally expose no URL in this public repository:

```text
REF-1001..REF-1007  private Google Docs/Sheets/Drive research sources
REF-1101..REF-1106  private domain/control/runtime repositories
```

The full locator map is owned by private `ed3c/ai-content-notes#56`, under parent source-registry work `#51`.

## Traceability lifecycle

```text
URL_INDEXED
→ IDENTITY_RESOLVED
→ REVISION_BOUND
→ RIGHTS_AND_COMPLETENESS_REVIEWED
→ SNAPSHOT_OR_READBACK_VERIFIED
→ CLAIM/REQUIREMENT LINKS BOUND
→ USED_BY_IMPLEMENTATION
→ EVIDENCE_RECEIPT
→ CURRENT | STALE | SUPERSEDED | REVOKED
```

This initial index establishes only `URL_INDEXED` unless another exact artifact already proves a stronger state.

## Rules for future references

1. Every material external source gets a stable `REF-*` ID before being relied upon by a requirement or architecture decision.
2. Never replace a stable `REF-*` ID because a URL changes; add revision/supersession history.
3. Duplicate titles are legal; file/repo/document identity must remain distinct.
4. Official policy pages are preferred over secondary summaries for mutable platform claims.
5. Public repo license metadata is discovery only; dependency admission still reads exact license/notice files.
6. Private URLs remain private even when their derived requirement is public.
7. A source deletion/revocation changes freshness/availability and propagates to downstream evidence; it does not erase history.
