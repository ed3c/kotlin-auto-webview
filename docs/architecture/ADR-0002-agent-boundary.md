# ADR-0002 — Agent proposals are not executable authority

**Status:** Accepted

## Decision

MCP tools return or create typed proposals. `CapabilityRegistry` validates identity, enablement, permissions, and risk ceiling. `LocalDispatcher` then applies temporal rules and Human-in-the-loop. The WebView executor is intentionally absent from MCP handlers.

## Why

Directly mapping model output to JavaScript is prompt-injection-prone and makes audit impossible. Typed actions make the policy surface finite and testable.
