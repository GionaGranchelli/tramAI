# Tool Permission Model

> **Status:** Design boundary with implemented evidence — defines the trust and risk taxonomy for governed tool use in TramAI. The dedicated `tool.permission` runtime evidence family is implemented (PR #200).
> **Phase:** Phase 6 — Tool and MCP Governance of the [Post-Sovereignty Roadmap](../POST-SOVEREIGNTY-ROADMAP.md).
> **Prerequisites:** [Security Model](SECURITY-MODEL.md), [Runtime Evidence Export Model](../evidence/runtime-evidence-export-model.md).

---

## Purpose

This document defines TramAI's tool permission model: which tools are trusted, which require human approval, which must be denied by policy, and what evidence should be emitted when a tool permission decision is made.

The security model already states:

- Every tool invocation passes through policy enforcement (trust boundary rule #2).
- Tools are deny-by-default (abuse scenario AS-02).
- High-risk tools require human approval gates (abuse scenario AS-04).

This document provides the concrete vocabulary — trust classes, risk classes, permission decisions, approval categories, and enforcement points — that those security-model statements need.

---

## Scope

This model covers:

- Built-in TramAI tools (date/time, deterministic utilities)
- Application-defined tools (business logic, document classifiers)
- External API tools (HTTP endpoints, SaaS integrations)
- Data access tools (database lookups, file reads)
- State-changing tools (writes, record creation)
- High-impact tools (money movement, account closure)
- Future MCP-connected tools (remote tools exposed through MCP connectors)

It does **not** cover:

- Model inference routing (that is the provider routing model, covered in Phase 5)
- Structured output validation (contract enforcement, not trust)
- Workflow-level policy gates (already covered in the governed workflow quickstart)
- Approval lifecycle semantics (already covered in the approval ergonomics guide)

---

## Definitions

| Term | Definition |
|------|------------|
| **Tool** | A named, registered function that an AI operation or workflow step may invoke. Tools are resolved through the `ToolRegistry` and exposed via `@Operation(... tools = [...])`. |
| **Tool trust class** | A broad category describing where the tool runs and who controls it (INTERNAL, APPLICATION, EXTERNAL_API, etc.). |
| **Tool risk class** | A broad category describing what the tool can affect (READ_ONLY, WRITE, DESTRUCTIVE, FINANCIAL, LEGAL_MEDICAL, PRIVILEGED). |
| **Permission decision** | The outcome of tool permission evaluation: ALLOW, DENY, REQUIRE_APPROVAL, REDACT_RESULT, or ALLOW_INTERNAL_ONLY. |
| **Policy enforcement point** | The point in the tool lifecycle where permission is checked. TramAI already enforces at `BEFORE_TOOL_EXPOSURE`. |
| **Deny-by-default** | A tool that is not explicitly allowed is denied. This is already the security model default. |

---

## Tool Trust Classes

Trust classes describe where a tool runs and who controls it. A tool may belong to multiple trust classes — for example, an `EXTERNAL_API` tool that also changes state is both `EXTERNAL_API` and `STATE_CHANGING`.

| Class | Meaning | Example |
|-------|---------|---------|
| `INTERNAL` | In-process and controlled by the application. No network, no external side effects. | Date/time, deterministic calculator, string utilities |
| `APPLICATION` | Business application tool inside the organization boundary. May access internal services. | Customer lookup, document classifier, policy evaluation |
| `EXTERNAL_API` | Calls a network service outside the process. May cross organization boundary. | HTTP API call, SaaS endpoint, remote service invocation |
| `DATA_ACCESS` | Reads or queries sensitive data stores. May expose PII, trade secrets, or classified data. | Database lookup, file retrieval, document search |
| `STATE_CHANGING` | Mutates state. Creates, updates, or deletes records. | Write record, update ticket, send notification |
| `HIGH_IMPACT` | Can affect money, legal, medical, identity, access, or deletion. Irreversible or high-consequence. | Payment execution, account closure, legal document generation |
| `MCP_REMOTE` | Tool exposed through a future MCP connector. Trust depends on MCP server identity and tool claims. | Remote MCP tool from an external MCP server |

These are documentation categories, not Kotlin enums. Future implementation PRs may encode them as runtime types, but this document only defines the vocabulary.

---

## Tool Risk Classes

Risk classes complement trust classes by describing what the tool **can affect**:

| Class | Meaning | Default permission |
|-------|---------|--------------------|
| `READ_ONLY` | Does not modify state. Observes or queries only. | `ALLOW` (subject to trust class) |
| `WRITE` | Creates or updates records. Reversible but auditable. | `REQUIRE_APPROVAL` or explicit policy `ALLOW` |
| `DESTRUCTIVE` | Deletes records, closes accounts, or executes irreversible actions. | `REQUIRE_APPROVAL` |
| `FINANCIAL` | Moves money, executes payments, or modifies financial records. | `REQUIRE_APPROVAL` |
| `LEGAL_MEDICAL` | Produces legal or medical decision support. | `REQUIRE_APPROVAL` |
| `PRIVILEGED` | Changes system configuration, policy, or access controls. | `REQUIRE_APPROVAL` |

Risk classes are additive with trust classes. An `INTERNAL` + `FINANCIAL` tool is still `REQUIRE_APPROVAL` by default because the risk class dominates.

---

## Permission Decisions

### Current Runtime Policy Decisions

The current `PolicyDecision` sealed type supports three outcomes:

| Decision | Meaning |
|----------|---------|
| `ALLOW` | Tool may be exposed and invoked. |
| `DENY` | Tool must not be exposed or invoked. The operation receives a policy rejection. |
| `REQUIRE_APPROVAL` | Tool requires human approval before execution. The workflow suspends until approval is received. |

These are already implemented and used by the policy decision evidence exporter (#184) and the tool permission evidence exporter (#200).

### Future Decision Extensions

The following decisions are defined here as a design boundary for future tool governance:

| Decision | Meaning | Status |
|----------|---------|--------|
| `REDACT_RESULT` | Tool may be invoked, but its result must be filtered or redacted before returning to the caller. | Future — not yet implemented |
| `ALLOW_INTERNAL_ONLY` | Tool may run only inside the sovereign/local boundary. If the deployment is not sovereign, the tool is denied. | Future — not yet implemented |

These decisions require future implementation. The `tool.permission` evidence exporter (PR #200) actively filters them out, so `REDACT_RESULT` and `ALLOW_INTERNAL_ONLY` records never appear in runtime evidence.

---

## Approval Requirements

The following tool categories should require human approval by default:

| Category | Default |
|----------|---------|
| Money movement (payment, refund, transfer) | `REQUIRE_APPROVAL` |
| Account closure or deletion | `REQUIRE_APPROVAL` |
| Legal, medical, or financial decision support | `REQUIRE_APPROVAL` |
| External data transfer (sending data outside the organization boundary) | `REQUIRE_APPROVAL` or explicit policy `ALLOW` |
| Persistent write (creating or updating records) | `REQUIRE_APPROVAL` or explicit policy `ALLOW` |
| Privileged admin action (changing policy, configuration, or access controls) | `REQUIRE_APPROVAL` |
| Low-risk `INTERNAL` utility tools (date/time, calculator, string utils) | `ALLOW` (no approval needed) |

This aligns with the security model's abuse scenario AS-04: high-risk action without human approval.

An explicit policy rule can override any default — for example, a policy that allows low-value payments (< €50) without approval. The defaults listed here are the **starting point**, not hard-coded rules.

---

## Policy Enforcement Points

### Current Runtime Behavior

TramAI already enforces tool permission at `BEFORE_TOOL_EXPOSURE`:

1. The workflow engine iterates the operation's tool definitions.
2. Each tool is resolved through the `ToolRegistry`.
3. The tool's `toolName` and `toolSecurity` are sent to the policy engine.
4. The `PolicyEnforcementHelper.enforce(...)` evaluates the tool against the active policy rules.
5. The policy returns a decision (`ALLOW`, `DENY`, or `REQUIRE_APPROVAL`).
6. The engine acts on the decision:
   - `ALLOW` → tool is exposed to the operation.
   - `DENY` → operation receives a `PolicyViolationException`.
   - `REQUIRE_APPROVAL` → `ApprovalRequiredException` is thrown; the approval continuation/suspension workflow is only implemented at `BEFORE_TOOL_EXECUTION`.

Additional enforcement points are already active in the engine, each with distinct `REQUIRE_APPROVAL` routing:

| Enforcement point | Evaluation method | `REQUIRE_APPROVAL` behavior |
|-------------------|-------------------|------------------------------|
| `BEFORE_TOOL_EXPOSURE` | `enforce(...)` | Throws `ApprovalRequiredException`; no continuation is persisted |
| `BEFORE_TOOL_EXECUTION` | `evaluate(...)` | Uses approval suspension/resume workflow |
| `BEFORE_TOOL_RESULT_REINJECTION` | `enforce(...)` | Throws `ApprovalRequiredException`; no reinjection-specific suspension workflow |

All three points support `ALLOW` and `DENY` in addition to `REQUIRE_APPROVAL`.

### Future Decision Extensions

The following decisions are **not yet implemented** and should not be assumed as runtime behavior:

| Decision | Status |
|----------|--------|
| `REDACT_RESULT` | Future — result filtering before reinjection or return |
| `ALLOW_INTERNAL_ONLY` | Future — tool allowed only inside sovereign/local boundary |

---

## Audit and Evidence Expectations

Each tool permission decision produces an audit event and is exported as dedicated `tool.permission` runtime evidence. The runtime evidence model (Phase 5) defines the record shape for runtime decisions — tool permission events follow the `runtime-evidence.v1` schema:

| Field | Expected value |
|-------|---------------|
| `eventType` | `tool.permission` |
| `source.component` | `policy-engine` |
| `decision.kind` | `ALLOW`, `DENY`, or `REQUIRE_APPROVAL` |
| `metadata` | `toolName` (required), `enforcementPoint` (required), `riskLevel`, `classification`, `classificationSource` |

Tool enforcement events (`BEFORE_TOOL_EXPOSURE`, `BEFORE_TOOL_EXECUTION`, `BEFORE_TOOL_RESULT_REINJECTION`) are partitioned into the `tool.permission` family and excluded from generic `policy.decision` evidence. The `ToolPermissionRuntimeEvidenceExporter` (PR #200) writes to `runtime-evidence/tool-permissions.jsonl` in the evidence bundle.

The [Runtime Evidence Bundle Map](../evidence/runtime-evidence-bundle-map.md) covers all four runtime evidence families, including `tool-permissions.jsonl`.

---

## MCP Relationship

The [MCP governance boundary](mcp-governance-boundary.md) document defines how future MCP-connected tools fit into this model. These principles are a design boundary and are not yet implemented:

- MCP tools are `MCP_REMOTE` by trust class.
- The MCP server's identity and tool claims inform permission decisions.
- MCP tools are deny-by-default — explicit allowlisting is required.
- MCP tool permission decisions follow the same `ALLOW`/`DENY`/`REQUIRE_APPROVAL` model as built-in tools.
- MCP tool permissions are scoped per server, per tool, per audience (token audience validation is already in the security model, abuse scenario AS-10).

The tool permission model is intentionally broader than MCP. Documenting tool governance first avoids defining MCP-specific safety rules before the general TramAI tool boundary is clear.

---

## Non-Claims

- This model defines a vocabulary and decision taxonomy. It does not implement runtime enforcement beyond what is described.
- It does not add new Kotlin enums, policy engine behavior, or MCP connector support.
- It does not implement MCP connector support, runtime tool registration, or tool result filtering.
- Default approval requirements are design guidance, not runtime defaults.
- No production-readiness, compliance, or certification claims are made.

---

## Future Implementation Plan

| PR | Scope | Status |
|----|-------|--------|
|| #188 | Define tool permission model (this document) | ✅ Documented |
|| #189 | Define MCP governance boundary | ✅ Documented |
|| #190 | Verify tool exposure policy audit | ✅ Verified |
|| #191 | Verify tool execution denial and generic evidence | ✅ Verified |
|| #200 | Add dedicated tool.permission evidence family and tool-permissions.jsonl | ✅ Implemented |
|| TBD | Add tool governance examples: usage guide with deny/approval scenarios | 🔧 Pending |

Implementation PRs should reference this model. Tool audit events should follow the runtime-evidence.v1 record shape defined in Phase 5.

---

## Where to Look Next

| Topic | Link |
|-------|------|
| Security Model | [SECURITY-MODEL.md](SECURITY-MODEL.md) |
| Runtime Evidence Export Model | [Evidence Export Model](../evidence/runtime-evidence-export-model.md) |
| Runtime Evidence Bundle Map | [Bundle Map](../evidence/runtime-evidence-bundle-map.md) |
| Approval Failure Taxonomy | [Approval Failure Taxonomy](../guides/approval-failure-taxonomy.md) |
| Governed Workflow Quickstart | [Quickstart](../guides/governed-workflow-quickstart.md) |
| Post-Sovereignty Roadmap | [Roadmap](../POST-SOVEREIGNTY-ROADMAP.md) |
