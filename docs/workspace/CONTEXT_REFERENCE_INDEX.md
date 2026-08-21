# Conversation Context Reference Backfill

Owner: `ed3c/kotlin-auto-webview#132`  
Cross-repository audit: `ed3c/ai-content-notes#61/#63`  
Machine shard: [`reference-index.public.context.json`](reference-index.public.context.json)

## Purpose

This shard captures material **public primary sources** that appeared in prior architecture/engineering conversations but were not guaranteed to exist in the original URL registry.

```text
conversation mention
→ primary URL resolved
→ stable REF-* bound
→ authority/source class
→ consuming Issue/decision | NO_CURRENT_CONSUMER
→ later claim/revision/evidence closure
```

Conversation text explains why a source matters. It never proves what the source says.

## Control-plane and engineering references

| REF | Source | URL |
|---|---|---|
| REF-0012 | enterprise_agent_system | https://github.com/ed3c/enterprise_agent_system |
| REF-0701 | Android WebView overview | https://developer.android.com/develop/ui/views/layout/webapps/webview |
| REF-0702 | Kotlin documentation | https://kotlinlang.org/docs/home.html |
| REF-0703 | Gradle User Manual | https://docs.gradle.org/current/userguide/ |
| REF-0704 | MCP specification 2026-07-28 | https://modelcontextprotocol.io/specification/2026-07-28 |
| REF-0705 | OpenAI API quickstart | https://platform.openai.com/docs/quickstart |
| REF-0706 | OpenAI API reference | https://platform.openai.com/docs/api-reference/introduction |
| REF-0707 | Claude Code getting started | https://docs.anthropic.com/en/docs/claude-code/getting-started |
| REF-0708 | Gemini API get started | https://ai.google.dev/gemini-api/docs/get-started |
| REF-0709 | GitHub Actions reference | https://docs.github.com/en/actions/reference |
| REF-0710 | Cursor quickstart | https://docs.cursor.com/en/get-started/quickstart |

## Product/design/source-grounding references

| REF | Source | URL |
|---|---|---|
| REF-0711 | AWS Working Backwards / PRFAQ guidance | https://docs.aws.amazon.com/wellarchitected/latest/devops-guidance/oa.ti.6-prioritize-customer-needs-to-deliver-optimal-business-outcomes.html |
| REF-0712 | IBM Enterprise Design Thinking | https://www.ibm.com/design/approach/design-thinking/ |
| REF-0713 | NotebookLM overview | https://support.google.com/notebooklm/answer/16164461 |
| REF-0714 | ReAct | https://arxiv.org/abs/2210.03629 |
| REF-0715 | Toolformer | https://arxiv.org/abs/2302.04761 |
| REF-0716 | SWE-bench | https://github.com/SWE-bench/SWE-bench |
| REF-0717 | SWE-agent | https://github.com/SWE-agent/SWE-agent |
| REF-0718 | Retrofit | https://github.com/square/retrofit |
| REF-0719 | OkHttp | https://github.com/square/okhttp |

## Traceability boundary

These entries currently prove `URL_INDEXED` or a versioned paper/spec identity only. They do not automatically prove:

- current mutable policy or documentation freshness;
- dependency/license admission;
- that a conversation claim is supported;
- that a repository uses the technology;
- implementation, runtime, legal/store, or user/market closure.

The semantic closure owner is `ai-content-notes#61/#64`. Private conversation/Google/CodexDoc provenance remains in the private registry and is never copied here.
