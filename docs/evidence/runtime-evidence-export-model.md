# Runtime Evidence Export Model

> **Status:** Design boundary — defines the shape and claim limits for exporting runtime decisions into evidence artifacts.
> **Phase:** Phase 5 — Runtime Evidence Export of the [Post-Sovereignty Roadmap](../POST-SOVEREIGNTY-ROADMAP.md).
> **Prerequisites:** Familiarity with the [Sovereign Lab Evidence Chain](../../examples/sovereign-lab/EVIDENCE-CHAIN.md), the [Approval Failure Taxonomy](../guides/approval-failure-taxonomy.md), and the [Governed Workflow Quickstart](../guides/governed-workflow-quickstart.md).

---

## What This Model Covers

This document defines the evidence shape for exporting TramAI runtime decisions into reviewable, digest-verifiable artifacts. It covers three event families, all implemented:

1. **Policy decisions** — allow/deny/require-approval outcomes produced by policy evaluation at runtime.
2. **Approval decisions** — approve/deny outcomes produced by the approval decision control plane.
3. **Provider routing decisions** — selected/fallback/blocked routes produced by the provider router at runtime.

The model defines the record shape, bundle placement, verification semantics, and privacy/sanitisation rules. All three exporters, the `RuntimeEvidenceBundleWriter`, and the bundle verifier are implemented.

---

## Runtime Evidence Record

Every exported runtime event follows a common record shape:

```json
{
  "schemaVersion": "runtime-evidence.v1",
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "policy.decision",
  "workflowRunId": "wf-claim-123",
  "correlationId": "corr-claim-123",
  "actor": "policy-engine",
  "createdAt": "2026-07-08T12:00:00Z",
  "source": {
    "component": "policy-engine",
    "module": "claim-triage-policy"
  },
  "decision": {
    "kind": "ALLOW",
    "reasonCode": "local-route-allowed"
  },
  "digests": {
    "subjectDigest": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "payloadDigest": "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
  },
  "metadata": {
    "classification": "RESTRICTED",
    "riskLevel": "HIGH",
    "providerName": "local-llama"
  }
}
```

### Field Constraints

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `schemaVersion` | Yes | `string` | Must be `"runtime-evidence.v1"`. The verifier rejects unknown versions. |
| `eventId` | Yes | `string` (UUID) | Unique identifier for the event. Must be globally unique across all event families. |
| `eventType` | Yes | `string` | One of the defined event family types. |
| `workflowRunId` | No | `string\|null` | Present when the decision is scoped to a workflow run. |
| `correlationId` | No | `string\|null` | Correlation identifier for cross-referencing. |
| `actor` | No | `string\|null` | Identity of the component or human that produced the decision. |
| `createdAt` | Yes | `string` (ISO-8601) | Timestamp of the decision. Validated with actual datetime parsing. |
| `source.component` | Yes | `string` | The component that produced the event. |
| `source.module` | No | `string\|null` | Optional sub-module or policy name. |
| `decision.kind` | Yes | `string` | The decision outcome. See event families below. |
| `decision.reasonCode` | No | `string\|null` | Sanitised reason code (no raw prompts, secrets, or medical details). |
| `digests.subjectDigest` | Yes | `string` | SHA-256 digest of the subject identifier. Must match `^sha256:[0-9a-f]{64}$`. |
| `digests.payloadDigest` | Yes | `string` | SHA-256 digest of the decision payload. Must match `^sha256:[0-9a-f]{64}$`. |
| `metadata` | No | `object` | Allowlisted key-value metadata. Must not contain raw prompts, tool arguments, secrets, or unbounded model output. |

### Excluded Fields

The following must never appear in a runtime evidence record:

- Raw prompts or model inputs
- Raw model output or tool call responses
- Secrets, credentials, API keys, tokens
- Medical details, PII, or other sensitive raw data
- Approval tokens, resume tokens, or replay envelopes
- Unbounded free-text comments (only `reasonCode` with allowlisted values is permitted)

---

## Event Families

### A. Policy Decisions

Policy decisions are produced by the policy evaluation engine when a tool or action is evaluated against the active policy rules.

| Field | Value |
|-------|-------|
| `eventType` | `"policy.decision"` |
| `source.component` | `"policy-engine"` |
| `decision.kind` | One of: `ALLOW`, `DENY`, `REQUIRE_APPROVAL` |

The current policy audit emitter records decision strings (`ALLOW`, `DENY`, `REQUIRE_APPROVAL`), sanitised `reasonCode` values, and allowlisted metadata: `providerName`, `modelName`, `toolName`, `classification`, `classificationSource`, `riskLevel`, `fallbackProviderName`, plus explicit safe attributes such as `cacheReuse` and `fallbackReason`. Unknown attributes, including prompts, tool arguments, and secrets, are dropped.

**Source:** The existing policy audit behaviour in `tramai-security` produces these decisions at runtime. See the policy audit emitter for the current event shape.

### B. Approval Decisions

Approval decisions are produced by the `ApprovalDecisionControlPlane` when an operator or automation approves or denies a pending approval.

| Field | Value |
|-------|-------|
| `eventType` | `"approval.decision"` |
| `source.component` | `"approval-control-plane"` |
| `decision.kind` | One of: `APPROVED`, `DENIED` |

The existing approval decision control plane creates outbox records with event keys `approval-approved.<id>` and `approval-denied.<id>`, capturing actor, workflow run ID, correlation ID, approval status/version, and digest/length metadata. The raw decision comment is never stored — only its digest and length.

**Source:** The outbox records produced by `SovereignOpsApprovalDecisionControlPlane` (see [approval decision evidence tests](../../tramai-spring-boot-starter-sovereign-persistence-jdbc/src/test/kotlin/dev/tramai/spring/sovereign/persistence/jdbc/JdbcSovereignOpsApprovalDecisionControlPlaneTest.kt)).

### C. Provider Routing Decisions (Implemented)

Provider routing decisions are produced when the provider resolution layer selects, falls back, or blocks a route to a model provider. All three decision kinds are exported by `ProviderRoutingRuntimeEvidenceExporter`.

| Field | Value |
|-------|-------|
| `eventType` | `"provider.route"` |
| `source.component` | `"provider-router"` |
| `decision.kind` | `SELECTED`, `FALLBACK`, `BLOCKED` |

---

## Evidence Bundle Placement

Runtime evidence records are placed in the sovereign evidence bundle under a `runtime-evidence/` directory by `RuntimeEvidenceBundleWriter`:

```
runtime-evidence/
  policy-decisions.jsonl     # Newline-delimited JSON policy decision records
  approval-decisions.jsonl   # Newline-delimited JSON approval decision records
  provider-routing.jsonl     # Newline-delimited JSON provider routing records
```

A human-readable summary could also be generated:

```
runtime-decisions.md   # Human-readable summary of runtime decisions
```

**JSONL** (newline-delimited JSON) is recommended for machine-verifiable runtime events: each line is a complete JSON record, making the file streamable and appendable. The human-readable `.md` parallel is optional and can be generated from the JSONL data.

No sub-manifest is placed under `runtime-evidence/`. The root `manifest.json` at the bundle level records the file digests of every artifact, including runtime evidence files.

---

## Verification Semantics

### What the Verifier Should Check

- Each JSONL line is valid JSON.
- Each record matches the `runtime-evidence.v1` schema version.
- Required fields (`schemaVersion`, `eventId`, `eventType`, `createdAt`, `source.component`, `decision.kind`, `digests.subjectDigest`, `digests.payloadDigest`) are present and non-null.
- `eventType` is one of the known event family types.
- `decision.kind` is valid for the given `eventType`.
- Digest strings match `^sha256:[0-9a-f]{64}$`.
- Records do not contain forbidden raw fields (prompts, secrets, tokens, raw comments).
- The evidence bundle manifest includes the expected `runtime-evidence/` files.
- File digests in the bundle manifest match the actual file contents.

### What the Verifier Must NOT Claim

- The decision was correct (legally, medically, financially, or otherwise).
- The policy was legally valid or compliant.
- The human reviewer was qualified or had sufficient context.
- The model output was true or accurate.
- The system is production-ready.
- The evidence proves EU AI Act or any regulatory compliance.
- The evidence chain replaces an audit.

This matches the existing evidence-chain boundary documented in the [Sovereign Lab Evidence Chain](../../examples/sovereign-lab/EVIDENCE-CHAIN.md): the verifier checks manifests, paths, sizes, digests, and claim boundaries, but does **not** verify evidence truth, compliance, production readiness, security certification, or audit sufficiency.

---

## Privacy and Sanitisation

All runtime evidence records must follow the same sanitisation rules already applied by the existing policy audit emitter and approval decision control plane:

- **No raw prompts:** The model input that triggered the decision is never included.
- **No raw tool arguments:** The arguments passed to a gated tool are never included.
- **No secrets or credentials:** API keys, tokens, and credentials are never included.
- **No raw decision comments:** Approval decision comments are represented by digest and length only.
- **No medical details or PII:** Subject identifiers are digests; reason codes are allowlisted.
- **Allowlisted metadata only:** Only explicitly configured metadata keys are exported.

These rules are not optional. A verifier that encounters raw sensitive data in a runtime evidence record should reject the record and flag a sanitisation violation.

---

## Non-Claims

- Evidence records prove that a decision was recorded with a specific outcome. They do not prove the decision was correct, compliant, or sufficiently reviewed.
- Bundle artifacts are local, reviewable evidence artifacts — they are not production deployments, compliance submissions, or certification materials.
- The bundle verifier checks structural integrity and digest consistency. It does not verify correctness of recorded decisions.

---

## Future Implementation Plan

The following PRs (in order) will implement the runtime evidence export defined by this model:

| PR | Scope | Status |
|----|-------|--------|
| #184 | Implement policy decision exporter: capture `policy.decision` events during workflow execution and write to `policy-decisions.jsonl` | ✅ Implemented |
| #185 | Implement approval decision exporter: capture `approval.decision` events from the outbox store and write to `approval-decisions.jsonl` | ✅ Implemented |
| #186 | Implement provider routing exporter: capture `provider.route` events | ✅ Implemented |
| #187 | Map runtime events to evidence bundle sections | ✅ Documented |
| #199 | Add verifier rules for runtime evidence records | ✅ Implemented |
| #199 | Integrate runtime evidence into the sovereign lab evidence bundle lifecycle | ✅ Implemented |

All three exporters and the bundle writer/verifier are implemented. No further PRs are required for the core runtime evidence lifecycle.

Automatic background evidence exporting and scheduled polling of approval outboxes remain unimplemented and are out of scope for 0.5.0.

---

## Where to Look Next

| Topic | Link |
|-------|------|
| Runtime Evidence Bundle Map | [Bundle Map](runtime-evidence-bundle-map.md) |
| Sovereign Lab Evidence Chain | [EVIDENCE-CHAIN.md](../../examples/sovereign-lab/EVIDENCE-CHAIN.md) |
| Approval Failure Taxonomy | [Failure Taxonomy](../guides/approval-failure-taxonomy.md) |
| Approval decision evidence tests | [JdbcSovereignOpsApprovalDecisionControlPlaneTest](../../tramai-spring-boot-starter-sovereign-persistence-jdbc/src/test/kotlin/dev/tramai/spring/sovereign/persistence/jdbc/JdbcSovereignOpsApprovalDecisionControlPlaneTest.kt) |
| Governed Workflow Troubleshooting | [Troubleshooting Guide](../guides/governed-workflow-troubleshooting.md) |
