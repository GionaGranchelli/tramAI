# MCP Governance Boundary

> **Status:** Design boundary — defines what MCP support should and should not mean before any connector/runtime implementation.
> **Phase:** Phase 6 — Tool and MCP Governance of the [Post-Sovereignty Roadmap](../POST-SOVEREIGNTY-ROADMAP.md).
> **Prerequisites:** [Security Model](SECURITY-MODEL.md), [Tool Permission Model](tool-permission-model.md).

---

## Purpose

This document defines the governance boundary for future MCP (Model Context Protocol) support in TramAI. It answers:

- When TramAI eventually supports MCP, what is governed by TramAI?
- What remains the MCP server/client responsibility?
- How are MCP tools classified within the tool permission model?
- How are tokens, audiences, and permissions handled?
- What must not be claimed before runtime MCP support exists?

The security model already anchors this discussion: abuse scenario AS-10 states that MCP token passthrough must be rejected, authorization must validate resource audience, and each MCP tool needs scoped permissions.

---

## Scope

This document covers the governance boundary for future MCP-connected tools in TramAI. It does **not** cover:

- MCP transport protocol design or implementation
- MCP server or client runtime code
- Token exchange or credential management
- MCP resource or prompt semantics
- MCP server discovery or health checking

Those are implementation concerns for a future connector PR. This document defines only what TramAI should govern.

---

## Current Status

> **TramAI does not currently implement an MCP connector, MCP client, MCP server, MCP transport, or MCP runtime adapter.**

This document is a design boundary for future MCP support. No MCP tools can be registered, invoked, or governed today. Nothing documented here constitutes a runtime guarantee.

---

## MCP Trust Boundary

When MCP support is introduced, the trust boundary looks like this:

```
TramAI workflow
  ↓
TramAI policy engine (governs tool exposure, approval, audit)
  ↓
MCP connector boundary (TramAI-controlled, not yet implemented)
  ↓
MCP server (external, managed by the server operator)
  ↓
MCP tool / resource
```

**TramAI can govern:**

| Concern | How |
|---------|-----|
| Which MCP servers are trusted | Explicit server allowlist, configured by the application |
| Which MCP tools are exposed to workflows | Per-server, per-tool allowlist |
| Which MCP tools require human approval | Default approval-required categories (financial, destructive, etc.) |
| Which tokens and audiences are allowed | Audience validation, no passthrough |
| What audit and evidence is emitted | Policy decisions, tool permission events, digest-verifiable records |

**TramAI must not claim it can automatically prove:**

| Non-claim | Rationale |
|-----------|-----------|
| MCP server safety | TramAI cannot audit arbitrary external server code or configuration |
| Tool correctness | An MCP tool's behaviour is owned by the server, not by TramAI |
| External server identity without configured verification | Identity proof requires explicit trust anchors (TLS certs, signed claims) |
| Compliance | Governance controls support compliance processes; they do not constitute certification |
| Production readiness | Requires independent operational validation |
| Zero data leakage from arbitrary MCP servers | A malicious or compromised server can exfiltrate data sent to it |

---

## MCP Tool Classification

MCP tools map into the [Tool Permission Model](tool-permission-model.md) as follows:

| MCP concept | Trust class | Risk class |
|-------------|-------------|------------|
| MCP server connection | External trust boundary | N/A |
| MCP tool | `MCP_REMOTE` | Depends on declared/configured capability |
| MCP tool that reads data | `MCP_REMOTE` + `DATA_ACCESS` | `READ_ONLY` |
| MCP tool that creates or updates records | `MCP_REMOTE` + `STATE_CHANGING` | `WRITE` |
| MCP tool that deletes records | `MCP_REMOTE` + `HIGH_IMPACT` | `DESTRUCTIVE` |
| MCP tool that moves money | `MCP_REMOTE` + `HIGH_IMPACT` | `FINANCIAL` |
| MCP tool that produces legal or medical advice | `MCP_REMOTE` + `HIGH_IMPACT` | `LEGAL_MEDICAL` |
| Unknown or unlisted MCP tool | `MCP_REMOTE` | **`DENY` by default** |

The tool permission model already defines `MCP_REMOTE` as a future connector trust class. Server identity and tool claims feed into permission decisions, but the classification above defines the default posture: MCP tools are deny-by-default, with explicit allowlisting required for exposure and approval required for high-impact categories.

---

## Token and Audience Rules

These rules extend the security model's AS-10 (Token Passthrough via MCP) with concrete governance requirements:

| # | Rule | Requirement |
|---|------|-------------|
| 1 | No token passthrough by default | Tokens issued for one audience must not be reused for another resource or server. Cross-audience passthrough is rejected. |
| 2 | Audience validation required | Every MCP tool invocation must validate that the token audience matches the target MCP server or resource identifier. |
| 3 | Least privilege | MCP tool tokens should be scoped per server, per tool, and per action where the token provider supports fine-grained scoping. |
| 4 | No raw token logging | Tokens must never appear in audit records, evidence artifacts, logs, or error messages. Only token digests are recorded. |
| 5 | Explicit allowlist | MCP servers and tools must be explicitly configured in the application. There is no auto-discovery path that bypasses allowlisting. |
| 6 | Approval for high-impact MCP tools | MCP tools classified as `HIGH_IMPACT` (financial, destructive, legal/medical) require human approval before execution. |

These rules are governance requirements, not runtime implementation. A future MCP connector PR must implement or enforce each rule.

---

## Permission Decision Mapping

MCP tool permission decisions use the same vocabulary as the tool permission model:

| Decision | MCP meaning | Status |
|----------|-------------|--------|
| `ALLOW` | MCP tool may be exposed and invoked | Current runtime policy vocabulary |
| `DENY` | MCP tool must not be exposed or invoked | Current runtime policy vocabulary |
| `REQUIRE_APPROVAL` | MCP tool requires human approval before execution | Current runtime policy vocabulary |
| `REDACT_RESULT` | MCP tool result must be filtered before returning to the caller | Future — not yet implemented |
| `ALLOW_INTERNAL_ONLY` | MCP tool allowed only inside the sovereign/local boundary | Future — not yet implemented |

The split between current and future decisions matches #188. Only `ALLOW`, `DENY`, and `REQUIRE_APPROVAL` are currently available at runtime. `REDACT_RESULT` and `ALLOW_INTERNAL_ONLY` are design boundaries reserved for future implementation.

---

## Server Identity and Tool Claims

When an MCP server registers tools with a TramAI MCP connector (future), the following identity and claim model applies:

1. **Server identity** is established through configured trust anchors (TLS certificates, signed metadata, or explicit endpoint allowlisting). TramAI does not trust self-claimed server identity without verification.
2. **Tool claims** are the metadata an MCP server provides about its tools: name, description, input schema, and declared capabilities. These claims inform classification but are **not authoritative** — TramAI applies its own classification based on configured rules.
3. **Claim discrepancies** (e.g., a server claiming a tool is read-only when it mutates state) are treated as server/operator responsibility. TramAI does not rely on server declarations alone — configured classifications, deny-by-default policy, approval gates, and audit provide defence-in-depth.
4. **Server provenance** (who operates the server, what data it has access to) is outside TramAI's governance scope. TramAI can enforce what tools are exposed and under what conditions, but it cannot verify what the server actually does with data sent to it.

---

## Audit and Evidence Expectations

Each MCP tool permission decision should produce an audit event following the same `runtime-evidence.v1` schema defined in Phase 5:

| Field | Expected value for MCP tool events |
|-------|-----------------------------------|
| `eventType` | `tool.permission` |
| `source.component` | `policy-engine` |
| `decision.kind` | `ALLOW`, `DENY`, or `REQUIRE_APPROVAL` |
| `metadata` | Tool name, MCP server identifier (digest), trust class (`MCP_REMOTE`), risk class, operation identifier |

MCP tool audit events are **not implemented** as part of this document. They are reserved for a future tool audit event PR.

The [Runtime Evidence Bundle Map](../evidence/runtime-evidence-bundle-map.md) may later include a `tool-permissions.jsonl` section for tool permission events, including MCP tool decisions. That update is a separate future PR.

---

## Non-Goals

This document does **not**:

- Define an MCP transport protocol, wire format, or connection lifecycle.
- Implement MCP client, server, or connector code.
- Add token exchange, credential management, or cryptographic identity verification.
- Modify runtime policy enforcement.
- Add new Kotlin APIs or configuration types.
- Implement tool audit events or evidence export for MCP tools.
- Define compliance, certification, or production-readiness criteria.

---

## Future Implementation Plan

| PR | Scope | Status |
|----|-------|--------|
| #189 | Define MCP governance boundary (this document) | ✅ Documented |
| TBD | Implement MCP connector: transport, server registration, tool discovery | Pending |
| TBD | Implement tool audit events (including MCP tool permission events) | Pending |
| TBD | Implement MCP token audience validation | Pending |
| TBD | Add MCP governance examples and integration tests | Pending |

---

## Where to Look Next

| Topic | Link |
|-------|------|
| Tool Permission Model | [Tool Permission Model](tool-permission-model.md) |
| Security Model | [SECURITY-MODEL.md](SECURITY-MODEL.md) |
| Runtime Evidence Bundle Map | [Bundle Map](../evidence/runtime-evidence-bundle-map.md) |
| Runtime Evidence Export Model | [Evidence Export Model](../evidence/runtime-evidence-export-model.md) |
| Post-Sovereignty Roadmap | [Roadmap](../POST-SOVEREIGNTY-ROADMAP.md) |
