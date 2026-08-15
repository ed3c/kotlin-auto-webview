# ADR-0009: Agent Provider Compatibility Plane

- Status: proposed
- Issue: #23
- Parent: `feat/openclaw-stream-contract`
- Scope: portable provider modeling and authority admission only

## Context

The application started with an OpenClaw-oriented private-edge stream. The integration must also interoperate with NousResearch Hermes Agent and NVIDIA NemoClaw without turning provider identity into application authority.

Upstream observations are pinned to public repository subjects used for this decision:

| Upstream | Observed subject | Direct license | Relevant observed boundary |
|---|---|---|---|
| NousResearch/hermes-agent | `77be513de1da24610ebe8d1d4848228578c6bdf3` | MIT | MCP integration, messaging gateway, OpenAI-compatible API, agentskills.io-compatible skills |
| NVIDIA/NemoClaw | `815bdd563ce7c90f1144e6bdef3471a6f95b45c6` | Apache-2.0 | NemoClaw orchestrates OpenShell; Hermes is a supported sandboxed agent; managed MCP is authenticated HTTPS Streamable HTTP |

No upstream source code is copied. The compatibility layer is original repository code based on documented protocol and architectural boundaries.

## Decision

Introduce a provider-neutral compatibility plane under `providers/`.

```text
External agent/control plane
        |
        v
AgentProviderProfile
        |
        v
AgentProviderRegistry.admit()
        |
        +--> protocol capability check
        +--> provider-role check
        +--> remote authority ceiling
        |
        v
local application boundary
Privacy -> Capability Policy -> Dispatcher -> HITL -> Executor
```

Provider discovery and protocol compatibility never grant direct browser or native execution.

### OpenClaw

OpenClaw remains an `AGENT_RUNTIME` with `ORDERED_PRIVATE_STREAM`. The existing pairing, sequence, replay, expiry, context-fingerprint, and bounded-buffer state machine remains the owning transport admission path. This ADR does not invent an upstream OpenClaw commit when one was not independently observed for this slice; its `observedUpstreamCommit` remains `null`.

### Hermes Agent

Hermes is modeled as an `AGENT_RUNTIME`. For this application, the primary compatibility direction is MCP-first: Hermes can consume MCP servers while the KMP application already owns a transport-independent MCP JSON-RPC gateway. Hermes also advertises messaging and OpenAI-compatible API surfaces, but those are compatibility/discovery capabilities rather than execution authority.

The current repository does **not** yet claim a real Hermes MCP session. An HTTP/stdio transport adapter and subject-bound interoperability receipt are required before that evidence can become `PASS`.

### NVIDIA NemoClaw

NemoClaw is modeled as `SANDBOX_CONTROL_PLANE`, not as an application action executor. It can orchestrate OpenShell sandbox lifecycle, policy, managed inference, and managed MCP for supported agents such as Hermes. Therefore its application authority ceiling is `NONE`.

A NemoClaw sandbox may host an agent that later connects to this application through an admitted protocol, but sandbox creation or managed inference success cannot itself authorize KMP actions.

## State machine

`SM-PROVIDER-001`

```text
UNKNOWN
  -> DISCOVERED
  -> PROTOCOL_CHECKED
      -> REJECTED_UNSUPPORTED_PROTOCOL
      -> AUTHORITY_CHECKED
          -> REJECTED_DIRECT_EXECUTION
          -> REJECTED_CONTROL_PLANE_AUTHORITY
          -> REJECTED_AUTHORITY_CEILING
          -> COMPATIBILITY_ADMITTED
```

`COMPATIBILITY_ADMITTED` means only that the provider profile can participate in the named protocol and authority level. It is not runtime connectivity, authentication, tool permission, sandbox security, or production readiness.

## Invariants

### INV-PROVIDER-001 — Provider identity is not authority

Provider name, upstream reputation, discovery metadata, sandbox status, or protocol connection cannot enable a capability.

### INV-PROVIDER-002 — Direct remote execution is forbidden

Every provider profile and request fails closed if it requests `DIRECT_EXECUTION`.

### INV-PROVIDER-003 — Control planes own no application action authority

A `SANDBOX_CONTROL_PLANE` profile must have authority ceiling `NONE`.

### INV-PROVIDER-004 — Local HITL remains mandatory

All profiles require the local HITL boundary. Remote proposals remain proposals.

### INV-PROVIDER-005 — Evidence cannot be invented

Upstream commit identity may be absent. Missing identity is `null`/ABSENT, never a fake SHA.

## Data flows

### DF-PROVIDER-001 — Hermes MCP path

```text
Hermes Agent
  -> MCP client transport (NOT_IMPLEMENTED here)
  -> KMP MCP gateway
  -> typed resource/tool proposal
  -> local capability policy
  -> dispatcher/HITL
  -> bounded executor
```

### DF-PROVIDER-002 — NemoClaw envelope path

```text
NemoClaw host control plane
  -> OpenShell sandbox/policy/inference/MCP management
  -> hosted Hermes or another supported agent
  -> admitted application protocol
  -> KMP local authority boundary
```

NemoClaw itself does not skip the hosted-agent/application protocol boundary.

### DF-PROVIDER-003 — OpenClaw private stream

```text
OpenClaw private edge
  -> wss transport
  -> paired stream admission
  -> projection candidate / typed action proposal
  -> local policy/HITL
```

## Negative controls

- Unknown provider -> reject.
- Unsupported protocol requirement -> reject.
- `DIRECT_EXECUTION` -> reject for every provider.
- NemoClaw + non-`NONE` application authority -> reject.
- Hermes MCP discovery cannot satisfy a NemoClaw managed-MCP capability.
- A missing upstream commit remains missing rather than using a placeholder SHA.
- No secret, endpoint credential, sandbox credential, or upstream code is stored by these profiles.

## Evidence boundary

This ADR and its tests can prove only provider-role modeling, protocol compatibility decisions, authority ceilings, serialization, and negative controls on the exact repository subject.

The following remain `NOT_IMPLEMENTED` or `NOT_EXERCISED` in this slice:

- real Hermes MCP interoperability;
- an HTTPS Streamable HTTP transport for the KMP MCP gateway;
- NemoClaw/OpenShell sandbox creation;
- NemoClaw managed MCP interoperability;
- managed inference routing;
- network-policy verification;
- physical-device interoperability;
- provider credentials or hardware-backed key custody;
- production deployment, merge, or store delivery.
